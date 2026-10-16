package com.zeny.hotel.checklists.application

import com.zeny.hotel.checklists.ChecklistAnswerInput
import com.zeny.hotel.checklists.ChecklistCompleted
import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.checklists.ChecklistExecutionStatus
import com.zeny.hotel.checklists.ChecklistSubject
import com.zeny.hotel.checklists.ChecklistTemplateId
import com.zeny.hotel.checklists.ChecklistVersionId
import com.zeny.hotel.checklists.ChecklistVersionView
import com.zeny.hotel.checklists.ChecklistsErrorCodes
import com.zeny.hotel.checklists.ChecklistsFilePurposes
import com.zeny.hotel.checklists.domain.ChecklistAnswer
import com.zeny.hotel.checklists.domain.ChecklistAnswerRules
import com.zeny.hotel.checklists.domain.ChecklistExecution
import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.FilesApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.UserId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * O que fazer com uma foto citada que não serve à resposta: não existe (ainda não chegou ou foi expurgada) ou é
 * inválida (outra finalidade, outro autor ou outro dono).
 */
enum class PhotoPolicy {
    /** Online: recusar (`422 CHECKLIST_PHOTO_NOT_FOUND` ou `CHECKLIST_PHOTO_INVALID`); o executor corrige. */
    REFUSE,

    /**
     * Offline e pela API pública: a resposta fica sem a foto (`photoMissing`, que conta como respondida no
     * obrigatório), nada é vinculado e o comando é aplicado com alerta (H10, RN-22: o checklist feito não se perde;
     * decisão do usuário em 2026-10-16 para a foto inválida).
     */
    TOLERATE,
}

/** As chaves dos itens cuja foto ficou de fora: a que não chegou ([missing]) e a inválida ([rejected]). */
data class PhotoIssues(
    val missing: List<String> = emptyList(),
    val rejected: List<String> = emptyList(),
)

/** O resultado da conferência de uma foto nova. */
private enum class PhotoCheck {
    ATTACHED,
    MISSING,
    REJECTED,
}

/**
 * As operações sobre execuções que os casos de uso (online, offline e a API pública) compartilham, sempre dentro
 * da transação e da auditoria de quem chama: criar e assumir, conferir e vincular as fotos, trocar as respostas e
 * concluir publicando `ChecklistCompleted` uma vez. Não checa permissão: cada caso de uso já se autorizou.
 */
@Component
// Os passos de uma execução que os três caminhos (online, offline e API) compartilham, com as portas de que precisam.
@Suppress("TooManyFunctions", "LongParameterList")
class ExecutionOperations(
    private val executions: ChecklistExecutionStore,
    private val templates: ChecklistTemplateStore,
    private val versions: ChecklistDirectory,
    private val files: FilesApi,
    private val audit: AuditContext,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun now(): Instant = clock.instant()

    /** A execução travada para escrita, ou `404 CHECKLIST_EXECUTION_NOT_FOUND`. */
    fun lock(id: ChecklistExecutionId): ChecklistExecution = executions.findForUpdate(id) ?: throw executionNotFound()

    fun version(id: ChecklistVersionId): ChecklistVersionView =
        versions.version(id)?.view()
            ?: throw NotFoundException(ChecklistsErrorCodes.CHECKLIST_VERSION_NOT_FOUND, "Versão não encontrada.")

    /** A última versão publicada do modelo ativo, ou `422` (desativado ou nunca publicado). */
    @Suppress("ThrowsCount") // uma recusa por motivo: inexistente, desativado, nunca publicado
    fun latestVersion(templateId: ChecklistTemplateId): ChecklistVersionView {
        val template =
            templates.find(templateId)
                ?: throw NotFoundException(
                    ChecklistsErrorCodes.CHECKLIST_TEMPLATE_NOT_FOUND,
                    "Modelo de checklist não encontrado.",
                )
        if (!template.active) {
            throw BusinessRuleException(
                ChecklistsErrorCodes.CHECKLIST_TEMPLATE_INACTIVE,
                "O modelo está desativado.",
            )
        }
        val number =
            template.publishedVersion
                ?: throw BusinessRuleException(
                    ChecklistsErrorCodes.CHECKLIST_TEMPLATE_NOT_PUBLISHED,
                    "O modelo ainda não tem versão publicada.",
                )
        return checkNotNull(versions.version(templateId, number)).view()
    }

    /** Se há versão publicada do modelo mais nova que a [version] (a execução offline usou uma substituída). */
    fun isOutdated(version: ChecklistVersionView): Boolean =
        (templates.find(version.templateId)?.publishedVersion ?: version.number) > version.number

    /** Cria uma execução avulsa iniciada. */
    @Suppress("LongParameterList")
    fun startNew(
        id: ChecklistExecutionId,
        version: ChecklistVersionView,
        subject: ChecklistSubject?,
        executor: UserId,
        startedAt: Instant,
        session: DeviceSessionId?,
        commandId: UUID?,
    ): ChecklistExecution {
        val execution =
            ChecklistExecution.start(id, version, subject, executor, startedAt, session, commandId, now())
        audit.created(ChecklistsAudit.EXECUTION, id.value) { execution.auditState() }
        add(execution)
        return execution
    }

    /** Assume a pendente de uma rotina. */
    @Suppress("LongParameterList")
    fun assume(
        execution: ChecklistExecution,
        version: ChecklistVersionView,
        executor: UserId,
        startedAt: Instant,
        session: DeviceSessionId?,
        commandId: UUID?,
    ) {
        audit.track(ChecklistsAudit.EXECUTION, execution.id.value) { execution.auditState() }
        execution.assume(version, executor, startedAt, session, commandId, now())
        executions.update(execution)
    }

    /** Registra à parte a execução offline de uma rotina já assumida por outra pessoa (RN-22). */
    @Suppress("LongParameterList")
    fun duplicate(
        original: ChecklistExecution,
        id: ChecklistExecutionId,
        version: ChecklistVersionView,
        executor: UserId,
        startedAt: Instant,
        session: DeviceSessionId?,
        commandId: UUID,
    ): ChecklistExecution {
        val execution = original.duplicateFor(id, version, executor, startedAt, session, commandId, now())
        audit.created(ChecklistsAudit.EXECUTION, id.value) { execution.auditState() }
        add(execution)
        return execution
    }

    /** As respostas conferidas contra a versão da execução em andamento. */
    fun normalize(
        execution: ChecklistExecution,
        inputs: List<ChecklistAnswerInput>,
        defaultAnsweredAt: Instant,
    ): List<ChecklistAnswer> =
        ChecklistAnswerRules.normalize(
            version(checkNotNull(execution.versionId)).content,
            inputs,
            defaultAnsweredAt,
        )

    /**
     * Troca as respostas da execução em andamento do [actor] (o executor): confere e vincula as fotos novas
     * (finalidade `checklists.photo`, enviadas pelo executor, sem outro dono), desvincula as que saíram e grava.
     * Devolve as chaves dos itens cuja foto ficou de fora (só com [PhotoPolicy.TOLERATE]).
     */
    fun replaceAnswers(
        execution: ChecklistExecution,
        actor: UserId,
        answers: List<ChecklistAnswer>,
        policy: PhotoPolicy,
    ): PhotoIssues {
        execution.requireInProgressBy(actor)
        audit.track(ChecklistsAudit.EXECUTION, execution.id.value) { execution.auditState() }
        val previous = execution.photoIds
        val missing = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        val resolved =
            answers.map { answer ->
                val photo = answer.photoFileId
                if (photo == null || photo in previous) return@map answer
                when (attachPhoto(execution, photo, policy)) {
                    PhotoCheck.ATTACHED -> {
                        answer
                    }

                    PhotoCheck.MISSING -> {
                        missing += answer.itemKey
                        answer.withoutPhoto()
                    }

                    PhotoCheck.REJECTED -> {
                        rejected += answer.itemKey
                        answer.withoutPhoto()
                    }
                }
            }
        val kept = resolved.mapNotNull { it.photoFileId }.toSet()
        if (execution.replaceAnswers(actor, resolved, now())) executions.update(execution)
        (previous - kept).forEach { detachPhoto(execution, it) }
        return PhotoIssues(missing, rejected)
    }

    /**
     * Conclui a execução em andamento do [actor] no horário real [completedAt] e publica `ChecklistCompleted`
     * (uma vez: a execução concluída recusa outra conclusão). As fotos das respostas de itens ocultos pela
     * condição, descartadas na conclusão, são desvinculadas.
     */
    fun complete(
        execution: ChecklistExecution,
        actor: UserId,
        completedAt: Instant,
        session: DeviceSessionId?,
        commandId: UUID?,
    ) {
        audit.track(ChecklistsAudit.EXECUTION, execution.id.value) { execution.auditState() }
        val version = version(checkNotNull(execution.versionId))
        val before = execution.photoIds
        val actions = execution.complete(actor, version.content, completedAt, session, commandId, now())
        executions.update(execution)
        (before - execution.photoIds).forEach { detachPhoto(execution, it) }
        check(execution.status == ChecklistExecutionStatus.COMPLETED)
        events.publishEvent(
            ChecklistCompleted(
                executionId = execution.id.value,
                templateId = execution.templateId.value,
                versionId = version.id.value,
                versionNumber = version.number,
                context = execution.context,
                subjectType = execution.subject?.type,
                subjectId = execution.subject?.id,
                routineId = execution.routineId?.value,
                executorId = checkNotNull(execution.executorId).value,
                startedAt = checkNotNull(execution.startedAt),
                completedAt = checkNotNull(execution.completedAt),
                actions = actions,
                duplicateOf = execution.duplicateOf?.value,
            ),
        )
    }

    /**
     * Confere e vincula a foto à execução. Com [PhotoPolicy.TOLERATE], a que não existe é [PhotoCheck.MISSING] e a
     * inválida (outra finalidade, outro autor ou outro dono) é [PhotoCheck.REJECTED], sem vincular nada; com
     * [PhotoPolicy.REFUSE], `422 CHECKLIST_PHOTO_NOT_FOUND` e `422 CHECKLIST_PHOTO_INVALID`.
     */
    private fun attachPhoto(
        execution: ChecklistExecution,
        photo: FileId,
        policy: PhotoPolicy,
    ): PhotoCheck {
        val owner = execution.id.value.toString()
        val summary = files.find(photo)
        val check =
            when {
                summary == null -> {
                    PhotoCheck.MISSING
                }

                summary.purpose != ChecklistsFilePurposes.PHOTO ||
                    summary.createdBy != execution.executorId ||
                    (summary.ownerEntityId != null && summary.ownerEntityId != owner) -> {
                    PhotoCheck.REJECTED
                }

                else -> {
                    PhotoCheck.ATTACHED
                }
            }
        when {
            check == PhotoCheck.ATTACHED -> {
                files.attach(photo, ChecklistsFilePurposes.PHOTO, owner)
            }

            policy == PhotoPolicy.TOLERATE -> {
                Unit
            }

            check == PhotoCheck.MISSING -> {
                throw BusinessRuleException(
                    ChecklistsErrorCodes.CHECKLIST_PHOTO_NOT_FOUND,
                    "A foto citada não foi encontrada: envie-a antes de responder.",
                )
            }

            else -> {
                throw BusinessRuleException(
                    ChecklistsErrorCodes.CHECKLIST_PHOTO_INVALID,
                    "A foto precisa ser enviada pelo executor como foto de checklist e não pode ser de outro registro.",
                )
            }
        }
        return check
    }

    /** Insere a execução; id já usado (`checklist_execution_pk`): `409 CHECKLIST_EXECUTION_ALREADY_EXISTS`. */
    private fun add(execution: ChecklistExecution) {
        if (!executions.add(execution)) {
            throw ConflictException(
                ChecklistsErrorCodes.CHECKLIST_EXECUTION_ALREADY_EXISTS,
                "Já existe uma execução de checklist com este id.",
            )
        }
    }

    private fun detachPhoto(
        execution: ChecklistExecution,
        photo: FileId,
    ) {
        files.detach(photo, ChecklistsFilePurposes.PHOTO, execution.id.value.toString())
    }

    private fun executionNotFound() =
        NotFoundException(ChecklistsErrorCodes.CHECKLIST_EXECUTION_NOT_FOUND, "Execução de checklist não encontrada.")
}
