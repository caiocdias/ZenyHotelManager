package com.zeny.hotel.endofday

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.endofday.application.steps.RoomChargesStep
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.frontdesk.FrontdeskTestSupport
import com.zeny.hotel.guests.GuestsTestSupport
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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

/**
 * O ataque à etapa `room-charges` (M6 E11, Task 13): dinheiro imutável (RN-03), então nenhuma noite duplicada e
 * nenhuma perdida em dois fechamentos seguidos, no roteamento com retomada, na conta encerrada, na falha em qualquer
 * posição da ordem, na troca da taxa de turismo, no check-in na data que fecha, na troca de UH e na cortesia; e nada de
 * dado pessoal nas chaves, descrições e pendências. Contexto das etapas reais no banco isolado; cada teste confere a
 * conta inteira das próprias reservas (não só as chaves, que o índice único já impede de repetir), devolve a taxa de
 * turismo e os preços que mudou e termina com [EndOfDayTestRuns.settle].
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class)
@Suppress("LongParameterList", "LargeClass")
class RoomChargesStepAttackTest(
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
    private val billing = support.billing
    private val frontdesk = FrontdeskTestSupport(iam, support, hotel, roomsApi, jsonMapper)

    private class Stay(
        val id: UUID,
        val folio: UUID,
    )

    /** Um lançamento, onde quer que esteja (a conta pedida ou a de destino do roteamento). */
    private data class Line(
        val folio: UUID,
        val key: String?,
        val code: String?,
        val amount: String,
        val businessDate: LocalDate,
        val source: String,
        val description: String?,
        val routedFrom: UUID?,
    )

    @Test
    fun `two consecutive closes post each night of a three-night stay once, on the date of its close`() {
        withTourismFee("5.00") {
            val date = hotel.currentBusinessDate()
            val (type, rooms) = inventory.roomTypeWithRooms(1, date)
            val stay = inHouse(type, pricedPlan(type, date), rooms[0], date, date.plusDays(3))
            try {
                EndOfDayTestRuns.closeCurrentDate(context)
                val second = EndOfDayTestRuns.closeCurrentDate(context)

                statusOf(second) shouldBe "COMPLETED"
                hotel.currentBusinessDate() shouldBe date.plusDays(2)
                val base = "reservations.reservation:${stay.id}"
                charged(stay).map { it.summary() } shouldContainExactlyInAnyOrder
                    listOf(
                        Summary(stay.folio, "$base:$date:room", "400.00", date),
                        Summary(stay.folio, "$base:$date:tourism-fee", "10.00", date),
                        Summary(stay.folio, "$base:${date.plusDays(1)}:room", "400.00", date.plusDays(1)),
                        Summary(stay.folio, "$base:${date.plusDays(1)}:tourism-fee", "10.00", date.plusDays(1)),
                    )
                lines(stay.folio).size shouldBe 4
            } finally {
                EndOfDayTestRuns.settle(context)
            }
        }
    }

    @Test
    fun `a night routed by code to another folio is not charged again on resume nor on the next close`() {
        withTourismFee("5.00") {
            val date = hotel.currentBusinessDate()
            val (type, rooms) = inventory.roomTypeWithRooms(2, date)
            val plan = pricedPlan(type, date)
            val (routed, failing) =
                listOf(
                    inHouse(type, plan, rooms[0], date, date.plusDays(2)),
                    inHouse(type, plan, rooms[1], date, date.plusDays(2)),
                ).sortedBy { it.id }
            val target = billing.openFolio()
            with(billing) {
                createRule(routed.folio, target, date, codes = listOf(codeId("ROOM_CHARGE"))).expect(201)
            }
            probe.failOn(FolioId(failing.folio))
            try {
                val run = EndOfDayTestRuns.closeCurrentDate(context)
                statusOf(run) shouldBe "FAILED"
                charged(routed).map { it.key to it.folio } shouldContainExactlyInAnyOrder
                    listOf(
                        "reservations.reservation:${routed.id}:$date:room" to target,
                        "reservations.reservation:${routed.id}:$date:tourism-fee" to routed.folio,
                    )

                EndOfDayTestRuns.settle(context)
                statusOf(run) shouldBe "COMPLETED"
                EndOfDayTestRuns.closeCurrentDate(context)

                val base = "reservations.reservation:${routed.id}"
                charged(routed).map { it.summary() } shouldContainExactlyInAnyOrder
                    listOf(
                        Summary(target, "$base:$date:room", "400.00", date),
                        Summary(routed.folio, "$base:$date:tourism-fee", "10.00", date),
                        Summary(target, "$base:${date.plusDays(1)}:room", "400.00", date.plusDays(1)),
                        Summary(routed.folio, "$base:${date.plusDays(1)}:tourism-fee", "10.00", date.plusDays(1)),
                    )
                lines(target).forEach { it.routedFrom shouldBe routed.folio }
                lines(target).size shouldBe 2
                lines(routed.folio).size shouldBe 2
                charged(failing).map { it.key } shouldContainExactlyInAnyOrder
                    listOf(date, date.plusDays(1)).flatMap {
                        listOf(
                            "reservations.reservation:${failing.id}:$it:room",
                            "reservations.reservation:${failing.id}:$it:tourism-fee",
                        )
                    }
            } finally {
                EndOfDayTestRuns.settle(context)
            }
        }
    }

    @Test
    fun `a closed reservation folio becomes an issue and the other in-house stays are still charged`() {
        withTourismFee(null) {
            val date = hotel.currentBusinessDate()
            val (type, rooms) = inventory.roomTypeWithRooms(3, date)
            val plan = pricedPlan(type, date)
            val stays = rooms.map { inHouse(type, plan, it, date, date.plusDays(2)) }.sortedBy { it.id }
            val closed = stays[1]
            with(billing) { call(POST, "/api/v1/folios/${closed.folio}/close", support.admin).expect(200) }
            try {
                val run = EndOfDayTestRuns.closeCurrentDate(context)

                statusOf(run) shouldBe "COMPLETED"
                stays.map { stay -> charged(stay).map { it.key } } shouldContainExactly
                    listOf(
                        listOf("reservations.reservation:${stays[0].id}:$date:room"),
                        emptyList(),
                        listOf("reservations.reservation:${stays[2].id}:$date:room"),
                    )
                lines(closed.folio).shouldBeEmpty()
                issuesOf(run, stays) shouldContainExactly
                    listOf(Triple("ROOM_CHARGE_FAILED", closed.id.toString(), "ROOM_CHARGE_FAILED: FOLIO_CLOSED"))
            } finally {
                EndOfDayTestRuns.settle(context)
            }
        }
    }

    @ParameterizedTest(name = "failure on stay #{0} of three")
    @ValueSource(ints = [0, 1, 2])
    fun `an injected failure at the first, middle or last stay and the resume post each charge exactly once`(
        failing: Int,
    ) {
        withTourismFee("5.00") {
            val date = hotel.currentBusinessDate()
            val (type, rooms) = inventory.roomTypeWithRooms(3, date)
            val plan = pricedPlan(type, date)
            val stays = rooms.map { inHouse(type, plan, it, date, date.plusDays(3)) }.sortedBy { it.id }
            probe.failOn(FolioId(stays[failing].folio))
            try {
                val run = EndOfDayTestRuns.closeCurrentDate(context)

                statusOf(run) shouldBe "FAILED"
                stepStatus(run, RoomChargesStep.NAME) shouldBe "FAILED"
                hotel.currentBusinessDate() shouldBe date
                stays.map { lines(it.folio).size } shouldBe stays.indices.map { if (it < failing) 2 else 0 }

                EndOfDayTestRuns.settle(context)

                statusOf(run) shouldBe "COMPLETED"
                hotel.currentBusinessDate() shouldBe date.plusDays(1)
                stays.forEach { stay ->
                    val base = "reservations.reservation:${stay.id}:$date"
                    lines(stay.folio).map { it.summary() } shouldContainExactlyInAnyOrder
                        listOf(
                            Summary(stay.folio, "$base:room", "400.00", date),
                            Summary(stay.folio, "$base:tourism-fee", "10.00", date),
                        )
                }
            } finally {
                EndOfDayTestRuns.settle(context)
            }
        }
    }

    @Test
    fun `a tourism fee changed between closes charges each night with the fee in force at its close`() {
        withTourismFee("5.00") {
            val date = hotel.currentBusinessDate()
            val (type, rooms) = inventory.roomTypeWithRooms(1, date)
            val stay = inHouse(type, pricedPlan(type, date), rooms[0], date, date.plusDays(3))
            try {
                EndOfDayTestRuns.closeCurrentDate(context)
                val firstNight = charged(stay)
                putTourismFee("7.00")
                EndOfDayTestRuns.closeCurrentDate(context)

                val base = "reservations.reservation:${stay.id}"
                val after = charged(stay)
                after.filter { it.businessDate == date } shouldBe firstNight
                after.filter { it.key!!.endsWith(":tourism-fee") }.map { it.summary() } shouldContainExactlyInAnyOrder
                    listOf(
                        Summary(stay.folio, "$base:$date:tourism-fee", "10.00", date),
                        Summary(stay.folio, "$base:${date.plusDays(1)}:tourism-fee", "14.00", date.plusDays(1)),
                    )
                lines(stay.folio).size shouldBe 4
            } finally {
                EndOfDayTestRuns.settle(context)
            }
        }
    }

    @Test
    fun `a stay checked in on the closing date gets only that night`() {
        withTourismFee("5.00") {
            val date = hotel.currentBusinessDate()
            val booked = frontdesk.stay().checkedIn(departure = date.plusDays(3))
            val stay = Stay(booked.id, booked.folio)
            try {
                val run = EndOfDayTestRuns.closeCurrentDate(context)

                statusOf(run) shouldBe "COMPLETED"
                val base = "reservations.reservation:${stay.id}:$date"
                lines(stay.folio).map { it.summary() } shouldContainExactlyInAnyOrder
                    listOf(
                        Summary(stay.folio, "$base:room", "400.00", date),
                        Summary(stay.folio, "$base:tourism-fee", "10.00", date),
                    )
                lines(stay.folio).forEach { it.source shouldBe "END_OF_DAY" }
            } finally {
                EndOfDayTestRuns.settle(context)
            }
        }
    }

    @Test
    fun `a room move in the middle of the stay neither duplicates nor loses a night`() {
        withTourismFee(null) {
            val date = hotel.currentBusinessDate()
            val setup = frontdesk.stay(rooms = 2)
            val booked = setup.checkedIn(departure = date.plusDays(3))
            val stay = Stay(booked.id, booked.folio)
            try {
                EndOfDayTestRuns.closeCurrentDate(context)
                // A tarifa atual sobe e a troca reprecifica as noites a partir de D+1; a de D já lançada fica.
                val today = hotel.currentBusinessDate()
                support.setPrices(setup.plan, listOf(setup.type), today, date.plusDays(10), "450.00", "500.00")
                setup.ready(setup.rooms[1])
                moveRoom(stay.id, setup.rooms[1], "RECALCULATE")
                EndOfDayTestRuns.closeCurrentDate(context)
                EndOfDayTestRuns.closeCurrentDate(context)

                val nights = reservationsApi.reservation(ReservationId(stay.id))!!.nightlyRates.associateBy { it.date }
                val base = "reservations.reservation:${stay.id}"
                lines(stay.folio).map { it.summary() } shouldContainExactlyInAnyOrder
                    listOf(date, date.plusDays(1), date.plusDays(2)).map {
                        val night = nights.getValue(it)
                        val amount =
                            if (it == date) {
                                "400.00"
                            } else {
                                (night.roomAmount - night.includedPackagesAmount).amount.toPlainString()
                            }
                        Summary(stay.folio, "$base:$it:room", amount, it)
                    }
                nights
                    .getValue(date.plusDays(1))
                    .roomAmount.amount
                    .toPlainString() shouldBe "500.00"
            } finally {
                EndOfDayTestRuns.settle(context)
            }
        }
    }

    @Test
    fun `a courtesy stay priced at zero posts no zero line and raises no issue`() {
        withTourismFee(null) {
            val date = hotel.currentBusinessDate()
            val (type, rooms) = inventory.roomTypeWithRooms(1, date)
            val plan = courtesyPlan(type, date)
            val stay = inHouse(type, plan, rooms[0], date, date.plusDays(2))
            try {
                val run = EndOfDayTestRuns.closeCurrentDate(context)

                statusOf(run) shouldBe "COMPLETED"
                reservationsApi
                    .reservation(ReservationId(stay.id))!!
                    .nightlyRates
                    .single { it.date == date }
                    .roomAmount
                    .isZero() shouldBe true
                lines(stay.folio).shouldBeEmpty()
                issuesOf(run, listOf(stay)).shouldBeEmpty()
            } finally {
                EndOfDayTestRuns.settle(context)
            }
        }
    }

    @Test
    fun `no posting key, description or issue of the step carries the guest personal data`() {
        withTourismFee("5.00") {
            val date = hotel.currentBusinessDate()
            val (type, rooms) = inventory.roomTypeWithRooms(2, date)
            val code = support.rates.createTransactionCode(serviceChargeApplies = false)
            val packageName = "Cafe ${UUID.randomUUID().toString().take(8)}"
            val pkg = createPackage(code, packageName)
            val plan = support.plan(listOf(type), packages = listOf(pkg))
            support.setPrices(plan.id, listOf(type), date, date.plusDays(10))
            val name = "Joana Pessoal Sigilosa"
            val cpf = GuestsTestSupport.newCpf()
            val note = "Alergia grave a amendoim"
            val guests = support.guests
            val holder =
                UUID.fromString(
                    guests
                        .createGuest(
                            guests.createBody(
                                profile = guests.profileBody(fullName = name),
                                documents = listOf(guests.document("CPF", cpf)),
                            ),
                        )["id"]
                        .asString(),
                )
            val charged = inHouse(type, plan.id, rooms[0], date, date.plusDays(2), holder, note)
            val refused = inHouse(type, plan.id, rooms[1], date, date.plusDays(2), holder, note)
            with(billing) { call(POST, "/api/v1/folios/${refused.folio}/close", support.admin).expect(200) }
            try {
                val run = EndOfDayTestRuns.closeCurrentDate(context)

                val posted = lines(charged.folio)
                val base = "reservations.reservation:${charged.id}:$date"
                posted.map { it.key } shouldContainExactlyInAnyOrder
                    listOf("$base:room", "$base:package:$pkg", "$base:tourism-fee")
                posted.single { it.key!!.endsWith(":package:$pkg") }.description shouldBe packageName
                posted.single { it.key!!.endsWith(":tourism-fee") }.description shouldBe "Taxa de turismo"
                val issues = issuesOf(run, listOf(charged, refused))
                issues.map { it.second } shouldBe listOf(refused.id.toString())
                val texts = posted.flatMap { listOf(it.key, it.description) } + issues.flatMap { it.toList() }
                texts.filterNotNull().forEach { text ->
                    listOf(name, "Joana", "Sigilosa", cpf, GuestsTestSupport.formattedCpf(cpf), note, "amendoim")
                        .forEach { text shouldNotContain it }
                }
                posted.mapNotNull { it.key }.forEach {
                    it shouldMatch Regex("^reservations\\.reservation:[0-9a-f-]{36}:\\d{4}-\\d{2}-\\d{2}:[a-z0-9:-]+$")
                }
            } finally {
                EndOfDayTestRuns.settle(context)
            }
        }
    }

    @Test
    fun `the step is not applicable on a date with no in-house stay`() {
        // Nenhuma reserva do banco isolado chegou antes de 2000; a pré-condição é conferida, não suposta.
        val date = LocalDate.of(2000, 1, 3)
        reservationsApi.inHouse(date).shouldBeEmpty()
        val raised = mutableListOf<String?>()

        val outcome =
            tx.execute { _ ->
                step.execute(EndOfDayStepContext(EndOfDayRunId(UuidV7.generate()), date) { _, ref, _ -> raised += ref })
            }

        outcome shouldBe EndOfDayStepOutcome.NOT_APPLICABLE
        raised.shouldBeEmpty()
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private data class Summary(
        val folio: UUID,
        val key: String?,
        val amount: String,
        val businessDate: LocalDate,
    )

    private fun Line.summary() = Summary(folio, key, amount, businessDate)

    private fun pricedPlan(
        type: UUID,
        from: LocalDate,
    ): UUID {
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), from, from.plusDays(10))
        return plan.id
    }

    /** Um plano de cortesia (Q-15) com preço 0,00 para um e dois adultos. */
    private fun courtesyPlan(
        type: UUID,
        from: LocalDate,
    ): UUID {
        val rates = support.rates
        val cancellation = rates.createCancellationPolicy(rates.cancellationBody())
        val guarantee =
            rates.createGuaranteePolicy(rates.guaranteeBody(type = "NONE", depositPercent = null, dueDaysBefore = null))
        val body = rates.planBody(listOf(type), cancellation, guarantee, category = "COURTESY")
        val id =
            with(iam) {
                val created = call(POST, "/api/v1/rate-plans", support.admin, body).expectStatus(201)!!
                UUID.fromString(created["id"].asString())
            }
        support.setPrices(id, listOf(type), from, from.plusDays(10), "0.00", "0.00")
        return id
    }

    private fun createPackage(
        code: UUID,
        name: String,
    ): UUID {
        val body =
            support.rates.packageBody(
                code,
                name = name,
                price = "20.00",
                pricing = "PER_NIGHT",
                postingRhythm = "DAILY",
                includedInRate = false,
            )
        val created = with(iam) { call(POST, "/api/v1/packages", asAdmin(), body).expectStatus(201)!! }
        return UUID.fromString(created["id"].asString())
    }

    /** Uma reserva de [arrival] a [departure] gravada como hospedada na UH [room] (check-in na chegada). */
    @Suppress("LongParameterList") // a reserva inteira, com padrões
    private fun inHouse(
        type: UUID,
        plan: UUID,
        room: UUID,
        arrival: LocalDate,
        departure: LocalDate,
        holder: UUID = support.guest(),
        note: String? = null,
    ): Stay {
        val node =
            support.created(
                support.booking(
                    holder,
                    support.reservation(
                        type,
                        plan,
                        arrival,
                        departure,
                        specialRequests = note,
                        guests = listOf(holder to true),
                    ),
                ),
            )["reservations"][0]
        val id = UUID.fromString(node["id"].asString())
        support.forceInHouse(id, room) shouldBe 1
        return Stay(id, UUID.fromString(node["folioId"].asString()))
    }

    private fun moveRoom(
        id: UUID,
        roomId: UUID,
        tariff: String,
    ) {
        frontdesk
            .Response(
                billing.call(
                    POST,
                    "/api/v1/reservations/$id/room-moves",
                    iam.asAdmin(),
                    mapOf("roomId" to roomId, "reason" to "OPERATIONAL", "tariff" to tariff),
                    UuidV7.generate().toString(),
                    ifMatch = support.etag(id),
                ),
            ).expect(200)
    }

    private val lineQuery =
        "SELECT p.folio_id, p.source_reference, c.system_key, p.amount, p.business_date, p.source, p.description, " +
            "p.routed_from_folio_id FROM billing.posting p " +
            "JOIN billing.transaction_code c ON c.id = p.transaction_code_id "

    private fun queryLines(
        where: String,
        param: Pair<String, Any>,
    ): List<Line> =
        jdbc
            .sql("$lineQuery WHERE $where ORDER BY p.id")
            .param(param.first, param.second)
            .query { rs, _ ->
                Line(
                    rs.getObject("folio_id", UUID::class.java),
                    rs.getString("source_reference"),
                    rs.getString("system_key"),
                    rs.getBigDecimal("amount").toPlainString(),
                    rs.getObject("business_date", LocalDate::class.java),
                    rs.getString("source"),
                    rs.getString("description"),
                    rs.getObject("routed_from_folio_id", UUID::class.java),
                )
            }.list()

    /** Todos os lançamentos da conta [folio], com chave ou sem. */
    private fun lines(folio: UUID): List<Line> = queryLines("p.folio_id = :folio", "folio" to folio)

    /** Os lançamentos com a chave de alguma noite da [stay], em qualquer conta. */
    private fun charged(stay: Stay): List<Line> =
        queryLines("p.source_reference LIKE :prefix", "prefix" to "reservations.reservation:${stay.id}:%")

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

    /** As pendências da etapa `room-charges` com a referência de uma das [stays]: (tipo, referência, mensagem). */
    private fun issuesOf(
        run: EndOfDayRunId,
        stays: List<Stay>,
    ): List<Triple<String, String, String>> {
        val refs = stays.map { it.id.toString() }.toSet()
        return jdbc
            .sql(
                "SELECT kind, reference, message FROM endofday.run_issue WHERE run_id = :id " +
                    "AND step_name = 'room-charges' ORDER BY created_at, id",
            ).param("id", run.value)
            .query { rs, _ -> Triple(rs.getString("kind"), rs.getString("reference"), rs.getString("message")) }
            .list()
            .filter { it.second in refs }
    }

    private val taxPath = "/api/v1/hotel/tax-settings"

    private fun putTourismFee(value: String?) =
        with(iam) {
            val current = json(call(GET, taxPath, asAdmin()))
            val etag = checkNotNull(call(GET, taxPath, asAdmin()).getHeader("ETag"))
            call(
                PUT,
                taxPath,
                asAdmin(),
                mapOf(
                    "serviceChargeRate" to current["serviceChargeRate"].asString(),
                    "issRate" to current["issRate"].asString(),
                    "tourismFeePerPersonNight" to value,
                ),
                ifMatch = etag,
            ).ok()
        }

    /** Roda [block] com a taxa de turismo do hotel em [fee] e devolve a original no `finally`. */
    private fun withTourismFee(
        fee: String?,
        block: () -> Unit,
    ) {
        val before = with(iam) { json(call(GET, taxPath, asAdmin())) }
        try {
            putTourismFee(fee)
            block()
        } finally {
            putTourismFee(before["tourismFeePerPersonNight"].takeUnless { it.isNull }?.asString())
        }
    }
}
