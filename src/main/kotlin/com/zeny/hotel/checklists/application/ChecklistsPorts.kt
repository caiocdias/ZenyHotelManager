package com.zeny.hotel.checklists.application

import com.zeny.hotel.checklists.ChecklistContent
import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.checklists.ChecklistExecutionStatus
import com.zeny.hotel.checklists.ChecklistExecutionView
import com.zeny.hotel.checklists.ChecklistRoutineId
import com.zeny.hotel.checklists.ChecklistTemplateId
import com.zeny.hotel.checklists.ChecklistVersionId
import com.zeny.hotel.checklists.domain.ChecklistExecution
import com.zeny.hotel.checklists.domain.ChecklistRoutine
import com.zeny.hotel.checklists.domain.ChecklistTemplate
import com.zeny.hotel.checklists.domain.ChecklistVersion
import com.zeny.hotel.checklists.domain.ResolutionCandidate
import com.zeny.hotel.shared.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Os dados de um modelo como chegam da API para criar ou editar (o contexto só na criação). */
data class ChecklistTemplateCommand(
    val name: String,
    val content: ChecklistContent,
)

/** Um modelo como sai da consulta: os dados, o rascunho e a última versão publicada. */
data class ChecklistTemplateView(
    val id: ChecklistTemplateId,
    val name: String,
    val context: ChecklistContext,
    val active: Boolean,
    val draft: ChecklistContent,
    val publishedVersion: Int?,
    val publishedAt: Instant?,
    val hasUnpublishedChanges: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

/** Um modelo na listagem, sem o conteúdo. */
data class ChecklistTemplateSummary(
    val id: ChecklistTemplateId,
    val name: String,
    val context: ChecklistContext,
    val active: Boolean,
    val publishedVersion: Int?,
    val publishedAt: Instant?,
    val hasUnpublishedChanges: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

/** Uma versão publicada na listagem das versões de um modelo, sem o conteúdo. */
data class ChecklistVersionSummary(
    val id: ChecklistVersionId,
    val number: Int,
    val name: String,
    val publishedAt: Instant,
    val publishedBy: UserId?,
)

/**
 * Os modelos para escrita, na transação corrente. As versões só são inseridas: a tabela é *append-only* para o
 * papel da aplicação.
 */
interface ChecklistTemplateStore {
    fun find(id: ChecklistTemplateId): ChecklistTemplate?

    /** O modelo travado para escrita (`SELECT … FOR UPDATE`) até o fim da transação. */
    fun findForUpdate(id: ChecklistTemplateId): ChecklistTemplate?

    fun add(template: ChecklistTemplate)

    /** Grava o estado do modelo (nome, ativo, rascunho, última versão, versão do registro). */
    fun update(template: ChecklistTemplate)

    fun addVersion(version: ChecklistVersion)
}

/** Leituras dos modelos e versões (`JdbcClient`). */
interface ChecklistDirectory {
    /** Os modelos em ordem de nome e id, depois de [after], com os filtros opcionais. */
    fun templates(
        context: ChecklistContext?,
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<ChecklistTemplateSummary>

    /** As versões do modelo, da mais nova para a mais antiga, antes do número [before]. */
    fun versions(
        templateId: ChecklistTemplateId,
        before: Int?,
        limit: Int,
    ): List<ChecklistVersionSummary>

    fun version(
        templateId: ChecklistTemplateId,
        number: Int,
    ): ChecklistVersion?

    fun version(id: ChecklistVersionId): ChecklistVersion?

    /** Os modelos ativos e publicados do [context], com a aplicabilidade da última versão publicada. */
    fun resolutionCandidates(context: ChecklistContext): List<ResolutionCandidate>
}

/** Filtros da listagem das execuções (todos opcionais). */
data class ExecutionFilter(
    val status: ChecklistExecutionStatus? = null,
    val context: ChecklistContext? = null,
    val routineId: ChecklistRoutineId? = null,
    val businessDate: LocalDate? = null,
    val subjectType: String? = null,
    val subjectId: UUID? = null,
)

/** As execuções para escrita, na transação corrente (`JdbcClient`). */
interface ChecklistExecutionStore {
    fun find(id: ChecklistExecutionId): ChecklistExecution?

    /** A execução travada para escrita (`SELECT … FOR UPDATE`) até o fim da transação. */
    fun findForUpdate(id: ChecklistExecutionId): ChecklistExecution?

    /** A execução à parte (RN-22) que o [executor] registrou para a rotina [original], travada, ou `null`. */
    fun findDuplicateForUpdate(
        original: ChecklistExecutionId,
        executor: UserId,
    ): ChecklistExecution?

    /**
     * Insere a execução e as respostas; devolve `false`, sem gravar nada, se o id já existe (violação de
     * `checklist_execution_pk`, reconhecida pelo nome da restrição; a transação de quem chama fica marcada para
     * desfazer).
     */
    fun add(execution: ChecklistExecution): Boolean

    /**
     * Insere a pendente de uma rotina se ainda não há execução da rotina na data (H14); devolve se inseriu. A
     * reentrega do evento, ou duas gerações simultâneas, não duplicam (`checklist_execution_routine_day_uk`).
     */
    fun addScheduled(execution: ChecklistExecution): Boolean

    /** Grava o estado da execução e troca as respostas pelas dela. */
    fun update(execution: ChecklistExecution)
}

/** Leituras das execuções (`JdbcClient`), sem as respostas. */
interface ChecklistExecutionDirectory {
    /**
     * As execuções da mais nova para a mais antiga (criação e id), antes de [before], com os filtros e, com
     * [owner], só as de que ele é candidato ou executor (escopo `OWN`, H14).
     */
    fun executions(
        filter: ExecutionFilter,
        owner: UUID?,
        before: Pair<Instant, UUID>?,
        limit: Int,
    ): List<ChecklistExecutionView>
}

/** Uma rotina que gera na data, com o contexto do modelo (ativo e publicado). */
data class GeneratingRoutine(
    val routine: ChecklistRoutine,
    val context: ChecklistContext,
)

/** As rotinas (`JdbcClient`). */
interface ChecklistRoutineStore {
    fun find(id: ChecklistRoutineId): ChecklistRoutine?

    fun findForUpdate(id: ChecklistRoutineId): ChecklistRoutine?

    fun add(routine: ChecklistRoutine)

    fun update(routine: ChecklistRoutine)

    /** As rotinas em ordem de nome e id, depois de [after], com o filtro opcional. */
    fun routines(
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<ChecklistRoutine>

    /** As rotinas ativas cujo modelo está ativo e publicado (as que podem gerar), em ordem de id. */
    fun generating(): List<GeneratingRoutine>
}
