package com.zeny.hotel.printing.application

import com.zeny.hotel.printing.PrintJobStatus
import com.zeny.hotel.printing.domain.PrintJob
import com.zeny.hotel.printing.domain.PrintJobSummary
import com.zeny.hotel.printing.infrastructure.PrintingProperties
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** O que uma rodada da manutenção fez, por tipo de mudança. */
data class PrintMaintenanceRun(
    /** `SENT` sem confirmação que voltaram a `PENDING`. */
    val requeued: Int,
    /** `SENT` sem confirmação na última tentativa: `FAILED` (`ACK_TIMEOUT`). */
    val ackTimedOut: Int,
    /** `PENDING` vencidos: `FAILED` (`EXPIRED`). */
    val expired: Int,
    /** `PENDING` e `SENT` de impressora ou agente desativado: `FAILED` (`PRINTER_INACTIVE`). */
    val inactive: Int,
    /** Jobs terminados que perderam o conteúdo. */
    val purged: Int,
    /** Jobs que falharam por erro inesperado (a rodada segue com os demais). */
    val failed: Int,
)

/**
 * A manutenção da fila de impressão (D-E10-4 e D-E10-10): reenvio ou `ACK_TIMEOUT` do `SENT` sem confirmação, validade
 * dos `PENDING`, jobs de impressora inativa e expurgo do conteúdo. As consultas só escolhem candidatos; cada job muda
 * na transação própria, travado (`FOR UPDATE`) e **relido**: quem chegou antes (um ack, outra instância) vence e o job
 * é pulado. Escritas auditadas com o ator sistema, sem conteúdo.
 */
@Service
@Suppress("LongParameterList", "TooManyFunctions") // as portas do caso de uso e um passo por regra da manutenção
class PrintJobsMaintenance(
    private val store: PrintJobStore,
    private val printers: PrinterStore,
    private val agents: PrintAgentStore,
    private val properties: PrintingProperties,
    private val audit: AuditContext,
    private val clock: Clock,
    // Cada job muda pelo proxy: sem ele, `@Transactional` e `@Audited` não valem.
    @param:Lazy private val self: PrintJobsMaintenance,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Uma rodada completa agora. */
    fun run(): PrintMaintenanceRun = run(clock.instant())

    /**
     * Uma rodada completa, no instante [now]. Não é transacional: cada job tem a sua transação. Sem argumento padrão:
     * o `$default` do Kotlin leria os campos do proxy, que ficam nulos.
     */
    fun run(now: Instant): PrintMaintenanceRun {
        val at = now.truncatedTo(ChronoUnit.MICROS)
        val acks = sweep({ store.expiredSent(at.minus(properties.ackTimeout), BATCH) }) { self.expireAck(it, at) }
        val stale = sweep({ store.stalePending(at.minus(properties.pendingTtl), BATCH) }) { self.expire(it, at) }
        val ofPrinters = sweep({ store.ofInactivePrinters(BATCH) }) { self.failInactive(it, at) }
        val ofAgents = sweep({ store.ofDeactivatedAgents(BATCH) }) { self.failInactive(it, at) }
        val purged = sweep({ store.purgeable(at.minus(properties.contentRetention), BATCH) }) { self.purge(it, at) }
        val run =
            PrintMaintenanceRun(
                requeued = acks.changed.count { it == PrintJobStatus.PENDING },
                ackTimedOut = acks.changed.count { it == PrintJobStatus.FAILED },
                expired = stale.changed.size,
                inactive = ofPrinters.changed.size + ofAgents.changed.size,
                purged = purged.changed.size,
                failed = listOf(acks, stale, ofPrinters, ofAgents, purged).sumOf { it.failures },
            )
        if (run.requeued + run.ackTimedOut + run.expired + run.inactive + run.purged + run.failed > 0) {
            log.info(
                "Manutenção da fila de impressão: {} reenviados, {} sem confirmação, {} vencidos, {} de impressora " +
                    "inativa, {} com conteúdo apagado, {} com falha",
                run.requeued,
                run.ackTimedOut,
                run.expired,
                run.inactive,
                run.purged,
                run.failed,
            )
        }
        return run
    }

    private class Sweep(
        val changed: List<PrintJobStatus>,
        val failures: Int,
    )

    /**
     * Percorre os candidatos em lotes (no máximo [MAX_BATCHES]); um lote sem mudança alguma encerra o passo. Um job
     * que falha é contado e os demais seguem.
     */
    private fun sweep(
        candidates: () -> List<PrintJobSummary>,
        handle: (UUID) -> PrintJobStatus?,
    ): Sweep {
        val changed = mutableListOf<PrintJobStatus>()
        var failures = 0
        var rounds = 0
        do {
            val ids = candidates().map { it.id }
            val before = changed.size
            ids.forEach { id ->
                try {
                    handle(id)?.let(changed::add)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    failures++
                    log.warn("Manutenção da fila de impressão: o job {} falhou ({})", id, e.javaClass.simpleName)
                }
            }
            rounds++
        } while (changed.size > before && ids.size == BATCH && rounds < MAX_BATCHES)
        return Sweep(changed, failures)
    }

    /**
     * `SENT` sem confirmação: de volta a `PENDING` com posição nova ou `FAILED`. Devolve o estado novo do job, ou
     * `null` se pulou (como os demais passos).
     */
    @Audited(EXPIRE_ACK)
    @Transactional
    fun expireAck(
        id: UUID,
        now: Instant,
    ): PrintJobStatus? {
        val job = store.lockById(id)
        val cutoff = now.minus(properties.ackTimeout)
        if (job == null || job.status != PrintJobStatus.SENT || job.sentAt?.isBefore(cutoff) != true) {
            audit.noChanges()
            return null
        }
        audit.track(PrintingAudit.JOB, id) { job.auditState() }
        job.expireAck(now, properties.maxAttempts, store.nextPosition())
        store.flush()
        return job.status
    }

    /** `PENDING` vencido: `FAILED` (`EXPIRED`). */
    @Audited(EXPIRE)
    @Transactional
    fun expire(
        id: UUID,
        now: Instant,
    ): PrintJobStatus? {
        val job = store.lockById(id)
        val cutoff = now.minus(properties.pendingTtl)
        if (job == null || job.status != PrintJobStatus.PENDING || !job.createdAt.isBefore(cutoff)) {
            audit.noChanges()
            return null
        }
        audit.track(PrintingAudit.JOB, id) { job.auditState() }
        job.fail(PrintJob.EXPIRED, now)
        store.flush()
        return job.status
    }

    /** `PENDING` ou `SENT` de impressora ou agente desativado: `FAILED` (`PRINTER_INACTIVE`). */
    @Audited(FAIL_INACTIVE)
    @Transactional
    fun failInactive(
        id: UUID,
        now: Instant,
    ): PrintJobStatus? {
        val job = store.lockById(id)
        if (job == null || !job.isQueued() || !isInactive(job)) {
            audit.noChanges()
            return null
        }
        audit.track(PrintingAudit.JOB, id) { job.auditState() }
        job.fail(PrintJob.PRINTER_INACTIVE, now)
        store.flush()
        return job.status
    }

    /** Apaga o conteúdo de um job terminado há mais que a retenção. */
    @Audited(PURGE)
    @Transactional
    fun purge(
        id: UUID,
        now: Instant,
    ): PrintJobStatus? {
        val job = store.lockById(id)
        val cutoff = now.minus(properties.contentRetention)
        if (job == null || !job.isPurgeable(cutoff)) {
            audit.noChanges()
            return null
        }
        audit.track(PrintingAudit.JOB, id) { job.auditState() }
        job.purgeContent(now)
        store.flush()
        return job.status
    }

    private fun PrintJob.isPurgeable(cutoff: Instant): Boolean {
        val finished = status == PrintJobStatus.PRINTED || status == PrintJobStatus.FAILED
        return finished && finishedAt?.isBefore(cutoff) == true && content != null
    }

    private fun PrintJob.isQueued() = status == PrintJobStatus.PENDING || status == PrintJobStatus.SENT

    private fun isInactive(job: PrintJob): Boolean {
        val printer = printers.byId(job.printerId) ?: return false
        return !printer.active || agents.byId(printer.agentId)?.active == false
    }

    companion object {
        const val EXPIRE_ACK = "printing.job.expire-ack"
        const val EXPIRE = "printing.job.expire"
        const val FAIL_INACTIVE = "printing.job.fail-inactive"
        const val PURGE = "printing.job.purge-content"
        private const val BATCH = 100
        private const val MAX_BATCHES = 10
    }
}
