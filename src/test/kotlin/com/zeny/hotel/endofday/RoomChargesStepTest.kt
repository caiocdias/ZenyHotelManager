package com.zeny.hotel.endofday

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.endofday.application.steps.RoomChargesStep
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.frontdesk.FrontdeskTestSupport
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A etapa real `room-charges` (M6 E11, 300): cada noite devida das hospedadas (a hospedagem sem os inclusos, os
 * pacotes e a taxa de turismo vigente, nunca no day use) lançada uma vez pela chave da noite (RN-03, RF-FEC-03), na
 * data que fecha e com origem `END_OF_DAY`; a repetição, a retomada e as corridas com o check-in e o check-out não
 * duplicam nada. Contexto das etapas reais no banco isolado; cada teste afirma só sobre os próprios dados, devolve a
 * taxa de turismo do hotel e termina com [EndOfDayTestRuns.settle].
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class)
@Suppress("LongParameterList", "LargeClass")
class RoomChargesStepTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired private val probe: BillingPostProbe,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired private val step: RoomChargesStep,
    @Autowired private val tx: TransactionTemplate,
    @Autowired roomsApi: RoomsApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory
    private val frontdesk = FrontdeskTestSupport(iam, support, hotel, roomsApi, jsonMapper)

    private class Stay(
        val id: UUID,
        val folio: UUID,
    )

    /** Um lançamento da conta, como o teste o confere. */
    private data class Line(
        val id: UUID,
        val key: String?,
        val code: String?,
        val amount: String,
        val businessDate: LocalDate,
        val source: String,
        val serviceChargeFor: UUID?,
    )

    @Test
    @Suppress("LongMethod")
    fun `in-house stays get the night, its packages apart and the tourism fee once per reservation and night`() {
        withTourismFee("5.00") {
            // Sem faixas de criança no hotel: um preço único de criança, de 0 à idade limite.
            support.prices.withChildBands(CHILD_MAX_AGE, emptyList()) {
                val start = hotel.currentBusinessDate()
                val (type, rooms) = inventory.roomTypeWithRooms(1, start)
                val includedCode = support.rates.createTransactionCode(serviceChargeApplies = true)
                val additionalCode = support.rates.createTransactionCode(serviceChargeApplies = true)
                val included = createPackage(includedCode, "30.00", included = true)
                val additional = createPackage(additionalCode, "20.00", included = false)
                val plan = support.plan(listOf(type), packages = listOf(included, additional))
                val childPrice = Triple(0, CHILD_MAX_AGE, "50.00")
                with(iam) {
                    support.prices
                        .update(
                            plan.id,
                            support.prices.selection(listOf(type), start, start.plusDays(10)),
                            support.prices.prices("300.00", "400.00", "120.00", listOf(childPrice)),
                        ).ok()
                }
                val stay = inHouse(type, plan.id, rooms[0], start, start.plusDays(3), adults = 2, childAges = listOf(8))
                try {
                    // A noite de D-1 já lançada pela chave: o fechamento de D-1 (o check-in foi em D-1).
                    EndOfDayTestRuns.closeCurrentDate(context)
                    val date = hotel.currentBusinessDate()
                    date shouldBe start.plusDays(1)
                    val before = lines(stay.folio)
                    keysOn(before, stay, start).size shouldBe 4

                    val run = EndOfDayTestRuns.closeCurrentDate(context)

                    statusOf(run) shouldBe "COMPLETED"
                    val after = lines(stay.folio)
                    val added = after.filterNot { it in before }
                    val night =
                        reservationsApi.reservation(ReservationId(stay.id))!!.nightlyRates.single { it.date == date }
                    val base = "reservations.reservation:${stay.id}:$date"
                    val keyed = added.filter { it.key != null }
                    keyed.map { Triple(it.key, it.code, it.amount) } shouldContainExactlyInAnyOrder
                        listOf(
                            Triple(
                                "$base:room",
                                "ROOM_CHARGE",
                                (night.roomAmount - night.includedPackagesAmount).amount.toPlainString(),
                            ),
                            Triple("$base:package:$included", null, "30.00"),
                            Triple("$base:package:$additional", null, "20.00"),
                            Triple("$base:tourism-fee", "TOURISM_FEE", "15.00"),
                        )
                    keyed.forEach {
                        it.businessDate shouldBe date
                        it.source shouldBe "END_OF_DAY"
                    }
                    // Só o adicional leva taxa de serviço (10% do código); a hospedagem e o incluso, zero.
                    val additionalLine = keyed.single { it.key == "$base:package:$additional" }
                    val serviceCharges = added.filter { it.serviceChargeFor != null }
                    serviceCharges.map { it.serviceChargeFor to it.amount } shouldBe listOf(additionalLine.id to "2.00")
                    serviceCharges.single().businessDate shouldBe date
                    added.size shouldBe keyed.size + 1
                    keysOn(after, stay, start) shouldBe keysOn(before, stay, start)
                } finally {
                    EndOfDayTestRuns.settle(context)
                }
            }
        }
    }

    @Test
    fun `a day use in house at the close is charged without tourism fee`() {
        withTourismFee("5.00") {
            val date = hotel.currentBusinessDate()
            val dayUse = frontdesk.stay().checkedIn(departure = date)
            try {
                val run = EndOfDayTestRuns.closeCurrentDate(context)

                statusOf(run) shouldBe "COMPLETED"
                val charged = lines(dayUse.folio)
                charged.map { Triple(it.key, it.code, it.amount) } shouldBe
                    listOf(Triple("reservations.reservation:${dayUse.id}:$date:room", "ROOM_CHARGE", "400.00"))
                charged.single().businessDate shouldBe date
                charged.single().source shouldBe "END_OF_DAY"
                roomChargeIssues(run, dayUse.id).shouldBeEmpty()
            } finally {
                EndOfDayTestRuns.settle(context)
            }
        }
    }

    @Test
    fun `repeating the step for the same date posts nothing new`() {
        withTourismFee("5.00") {
            val date = hotel.currentBusinessDate()
            val (type, rooms) = inventory.roomTypeWithRooms(1, date)
            val plan = pricedPlan(type, date)
            val stay = inHouse(type, plan, rooms[0], date, date.plusDays(2))
            try {
                val run = EndOfDayTestRuns.closeCurrentDate(context)
                statusOf(run) shouldBe "COMPLETED"
                val before = lines(stay.folio)
                keysOn(before, stay, date).size shouldBe 2

                val raised = mutableListOf<String?>()
                val outcome =
                    tx.execute { _ -> step.execute(EndOfDayStepContext(run, date) { _, ref, _ -> raised += ref }) }

                outcome shouldBe EndOfDayStepOutcome.DONE
                lines(stay.folio) shouldBe before
                raised.filter { it == stay.id.toString() }.shouldBeEmpty()
            } finally {
                EndOfDayTestRuns.settle(context)
            }
        }
    }

    @Test
    @Timeout(value = 180, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @Suppress("LongMethod")
    fun `a check-in made after the charges step is charged by the next close`() {
        withTourismFee(null) {
            val date = hotel.currentBusinessDate()
            val (type, rooms) = inventory.roomTypeWithRooms(2, date)
            val plan = pricedPlan(type, date)
            val (first, held) =
                listOf(
                    inHouse(type, plan, rooms[0], date, date.plusDays(3)),
                    inHouse(type, plan, rooms[1], date, date.plusDays(3)),
                ).sortedBy { it.id }
            // O check-in fica noutro tipo de UH, criado e feito com a etapa segurada (a confirmada com chegada D criada
            // antes do fechamento viraria no-show na etapa 100).
            val other = frontdesk.stay()
            val gate = CountDownLatch(1)
            probe.holdOn(FolioId(held.folio), gate)
            val pool = Executors.newFixedThreadPool(1)
            try {
                val running = pool.submit<EndOfDayRunId> { EndOfDayTestRuns.closeCurrentDate(context) }
                awaitRoomCharge(first, date)

                val late = other.checkedIn(departure = date.plusDays(2))
                lines(late.folio).shouldBeEmpty()
                gate.countDown()
                val run = running.get(60, TimeUnit.SECONDS)

                statusOf(run) shouldBe "COMPLETED"
                hotel.currentBusinessDate() shouldBe date.plusDays(1)
                lines(late.folio).shouldBeEmpty()
                keysOn(lines(held.folio), held, date).size shouldBe 1

                val next = EndOfDayTestRuns.closeCurrentDate(context)

                statusOf(next) shouldBe "COMPLETED"
                val charged = lines(late.folio)
                charged.map { it.key } shouldContainExactlyInAnyOrder
                    listOf(
                        "reservations.reservation:${late.id}:$date:room",
                        "reservations.reservation:${late.id}:${date.plusDays(1)}:room",
                    )
                charged.forEach {
                    it.businessDate shouldBe date.plusDays(1)
                    it.source shouldBe "END_OF_DAY"
                }
            } finally {
                gate.countDown()
                pool.shutdown()
                pool.awaitTermination(60, TimeUnit.SECONDS)
                EndOfDayTestRuns.settle(context)
            }
        }
    }

    @Test
    @Timeout(value = 180, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @Suppress("LongMethod")
    fun `a day use checked out between listing and posting is not charged twice`() {
        withTourismFee(null) {
            val date = hotel.currentBusinessDate()
            val (type, rooms) = inventory.roomTypeWithRooms(2, date)
            val plan = pricedPlan(type, date)
            // A segurada (e a anterior a ela, que mostra que a listagem já foi feita) criadas antes da day use: vêm
            // antes na ordem por id.
            val (first, held) =
                listOf(
                    inHouse(type, plan, rooms[0], date, date.plusDays(2)),
                    inHouse(type, plan, rooms[1], date, date.plusDays(2)),
                ).sortedBy { it.id }
            val dayUse = frontdesk.stay().checkedIn(departure = date)
            val cashier = frontdesk.cashier()
            val gate = CountDownLatch(1)
            probe.holdOn(FolioId(held.folio), gate)
            val pool = Executors.newFixedThreadPool(1)
            try {
                val running = pool.submit<EndOfDayRunId> { EndOfDayTestRuns.closeCurrentDate(context) }
                awaitRoomCharge(first, date)
                lines(dayUse.folio).shouldBeEmpty()

                frontdesk.pay(dayUse.folio, cashier, "400.00")
                checkOut(dayUse.id).expect(200)
                val checkedOut = lines(dayUse.folio)
                gate.countDown()
                val run = running.get(60, TimeUnit.SECONDS)

                statusOf(run) shouldBe "COMPLETED"
                support.status(dayUse.id) shouldBe "CHECKED_OUT"
                lines(dayUse.folio) shouldBe checkedOut
                checkedOut.mapNotNull { it.key } shouldBe listOf("reservations.reservation:${dayUse.id}:$date:room")
                roomChargeIssues(run, dayUse.id).shouldBeEmpty()
            } finally {
                gate.countDown()
                pool.shutdown()
                pool.awaitTermination(60, TimeUnit.SECONDS)
                EndOfDayTestRuns.settle(context)
            }
        }
    }

    @Test
    fun `an injected failure mid-step and the resume post every charge exactly once`() {
        withTourismFee("5.00") {
            val date = hotel.currentBusinessDate()
            val (type, rooms) = inventory.roomTypeWithRooms(3, date)
            val plan = pricedPlan(type, date)
            val stays = rooms.map { inHouse(type, plan, it, date, date.plusDays(2)) }.sortedBy { it.id }
            probe.failOn(FolioId(stays[1].folio))
            try {
                val run = EndOfDayTestRuns.closeCurrentDate(context)

                statusOf(run) shouldBe "FAILED"
                stepStatus(run, "room-charges") shouldBe "FAILED"
                hotel.currentBusinessDate() shouldBe date
                stays.map { keysOn(lines(it.folio), it, date).size } shouldBe listOf(2, 0, 0)

                EndOfDayTestRuns.settle(context)

                statusOf(run) shouldBe "COMPLETED"
                hotel.currentBusinessDate() shouldBe date.plusDays(1)
                stays.forEach { stay ->
                    val keys = lines(stay.folio).mapNotNull { it.key }
                    keys shouldContainExactlyInAnyOrder
                        listOf(
                            "reservations.reservation:${stay.id}:$date:room",
                            "reservations.reservation:${stay.id}:$date:tourism-fee",
                        )
                }
            } finally {
                EndOfDayTestRuns.settle(context)
            }
        }
    }

    @Test
    fun `a tourism fee configured after a close is not charged to the night already closed`() {
        withTourismFee(null) {
            val start = hotel.currentBusinessDate()
            val (type, rooms) = inventory.roomTypeWithRooms(1, start)
            val plan = pricedPlan(type, start)
            val stay = inHouse(type, plan, rooms[0], start, start.plusDays(3))
            try {
                statusOf(EndOfDayTestRuns.closeCurrentDate(context)) shouldBe "COMPLETED"
                val date = hotel.currentBusinessDate()
                date shouldBe start.plusDays(1)

                withTourismFee("5.00") {
                    val run = EndOfDayTestRuns.closeCurrentDate(context)

                    statusOf(run) shouldBe "COMPLETED"
                    val charged = lines(stay.folio).filter { it.key != null }
                    charged.map { Triple(it.key, it.amount, it.businessDate) } shouldContainExactlyInAnyOrder
                        listOf(
                            Triple("reservations.reservation:${stay.id}:$start:room", "400.00", start),
                            Triple("reservations.reservation:${stay.id}:$date:room", "400.00", date),
                            Triple("reservations.reservation:${stay.id}:$date:tourism-fee", "10.00", date),
                        )
                }
            } finally {
                EndOfDayTestRuns.settle(context)
            }
        }
    }

    @Test
    fun `an overstay gets no automatic night`() {
        withTourismFee("5.00") {
            val start = hotel.currentBusinessDate()
            val (type, rooms) = inventory.roomTypeWithRooms(1, start)
            val plan = pricedPlan(type, start)
            val stay = inHouse(type, plan, rooms[0], start, start.plusDays(1))
            try {
                EndOfDayTestRuns.closeCurrentDate(context)
                hotel.currentBusinessDate() shouldBe start.plusDays(1)
                val before = lines(stay.folio)

                val run = EndOfDayTestRuns.closeCurrentDate(context)

                statusOf(run) shouldBe "COMPLETED"
                support.status(stay.id) shouldBe "IN_HOUSE"
                lines(stay.folio) shouldBe before
                roomChargeIssues(run, stay.id).shouldBeEmpty()
            } finally {
                EndOfDayTestRuns.settle(context)
            }
        }
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private fun pricedPlan(
        type: UUID,
        from: LocalDate,
    ): UUID {
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), from, from.plusDays(10))
        return plan.id
    }

    /** Uma reserva de [arrival] a [departure] gravada como hospedada na UH [room] (check-in na chegada). */
    @Suppress("LongParameterList") // a reserva inteira, com padrões
    private fun inHouse(
        type: UUID,
        plan: UUID,
        room: UUID,
        arrival: LocalDate,
        departure: LocalDate,
        adults: Int = 2,
        childAges: List<Int> = emptyList(),
    ): Stay {
        val node =
            support.created(
                support.booking(
                    support.guest(),
                    support.reservation(type, plan, arrival, departure, adults = adults, childAges = childAges),
                ),
            )["reservations"][0]
        val id = UUID.fromString(node["id"].asString())
        support.forceInHouse(id, room) shouldBe 1
        return Stay(id, UUID.fromString(node["folioId"].asString()))
    }

    private fun createPackage(
        code: UUID,
        price: String,
        included: Boolean,
    ): UUID {
        val body =
            support.rates.packageBody(
                code,
                price = price,
                pricing = "PER_NIGHT",
                postingRhythm = "DAILY",
                includedInRate = included,
            )
        val created = with(iam) { call(POST, "/api/v1/packages", asAdmin(), body).expectStatus(201)!! }
        return UUID.fromString(created["id"].asString())
    }

    private fun lines(folio: UUID): List<Line> =
        jdbc
            .sql(
                "SELECT p.id, p.source_reference, c.system_key, p.amount, p.business_date, p.source, " +
                    "p.service_charge_for_id FROM billing.posting p " +
                    "JOIN billing.transaction_code c ON c.id = p.transaction_code_id " +
                    "WHERE p.folio_id = :folio ORDER BY p.id",
            ).param("folio", folio)
            .query { rs, _ ->
                Line(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("source_reference"),
                    rs.getString("system_key"),
                    rs.getBigDecimal("amount").toPlainString(),
                    rs.getObject("business_date", LocalDate::class.java),
                    rs.getString("source"),
                    rs.getObject("service_charge_for_id", UUID::class.java),
                )
            }.list()

    /** As chaves da noite [night] da [stay] entre as [lines]. */
    private fun keysOn(
        lines: List<Line>,
        stay: Stay,
        night: LocalDate,
    ): List<String> =
        lines.mapNotNull { it.key }.filter { it.startsWith("reservations.reservation:${stay.id}:$night:") }

    /** Espera a hospedagem da noite [night] da [stay] lançada: a etapa já listou as hospedadas e passou dela. */
    private fun awaitRoomCharge(
        stay: Stay,
        night: LocalDate,
    ) {
        val key = "reservations.reservation:${stay.id}:$night:room"
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (lines(stay.folio).none { it.key == key }) {
            check(System.nanoTime() < deadline) { "A etapa não chegou à hospedada segurada" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun checkOut(id: UUID) =
        frontdesk.Response(
            support.billing.call(
                POST,
                "/api/v1/reservations/$id/check-out",
                iam.asAdmin(),
                null,
                UuidV7.generate().toString(),
            ),
        )

    private fun statusOf(run: EndOfDayRunId): String =
        jdbc
            .sql("SELECT status FROM endofday.run WHERE id = :id")
            .param("id", run.value)
            .query(String::class.java)
            .single()

    private fun stepStatus(
        run: EndOfDayRunId,
        name: String,
    ): String =
        jdbc
            .sql("SELECT status FROM endofday.run_step WHERE run_id = :id AND name = :name")
            .param("id", run.value)
            .param("name", name)
            .query(String::class.java)
            .single()

    private fun roomChargeIssues(
        run: EndOfDayRunId,
        reservation: UUID,
    ): List<String> =
        jdbc
            .sql(
                "SELECT kind FROM endofday.run_issue WHERE run_id = :id AND reference = :ref " +
                    "AND step_name = 'room-charges'",
            ).param("id", run.value)
            .param("ref", reservation.toString())
            .query { rs, _ -> rs.getString("kind") }
            .list()

    /** Roda [block] com a taxa de turismo do hotel em [fee] e devolve a original no `finally`. */
    private fun withTourismFee(
        fee: String?,
        block: () -> Unit,
    ) {
        val path = "/api/v1/hotel/tax-settings"
        val before = with(iam) { json(call(GET, path, asAdmin())) }

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

    private companion object {
        const val POLL_MILLIS = 50L
        const val CHILD_MAX_AGE = 12
    }
}
