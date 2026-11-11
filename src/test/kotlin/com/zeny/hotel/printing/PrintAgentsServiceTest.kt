package com.zeny.hotel.printing

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.printing.application.NewPrinter
import com.zeny.hotel.printing.application.PrintAgentsService
import com.zeny.hotel.printing.application.PrinterChange
import com.zeny.hotel.printing.domain.PrintAgent
import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.ErrorKind
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.util.UUID

/** A administração de agentes e impressoras (M5 E10, Task 5): token só como hash, permissões e trilha sem segredo. */
@IntegrationTest
@Import(IamTestConfiguration::class)
class PrintAgentsServiceTest(
    @Autowired private val service: PrintAgentsService,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
) {
    private fun suffix() = UuidV7.generate().toString().takeLast(12)

    private fun admin() = UserId(iam.adminId())

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

    private fun <T> asAdmin(block: () -> T): T = actingAs(admin(), block)

    private fun reader(): UserId =
        iam
            .createUserWith(
                TestGrant(
                    PrintingPermissions.permissions.single {
                        it.code ==
                            PrintingPermissions.JOBS_READ
                    },
                    ALL,
                ),
            ).id

    private fun hashOf(agentId: UUID): String? =
        jdbc
            .sql("SELECT token_hash FROM printing.print_agent WHERE id = :id")
            .param("id", agentId)
            .query(String::class.java)
            .optional()
            .orElse(null)

    private fun errorOf(block: () -> Unit): DomainException = shouldThrow<DomainException>(block)

    private fun auditOf(action: String): List<String> =
        jdbc
            .sql(
                "SELECT COALESCE(CAST(before_state AS text), '') || '|' || COALESCE(CAST(after_state AS text), '') " +
                    "FROM audit.audit_entry WHERE action = :a",
            ).param("a", action)
            .query(String::class.java)
            .list()
            .filterNotNull()

    @Test
    fun `createAgent returns the token once and stores only its hash`(): Unit =
        asAdmin {
            val created = service.createAgent("Agente ${suffix()}")

            created.token shouldStartWith "zpa_"
            hashOf(created.view.id) shouldBe PrintAgent.hashOf(created.token)
            created.view.active shouldBe true
            service.agent(created.view.id).id shouldBe created.view.id
            service.agents().map { it.id } shouldContain created.view.id
            service.identity(created.view.id).name shouldBe created.view.name
        }

    @Test
    fun `rotateToken invalidates the previous token`(): Unit =
        asAdmin {
            val created = service.createAgent("Agente ${suffix()}")

            val rotated = service.rotateToken(created.view.id)

            rotated.token shouldNotBe created.token
            hashOf(created.view.id) shouldBe PrintAgent.hashOf(rotated.token)
            hashOf(created.view.id) shouldNotBe PrintAgent.hashOf(created.token)
        }

    @Test
    fun `deactivateAgent erases the hash and blocks rotation`(): Unit =
        asAdmin {
            val created = service.createAgent("Agente ${suffix()}")

            service.deactivateAgent(created.view.id).active shouldBe false

            hashOf(created.view.id).shouldBeNull()
            errorOf { service.rotateToken(created.view.id) }.code shouldBe PrintingErrorCodes.PRINT_AGENT_INACTIVE
        }

    @Test
    fun `an unknown agent is 404`(): Unit =
        asAdmin {
            val error = errorOf { service.agent(UUID.randomUUID()) }

            error.kind shouldBe ErrorKind.NOT_FOUND
            error.code shouldBe PrintingErrorCodes.PRINT_AGENT_NOT_FOUND
        }

    @Test
    fun `a printer needs an existing and active agent`(): Unit =
        asAdmin {
            val agent = service.createAgent("Agente ${suffix()}").view
            val unknown =
                errorOf { service.createPrinter(NewPrinter("P", PrinterKind.A4_PDF, "a4", UUID.randomUUID())) }
            service.deactivateAgent(agent.id)
            val inactive = errorOf { service.createPrinter(NewPrinter("P", PrinterKind.A4_PDF, "a4", agent.id)) }

            unknown.code shouldBe PrintingErrorCodes.UNKNOWN_PRINT_AGENT
            unknown.kind shouldBe ErrorKind.INVALID_INPUT
            inactive.code shouldBe PrintingErrorCodes.PRINT_AGENT_INACTIVE
            inactive.kind shouldBe ErrorKind.RULE_VIOLATION
        }

    @Test
    fun `invalid names and addresses are validation errors, not 500`(): Unit =
        asAdmin {
            val agent = service.createAgent("Agente ${suffix()}").view

            val agentName = errorOf { service.createAgent("   ") }
            val printerName = errorOf { service.createPrinter(NewPrinter("", PrinterKind.A4_PDF, "a4", agent.id)) }
            val address = errorOf { service.createPrinter(NewPrinter("P", PrinterKind.A4_PDF, "com espaco", agent.id)) }

            agentName.code shouldBe ErrorCodes.VALIDATION_FAILED
            agentName.violations.map { it.field } shouldBe listOf("name")
            printerName.code shouldBe ErrorCodes.VALIDATION_FAILED
            printerName.violations.map { it.field } shouldBe listOf("name")
            address.code shouldBe ErrorCodes.VALIDATION_FAILED
            address.violations.map { it.field } shouldBe listOf("address")
        }

    @Test
    fun `updatePrinter checks the version and keeps the kind`(): Unit =
        asAdmin {
            val agent = service.createAgent("Agente ${suffix()}").view
            val printer =
                service.createPrinter(
                    NewPrinter("P ${suffix()}", PrinterKind.THERMAL_ESCPOS, "usb1", agent.id),
                )
            val version = printer.version

            val updated = service.updatePrinter(printer.id, version, PrinterChange("Balcao", "usb2", agent.id))
            val stale = errorOf { service.updatePrinter(printer.id, version, PrinterChange("Outro", "usb3", agent.id)) }

            updated.name shouldBe "Balcao"
            updated.address shouldBe "usb2"
            updated.kind shouldBe PrinterKind.THERMAL_ESCPOS
            stale.kind shouldBe ErrorKind.PRECONDITION_FAILED
        }

    @Test
    fun `updatePrinter to an inactive agent is refused and deactivatePrinter keeps the row`(): Unit =
        asAdmin {
            val agent = service.createAgent("Agente ${suffix()}").view
            val other = service.createAgent("Outro ${suffix()}").view
            val printer = service.createPrinter(NewPrinter("P", PrinterKind.A4_PDF, "a4", agent.id))
            service.deactivateAgent(other.id)

            val toInactive =
                errorOf { service.updatePrinter(printer.id, printer.version, PrinterChange("P", "a4", other.id)) }
            val gone =
                errorOf {
                    service.updatePrinter(
                        printer.id,
                        printer.version,
                        PrinterChange("P", "a4", UUID.randomUUID()),
                    )
                }
            val off = service.deactivatePrinter(printer.id)

            toInactive.code shouldBe PrintingErrorCodes.PRINT_AGENT_INACTIVE
            gone.code shouldBe PrintingErrorCodes.UNKNOWN_PRINT_AGENT
            off.active shouldBe false
            service.printer(printer.id).active shouldBe false
            errorOf { service.updatePrinter(printer.id, off.version, PrinterChange("P", "a4", agent.id)) }
                .code shouldBe PrintingErrorCodes.PRINTER_INACTIVE
        }

    @Test
    fun `readers see the printers but cannot change anything`() {
        val agent = asAdmin { service.createAgent("Agente ${suffix()}").view }
        val printer = asAdmin { service.createPrinter(NewPrinter("P ${suffix()}", PrinterKind.A4_PDF, "a4", agent.id)) }
        val user = reader()

        actingAs(user) {
            service.printers().map { it.id } shouldContain printer.id
            service.printer(printer.id).id shouldBe printer.id
            val denied =
                listOf<() -> Unit>(
                    { service.createAgent("X") },
                    { service.agents() },
                    { service.agent(agent.id) },
                    { service.rotateToken(agent.id) },
                    { service.deactivateAgent(agent.id) },
                    { service.createPrinter(NewPrinter("P", PrinterKind.A4_PDF, "a4", agent.id)) },
                    { service.updatePrinter(printer.id, printer.version, PrinterChange("P", "a4", agent.id)) },
                    { service.deactivatePrinter(printer.id) },
                ).map { errorOf(it).kind }
            denied.toSet() shouldBe setOf(ErrorKind.PERMISSION_DENIED)
        }
    }

    @Test
    fun `the audit trail of the six actions carries neither the token nor the hash`() {
        val created = asAdmin { service.createAgent("Agente ${suffix()}") }
        val agent = created.view
        val rotated = asAdmin { service.rotateToken(agent.id) }
        val printer = asAdmin { service.createPrinter(NewPrinter("P ${suffix()}", PrinterKind.A4_PDF, "a4", agent.id)) }
        asAdmin {
            service.updatePrinter(printer.id, printer.version, PrinterChange("Novo ${suffix()}", "a4b", agent.id))
            service.deactivatePrinter(printer.id)
            service.deactivateAgent(agent.id)
        }

        val actions =
            listOf(
                "printing.agent.create",
                "printing.agent.rotate-token",
                "printing.agent.deactivate",
                "printing.printer.create",
                "printing.printer.update",
                "printing.printer.deactivate",
            )
        for (action in actions) {
            val entries = auditOf(action)
            (entries.isNotEmpty()) shouldBe true
            for (entry in entries) {
                for (secret in listOf(
                    created.token,
                    rotated.token,
                    PrintAgent.hashOf(created.token),
                    PrintAgent.hashOf(rotated.token),
                )) {
                    entry shouldNotContain secret
                }
                entry shouldNotContain "tokenHash"
                entry shouldNotContain "token_hash"
            }
        }
    }

    @Test
    fun `without a user the service refuses`() {
        errorOf { service.createAgent("X") }.kind shouldBe ErrorKind.UNAUTHENTICATED
    }
}
