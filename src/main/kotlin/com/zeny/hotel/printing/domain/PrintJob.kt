package com.zeny.hotel.printing.domain

import com.zeny.hotel.printing.PrintJobStatus
import com.zeny.hotel.printing.PrinterKind
import com.zeny.hotel.printing.PrintingErrorCodes
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.InvalidInputException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/**
 * O job de impressão (SPEC §8.2.18, D-E10-4): `PENDING` → `SENT` (reivindicado por um agente) → `PRINTED` ou `FAILED`.
 * Sem confirmação até o prazo, o job volta a `PENDING` com posição nova, ou vira `FAILED` (`ACK_TIMEOUT`) na última
 * tentativa. O [content] é o conteúdo **já cifrado** (D-E10-5) e some depois da retenção ([purgeContent]); o
 * [failureCode] é sempre um código fixo, nunca texto livre.
 *
 * **Nunca** ponha o [content] em `toString`, log ou trilha.
 */
@Entity
@Table(name = "print_job", schema = "printing")
class PrintJob private constructor(
    @Id
    val id: UUID,
    @Column(name = "printer_id", nullable = false)
    val printerId: UUID,
    @Column(name = "document_type", nullable = false)
    val documentType: String,
    @Column(name = "media_type", nullable = false, length = MEDIA_TYPE_LENGTH)
    val mediaType: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    position: Long,
    content: ByteArray,
) {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = STATUS_LENGTH)
    var status: PrintJobStatus = PrintJobStatus.PENDING
        protected set

    @Column(nullable = false)
    var position: Long = position
        protected set

    @Column(nullable = false)
    var attempts: Int = 0
        protected set

    @Column
    var content: ByteArray? = content
        protected set

    @Column(name = "content_purged_at")
    var contentPurgedAt: Instant? = null
        protected set

    @Column(name = "sent_at")
    var sentAt: Instant? = null
        protected set

    @Column(name = "delivered_to")
    var deliveredTo: UUID? = null
        protected set

    @Column(name = "finished_at")
    var finishedAt: Instant? = null
        protected set

    @Column(name = "failure_code", length = FAILURE_CODE_MAX)
    var failureCode: String? = null
        protected set

    @Version
    var version: Long? = null
        protected set

    fun isDeliveredTo(agentId: UUID): Boolean = deliveredTo == agentId

    /** O agente reivindica o job: `PENDING` → `SENT`, com mais uma tentativa. */
    fun claim(
        agentId: UUID,
        now: Instant,
    ) {
        check(status == PrintJobStatus.PENDING) { "Só um job PENDING pode ser reivindicado (estado: $status)." }
        status = PrintJobStatus.SENT
        attempts += 1
        sentAt = now
        deliveredTo = agentId
    }

    /**
     * O agente a que o job foi entregue confirma: `SENT` → `PRINTED` ([printed]) ou `FAILED` com o [failureCode]
     * opcional. Fora de `SENT`, ou de outro agente, é `PRINT_JOB_NOT_ACKNOWLEDGEABLE`; a camada de aplicação decide
     * antes se o job é do agente (`404`) ou não.
     */
    fun acknowledge(
        agentId: UUID,
        printed: Boolean,
        failureCode: String?,
        now: Instant,
    ) {
        if (status != PrintJobStatus.SENT || !isDeliveredTo(agentId)) {
            throw ConflictException(
                PrintingErrorCodes.PRINT_JOB_NOT_ACKNOWLEDGEABLE,
                "O job de impressão não está aguardando confirmação deste agente.",
            )
        }
        if (failureCode != null && !FAILURE_CODE.matches(failureCode)) {
            throw InvalidInputException(
                PrintingErrorCodes.INVALID_FAILURE_CODE,
                "O código de falha deve seguir ${FAILURE_CODE.pattern}.",
            )
        }
        if (printed) {
            finish(PrintJobStatus.PRINTED, null, now)
        } else {
            finish(PrintJobStatus.FAILED, failureCode, now)
        }
    }

    /**
     * Sem confirmação no prazo: com `attempts >= maxAttempts` o job vira `FAILED` (`ACK_TIMEOUT`); senão volta a
     * `PENDING` com a [newPosition], para passar do `Last-Event-ID` do agente.
     */
    fun expireAck(
        now: Instant,
        maxAttempts: Int,
        newPosition: Long,
    ) {
        check(status == PrintJobStatus.SENT) { "Só um job SENT pode expirar a confirmação (estado: $status)." }
        if (attempts >= maxAttempts) {
            finish(PrintJobStatus.FAILED, ACK_TIMEOUT, now)
            return
        }
        status = PrintJobStatus.PENDING
        position = newPosition
        sentAt = null
        deliveredTo = null
    }

    /** Encerra um job `PENDING` ou `SENT` com falha de sistema (`EXPIRED`, `PRINTER_INACTIVE`). */
    fun fail(
        code: String,
        now: Instant,
    ) {
        require(FAILURE_CODE.matches(code)) { "O código de falha deve seguir ${FAILURE_CODE.pattern}." }
        check(status == PrintJobStatus.PENDING || status == PrintJobStatus.SENT) {
            "Só um job PENDING ou SENT pode falhar (estado: $status)."
        }
        finish(PrintJobStatus.FAILED, code, now)
    }

    /** Apaga o conteúdo de um job terminado, depois da retenção. */
    fun purgeContent(now: Instant) {
        check(status == PrintJobStatus.PRINTED || status == PrintJobStatus.FAILED) {
            "Só o conteúdo de um job PRINTED ou FAILED pode ser apagado (estado: $status)."
        }
        content = null
        contentPurgedAt = now
    }

    private fun finish(
        to: PrintJobStatus,
        code: String?,
        now: Instant,
    ) {
        status = to
        failureCode = code
        finishedAt = now
    }

    override fun toString(): String =
        "PrintJob(id=$id, printerId=$printerId, status=$status, attempts=$attempts, position=$position)"

    companion object {
        /** Códigos de falha de sistema (D-E10-4). */
        const val ACK_TIMEOUT = "ACK_TIMEOUT"
        const val EXPIRED = "EXPIRED"
        const val PRINTER_INACTIVE = "PRINTER_INACTIVE"

        private const val FAILURE_CODE_MAX = 50
        private const val MEDIA_TYPE_LENGTH = 50
        private const val STATUS_LENGTH = 20
        private val FAILURE_CODE = Regex("[A-Z][A-Z0-9_]{0,${FAILURE_CODE_MAX - 1}}")

        /** Cria o job `PENDING`. O [sealedContent] já vem cifrado; o tipo de mídia sai do [kind]. */
        @Suppress("LongParameterList")
        fun create(
            id: UUID,
            printerId: UUID,
            kind: PrinterKind,
            documentType: String,
            sealedContent: ByteArray,
            position: Long,
            now: Instant,
        ): PrintJob = PrintJob(id, printerId, documentType, kind.mediaType, now, position, sealedContent)
    }
}
