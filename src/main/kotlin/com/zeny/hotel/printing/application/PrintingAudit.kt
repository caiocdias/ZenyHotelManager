package com.zeny.hotel.printing.application

import com.zeny.hotel.printing.domain.PrintAgent
import com.zeny.hotel.printing.domain.Printer

internal object PrintingAudit {
    const val AGENT = "printing.agent"
    const val CREATE_AGENT = "printing.agent.create"
    const val ROTATE_TOKEN = "printing.agent.rotate-token"
    const val DEACTIVATE_AGENT = "printing.agent.deactivate"
    const val PRINTER = "printing.printer"
    const val CREATE_PRINTER = "printing.printer.create"
    const val UPDATE_PRINTER = "printing.printer.update"
    const val DEACTIVATE_PRINTER = "printing.printer.deactivate"
}

/**
 * O retrato do agente para a trilha: nome, situação e a geração do token (que muda a cada rotação). **Nunca** o hash
 * nem o token. A [version] sobe a cada mudança gravada.
 */
internal data class PrintAgentAuditState(
    val id: String,
    val name: String,
    val active: Boolean,
    val tokenGeneration: Int,
    val version: Long?,
)

internal fun PrintAgent.auditState() = PrintAgentAuditState(id.toString(), name, active, tokenGeneration, version)

/** O retrato da impressora para a trilha. */
internal data class PrinterAuditState(
    val id: String,
    val name: String,
    val kind: String,
    val address: String,
    val agentId: String,
    val active: Boolean,
    val version: Long?,
)

internal fun Printer.auditState() =
    PrinterAuditState(id.toString(), name, kind.name, address, agentId.toString(), active, version)
