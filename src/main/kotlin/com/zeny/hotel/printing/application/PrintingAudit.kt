package com.zeny.hotel.printing.application

import com.zeny.hotel.printing.domain.PrintAgent
import com.zeny.hotel.printing.domain.PrintJob
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
    const val JOB = "printing.job"
    const val CLAIM_JOB = "printing.job.claim"
    const val ACK_JOB = "printing.job.ack"
}

/**
 * O retrato do agente para a trilha: nome, situação e a quantidade de emissões do token (`emissionCount`, que sobe a
 * cada rotação). **Nunca** o hash nem o token. O mascaramento da trilha troca por `***` todo campo com "token" no
 * nome, por isso a contagem vem com outro nome, para a rotação ficar visível; `tokenGeneration` fica só por
 * compatibilidade. A [version] sobe a cada mudança gravada.
 */
internal data class PrintAgentAuditState(
    val id: String,
    val name: String,
    val active: Boolean,
    val tokenGeneration: Int,
    val emissionCount: Int,
    val version: Long?,
)

internal fun PrintAgent.auditState() =
    PrintAgentAuditState(id.toString(), name, active, tokenGeneration, tokenGeneration, version)

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

/** O retrato do job para a trilha (D-E10-3): sem conteúdo nem token. */
internal data class PrintJobAuditState(
    val id: String,
    val printerId: String,
    val documentType: String,
    val status: String,
    val attempts: Int,
    val deliveredTo: String?,
)

internal fun PrintJob.auditState() =
    PrintJobAuditState(
        id.toString(),
        printerId.toString(),
        documentType,
        status.name,
        attempts,
        deliveredTo?.toString(),
    )
