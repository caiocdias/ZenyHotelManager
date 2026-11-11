package com.zeny.hotel.printing.application

import com.zeny.hotel.printing.PrinterKind
import com.zeny.hotel.printing.PrintingErrorCodes
import com.zeny.hotel.printing.PrintingPermissions
import com.zeny.hotel.printing.domain.PrintAgent
import com.zeny.hotel.printing.domain.Printer
import com.zeny.hotel.printing.domain.requireValidName
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.requireCurrentVersion
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** O agente como a API o mostra: nunca o token nem o hash. */
data class PrintAgentView(
    val id: UUID,
    val name: String,
    val active: Boolean,
    val lastSeenAt: Instant?,
    val version: Long,
)

/** O agente criado ou com token novo: o [token] em claro existe só aqui e não se recupera. */
class CreatedAgent(
    val view: PrintAgentView,
    val token: String,
) {
    override fun toString(): String = "CreatedAgent(view=$view)"
}

/** Quem é o agente autenticado, para ele mesmo (`GET /print-agents/me`). */
data class PrintAgentIdentity(
    val id: UUID,
    val name: String,
)

data class PrinterView(
    val id: UUID,
    val name: String,
    val kind: PrinterKind,
    val address: String,
    val agentId: UUID,
    val active: Boolean,
    val version: Long,
)

data class NewPrinter(
    val name: String,
    val kind: PrinterKind,
    val address: String,
    val agentId: UUID,
)

/** O que muda numa impressora: o [PrinterKind] é fixo depois da criação. */
data class PrinterChange(
    val name: String,
    val address: String,
    val agentId: UUID,
)

/** Os agentes gravados, na transação corrente. */
interface PrintAgentStore {
    /** O agente ativo cujo token tem este SHA-256 (hex), ou `null`. */
    fun byTokenHash(hash: String): PrintAgent?

    fun byId(id: UUID): PrintAgent?

    fun all(): List<PrintAgent>

    fun save(agent: PrintAgent)

    /** Grava na hora as mudanças pendentes. */
    fun flush()
}

/** As impressoras gravadas, na transação corrente. */
interface PrinterStore {
    fun byId(id: UUID): Printer?

    fun all(): List<Printer>

    fun save(printer: Printer)

    /** Grava na hora as mudanças pendentes. */
    fun flush()
}

/**
 * Administração de agentes e impressoras (D-E10-2, D-E10-7 e D-E10-8). Escritas e leitura de agentes exigem
 * `printing.agents.manage`; ler as impressoras, `printing.jobs.read`. O token em claro só volta de [createAgent] e
 * [rotateToken]; a trilha leva só o retrato sem hash.
 */
@Service
@Suppress("TooManyFunctions") // um caso de uso por operação da API de administração (D-E10-8)
class PrintAgentsService(
    private val agents: PrintAgentStore,
    private val printers: PrinterStore,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Audited(PrintingAudit.CREATE_AGENT)
    @Transactional
    fun createAgent(name: String): CreatedAgent {
        authorization.require(PrintingPermissions.AGENTS_MANAGE)
        val (agent, token) = PrintAgent.create(validName(name), clock.instant())
        agents.save(agent)
        audit.created(PrintingAudit.AGENT, agent.id) { agent.auditState() }
        agents.flush()
        return CreatedAgent(agent.view(), token)
    }

    @Transactional(readOnly = true)
    fun agents(): List<PrintAgentView> {
        authorization.require(PrintingPermissions.AGENTS_MANAGE)
        return agents.all().map { it.view() }
    }

    @Transactional(readOnly = true)
    fun agent(id: UUID): PrintAgentView {
        authorization.require(PrintingPermissions.AGENTS_MANAGE)
        return agentOrNotFound(id).view()
    }

    /** Emite um token novo e invalida o anterior na hora; agente inativo: `422 PRINT_AGENT_INACTIVE`. */
    @Audited(PrintingAudit.ROTATE_TOKEN)
    @Transactional
    fun rotateToken(id: UUID): CreatedAgent {
        authorization.require(PrintingPermissions.AGENTS_MANAGE)
        val agent = agentOrNotFound(id)
        audit.track(PrintingAudit.AGENT, id) { agent.auditState() }
        val token = agent.rotateToken(clock.instant())
        agents.flush()
        return CreatedAgent(agent.view(), token)
    }

    /** Revoga o agente (apaga o hash). Já inativo: devolve como está, sem nova entrada na trilha. */
    @Audited(PrintingAudit.DEACTIVATE_AGENT)
    @Transactional
    fun deactivateAgent(id: UUID): PrintAgentView {
        authorization.require(PrintingPermissions.AGENTS_MANAGE)
        val agent = agentOrNotFound(id)
        audit.track(PrintingAudit.AGENT, id) { agent.auditState() }
        agent.deactivate()
        agents.flush()
        return agent.view()
    }

    /** Quem é o agente [agentId], já autenticado pelo token: serve ao próprio agente, sem permissão de usuário. */
    @Transactional(readOnly = true)
    fun identity(agentId: UUID): PrintAgentIdentity =
        agentOrNotFound(agentId).let { PrintAgentIdentity(it.id, it.name) }

    @Audited(PrintingAudit.CREATE_PRINTER)
    @Transactional
    fun createPrinter(command: NewPrinter): PrinterView {
        authorization.require(PrintingPermissions.AGENTS_MANAGE)
        val name = validName(command.name)
        requireUsableAgent(command.agentId)
        val printer =
            try {
                Printer.create(name, command.kind, command.address, command.agentId)
            } catch (e: IllegalArgumentException) {
                throw invalid("address", e)
            }
        printers.save(printer)
        audit.created(PrintingAudit.PRINTER, printer.id) { printer.auditState() }
        printers.flush()
        return printer.view()
    }

    @Transactional(readOnly = true)
    fun printers(): List<PrinterView> {
        authorization.require(PrintingPermissions.JOBS_READ)
        return printers.all().map { it.view() }
    }

    @Transactional(readOnly = true)
    fun printer(id: UUID): PrinterView {
        authorization.require(PrintingPermissions.JOBS_READ)
        return printerOrNotFound(id).view()
    }

    @Audited(PrintingAudit.UPDATE_PRINTER)
    @Transactional
    fun updatePrinter(
        id: UUID,
        expectedVersion: Long,
        change: PrinterChange,
    ): PrinterView {
        authorization.require(PrintingPermissions.AGENTS_MANAGE)
        val printer = printerOrNotFound(id)
        requireCurrentVersion(expectedVersion, checkNotNull(printer.version))
        requireActive(printer)
        val name = validName(change.name)
        // Trocar de agente exige um agente ativo; manter o atual (mesmo desativado) não impede renomear a impressora.
        if (change.agentId != printer.agentId) requireUsableAgent(change.agentId)
        audit.track(PrintingAudit.PRINTER, id) { printer.auditState() }
        try {
            printer.update(name, change.address, change.agentId)
        } catch (e: IllegalArgumentException) {
            throw invalid("address", e)
        }
        printers.flush()
        return printer.view()
    }

    /** Desativa a impressora, sem apagar a linha. Já inativa: devolve como está. */
    @Audited(PrintingAudit.DEACTIVATE_PRINTER)
    @Transactional
    fun deactivatePrinter(id: UUID): PrinterView {
        authorization.require(PrintingPermissions.AGENTS_MANAGE)
        val printer = printerOrNotFound(id)
        audit.track(PrintingAudit.PRINTER, id) { printer.auditState() }
        printer.deactivate()
        printers.flush()
        return printer.view()
    }

    private fun agentOrNotFound(id: UUID): PrintAgent =
        agents.byId(id) ?: throw NotFoundException(
            ErrorCodes.NOT_FOUND,
            "O agente de impressão não existe.",
        )

    private fun printerOrNotFound(id: UUID): Printer =
        printers.byId(id) ?: throw NotFoundException(
            ErrorCodes.NOT_FOUND,
            "A impressora não existe.",
        )

    private fun requireActive(printer: Printer) {
        if (!printer.active) {
            throw BusinessRuleException(PrintingErrorCodes.PRINTER_INACTIVE, "A impressora está desativada.")
        }
    }

    private fun requireUsableAgent(agentId: UUID) {
        val agent =
            agents.byId(agentId) ?: throw InvalidInputException(
                PrintingErrorCodes.UNKNOWN_PRINT_AGENT,
                "O agente de impressão não existe.",
                listOf(FieldViolation("agentId", "UNKNOWN_REFERENCE", "O agente de impressão não existe.")),
            )
        if (!agent.active) {
            throw BusinessRuleException(
                PrintingErrorCodes.PRINT_AGENT_INACTIVE,
                "O agente de impressão está desativado.",
            )
        }
    }

    private fun validName(name: String): String =
        try {
            requireValidName(name)
        } catch (e: IllegalArgumentException) {
            throw invalid("name", e)
        }

    private fun invalid(
        field: String,
        cause: IllegalArgumentException,
    ): InvalidInputException {
        val message = cause.message ?: "Valor inválido."
        return InvalidInputException(
            ErrorCodes.VALIDATION_FAILED,
            message,
            listOf(FieldViolation(field, "INVALID_FORMAT", message)),
            cause,
        )
    }

    private fun PrintAgent.view() = PrintAgentView(id, name, active, lastSeenAt, checkNotNull(version))

    private fun Printer.view() = PrinterView(id, name, kind, address, agentId, active, checkNotNull(version))
}

/** Publica as permissões do `printing` no catálogo do `iam` (ARCHITECTURE §8.2). */
@Configuration(proxyBeanMethods = false)
class PrintingPermissionsConfiguration {
    @Bean
    fun printingPermissions(): ModulePermissions = PrintingPermissions
}
