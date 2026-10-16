package com.zeny.hotel.checklists.application

import com.zeny.hotel.checklists.CheckAnswer
import com.zeny.hotel.checklists.ChecklistAnswerInput
import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.checklists.ChecklistExecutionStatus
import com.zeny.hotel.checklists.ChecklistSubject
import com.zeny.hotel.checklists.ChecklistVersionId
import com.zeny.hotel.checklists.ChecklistVersionView
import com.zeny.hotel.checklists.ChecklistsErrorCodes
import com.zeny.hotel.checklists.ChecklistsOffline
import com.zeny.hotel.checklists.ChecklistsPermissions
import com.zeny.hotel.checklists.domain.ChecklistExecution
import com.zeny.hotel.files.FileId
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.OfflineCommand
import com.zeny.hotel.shared.OfflineCommandHandler
import com.zeny.hotel.shared.OfflineCommandOutcome
import com.zeny.hotel.shared.OfflineCommandWarning
import com.zeny.hotel.shared.UserId
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * `StartChecklistExecution` (`checklists.start-execution`): inicia, offline, uma execução da versão [versionId]
 * que o app tem (RF-CHK-06: a versão usada fica guardada, mesmo que já substituída, com alerta). Sem
 * [executionId], uma execução avulsa nova, com o `commandId` como id, para o [subjectType]/[subjectId] opcional;
 * com ele, assume a pendente da rotina (H14). O início real é o `occurredAt` do comando, corrigido pela
 * defasagem (RN-22).
 */
data class StartChecklistExecutionPayload(
    @field:NotNull
    val versionId: UUID?,
    val executionId: UUID? = null,
    @field:Size(max = ChecklistSubject.MAX_TYPE_LENGTH)
    val subjectType: String? = null,
    val subjectId: UUID? = null,
)

/**
 * `CompleteChecklistExecution` (`checklists.complete-execution`): conclui, offline, a execução [executionId] com
 * todas as [answers] (substituem as gravadas). O fim real é o `occurredAt` do comando e o horário de cada
 * resposta, o `answeredAt` do dispositivo, os dois corrigidos pela defasagem (RN-22). A foto vai pelo
 * `clientFileId` enviado antes (`POST /api/v1/files`); se ainda não chegou, a resposta fica sem ela e o comando é
 * aplicado com alerta (H10).
 */
data class CompleteChecklistExecutionPayload(
    @field:NotNull
    val executionId: UUID?,
    @field:Valid
    @field:Size(max = MAX_ANSWERS)
    val answers: List<OfflineAnswerPayload> = emptyList(),
) {
    companion object {
        const val MAX_ANSWERS = 300
    }
}

/** Uma resposta num comando offline: a chave do item, só o campo do tipo dele, a observação e o horário. */
data class OfflineAnswerPayload(
    @field:NotNull
    @field:Size(max = 40)
    val itemKey: String?,
    val check: CheckAnswer? = null,
    val quantity: BigDecimal? = null,
    val number: BigDecimal? = null,
    @field:Size(max = 2000)
    val text: String? = null,
    @field:Size(max = 40)
    val choice: String? = null,
    @field:Size(max = 50)
    val choices: List<String>? = null,
    val photoFileId: UUID? = null,
    @field:Size(max = 500)
    val note: String? = null,
    val answeredAt: Instant? = null,
) {
    fun toInput(answeredAt: Instant) =
        ChecklistAnswerInput(
            checkNotNull(itemKey),
            check,
            quantity,
            number,
            text,
            choice,
            choices,
            photoFileId?.let(::FileId),
            note,
            answeredAt,
        )
}

/** O handler de `checklists.start-execution` ([StartChecklistExecutionPayload]). */
@Component
class StartChecklistExecutionHandler(
    private val operations: ExecutionOperations,
    private val executions: ChecklistExecutionStore,
    private val authorization: Authorization,
    private val actor: CurrentActor,
) : OfflineCommandHandler<StartChecklistExecutionPayload> {
    override val type = ChecklistsOffline.START
    override val payloadType = StartChecklistExecutionPayload::class.java

    @Audited(ChecklistsAudit.START_EXECUTION_OFFLINE)
    @Transactional
    override fun handle(command: OfflineCommand<StartChecklistExecutionPayload>): OfflineCommandOutcome {
        authorization.require(ChecklistsPermissions.EXECUTIONS_EXECUTE)
        val payload = command.payload
        val current = actor.current()
        val version = operations.version(ChecklistVersionId(checkNotNull(payload.versionId)))
        val warnings = mutableListOf<OfflineCommandWarning>()
        if (operations.isOutdated(version)) {
            warnings +=
                OfflineCommandWarning(
                    ChecklistsErrorCodes.CHECKLIST_VERSION_OUTDATED,
                    "A execução usou a versão ${version.number} do modelo, já substituída; ela ficou guardada.",
                )
        }
        val executionId = payload.executionId
        if (executionId == null) {
            operations.startNew(
                ChecklistExecutionId(command.commandId),
                version,
                subjectOf(payload),
                current.userId,
                command.occurredAt,
                current.deviceSessionId,
                command.commandId,
            )
            return OfflineCommandOutcome(warnings)
        }
        warnings += startRoutine(command, ChecklistExecutionId(executionId), version)
        return OfflineCommandOutcome(warnings)
    }

    /**
     * Assume a pendente de uma rotina; já iniciada por este usuário, nada a fazer; assumida por outra pessoa, a
     * execução deste usuário fica registrada à parte, com alerta (RN-22). Devolve os alertas.
     */
    private fun startRoutine(
        command: OfflineCommand<StartChecklistExecutionPayload>,
        executionId: ChecklistExecutionId,
        version: ChecklistVersionView,
    ): List<OfflineCommandWarning> {
        val current = actor.current()
        val execution = operations.lock(executionId)
        return when {
            execution.status == ChecklistExecutionStatus.PENDING -> {
                authorization.require(ChecklistsPermissions.EXECUTIONS_EXECUTE, execution.candidatesResource)
                operations.assume(
                    execution,
                    version,
                    current.userId,
                    command.occurredAt,
                    current.deviceSessionId,
                    command.commandId,
                )
                emptyList()
            }

            // Já iniciada por este usuário (online, ou por outro comando): nada a fazer.
            execution.executorId == current.userId -> {
                emptyList()
            }

            execution.routineId == null -> {
                throw ConflictException(
                    ChecklistsErrorCodes.CHECKLIST_EXECUTION_ALREADY_STARTED,
                    "Esta execução já foi iniciada por outra pessoa.",
                )
            }

            else -> {
                recordApart(command, execution, version)
            }
        }
    }

    /** RN-22: a rotina já foi assumida por outra pessoa; a execução deste usuário fica registrada à parte. */
    private fun recordApart(
        command: OfflineCommand<StartChecklistExecutionPayload>,
        execution: ChecklistExecution,
        version: ChecklistVersionView,
    ): List<OfflineCommandWarning> {
        val current = actor.current()
        authorization.require(ChecklistsPermissions.EXECUTIONS_EXECUTE, execution.candidatesResource)
        if (executions.findDuplicateForUpdate(execution.id, current.userId) == null) {
            operations.duplicate(
                execution,
                ChecklistExecutionId(command.commandId),
                version,
                current.userId,
                command.occurredAt,
                current.deviceSessionId,
                command.commandId,
            )
        }
        return listOf(
            OfflineCommandWarning(
                ChecklistsErrorCodes.CHECKLIST_ROUTINE_ALREADY_TAKEN,
                "A rotina já tinha sido assumida por outra pessoa; esta execução foi registrada à parte.",
            ),
        )
    }

    private fun subjectOf(payload: StartChecklistExecutionPayload): ChecklistSubject? {
        val type = payload.subjectType?.trim()
        val id = payload.subjectId
        if (type == null && id == null) return null
        if (type == null || id == null || !ChecklistSubject.isValidType(type)) {
            throw InvalidInputException(
                ChecklistsErrorCodes.CHECKLIST_EXECUTION_INVALID,
                "Informe o tipo e o id do sujeito juntos, com o tipo em minúsculas (ex.: rooms.room).",
            )
        }
        return ChecklistSubject(type, id)
    }
}

/** O handler de `checklists.complete-execution` ([CompleteChecklistExecutionPayload]). */
@Component
class CompleteChecklistExecutionHandler(
    private val operations: ExecutionOperations,
    private val executions: ChecklistExecutionStore,
    private val authorization: Authorization,
    private val actor: CurrentActor,
) : OfflineCommandHandler<CompleteChecklistExecutionPayload> {
    override val type = ChecklistsOffline.COMPLETE
    override val payloadType = CompleteChecklistExecutionPayload::class.java

    @Audited(ChecklistsAudit.COMPLETE_EXECUTION_OFFLINE)
    @Transactional
    override fun handle(command: OfflineCommand<CompleteChecklistExecutionPayload>): OfflineCommandOutcome {
        authorization.require(ChecklistsPermissions.EXECUTIONS_EXECUTE)
        val current = actor.current()
        val execution = target(ChecklistExecutionId(checkNotNull(command.payload.executionId)), current.userId)
        authorization.require(ChecklistsPermissions.EXECUTIONS_EXECUTE, execution.executorResource)
        execution.requireInProgressBy(current.userId)
        // A defasagem do relógio do dispositivo, a mesma que o protocolo aplicou ao occurredAt (RN-22).
        val skew = Duration.between(command.deviceOccurredAt, command.occurredAt)
        val inputs =
            command.payload.answers.map { answer ->
                val corrected = answer.answeredAt?.plus(skew)?.coerceAtMost(command.occurredAt)
                answer.toInput(corrected ?: command.occurredAt)
            }
        val answers = operations.normalize(execution, inputs, command.occurredAt)
        val photos = operations.replaceAnswers(execution, current.userId, answers, PhotoPolicy.TOLERATE)
        operations.complete(execution, current.userId, command.occurredAt, current.deviceSessionId, command.commandId)
        val warnings =
            photos.missing.map {
                OfflineCommandWarning(
                    ChecklistsErrorCodes.CHECKLIST_PHOTO_MISSING,
                    "A foto do item \"$it\" ainda não chegou ao servidor; a resposta ficou sem ela.",
                )
            } +
                photos.rejected.map {
                    OfflineCommandWarning(
                        ChecklistsErrorCodes.CHECKLIST_PHOTO_INVALID,
                        "A foto do item \"$it\" não serve (outra finalidade, outro autor ou outro registro); a " +
                            "resposta ficou sem ela.",
                    )
                }
        return OfflineCommandOutcome(warnings)
    }

    /**
     * A execução a concluir: a citada ou, se ela é de uma rotina que outra pessoa assumiu, a registrada à parte por
     * este usuário (RN-22: o app só conhece o id da pendente).
     */
    private fun target(
        id: ChecklistExecutionId,
        user: UserId,
    ): ChecklistExecution {
        val execution = operations.lock(id)
        if (execution.executorId == user || execution.routineId == null) return execution
        return executions.findDuplicateForUpdate(execution.id, user) ?: execution
    }
}
