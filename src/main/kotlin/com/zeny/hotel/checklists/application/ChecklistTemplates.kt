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
import com.zeny.hotel.checklists.ChecklistsFilePurposes
import com.zeny.hotel.checklists.ChecklistsPermissions
import com.zeny.hotel.checklists.SubjectAttributes
import com.zeny.hotel.checklists.domain.ChecklistTemplate
import com.zeny.hotel.checklists.domain.TemplateResolution
import com.zeny.hotel.files.ModuleFilePurposes
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.requireCurrentVersion
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * Modelos de checklist (RF-CHK-01 a 06): criar, editar o rascunho, publicar versões, desativar e reativar.
 * Consultar exige `checklists.templates.read`; escrever, `checklists.templates.manage`. As edições e a
 * publicação conferem a versão da última leitura (`If-Match`): publica-se o rascunho que se viu. Nada é
 * apagado. Toda escrita trava a linha do modelo (`SELECT … FOR UPDATE`), então duas publicações simultâneas
 * se enfileiram e a segunda vê a primeira (sem número de versão repetido; o banco também barra, com
 * `checklist_version_number_uk`).
 */
@Service
class ChecklistTemplatesService(
    private val store: ChecklistTemplateStore,
    private val directory: ChecklistDirectory,
    private val authorization: Authorization,
    private val actor: CurrentActor,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun templates(
        context: ChecklistContext?,
        active: Boolean?,
        page: PageParams,
    ): CursorPage<ChecklistTemplateSummary> {
        authorization.require(ChecklistsPermissions.TEMPLATES_READ)
        val after = page.decodedCursor()?.let { it.string(0) to it.uuid(1) }
        val rows = directory.templates(context, active, after, page.limit + 1)
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.name, it.id.toString()) }
    }

    @Transactional(readOnly = true)
    fun template(id: ChecklistTemplateId): ChecklistTemplateView {
        authorization.require(ChecklistsPermissions.TEMPLATES_READ)
        return (store.find(id) ?: throw templateNotFound()).view()
    }

    @Audited(ChecklistsAudit.CREATE_TEMPLATE)
    @Transactional
    fun create(
        context: ChecklistContext,
        command: ChecklistTemplateCommand,
    ): ChecklistTemplateView {
        authorization.require(ChecklistsPermissions.TEMPLATES_MANAGE)
        val template = ChecklistTemplate.create(command.name, context, command.content, clock.instant())
        audit.created(ChecklistsAudit.TEMPLATE, template.id.value) { template.auditState() }
        store.add(template)
        return template.view()
    }

    @Audited(ChecklistsAudit.UPDATE_TEMPLATE)
    @Transactional
    fun update(
        id: ChecklistTemplateId,
        expectedVersion: Long,
        command: ChecklistTemplateCommand,
    ): ChecklistTemplateView {
        authorization.require(ChecklistsPermissions.TEMPLATES_MANAGE)
        val template = lockedTemplate(id)
        requireCurrentVersion(expectedVersion, template.version)
        audit.track(ChecklistsAudit.TEMPLATE, id.value) { template.auditState() }
        if (template.edit(command.name, command.content, clock.instant())) store.update(template)
        return template.view()
    }

    /** Publica o rascunho como a versão seguinte (RF-CHK-06); a anterior continua igual. */
    @Audited(ChecklistsAudit.PUBLISH_TEMPLATE)
    @Transactional
    fun publish(
        id: ChecklistTemplateId,
        expectedVersion: Long,
    ): ChecklistVersionView {
        authorization.require(ChecklistsPermissions.TEMPLATES_MANAGE)
        val publisher = actor.current().userId
        val template = lockedTemplate(id)
        requireCurrentVersion(expectedVersion, template.version)
        audit.track(ChecklistsAudit.TEMPLATE, id.value) { template.auditState() }
        val version = template.publish(clock.instant(), publisher)
        audit.created(ChecklistsAudit.VERSION, version.id.value) { version.auditState() }
        store.addVersion(version)
        store.update(template)
        return version.view()
    }

    @Audited(ChecklistsAudit.DEACTIVATE_TEMPLATE)
    @Transactional
    fun deactivate(id: ChecklistTemplateId): ChecklistTemplateView {
        authorization.require(ChecklistsPermissions.TEMPLATES_MANAGE)
        val template = lockedTemplate(id)
        audit.track(ChecklistsAudit.TEMPLATE, id.value) { template.auditState() }
        if (template.deactivate(clock.instant())) store.update(template)
        return template.view()
    }

    @Audited(ChecklistsAudit.REACTIVATE_TEMPLATE)
    @Transactional
    fun reactivate(id: ChecklistTemplateId): ChecklistTemplateView {
        authorization.require(ChecklistsPermissions.TEMPLATES_MANAGE)
        val template = lockedTemplate(id)
        audit.track(ChecklistsAudit.TEMPLATE, id.value) { template.auditState() }
        if (template.reactivate(clock.instant())) store.update(template)
        return template.view()
    }

    @Transactional(readOnly = true)
    fun versions(
        id: ChecklistTemplateId,
        page: PageParams,
    ): CursorPage<ChecklistVersionSummary> {
        authorization.require(ChecklistsPermissions.TEMPLATES_READ)
        store.find(id) ?: throw templateNotFound()
        val before = page.decodedCursor()?.long(0)?.toInt()
        val rows = directory.versions(id, before, page.limit + 1)
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.number.toString()) }
    }

    @Transactional(readOnly = true)
    fun version(
        id: ChecklistTemplateId,
        number: Int,
    ): ChecklistVersionView {
        authorization.require(ChecklistsPermissions.TEMPLATES_READ)
        store.find(id) ?: throw templateNotFound()
        return directory.version(id, number)?.view()
            ?: throw NotFoundException(ChecklistsErrorCodes.CHECKLIST_VERSION_NOT_FOUND, "Versão não encontrada.")
    }

    private fun lockedTemplate(id: ChecklistTemplateId) = store.findForUpdate(id) ?: throw templateNotFound()

    private fun templateNotFound() =
        NotFoundException(ChecklistsErrorCodes.CHECKLIST_TEMPLATE_NOT_FOUND, "Modelo de checklist não encontrado.")
}

/** A API pública (sem checar permissão: quem chama já se autorizou). Só lê versões publicadas. */
@Service
class ChecklistsApiService(
    private val directory: ChecklistDirectory,
    private val executions: ChecklistExecutionsApiService,
) : ChecklistsApi {
    @Transactional(readOnly = true)
    override fun resolveTemplate(
        context: ChecklistContext,
        subject: SubjectAttributes,
    ): ChecklistVersionView? {
        val chosen = TemplateResolution.choose(directory.resolutionCandidates(context), subject) ?: return null
        return directory.version(chosen.templateId, chosen.publishedVersion)?.view()
    }

    @Transactional(readOnly = true)
    override fun version(
        templateId: ChecklistTemplateId,
        number: Int,
    ): ChecklistVersionView? = directory.version(templateId, number)?.view()

    @Transactional(readOnly = true)
    override fun version(id: ChecklistVersionId): ChecklistVersionView? = directory.version(id)?.view()

    override fun execution(id: ChecklistExecutionId): ChecklistExecutionView? = executions.execution(id)

    override fun startExecution(
        versionId: ChecklistVersionId,
        subject: ChecklistSubject?,
        executor: UserId,
        startedAt: Instant,
        id: ChecklistExecutionId,
    ): ChecklistExecutionView = executions.start(versionId, subject, executor, startedAt, id)

    override fun completeExecution(
        id: ChecklistExecutionId,
        answers: List<ChecklistAnswerInput>,
        completedAt: Instant,
    ): ChecklistCompletion = executions.complete(id, answers, completedAt)
}

@Configuration(proxyBeanMethods = false)
class ChecklistsPermissionsConfiguration {
    @Bean
    fun checklistsPermissions(): ModulePermissions = ChecklistsPermissions

    @Bean
    fun checklistsFilePurposes(): ModuleFilePurposes = ChecklistsFilePurposes
}

internal fun ChecklistTemplate.view() =
    ChecklistTemplateView(
        id,
        name,
        context,
        active,
        draft,
        publishedVersion,
        publishedAt,
        hasUnpublishedChanges,
        createdAt,
        updatedAt,
        version,
    )
