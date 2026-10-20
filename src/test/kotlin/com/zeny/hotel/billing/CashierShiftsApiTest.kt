package com.zeny.hotel.billing

import com.zeny.hotel.billing.BillingTestSupport.Companion.definition
import com.zeny.hotel.billing.application.CashierShiftService
import com.zeny.hotel.billing.application.DeclaredCount
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.IDEMPOTENT_REPLAYED_HEADER
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pontos e turnos de caixa (M3 E07, RF-CTA-09 e 10, H13, H14): um turno aberto por usuário (inclusive em aberturas
 * simultâneas), sangria e suprimento imutáveis no banco, o fechamento cego que só mostra o esperado depois da
 * contagem (salvo a quem confere), a diferença por forma e o relatório, o escopo `OWN` na leitura, o
 * `BillingApi.requireOpenShift` e a notificação de diferença só para quem confere os turnos (nunca o operador), sem
 * dado pessoal e nunca numa transação desfeita.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class)
@Suppress("LargeClass", "LongParameterList") // cenários de ponta a ponta; os beans que eles usam
class CashierShiftsApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val iamApi: IamApi,
    @Autowired private val billingApi: BillingApi,
    @Autowired private val shiftService: CashierShiftService,
    @Autowired flyway: Flyway,
) {
    private val billing = BillingTestSupport(iam, mockMvc, jsonMapper)
    private val owner = JdbcClient.create(flyway.configuration.dataSource)
    private val operate = definition(BillingPermissions.CASHIER_OPERATE)
    private val read = definition(BillingPermissions.SHIFTS_READ)
    private val review = definition(BillingPermissions.SHIFTS_REVIEW)

    // --- Pontos de caixa -----------------------------------------------------------------------------

    @Test
    fun `cash points are registered with a unique name, edited with If-Match, deactivated and never deleted`(): Unit =
        with(billing) {
            val name = "Recepção ${suffix()}"
            val created = call(POST, "/api/v1/cash-points", admin, mapOf("name" to name)).expect(201)
            created["blindClose"].asBoolean() shouldBe true
            created["active"].asBoolean() shouldBe true
            val id = created.uuid("id")
            call(POST, "/api/v1/cash-points", admin, mapOf("name" to name.uppercase()))
                .problem(409, "CASH_POINT_NAME_ALREADY_IN_USE")

            val path = "/api/v1/cash-points/$id"
            call(PUT, path, admin, mapOf("name" to "$name B", "blindClose" to false))
                .problem(428, "PRECONDITION_REQUIRED")
            val edited =
                call(PUT, path, admin, mapOf("name" to "$name B", "blindClose" to false), ifMatch = etagOf(path))
                    .expect(200)
            edited["name"].asString() shouldBe "$name B"
            edited["blindClose"].asBoolean() shouldBe false

            call(POST, "$path/deactivate", admin).expect(200)["active"].asBoolean() shouldBe false
            listAll("/api/v1/cash-points?active=false").map { it["id"].asString() } shouldContain id.toString()
            call(POST, "$path/reactivate", admin).expect(200)["active"].asBoolean() shouldBe true

            withClue("nada é apagado") {
                shouldThrow<DataAccessException> {
                    owner.sql("DELETE FROM billing.cash_point WHERE id = :id").param("id", id).update()
                }.mostSpecificCause.message shouldContain "nunca são apagados"
            }
            val operator = iam.createUserWith(TestGrant(operate, ALL))
            call(POST, "/api/v1/cash-points", iam.asTestUser(operator), mapOf("name" to "X ${suffix()}"))
                .problem(403, "PERMISSION_DENIED")
        }

    // --- Abertura ------------------------------------------------------------------------------------

    @Test
    fun `a second open shift for the same user is refused with 409, even on another cash point`(): Unit =
        with(billing) {
            val operator = operator()
            val auth = iam.asTestUser(operator)
            val shift = openShift(auth, cashPoint(), "150.00").expect(201)
            shift["status"].asString() shouldBe "OPEN"
            shift["openingFloat"].asString() shouldBe "150.00"
            shift["openedOn"].asString() shouldBe hotelApi.currentBusinessDate().toString()
            shift["number"].asString() shouldContain "CX-"
            shift["operatorId"].asString() shouldBe operator.id.toString()

            openShift(auth, cashPoint(), "0.00").problem(409, "CASHIER_SHIFT_ALREADY_OPEN")
            call(GET, "/api/v1/cashier-shifts/current", auth).expect(200)["id"] shouldBe shift["id"]

            val other = operator()
            call(GET, "/api/v1/cashier-shifts/current", iam.asTestUser(other)).problem(404, "CASHIER_SHIFT_NOT_FOUND")
            openShift(iam.asTestUser(other), UuidV7.generate(), "0.00")
                .problem(400, "UNKNOWN_CASH_POINT")["errors"][0]["field"]
                .asString() shouldBe "cashPointId"
            openShift(iam.asTestUser(other), cashPoint(), "-1.00").problem(400, "CASHIER_SHIFT_INVALID")
            val inactive = cashPoint()
            call(POST, "/api/v1/cash-points/$inactive/deactivate", admin).expect(200)
            openShift(iam.asTestUser(other), inactive, "0.00").problem(422, "CASH_POINT_INACTIVE")
            openShift(iam.asTestUser(other), cashPoint(), "1.00", idempotencyKey = null)
                .problem(400, "IDEMPOTENCY_KEY_REQUIRED")
        }

    @Test
    fun `simultaneous openings by the same user open exactly one shift and refuse the others with 409`() {
        val operator = operator()
        val auth = iam.asTestUser(operator)
        val points = List(CONCURRENT) { cashPoint() }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(CONCURRENT)
        try {
            val responses =
                points
                    .map { point ->
                        pool.submit(
                            Callable {
                                start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
                                billing.openShift(auth, point, "10.00")
                            },
                        )
                    }.also { start.countDown() }
                    .map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            responses.map { it.status }.sorted() shouldContainExactly listOf(201) + List(CONCURRENT - 1) { 409 }
            with(billing) {
                responses.filter { it.status == 409 }.forEach { it.problem(409, "CASHIER_SHIFT_ALREADY_OPEN") }
            }
            openShiftsOf(operator.id) shouldBe 1L
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `the database itself refuses a second open shift for the same operator`() {
        val operator = operator()
        with(billing) { openShift(iam.asTestUser(operator), cashPoint(), "0.00").expect(201) }
        val point = cashPoint()
        shouldThrow<DataAccessException> {
            owner
                .sql(
                    """
                    INSERT INTO billing.cashier_shift (id, number, cash_point_id, operator_id, status, blind_close,
                        opening_float, opened_on, opened_at, version)
                    VALUES (:id, :number, :point, :operator, 'OPEN', true, 0, current_date, now(), 0)
                    """.trimIndent(),
                ).param("id", UuidV7.generate())
                .param("number", "CX-2026-9${(100000..999999).random()}")
                .param("point", point)
                .param("operator", operator.id.value)
                .update()
        }.mostSpecificCause.message shouldContain "cashier_shift_open_operator_uk"
    }

    // --- Movimentos ----------------------------------------------------------------------------------

    @Test
    fun `withdrawals and supplies are recorded with a reason and can never be updated nor deleted`(): Unit =
        with(billing) {
            val operator = operator()
            val auth = iam.asTestUser(operator)
            val shift = openShift(auth, cashPoint(), "100.00").expect(201)
            val path = "/api/v1/cashier-shifts/${shift["id"].asString()}"
            val etag = etagOf(path)

            val withdrawal = movement(path, auth, "WITHDRAWAL", "40.00", "Recolhimento ao cofre").expect(201)
            withdrawal["type"].asString() shouldBe "WITHDRAWAL"
            withdrawal["amount"].asString() shouldBe "40.00"
            withdrawal["reason"].asString() shouldBe "Recolhimento ao cofre"
            withdrawal["recordedBy"].asString() shouldBe operator.id.toString()
            withdrawal["businessDate"].asString() shouldBe hotelApi.currentBusinessDate().toString()
            movement(path, auth, "SUPPLY", "25.00", "Troco").expect(201)
            (etagOf(path) == etag) shouldBe false

            movement(path, auth, "SUPPLY", "0.00", "Troco").problem(400, "CASHIER_SHIFT_INVALID")
            movement(path, auth, "SUPPLY", "5.00", " ").problem(400, "VALIDATION_FAILED")
            movement(path, iam.asTestUser(operator()), "SUPPLY", "5.00", "Troco")
                .problem(403, "CASHIER_SHIFT_OPERATOR_REQUIRED")
            movement(path, auth, "SUPPLY", "5.00", "Troco", idempotencyKey = null)
                .problem(400, "IDEMPOTENCY_KEY_REQUIRED")
            val key = UuidV7.generate().toString()
            movement(path, auth, "SUPPLY", "5.00", "Troco", idempotencyKey = key).expect(201)
            val replay = movement(path, auth, "SUPPLY", "5.00", "Troco", idempotencyKey = key)
            replay.getHeader(IDEMPOTENT_REPLAYED_HEADER) shouldBe "true"
            call(GET, path, auth).expect(200)["movements"].values().size shouldBe 3

            val id = withdrawal.uuid("id")
            for (sql in listOf(
                "UPDATE billing.cash_movement SET amount = 1 WHERE id = :id",
                "UPDATE billing.cash_movement SET reason = 'x' WHERE id = :id",
                "DELETE FROM billing.cash_movement WHERE id = :id",
            )) {
                withClue("papel da aplicação: $sql") {
                    shouldThrow<DataAccessException> {
                        transactions.executeWithoutResult { jdbc.sql(sql).param("id", id).update() }
                    }.mostSpecificCause.message shouldContain "permission denied"
                }
                withClue("dono: $sql") {
                    shouldThrow<DataAccessException> { owner.sql(sql).param("id", id).update() }
                        .mostSpecificCause.message shouldContain "imutáveis"
                }
            }
            withClue("TRUNCATE") {
                shouldThrow<DataAccessException> {
                    transactions.executeWithoutResult { jdbc.sql("TRUNCATE billing.cash_movement").update() }
                }.mostSpecificCause.message shouldContain "permission denied"
            }
        }

    // --- Fechamento cego ----------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // o cenário do fechamento cego, com cada leitor, a contagem e as recusas depois
    fun `a blind close hides the expected amount before the count, then records the difference per method`(): Unit =
        with(billing) {
            val operator = operator()
            val auth = iam.asTestUser(operator)
            val reviewer = iam.createUserWith(TestGrant(read, ALL), TestGrant(review, ALL))
            val shift = openShift(auth, cashPoint(blind = true), "200.00").expect(201)
            withClue("na abertura") { shift.hidesExpected() }
            val path = "/api/v1/cashier-shifts/${shift["id"].asString()}"
            movement(path, auth, "WITHDRAWAL", "50.00", "Cofre").expect(201).toString() shouldNotContain "expected"
            movement(path, auth, "SUPPLY", "10.00", "Troco").expect(201)

            withClue("o operador, no turno e no turno corrente") {
                call(GET, path, auth).expect(200).hidesExpected()
                call(GET, "/api/v1/cashier-shifts/current", auth).expect(200).hidesExpected()
            }
            withClue("quem lê todos mas não confere") {
                call(GET, path, iam.asTestUser(iam.createUserWith(TestGrant(read, ALL)))).expect(200).hidesExpected()
            }
            withClue("a lista não traz o esperado") {
                val item = listAll("/api/v1/cashier-shifts?operatorId=${operator.id}").single()
                item.has("expected") shouldBe false
                item["differenceTotal"].isNull shouldBe true
            }
            withClue("quem confere vê o esperado sempre") {
                val seen = call(GET, path, iam.asTestUser(reviewer)).expect(200)
                seen["expectedVisible"].asBoolean() shouldBe true
                seen.expectedOf("CASH") shouldBe "160.00"
                seen["expectedTotal"].asString() shouldBe "160.00"
            }
            call(GET, "$path/report", auth).problem(409, "CASHIER_SHIFT_NOT_CLOSED")

            val report =
                close(path, auth, mapOf("CASH" to "150.00", "PIX" to "0.00")).expect(200)
            val closed = report["shift"]
            closed["status"].asString() shouldBe "CLOSED"
            closed["expectedVisible"].asBoolean() shouldBe true
            closed.expectedOf("CASH") shouldBe "160.00"
            closed.countOf("CASH")["declared"].asString() shouldBe "150.00"
            closed.countOf("CASH")["expected"].asString() shouldBe "160.00"
            closed.countOf("CASH")["difference"].asString() shouldBe "-10.00"
            closed.countOf("DEBIT_CARD")["difference"].asString() shouldBe "0.00"
            closed["counts"].values().map { it["method"].asString() } shouldContainExactly
                PaymentMethod.entries.map { it.name }
            closed["differenceTotal"].asString() shouldBe "-10.00"
            closed["hasDifference"].asBoolean() shouldBe true
            closed["closedOn"].asString() shouldBe hotelApi.currentBusinessDate().toString()

            val stored =
                jdbc
                    .sql(
                        "SELECT method, declared, expected, difference FROM billing.cashier_shift_count " +
                            "WHERE shift_id = :id",
                    ).param("id", shift.uuid("id"))
                    .query { rs, _ ->
                        rs.getString("method") to
                            listOf("declared", "expected", "difference").map { rs.getBigDecimal(it).toPlainString() }
                    }.list()
                    .toMap()
            stored.keys shouldContainExactlyInAnyOrder PaymentMethod.entries.map { it.name }
            stored["CASH"] shouldBe listOf("150.00", "160.00", "-10.00")

            withClue("depois do fechamento, o esperado sai a quem lê o turno; o relatório também") {
                call(GET, path, auth).expect(200).expectedOf("CASH") shouldBe "160.00"
                val again = call(GET, "$path/report", auth).expect(200)
                again["shift"].countOf("CASH")["difference"].asString() shouldBe "-10.00"
                again["generatedAt"].isNull shouldBe false
            }
            withClue("turno fechado não recebe movimento nem outro fechamento") {
                movement(path, auth, "SUPPLY", "1.00", "Troco").problem(409, "CASHIER_SHIFT_CLOSED")
                close(path, auth, mapOf("CASH" to "1.00")).problem(409, "CASHIER_SHIFT_CLOSED")
                close(path, iam.asTestUser(reviewer), mapOf()).problem(403, "PERMISSION_DENIED")
            }
            withClue("as contagens são imutáveis") {
                shouldThrow<DataAccessException> {
                    transactions.executeWithoutResult {
                        jdbc
                            .sql("UPDATE billing.cashier_shift_count SET declared = 160 WHERE shift_id = :id")
                            .param("id", shift.uuid("id"))
                            .update()
                    }
                }.mostSpecificCause.message shouldContain "permission denied"
                shouldThrow<DataAccessException> {
                    owner
                        .sql("UPDATE billing.cashier_shift SET status = 'OPEN' WHERE id = :id")
                        .param("id", shift.uuid("id"))
                        .update()
                }.mostSpecificCause.message shouldContain "não muda"
            }
            withClue("o operador pode abrir outro depois") { openShift(auth, cashPoint(), "0.00").expect(201) }
        }

    @Test
    fun `a shift that is not blind shows the expected amount to the operator while open`(): Unit =
        with(billing) {
            val auth = iam.asTestUser(operator())
            val shift = openShift(auth, cashPoint(blind = false), "80.00").expect(201)
            shift["expectedVisible"].asBoolean() shouldBe true
            shift.expectedOf("CASH") shouldBe "80.00"
            shift.expectedOf("PIX") shouldBe "0.00"
            val path = "/api/v1/cashier-shifts/${shift["id"].asString()}"
            close(path, auth, mapOf("CASH" to "80.00")).expect(200)["shift"]["hasDifference"].asBoolean() shouldBe false
        }

    @Test
    fun `the close count refuses a repeated method and a negative amount, and only the operator closes`(): Unit =
        with(billing) {
            val auth = iam.asTestUser(operator())
            val shift = openShift(auth, cashPoint(), "0.00").expect(201)
            val path = "/api/v1/cashier-shifts/${shift["id"].asString()}"
            val repeated =
                call(
                    POST,
                    "$path/close",
                    auth,
                    mapOf("counts" to listOf(count("CASH", "1.00"), count("CASH", "2.00"))),
                ).problem(400, "CASHIER_COUNT_INVALID")
            repeated["errors"][0]["field"].asString() shouldBe "counts[1].method"
            call(POST, "$path/close", auth, mapOf("counts" to listOf(count("CASH", "-1.00"))))
                .problem(400, "CASHIER_COUNT_INVALID")["errors"][0]["field"]
                .asString() shouldBe "counts[0].amount"
            call(POST, "$path/close", auth, mapOf("counts" to listOf(count("PIX", "-1000000.01"))))
                .problem(400, "CASHIER_COUNT_INVALID")["errors"][0]["field"]
                .asString() shouldBe "counts[0].amount"
            close(path, iam.asTestUser(operator()), mapOf("CASH" to "0.00"))
                .problem(403, "CASHIER_SHIFT_OPERATOR_REQUIRED")
            withClue("o operador é conferido antes da contagem: quem não é recebe 403, não 400") {
                call(
                    POST,
                    "$path/close",
                    iam.asTestUser(operator()),
                    mapOf("counts" to listOf(count("CASH", "1.00"), count("CASH", "-2.00"))),
                ).problem(403, "CASHIER_SHIFT_OPERATOR_REQUIRED")
            }
            call(GET, path, auth).expect(200)["status"].asString() shouldBe "OPEN"
        }

    // --- Escopo OWN ---------------------------------------------------------------------------------

    @Test
    fun `an operator without ALL lists and reads only their own shifts`(): Unit =
        with(billing) {
            val first = operator()
            val second = operator()
            val point = cashPoint()
            val mine = openShift(iam.asTestUser(first), point, "0.00").expect(201)["id"].asString()
            val theirs = openShift(iam.asTestUser(second), point, "0.00").expect(201)["id"].asString()

            val listed = listAs(iam.asTestUser(first), "/api/v1/cashier-shifts?cashPointId=$point")
            listed.map { it["id"].asString() } shouldContainExactly listOf(mine)
            withClue("nenhum turno de outro operador, em toda a lista") {
                listAs(
                    iam.asTestUser(first),
                    "/api/v1/cashier-shifts",
                ).map { it["operatorId"].asString() }.toSet() shouldBe
                    setOf(first.id.toString())
            }
            listAs(iam.asTestUser(first), "/api/v1/cashier-shifts?operatorId=${second.id}").shouldBeEmpty()
            call(GET, "/api/v1/cashier-shifts/$theirs", iam.asTestUser(first)).problem(404, "CASHIER_SHIFT_NOT_FOUND")
            call(GET, "/api/v1/cashier-shifts/$theirs/report", iam.asTestUser(first))
                .problem(404, "CASHIER_SHIFT_NOT_FOUND")

            val supervisor = iam.createUserWith(TestGrant(read, ALL))
            listAs(iam.asTestUser(supervisor), "/api/v1/cashier-shifts?cashPointId=$point").map {
                it["id"].asString()
            } shouldContainExactlyInAnyOrder listOf(mine, theirs)
            call(GET, "/api/v1/cashier-shifts/$theirs", iam.asTestUser(supervisor)).expect(200)
            call(GET, "/api/v1/cashier-shifts", iam.asTestUser(iam.createUserWith(TestGrant(operate, ALL))))
                .problem(403, "PERMISSION_DENIED")
        }

    // --- requireOpenShift ---------------------------------------------------------------------------

    @Test
    fun `requireOpenShift returns the open shift of the user inside a write transaction, or refuses with 422`() {
        val operator = operator()
        val noShift = operator()
        val shift = with(billing) { openShift(iam.asTestUser(operator), cashPoint(), "30.00").expect(201) }

        val found = transactions.execute { billingApi.requireOpenShift(operator.id) }.shouldNotBeNull()
        found.id.value shouldBe UUID.fromString(shift["id"].asString())
        found.operatorId shouldBe operator.id
        found.status shouldBe CashierShiftStatus.OPEN
        found.openingFloat shouldBe Money.of("30.00")
        billingApi
            .cashierShift(found.id)
            .shouldNotBeNull()
            .number
            .toString() shouldBe shift["number"].asString()

        shouldThrow<BusinessRuleException> {
            transactions.execute { billingApi.requireOpenShift(noShift.id) }
        }.code.value shouldBe "CASHIER_SHIFT_REQUIRED"
        withClue("fora de uma transação de escrita") {
            shouldThrow<IllegalStateException> { billingApi.requireOpenShift(operator.id) }
            shouldThrow<IllegalStateException> {
                TransactionTemplate(checkNotNull(transactions.transactionManager)).apply { isReadOnly = true }.execute {
                    billingApi.requireOpenShift(operator.id)
                }
            }
        }

        with(billing) {
            close("/api/v1/cashier-shifts/${found.id}", iam.asTestUser(operator), mapOf("CASH" to "30.00")).expect(200)
        }
        shouldThrow<BusinessRuleException> {
            transactions.execute { billingApi.requireOpenShift(operator.id) }
        }.code.value shouldBe "CASHIER_SHIFT_REQUIRED"
    }

    @Test
    fun `requireOpenShift holds the shift until the caller commits, so a close waits for the receipt`() {
        val operator = operator()
        val auth = iam.asTestUser(operator)
        val shift = with(billing) { openShift(auth, cashPoint(), "0.00").expect(201) }
        val path = "/api/v1/cashier-shifts/${shift["id"].asString()}"
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val receipt =
                pool.submit(
                    Callable {
                        transactions.execute {
                            billingApi.requireOpenShift(operator.id)
                            locked.countDown()
                            release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        }
                    },
                )
            locked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
            val closing = pool.submit(Callable { with(billing) { close(path, auth, mapOf()) } })
            withClue("o fechamento espera a trava do recebimento") {
                runCatching { closing.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }.isFailure shouldBe true
            }
            release.countDown()
            receipt.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
            closing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status shouldBe 200
        } finally {
            pool.shutdownNow()
        }
    }

    // --- Notificação de diferença -------------------------------------------------------------------

    @Test
    fun `a close with a difference notifies exactly the reviewers but the operator, with no personal data`(): Unit =
        with(billing) {
            val reviewer = iam.createUserWith(TestGrant(read, ALL), TestGrant(review, ALL))
            val bystander = iam.createUserWith(TestGrant(read, ALL), TestGrant(operate, ALL))
            // O operador que também confere turnos não é notificado da própria diferença.
            val operator = iam.createUserWith(TestGrant(operate, ALL), TestGrant(read, ALL), TestGrant(review, ALL))
            val pointName = "Bar ${suffix()}"
            val shift = openShift(iam.asTestUser(operator), cashPoint(name = pointName), "100.00").expect(201)
            val number = shift["number"].asString()
            close(
                "/api/v1/cashier-shifts/${shift["id"].asString()}",
                iam.asTestUser(operator),
                mapOf("CASH" to "90.00"),
            ).expect(200)

            val notified = notificationsOf(number)
            val expected = iamApi.usersWithPermission(BillingPermissions.SHIFTS_REVIEW) - operator.id
            notified.map { it.recipient } shouldContainExactlyInAnyOrder expected.toList()
            notified.map { it.recipient } shouldContain reviewer.id
            notified.map { it.recipient } shouldNotContain operator.id
            notified.map { it.recipient } shouldNotContain bystander.id
            val notice = notified.first()
            notice.title shouldBe "Diferença no fechamento do caixa $number"
            notice.body shouldContain pointName
            notice.body shouldContain "Dinheiro: falta de R$ 10,00."
            for (text in listOf(notice.title, notice.body)) {
                text shouldNotContain operator.login
                text shouldNotContain operator.id.toString()
            }

            withClue("o destinatário a vê no painel; o operador e os demais, não") {
                call(GET, "/api/v1/notifications", iam.asTestUser(reviewer)).expect(200)["items"].values().map {
                    it["type"].asString()
                } shouldContain "billing.cashier-shift-difference"
                listOf(operator, bystander).forEach { user ->
                    call(GET, "/api/v1/notifications", iam.asTestUser(user)).expect(200)["items"].values().map {
                        it["type"].asString()
                    } shouldNotContain "billing.cashier-shift-difference"
                }
            }
        }

    @Test
    fun `a close without a difference notifies no one`(): Unit =
        with(billing) {
            val auth = iam.asTestUser(operator())
            val shift = openShift(auth, cashPoint(), "50.00").expect(201)
            close("/api/v1/cashier-shifts/${shift["id"].asString()}", auth, mapOf("CASH" to "50.00"))
                .expect(200)["shift"]["hasDifference"]
                .asBoolean() shouldBe false
            notificationsOf(shift["number"].asString()).shouldBeEmpty()
        }

    @Test
    fun `a close rolled back with a difference leaves the shift open and notifies no one`() {
        val operator = operator()
        val reviewer = iam.createUserWith(TestGrant(read, ALL), TestGrant(review, ALL))
        val shift = with(billing) { openShift(iam.asTestUser(operator), cashPoint(), "50.00").expect(201) }
        val id = CashierShiftId(UUID.fromString(shift["id"].asString()))
        val number = shift["number"].asString()
        val counts = listOf(DeclaredCount(PaymentMethod.CASH, Money.of("10.00")))
        actingAs(operator.id) {
            transactions.executeWithoutResult {
                shiftService.close(id, counts).shift.hasDifference shouldBe true
                withClue("dentro da transação, a notificação já foi criada pelo fechamento") {
                    notificationsOf(number).map { it.recipient } shouldContain reviewer.id
                }
                it.setRollbackOnly()
            }
        }
        notificationsOf(number).shouldBeEmpty()
        with(
            billing,
        ) { call(GET, "/api/v1/cashier-shifts/$id", admin).expect(200)["status"].asString() shouldBe "OPEN" }

        withClue("o mesmo fechamento confirmado notifica o conferente") {
            actingAs(operator.id) { transactions.executeWithoutResult { shiftService.close(id, counts) } }
            notificationsOf(number).map { it.recipient } shouldContain reviewer.id
        }
    }

    // --- Apoio --------------------------------------------------------------------------------------

    private data class StoredNotification(
        val recipient: UserId,
        val title: String,
        val body: String,
    )

    private fun notificationsOf(number: String): List<StoredNotification> =
        jdbc
            .sql(
                "SELECT recipient_id, title, body FROM notifications.notification " +
                    "WHERE type = 'billing.cashier-shift-difference' AND title LIKE :number",
            ).param("number", "%$number")
            .query { rs, _ ->
                StoredNotification(
                    UserId(rs.getObject("recipient_id", UUID::class.java)),
                    rs.getString("title"),
                    rs.getString("body"),
                )
            }.list()

    private fun operator(): TestUser = iam.createUserWith(TestGrant(operate, ALL), TestGrant(read, OWN))

    private fun cashPoint(
        blind: Boolean = true,
        name: String = "Caixa ${suffix()}",
    ): UUID =
        with(billing) {
            call(
                POST,
                "/api/v1/cash-points",
                admin,
                mapOf("name" to name, "blindClose" to blind),
            ).expect(201).uuid("id")
        }

    private fun BillingTestSupport.openShift(
        auth: RequestPostProcessor,
        cashPoint: UUID,
        float: String,
        idempotencyKey: String? = UuidV7.generate().toString(),
    ): MockHttpServletResponse =
        call(
            POST,
            "/api/v1/cashier-shifts",
            auth,
            mapOf("cashPointId" to cashPoint, "openingFloat" to float),
            idempotencyKey = idempotencyKey,
        )

    @Suppress("LongParameterList") // os campos do movimento e a chave
    private fun BillingTestSupport.movement(
        path: String,
        auth: RequestPostProcessor,
        type: String,
        amount: String,
        reason: String,
        idempotencyKey: String? = UuidV7.generate().toString(),
    ): MockHttpServletResponse =
        call(
            POST,
            "$path/movements",
            auth,
            mapOf("type" to type, "amount" to amount, "reason" to reason),
            idempotencyKey = idempotencyKey,
        )

    private fun BillingTestSupport.close(
        path: String,
        auth: RequestPostProcessor,
        counts: Map<String, String>,
    ): MockHttpServletResponse =
        call(POST, "$path/close", auth, mapOf("counts" to counts.map { (method, amount) -> count(method, amount) }))

    private fun count(
        method: String,
        amount: String,
    ) = mapOf("method" to method, "amount" to amount)

    private fun BillingTestSupport.listAs(
        auth: RequestPostProcessor,
        path: String,
    ): List<JsonNode> {
        val items = mutableListOf<JsonNode>()
        var cursor: String? = null
        do {
            val separator = if ('?' in path) '&' else '?'
            val page =
                call(GET, "$path${separator}limit=200" + cursor?.let { "&cursor=$it" }.orEmpty(), auth).expect(200)
            items += page["items"].values()
            cursor = page["nextCursor"]?.takeUnless(JsonNode::isNull)?.asString()
        } while (cursor != null)
        return items
    }

    private fun JsonNode.hidesExpected() {
        this["expectedVisible"].asBoolean() shouldBe false
        this["expected"].isNull shouldBe true
        this["expectedTotal"].isNull shouldBe true
        this["counts"].isNull shouldBe true
        toString() shouldNotContain "\"withdrawals\""
    }

    private fun JsonNode.expectedOf(method: String): String =
        this["expected"].values().single { it["method"].asString() == method }["expected"].asString()

    private fun JsonNode.countOf(method: String): JsonNode =
        this["counts"].values().single { it["method"].asString() == method }

    private fun openShiftsOf(operator: UserId): Long =
        jdbc
            .sql("SELECT count(*) FROM billing.cashier_shift WHERE operator_id = :id AND status = 'OPEN'")
            .param("id", operator.value)
            .query(Long::class.java)
            .single()

    private fun suffix() = UuidV7.generate().toString().takeLast(SUFFIX_LENGTH)

    /** Roda [block] com o ator [userId] no contexto de segurança (fora do `MockMvc`). */
    private fun <T> actingAs(
        userId: UserId,
        block: () -> T,
    ): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(userId.toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 20L
        const val WAIT_MILLIS = 700L
        const val CONCURRENT = 5
        const val SUFFIX_LENGTH = 12
    }
}
