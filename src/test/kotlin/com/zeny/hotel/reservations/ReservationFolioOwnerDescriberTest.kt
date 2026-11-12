package com.zeny.hotel.reservations

import com.zeny.hotel.billing.FolioOwnerDescriber
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * O `reservations` descreve a reserva como dona da conta para o extrato (M5 E11, Task 12; D-E11-12): o rótulo com o
 * código, o titular da reserva agrupadora (nome social quando houver; o perfil corrente se mesclado), a UH alocada e
 * as datas; sem UH, sem número; reserva inexistente, `null`.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList")
class ReservationFolioOwnerDescriberTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val describers: List<FolioOwnerDescriber>,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val today: LocalDate get() = hotelApi.currentBusinessDate()
    private val describer get() = describers.single { it.ownerType == FolioOwnerType.RESERVATION }

    @Test
    fun `a reservation with a room is described by code, social name, room and dates`() {
        val suffix = suffix()
        val holder = guest("Civil Titular $suffix", "Social Titular $suffix")
        val (reservation, code, roomId) = reservation(holder, assignRoom = true)

        val description = describer.describe(reservation).shouldNotBeNullValue()

        description.label shouldBe "Reserva $code"
        description.guestName shouldBe "Social Titular $suffix"
        description.roomNumber shouldBe roomsApi.room(RoomId(roomId!!))!!.number
        description.arrival shouldBe today
        description.departure shouldBe today.plusDays(2)
    }

    @Test
    fun `a guest without a social name is described by the civil name`() {
        val suffix = suffix()
        val holder = guest("Civil Titular $suffix", null)

        describer
            .describe(reservation(holder, assignRoom = false).first)
            .shouldNotBeNullValue()
            .guestName shouldBe "Civil Titular $suffix"
    }

    @Test
    fun `a reservation without a room has no room number`() {
        val holder = guest("Civil Titular ${suffix()}", null)

        describer.describe(reservation(holder, assignRoom = false).first).shouldNotBeNullValue().roomNumber shouldBe
            null
    }

    @Test
    fun `a merged holder is described by the surviving profile`() {
        val suffix = suffix()
        val survivor = guest("Sobrevivente $suffix", null)
        val merged = guest("Duplicado $suffix", null)
        val (reservation, _, _) = reservation(merged, assignRoom = false)
        with(iam) {
            call(
                POST,
                "/api/v1/guests/$survivor/merge",
                asAdmin(),
                mapOf("mergedGuestId" to merged, "takeFromMerged" to emptyList<String>()),
                ifMatch = support.guests.etagOf("/api/v1/guests/$survivor"),
            ).ok()
        }

        val description = describer.describe(reservation).shouldNotBeNullValue()

        description.guestName shouldBe "Sobrevivente $suffix"
        description.toString() shouldNotContain "Sobrevivente"
    }

    @Test
    fun `an unknown reservation is null`() {
        describer.describe(UuidV7.generate()) shouldBe null
    }

    private fun suffix(): String = UuidV7.generate().toString().takeLast(SUFFIX_LENGTH)

    private fun guest(
        fullName: String,
        socialName: String?,
    ): UUID =
        UUID.fromString(
            support.guests
                .createGuest(
                    support.guests.createBody(
                        profile =
                            support.guests.profileBody(
                                extra = mapOf("fullName" to fullName, "socialName" to socialName),
                            ),
                        documents = emptyList(),
                    ),
                )["id"]
                .asString(),
        )

    private fun reservation(
        holder: UUID,
        assignRoom: Boolean,
    ): Triple<UUID, String, UUID?> {
        val (type, rooms) = support.inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(PRICED_DAYS))
        val created =
            support.created(
                support.booking(holder, support.reservation(type, plan.id, today, today.plusDays(2), adults = 1)),
            )["reservations"][0]
        val id = UUID.fromString(created["id"].asString())
        val room = rooms.single().takeIf { assignRoom }
        if (room != null) {
            with(iam) { call(POST, "/api/v1/reservations/$id/assign-room", asAdmin(), mapOf("roomId" to room)).ok() }
        }
        return Triple(id, created["code"].asString(), room)
    }

    private fun <T : Any> T?.shouldNotBeNullValue(): T = checkNotNull(this) { "descrição ausente" }

    private companion object {
        const val SUFFIX_LENGTH = 12
        const val PRICED_DAYS = 10L
    }
}
