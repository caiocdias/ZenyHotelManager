package com.zeny.hotel.endofday.domain

import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Id da execução do fechamento (UUIDv7). */
@JvmInline
value class EndOfDayRunId(
    override val value: UUID,
) : TypedId {
    companion object {
        fun new() = EndOfDayRunId(UuidV7.generate())
    }
}

/** Quem disparou a execução: o gatilho do horário ou alguém, manualmente (com step-up). */
enum class RunTrigger { AUTOMATIC, MANUAL }

/** Situação da execução: `RUNNING` → `COMPLETED`, ou `FAILED` → (retomada) `RUNNING`. */
enum class RunStatus { RUNNING, FAILED, COMPLETED }

/** Situação de uma etapa; `COMPLETED` e `SKIPPED` são finais. */
enum class StepStatus { PENDING, RUNNING, COMPLETED, FAILED, SKIPPED }

/** Uma etapa do registro (ou da execução): nome estável, como um código, e posição na ordem. */
data class StepSlot(
    val name: String,
    val position: Int,
)

/** A etapa iniciada por [EndOfDayRun.beginNext], com a tentativa em curso. */
data class StepStart(
    val name: String,
    val attempts: Int,
)

/** O resultado de [EndOfDayRun.beginNext]: a etapa iniciada (nula se a execução terminou) e as puladas agora. */
data class BeginResult(
    val started: StepStart?,
    val skipped: List<String>,
)

/**
 * A execução do fechamento de uma data operacional (SPEC RF-FEC, RN-21): uma só por data (`run_business_date_uk`), com
 * as etapas em ordem de posição. A etapa é parte do agregado: **toda transição altera [updatedAt]**, para a [version]
 * subir a cada mudança de etapa (o Hibernate não sobe a versão do pai quando só a linha da etapa muda). O instante é
 * truncado para microssegundos (a precisão do `timestamptz`) e, se não andou desde a transição anterior, [updatedAt]
 * avança 1 µs. A data, o gatilho, quem disparou e o início nunca mudam depois de gravados (`updatable = false`).
 *
 * A reconciliação ([reconcile]) acerta as etapas com o registro do deploy corrente: etapa que saiu do registro e não
 * foi concluída vira `SKIPPED` (`UNREGISTERED`); etapa nova entra `PENDING` se vier depois da última concluída (não se
 * volta atrás) e se a posição dela estiver livre na execução.
 */
@Suppress("LongParameterList") // construtor da entidade JPA: uma coluna imutável por parâmetro
@Entity
@Table(name = "run", schema = "endofday")
class EndOfDayRun private constructor(
    @Id
    val id: EndOfDayRunId,
    @Column(name = "business_date", nullable = false, updatable = false)
    val businessDate: LocalDate,
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger", nullable = false, updatable = false, length = ENUM_LENGTH)
    val trigger: RunTrigger,
    @Column(name = "triggered_by", updatable = false)
    val triggeredBy: UserId?,
    @Column(name = "started_at", nullable = false, updatable = false)
    val startedAt: Instant,
) {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = ENUM_LENGTH)
    var status: RunStatus = RunStatus.RUNNING
        protected set

    @Column(name = "finished_at")
    var finishedAt: Instant? = null
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = startedAt
        protected set

    @Version
    @Column(name = "version", nullable = false)
    @Suppress("VarCouldBeVal") // o Hibernate grava a versão por reflexão
    private var versionValue: Long? = null

    /**
     * Versão otimista da execução (0 antes de gravar). O campo gravado é nulo antes do primeiro `save`, para o Spring
     * Data persistir a execução nova (`persist`) em vez de fundi-la (`merge`).
     */
    val version: Long get() = versionValue ?: 0

    @OneToMany(cascade = [CascadeType.ALL])
    @JoinColumn(name = "run_id", insertable = false, updatable = false)
    @OrderBy("position")
    private val stepList: MutableList<EndOfDayRunStep> = mutableListOf()

    /** As etapas, em ordem de posição. */
    val steps: List<EndOfDayRunStep> get() = stepList.sortedBy { it.position }

    /**
     * Reconcilia com o registro ([reconcile]) e inicia a primeira etapa, em ordem de posição, que não está `COMPLETED`
     * nem `SKIPPED`, com mais uma tentativa. Sem etapa restante, a execução vira `COMPLETED`. A escolha vem depois da
     * reconciliação: uma etapa nova anterior à que falhou roda antes dela, e uma etapa pulada nunca é iniciada.
     *
     * **Só chame sob a trava da execução** (`EndOfDayRunRepository.lockForUpdate`, na mesma transação): uma etapa
     * `RUNNING` é reiniciada com mais uma tentativa (é a retomada depois de uma queda no meio da etapa), então duas
     * chamadas concorrentes sem a trava rodariam a mesma etapa duas vezes.
     */
    fun beginNext(
        registered: List<StepSlot>,
        now: Instant,
    ): BeginResult {
        val skipped = reconcile(registered, now)
        val next = steps.firstOrNull { !it.isDone }
        if (next == null) {
            finish(RunStatus.COMPLETED, now)
            return BeginResult(null, skipped)
        }
        next.begin(now)
        touch(now)
        return BeginResult(StepStart(next.name, next.attempts), skipped)
    }

    /** Conclui a etapa em curso com o [result]; sem etapa restante, a execução vira `COMPLETED`. */
    fun completeStep(
        name: String,
        result: String,
        now: Instant,
    ) {
        checkRunning()
        step(name).complete(result, now)
        if (stepList.all { it.isDone }) finish(RunStatus.COMPLETED, now) else touch(now)
    }

    /** A etapa em curso falhou pelo [reason] (um código, nunca a mensagem): etapa e execução `FAILED`. */
    fun failStep(
        name: String,
        reason: String,
        now: Instant,
    ) {
        checkRunning()
        step(name).fail(reason, now)
        finish(RunStatus.FAILED, now)
    }

    /** Retoma uma execução `FAILED`: volta a `RUNNING` e a etapa que falhou volta a `PENDING`; as concluídas ficam. */
    fun resume(now: Instant) {
        check(status == RunStatus.FAILED) { "Só uma execução FAILED pode ser retomada (estado: $status)." }
        stepList.filter { it.status == StepStatus.FAILED }.forEach { it.reopen() }
        status = RunStatus.RUNNING
        finishedAt = null
        touch(now)
    }

    /**
     * Acerta as etapas com o [registered] (só em `RUNNING`) e devolve os nomes das que foram puladas, para o chamador
     * abrir uma pendência por nome. Sem mudança, não altera nada.
     */
    fun reconcile(
        registered: List<StepSlot>,
        now: Instant,
    ): List<String> {
        checkRunning()
        val registeredNames = registered.map { it.name }.toSet()
        val skipped = stepList.filter { !it.isDone && it.name !in registeredNames }
        skipped.forEach { it.skip(UNREGISTERED, now) }
        val known = stepList.map { it.name }.toSet()
        val takenPositions = stepList.map { it.position }.toSet()
        val lastCompleted = stepList.filter { it.status == StepStatus.COMPLETED }.maxOfOrNull { it.position }
        val added =
            registered.filter {
                it.name !in known && it.position !in takenPositions &&
                    (lastCompleted == null || it.position > lastCompleted)
            }
        added.forEach { stepList += EndOfDayRunStep.pending(id, it) }
        if (skipped.isNotEmpty() || added.isNotEmpty()) touch(now)
        return skipped.map { it.name }
    }

    private fun step(name: String): EndOfDayRunStep =
        requireNotNull(stepList.firstOrNull { it.name == name }) { "A execução não tem a etapa $name." }

    private fun checkRunning() = check(status == RunStatus.RUNNING) { "A execução não está RUNNING (estado: $status)." }

    private fun finish(
        to: RunStatus,
        now: Instant,
    ) {
        status = to
        finishedAt = now
        touch(now)
    }

    private fun touch(now: Instant) {
        val at = now.truncatedTo(ChronoUnit.MICROS)
        updatedAt = if (at.isAfter(updatedAt)) at else updatedAt.plus(1, ChronoUnit.MICROS)
    }

    override fun toString(): String = "EndOfDayRun(id=$id, businessDate=$businessDate, status=$status)"

    companion object {
        /** O resultado de uma etapa pulada por ter saído do registro. */
        const val UNREGISTERED = "UNREGISTERED"

        private const val ENUM_LENGTH = 20

        /** Abre a execução da [businessDate] em `RUNNING`, com as [steps] `PENDING`, sem nome nem posição repetidos. */
        fun start(
            businessDate: LocalDate,
            trigger: RunTrigger,
            triggeredBy: UserId?,
            steps: List<StepSlot>,
            now: Instant,
        ): EndOfDayRun {
            require(steps.isNotEmpty()) { "A execução precisa de ao menos uma etapa." }
            require(steps.map { it.name }.toSet().size == steps.size) { "Nome de etapa repetido." }
            require(steps.map { it.position }.toSet().size == steps.size) { "Posição de etapa repetida." }
            val run = EndOfDayRun(EndOfDayRunId.new(), businessDate, trigger, triggeredBy, now)
            steps.sortedBy { it.position }.forEach { run.stepList += EndOfDayRunStep.pending(run.id, it) }
            return run
        }
    }
}

/** Chave da etapa: a execução e o nome (`run_step_pk`). */
@Embeddable
data class EndOfDayRunStepKey(
    @Column(name = "run_id", nullable = false)
    val runId: UUID,
    @Column(name = "name", nullable = false, length = EndOfDayRunStep.NAME_LENGTH)
    val name: String,
) : Serializable {
    private companion object {
        private const val serialVersionUID = 1L
    }
}

/** Uma etapa da execução (tabela `endofday.run_step`); só muda pelas transições de [EndOfDayRun]. */
@Entity
@Table(name = "run_step", schema = "endofday")
class EndOfDayRunStep private constructor(
    @EmbeddedId
    private val key: EndOfDayRunStepKey,
    @Column(nullable = false)
    val position: Int,
) {
    val name: String get() = key.name

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = STATUS_LENGTH)
    var status: StepStatus = StepStatus.PENDING
        protected set

    @Column(name = "started_at")
    var startedAt: Instant? = null
        protected set

    @Column(name = "finished_at")
    var finishedAt: Instant? = null
        protected set

    @Column(length = RESULT_LENGTH)
    var result: String? = null
        protected set

    @Column(nullable = false)
    var attempts: Int = 0
        protected set

    internal val isDone: Boolean get() = status == StepStatus.COMPLETED || status == StepStatus.SKIPPED

    internal fun begin(now: Instant) {
        status = StepStatus.RUNNING
        attempts += 1
        startedAt = now
        finishedAt = null
        result = null
    }

    internal fun complete(
        outcome: String,
        now: Instant,
    ) {
        checkRunning()
        end(StepStatus.COMPLETED, outcome, now)
    }

    internal fun fail(
        reason: String,
        now: Instant,
    ) {
        checkRunning()
        end(StepStatus.FAILED, reason, now)
    }

    internal fun skip(
        reason: String,
        now: Instant,
    ) = end(StepStatus.SKIPPED, reason, now)

    internal fun reopen() {
        status = StepStatus.PENDING
        startedAt = null
        finishedAt = null
        result = null
    }

    private fun checkRunning() = check(status == StepStatus.RUNNING) { "A etapa $name não está RUNNING ($status)." }

    private fun end(
        to: StepStatus,
        outcome: String,
        now: Instant,
    ) {
        require(outcome.length <= RESULT_LENGTH) { "O resultado da etapa passa de $RESULT_LENGTH caracteres." }
        status = to
        result = outcome
        finishedAt = now
    }

    override fun toString(): String = "EndOfDayRunStep(name=$name, position=$position, status=$status)"

    companion object {
        const val NAME_LENGTH = 60
        private const val STATUS_LENGTH = 20
        const val RESULT_LENGTH = 200

        internal fun pending(
            runId: EndOfDayRunId,
            slot: StepSlot,
        ) = EndOfDayRunStep(EndOfDayRunStepKey(runId.value, slot.name), slot.position)
    }
}

/**
 * Uma pendência da execução (tabela `endofday.run_issue`): o [kind] é um código `UPPER_SNAKE`, a [reference] é opaca e
 * a [message] nunca leva dado pessoal de hóspede (RN-13).
 */
@Suppress("LongParameterList") // construtor da entidade JPA: uma coluna imutável por parâmetro
@Entity
@Table(name = "run_issue", schema = "endofday")
class EndOfDayIssue private constructor(
    @Id
    val id: UUID,
    @Column(name = "run_id", nullable = false)
    val runId: EndOfDayRunId,
    @Column(name = "step_name", length = EndOfDayRunStep.NAME_LENGTH)
    val stepName: String?,
    @Column(nullable = false, length = KIND_LENGTH)
    val kind: String,
    @Column(length = REFERENCE_LENGTH)
    val reference: String?,
    @Column(nullable = false, length = MESSAGE_LENGTH)
    val message: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    @Column(nullable = false)
    var resolved: Boolean = false
        protected set

    override fun toString(): String = "EndOfDayIssue(id=$id, runId=$runId, kind=$kind)"

    companion object {
        private const val KIND_LENGTH = 60
        private const val REFERENCE_LENGTH = 120
        private const val MESSAGE_LENGTH = 500
        private val KIND = Regex("[A-Z][A-Z0-9_]{0,${KIND_LENGTH - 1}}")

        /** Abre a pendência, conferindo o formato do [kind] e os tamanhos (inclusive o do nome da etapa). */
        @Suppress("LongParameterList")
        fun open(
            runId: EndOfDayRunId,
            stepName: String?,
            kind: String,
            reference: String?,
            message: String,
            now: Instant,
        ): EndOfDayIssue {
            require(KIND.matches(kind)) { "O tipo da pendência deve seguir ${KIND.pattern}." }
            require(stepName == null || stepName.length <= EndOfDayRunStep.NAME_LENGTH) {
                "Nome de etapa longo demais."
            }
            require(reference == null || reference.length <= REFERENCE_LENGTH) { "Referência longa demais." }
            require(message.length <= MESSAGE_LENGTH) { "Mensagem longa demais." }
            return EndOfDayIssue(UuidV7.generate(), runId, stepName, kind, reference, message, now)
        }
    }
}
