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

    /** A versão publicada [number] do modelo, ou `null` se não existe. */
    fun version(
        templateId: ChecklistTemplateId,
        number: Int,
    ): ChecklistVersionView?

    /** A versão publicada pelo id, ou `null` se não existe. */
    fun version(id: ChecklistVersionId): ChecklistVersionView?
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
