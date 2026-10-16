package com.zeny.hotel.checklists.domain

import com.zeny.hotel.checklists.ChecklistContent
import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistTemplateId
import com.zeny.hotel.checklists.ChecklistVersionId
import com.zeny.hotel.checklists.ChecklistVersionView
import com.zeny.hotel.checklists.ChecklistsErrorCodes
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.UserId
import java.time.Instant

/**
 * Modelo de checklist (RF-CHK-01 e 06, SPEC §8.2.11): nome, contexto (fixo desde a criação), ativo ou não, o
 * **rascunho** editável ([draft]: aplicabilidade, seções e itens) e a última versão publicada. Publicar congela o
 * rascunho numa [ChecklistVersion] nova (número seguinte); a versão publicada nunca muda (o papel da aplicação
 * nem tem `UPDATE` na tabela), então editar um modelo publicado é editar o rascunho e publicar outra versão. O
 * nome vale na hora (a versão guarda o nome da publicação). Nunca é apagado, só desativado; desativado, não é
 * editado nem publicado, e a escolha do modelo de um sujeito o ignora.
 *
 * [version] é a versão do registro (o `ETag`): cada operação que muda algo a avança uma vez.
 */
@Suppress("LongParameterList") // reconstituído da persistência com todos os campos
class ChecklistTemplate private constructor(
    val id: ChecklistTemplateId,
    name: String,
    val context: ChecklistContext,
    active: Boolean,
    draft: ChecklistContent,
    publishedContent: ChecklistContent?,
    publishedVersion: Int?,
    publishedAt: Instant?,
    val createdAt: Instant,
    updatedAt: Instant,
    version: Long,
) {
    var name: String = name
        private set

    var active: Boolean = active
        private set

    /** O rascunho: o que a próxima publicação congela. */
    var draft: ChecklistContent = draft
        private set

    /** O conteúdo da última versão publicada, ou `null` se nunca foi publicado. */
    var publishedContent: ChecklistContent? = publishedContent
        private set

    /** O número da última versão publicada, ou `null` se nunca foi publicado. */
    var publishedVersion: Int? = publishedVersion
        private set

    var publishedAt: Instant? = publishedAt
        private set

    var updatedAt: Instant = updatedAt
        private set

    var version: Long = version
        private set

    /** Se o rascunho difere da última versão publicada (ou se nunca houve publicação). */
    val hasUnpublishedChanges: Boolean get() = draft != publishedContent

    /** Troca o nome e o rascunho; devolve se algo mudou. Desativado: `422 CHECKLIST_TEMPLATE_INACTIVE`. */
    fun edit(
        name: String,
        draft: ChecklistContent,
        now: Instant,
    ): Boolean {
        requireActive()
        val newName = validName(name)
        val newDraft = ChecklistContentRules.normalize(draft)
        if (newName == this.name && newDraft == this.draft) return false
        this.name = newName
        this.draft = newDraft
        touch(now)
        return true
    }

    /**
     * Publica o rascunho como a versão seguinte (RF-CHK-06). Sem mudança desde a última publicação: `422
     * CHECKLIST_NOTHING_TO_PUBLISH`; desativado: `422 CHECKLIST_TEMPLATE_INACTIVE`.
     */
    fun publish(
        now: Instant,
        publishedBy: UserId?,
    ): ChecklistVersion {
        requireActive()
        if (!hasUnpublishedChanges) {
            throw BusinessRuleException(
                ChecklistsErrorCodes.CHECKLIST_NOTHING_TO_PUBLISH,
                "O rascunho é igual à última versão publicada: não há o que publicar.",
            )
        }
        val number = (publishedVersion ?: 0) + 1
        val published = ChecklistVersion(ChecklistVersionId.new(), id, number, name, context, draft, now, publishedBy)
        publishedContent = draft
        publishedVersion = number
        publishedAt = now
        touch(now)
        return published
    }

    /** Desativa; devolve se mudou. As versões publicadas ficam (as execuções as citam). */
    fun deactivate(now: Instant): Boolean {
        if (!active) return false
        active = false
        touch(now)
        return true
    }

    /** Reativa; devolve se mudou. */
    fun reactivate(now: Instant): Boolean {
        if (active) return false
        active = true
        touch(now)
        return true
    }

    private fun touch(now: Instant) {
        updatedAt = now
        version += 1
    }

    private fun requireActive() {
        if (!active) {
            throw BusinessRuleException(
                ChecklistsErrorCodes.CHECKLIST_TEMPLATE_INACTIVE,
                "O modelo está desativado: reative-o antes de editar ou publicar.",
            )
        }
    }

    companion object {
        const val MAX_NAME_LENGTH = 120

        /** Um modelo novo, só com o rascunho (nada publicado). */
        fun create(
            name: String,
            context: ChecklistContext,
            draft: ChecklistContent,
            now: Instant,
        ): ChecklistTemplate =
            ChecklistTemplate(
                ChecklistTemplateId.new(),
                validName(name),
                context,
                active = true,
                draft = ChecklistContentRules.normalize(draft),
                publishedContent = null,
                publishedVersion = null,
                publishedAt = null,
                createdAt = now,
                updatedAt = now,
                version = 0,
            )

        /** Reconstitui um modelo gravado (a persistência já guardou o conteúdo validado). */
        fun restore(
            id: ChecklistTemplateId,
            name: String,
            context: ChecklistContext,
            active: Boolean,
            draft: ChecklistContent,
            published: PublishedState?,
            createdAt: Instant,
            updatedAt: Instant,
            version: Long,
        ): ChecklistTemplate =
            ChecklistTemplate(
                id,
                name,
                context,
                active,
                draft,
                published?.content,
                published?.number,
                published?.publishedAt,
                createdAt,
                updatedAt,
                version,
            )

        private fun validName(name: String) = ChecklistContentRules.required("name", name, MAX_NAME_LENGTH)
    }

    /** A última versão publicada, como a persistência a entrega. */
    data class PublishedState(
        val number: Int,
        val content: ChecklistContent,
        val publishedAt: Instant,
    )
}

/**
 * Uma versão publicada (RF-CHK-06, SPEC §8.2.11): o número (1, 2, …, por modelo), o nome do modelo na
 * publicação, o contexto e o conteúdo congelado. Imutável: nunca é alterada nem apagada.
 */
data class ChecklistVersion(
    val id: ChecklistVersionId,
    val templateId: ChecklistTemplateId,
    val number: Int,
    val name: String,
    val context: ChecklistContext,
    val content: ChecklistContent,
    val publishedAt: Instant,
    val publishedBy: UserId?,
) {
    fun view(): ChecklistVersionView =
        ChecklistVersionView(id, templateId, number, name, context, content, publishedAt, publishedBy)
}
