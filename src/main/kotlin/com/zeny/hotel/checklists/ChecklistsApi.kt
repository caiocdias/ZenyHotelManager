package com.zeny.hotel.checklists

import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import java.time.Instant
import java.util.UUID

/**
 * API pública do módulo `checklists` para os outros módulos (ARCHITECTURE §3.1, RF-CHK-04 e 06). Não checa
 * permissão: quem chama é um caso de uso que já se autorizou. Só lê versões **publicadas**, que nunca mudam
 * (o rascunho não sai do módulo).
 */
interface ChecklistsApi {
    /**
     * O modelo da tarefa ou rotina de um sujeito (RF-CHK-04): entre os modelos ativos do [context] com versão
     * publicada, o que tem uma regra de aplicabilidade que vale para [subject], o mais específico (mais
     * critérios restritos), ou, sem regra que valha, o modelo geral do contexto (sem regras). Empate: o modelo
     * criado primeiro. Devolve a última versão publicada dele, ou `null` se nenhum modelo vale.
     */
    fun resolveTemplate(
        context: ChecklistContext,
        subject: SubjectAttributes,
    ): ChecklistVersionView?

    /**
     * O resumo do modelo (contexto, se está ativo e se tem versão publicada), ou `null` se não existe. Para quem
     * vincula um modelo (governança, M6 E01) e confere o contexto, o estado ativo e a publicação no momento do vínculo.
     */
    fun template(id: ChecklistTemplateId): ChecklistTemplateSummary?

    /**
     * A versão publicada vigente (a última) do modelo **ativo** [templateId], ou `null` se o modelo não existe, está
     * desativado ou nunca foi publicado. Para quem abre uma execução de um modelo vinculado (governança, D5 da M6 E04).
     */
    fun currentVersion(templateId: ChecklistTemplateId): ChecklistVersionView?

    /** A versão publicada [number] do modelo, ou `null` se não existe. */
    fun version(
        templateId: ChecklistTemplateId,
        number: Int,
    ): ChecklistVersionView?

    /** A versão publicada pelo id, ou `null` se não existe. */
    fun version(id: ChecklistVersionId): ChecklistVersionView?

    /** A execução com as respostas, ou `null` se não existe. */
    fun execution(id: ChecklistExecutionId): ChecklistExecutionView?

    /**
     * Inicia uma execução da versão [versionId] (de um modelo ativo ou não: a versão é a usada, RF-CHK-06) para
     * o [subject], com o [executor] e o início real [startedAt] (quem chama corrige o horário do dispositivo).
     * Para o módulo dono do sujeito (governança no M6, que já se autorizou). Auditada
     * (`checklists.execution.start`). Versão inexistente: `404 CHECKLIST_VERSION_NOT_FOUND`.
     */
    fun startExecution(
        versionId: ChecklistVersionId,
        subject: ChecklistSubject?,
        executor: UserId,
        startedAt: Instant,
        id: ChecklistExecutionId = ChecklistExecutionId.new(),
    ): ChecklistExecutionView

    /**
     * Conclui a execução em andamento com as [answers] (todas: substituem as gravadas) no horário real
     * [completedAt] e publica `ChecklistCompleted` (uma vez: concluir de novo é `409
     * CHECKLIST_EXECUTION_ALREADY_COMPLETED`). As fotos são conferidas (finalidade `checklists.photo`, enviadas
     * pelo executor) e vinculadas à execução; a que ainda não chegou fica de fora e volta em
     * [ChecklistCompletion.missingPhotos] (H10). Item obrigatório visível sem resposta: `422
     * CHECKLIST_REQUIRED_ITEMS_MISSING`. Auditada (`checklists.execution.complete`).
     */
    fun completeExecution(
        id: ChecklistExecutionId,
        answers: List<ChecklistAnswerInput>,
        completedAt: Instant,
    ): ChecklistCompletion

    /**
     * A conclusão condicional (governança, D5 da M6 E04): trava a execução e, em andamento, conclui como
     * [completeExecution] (com o `422 CHECKLIST_REQUIRED_ITEMS_MISSING` de item obrigatório faltando), com as fotos
     * pela [photoPolicy]: `REFUSE` na ação online, como o endpoint do `checklists`; `TOLERATE` no comando offline.
     * **Já concluída por fora** (pelo próprio executor, online ou offline), devolve `null` sem erro, sem tocar na
     * execução, sem entrada na trilha e sem um segundo `ChecklistCompleted`, e as [answers] são ignoradas. Pendente:
     * `409 CHECKLIST_EXECUTION_NOT_STARTED`. Inexistente: `404 CHECKLIST_EXECUTION_NOT_FOUND`.
     */
    fun completeExecutionIfOpen(
        id: ChecklistExecutionId,
        answers: List<ChecklistAnswerInput>,
        completedAt: Instant,
        photoPolicy: ChecklistPhotoPolicy,
    ): ChecklistCompletion?
}

/**
 * Uma versão publicada de um modelo (RF-CHK-06): imutável; cada execução guarda a versão usada. [name] é o
 * nome do modelo na publicação.
 */
data class ChecklistVersionView(
    val id: ChecklistVersionId,
    val templateId: ChecklistTemplateId,
    val number: Int,
    val name: String,
    val context: ChecklistContext,
    val content: ChecklistContent,
    val publishedAt: Instant,
    val publishedBy: UserId?,
)

/** O resumo de um modelo (RF-CHK-04): [published] diz se já tem versão publicada. */
data class ChecklistTemplateSummary(
    val id: ChecklistTemplateId,
    val context: ChecklistContext,
    val active: Boolean,
    val published: Boolean,
)

/** Id de um modelo de checklist. */
@JvmInline
value class ChecklistTemplateId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): ChecklistTemplateId = ChecklistTemplateId(UuidV7.generate())
    }
}

/** Id de uma versão publicada de um modelo de checklist. */
@JvmInline
value class ChecklistVersionId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): ChecklistVersionId = ChecklistVersionId(UuidV7.generate())
    }
}
