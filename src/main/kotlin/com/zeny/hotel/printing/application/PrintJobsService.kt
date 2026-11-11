package com.zeny.hotel.printing.application

import com.zeny.hotel.printing.NewPrintJob
import com.zeny.hotel.printing.PrintJobStatus
import com.zeny.hotel.printing.PrinterKind
import com.zeny.hotel.printing.PrintingApi
import com.zeny.hotel.printing.PrintingErrorCodes
import com.zeny.hotel.printing.PrintingPermissions
import com.zeny.hotel.printing.domain.PrintJob
import com.zeny.hotel.printing.domain.Printer
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ContentCipher
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.UuidV7
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** O job como a API o mostra: **nunca** o conteúdo. */
data class PrintJobView(
    val id: UUID,
    val printerId: UUID,
    val documentType: String,
    val status: PrintJobStatus,
    val attempts: Int,
    val createdAt: Instant,
    val sentAt: Instant?,
    val finishedAt: Instant?,
    val failureCode: String?,
)

/**
 * Os jobs gravados, na transação corrente. As consultas de manutenção devolvem entidades gerenciadas; quem as muda
 * trava a linha antes com [lockById].
 */
@Suppress("TooManyFunctions") // uma consulta por necessidade da entrega e da manutenção (D-E10-4, D-E10-6, D-E10-10)
interface PrintJobStore {
    /** A próxima posição da fila (sequence): cresce a cada job novo e a cada reenvio. */
    fun nextPosition(): Long

    fun save(job: PrintJob)

    /** Grava na hora as mudanças pendentes. */
    fun flush()

    /** O job sem travar (para leitura), ou `null`. */
    fun byId(id: UUID): PrintJob?

    /** O job travado para escrita (`FOR UPDATE`), ou `null`. Quem chega depois vê o estado novo. */
    fun lockById(id: UUID): PrintJob?

    /** Os jobs mais novos primeiro, filtrados por situação e impressora quando informadas. */
    fun page(
        status: PrintJobStatus?,
        printerId: UUID?,
        page: PageParams,
    ): CursorPage<PrintJob>

    /**
     * Trava (`FOR UPDATE SKIP LOCKED`) até [limit] jobs `PENDING` das impressoras **ativas** do agente [agentId] ativo,
     * na ordem da posição e **sem filtro de posição**: uma transação concorrente do mesmo agente pula as linhas
     * travadas, então nunca leva o mesmo job. Quem chama faz [PrintJob.claim] em cada um na mesma transação.
     */
    fun claimPending(
        agentId: UUID,
        limit: Int,
    ): List<PrintJob>

    /** Os `SENT` entregues ao [agentId] com posição maior que [afterPosition], na ordem da posição. */
    fun unackedSent(
        agentId: UUID,
        afterPosition: Long,
    ): List<PrintJob>

    /** Os `SENT` enviados antes de [before] (sem confirmação), os mais antigos primeiro. */
    fun expiredSent(
        before: Instant,
        limit: Int,
    ): List<PrintJob>

    /** Os `PENDING` criados antes de [before], os mais antigos primeiro. */
    fun stalePending(
        before: Instant,
        limit: Int,
    ): List<PrintJob>

    /** Os `PENDING` e `SENT` de impressoras desativadas. */
    fun ofInactivePrinters(limit: Int): List<PrintJob>

    /** Os `PRINTED`/`FAILED` terminados antes de [before] que ainda guardam o conteúdo. */
    fun purgeable(
        before: Instant,
        limit: Int,
    ): List<PrintJob>

    fun countByStatus(): Map<PrintJobStatus, Long>
}

/** Os tetos do conteúdo (D-E10-5): o da [PrintingApi] e o menor, da rota manual. */
@Component
class PrintJobLimits(
    @Value("\${zeny.printing.max-content-bytes}") val maxContentBytes: Int,
    @Value("\${zeny.printing.max-manual-content-bytes}") val maxManualContentBytes: Int,
)

/**
 * A fila de jobs de impressão (D-E10-4 e D-E10-5). O [enqueue] da [PrintingApi] não confere permissão; a [create] da
 * rota exige `printing.jobs.manage` e o teto manual; [jobs] exige `printing.jobs.read`. O conteúdo é validado, cifrado
 * com o id do job como dado associado e nunca volta em resposta, log nem trilha.
 */
@Service
@Suppress("LongParameterList") // as portas e os colaboradores do caso de uso; os tetos já vão agrupados
class PrintJobsService(
    private val store: PrintJobStore,
    private val printers: PrinterStore,
    private val cipher: ContentCipher,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val clock: Clock,
    private val limits: PrintJobLimits,
) : PrintingApi {
    @Audited(JOB_CREATE)
    @Transactional
    override fun enqueue(job: NewPrintJob): UUID = place(job, limits.maxContentBytes).id

    /** A rota manual: `printing.jobs.manage` e o teto menor. */
    @Audited(JOB_CREATE)
    @Transactional
    fun create(job: NewPrintJob): PrintJobView {
        authorization.require(PrintingPermissions.JOBS_MANAGE)
        return place(job, limits.maxManualContentBytes).view()
    }

    @Transactional(readOnly = true)
    fun jobs(
        status: PrintJobStatus?,
        printerId: UUID?,
        page: PageParams,
    ): CursorPage<PrintJobView> {
        authorization.require(PrintingPermissions.JOBS_READ)
        return store.page(status, printerId, page).map { it.view() }
    }

    private fun place(
        job: NewPrintJob,
        ceiling: Int,
    ): PrintJob {
        requireDocumentType(job.documentType)
        requireSize(job.content, ceiling)
        val printer = usablePrinter(job.printerId)
        if (printer.kind == PrinterKind.A4_PDF && !job.content.startsWithPdfMagic()) {
            throw BusinessRuleException(
                PrintingErrorCodes.PRINT_CONTENT_NOT_PDF,
                "O conteúdo para uma impressora A4 precisa ser um PDF.",
            )
        }
        val id = UuidV7.generate()
        val sealed = cipher.encrypt(job.content, associatedData(id))
        val created =
            PrintJob.create(
                id,
                printer.id,
                printer.kind,
                job.documentType,
                sealed,
                store.nextPosition(),
                clock.instant(),
            )
        store.save(created)
        audit.created(JOB, id) { created.auditState() }
        store.flush()
        return created
    }

    private fun usablePrinter(id: UUID): Printer {
        val printer =
            printers.byId(id) ?: throw InvalidInputException(
                PrintingErrorCodes.UNKNOWN_PRINTER,
                "A impressora não existe.",
                listOf(FieldViolation("printerId", "UNKNOWN_REFERENCE", "A impressora não existe.")),
            )
        if (!printer.active) {
            throw BusinessRuleException(PrintingErrorCodes.PRINTER_INACTIVE, "A impressora está desativada.")
        }
        return printer
    }

    private fun requireDocumentType(type: String) {
        if (type.length > DOCUMENT_TYPE_MAX || !DOCUMENT_TYPE.matches(type)) {
            throw InvalidInputException(
                ErrorCodes.VALIDATION_FAILED,
                "O tipo do documento deve seguir ${DOCUMENT_TYPE.pattern}.",
                listOf(
                    FieldViolation("documentType", "INVALID_FORMAT", "Tipo de documento inválido."),
                ),
            )
        }
    }

    private fun requireSize(
        content: ByteArray,
        ceiling: Int,
    ) {
        if (content.isEmpty()) {
            throw BusinessRuleException(PrintingErrorCodes.PRINT_CONTENT_EMPTY, "O conteúdo a imprimir está vazio.")
        }
        if (content.size > ceiling) {
            throw BusinessRuleException(
                PrintingErrorCodes.PRINT_CONTENT_TOO_LARGE,
                "O conteúdo a imprimir passa do limite de $ceiling bytes.",
            )
        }
    }

    private fun ByteArray.startsWithPdfMagic(): Boolean =
        size >= PDF_MAGIC.size && PDF_MAGIC.indices.all { this[it] == PDF_MAGIC[it] }

    private fun PrintJob.view() =
        PrintJobView(id, printerId, documentType, status, attempts, createdAt, sentAt, finishedAt, failureCode)

    companion object {
        const val JOB_CREATE = "printing.job.create"
        private const val JOB = "printing.job"
        private const val DOCUMENT_TYPE_MAX = 100
        private val DOCUMENT_TYPE = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+")
        private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)

        /** O dado associado da cifragem do conteúdo do job [id]: o conteúdo de um job não decifra como o de outro. */
        fun associatedData(id: UUID): ByteArray = id.toString().toByteArray(Charsets.UTF_8)
    }
}
