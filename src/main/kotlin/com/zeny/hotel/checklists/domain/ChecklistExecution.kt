package com.zeny.hotel.checklists.domain

import com.zeny.hotel.checklists.ChecklistContent
import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.checklists.ChecklistExecutionStatus
import com.zeny.hotel.checklists.ChecklistExecutionStatus.COMPLETED
import com.zeny.hotel.checklists.ChecklistExecutionStatus.IN_PROGRESS
import com.zeny.hotel.checklists.ChecklistExecutionStatus.PENDING
import com.zeny.hotel.checklists.ChecklistExecutionView
import com.zeny.hotel.checklists.ChecklistRoutineId
import com.zeny.hotel.checklists.ChecklistSubject
import com.zeny.hotel.checklists.ChecklistTemplateId
import com.zeny.hotel.checklists.ChecklistTriggeredAction
import com.zeny.hotel.checklists.ChecklistVersionId
import com.zeny.hotel.checklists.ChecklistVersionView
import com.zeny.hotel.checklists.ChecklistsErrorCodes
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.OwnedResource
import com.zeny.hotel.shared.PermissionDeniedException
import com.zeny.hotel.shared.UserId
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Execução de um checklist (RF-CHK-05 a 08, SPEC §8.2.11): a versão usada (RF-CHK-06), o sujeito opaco, o
 * executor e os horários **reais** (offline, os do dispositivo corrigidos pela defasagem, RN-22), as respostas
 * por chave de item e, numa rotina (H14), a data operacional, o horário previsto e os candidatos escalados.
 *
 * Ciclo: pendente (só de rotina) → em andamento (com executor e versão) → concluída. Só o executor responde e
 * conclui. Concluir confere os obrigatórios visíveis (RF-CHK-03), descarta as respostas de itens ocultos pela
 * condição e devolve as ações disparadas (RF-CHK-05), uma vez: concluída, a execução não muda mais.
 *
 * [version] é a versão do registro (o `ETag`): cada operação que muda algo a avança uma vez.
 */
@Suppress("LongParameterList", "TooManyFunctions") // reconstituída da persistência com todos os campos
class ChecklistExecution private constructor(
    val id: ChecklistExecutionId,
    val templateId: ChecklistTemplateId,
    versionId: ChecklistVersionId?,
    versionNumber: Int?,
    val context: ChecklistContext,
    val subject: ChecklistSubject?,
    val routineId: ChecklistRoutineId?,
    val businessDate: LocalDate?,
    val dueAt: Instant?,
    val candidates: Set<UserId>,
    val duplicateOf: ChecklistExecutionId?,
    status: ChecklistExecutionStatus,
    executorId: UserId?,
    startedAt: Instant?,
    completedAt: Instant?,
    deviceSessionId: DeviceSessionId?,
    startCommandId: UUID?,
    completeCommandId: UUID?,
    answers: List<ChecklistAnswer>,
    val createdAt: Instant,
    updatedAt: Instant,
    version: Long,
) : OwnedResource {
    var versionId: ChecklistVersionId? = versionId
        private set
    var versionNumber: Int? = versionNumber
        private set
    var status: ChecklistExecutionStatus = status
        private set
    var executorId: UserId? = executorId
        private set
    var startedAt: Instant? = startedAt
        private set
    var completedAt: Instant? = completedAt
        private set
    var deviceSessionId: DeviceSessionId? = deviceSessionId
        private set
    var startCommandId: UUID? = startCommandId
        private set
    var completeCommandId: UUID? = completeCommandId
        private set
    var answers: List<ChecklistAnswer> = answers
        private set
    var updatedAt: Instant = updatedAt
        private set
    var version: Long = version
        private set

    /** Donos para o escopo `OWN` (H14): os candidatos da rotina e o executor. */
    override val ownerIds: Set<UserId> get() = candidates + listOfNotNull(executorId)

    /** Quem pode assumir a pendente (`OWN`): os candidatos. */
    val candidatesResource: OwnedResource get() = OwnedResource.of(candidates)

    /** Só o executor responde e conclui (`OWN`). */
    val executorResource: OwnedResource get() = OwnedResource.of(executorId)

    /** Se foi iniciada ou concluída por comando offline. */
    val offline: Boolean get() = startCommandId != null || completeCommandId != null

    /** As fotos das respostas gravadas. */
    val photoIds get() = answers.mapNotNull { it.photoFileId }.toSet()

    /**
     * Assume a pendente de uma rotina (H14: o primeiro candidato que inicia) com a [version] do modelo da rotina.
     * Já iniciada: `409 CHECKLIST_EXECUTION_ALREADY_STARTED`.
     */
    fun assume(
        version: ChecklistVersionView,
        executor: UserId,
        startedAt: Instant,
        session: DeviceSessionId?,
        commandId: UUID?,
        now: Instant,
    ) {
        if (status != PENDING) {
            throw ConflictException(
                ChecklistsErrorCodes.CHECKLIST_EXECUTION_ALREADY_STARTED,
                "Esta execução já foi iniciada.",
            )
        }
        requireVersionOfTemplate(version)
        versionId = version.id
        versionNumber = version.number
        status = IN_PROGRESS
        executorId = executor
        this.startedAt = micros(startedAt)
        deviceSessionId = session
        startCommandId = commandId
        touch(now)
    }

    /**
     * A execução offline desta rotina já assumida por outra pessoa (RN-22): registrada à parte, ligada a esta
     * ([duplicateOf]), com o [id] do comando, a mesma rotina, data e candidatos.
     */
    fun duplicateFor(
        id: ChecklistExecutionId,
        version: ChecklistVersionView,
        executor: UserId,
        startedAt: Instant,
        session: DeviceSessionId?,
        commandId: UUID,
        now: Instant,
    ): ChecklistExecution {
        requireVersionOfTemplate(version)
        return ChecklistExecution(
            id,
            templateId,
            version.id,
            version.number,
            context,
            subject,
            routineId,
            businessDate,
            dueAt,
            candidates,
            this.id,
            IN_PROGRESS,
            executor,
            micros(startedAt),
            null,
            session,
            commandId,
            null,
            emptyList(),
            micros(now),
            micros(now),
            0,
        )
    }

    /** Troca as respostas (em andamento, só o executor; a validação contra a versão já foi feita). */
    fun replaceAnswers(
        actor: UserId,
        newAnswers: List<ChecklistAnswer>,
        now: Instant,
    ): Boolean {
        requireInProgressBy(actor)
        // Uma resposta que não mudou mantém o horário em que foi dada.
        val previous = answers.associateBy { it.itemKey }
        val merged =
            newAnswers.map { answer ->
                val old = previous[answer.itemKey]
                if (old != null && old.copy(answeredAt = answer.answeredAt) == answer) old else answer
            }
        if (merged.sortedBy { it.itemKey } == answers.sortedBy { it.itemKey }) return false
        answers = merged
        touch(now)
        return true
    }

    /**
     * Conclui com as respostas gravadas (as de itens ocultos pela condição saem): obrigatório visível sem
     * resposta, `422 CHECKLIST_REQUIRED_ITEMS_MISSING` com as chaves. O fim real é [completedAt], nunca antes do
     * início. Devolve as ações disparadas (RF-CHK-05).
     */
    fun complete(
        actor: UserId,
        content: ChecklistContent,
        completedAt: Instant,
        session: DeviceSessionId?,
        commandId: UUID?,
        now: Instant,
    ): List<ChecklistTriggeredAction> {
        requireInProgressBy(actor)
        val byKey = answers.associateBy { it.itemKey }
        val visible = ChecklistAnswerRules.visibleKeys(content, byKey)
        val missing = ChecklistAnswerRules.missingRequired(content, byKey)
        if (missing.isNotEmpty()) throw RequiredItemsMissingException(missing)
        val start = checkNotNull(startedAt)
        val end = maxOf(micros(completedAt), start)
        answers =
            content.items
                .mapNotNull { item -> byKey[item.key]?.takeIf { item.key in visible } }
                .map { it.copy(answeredAt = it.answeredAt.coerceIn(start, end)) }
        status = COMPLETED
        this.completedAt = end
        if (session != null) deviceSessionId = session
        completeCommandId = commandId
        touch(now)
        return ChecklistAnswerRules.triggeredActions(content, answers.associateBy { it.itemKey })
    }

    /** O executor da execução em andamento: senão `409` (pendente ou concluída). */
    fun inProgressExecutor(): UserId {
        requireStatusInProgress()
        return checkNotNull(executorId)
    }

    /** Em andamento e do [actor]: senão `409` (pendente ou concluída) ou `403 CHECKLIST_EXECUTOR_REQUIRED`. */
    fun requireInProgressBy(actor: UserId) {
        requireStatusInProgress()
        if (executorId != actor) {
            throw PermissionDeniedException(
                ChecklistsErrorCodes.CHECKLIST_EXECUTOR_REQUIRED,
                "Só quem iniciou a execução responde e a conclui.",
            )
        }
    }

    private fun requireStatusInProgress() {
        when (status) {
            PENDING -> {
                throw ConflictException(
                    ChecklistsErrorCodes.CHECKLIST_EXECUTION_NOT_STARTED,
                    "Inicie a execução antes de responder ou concluir.",
                )
            }

            COMPLETED -> {
                throw ConflictException(
                    ChecklistsErrorCodes.CHECKLIST_EXECUTION_ALREADY_COMPLETED,
                    "Esta execução já foi concluída.",
                )
            }

            IN_PROGRESS -> {
                Unit
            }
        }
    }

    private fun requireVersionOfTemplate(version: ChecklistVersionView) {
        if (version.templateId != templateId) {
            throw BusinessRuleException(
                ChecklistsErrorCodes.CHECKLIST_VERSION_MISMATCH,
                "A versão informada não é do modelo desta execução.",
            )
        }
    }

    private fun touch(now: Instant) {
        updatedAt = micros(now)
        version += 1
    }

    fun view() =
        ChecklistExecutionView(
            id,
            status,
            templateId,
            versionId,
            versionNumber,
            context,
            subject,
            routineId,
            businessDate,
            dueAt,
            candidates,
            duplicateOf,
            executorId,
            startedAt,
            completedAt,
            offline,
            answers.map(ChecklistAnswer::view),
            createdAt,
            updatedAt,
            version,
        )

    companion object {
        /** Uma execução avulsa (de tarefa ou do próprio executor), já iniciada com a [version] usada. */
        @Suppress("LongParameterList")
        fun start(
            id: ChecklistExecutionId,
            version: ChecklistVersionView,
            subject: ChecklistSubject?,
            executor: UserId,
            startedAt: Instant,
            session: DeviceSessionId?,
            commandId: UUID?,
            now: Instant,
        ) = ChecklistExecution(
            id,
            version.templateId,
            version.id,
            version.number,
            version.context,
            subject,
            null,
            null,
            null,
            emptySet(),
            null,
            IN_PROGRESS,
            executor,
            micros(startedAt),
            null,
            session,
            commandId,
            null,
            emptyList(),
            micros(now),
            micros(now),
            0,
        )

        /** A pendente de uma rotina numa data (H14), com os candidatos escalados. */
        @Suppress("LongParameterList")
        fun scheduled(
            routineId: ChecklistRoutineId,
            templateId: ChecklistTemplateId,
            context: ChecklistContext,
            location: ChecklistSubject?,
            businessDate: LocalDate,
            dueAt: Instant,
            candidates: Set<UserId>,
            now: Instant,
        ) = ChecklistExecution(
            ChecklistExecutionId.new(),
            templateId,
            null,
            null,
            context,
            location,
            routineId,
            businessDate,
            dueAt,
            candidates,
            null,
            PENDING,
            null,
            null,
            null,
            null,
            null,
            null,
            emptyList(),
            micros(now),
            micros(now),
            0,
        )

        /** Reconstitui uma execução gravada. */
        fun restore(
            id: ChecklistExecutionId,
            templateId: ChecklistTemplateId,
            versionId: ChecklistVersionId?,
            versionNumber: Int?,
            context: ChecklistContext,
            subject: ChecklistSubject?,
            routineId: ChecklistRoutineId?,
            businessDate: LocalDate?,
            dueAt: Instant?,
            candidates: Set<UserId>,
            duplicateOf: ChecklistExecutionId?,
            status: ChecklistExecutionStatus,
            executorId: UserId?,
            startedAt: Instant?,
            completedAt: Instant?,
            deviceSessionId: DeviceSessionId?,
            startCommandId: UUID?,
            completeCommandId: UUID?,
            answers: List<ChecklistAnswer>,
            createdAt: Instant,
            updatedAt: Instant,
            version: Long,
        ) = ChecklistExecution(
            id,
            templateId,
            versionId,
            versionNumber,
            context,
            subject,
            routineId,
            businessDate,
            dueAt,
            candidates,
            duplicateOf,
            status,
            executorId,
            startedAt,
            completedAt,
            deviceSessionId,
            startCommandId,
            completeCommandId,
            answers,
            createdAt,
            updatedAt,
            version,
        )
    }
}

/** O instante na precisão do banco (microssegundos): o que se grava é o que se relê. */
internal fun micros(instant: Instant): Instant = instant.truncatedTo(ChronoUnit.MICROS)

/** `422 CHECKLIST_REQUIRED_ITEMS_MISSING`, com as chaves em `missingItems` no problema. */
class RequiredItemsMissingException(
    val missingItems: List<String>,
) : BusinessRuleException(
        ChecklistsErrorCodes.CHECKLIST_REQUIRED_ITEMS_MISSING,
        "Responda os itens obrigatórios antes de concluir: ${missingItems.joinToString()}.",
    ) {
    override val problemProperties: Map<String, Any> get() = mapOf("missingItems" to missingItems)
}
