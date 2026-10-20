package com.zeny.hotel.billing

import com.zeny.hotel.billing.BillingTestSupport.Companion.definition
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Encerramento e reabertura de contas (M3 E10, RF-CTA-11, RN-06, RF-ACS-08; decisões do usuário em 2026-10-20): o
 * encerramento só com saldo exatamente zero e sem caução em aberto ainda não vencida, a conta encerrada que recusa
 * lançamento, pagamento, reembolso, caução, transferência e edição (pela API HTTP, pela `BillingApi` e no banco), a
 * reabertura sensível com o solicitante e o aprovador na trilha e o motivo fora dela, e o saldo por dono da
 * `BillingApi` para o check-out (M5).
 */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class)
@Suppress("LargeClass", "LongParameterList") // cenários de ponta a ponta; os beans que eles usam
class FolioLifecycleApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val billingApi: BillingApi,
    @Autowired private val clock: MutableClock,
    @Autowired flyway: Flyway,
) {
    private val billing = BillingTestSupport(iam, mockMvc, jsonMapper)
    private val owner = JdbcClient.create(flyway.configuration.dataSource)
    private val receive = definition(BillingPermissions.PAYMENTS_RECEIVE)
    private val folioRead = definition(BillingPermissions.FOLIOS_READ)
    private val folioManage = definition(BillingPermissions.FOLIOS_MANAGE)
    private val reopen = definition(BillingPermissions.FOLIOS_REOPEN)
    private val operate = definition(BillingPermissions.CASHIER_OPERATE)
    private val postingsCreate = definition(BillingPermissions.POSTINGS_CREATE)

    // --- Encerramento ------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // o saldo, a caução, o encerramento, a trilha e as recusas da conta encerrada
    fun `a folio closes only with a zero balance and no active pre-authorization, and then refuses everything`(): Unit =
        with(billing) {
            val folio = openFolio()
            val room = post(folio, codeId("ROOM_CHARGE"), "100.00")
            val cashier = cashier()
            openShift(cashier.auth)

            val unsettled = close(folio).problem(409, "FOLIO_BALANCE_NOT_ZERO")
            unsettled["balance"].asString() shouldBe "100.00"
            folio(folio)["status"].asString() shouldBe "OPEN"

            val payment = pay(folio, cashier.auth, mapOf("method" to "CASH", "amount" to "100.00")).expect(201)
            val preAuthorization = register(folio, cashier.auth, "300.00", inDays(3)).expect(201)["id"].asString()
            val held = close(folio).problem(409, "FOLIO_HAS_ACTIVE_PRE_AUTHORIZATION")
            held["preAuthorizationIds"].values().map { it.asString() } shouldContainExactly listOf(preAuthorization)
            folio(folio)["status"].asString() shouldBe "OPEN"

            release(folio, preAuthorization, cashier.auth).expect(200)["status"].asString() shouldBe "RELEASED"
            val etag = etagOf("/api/v1/folios/$folio")
            val response = close(folio)
            val closed = response.expect(200)
            closed["status"].asString() shouldBe "CLOSED"
            closed["balance"].asString() shouldBe "0.00"
            closed["closedOn"].asString() shouldBe hotelApi.currentBusinessDate().toString()
            closed["closedBy"].asString() shouldBe iam.adminId().toString()
            closed["closedAt"].isNull shouldBe false
            response.getHeader(HttpHeaders.ETAG) shouldNotBe etag

            withClue("o encerramento fica na trilha, com a conta antes e depois") {
                val entry =
                    jdbc
                        .sql(
                            "SELECT before_state::text, after_state::text FROM audit.audit_entry " +
                                "WHERE action = 'billing.folio.close' AND entity_id = :id AND outcome = 'SUCCEEDED'",
                        ).param("id", folio.toString())
                        .query { rs, _ -> rs.getString(1) to rs.getString(2) }
                        .single()
                entry.first shouldNotContain "CLOSED"
                entry.second shouldNotContain "\"OPEN\""
            }

            withClue("encerrada, recusa tudo o que mexe no saldo ou nela, com 409 FOLIO_CLOSED") {
                close(folio).problem(409, "FOLIO_CLOSED")
                call(
                    POST,
                    "/api/v1/folios/$folio/postings",
                    admin,
                    mapOf("transactionCodeId" to codeId("ROOM_CHARGE"), "unitPrice" to "10.00"),
                ).problem(409, "FOLIO_CLOSED")
                pay(folio, cashier.auth, mapOf("method" to "PIX", "amount" to "10.00")).problem(409, "FOLIO_CLOSED")
                call(
                    POST,
                    "/api/v1/folios/$folio/payments/${payment["id"].asString()}/refund",
                    cashier.auth,
                    mapOf("amount" to "10.00", "reason" to "Devolução"),
                ).problem(409, "FOLIO_CLOSED")
                register(folio, cashier.auth, "50.00", inDays(1)).problem(409, "FOLIO_CLOSED")
                reverseWithReason(folio, room["id"].asString()).problem(409, "FOLIO_CLOSED")
                val other = openFolio()
                val otherPosting = post(other, codeId("LAUNDRY"), "20.00")
                transfer(other, folio, listOf(otherPosting["id"].asString())).problem(409, "FOLIO_CLOSED")
                call(
                    PUT,
                    "/api/v1/folios/$folio",
                    admin,
                    mapOf("creditLimit" to "500.00"),
                    ifMatch = etagOf("/api/v1/folios/$folio"),
                ).problem(409, "FOLIO_CLOSED")
                shouldThrow<ConflictException> {
                    billingApi.post(
                        FolioId(folio),
                        NewPosting(TransactionCodeId(codeId("ROOM_CHARGE")), unitPrice(), source = PostingSource.POS),
                    )
                }.code.value shouldBe "FOLIO_CLOSED"
                postingCount(folio) shouldBe 1L
                balance(folio) shouldBe "0.00"
            }

            withClue("o banco também recusa lançamento, pagamento e caução numa conta encerrada (SQL direto)") {
                refusedByDatabase {
                    owner
                        .sql(
                            """
                            INSERT INTO billing.posting (id, folio_id, transaction_code_id, posting_type, quantity,
                                unit_price, amount, description, source, business_date, posted_at)
                            VALUES (:id, :folio, :code, 'REGULAR', 1, 10, 10, 'Direto', 'MANUAL', current_date, now())
                            """.trimIndent(),
                        ).param("id", UuidV7.generate())
                        .param("folio", folio)
                        .param("code", codeId("ROOM_CHARGE"))
                        .update()
                }
                postingCount(folio) shouldBe 1L
            }
        }

    @Test
    fun `a residual cent of a rounded posting, owed or in credit, blocks the closing`(): Unit =
        with(billing) {
            val folio = openFolio()
            // 3 × 3,33 = 9,99 pela regra de Money; o pagamento de 10,00 deixa a conta credora em um centavo.
            post(folio, codeId("MISCELLANEOUS"), "3.33", quantity = "3")["amount"].asString() shouldBe "9.99"
            val cashier = cashier()
            openShift(cashier.auth)
            pay(folio, cashier.auth, mapOf("method" to "PIX", "amount" to "10.00")).expect(201)
            close(folio).problem(409, "FOLIO_BALANCE_NOT_ZERO")["balance"].asString() shouldBe "-0.01"
            post(folio, codeId("MISCELLANEOUS"), "0.02")
            close(folio).problem(409, "FOLIO_BALANCE_NOT_ZERO")["balance"].asString() shouldBe "0.01"
            folio(folio)["status"].asString() shouldBe "OPEN"
        }

    @Test
    fun `an expired pre-authorization does not block the closing, a partially captured one still valid does`(): Unit =
        with(billing) {
            val folio = openFolio()
            post(folio, codeId("ROOM_CHARGE"), "100.00")
            val cashier = cashier()
            openShift(cashier.auth)
            val id = register(folio, cashier.auth, "300.00", inDays(1)).expect(201)["id"].asString()
            call(
                POST,
                "/api/v1/folios/$folio/card-pre-authorizations/$id/capture",
                cashier.auth,
                mapOf("amount" to "100.00"),
            ).expect(201)["preAuthorization"]["status"].asString() shouldBe "PARTIALLY_CAPTURED"
            balance(folio) shouldBe "0.00"
            close(folio).problem(409, "FOLIO_HAS_ACTIVE_PRE_AUTHORIZATION")

            moveClock(Duration.ofDays(2)) {
                call(GET, "/api/v1/folios/$folio/card-pre-authorizations/$id", admin)
                    .expect(200)["status"]
                    .asString() shouldBe "EXPIRED"
                close(folio).expect(200)["status"].asString() shouldBe "CLOSED"
                withClue("a sobra da vencida ainda é liberada com a conta encerrada (decisão da E09)") {
                    release(folio, id, cashier.auth).expect(200)["releasedAmount"].asString() shouldBe "200.00"
                }
            }
        }

    @Test
    fun `closing needs folios manage, and a missing folio is 404`(): Unit =
        with(billing) {
            val folio = openFolio()
            val reader = iam.createUserWith(TestGrant(folioRead, ALL))
            close(folio, iam.asTestUser(reader)).problem(403, "PERMISSION_DENIED")
            close(UuidV7.generate()).problem(404, "FOLIO_NOT_FOUND")
            val manager = iam.createUserWith(TestGrant(folioRead, ALL), TestGrant(folioManage, ALL))
            close(folio, iam.asTestUser(manager)).expect(200)["closedBy"].asString() shouldBe manager.id.toString()
        }

    // --- Reabertura --------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // a recusa, a aprovação, a trilha sem o motivo, a reabertura gravada e o extrato
    fun `reopening is sensitive, approved by a superior, audited with both, the reason out of the trail`(): Unit =
        with(billing) {
            val folio = openFolio()
            close(folio).expect(200)
            val requester = Operator(iam.createUserWith(TestGrant(folioRead, ALL), TestGrant(folioManage, ALL)))

            withClue("sem a permissão nem a aprovação: 403 APPROVAL_REQUIRED, nada muda") {
                val required = reopenAs(folio, requester.auth).problem(403, "APPROVAL_REQUIRED")
                required["action"].asString() shouldBe "billing.folio.reopen"
                required["permission"].asString() shouldBe "billing.folios.reopen"
                required["resourceRef"].asString() shouldBe "billing.folio:$folio"
                required["limit"]?.takeUnless(JsonNode::isNull).shouldBeNull()
                folio(folio)["status"].asString() shouldBe "CLOSED"
            }
            withClue("o motivo é obrigatório; uma conta aberta não se reabre (antes da aprovação)") {
                reopenAs(folio, requester.auth, reason = " ").problem(400, "VALIDATION_FAILED")
                reopenAs(openFolio(), requester.auth).problem(409, "FOLIO_NOT_CLOSED")
                reopenAs(UuidV7.generate(), requester.auth).problem(404, "FOLIO_NOT_FOUND")
                val stranger = iam.createUserWith(TestGrant(reopen, ALL))
                reopenAs(folio, iam.asTestUser(stranger)).problem(403, "PERMISSION_DENIED")
            }

            val required = reopenAs(folio, requester.auth).problem(403, "APPROVAL_REQUIRED")
            val approver = approverWith(TestGrant(reopen, ALL))
            val approval = approve(requester, required, approver).expect(201)
            val approvalId = UUID.fromString(approval["approvalId"].asString())
            val reopened = reopenAs(folio, requester.auth, approval["approvalToken"].asString()).expect(200)
            reopened["status"].asString() shouldBe "OPEN"
            reopened["closedOn"].isNull shouldBe true
            reopened["reopenedOn"].asString() shouldBe hotelApi.currentBusinessDate().toString()
            reopened["reopenedBy"].asString() shouldBe requester.user.id.toString()

            withClue("a reabertura fica gravada com o motivo, o encerramento desfeito e a aprovação") {
                val row =
                    jdbc
                        .sql(
                            "SELECT reason, reopened_by, approval_id, approved_by FROM billing.folio_reopening " +
                                "WHERE folio_id = :id",
                        ).param("id", folio)
                        .query { rs, _ ->
                            listOf(rs.getString(1), rs.getObject(2), rs.getObject(3), rs.getObject(4))
                        }.single()
                row shouldBe listOf(REASON, requester.user.id.value, approvalId, approver.user.id.value)
            }
            withClue("a trilha: as entradas da ação com a aprovação e o solicitante, sem o motivo") {
                val entries =
                    jdbc
                        .sql(
                            "SELECT entity_type, user_id, coalesce(after_state::text, '') FROM audit.audit_entry " +
                                "WHERE action = 'billing.folio.reopen' AND approval_id = :id",
                        ).param("id", approvalId)
                        .query { rs, _ -> Triple(rs.getString(1), rs.getObject(2, UUID::class.java), rs.getString(3)) }
                        .list()
                entries.map { it.first } shouldContainExactlyInAnyOrder
                    listOf("billing.folio", "billing.folio-reopening", "iam.approval")
                entries.forEach {
                    it.second shouldBe requester.user.id.value
                    it.third shouldNotContain REASON
                }
            }
            withClue("o extrato interno mostra a reabertura com o motivo; a prévia do hóspede, não") {
                val internal = call(GET, "/api/v1/folios/$folio/statement", admin).expect(200)
                val listed = internal["reopenings"].values().single()
                listed["reason"].asString() shouldBe REASON
                listed["approvedBy"].asString() shouldBe approver.user.id.toString()
                listed["reopenedBy"].asString() shouldBe requester.user.id.toString()
                call(GET, "/api/v1/folios/$folio/statement?audience=GUEST", admin)
                    .expect(200)["reopenings"]
                    .isNull shouldBe true
            }
            withClue("reaberta, a conta volta a aceitar lançamentos; o token não vale de novo") {
                post(folio, codeId("LAUNDRY"), "15.00")
                close(folio).problem(409, "FOLIO_BALANCE_NOT_ZERO")
                reopenAs(folio, requester.auth, approval["approvalToken"].asString()).problem(409, "FOLIO_NOT_CLOSED")
            }
        }

    @Test
    fun `whoever holds folios reopen reopens without approval, and only manager and finance get it`(): Unit =
        with(billing) {
            val folio = openFolio()
            close(folio).expect(200)
            val supervisor = iam.createUserWith(TestGrant(folioRead, ALL), TestGrant(reopen, ALL))
            reopenAs(folio, asUser(supervisor.id.value, UuidV7.generate())).expect(200)["status"].asString() shouldBe
                "OPEN"
            jdbc
                .sql("SELECT approval_id IS NULL FROM billing.folio_reopening WHERE folio_id = :id")
                .param("id", folio)
                .query(Boolean::class.java)
                .single() shouldBe true

            reopen.sensitive shouldBe true
            reopen.limits shouldBe emptyList()
            reopen.templates.keys shouldContainExactlyInAnyOrder listOf(RoleTemplate.MANAGER, RoleTemplate.FINANCE)
        }

    @Test
    fun `folio reopenings can never be updated nor deleted`(): Unit =
        with(billing) {
            val folio = openFolio()
            close(folio).expect(200)
            reopenAs(folio, admin).expect(200)
            for ((client, sqlState) in listOf(jdbc to INSUFFICIENT_PRIVILEGE, owner to RESTRICT_VIOLATION)) {
                refusedByDatabase(sqlState) {
                    client
                        .sql("UPDATE billing.folio_reopening SET reason = 'x' WHERE folio_id = :id")
                        .param("id", folio)
                        .update()
                }
                refusedByDatabase(sqlState) {
                    client.sql("DELETE FROM billing.folio_reopening WHERE folio_id = :id").param("id", folio).update()
                }
            }
        }

    // --- BillingApi ----------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // o saldo por dono antes e depois da transferência, o encerramento e as recusas
    fun `the billing api tells whether an owner is settled and closes a folio with the same rules`(): Unit =
        with(billing) {
            val reservation = UuidV7.generate()
            val owed = openFolio(ownerId = reservation)
            val credit = openFolio(ownerId = reservation)
            val room = post(owed, codeId("ROOM_CHARGE"), "100.00")
            val cashier = cashier()
            openShift(cashier.auth)
            pay(credit, cashier.auth, mapOf("method" to "PIX", "amount" to "100.00", "deposit" to true)).expect(201)
            val holder = FolioOwner(FolioOwnerType.RESERVATION, reservation)

            val before = billingApi.balanceOf(holder)
            before.folios.map { it.id.value } shouldContainExactly listOf(owed, credit)
            before.folios.map { it.balance.toString() } shouldContainExactly listOf("100.00", "-100.00")
            before.total.toString() shouldBe "0.00"
            withClue("uma conta devedora e outra credora que se anulam não estão quitadas (RN-06)") {
                before.settled shouldBe false
            }
            shouldThrow<ConflictException> { billingApi.closeFolio(FolioId(owed)) }.code.value shouldBe
                "FOLIO_BALANCE_NOT_ZERO"
            shouldThrow<NotFoundException> { billingApi.closeFolio(FolioId(UuidV7.generate())) }.code.value shouldBe
                "FOLIO_NOT_FOUND"

            transfer(owed, credit, listOf(room["id"].asString())).expect(201)
            billingApi.balanceOf(holder).settled shouldBe true
            val closed = billingApi.closeFolio(FolioId(owed))
            closed.status shouldBe FolioStatus.CLOSED
            closed.closedOn shouldBe hotelApi.currentBusinessDate()
            folio(owed)["closedBy"].isNull shouldBe true
            jdbc
                .sql(
                    "SELECT count(*) FROM audit.audit_entry WHERE action = 'billing.folio.close' " +
                        "AND entity_id = :id AND user_id IS NULL",
                ).param("id", owed.toString())
                .query(Long::class.java)
                .single() shouldBe 1L
            shouldThrow<ConflictException> { billingApi.closeFolio(FolioId(owed)) }.code.value shouldBe "FOLIO_CLOSED"
            shouldThrow<ConflictException> {
                billingApi.post(
                    FolioId(owed),
                    NewPosting(TransactionCodeId(codeId("ROOM_CHARGE")), unitPrice(), source = PostingSource.POS),
                )
            }.code.value shouldBe "FOLIO_CLOSED"

            val after = billingApi.balanceOf(holder)
            after.settled shouldBe true
            after.folios.map { it.status } shouldContainExactly listOf(FolioStatus.CLOSED, FolioStatus.OPEN)
            after.openFolios.map { it.id.value } shouldContainExactly listOf(credit)
            billingApi.foliosOf(holder).map { it.id.value } shouldContainExactly listOf(owed, credit)
            billingApi.balanceOf(FolioOwner(FolioOwnerType.EVENT, UuidV7.generate())).let {
                it.folios shouldBe emptyList()
                it.settled shouldBe true
            }
        }

    @Test
    fun `a posting user without folios manage cannot close nor reopen`(): Unit =
        with(billing) {
            val folio = openFolio()
            val clerk = iam.createUserWith(TestGrant(folioRead, ALL), TestGrant(postingsCreate, ALL))
            close(folio, iam.asTestUser(clerk)).problem(403, "PERMISSION_DENIED")
        }

    // --- Apoio -----------------------------------------------------------------------------------------

    /** Quem opera numa sessão de dispositivo fixa (o token de aprovação fica vinculado a ela). */
    private inner class Operator(
        val user: TestUser,
        val sessionId: UUID = UuidV7.generate(),
    ) {
        val auth: RequestPostProcessor get() = asUser(user.id.value, sessionId)
    }

    private data class Approver(
        val user: TestUser,
        val pin: String,
    )

    private fun cashier(): Operator =
        Operator(
            iam.createUserWith(
                TestGrant(receive, ALL),
                TestGrant(folioRead, ALL),
                TestGrant(operate, ALL),
            ),
        )

    private fun close(
        folio: UUID,
        auth: RequestPostProcessor = billing.admin,
    ): MockHttpServletResponse = billing.call(POST, "/api/v1/folios/$folio/close", auth)

    private fun reopenAs(
        folio: UUID,
        auth: RequestPostProcessor,
        approvalToken: String? = null,
        reason: String = REASON,
    ): MockHttpServletResponse =
        billing.call(
            POST,
            "/api/v1/folios/$folio/reopen",
            auth,
            mapOf("reason" to reason),
            approvalToken = approvalToken,
        )

    private fun register(
        folio: UUID,
        auth: RequestPostProcessor,
        amount: String,
        expiresAt: Instant,
    ): MockHttpServletResponse =
        billing.call(
            POST,
            "/api/v1/folios/$folio/card-pre-authorizations",
            auth,
            mapOf("amount" to amount, "expiresAt" to expiresAt.toString(), "authorizationCode" to "AUT1234"),
        )

    private fun release(
        folio: UUID,
        id: String,
        auth: RequestPostProcessor,
    ): MockHttpServletResponse = billing.call(POST, "/api/v1/folios/$folio/card-pre-authorizations/$id/release", auth)

    private fun approverWith(vararg grants: TestGrant): Approver {
        val user = iam.createUserWith(*grants)
        val pin = (PIN_MIN..PIN_MAX).random().toString()
        iam.setPin(user, pin)
        return Approver(user, pin)
    }

    private fun approve(
        requester: Operator,
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
                "approverLogin" to approver.user.login,
                "approverPin" to approver.pin,
                "reason" to "Conferido com o hóspede",
            ),
        )

    private fun inDays(days: Long): Instant =
        clock.instant().plus(Duration.ofDays(days)).truncatedTo(ChronoUnit.SECONDS)

    /** Avança o relógio da aplicação, roda [block] e o devolve (o contexto é compartilhado). */
    private fun moveClock(
        duration: Duration,
        block: () -> Unit,
    ) {
        clock.advance(duration)
        try {
            block()
        } finally {
            clock.advance(duration.negated())
        }
    }

    private fun postingCount(folio: UUID): Long =
        jdbc
            .sql("SELECT count(*) FROM billing.posting WHERE folio_id = :id")
            .param("id", folio)
            .query(Long::class.java)
            .single()

    private fun refusedByDatabase(
        sqlState: String = RESTRICT_VIOLATION,
        block: () -> Unit,
    ) {
        val error = shouldThrow<DataAccessException> { block() }
        (error.mostSpecificCause as SQLException).sqlState shouldBe sqlState
    }

    private fun unitPrice() = Money.of("10.00")

    private companion object {
        const val REASON = "Diária lançada a menor no check-out"
        const val PIN_MIN = 1000
        const val PIN_MAX = 9999
        const val INSUFFICIENT_PRIVILEGE = "42501"
        const val RESTRICT_VIOLATION = "23001"
    }
}
