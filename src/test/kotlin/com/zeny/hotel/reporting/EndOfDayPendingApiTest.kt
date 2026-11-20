package com.zeny.hotel.reporting

import com.zeny.hotel.billing.BillingPermissions
import com.zeny.hotel.billing.BillingTestSupport.Companion.definition
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.InventoryListenersQuiescence
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * `GET /api/v1/reports/end-of-day-pending` (M6 E09, D4): as contas abertas de reservas `CHECKED_OUT` com saldo (com
 * transferência, reembolso e depósito), os caixas abertos e as submissões FNRH `PENDING`, `FAILED` e `INVALID`; saldo
 * zero, caixa fechado e submissão resolvida não aparecem. O banco é compartilhado: as listas têm itens de outros
 * testes, então tudo é achado pelo número da conta, do turno ou pela reserva criada aqui, nunca por total.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
class EndOfDayPendingApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val seed = ReportingTestSupport(iam, support)
    private val billing = support.billing
    private val admin = iam.asAdmin()

    private fun pending(auth: RequestPostProcessor = admin) = iam.call(GET, "/api/v1/reports/end-of-day-pending", auth)

    private fun cashier(): TestUser =
        iam.createUserWith(
            TestGrant(definition(BillingPermissions.PAYMENTS_RECEIVE), ALL),
            TestGrant(definition(BillingPermissions.FOLIOS_READ), ALL),
            TestGrant(definition(BillingPermissions.CASHIER_OPERATE), ALL),
            TestGrant(
                definition(BillingPermissions.PAYMENTS_REFUND),
                ALL,
                mapOf(BillingPermissions.MAX_AMOUNT to LimitValue.Unlimited),
            ),
        )

    private fun folioOf(reservation: UUID): UUID =
        jdbc
            .sql("SELECT folio_id FROM reservations.report_reservations WHERE reservation_id = :id")
            .param("id", reservation)
            .query(UUID::class.java)
            .single()

    private fun codeOf(reservation: UUID): String =
        jdbc
            .sql("SELECT reservation_code FROM reservations.report_reservations WHERE reservation_id = :id")
            .param("id", reservation)
            .query(String::class.java)
            .single()

    private fun numberOf(folio: UUID): String = billing.folio(folio)["number"].asString()

    private fun JsonNode.folio(number: String): JsonNode? =
        this["folios"].values().singleOrNull {
            it["folioNumber"].asString() == number
        }

    private fun submit(
        reservation: UUID,
        operation: String,
        status: String,
        attempts: Int,
        failure: String?,
    ) = jdbc
        .sql(
            """
            INSERT INTO fnrh.submission (id, reservation_id, reservation_code, operation, operation_rank, event_key,
                causal_version, status, attempts, next_attempt_at, last_failure, in_flight, occurred_at, created_at,
                sent_at, version)
            VALUES (:id, :reservation, 'R-PENDING', :operation, 0, :key, 1, :status, :attempts, now() + interval '30 days',
                :failure,
                false, now(), now(), CASE WHEN :status = 'SENT' THEN now() END, 0)
            """.trimIndent(),
        ).param("id", UuidV7.generate())
        .param("reservation", reservation)
        .param("operation", operation)
        .param("key", "pending-$operation")
        .param("status", status)
        .param("attempts", attempts)
        .param("failure", failure)
        .update()

    @Test
    @Suppress("LongMethod") // o cenário e as conferências de cada lista
    fun `open balances, open shifts and unresolved FNRH submissions appear, zero balances and closed ones do not`() {
        val base = hotel.currentBusinessDate()
        val (type, _) = support.inventory.roomTypeWithRooms(3, base)
        val plan = seed.plan(type, "NORMAL", base)
        val marker = support.guests.suffix().uppercase()
        val holder =
            UUID.fromString(
                support.guests
                    .createGuest(
                        support.guests.createBody(
                            profile = support.guests.profileBody(fullName = "Hóspede Pendência $marker"),
                            documents = emptyList(),
                        ),
                    )["id"]
                    .asString(),
            )
        val owing = seed.book(holder, support.reservation(type, plan, base, base.plusDays(1), adults = 1))
        val settled = seed.book(holder, support.reservation(type, plan, base, base.plusDays(1), adults = 1))
        val notOut = seed.book(holder, support.reservation(type, plan, base, base.plusDays(1), adults = 1))
        InventoryListenersQuiescence.await(jdbc)
        support.forceCheckedOut(owing)
        support.forceCheckedOut(settled)
        val owingFolio = folioOf(owing)
        val secondFolio = billing.openFolio("RESERVATION", owing)
        val settledFolio = folioOf(settled)
        val notOutFolio = folioOf(notOut)

        val operator = cashier()
        val auth = iam.asTestUser(operator)
        val openShift = billing.openShift(auth)
        val closedOperator = cashier()
        val closedShift = billing.openShift(iam.asTestUser(closedOperator))
        with(billing) {
            call(
                POST,
                "/api/v1/cashier-shifts/$closedShift/close",
                iam.asTestUser(closedOperator),
                mapOf("counts" to listOf(mapOf("method" to "CASH", "amount" to "0.00"))),
            ).expect(200)
        }
        // Um caixa aberto de outra data operacional também é pendência.
        jdbc
            .sql("UPDATE billing.cashier_shift SET opened_on = opened_on - 3 WHERE id = :id")
            .param("id", openShift)
            .update()

        val laundry = billing.codeId("LAUNDRY")
        val first = billing.post(owingFolio, laundry, "100.00")
        val second = billing.post(owingFolio, laundry, "60.00")
        val deposit =
            billing
                .pay(owingFolio, auth, mapOf("method" to "PIX", "amount" to "30.00", "deposit" to true))
                .let { with(billing) { it.expect(201) } }
        with(billing) {
            call(
                POST,
                "/api/v1/folios/$owingFolio/payments/${deposit["id"].asString()}/refund",
                auth,
                mapOf("amount" to "10.00", "reason" to "Devolução de teste $marker"),
            ).expect(201)
            transfer(owingFolio, secondFolio, listOf(second["id"].asString())).expect(201)
            post(settledFolio, laundry, "50.00")
            pay(settledFolio, auth, mapOf("method" to "CASH", "amount" to "50.00")).expect(201)
            post(notOutFolio, laundry, "40.00")
        }
        first["id"].asString().isNotEmpty() shouldBe true
        submit(owing, "REGISTER_RESERVATION", "PENDING", 0, null)
        submit(owing, "UPDATE_RESERVATION", "FAILED", 3, "SERVER_ERROR")
        submit(owing, "CHECK_IN", "INVALID", 0, "VALIDATION")
        submit(owing, "CHECK_OUT", "SENT", 1, null)
        submit(owing, "CANCEL_RESERVATION", "DISMISSED", 1, null)

        val body = with(iam) { pending().ok() }

        // Conta com saldo: 100 lançados e 60 transferidos para a outra conta, menos o depósito de 30 com 10 devolvidos.
        val owingRow = body.folio(numberOf(owingFolio))!!
        owingRow["balance"].asString() shouldBe "80.00"
        owingRow["balance"].asString() shouldBe billing.balance(owingFolio)
        owingRow["reservationId"].asString() shouldBe "$owing"
        owingRow["folioId"].asString() shouldBe "$owingFolio"
        owingRow["reservationCode"].asString() shouldBe codeOf(owing)
        body.folio(numberOf(secondFolio))!!["balance"].asString() shouldBe "60.00"
        body.folio(numberOf(secondFolio))!!["balance"].asString() shouldBe billing.balance(secondFolio)
        // Saldo zero, reserva que ainda não saiu: ausentes.
        body.folio(numberOf(settledFolio)) shouldBe null
        body.folio(numberOf(notOutFolio)) shouldBe null

        // Caixas: o aberto (de outra data) aparece com o operador; o fechado não.
        val shifts = body["cashierShifts"].values().associateBy { it["shiftId"].asString() }
        val shiftRow = shifts.getValue("$openShift")
        shiftRow["operatorId"].asString() shouldBe "${operator.id.value}"
        shiftRow["openedOn"].asString() shouldBe hotelDateOf(openShift)
        shifts.containsKey("$closedShift") shouldBe false

        // FNRH: só PENDING, FAILED e INVALID da reserva, com tentativas e a última falha.
        val submissions = body["fnrhSubmissions"].values().filter { it["reservationId"].asString() == "$owing" }
        // O envio automático do cadastro da reserva também está PENDING: por isso o conjunto.
        submissions
            .map { Triple(it["operation"].asString(), it["status"].asString(), it["attempts"].asInt()) }
            .toSet() shouldBe
            setOf(
                Triple("REGISTER_RESERVATION", "PENDING", 0),
                Triple("UPDATE_RESERVATION", "FAILED", 3),
                Triple("CHECK_IN", "INVALID", 0),
            )
        submissions.single { it["status"].asString() == "FAILED" }["lastFailure"].asString() shouldBe "SERVER_ERROR"
        submissions.filter { it["status"].asString() == "PENDING" }.forEach { it["lastFailure"].isNull shouldBe true }
        submissions.forEach { it["reservationCode"].asString() shouldBe owingRow["reservationCode"].asString() }

        // RN-13: nenhum nome de hóspede nem texto livre.
        body.toString() shouldNotContain marker
    }

    private fun hotelDateOf(shift: UUID): String =
        jdbc
            .sql("SELECT opened_on FROM billing.report_cashier_shifts WHERE shift_id = :id")
            .param("id", shift)
            .query(String::class.java)
            .single()

    @Test
    fun `without the permission the pendings are refused with 403, and with it they are served`() {
        val none = iam.asTestUser(iam.createUser())
        val onlyDashboard =
            iam.asTestUser(
                iam.createUserWith(
                    TestGrant(
                        ReportingPermissions.permissions.single { it.code == ReportingPermissions.DASHBOARD_READ },
                        ALL,
                    ),
                ),
            )
        val reader =
            iam.asTestUser(
                iam.createUserWith(
                    TestGrant(
                        ReportingPermissions.permissions.single { it.code == ReportingPermissions.PENDING_READ },
                        ALL,
                    ),
                ),
            )

        with(iam) {
            pending(none).problem(403, "PERMISSION_DENIED")
            pending(onlyDashboard).problem(403, "PERMISSION_DENIED")
            pending(reader).ok()
        }
    }

    @Test
    fun `the response is a typed object with decimal string balances and no guest data`() {
        val base = hotel.currentBusinessDate()
        val (type, _) = support.inventory.roomTypeWithRooms(1, base)
        val plan = seed.plan(type, "NORMAL", base)
        val reservation =
            seed.book(
                support.guest(),
                support.reservation(type, plan, base, base.plusDays(1), adults = 1),
            )
        support.forceCheckedOut(reservation)
        billing.post(folioOf(reservation), billing.codeId("LAUNDRY"), "12.30")
        billing.openShift(iam.asTestUser(cashier()))
        submit(reservation, "CHECK_IN", "PENDING", 0, null)

        val body = with(iam) { pending().ok() }

        body.propertyNames().toSet() shouldBe setOf("folios", "cashierShifts", "fnrhSubmissions")
        body["folios"].values().forEach {
            it.propertyNames().toSet() shouldBe
                setOf("folioId", "folioNumber", "reservationId", "reservationCode", "departure", "balance")
            it["balance"].isString shouldBe true
        }
        body["cashierShifts"].values().forEach {
            it.propertyNames().toSet() shouldBe
                setOf("shiftId", "shiftNumber", "cashPointName", "operatorId", "openedOn", "openedAt")
        }
        body["fnrhSubmissions"].values().forEach {
            it.propertyNames().toSet() shouldBe
                setOf(
                    "operation",
                    "status",
                    "reservationId",
                    "reservationCode",
                    "attempts",
                    "lastFailure",
                    "createdAt",
                )
        }
        body["folios"].values().any { it["balance"].asString() == "12.30" } shouldBe true
    }
}
