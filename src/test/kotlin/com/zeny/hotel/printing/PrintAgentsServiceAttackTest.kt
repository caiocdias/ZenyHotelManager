package com.zeny.hotel.printing

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.printing.application.CreatedAgent
import com.zeny.hotel.printing.application.NewPrinter
import com.zeny.hotel.printing.application.PrintAgentIdentity
import com.zeny.hotel.printing.application.PrintAgentStore
import com.zeny.hotel.printing.application.PrintAgentsService
import com.zeny.hotel.printing.application.PrinterChange
import com.zeny.hotel.printing.domain.PrintAgent
import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.ErrorKind
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ataque a `PrintAgentsService` e a `PrintAgentStore` (M5 E10, Task 5): permissões por operação com roles reais e
 * customizadas, token só como hash e nunca na trilha, rotação e desativação concorrentes, impressora de agente
 * desativado, versões e entradas inválidas. Banco compartilhado: cada teste usa agentes e impressoras próprios.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LargeClass", "TooManyFunctions")
class PrintAgentsServiceAttackTest(
    @Autowired private val service: PrintAgentsService,
    @Autowired private val store: PrintAgentStore,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
) {
    private fun suffix() = UuidV7.generate().toString().takeLast(12)

    private fun <T> actingAs(
        user: UserId,
        block: () -> T,
    ): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(user.toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun <T> asAdmin(block: () -> T): T = actingAs(UserId(iam.adminId()), block)

    private fun grant(code: PermissionCode) = TestGrant(PrintingPermissions.permissions.single { it.code == code }, ALL)

    private fun userWith(codes: List<PermissionCode>): UserId =
        iam.createUserWith(*codes.map(::grant).toTypedArray()).id

    private fun userWithTemplate(template: RoleTemplate): UserId {
        val roleId =
            jdbc
                .sql("SELECT id FROM iam.role WHERE template_key = :k")
                .param("k", template.name)
                .query(UUID::class.java)
                .single()
        val user = iam.createUser()
        iam.assignRole(user, RoleId(roleId))
        return user.id
    }

    private fun errorOf(block: () -> Unit): DomainException = shouldThrow<DomainException>(block)

    private fun hashOf(agentId: UUID): String? =
        jdbc
            .sql("SELECT token_hash FROM printing.print_agent WHERE id = :id")
            .param("id", agentId)
            .query(String::class.java)
            .optional()
            .orElse(null)

    private fun generationOf(agentId: UUID): Int =
        jdbc
            .sql("SELECT token_generation FROM printing.print_agent WHERE id = :id")
            .param("id", agentId)
            .query(Int::class.java)
            .single()

    private fun agentVersionOf(agentId: UUID): Long =
        jdbc
            .sql("SELECT version FROM printing.print_agent WHERE id = :id")
            .param("id", agentId)
            .query(Long::class.java)
            .single()

    private fun agentCountNamed(name: String): Int =
        jdbc
            .sql("SELECT count(*) FROM printing.print_agent WHERE name = :n")
            .param("n", name)
            .query(Int::class.java)
            .single()

    private fun printerRow(id: UUID): Map<String, Any?> =
        jdbc
            .sql("SELECT name, address, agent_id, active, version FROM printing.printer WHERE id = :id")
            .param("id", id)
            .query()
            .singleRow()

    private fun auditOf(entityId: UUID): List<String> =
        jdbc
            .sql("SELECT CAST(to_jsonb(e) AS text) FROM audit.audit_entry e WHERE e.entity_id = :id")
            .param("id", entityId.toString())
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun auditCount(
        action: String,
        entityId: UUID,
    ): Int =
        jdbc
            .sql("SELECT count(*) FROM audit.audit_entry WHERE action = :a AND entity_id = :id")
            .param("a", action)
            .param("id", entityId.toString())
            .query(Int::class.java)
            .single()

    private fun mentionsAnywhere(secret: String): Int =
        jdbc
            .sql("SELECT count(*) FROM audit.audit_entry e WHERE CAST(to_jsonb(e) AS text) LIKE :p")
            .param("p", "%$secret%")
            .query(Int::class.java)
            .single()

    private fun newAgent(): CreatedAgent = asAdmin { service.createAgent("Agente ${suffix()}") }

    private fun newPrinter(agentId: UUID) =
        asAdmin { service.createPrinter(NewPrinter("Imp ${suffix()}", PrinterKind.A4_PDF, "a4", agentId)) }

    // --- permissoes ----------------------------------------------------------------------------

    private fun manageOps(
        agentId: UUID,
        printerId: UUID,
        version: Long,
    ): List<Pair<String, () -> Unit>> =
        listOf(
            "createAgent" to { service.createAgent("X ${suffix()}") },
            "agents" to { service.agents() },
            "agent" to { service.agent(agentId) },
            "rotateToken" to { service.rotateToken(agentId) },
            "deactivateAgent" to { service.deactivateAgent(agentId) },
            "createPrinter" to { service.createPrinter(NewPrinter("P", PrinterKind.A4_PDF, "a4", agentId)) },
            "updatePrinter" to { service.updatePrinter(printerId, version, PrinterChange("P", "a4", agentId)) },
            "deactivatePrinter" to { service.deactivatePrinter(printerId) },
        )

    private fun assertDeniedFor(
        user: UserId,
        ops: List<Pair<String, () -> Unit>>,
    ) = actingAs(user) {
        ops.forEach { (name, op) ->
            val error = errorOf(op)
            check(error.kind == ErrorKind.PERMISSION_DENIED) { "$name devolveu ${error.kind}" }
        }
    }

    @Test
    fun `Manager and Receptionist read printers but every write and agent read is 403`() {
        val created = newAgent()
        val agent = created.view
        val printer = newPrinter(agent.id)
        val denied = manageOps(agent.id, printer.id, printer.version)
        for (template in listOf(RoleTemplate.MANAGER, RoleTemplate.RECEPTIONIST)) {
            val user = userWithTemplate(template)
            actingAs(user) {
                service.printers().map { it.id } shouldContain printer.id
                service.printer(printer.id).id shouldBe printer.id
            }
            assertDeniedFor(user, denied)
        }
        // nada mudou por causa das tentativas negadas
        hashOf(agent.id) shouldBe PrintAgent.hashOf(created.token)
        asAdmin { service.agent(agent.id).active } shouldBe true
        printerRow(printer.id)["version"] shouldBe printer.version
    }

    @Test
    fun `a role with only agents manage writes and reads agents but does not read printers`() {
        val user = userWith(listOf(PrintingPermissions.AGENTS_MANAGE))
        actingAs(user) {
            val agent = service.createAgent("Agente ${suffix()}").view
            service.agents().map { it.id } shouldContain agent.id
            service.agent(agent.id).id shouldBe agent.id
            val printer = service.createPrinter(NewPrinter("P ${suffix()}", PrinterKind.A4_PDF, "a4", agent.id))
            service.updatePrinter(printer.id, printer.version, PrinterChange("Q", "a5", agent.id)).name shouldBe "Q"
            service.deactivatePrinter(printer.id).active shouldBe false
            service.rotateToken(agent.id).token.startsWith("zpa_") shouldBe true
            service.deactivateAgent(agent.id).active shouldBe false
            errorOf { service.printers() }.kind shouldBe ErrorKind.PERMISSION_DENIED
            errorOf { service.printer(printer.id) }.kind shouldBe ErrorKind.PERMISSION_DENIED
        }
    }

    @Test
    fun `a role with only jobs read reads printers but cannot touch agents or write printers`() {
        val agent = newAgent().view
        val printer = newPrinter(agent.id)
        val user = userWith(listOf(PrintingPermissions.JOBS_READ))
        actingAs(user) {
            service.printers().map { it.id } shouldContain printer.id
            service.printer(printer.id).agentId shouldBe agent.id
        }
        assertDeniedFor(user, manageOps(agent.id, printer.id, printer.version))
    }

    @Test
    fun `jobs manage alone grants neither agents manage nor jobs read`() {
        val agent = newAgent().view
        val printer = newPrinter(agent.id)
        val user = userWith(listOf(PrintingPermissions.JOBS_MANAGE))
        assertDeniedFor(
            user,
            manageOps(agent.id, printer.id, printer.version) +
                listOf<Pair<String, () -> Unit>>("printers" to { service.printers() }),
        )
    }

    @Test
    fun `a user with no role is refused everywhere and a denied createAgent leaves no row`() {
        val name = "Negado ${suffix()}"
        val agent = newAgent().view
        val printer = newPrinter(agent.id)
        val user = iam.createUser().id
        actingAs(user) { errorOf { service.createAgent(name) }.kind shouldBe ErrorKind.PERMISSION_DENIED }
        assertDeniedFor(user, manageOps(agent.id, printer.id, printer.version))
        agentCountNamed(name) shouldBe 0
    }

    @Test
    fun `a denied rotation or deactivation changes nothing in the agent`() {
        val created = newAgent()
        val id = created.view.id
        val before = agentVersionOf(id)
        val user = userWith(listOf(PrintingPermissions.JOBS_READ))
        actingAs(user) {
            errorOf { service.rotateToken(id) }.kind shouldBe ErrorKind.PERMISSION_DENIED
            errorOf { service.deactivateAgent(id) }.kind shouldBe ErrorKind.PERMISSION_DENIED
        }
        hashOf(id) shouldBe PrintAgent.hashOf(created.token)
        agentVersionOf(id) shouldBe before
        generationOf(id) shouldBe 1
    }

    // --- token e trilha ------------------------------------------------------------------------

    @Test
    fun `toString of the created agent and of every view never shows the token or the hash`() {
        val created = newAgent()
        val rotated = asAdmin { service.rotateToken(created.view.id) }
        val texts =
            listOf(
                created.toString(),
                rotated.toString(),
                created.view.toString(),
                rotated.view.toString(),
                asAdmin { service.agents().joinToString { it.toString() } },
            )
        for (text in texts) {
            for (secret in listOf(created.token, rotated.token, hashOf(created.view.id).orEmpty())) {
                if (secret.isNotEmpty()) text shouldNotContain secret
            }
        }
    }

    @Test
    fun `no audit entry of any outcome carries a token, a hash or a token hash field`() {
        val created = newAgent()
        val id = created.view.id
        val rotated = asAdmin { service.rotateToken(id) }
        val deniedUser = userWith(listOf(PrintingPermissions.JOBS_READ))
        actingAs(deniedUser) { errorOf { service.rotateToken(id) } }
        val printer = newPrinter(id)
        asAdmin {
            service.deactivateAgent(id)
            errorOf { service.rotateToken(id) } // REJECTED: agente inativo
            service.updatePrinter(printer.id, printer.version, PrinterChange("N ${suffix()}", "a4", id))
        }
        val secrets =
            listOf(
                created.token,
                rotated.token,
                PrintAgent.hashOf(created.token),
                PrintAgent.hashOf(rotated.token),
            )
        for (secret in secrets) mentionsAnywhere(secret) shouldBe 0
        val entries = auditOf(id)
        (entries.size >= 3) shouldBe true
        for (entry in entries) {
            entry shouldNotContain "tokenHash"
            entry shouldNotContain "token_hash"
            entry shouldNotContain "zpa_"
        }
    }

    @Test
    fun `the snapshots before and after a rotation show the version going up and no secret`() {
        val created = newAgent()
        val id = created.view.id
        val rotated = asAdmin { service.rotateToken(id) }
        val row =
            jdbc
                .sql(
                    "SELECT CAST(before_state AS text) AS b, CAST(after_state AS text) AS a, " +
                        "before_state->>'tokenGeneration' AS bg, " +
                        "after_state->>'tokenGeneration' AS ag, " +
                        "before_state->>'version' AS bv, after_state->>'version' AS av " +
                        "FROM audit.audit_entry WHERE action = 'printing.agent.rotate-token' AND entity_id = :id",
                ).param("id", id.toString())
                .query()
                .singleRow()

        // O mascaramento da trilha cobre campos com "token" no nome: a geracao sai como "***" ou como o numero.
        listOf(row["bg"], row["ag"]).forEach { it.toString() shouldBeIn listOf("***", "1", "2") }
        row["bv"] shouldBe "0"
        row["av"] shouldBe "1"
        generationOf(id) shouldBe 2
        listOf(row["b"], row["a"]).forEach { snapshot ->
            snapshot.toString() shouldNotContain rotated.token
            snapshot.toString() shouldNotContain PrintAgent.hashOf(rotated.token)
            snapshot.toString() shouldNotContain PrintAgent.hashOf(created.token)
        }
    }

    @Test
    fun `createAgent audit has no before state and an after state with the name and no secret`() {
        val created = newAgent()
        val row =
            jdbc
                .sql(
                    "SELECT before_state IS NULL AS nobefore, after_state->>'name' AS n " +
                        "FROM audit.audit_entry WHERE action = 'printing.agent.create' AND entity_id = :id",
                ).param("id", created.view.id.toString())
                .query()
                .singleRow()

        row["nobefore"] shouldBe true
        row["n"] shouldBe created.view.name
    }

    // --- rotacao e desativacao -----------------------------------------------------------------

    @Test
    fun `concurrent rotation of the same agent never leaves two valid tokens`() {
        repeat(5) { _ ->
            val created = newAgent()
            val id = created.view.id
            val results = runConcurrently(4) { asAdmin { service.rotateToken(id) } }

            val tokens = results.mapNotNull { it.getOrNull()?.token }
            val failures = results.mapNotNull { it.exceptionOrNull() }
            failures.forEach { isLostRace(it, ErrorKind.CONFLICT) shouldBe true }
            tokens.isNotEmpty() shouldBe true
            val valid = (tokens + created.token).filter { store.byTokenHash(PrintAgent.hashOf(it)) != null }
            valid.size shouldBe 1
            hashOf(id) shouldBe PrintAgent.hashOf(valid.single())
            generationOf(id) shouldBe 1 + tokens.size
            tokens.toSet().size shouldBe tokens.size
        }
    }

    @Test
    fun `concurrent rotate and deactivate never leave an active agent after a successful deactivation`() {
        repeat(5) { _ ->
            val id = newAgent().view.id
            val outcomes =
                runConcurrentlyDifferent(
                    { asAdmin { service.rotateToken(id) } },
                    { asAdmin { service.deactivateAgent(id) } },
                )
            val deactivation = outcomes[1]
            if (deactivation.isSuccess) {
                hashOf(id).shouldBeNull()
                asAdmin { service.agent(id).active } shouldBe false
            }
            // o rotate so pode ter sucesso se veio antes da desativacao, e entao o hash foi apagado
            (hashOf(id) == null || deactivation.isFailure) shouldBe true
        }
    }

    @Test
    fun `deactivating twice is idempotent, keeps the hash null and the version and writes one audit entry`() {
        val created = newAgent()
        val id = created.view.id

        val first = asAdmin { service.deactivateAgent(id) }
        val versionAfterFirst = agentVersionOf(id)
        val second = asAdmin { service.deactivateAgent(id) }

        first.active shouldBe false
        second.active shouldBe false
        second.version shouldBe first.version
        agentVersionOf(id) shouldBe versionAfterFirst
        hashOf(id).shouldBeNull()
        store.byTokenHash(PrintAgent.hashOf(created.token)).shouldBeNull()
        auditCount("printing.agent.deactivate", id) shouldBe 1
    }

    @Test
    fun `a deactivated agent cannot be reactivated by rotation and keeps a null hash`() {
        val id = newAgent().view.id
        asAdmin { service.deactivateAgent(id) }
        val error = asAdmin { errorOf { service.rotateToken(id) } }

        error.code shouldBe PrintingErrorCodes.PRINT_AGENT_INACTIVE
        hashOf(id).shouldBeNull()
        asAdmin { service.agent(id).active } shouldBe false
    }

    @Test
    fun `rotation changes the hash every time even when called in a row`() {
        val created = newAgent()
        val id = created.view.id
        val tokens = listOf(created.token) + List(5) { asAdmin { service.rotateToken(id).token } }

        tokens.toSet().size shouldBe tokens.size
        hashOf(id) shouldBe PrintAgent.hashOf(tokens.last())
        tokens.dropLast(1).forEach { store.byTokenHash(PrintAgent.hashOf(it)).shouldBeNull() }
        generationOf(id) shouldBe tokens.size
    }

    @Test
    fun `operations on unknown ids are 404 with their own codes`() {
        val unknown = UUID.randomUUID()
        asAdmin {
            for (op in listOf<() -> Unit>({ service.agent(unknown) }, { service.rotateToken(unknown) }, {
                service.deactivateAgent(unknown)
            }, { service.identity(unknown) })) {
                val error = errorOf(op)
                error.kind shouldBe ErrorKind.NOT_FOUND
                error.code shouldBe PrintingErrorCodes.PRINT_AGENT_NOT_FOUND
            }
            for (op in listOf<() -> Unit>({ service.printer(unknown) }, { service.deactivatePrinter(unknown) }, {
                service.updatePrinter(unknown, 0, PrinterChange("P", "a4", unknown))
            })) {
                val error = errorOf(op)
                error.kind shouldBe ErrorKind.NOT_FOUND
                error.code shouldBe PrintingErrorCodes.PRINTER_NOT_FOUND
            }
        }
    }

    // --- impressoras e agentes -----------------------------------------------------------------

    @Test
    fun `a printer of an agent deactivated later stays listed and can still be renamed in place`() {
        val agent = newAgent().view
        val printer = newPrinter(agent.id)
        asAdmin { service.deactivateAgent(agent.id) }

        asAdmin {
            service.printers().map { it.id } shouldContain printer.id
            service.printer(printer.id).active shouldBe true
            val renamed = service.updatePrinter(printer.id, printer.version, PrinterChange("Novo", "a9", agent.id))
            renamed.name shouldBe "Novo"
            renamed.agentId shouldBe agent.id
            service.deactivatePrinter(printer.id).active shouldBe false
        }
    }

    @Test
    fun `updatePrinter moves the printer to another active agent and audits both agent ids`() {
        val first = newAgent().view
        val second = newAgent().view
        val printer = newPrinter(first.id)

        val moved =
            asAdmin { service.updatePrinter(printer.id, printer.version, PrinterChange("Mov", "a4", second.id)) }

        moved.agentId shouldBe second.id
        printerRow(printer.id)["agent_id"] shouldBe second.id
        val row =
            jdbc
                .sql(
                    "SELECT before_state->>'agentId' AS b, after_state->>'agentId' AS a " +
                        "FROM audit.audit_entry WHERE action = 'printing.printer.update' AND entity_id = :id",
                ).param("id", printer.id.toString())
                .query()
                .singleRow()
        row["b"] shouldBe first.id.toString()
        row["a"] shouldBe second.id.toString()
    }

    @Test
    fun `moving to an inactive or unknown agent is refused and leaves the printer untouched`() {
        val agent = newAgent().view
        val dead = newAgent().view
        asAdmin { service.deactivateAgent(dead.id) }
        val printer = newPrinter(agent.id)
        val before = printerRow(printer.id)

        asAdmin {
            errorOf { service.updatePrinter(printer.id, printer.version, PrinterChange("Z", "z", dead.id)) }
                .code shouldBe PrintingErrorCodes.PRINT_AGENT_INACTIVE
            errorOf { service.updatePrinter(printer.id, printer.version, PrinterChange("Z", "z", UUID.randomUUID())) }
                .code shouldBe PrintingErrorCodes.UNKNOWN_PRINT_AGENT
        }
        printerRow(printer.id) shouldBe before
    }

    @Test
    fun `a stale version is 412 and a wrong version never changes anything`() {
        val agent = newAgent().view
        val printer = newPrinter(agent.id)
        val updated =
            asAdmin { service.updatePrinter(printer.id, printer.version, PrinterChange("V1", "a4", agent.id)) }
        updated.version shouldBe printer.version + 1
        val before = printerRow(printer.id)

        asAdmin {
            for (version in listOf(printer.version, updated.version + 1, -1L, Long.MAX_VALUE)) {
                val error =
                    errorOf { service.updatePrinter(printer.id, version, PrinterChange("Velho", "a4", agent.id)) }
                error.kind shouldBe ErrorKind.PRECONDITION_FAILED
            }
        }
        printerRow(printer.id) shouldBe before
    }

    @Test
    fun `a stale version is 412 even when the new values are invalid`() {
        val agent = newAgent().view
        val printer = newPrinter(agent.id)
        asAdmin { service.updatePrinter(printer.id, printer.version, PrinterChange("V1", "a4", agent.id)) }

        val error =
            asAdmin {
                errorOf {
                    service.updatePrinter(
                        printer.id,
                        printer.version,
                        PrinterChange("", "com espaco", agent.id),
                    )
                }
            }

        error.kind shouldBe ErrorKind.PRECONDITION_FAILED
    }

    @Test
    fun `concurrent updates with the same expected version let exactly one win`() {
        repeat(5) { _ ->
            val agent = newAgent().view
            val printer = newPrinter(agent.id)
            val names = List(4) { "Conc$it ${suffix()}" }
            val results =
                runConcurrentlyDifferent(
                    *names
                        .map { name ->
                            {
                                asAdmin {
                                    service.updatePrinter(
                                        printer.id,
                                        printer.version,
                                        PrinterChange(name, "a4", agent.id),
                                    )
                                }
                            }
                        }.toTypedArray(),
                )

            val winners = results.filter { it.isSuccess }
            winners.size shouldBe 1
            results.mapNotNull { it.exceptionOrNull() }.forEach { e ->
                isLostRace(e, ErrorKind.PRECONDITION_FAILED) shouldBe true
            }
            printerRow(printer.id)["name"] shouldBe winners.single().getOrThrow().name
            printerRow(printer.id)["version"] shouldBe printer.version + 1
        }
    }

    @Test
    fun `deactivating a printer twice keeps the version and a stale version is refused after deactivation`() {
        val agent = newAgent().view
        val printer = newPrinter(agent.id)

        val first = asAdmin { service.deactivatePrinter(printer.id) }
        val second = asAdmin { service.deactivatePrinter(printer.id) }

        second.version shouldBe first.version
        auditCount("printing.printer.deactivate", printer.id) shouldBe 1
        asAdmin {
            errorOf { service.updatePrinter(printer.id, printer.version, PrinterChange("P", "a4", agent.id)) }
                .kind shouldBe ErrorKind.PRECONDITION_FAILED
            errorOf { service.updatePrinter(printer.id, first.version, PrinterChange("P", "a4", agent.id)) }
                .code shouldBe PrintingErrorCodes.PRINTER_INACTIVE
        }
    }

    // --- entradas invalidas --------------------------------------------------------------------

    // Os invisiveis entram por codigo, pois o ktlint troca a sequencia de escape pelo caractere literal.
    private val zwsp = 0x200B.toChar()
    private val rlo = 0x202E.toChar()
    private val lineSep = 0x2028.toChar()
    private val paragraphSep = 0x2029.toChar()
    private val bom = 0xFEFF.toChar()
    private val nbsp = 0x00A0.toChar()

    private val badNames =
        listOf(
            "",
            "   ",
            "$zwsp",
            "a${zwsp}b",
            "${rlo}abc",
            "a\u0000b",
            "a\nb",
            "a\tb",
            "a${lineSep}b",
            "a${paragraphSep}b",
            "${bom}abc",
            "x".repeat(101),
        )

    private val badAddresses =
        listOf(
            "",
            " ",
            "a b",
            "a\tb",
            "a\nb",
            "a${zwsp}b",
            "a${rlo}b",
            "a${lineSep}b",
            "a${nbsp}b",
            "a\u0000b",
            "x".repeat(256),
        )

    private fun assertValidation(
        field: String,
        input: String,
        block: () -> Unit,
    ) {
        val thrown = runCatching(block).exceptionOrNull()
        val label = input.map { "U+" + it.code.toString(16) }.take(6)
        check(thrown is DomainException) { "entrada $label ($field) nao foi recusada com erro de dominio: $thrown" }
        val error: DomainException = thrown
        error.kind shouldBe ErrorKind.INVALID_INPUT
        error.code shouldBe ErrorCodes.VALIDATION_FAILED
        error.violations.map { it.field } shouldBe listOf(field)
    }

    @Test
    fun `invalid agent names reaching the service are validation errors and create no row`() {
        asAdmin {
            for (bad in badNames) assertValidation("name", bad) { service.createAgent(bad) }
            service
                .createAgent("x".repeat(100))
                .view.name.length shouldBe 100
            service
                .createAgent("  Aparado ${suffix()}  ")
                .view.name
                .startsWith("Aparado") shouldBe true
        }
    }

    @Test
    fun `invalid printer names and addresses are validation errors on create and on update`() {
        val agent = newAgent().view
        val printer = newPrinter(agent.id)
        val before = printerRow(printer.id)
        asAdmin {
            for (bad in badNames) {
                assertValidation(
                    "name",
                    bad,
                ) { service.createPrinter(NewPrinter(bad, PrinterKind.A4_PDF, "a4", agent.id)) }
                assertValidation("name", bad) {
                    service.updatePrinter(printer.id, printer.version, PrinterChange(bad, "a4", agent.id))
                }
            }
            for (bad in badAddresses) {
                assertValidation("address", bad) {
                    service.createPrinter(NewPrinter("P", PrinterKind.THERMAL_ESCPOS, bad, agent.id))
                }
                assertValidation("address", bad) {
                    service.updatePrinter(printer.id, printer.version, PrinterChange("P", bad, agent.id))
                }
            }
            service
                .createPrinter(
                    NewPrinter("P", PrinterKind.A4_PDF, "x".repeat(255), agent.id),
                ).address.length shouldBe
                255
        }
        printerRow(printer.id) shouldBe before
    }

    // --- identity e byTokenHash ----------------------------------------------------------------

    @Test
    fun `identity needs no permission and returns only the id and the name`() {
        val created = newAgent()

        val identity = service.identity(created.view.id)

        identity shouldBe PrintAgentIdentity(created.view.id, created.view.name)
        PrintAgentIdentity::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet() shouldBe setOf("id", "name")
        identity.toString() shouldNotContain created.token
        identity.toString() shouldNotContain PrintAgent.hashOf(created.token)
        actingAs(iam.createUser().id) { service.identity(created.view.id) shouldBe identity }
    }

    @Test
    fun `byTokenHash finds only the active agent with the current hash`() {
        val created = newAgent()
        val id = created.view.id
        val hash = PrintAgent.hashOf(created.token)

        store.byTokenHash(hash)?.id shouldBe id
        store.byTokenHash(hash.uppercase()).shouldBeNull()
        store.byTokenHash(created.token).shouldBeNull()
        store.byTokenHash("").shouldBeNull()
        store.byTokenHash("' OR '1'='1").shouldBeNull()

        val rotated = asAdmin { service.rotateToken(id) }
        store.byTokenHash(hash).shouldBeNull()
        store.byTokenHash(PrintAgent.hashOf(rotated.token)).shouldNotBeNull().id shouldBe id

        asAdmin { service.deactivateAgent(id) }
        store.byTokenHash(PrintAgent.hashOf(rotated.token)).shouldBeNull()
        store.byTokenHash(hash).shouldBeNull()
    }

    @Test
    fun `two agents never share a token hash and each token finds only its own agent`() {
        val a = newAgent()
        val b = newAgent()

        PrintAgent.hashOf(a.token) shouldNotBe PrintAgent.hashOf(b.token)
        store.byTokenHash(PrintAgent.hashOf(a.token))?.id shouldBe a.view.id
        store.byTokenHash(PrintAgent.hashOf(b.token))?.id shouldBe b.view.id
        asAdmin { service.agents().map { it.id } }.let {
            it shouldContain a.view.id
            it shouldNotContain UUID.randomUUID()
        }
    }

    // --- apoio de concorrencia -----------------------------------------------------------------

    private fun isLostRace(
        error: Throwable,
        kind: ErrorKind,
    ): Boolean = error is OptimisticLockingFailureException || (error is DomainException && error.kind == kind)

    private fun <T> runConcurrently(
        threads: Int,
        block: () -> T,
    ): List<Result<T>> = runConcurrentlyDifferent(*Array(threads) { block })

    private fun <T> runConcurrentlyDifferent(vararg blocks: () -> T): List<Result<T>> {
        val pool = Executors.newFixedThreadPool(blocks.size)
        try {
            val ready = CountDownLatch(blocks.size)
            val go = CountDownLatch(1)
            val futures =
                blocks.map { block ->
                    pool.submit<Result<T>> {
                        ready.countDown()
                        go.await()
                        runCatching(block)
                    }
                }
            ready.await(10, TimeUnit.SECONDS)
            go.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
