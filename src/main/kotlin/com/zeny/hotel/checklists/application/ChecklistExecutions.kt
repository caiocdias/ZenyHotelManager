package com.zeny.hotel.checklists.application

import com.zeny.hotel.checklists.ChecklistAnswerInput
import com.zeny.hotel.checklists.ChecklistCompletion
import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.checklists.ChecklistExecutionView
import com.zeny.hotel.checklists.ChecklistSubject
import com.zeny.hotel.checklists.ChecklistTemplateId
import com.zeny.hotel.checklists.ChecklistVersionId
import com.zeny.hotel.checklists.ChecklistVersionView
import com.zeny.hotel.checklists.ChecklistsApi
import com.zeny.hotel.checklists.ChecklistsErrorCodes
import com.zeny.hotel.checklists.ChecklistsPermissions
import com.zeny.hotel.checklists.SubjectAttributes
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.requireCurrentVersion
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Como iniciar uma execução avulsa online: pelo modelo ou pelo contexto e os atributos do sujeito. */
data class StartExecutionCommand(
    val templateId: ChecklistTemplateId?,
    val context: ChecklistContext?,
    val subject: ChecklistSubject?,
    val attributes: SubjectAttributes,
)

/** Uma execução com o conteúdo da versão usada (nulo na pendente, que ainda não tem versão). */
data class ChecklistExecutionDetail(
    val execution: ChecklistExecutionView,
    val version: ChecklistVersionView?,
)

/**
 * Execuções de checklist online (RF-CHK-05 a 08): iniciar (avulsa, ou assumir a pendente de uma rotina),
 * trocar as respostas (`If-Match`), concluir e consultar. Executar exige `checklists.executions.execute`;
 * consultar, `checklists.executions.read`. Com o escopo `OWN`, só as execuções de que o usuário é executor ou
 * candidato (H14): assumir uma pendente exige ser candidato, e só o executor responde e conclui. Toda escrita
 * trava a linha da execução (`SELECT … FOR UPDATE`): duas conclusões simultâneas dão uma conclusão e um `409`.
 */
@Service
class ChecklistExecutionsService(
    private val operations: ExecutionOperations,
    private val executions: ChecklistExecutionStore,
    private val directory: ChecklistExecutionDirectory,
    private val checklists: ChecklistsApi,
    private val authorization: Authorization,
    private val actor: CurrentActor,
) {
    @Transactional(readOnly = true)
    fun executions(
        filter: ExecutionFilter,
        page: PageParams,
    ): CursorPage<ChecklistExecutionView> {
        val owner = authorization.scopeFilter(ChecklistsPermissions.EXECUTIONS_READ).ownerId
        val before = page.decodedCursor()?.let { it.instant(0) to it.uuid(1) }
        val rows = directory.executions(filter, owner, before, page.limit + 1)
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.createdAt.toString(), it.id.toString()) }
    }

    /** A execução com a versão usada; fora do escopo `OWN` responde `404` (não revela que existe). */
    @Transactional(readOnly = true)
    fun execution(id: ChecklistExecutionId): ChecklistExecutionDetail {
        val owner = authorization.scopeFilter(ChecklistsPermissions.EXECUTIONS_READ).ownerId
        val execution =
            executions.find(id)?.takeIf { owner == null || UserId(owner) in it.ownerIds } ?: throw notFound()
        return ChecklistExecutionDetail(execution.view(), execution.versionId?.let(operations::version))
    }

    /**
     * Inicia uma execução avulsa do usuário: a última versão publicada do modelo informado (ativo) ou do modelo que
     * vale para o sujeito no contexto (RF-CHK-04); nenhum, `422 CHECKLIST_NO_APPLICABLE_TEMPLATE`.
     */
    @Audited(ChecklistsAudit.START_EXECUTION)
    @Transactional
    fun start(command: StartExecutionCommand): ChecklistExecutionDetail {
        authorization.require(ChecklistsPermissions.EXECUTIONS_EXECUTE)
        val current = actor.current()
        val version =
            when {
                command.templateId != null && command.context == null -> {
                    operations.latestVersion(command.templateId)
                }

                command.templateId == null && command.context != null -> {
                    checklists.resolveTemplate(command.context, command.attributes)
                        ?: throw BusinessRuleException(
                            ChecklistsErrorCodes.CHECKLIST_NO_APPLICABLE_TEMPLATE,
                            "Nenhum modelo de checklist publicado vale para este sujeito no contexto.",
                        )
                }

                else -> {
                    throw invalidStart()
                }
            }
        val execution =
            operations.startNew(
                ChecklistExecutionId.new(),
                version,
                command.subject,
                current.userId,
                operations.now(),
                current.deviceSessionId,
                null,
            )
        return ChecklistExecutionDetail(execution.view(), version)
    }

    /**
     * Assume a pendente de uma rotina (H14: o primeiro candidato que inicia), com a última versão publicada do
     * modelo. Com `OWN`, só um candidato; já iniciada, `409 CHECKLIST_EXECUTION_ALREADY_STARTED`.
     */
    @Audited(ChecklistsAudit.START_EXECUTION)
    @Transactional
    fun startScheduled(id: ChecklistExecutionId): ChecklistExecutionDetail {
        authorization.require(ChecklistsPermissions.EXECUTIONS_EXECUTE)
        val current = actor.current()
        val execution = operations.lock(id)
        authorization.require(ChecklistsPermissions.EXECUTIONS_EXECUTE, execution.candidatesResource)
        val version = operations.latestVersion(execution.templateId)
        operations.assume(execution, version, current.userId, operations.now(), current.deviceSessionId, null)
        return ChecklistExecutionDetail(execution.view(), version)
    }

    /**
     * Troca as respostas da execução em andamento (todas: a lista substitui a gravada), com o `If-Match`. As fotos
     * novas são conferidas e vinculadas; as que saem, desvinculadas.
     */
    @Audited(ChecklistsAudit.ANSWER_EXECUTION)
    @Transactional
    fun replaceAnswers(
        id: ChecklistExecutionId,
        expectedVersion: Long,
        inputs: List<ChecklistAnswerInput>,
    ): ChecklistExecutionDetail {
        authorization.require(ChecklistsPermissions.EXECUTIONS_EXECUTE)
        val user = actor.current().userId
        val execution = operations.lock(id)
        authorization.require(ChecklistsPermissions.EXECUTIONS_EXECUTE, execution.executorResource)
        requireCurrentVersion(expectedVersion, execution.version)
        execution.requireInProgressBy(user)
        val now = operations.now()
        val answers = operations.normalize(execution, inputs.map { it.copy(answeredAt = null) }, now)
        operations.replaceAnswers(execution, user, answers, PhotoPolicy.REFUSE)
        return ChecklistExecutionDetail(execution.view(), operations.version(checkNotNull(execution.versionId)))
    }

    /** Conclui com as respostas gravadas e publica `ChecklistCompleted` (RF-CHK-05). */
    @Audited(ChecklistsAudit.COMPLETE_EXECUTION)
    @Transactional
    fun complete(id: ChecklistExecutionId): ChecklistExecutionDetail {
        authorization.require(ChecklistsPermissions.EXECUTIONS_EXECUTE)
        val current = actor.current()
        val execution = operations.lock(id)
        authorization.require(ChecklistsPermissions.EXECUTIONS_EXECUTE, execution.executorResource)
        operations.complete(execution, current.userId, operations.now(), current.deviceSessionId, null)
        return ChecklistExecutionDetail(execution.view(), operations.version(checkNotNull(execution.versionId)))
    }

    private fun notFound() =
        NotFoundException(ChecklistsErrorCodes.CHECKLIST_EXECUTION_NOT_FOUND, "Execução de checklist não encontrada.")

    private fun invalidStart(): InvalidInputException {
        val code = ChecklistsErrorCodes.CHECKLIST_EXECUTION_INVALID
        val message = "Informe o modelo (templateId) ou o contexto (context), um dos dois."
        return InvalidInputException(code, message, listOf(FieldViolation("templateId", code.value, message)))
    }
}

/**
 * As execuções pela API pública (para o módulo dono do sujeito, governança no M6): sem checar permissão, com o
 * executor e os horários reais informados por quem chama, e a foto que não chegou tolerada (H10).
 */
@Service
class ChecklistExecutionsApiService(
    private val operations: ExecutionOperations,
    private val executions: ChecklistExecutionStore,
) {
    @Transactional(readOnly = true)
    fun execution(id: ChecklistExecutionId): ChecklistExecutionView? = executions.find(id)?.view()

    @Audited(ChecklistsAudit.START_EXECUTION)
    @Transactional
    fun start(
        versionId: ChecklistVersionId,
        subject: ChecklistSubject?,
        executor: UserId,
        startedAt: Instant,
        id: ChecklistExecutionId,
    ): ChecklistExecutionView =
        operations.startNew(id, operations.version(versionId), subject, executor, startedAt, null, null).view()

    @Audited(ChecklistsAudit.COMPLETE_EXECUTION)
    @Transactional
    fun complete(
        id: ChecklistExecutionId,
        inputs: List<ChecklistAnswerInput>,
        completedAt: Instant,
    ): ChecklistCompletion {
        val execution = operations.lock(id)
        val executor = execution.inProgressExecutor()
        val answers = operations.normalize(execution, inputs, completedAt)
        val photos =
            operations.completeWithAnswers(execution, executor, answers, PhotoPolicy.TOLERATE, completedAt, null, null)
        return ChecklistCompletion(execution.view(), photos.missing, photos.rejected)
    }
}
