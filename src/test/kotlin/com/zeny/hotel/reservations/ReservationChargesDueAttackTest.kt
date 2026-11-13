package com.zeny.hotel.reservations

import com.zeny.hotel.frontdesk.domain.StayCharges
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * Ataque a `ReservationsApi.chargesDue` (M6 E11, Task 5): estados, bordas de data, paridade com o check-out, taxa de
 * turismo configurada ou nula e chaves sem dado pessoal. Banco compartilhado, sem avançar a data; a taxa de turismo do
 * hotel volta ao valor original no `finally`.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList")
class ReservationChargesDueAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    @Test
    fun `a no-show and an unknown reservation have no charges due`() {
        val (type, _) = inventory.roomTypeWithRooms(2, today)
        val plan = planFor(type)
        val noShow = created(type, plan, today, today.plusDays(1))
        jdbc
            .sql("UPDATE reservations.reservation SET status = 'NO_SHOW', provisional_until = NULL WHERE id = :id")
            .param("id", noShow)
            .update()
        val confirmed = created(type, plan, today, today.plusDays(1))

        reservationsApi.chargesDue(ReservationId(noShow), today.plusDays(5)).shouldBeNull()
        reservationsApi.chargesDue(ReservationId(confirmed), today.plusDays(5)).shouldBeNull()
        reservationsApi.chargesDue(ReservationId(UUID.randomUUID()), today.plusDays(5)).shouldBeNull()
    }

    @Test
    fun `the arrival night equals the check-out rule for the same reservation`() {
        withTourismFee(null) {
            val (type, rooms) = inventory.roomTypeWithRooms(2, today)
            val plan = planFor(type, packages = true)
            val id = created(type, plan, today, today.plusDays(2))
            support.forceInHouse(id, rooms[0])
            val summary = reservationsApi.reservation(ReservationId(id))!!

            val due = reservationsApi.chargesDue(ReservationId(id), today)!!.charges
            val checkOut = StayCharges.pending(summary, today)

            checkOut shouldHaveSize 2
            due.map { Triple(it.sourceReference, it.amount, it.includedInRate) } shouldBe
                checkOut.map { Triple(it.sourceReference, it.amount, it.includedInRate) }
        }
    }

    @Test
    fun `a configured tourism fee is charged per adult and night, a null one is not`() {
        val (type, rooms) = inventory.roomTypeWithRooms(2, today)
        val plan = planFor(type)
        val id = created(type, plan, today, today.plusDays(2), adults = 3)
        support.forceInHouse(id, rooms[0])

        withTourismFee("3.33") {
            val due = reservationsApi.chargesDue(ReservationId(id), today.plusDays(1))!!.charges

            val fees = due.filter { it.kind == NightChargeKind.TOURISM_FEE }
            fees.map { it.date } shouldBe listOf(today, today.plusDays(1))
            fees.forEach { it.amount.amount.toPlainString() shouldBe "9.99" }
        }
        withTourismFee(null) {
            reservationsApi
                .chargesDue(ReservationId(id), today.plusDays(1))!!
                .charges
                .none { it.kind == NightChargeKind.TOURISM_FEE } shouldBe true
        }
    }

    @Test
    fun `a date past the departure does not charge beyond the last frozen night`() {
        withTourismFee("2.00") {
            val (type, rooms) = inventory.roomTypeWithRooms(2, today)
            val plan = planFor(type)
            val id = created(type, plan, today, today.plusDays(2))
            support.forceInHouse(id, rooms[0])

            val far = reservationsApi.chargesDue(ReservationId(id), today.plusDays(30))!!.charges
            val last = reservationsApi.chargesDue(ReservationId(id), today.plusDays(1))!!.charges

            far.map { it.date }.distinct() shouldBe listOf(today, today.plusDays(1))
            far.map { it.sourceReference } shouldBe last.map { it.sourceReference }
        }
    }

    @Test
    fun `the first night only is due when the date is the arrival, and the call is repeatable`() {
        val (type, rooms) = inventory.roomTypeWithRooms(2, today)
        val plan = planFor(type)
        val id = created(type, plan, today, today.plusDays(3))
        support.forceInHouse(id, rooms[0])

        val first = reservationsApi.chargesDue(ReservationId(id), today)!!.charges
        val again = reservationsApi.chargesDue(ReservationId(id), today)!!.charges

        first.map { it.date }.distinct() shouldBe listOf(today)
        first.map { it.sourceReference } shouldBe again.map { it.sourceReference }
        reservationsApi.chargesDue(ReservationId(id), today.minusDays(1))!!.charges.shouldBeEmpty()
    }

    @Test
    fun `a day use in house charges its only night without the tourism fee`() {
        withTourismFee("5.00") {
            val (type, rooms) = inventory.roomTypeWithRooms(2, today)
            val plan = planFor(type)
            val id = created(type, plan, today, today)
            support.forceInHouse(id, rooms[0])

            val due = reservationsApi.chargesDue(ReservationId(id), today)!!.charges

            due.map { it.kind } shouldBe listOf(NightChargeKind.ROOM)
            reservationsApi.chargesDue(ReservationId(id), today.plusDays(3))!!.charges shouldHaveSize 1
        }
    }

    @Test
    fun `keys and descriptions of an in-house stay carry no personal data`() {
        withTourismFee("5.00") {
            val (type, rooms) = inventory.roomTypeWithRooms(2, today)
            val plan = planFor(type, packages = true)
            val id = created(type, plan, today, today.plusDays(2))
            support.forceInHouse(id, rooms[0])
            val uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

            val due = reservationsApi.chargesDue(ReservationId(id), today.plusDays(1))!!.charges

            due.forEach {
                it.sourceReference shouldMatch
                    Regex("reservations\\.reservation:$id:\\d{4}-\\d{2}-\\d{2}:(room|package:$uuid|tourism-fee)")
            }
        }
    }

    private fun withTourismFee(
        fee: String?,
        block: () -> Unit,
    ) {
        val path = "/api/v1/hotel/tax-settings"
        val original = with(iam) { call(GET, path, asAdmin()) }
        val before = with(iam) { json(original) }

        fun put(value: String?) =
            with(iam) {
                val etag = checkNotNull(call(GET, path, asAdmin()).getHeader("ETag"))
                call(
                    PUT,
                    path,
                    asAdmin(),
                    mapOf(
                        "serviceChargeRate" to before["serviceChargeRate"].asString(),
                        "issRate" to before["issRate"].asString(),
                        "tourismFeePerPersonNight" to value,
                    ),
                    ifMatch = etag,
                ).ok()
            }
        try {
            put(fee)
            block()
        } finally {
            put(before["tourismFeePerPersonNight"].takeUnless { it.isNull }?.asString())
        }
    }

    private fun planFor(
        type: UUID,
        packages: Boolean = false,
    ): UUID {
        val plan =
            if (packages) {
                support.plan(listOf(type), "NONE", packages = listOf(support.rates.createPackage()))
            } else {
                support.plan(listOf(type), "NONE")
            }
        support.setPrices(plan.id, listOf(type), today, today.plusDays(30))
        return plan.id
    }

    private fun created(
        type: UUID,
        plan: UUID,
        arrival: LocalDate,
        departure: LocalDate,
        adults: Int = 1,
        childAges: List<Int> = emptyList(),
    ): UUID =
        UUID.fromString(
            support
                .created(
                    support.booking(
                        support.guest(),
                        support.reservation(type, plan, arrival, departure, adults = adults, childAges = childAges),
                    ),
                )["reservations"][0]["id"]
                .asString(),
        )
}
