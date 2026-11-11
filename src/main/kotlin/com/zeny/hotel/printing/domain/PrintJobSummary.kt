package com.zeny.hotel.printing.domain

import com.zeny.hotel.printing.PrintJobStatus
import java.time.Instant
import java.util.UUID

/**
 * O job de impressão sem o conteúdo cifrado: a projeção das consultas que só listam ou escolhem candidatos. O
 * `content` pode ter até alguns MiB por job e essas consultas não o usam, então ele nem sai do banco.
 */
data class PrintJobSummary(
    val id: UUID,
    val printerId: UUID,
    val documentType: String,
    val mediaType: String,
    val createdAt: Instant,
    val position: Long,
    val status: PrintJobStatus,
    val attempts: Int,
    val sentAt: Instant?,
    val finishedAt: Instant?,
    val failureCode: String?,
)
