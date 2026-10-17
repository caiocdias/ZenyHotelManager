package com.zeny.hotel.billing

import com.zeny.hotel.billing.BillingTestSupport.Companion.definition
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.shared.IDEMPOTENT_REPLAYED_HEADER
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Lançamentos (M3 E04, RF-CTA-02 e 03, RN-01, RN-03, RF-ACS-06 a 08): imutáveis no banco (privilégio e trigger), na
 * data operacional (não na do relógio), idempotentes pela `Idempotency-Key`, o estorno inverso e vinculado uma vez
 * só (inclusive em duas requisições simultâneas), o ajuste parcial, e as correções sensíveis com o limite `maxAmount`
 * e a aprovação de superior auditada com o solicitante e o aprovador.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class)
@Suppress("LargeClass", "LongParameterList") // cenários de ponta a ponta; os beans que eles usam
class PostingsApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val clock: MutableClock,
    @Autowired flyway: Flyway,
) {
    private val billing = BillingTestSupport(iam, mockMvc, jsonMapper)
    private val owner = JdbcClient.create(flyway.configuration.dataSource)
    private val reverse = definition(BillingPermissions.POSTINGS_REVERSE)
    private val adjust = definition(BillingPermissions.POSTINGS_ADJUST)
    private val create = definition(BillingPermissions.POSTINGS_CREATE)
    private val read = definition(BillingPermissions.FOLIOS_READ)

    // --- Lançamento ----------------------------------------------------------------------------------

    @Test
    fun `a posting is recorded on the hotel business date, not on the calendar date of the clock`(): Unit =
        with(billing) {
            val folio = openFolio()
            val businessDate = hotelApi.currentBusinessDate()
            var moved = Duration.ofDays(3)
            clock.advance(moved)
            try {
                while (calendarDate() == businessDate) {
                    clock.advance(Duration.ofDays(1))
                    moved = moved.plusDays(1)
                }
                withClue("o relógio precisa estar noutra data") { (calendarDate() == businessDate) shouldBe false }

                val posting = post(folio, codeId("ROOM_CHARGE"), "320.00", quantity = "2")
                posting["businessDate"].asString() shouldBe businessDate.toString()
                posting["amount"].asString() shouldBe "640.00"
                posting["source"].asString() shouldBe "MANUAL"
                OffsetDateTime.parse(posting["postedAt"].asString()).toInstant() shouldBe
                    clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                stored(UUID.fromString(posting["id"].asString()), "business_date", LocalDate::class.java) shouldBe
                    businessDate
            } finally {
                clock.advance(moved.negated())
            }
        }

    @Test
    fun `a posting needs an Idempotency-Key and the same key never posts twice`(): Unit =
        with(billing) {
            val folio = openFolio()
            val body = mapOf("transactionCodeId" to codeId("LAUNDRY"), "quantity" to 3, "unitPrice" to "12.00")
            val path = "/api/v1/folios/$folio/postings"
            call(POST, path, admin, body, idempotencyKey = null).problem(400, "IDEMPOTENCY_KEY_REQUIRED")

            val key = UuidV7.generate().toString()
            val first = call(POST, path, admin, body, idempotencyKey = key)
            val again = call(POST, path, admin, body, idempotencyKey = key)
            first.expect(201)["id"] shouldBe again.expect(201)["id"]
            again.getHeader(IDEMPOTENT_REPLAYED_HEADER) shouldBe "true"
            call(POST, path, admin, body + mapOf("quantity" to 4), idempotencyKey = key)
                .problem(422, "IDEMPOTENCY_KEY_REUSED")
            rowsOf(folio) shouldBe 1
            folio(folio)["balance"].asString() shouldBe "36.00"
        }

    @Test
    fun `postings are refused on a missing folio, an unknown or inactive code, and without permission`(): Unit =
        with(billing) {
            val folio = openFolio()
            val room = codeId("ROOM_CHARGE")
            val path = "/api/v1/folios/$folio/postings"
            call(POST, "/api/v1/folios/${UuidV7.generate()}/postings", admin, postingBody(room, "1.00"))
                .problem(404, "FOLIO_NOT_FOUND")
            call(POST, path, admin, postingBody(UuidV7.generate(), "1.00"))
                .problem(400, "UNKNOWN_TRANSACTION_CODE")["errors"][0]["field"]
                .asString() shouldBe "transactionCodeId"
            call(POST, path, admin, postingBody(room, "0.00")).problem(400, "POSTING_INVALID")
            call(POST, path, admin, postingBody(room, "10.00") + mapOf("quantity" to BigDecimal("0.0001")))
                .problem(400, "POSTING_INVALID")

            val inactive =
                UUID.fromString(
                    call(
                        POST,
                        "/api/v1/transaction-codes",
                        admin,
                        mapOf(
                            "code" to "OLD-${suffix()}",
                            "name" to "Velho",
                            "kind" to "CHARGE",
                            "revenueGroup" to "OTHER",
                        ),
                    ).expect(201)["id"].asString(),
                )
            call(POST, "/api/v1/transaction-codes/$inactive/deactivate", admin).expect(200)
            call(POST, path, admin, postingBody(inactive, "5.00")).problem(422, "TRANSACTION_CODE_INACTIVE")

            val reader = iam.asTestUser(iam.createUserWith(TestGrant(read, ALL)))
            call(POST, path, reader, postingBody(room, "1.00")).problem(403, "PERMISSION_DENIED")
            rowsOf(folio) shouldBe 0
        }

    @Test
    fun `a credit code posts a negative amount and the posting is audited without its description`(): Unit =
        with(billing) {
            val folio = openFolio()
            val credit =
                UUID.fromString(
                    call(
                        POST,
                        "/api/v1/transaction-codes",
                        admin,
                        mapOf(
                            "code" to "CORT-${suffix()}",
                            "name" to "Cortesia",
                            "kind" to "CREDIT",
                            "revenueGroup" to "OTHER",
                        ),
                    ).expect(201)["id"].asString(),
                )
            val posting =
                call(
                    POST,
                    "/api/v1/folios/$folio/postings",
                    admin,
                    postingBody(credit, "45.00") + mapOf("description" to "Cortesia ao Sr. Fulano Secreto"),
                ).expect(201)
            posting["amount"].asString() shouldBe "-45.00"
            posting["description"].asString() shouldBe "Cortesia ao Sr. Fulano Secreto"
            val after = auditAfter(UUID.fromString(posting["id"].asString()), "billing.posting.create")
            after shouldContain "\"amount\": \"-45.00\""
            after shouldNotContain "Fulano"
        }

    // --- Imutabilidade (RN-03) -----------------------------------------------------------------------

    @Test
    fun `postings cannot be updated nor deleted, by the application role nor by the schema owner`() {
        val folio = billing.openFolio()
        val posting = UUID.fromString(billing.post(folio, billing.codeId("MISCELLANEOUS"), "10.00")["id"].asString())
        for (sql in listOf(
            "UPDATE billing.posting SET amount = 1 WHERE id = :id",
            "UPDATE billing.posting SET description = 'x' WHERE id = :id",
            "DELETE FROM billing.posting WHERE id = :id",
        )) {
            withClue("papel da aplicação: $sql") {
                shouldThrow<DataAccessException> {
                    transactions.executeWithoutResult { jdbc.sql(sql).param("id", posting).update() }
                }.mostSpecificCause.message shouldContain "permission denied"
            }
            withClue("dono: $sql") {
                shouldThrow<DataAccessException> { owner.sql(sql).param("id", posting).update() }
                    .mostSpecificCause.message shouldContain "imutáveis"
            }
        }
        withClue("TRUNCATE") {
            shouldThrow<DataAccessException> {
                transactions.executeWithoutResult { jdbc.sql("TRUNCATE billing.posting").update() }
            }.mostSpecificCause.message shouldContain "permission denied"
        }
        stored(posting, "amount", BigDecimal::class.java).compareTo(BigDecimal("10.00")) shouldBe 0
    }

    // --- Estorno e ajuste ----------------------------------------------------------------------------

    @Test
    fun `a reversal creates the inverse posting linked to the original, once, and zeroes it`(): Unit =
        with(billing) {
            val folio = openFolio()
            val original = post(folio, codeId("ROOM_CHARGE"), "150.00", quantity = "2")
            val id = original["id"].asString()
            val reversal = reverse(folio, id, admin).expect(201)
            reversal["type"].asString() shouldBe "REVERSAL"
            reversal["correctsId"].asString() shouldBe id
            reversal["amount"].asString() shouldBe "-300.00"
            reversal["transactionCodeId"] shouldBe original["transactionCodeId"]
            reversal["reason"].asString() shouldBe "Lançado na conta errada"
            reversal["businessDate"].asString() shouldBe hotelApi.currentBusinessDate().toString()
            reversal["approvalId"].isNull shouldBe true

            val corrected = call(GET, "/api/v1/folios/$folio/postings/$id", admin).expect(200)
            corrected["reversedBy"].asString() shouldBe reversal["id"].asString()
            corrected["remainingAmount"].asString() shouldBe "0.00"
            folio(folio)["balance"].asString() shouldBe "0.00"

            reverse(folio, id, admin).problem(409, "POSTING_ALREADY_REVERSED")
            adjustPosting(folio, id, "10.00", admin).problem(409, "POSTING_ALREADY_REVERSED")
            reverse(folio, reversal["id"].asString(), admin).problem(422, "POSTING_NOT_CORRECTABLE")
            reverse(openFolio(), id, admin).problem(404, "POSTING_NOT_FOUND")
            postings(folio).map { it["type"].asString() } shouldContainExactly listOf("REGULAR", "REVERSAL")
        }

    @Test
    fun `two simultaneous adjustments never go beyond what remains nor beyond the ceiling of the user`() {
        val folio = billing.openFolio()
        val code = billing.codeId("FOOD_BEVERAGE")
        // Além do que resta: 60 + 60 num lançamento de 100.
        val dinner = billing.post(folio, code, "100.00")["id"].asString()
        concurrently {
            adjustPosting(
                folio,
                dinner,
                "60.00",
                billing.admin,
            )
        }.map { it.status } shouldContainExactlyInAnyOrder
            listOf(201, 422)
        correctedTotal(dinner) shouldBe BigDecimal("60.00")

        // Além do teto cumulativo de quem ajusta: 30 + 30 com teto de 50.
        val lunch = billing.post(folio, code, "100.00")["id"].asString()
        val capped =
            Requester(
                iam.createUserWith(
                    TestGrant(read, ALL),
                    TestGrant(adjust, ALL, mapOf(BillingPermissions.MAX_AMOUNT to LimitValue.Max(BigDecimal("50")))),
                ),
            )
        val results = concurrently { adjustPosting(folio, lunch, "30.00", capped.auth) }
        results.map { it.status } shouldContainExactlyInAnyOrder listOf(201, 403)
        with(billing) { results.single { it.status == 403 }.problem(403, "APPROVAL_REQUIRED") }
        correctedTotal(lunch) shouldBe BigDecimal("30.00")
    }

    @Test
    fun `two simultaneous reversals of the same posting - one is recorded, the other gets 409`() {
        val folio = billing.openFolio()
        val id = billing.post(folio, billing.codeId("FOOD_BEVERAGE"), "88.00")["id"].asString()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val statuses =
                List(2) {
                    pool.submit(
                        Callable {
                            start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
                            reverse(folio, id, billing.admin)
                        },
                    )
                }.also { start.countDown() }
                    .map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            statuses.map { it.status } shouldContainExactlyInAnyOrder listOf(201, 409)
            with(billing) { statuses.single { it.status == 409 }.problem(409, "POSTING_ALREADY_REVERSED") }
            jdbc
                .sql("SELECT count(*) FROM billing.posting WHERE corrects_id = :id AND posting_type = 'REVERSAL'")
                .param("id", UUID.fromString(id))
                .query(Long::class.java)
                .single() shouldBe 1L
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `an adjustment is partial and opposite, never beyond what remains, and the reversal inverts the rest`(): Unit =
        with(billing) {
            val folio = openFolio()
            val id = post(folio, codeId("FOOD_BEVERAGE"), "100.00")["id"].asString()
            val adjustment = adjustPosting(folio, id, "30.00", admin).expect(201)
            adjustment["type"].asString() shouldBe "ADJUSTMENT"
            adjustment["amount"].asString() shouldBe "-30.00"
            adjustment["correctsId"].asString() shouldBe id
            adjustPosting(folio, id, "70.01", admin).problem(422, "ADJUSTMENT_EXCEEDS_REMAINING")
            adjustPosting(folio, adjustment["id"].asString(), "1.00", admin).problem(422, "POSTING_NOT_CORRECTABLE")
            call(GET, "/api/v1/folios/$folio/postings/$id", admin).expect(200)["remainingAmount"].asString() shouldBe
                "70.00"

            reverse(folio, id, admin).expect(201)["amount"].asString() shouldBe "-70.00"
            folio(folio)["balance"].asString() shouldBe "0.00"
        }

    // --- Step-up (RF-ACS-07 e 08) e limite (RF-ACS-06) ------------------------------------------------

    @Test
    @Suppress("LongMethod") // o fluxo inteiro: recusa, aprovação, conclusão e trilha
    fun `a reversal without the permission asks for approval, and the approved one is audited with both users`(): Unit =
        with(billing) {
            val folio = openFolio()
            val id = post(folio, codeId("ROOM_CHARGE"), "410.00")["id"].asString()
            val requester = Requester(iam.createUserWith(TestGrant(create, ALL), TestGrant(read, ALL)))
            val approver =
                approverWith(
                    TestGrant(
                        reverse,
                        ALL,
                        mapOf(BillingPermissions.MAX_AMOUNT to LimitValue.Unlimited),
                    ),
                )

            val required = reverse(folio, id, requester.auth).problem(403, "APPROVAL_REQUIRED")
            required["action"].asString() shouldBe "billing.posting.reverse"
            required["permission"].asString() shouldBe "billing.postings.reverse"
            required["resourceRef"].asString() shouldBe "billing.posting:$id"
            required["limit"]["key"].asString() shouldBe "maxAmount"
            required["limit"]["amount"].asString() shouldBe "410"
            rowsOf(folio) shouldBe 1

            val approval = approve(requester, required, approver).expect(201)
            val approvalId = UUID.fromString(approval["approvalId"].asString())
            val token = approval["approvalToken"].asString()
            // A repetição com o token usa outra Idempotency-Key (a primeira guardou a recusa).
            val reversal = reverse(folio, id, requester.auth, token).expect(201)
            reversal["approvalId"].asString() shouldBe approvalId.toString()
            reversal["approvedBy"].asString() shouldBe approver.user.id.toString()
            reversal["postedBy"].asString() shouldBe requester.user.id.toString()
            stored(UUID.fromString(reversal["id"].asString()), "approved_by", UUID::class.java) shouldBe
                approver.user.id.value

            val entries =
                jdbc
                    .sql(
                        "SELECT entity_type, user_id, device_session_id FROM audit.audit_entry " +
                            "WHERE action = 'billing.posting.reverse' AND approval_id = :id",
                    ).param("id", approvalId)
                    .query { rs, _ ->
                        Triple(
                            rs.getString(1),
                            rs.getObject(2, UUID::class.java),
                            rs.getObject(3, UUID::class.java),
                        )
                    }.list()
            entries.map { it.first } shouldContainExactlyInAnyOrder listOf("billing.posting", "iam.approval")
            entries.forEach {
                it.second shouldBe requester.user.id.value
                it.third shouldBe requester.sessionId
            }
            val approvalEntry =
                jsonMapper.readTree(
                    jdbc
                        .sql(
                            "SELECT after_state::text FROM audit.audit_entry WHERE approval_id = :id " +
                                "AND entity_type = 'iam.approval' AND action = 'billing.posting.reverse'",
                        ).param("id", approvalId)
                        .query(String::class.java)
                        .single(),
                )
            approvalEntry["approverId"].asString() shouldBe approver.user.id.toString()
            approvalEntry["resourceRef"].asString() shouldBe "billing.posting:$id"
        }

    @Test
    @Suppress("LongMethod") // dentro do limite, acima dele e a aprovação de quem cobre o valor
    fun `an adjustment within the limit needs no approval, above it needs an approver whose limit covers it`(): Unit =
        with(billing) {
            val folio = openFolio()
            val id = post(folio, codeId("FOOD_BEVERAGE"), "500.00")["id"].asString()
            val requester =
                Requester(
                    iam.createUserWith(
                        TestGrant(read, ALL),
                        TestGrant(
                            adjust,
                            ALL,
                            mapOf(BillingPermissions.MAX_AMOUNT to LimitValue.Max(BigDecimal("50"))),
                        ),
                    ),
                )
            // Dentro do limite: sem aprovação.
            adjustPosting(folio, id, "30.00", requester.auth).expect(201)["approvalId"].isNull shouldBe true

            // O limite é cumulativo por lançamento (contra o fracionamento): 30 + 30 = 60 passa do teto de 50.
            val required = adjustPosting(folio, id, "30.00", requester.auth).problem(403, "APPROVAL_REQUIRED")
            required["action"].asString() shouldBe "billing.posting.adjust"
            required["permission"].asString() shouldBe "billing.postings.adjust"
            required["limit"]["key"].asString() shouldBe "maxAmount"
            required["limit"]["amount"].asString() shouldBe "60"
            val short =
                approverWith(
                    TestGrant(
                        adjust,
                        ALL,
                        mapOf(BillingPermissions.MAX_AMOUNT to LimitValue.Max(BigDecimal("59.99"))),
                    ),
                )
            approve(requester, required, short).problem(403, "APPROVER_NOT_ALLOWED")

            val enough =
                approverWith(
                    TestGrant(
                        adjust,
                        ALL,
                        mapOf(BillingPermissions.MAX_AMOUNT to LimitValue.Max(BigDecimal("60"))),
                    ),
                )
            val token = approve(requester, required, enough).expect(201)["approvalToken"].asString()
            // O token vale para este total: outro valor (total 61) não o usa.
            adjustPosting(folio, id, "31.00", requester.auth, token).problem(403, "APPROVAL_TOKEN_MISMATCH")
            val approved = adjustPosting(folio, id, "30.00", requester.auth, token).expect(201)
            approved["approvedBy"].asString() shouldBe enough.user.id.toString()
            approved["amount"].asString() shouldBe "-30.00"

            // Sem a permissão de ajuste: também pede aprovação, com o total cumulativo (60 + 1).
            val reader = Requester(iam.createUserWith(TestGrant(read, ALL)))
            adjustPosting(folio, id, "1.00", reader.auth)
                .problem(403, "APPROVAL_REQUIRED")["limit"]["amount"]
                .asString() shouldBe "61"
            // O estorno do resto (440,00) também pede, com o total das correções do lançamento (500).
            reverse(folio, id, requester.auth).problem(403, "APPROVAL_REQUIRED")["limit"]["amount"].asString() shouldBe
                "500"
            folio(folio)["balance"].asString() shouldBe "440.00"
        }

    @Test
    fun `a reversal above the reverse limit of the user asks for approval, within it does not`(): Unit =
        with(billing) {
            val folio = openFolio()
            val user =
                Requester(
                    iam.createUserWith(
                        TestGrant(read, ALL),
                        TestGrant(
                            reverse,
                            ALL,
                            mapOf(BillingPermissions.MAX_AMOUNT to LimitValue.Max(BigDecimal("100"))),
                        ),
                    ),
                )
            val big = post(folio, codeId("ROOM_CHARGE"), "150.00")["id"].asString()
            val small = post(folio, codeId("ROOM_CHARGE"), "100.00")["id"].asString()
            val required = reverse(folio, big, user.auth).problem(403, "APPROVAL_REQUIRED")
            required["permission"].asString() shouldBe "billing.postings.reverse"
            required["limit"]["amount"].asString() shouldBe "150"
            // No teto: sem aprovação.
            reverse(folio, small, user.auth).expect(201)["approvalId"].isNull shouldBe true
            folio(folio)["balance"].asString() shouldBe "150.00"
        }

    @Test
    fun `without billing folios read a correction is refused with 403 before the folio is read`(): Unit =
        with(billing) {
            val folio = openFolio()
            val id = post(folio, codeId("FOOD_BEVERAGE"), "100.00")["id"].asString()
            reverse(folio, id, admin).expect(201)
            val stranger = Requester(iam.createUser())
            // Nem 404 (conta ou lançamento inexistente), nem 409 (já estornado), nem 422 (acima do que resta), nem
            // APPROVAL_REQUIRED: quem não tem acesso ao billing não descobre nada da conta.
            reverse(folio, id, stranger.auth).problem(403, "PERMISSION_DENIED")
            adjustPosting(folio, id, "999.00", stranger.auth).problem(403, "PERMISSION_DENIED")
            reverse(UuidV7.generate(), UuidV7.generate().toString(), stranger.auth).problem(403, "PERMISSION_DENIED")
            adjustPosting(UuidV7.generate(), UuidV7.generate().toString(), "1.00", stranger.auth)
                .problem(403, "PERMISSION_DENIED")
        }

    @Test
    fun `the database refuses a second reversal of the same posting, even inserted directly`() {
        val folio = billing.openFolio()
        val id = UUID.fromString(billing.post(folio, billing.codeId("LAUNDRY"), "20.00")["id"].asString())
        with(billing) { reverse(folio, id.toString(), admin).expect(201) }
        val insert =
            """
            INSERT INTO billing.posting (id, folio_id, transaction_code_id, posting_type, quantity, unit_price, amount,
                description, source, business_date, posted_at, corrects_id, reason)
            SELECT :newId, folio_id, transaction_code_id, 'REVERSAL', 1, -amount, -amount, description, 'MANUAL',
                business_date, now(), id, 'Segundo estorno'
            FROM billing.posting WHERE id = :id
            """.trimIndent()
        shouldThrow<DataAccessException> {
            owner
                .sql(insert)
                .param("newId", UuidV7.generate())
                .param("id", id)
                .update()
        }.mostSpecificCause.message shouldContain "posting_reversal_uk"
        jdbc
            .sql("SELECT count(*) FROM billing.posting WHERE corrects_id = :id")
            .param("id", id)
            .query(Long::class.java)
            .single() shouldBe 1L
    }

    @Test
    fun `manager and finance correct without a ceiling, receptionist and cashier always ask for approval`() {
        val grants =
            jdbc
                .sql(
                    """
                    SELECT r.template_key, g.permission_code, l.unlimited FROM iam.role r
                    JOIN iam.role_grant g ON g.role_id = r.id
                    LEFT JOIN iam.role_grant_limit l ON l.grant_id = g.id AND l.limit_key = 'maxAmount'
                    WHERE r.template_key IN ('MANAGER', 'FINANCE', 'RECEPTIONIST', 'FNB_CASHIER')
                      AND g.permission_code IN ('billing.postings.reverse', 'billing.postings.adjust')
                    """.trimIndent(),
                ).query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getBoolean(3)) }
                .list()
        grants shouldContainExactlyInAnyOrder
            listOf("MANAGER", "FINANCE").flatMap { template ->
                listOf("billing.postings.reverse", "billing.postings.adjust").map { Triple(template, it, true) }
            }
    }

    // --- Apoio -------------------------------------------------------------------------------------

    /** O solicitante numa sessão de dispositivo fixa (o token de aprovação fica vinculado a ela). */
    private inner class Requester(
        val user: TestUser,
        val sessionId: UUID = UuidV7.generate(),
    ) {
        val auth: RequestPostProcessor get() = asUser(user.id.value, sessionId)
    }

    private data class Approver(
        val user: TestUser,
        val pin: String,
    )

    private fun approverWith(vararg grants: TestGrant): Approver {
        val user = iam.createUserWith(*grants)
        val pin = (PIN_MIN..PIN_MAX).random().toString()
        iam.setPin(user, pin)
        return Approver(user, pin)
    }

    private fun approve(
        requester: Requester,
        required: JsonNode,
        approver: Approver,
    ): MockHttpServletResponse =
        iam.call(
            POST,
            "/api/v1/auth/approvals",
            requester.auth,
            mapOf(
                "action" to required["action"].asString(),
                "permission" to required["permission"].asString(),
                "resourceRef" to required["resourceRef"].asString(),
                "limit" to
                    mapOf(
                        "key" to required["limit"]["key"].asString(),
                        "amount" to required["limit"]["amount"].asString(),
                    ),
                "approverLogin" to approver.user.login,
                "approverPin" to approver.pin,
                "reason" to "Conferido com o hóspede",
            ),
        )

    private fun reverse(
        folio: UUID,
        posting: String,
        auth: RequestPostProcessor,
        approvalToken: String? = null,
    ): MockHttpServletResponse =
        billing.call(
            POST,
            "/api/v1/folios/$folio/postings/$posting/reverse",
            auth,
            mapOf("reason" to "Lançado na conta errada"),
            approvalToken = approvalToken,
        )

    private fun adjustPosting(
        folio: UUID,
        posting: String,
        amount: String,
        auth: RequestPostProcessor,
        approvalToken: String? = null,
    ): MockHttpServletResponse =
        billing.call(
            POST,
            "/api/v1/folios/$folio/postings/$posting/adjust",
            auth,
            mapOf("amount" to amount, "reason" to "Valor cobrado a mais"),
            approvalToken = approvalToken,
        )

    private fun postingBody(
        code: UUID,
        unitPrice: String,
    ): Map<String, Any?> = mapOf("transactionCodeId" to code, "unitPrice" to unitPrice)

    /** Roda [call] em duas requisições simultâneas e devolve as duas respostas. */
    private fun concurrently(call: () -> MockHttpServletResponse): List<MockHttpServletResponse> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            return List(2) {
                pool.submit(
                    Callable {
                        start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
                        call()
                    },
                )
            }.also { start.countDown() }
                .map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    /** O total, em valor absoluto, das correções gravadas de um lançamento. */
    private fun correctedTotal(posting: String): BigDecimal =
        jdbc
            .sql("SELECT COALESCE(sum(abs(amount)), 0) FROM billing.posting WHERE corrects_id = :id")
            .param("id", UUID.fromString(posting))
            .query(BigDecimal::class.java)
            .single()
            .setScale(2)

    private fun calendarDate(): LocalDate = LocalDate.ofInstant(clock.instant(), hotelApi.timezone())

    private fun rowsOf(folio: UUID): Long =
        jdbc
            .sql("SELECT count(*) FROM billing.posting WHERE folio_id = :id")
            .param("id", folio)
            .query(Long::class.java)
            .single()

    private fun <T : Any> stored(
        posting: UUID,
        column: String,
        type: Class<T>,
    ): T =
        jdbc
            .sql("SELECT $column FROM billing.posting WHERE id = :id")
            .param("id", posting)
            .query { rs, _ -> rs.getObject(1, type) }
            .single()

    private fun auditAfter(
        entityId: UUID,
        action: String,
    ): String =
        jdbc
            .sql("SELECT after_state::text FROM audit.audit_entry WHERE entity_id = :id AND action = :action")
            .param("id", entityId.toString())
            .param("action", action)
            .query(String::class.java)
            .single()

    private fun suffix(): String =
        UuidV7
            .generate()
            .toString()
            .takeLast(SUFFIX)
            .uppercase()

    private companion object {
        const val TIMEOUT_SECONDS = 15L
        const val PIN_MIN = 1000
        const val PIN_MAX = 9999
        const val SUFFIX = 8
    }
}
