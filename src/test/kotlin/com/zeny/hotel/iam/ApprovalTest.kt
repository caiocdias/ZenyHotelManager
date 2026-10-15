package com.zeny.hotel.iam

import approvalexample.ApprovalExampleConfiguration
import approvalexample.ApprovalExamplePermissions
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Aprovação de superior (step-up, M1 E05, RF-ACS-07 e 08, ARCHITECTURE §6.3): a ação sensível sem
 * permissão (ou acima do limite) devolve `403 APPROVAL_REQUIRED` com `action` e `resourceRef`;
 * outro usuário com a permissão aprova com login e PIN em `POST /auth/approvals`; o token é de uso
 * único, vale 2 min e fica vinculado a ação, recurso, solicitante (e à sessão dele); a requisição
 * repetida com `X-Approval-Token` é concluída e auditada com solicitante, aprovador e motivo. O PIN
 * errado tem bloqueio progressivo próprio, separado do login.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, ApprovalExampleConfiguration::class, MutableClockConfiguration::class)
@Suppress("LargeClass") // cenários de ponta a ponta do fluxo e de cada recusa
class ApprovalTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val clock: MutableClock,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val modules: List<ModulePermissions>,
) {
    private val usersRead = definition(IamPermissions.USERS_READ)
    private val usersManage = definition(IamPermissions.USERS_MANAGE)
    private val rolesManage = definition(IamPermissions.ROLES_MANAGE)
    private val discounts = definition(ApprovalExamplePermissions.DISCOUNTS_APPLY)
    private val notes = definition(ApprovalExamplePermissions.NOTES_WRITE)

    /** O banco é compartilhado: outro contexto pode ter marcado as permissões de exemplo como obsoletas. */
    @BeforeEach
    fun synchronizeCatalog() {
        synchronizer.synchronize()
    }

    // --- Fluxo completo --------------------------------------------------------------------------

    @Test
    fun `a sensitive action without permission is approved by another user and audited with both and the reason`() {
        val requester = Requester(iam.createUserWith(TestGrant(usersRead, ALL)))
        val approver = approverWith(TestGrant(usersManage, ALL))
        val target = iam.createUser()

        val required = deactivate(target, requester).refused(403, "APPROVAL_REQUIRED")
        required["action"].asString() shouldBe "iam.user.deactivate"
        required["permission"].asString() shouldBe "iam.users.manage"
        required["resourceRef"].asString() shouldBe "iam.user:${target.id}"
        required.has("limit") shouldBe false
        active(target) shouldBe true

        val approvedAt = clock.instant()
        val approval =
            approve(
                requester,
                required,
                approver,
                reason = "Hóspede pediu o bloqueio do acesso",
            ).succeeded(201)
        val token = approval["approvalToken"].asString()
        val approvalId = UUID.fromString(approval["approvalId"].asString())
        Instant.parse(approval["expiresAt"].asString()) shouldBe
            approvedAt.truncatedTo(ChronoUnit.MICROS).plus(Duration.ofMinutes(2))

        deactivate(target, requester, token).succeeded(200)["active"].asBoolean() shouldBe false
        active(target) shouldBe false

        expectAudited(approvalId, token, requester, approver, target)
    }

    @Test
    fun `the role management actions also ask for approval and accept it`() {
        val requester = Requester(iam.createUser())
        val approver = approverWith(TestGrant(rolesManage, ALL))
        val name = "Aprovada ${suffix()}"
        val body = mapOf("name" to name, "description" to "Role criada com aprovação", "grants" to emptyList<Any>())

        val required = iam.call(POST, "/api/v1/roles", requester.auth, body).refused(403, "APPROVAL_REQUIRED")
        required["action"].asString() shouldBe "iam.role.create"
        required["permission"].asString() shouldBe "iam.roles.manage"
        required["resourceRef"].asString() shouldBe "iam.role:new:${name.lowercase()}"

        val token = approve(requester, required, approver).succeeded(201)["approvalToken"].asString()
        val created = iam.call(POST, "/api/v1/roles", requester.auth, body, approvalToken = token).succeeded(201)
        created["name"].asString() shouldBe name

        // Outro nome é outra operação: um token aprovado para o primeiro nome não serve.
        val other = body + ("name" to "Outra ${suffix()}")
        val forOther = approve(requester, required, approver).succeeded(201)["approvalToken"].asString()
        iam
            .call(POST, "/api/v1/roles", requester.auth, other, approvalToken = forOther)
            .refused(403, "APPROVAL_TOKEN_MISMATCH")
    }

    @Test
    fun `a user with the permission does not need approval and a presented token is left unused`() {
        val manager = Requester(iam.createUserWith(TestGrant(usersManage, ALL)))
        val requester = Requester(iam.createUserWith(TestGrant(usersRead, ALL)))
        val approver = approverWith(TestGrant(usersManage, ALL))
        val target = iam.createUser()

        deactivate(target, manager).succeeded(200)
        // O token de outro solicitante nem é olhado por quem tem a permissão.
        val required = deactivate(target, requester).refused(403, "APPROVAL_REQUIRED")
        val token = approve(requester, required, approver).succeeded(201)["approvalToken"].asString()
        reactivate(target, manager, token).succeeded(200)
        deactivate(target, requester, token).succeeded(200)
    }

    // --- Recusas do token ------------------------------------------------------------------------

    @Test
    fun `a token is single use`() {
        val requester = Requester(iam.createUserWith(TestGrant(usersRead, ALL)))
        val approver = approverWith(TestGrant(usersManage, ALL))
        val target = iam.createUser()
        val required = deactivate(target, requester).refused(403, "APPROVAL_REQUIRED")
        val token = approve(requester, required, approver).succeeded(201)["approvalToken"].asString()

        deactivate(target, requester, token).succeeded(200)
        // Desativar de novo não muda nada, mas o token já foi usado.
        deactivate(target, requester, token).refused(403, "APPROVAL_TOKEN_USED")
    }

    @Test
    fun `a token is valid for two minutes only`() {
        val requester = Requester(iam.createUserWith(TestGrant(usersRead, ALL)))
        val approver = approverWith(TestGrant(usersManage, ALL))
        val target = iam.createUser()
        val required = deactivate(target, requester).refused(403, "APPROVAL_REQUIRED")

        val stillValid = approve(requester, required, approver).succeeded(201)["approvalToken"].asString()
        clock.advance(Duration.ofMinutes(2).minusSeconds(1))
        deactivate(target, requester, stillValid).succeeded(200)

        val expiring = approve(requester, deactivate(target, requester).refused(403, "APPROVAL_REQUIRED"), approver)
        val token = expiring.succeeded(201)["approvalToken"].asString()
        clock.advance(Duration.ofMinutes(2))
        deactivate(target, requester, token).refused(403, "APPROVAL_TOKEN_EXPIRED")
        // Recusado, o token não foi consumido nem a ação feita: ainda vencido depois.
        deactivate(target, requester, token).refused(403, "APPROVAL_TOKEN_EXPIRED")
    }

    @Test
    fun `a token is bound to the action, the resource, the requester and the requester session`() {
        val requester = Requester(iam.createUserWith(TestGrant(usersRead, ALL)))
        val otherRequester = Requester(iam.createUserWith(TestGrant(usersRead, ALL)))
        val approver = approverWith(TestGrant(usersManage, ALL))
        val target = iam.createUser()
        val otherTarget = iam.createUser()
        val required = deactivate(target, requester).refused(403, "APPROVAL_REQUIRED")
        val token = approve(requester, required, approver).succeeded(201)["approvalToken"].asString()

        // Outro recurso.
        deactivate(otherTarget, requester, token).refused(403, "APPROVAL_TOKEN_MISMATCH")
        active(otherTarget) shouldBe true
        // Outra ação sobre o mesmo recurso.
        iam
            .call(
                POST,
                "/api/v1/users/${target.id}/reset-pin",
                requester.auth,
                mapOf("newPin" to "9876"),
                approvalToken = token,
            ).refused(403, "APPROVAL_TOKEN_MISMATCH")
        // Outro solicitante.
        deactivate(target, otherRequester, token).refused(403, "APPROVAL_TOKEN_MISMATCH")
        // O mesmo usuário noutra sessão (outro dispositivo).
        val otherSession = Requester(requester.user)
        deactivate(target, otherSession, token).refused(403, "APPROVAL_TOKEN_MISMATCH")
        // Token desconhecido.
        deactivate(target, requester, "token-que-nao-existe").refused(403, "APPROVAL_TOKEN_INVALID")
        active(target) shouldBe true

        // Nenhuma recusa consumiu o token: ele ainda vale para a operação aprovada.
        deactivate(target, requester, token).succeeded(200)
    }

    @Test
    fun `the approver must still hold the permission when the token is used`() {
        val requester = Requester(iam.createUserWith(TestGrant(usersRead, ALL)))
        val approver = approverWith(TestGrant(usersManage, ALL))
        val target = iam.createUser()
        val required = deactivate(target, requester).refused(403, "APPROVAL_REQUIRED")
        val token = approve(requester, required, approver).succeeded(201)["approvalToken"].asString()

        iam.deactivate(approver.user)
        deactivate(target, requester, token).refused(403, "APPROVER_NOT_ALLOWED")
        active(target) shouldBe true
    }

    @Test
    fun `two simultaneous requests with the same token execute the action once`() {
        val requester =
            Requester(
                iam.createUserWith(
                    TestGrant(
                        discounts,
                        ALL,
                        mapOf(MAX_PERCENT to LimitValue.Max(BigDecimal.TEN)),
                    ),
                ),
            )
        val approver = approverWith(TestGrant(discounts, ALL, mapOf(MAX_PERCENT to LimitValue.Unlimited)))
        val order = UuidV7.generate()
        val required = discount(order, "30", requester).refused(403, "APPROVAL_REQUIRED")
        val token = approve(requester, required, approver).succeeded(201)["approvalToken"].asString()

        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results =
                (1..2)
                    .map {
                        executor.submit(
                            Callable {
                                start.await()
                                discount(order, "30", requester, token)
                            },
                        )
                    }.also { start.countDown() }
                    .map { it.get(30, TimeUnit.SECONDS) }
            results.map { it.status } shouldContainExactlyInAnyOrder listOf(200, 403)
            results.single { it.status == 403 }.refused(403, "APPROVAL_TOKEN_USED")
        } finally {
            executor.shutdownNow()
        }
        jdbc
            .sql(
                "SELECT count(*) FROM iam.approval_use u JOIN iam.approval a ON a.id = u.approval_id " +
                    "WHERE a.resource_ref = :ref",
            ).param("ref", "approvalexamples.order:$order")
            .query(Int::class.java)
            .single() shouldBe 1
    }

    // --- Recusas da aprovação --------------------------------------------------------------------

    @Test
    fun `the approver needs the permission, cannot be the requester and must give the right PIN`() {
        val requester = Requester(iam.createUserWith(TestGrant(usersRead, ALL)))
        val withoutPermission = approverWith(TestGrant(usersRead, ALL))
        val target = iam.createUser()
        val required = deactivate(target, requester).refused(403, "APPROVAL_REQUIRED")

        approve(requester, required, withoutPermission).refused(403, "APPROVER_NOT_ALLOWED")

        // O próprio solicitante, mesmo com PIN.
        iam.setPin(requester.user, "4321")
        approve(requester, required, Approver(requester.user, "4321")).refused(403, "APPROVER_IS_REQUESTER")

        // Login inexistente, aprovador sem PIN e PIN errado: a mesma recusa, sem dizer qual.
        val ghost =
            Approver(
                TestUser(
                    com.zeny.hotel.shared.UserId
                        .new(),
                    "ninguem.${suffix()}",
                    "x",
                ),
                "1234",
            )
        approve(requester, required, ghost).refused(403, "APPROVAL_CREDENTIALS_INVALID")
        val noPin = iam.createUserWith(TestGrant(usersManage, ALL))
        approve(requester, required, Approver(noPin, "1234")).refused(403, "APPROVAL_CREDENTIALS_INVALID")
        val manager = approverWith(TestGrant(usersManage, ALL))
        approve(requester, required, manager.copy(pin = "0000")).refused(403, "APPROVAL_CREDENTIALS_INVALID")

        // Aprovador desativado, mesmo com o PIN certo.
        val inactive = approverWith(TestGrant(usersManage, ALL))
        iam.deactivate(inactive.user)
        approve(requester, required, inactive).refused(403, "APPROVER_NOT_ALLOWED")

        // Permissão que não é sensível não aceita aprovação.
        val notSensitive = required.deepCopy() as tools.jackson.databind.node.ObjectNode
        notSensitive.put("permission", "iam.users.read")
        approve(requester, notSensitive, manager).refused(422, "APPROVAL_NOT_APPLICABLE")
        active(target) shouldBe true
    }

    @Test
    fun `wrong PINs lock the approvals of that approver for a growing time without locking the login`() {
        val requester = Requester(iam.createUserWith(TestGrant(usersRead, ALL)))
        val otherRequester = Requester(iam.createUserWith(TestGrant(usersRead, ALL)))
        val approver = approverWith(TestGrant(usersManage, ALL))
        val target = iam.createUser()
        val required = deactivate(target, requester).refused(403, "APPROVAL_REQUIRED")
        val wrong = approver.copy(pin = "0000")

        repeat(MAX_PIN_FAILURES) { approve(requester, required, wrong).refused(403, "APPROVAL_CREDENTIALS_INVALID") }
        // Bloqueado: nem o PIN certo passa, de nenhum solicitante.
        approve(requester, required, approver).refused(403, "APPROVER_LOCKED")
        val otherRequired = deactivate(target, otherRequester).refused(403, "APPROVAL_REQUIRED")
        approve(otherRequester, otherRequired, approver).refused(403, "APPROVER_LOCKED")
        // O login do aprovador continua funcionando.
        with(iam) { iam.loginOk(approver.user) }

        // Cada recusa ficou na trilha, com a contagem do aprovador.
        val rejections = entries("action = 'iam.approval.grant' AND entity_id = :id", approver.user.id.toString())
        rejections.filter { it.errorCode == "APPROVAL_CREDENTIALS_INVALID" } shouldHaveSize MAX_PIN_FAILURES
        rejections.filter { it.errorCode == "APPROVER_LOCKED" } shouldHaveSize 2
        rejections.forEach { it.outcome shouldBe "REJECTED" }
        rejections.maxOf { it.after!!["failedApprovalAttempts"].asInt() } shouldBe MAX_PIN_FAILURES
        rawTrail() shouldNotContain "\"0000\""

        // Vence o bloqueio de 1 min: o PIN certo passa e zera a contagem.
        clock.advance(ONE_MINUTE)
        approve(requester, required, approver).succeeded(201)

        // De novo: a partir do limite, cada nova falha dobra o bloqueio (1 min, 2 min…).
        repeat(MAX_PIN_FAILURES) { approve(requester, required, wrong).refused(403, "APPROVAL_CREDENTIALS_INVALID") }
        clock.advance(ONE_MINUTE)
        approve(requester, required, wrong).refused(403, "APPROVAL_CREDENTIALS_INVALID")
        clock.advance(ONE_MINUTE)
        approve(requester, required, approver).refused(403, "APPROVER_LOCKED")
        clock.advance(ONE_MINUTE)
        approve(requester, required, approver).succeeded(201)

        // Redefinir o PIN zera o bloqueio.
        repeat(MAX_PIN_FAILURES) { approve(requester, required, wrong).refused(403, "APPROVAL_CREDENTIALS_INVALID") }
        iam
            .call(POST, "/api/v1/users/${approver.user.id}/reset-pin", iam.asAdmin(), mapOf("newPin" to "5555"))
            .let { iam.run { it.expectStatus(204) } }
        approve(requester, required, approver.copy(pin = "5555")).succeeded(201)
    }

    // --- Ação com limite -------------------------------------------------------------------------

    @Test
    fun `an action above the limit of the user needs approval from someone whose limit covers the amount`() {
        val requester =
            Requester(
                iam.createUserWith(TestGrant(discounts, ALL, mapOf(MAX_PERCENT to LimitValue.Max(BigDecimal.TEN)))),
            )
        val order = UuidV7.generate()

        // Dentro do limite (inclusive no teto): sem aprovação.
        discount(order, "5", requester).succeeded(200)["byApproval"].asBoolean() shouldBe false
        discount(order, "10", requester).succeeded(200)["byApproval"].asBoolean() shouldBe false

        // Acima do limite: aprovação, com o limite no problema.
        val required = discount(order, "15", requester).refused(403, "APPROVAL_REQUIRED")
        required["action"].asString() shouldBe "approvalexamples.order.discount"
        required["permission"].asString() shouldBe "approvalexamples.discounts.apply"
        required["resourceRef"].asString() shouldBe "approvalexamples.order:$order"
        required["limit"]["key"].asString() shouldBe MAX_PERCENT
        required["limit"]["amount"].asString() shouldBe "15"

        // Aprovador com limite insuficiente.
        val small = approverWith(TestGrant(discounts, ALL, mapOf(MAX_PERCENT to LimitValue.Max(BigDecimal("12")))))
        approve(requester, required, small).refused(403, "APPROVER_NOT_ALLOWED")
        // Aprovador com escopo OWN não aprova o pedido, que não é dele (a recusa vem no uso do token).
        val own = approverWith(TestGrant(discounts, OWN, mapOf(MAX_PERCENT to LimitValue.Unlimited)))
        val ownToken = approve(requester, required, own).succeeded(201)["approvalToken"].asString()
        discount(order, "15", requester, ownToken).refused(403, "APPROVER_NOT_ALLOWED")

        val big = approverWith(TestGrant(discounts, ALL, mapOf(MAX_PERCENT to LimitValue.Max(BigDecimal("20")))))
        val token = approve(requester, required, big).succeeded(201)["approvalToken"].asString()
        // Outro valor é outra operação.
        discount(order, "16", requester, token).refused(403, "APPROVAL_TOKEN_MISMATCH")
        // Dentro do limite do próprio usuário, o token nem é olhado (e continua valendo).
        discount(order, "9", requester, token).succeeded(200)["byApproval"].asBoolean() shouldBe false
        val approved = discount(order, "15.0", requester, token).succeeded(200)
        approved["byApproval"].asBoolean() shouldBe true
        approved["approverId"].asString() shouldBe big.user.id.toString()

        // Sem a permissão: aprovação também (e o limite vai junto).
        val stranger = Requester(iam.createUser())
        discount(order, "1", stranger).refused(403, "APPROVAL_REQUIRED")["limit"]["amount"].asString() shouldBe "1"
    }

    @Test
    fun `an action that is not sensitive is refused without approval`() {
        val writer =
            Requester(iam.createUserWith(TestGrant(notes, ALL, mapOf(MAX_LENGTH to LimitValue.Max(BigDecimal.TEN)))))
        val approver = approverWith(TestGrant(notes, ALL, mapOf(MAX_LENGTH to LimitValue.Unlimited)))
        val note = UuidV7.generate()

        note(note, "10", writer).succeeded(200)["byApproval"].asBoolean() shouldBe false
        note(note, "11", writer).refused(422, "LIMIT_EXCEEDED")
        note(note, "1", Requester(iam.createUser())).refused(403, "PERMISSION_DENIED")

        val body =
            mapOf(
                "action" to "approvalexamples.note.write",
                "permission" to ApprovalExamplePermissions.NOTES_WRITE.value,
                "resourceRef" to "approvalexamples.note:$note",
                "approverLogin" to approver.user.login,
                "approverPin" to approver.pin,
                "reason" to "Teste",
            )
        iam.call(POST, "/api/v1/auth/approvals", writer.auth, body).refused(422, "APPROVAL_NOT_APPLICABLE")
    }

    /**
     * A trilha da ação aprovada: as entradas são do solicitante e referenciam a aprovação, a da
     * própria aprovação traz solicitante, aprovador, motivo e uso, a emissão também está na trilha,
     * e nem o token nem o PIN aparecem.
     */
    private fun expectAudited(
        approvalId: UUID,
        token: String,
        requester: Requester,
        approver: Approver,
        target: TestUser,
    ) {
        // O token não fica em claro no banco nem na trilha.
        rawApprovalRow(approvalId) shouldNotContain token
        rawTrail() shouldNotContain token
        rawTrail() shouldNotContain approver.pin.let { "\"$it\"" }

        // A ação concluída é do solicitante e referencia a aprovação.
        val actionEntries = entries("action = 'iam.user.deactivate' AND approval_id = :id", approvalId)
        actionEntries.map { it.entityType } shouldContainExactlyInAnyOrder listOf("iam.user", "iam.approval")
        actionEntries.forEach {
            it.userId shouldBe requester.user.id.value
            it.deviceSessionId shouldBe requester.sessionId
            it.outcome shouldBe "SUCCEEDED"
        }
        actionEntries.single { it.entityType == "iam.user" }.after!!["active"].asBoolean() shouldBe false
        // A entrada da aprovação traz solicitante, aprovador e motivo, e o uso.
        val used = actionEntries.single { it.entityType == "iam.approval" }
        used.entityId shouldBe approvalId.toString()
        used.after!!.let {
            it["requesterId"].asString() shouldBe requester.user.id.toString()
            it["approverId"].asString() shouldBe approver.user.id.toString()
            it["reason"].asString() shouldBe "Hóspede pediu o bloqueio do acesso"
            it["action"].asString() shouldBe "iam.user.deactivate"
            it["resourceRef"].asString() shouldBe "iam.user:${target.id}"
            it["usedAt"].isNull shouldBe false
        }
        used.before!!["usedAt"].isNull shouldBe true

        // A emissão também está na trilha, atribuída ao solicitante.
        val granted = entries("action = 'iam.approval.grant' AND entity_id = :id", approvalId.toString()).single()
        granted.userId shouldBe requester.user.id.value
        granted.approvalId shouldBe approvalId
        granted.after!!["approverId"].asString() shouldBe approver.user.id.toString()
        granted.after["reason"].asString() shouldBe "Hóspede pediu o bloqueio do acesso"
        granted.after["expiresAt"].isNull shouldBe false

        // A aprovação persistida guarda solicitante, aprovador e motivo.
        approvalRow(approvalId).let {
            it["requester_id"] shouldBe requester.user.id.value
            it["approver_id"] shouldBe approver.user.id.value
            it["reason"] shouldBe "Hóspede pediu o bloqueio do acesso"
        }

        // A consulta da trilha mostra a referência à aprovação.
        val listed =
            iam
                .call(GET, "/api/v1/audit-entries?entityType=iam.user&entityId=${target.id}", iam.asAdmin())
                .succeeded()
        listed["items"]
            .values()
            .single { it["action"].asString() == "iam.user.deactivate" }["approvalId"]
            .asString() shouldBe approvalId.toString()
    }

    // --- Apoio -----------------------------------------------------------------------------------

    /** O solicitante numa sessão de dispositivo fixa (o token fica vinculado a ela). */
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
        val pin = (1000..9999).random().toString()
        iam.setPin(user, pin)
        return Approver(user, pin)
    }

    private fun approve(
        requester: Requester,
        required: JsonNode,
        approver: Approver,
        reason: String = "Motivo do teste",
    ): MockHttpServletResponse {
        val body =
            buildMap {
                put("action", required["action"].asString())
                put("permission", required["permission"].asString())
                put("resourceRef", required["resourceRef"].asString())
                if (required.has("limit")) {
                    put(
                        "limit",
                        mapOf(
                            "key" to required["limit"]["key"].asString(),
                            "amount" to required["limit"]["amount"].asString(),
                        ),
                    )
                }
                put("approverLogin", approver.user.login)
                put("approverPin", approver.pin)
                put("reason", reason)
            }
        return iam.call(POST, "/api/v1/auth/approvals", requester.auth, body)
    }

    private fun deactivate(
        target: TestUser,
        requester: Requester,
        token: String? = null,
    ) = iam.call(POST, "/api/v1/users/${target.id}/deactivate", requester.auth, approvalToken = token)

    private fun reactivate(
        target: TestUser,
        requester: Requester,
        token: String? = null,
    ) = iam.call(POST, "/api/v1/users/${target.id}/reactivate", requester.auth, approvalToken = token)

    private fun discount(
        order: UUID,
        percent: String,
        requester: Requester,
        token: String? = null,
    ) = iam.call(
        POST,
        "/api/v1/approval-examples/orders/$order/discount",
        requester.auth,
        mapOf("amount" to BigDecimal(percent)),
        approvalToken = token,
    )

    private fun note(
        note: UUID,
        length: String,
        requester: Requester,
    ) = iam.call(POST, "/api/v1/approval-examples/notes/$note", requester.auth, mapOf("amount" to BigDecimal(length)))

    private fun MockHttpServletResponse.succeeded(status: Int = 200): JsonNode =
        iam.run { this@succeeded.expectStatus(status) }.shouldNotBeNull()

    private fun MockHttpServletResponse.refused(
        status: Int,
        code: String,
    ): JsonNode = iam.run { this@refused.problem(status, code) }

    private fun active(user: TestUser): Boolean =
        jdbc
            .sql("SELECT active FROM iam.user_account WHERE id = :id")
            .param("id", user.id.value)
            .query(Boolean::class.java)
            .single()

    private fun approvalRow(id: UUID): Map<String, Any?> =
        jdbc
            .sql("SELECT requester_id, approver_id, reason FROM iam.approval WHERE id = :id")
            .param("id", id)
            .query()
            .singleRow()

    private fun rawApprovalRow(id: UUID): String =
        jdbc
            .sql("SELECT CAST(a AS text) FROM iam.approval a WHERE a.id = :id")
            .param("id", id)
            .query(String::class.java)
            .single()

    private fun rawTrail(): String =
        jdbc
            .sql(
                "SELECT coalesce(string_agg(coalesce(CAST(before_state AS text), '') || " +
                    "coalesce(CAST(after_state AS text), ''), ''), '') FROM audit.audit_entry",
            ).query(String::class.java)
            .single()

    private fun entries(
        where: String,
        id: Any,
    ): List<Entry> =
        jdbc
            .sql(
                """
                SELECT action, outcome, error_code, user_id, device_session_id, entity_type, entity_id, approval_id,
                    CAST(before_state AS text) AS before_state, CAST(after_state AS text) AS after_state
                FROM audit.audit_entry WHERE $where
                ORDER BY occurred_at, id
                """.trimIndent(),
            ).param("id", id)
            .query { rs, _ ->
                Entry(
                    action = rs.getString("action"),
                    outcome = rs.getString("outcome"),
                    errorCode = rs.getString("error_code"),
                    userId = rs.getObject("user_id", UUID::class.java),
                    deviceSessionId = rs.getObject("device_session_id", UUID::class.java),
                    entityType = rs.getString("entity_type"),
                    entityId = rs.getString("entity_id"),
                    approvalId = rs.getObject("approval_id", UUID::class.java),
                    before = rs.getString("before_state")?.let(jsonMapper::readTree),
                    after = rs.getString("after_state")?.let(jsonMapper::readTree),
                )
            }.list()
            .also { withClue(where) { it.isEmpty() shouldBe false } }

    private data class Entry(
        val action: String,
        val outcome: String,
        val errorCode: String?,
        val userId: UUID?,
        val deviceSessionId: UUID?,
        val entityType: String?,
        val entityId: String?,
        val approvalId: UUID?,
        val before: JsonNode?,
        val after: JsonNode?,
    )

    private fun definition(code: PermissionCode): PermissionDefinition =
        (modules.flatMap { it.permissions }).single { it.code == code }

    private fun suffix() = UuidV7.generate().toString().takeLast(SUFFIX_LENGTH)

    private companion object {
        const val SUFFIX_LENGTH = 10
        const val MAX_PIN_FAILURES = 3

        /** Um minuto e um pouco: o banco guarda o fim do bloqueio em microssegundos, arredondado. */
        val ONE_MINUTE: Duration = Duration.ofMinutes(1).plusMillis(1)
        const val MAX_PERCENT = ApprovalExamplePermissions.MAX_PERCENT
        const val MAX_LENGTH = ApprovalExamplePermissions.MAX_LENGTH
    }
}
