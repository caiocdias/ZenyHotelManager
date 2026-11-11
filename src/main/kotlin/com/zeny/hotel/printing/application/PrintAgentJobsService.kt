package com.zeny.hotel.printing.application

import com.zeny.hotel.printing.PrintJobStatus
import com.zeny.hotel.printing.PrintingErrorCodes
import com.zeny.hotel.printing.domain.PrintJob
import com.zeny.hotel.printing.infrastructure.PrintingProperties
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.ContentCipher
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.ServicePrincipal
import com.zeny.hotel.shared.UnauthenticatedException
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** O job como o agente o recebe (sem o conteúdo, que ele busca à parte). [position] é o `id` do evento do stream. */
data class DeliveredJob(
    val id: UUID,
    val printerId: UUID,
    val documentType: String,
    val mediaType: String,
    val attempts: Int,
    val createdAt: Instant,
    val position: Long,
)

/** O conteúdo decifrado de um job entregue. Nunca em log: o [toString] não o mostra. */
class JobContent(
    val mediaType: String,
    val bytes: ByteArray,
) {
    override fun toString(): String = "JobContent(mediaType=$mediaType, size=${bytes.size})"
}

/**
 * A entrega dos jobs ao agente de impressão (D-E10-3 a D-E10-6). O agente vem do [ServicePrincipal]; a cada chamada
 * ele precisa seguir ativo e com o mesmo `credentialStamp`, senão `401`. As escritas são auditadas com ator sistema
 * (o agente não é um usuário). Um job só é do agente se foi entregue a ele **e** a impressora ainda é dele: depois de
 * mover a impressora, o agente antigo não lê nem confirma o que enviou (`404`, sem vazar a existência).
 */
@Service
@Suppress("LongParameterList", "TooManyFunctions") // as portas do caso de uso e uma operação por rota do agente
class PrintAgentJobsService(
    private val store: PrintJobStore,
    private val agents: PrintAgentStore,
    private val printers: PrinterStore,
    private val cipher: ContentCipher,
    private val audit: AuditContext,
    private val clock: Clock,
    private val connections: PrintAgentConnections,
    private val properties: PrintingProperties,
    // O laço do long-poll chama os casos de uso pelo proxy: sem ele, `@Transactional` e `@Audited` não valem.
    @param:Lazy private val self: PrintAgentJobsService,
) {
    /** O `wait` pedido em segundos (padrão 25, no máximo o teto configurado); fora de 0..teto, `400`. */
    fun resolveWait(seconds: Int?): Duration {
        val ceiling = properties.longPollMaxWait
        if (seconds == null) return minOf(DEFAULT_WAIT, ceiling)
        if (seconds < 0 || seconds > ceiling.seconds) {
            throw InvalidInputException(
                ErrorCodes.VALIDATION_FAILED,
                "O wait deve ficar entre 0 e ${ceiling.seconds} segundos.",
                listOf(FieldViolation("wait", "OUT_OF_RANGE", "Use de 0 a ${ceiling.seconds} segundos.")),
            )
        }
        return Duration.ofSeconds(seconds.toLong())
    }

    /** Abre uma conexão do agente (despeja a mais antiga acima do limite). O chamador a fecha ao responder. */
    fun open(principal: ServicePrincipal): AgentConnection = connections.open(principal.id)

    /**
     * Reivindica até [limit] jobs `PENDING` das impressoras do agente: `PENDING` → `SENT`, na mesma transação. Duas
     * chamadas simultâneas nunca levam o mesmo job (`SKIP LOCKED`). Sem nada a entregar, não grava entrada na trilha.
     */
    @Audited(PrintingAudit.CLAIM_JOB)
    @Transactional
    fun claim(
        principal: ServicePrincipal,
        limit: Int,
    ): List<DeliveredJob> {
        require(limit > 0) { "limit precisa ser positivo" }
        requireValid(principal)
        val claimed = store.claimPending(principal.id, limit)
        if (claimed.isEmpty()) {
            audit.noChanges()
            return emptyList()
        }
        val now = clock.instant()
        claimed.forEach {
            audit.track(PrintingAudit.JOB, it.id) { it.auditState() }
            it.claim(principal.id, now)
        }
        store.flush()
        return claimed.map { it.delivered() }
    }

    /**
     * O long-poll: devolve o que reivindicar e, sem nada, espera consultando a cada `delivery-poll-interval` até achar
     * ou acabar o [wait]. A [connection] encerrada (despejo, parada, resposta já fechada) devolve a lista vazia na
     * hora, sem reivindicar. Nenhuma transação fica aberta durante a espera.
     */
    fun awaitJobs(
        principal: ServicePrincipal,
        wait: Duration,
        connection: AgentConnection,
    ): List<DeliveredJob> {
        val deadline = System.nanoTime() + wait.toNanos()
        while (!connection.isDone) {
            val found = self.claim(principal, CLAIM_BATCH)
            val left = Duration.ofNanos(deadline - System.nanoTime())
            if (found.isNotEmpty() || left.isNegative || left.isZero) return found
            if (connection.awaitDone(minOf(left, properties.deliveryPollInterval))) break
        }
        return emptyList()
    }

    /** Os `SENT` do agente sem confirmação e de posição maior que [afterPosition]: o que o stream reenvia. */
    @Transactional(readOnly = true)
    fun unacknowledged(
        principal: ServicePrincipal,
        afterPosition: Long,
    ): List<DeliveredJob> {
        requireValid(principal)
        return store
            .unackedSent(principal.id, afterPosition)
            .filter { ownsPrinterOf(it, principal.id) }
            .map { it.delivered() }
    }

    /** O conteúdo do job `SENT` entregue ao agente; qualquer outro caso é `404`. */
    @Transactional(readOnly = true)
    fun content(
        principal: ServicePrincipal,
        jobId: UUID,
    ): JobContent {
        requireValid(principal)
        val job = store.byId(jobId)
        val sealed = job?.content
        if (job == null || sealed == null || !job.isSentTo(principal.id)) throw notFound()
        return JobContent(job.mediaType, cipher.decrypt(sealed, PrintJobsService.associatedData(job.id)))
    }

    /**
     * O agente confirma o job: `PRINTED` ([printed]) ou `FAILED` com o [failureCode] opcional. Impressora de outro
     * agente ou job inexistente: `404`; job de impressora dele fora de `SENT` para ele: `409`. A linha é travada:
     * quem chega depois vê o estado novo.
     */
    @Audited(PrintingAudit.ACK_JOB)
    @Transactional
    fun acknowledge(
        principal: ServicePrincipal,
        jobId: UUID,
        printed: Boolean,
        failureCode: String?,
    ) {
        requireValid(principal)
        val job = store.lockById(jobId)
        // Impressora de outro agente ou job inexistente: 404. Impressora própria e job fora de SENT para este
        // agente (voltou a PENDING, terminado, entregue a outro antes da troca de dono): 409 do domínio.
        if (job == null || !ownsPrinterOf(job, principal.id)) throw notFound()
        audit.track(PrintingAudit.JOB, job.id) { job.auditState() }
        job.acknowledge(principal.id, printed, failureCode, clock.instant())
        store.flush()
    }

    private fun requireValid(principal: ServicePrincipal) {
        val agent = agents.byId(principal.id)
        if (agent == null || !agent.active || agent.credentialStamp != principal.credentialStamp) {
            throw UnauthenticatedException(ErrorCodes.UNAUTHENTICATED, "O token do agente não vale mais.")
        }
    }

    private fun PrintJob.isSentTo(agentId: UUID): Boolean =
        status == PrintJobStatus.SENT &&
            isDeliveredTo(agentId) &&
            ownsPrinterOf(this, agentId)

    private fun ownsPrinterOf(
        job: PrintJob,
        agentId: UUID,
    ): Boolean = printers.byId(job.printerId)?.agentId == agentId

    private fun notFound() =
        NotFoundException(PrintingErrorCodes.PRINT_JOB_NOT_FOUND, "O job de impressão não existe para este agente.")

    private fun PrintJob.delivered() =
        DeliveredJob(id, printerId, documentType, mediaType, attempts, createdAt, position)

    private companion object {
        val DEFAULT_WAIT: Duration = Duration.ofSeconds(25)
        const val CLAIM_BATCH = 20
    }
}
