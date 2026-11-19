package com.zeny.hotel.reservations

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * A hora prevista de chegada em lote pela `ReservationsApi` (M6 E22, D6): só as reservas pedidas que têm hora; a sem
 * hora e a inexistente ficam de fora; a lista vazia dá o mapa vazio. Cada teste cria os próprios tipos de UH e reservas
 * (banco compartilhado, sem avançar a data operacional).
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList") // as portas que o teste usa
class ReservationsApiExpectedArrivalTimesTest(
    @Autowired iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    @Test
    fun `expected arrival times gives only the requested reservations that have a time`() {
        val (type, _) = inventory.roomTypeWithRooms(4, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(PRICED_DAYS))
        val morning = created(type, plan.id, "09:15")
        val withSeconds = created(type, plan.id, "14:30:45")
        val withoutTime = created(type, plan.id, null)
        val notRequested = created(type, plan.id, "11:00")
        val unknown = ReservationId(UuidV7.generate())

        val times = reservationsApi.expectedArrivalTimes(listOf(morning, withSeconds, withoutTime, unknown))

        times shouldBe mapOf(morning to LocalTime.of(9, 15), withSeconds to LocalTime.of(14, 30, 45))
        times.containsKey(notRequested) shouldBe false
    }

    @Test
    fun `expected arrival times of no reservation is empty`() {
        reservationsApi.expectedArrivalTimes(emptyList()).shouldBeEmpty()
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private fun created(
        type: UUID,
        plan: UUID,
        time: String?,
    ): ReservationId {
        val body =
            support.reservation(type, plan, today, today.plusDays(1), adults = 1) + ("expectedArrivalTime" to time)
        val reservation = support.created(support.booking(support.guest(), body))["reservations"][0]
        return ReservationId(UUID.fromString(reservation["id"].asString()))
    }

    private companion object {
        const val PRICED_DAYS = 10L
    }
}
