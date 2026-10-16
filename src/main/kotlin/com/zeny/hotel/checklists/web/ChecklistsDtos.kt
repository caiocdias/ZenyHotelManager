package com.zeny.hotel.checklists.web

import com.zeny.hotel.checklists.ApplicabilityRule
import com.zeny.hotel.checklists.ChecklistContent
import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistItem
import com.zeny.hotel.checklists.ChecklistItemType
import com.zeny.hotel.checklists.ChecklistSection
import com.zeny.hotel.checklists.ChecklistVersionView
import com.zeny.hotel.checklists.ChecklistsErrorCodes
import com.zeny.hotel.checklists.ChoiceOption
import com.zeny.hotel.checklists.ConsumeStockAction
import com.zeny.hotel.checklists.ItemAction
import com.zeny.hotel.checklists.ItemActionType
import com.zeny.hotel.checklists.ItemCondition
import com.zeny.hotel.checklists.LinenExchangeAction
import com.zeny.hotel.checklists.LinenMovement
import com.zeny.hotel.checklists.OccurrenceKind
import com.zeny.hotel.checklists.OpenOccurrenceAction
import com.zeny.hotel.checklists.application.ChecklistTemplateCommand
import com.zeny.hotel.checklists.application.ChecklistTemplateSummary
import com.zeny.hotel.checklists.application.ChecklistTemplateView
import com.zeny.hotel.checklists.application.ChecklistVersionSummary
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

private const val MAX_RULES = 20
private const val MAX_IDS = 200
private const val MAX_SECTIONS = 30
private const val MAX_ITEMS = 100
private const val MAX_OPTIONS = 50
private const val MAX_ACTIONS = 5

@Schema(description = "Um modelo de checklist novo: nome, contexto (fixo depois de criado) e o rascunho.")
data class CreateChecklistTemplateRequest(
    @field:Schema(description = "Até 120 caracteres.", example = "Limpeza de saída")
    val name: String,
    @field:NotNull
    val context: ChecklistContext,
    @field:Schema(
        description =
            "Regras de aplicabilidade (RF-CHK-04); vale qualquer uma. Vazio: o modelo geral do contexto.",
    )
    @field:Valid
    @field:Size(max = MAX_RULES)
    val applicability: List<ApplicabilityRuleDto> = emptyList(),
    @field:Schema(description = "As seções, na ordem de exibição (de 1 a 30).")
    @field:Valid
    @field:Size(max = MAX_SECTIONS)
    val sections: List<ChecklistSectionDto> = emptyList(),
) {
    fun toCommand() = ChecklistTemplateCommand(name, content(applicability, sections))
}

@Schema(description = "O nome (vale na hora) e o rascunho inteiro de um modelo (vale na próxima publicação).")
data class UpdateChecklistTemplateRequest(
    @field:Schema(description = "Até 120 caracteres.", example = "Limpeza de saída")
    val name: String,
    @field:Schema(
        description =
            "Regras de aplicabilidade (RF-CHK-04); vale qualquer uma. Vazio: o modelo geral do contexto.",
    )
    @field:Valid
    @field:Size(max = MAX_RULES)
    val applicability: List<ApplicabilityRuleDto> = emptyList(),
    @field:Schema(description = "As seções, na ordem de exibição (de 1 a 30).")
    @field:Valid
    @field:Size(max = MAX_SECTIONS)
    val sections: List<ChecklistSectionDto> = emptyList(),
) {
    fun toCommand() = ChecklistTemplateCommand(name, content(applicability, sections))
}

@Schema(
    description =
        "Regra de aplicabilidade: o modelo vale para o sujeito quando cada lista não vazia contém o atributo dele " +
            "(lista vazia: o critério não restringe). Ao menos uma lista não vazia. Os IDs são de outros módulos e " +
            "não são conferidos.",
)
data class ApplicabilityRuleDto(
    @field:Size(max = MAX_IDS)
    val roomTypeIds: List<UUID> = emptyList(),
    @field:Size(max = MAX_IDS)
    val cleaningTypeIds: List<UUID> = emptyList(),
    @field:Size(max = MAX_IDS)
    val areaIds: List<UUID> = emptyList(),
    @field:Size(max = MAX_IDS)
    val assetIds: List<UUID> = emptyList(),
) {
    fun toRule() = ApplicabilityRule(roomTypeIds.toSet(), cleaningTypeIds.toSet(), areaIds.toSet(), assetIds.toSet())

    companion object {
        fun of(rule: ApplicabilityRule) =
            ApplicabilityRuleDto(
                rule.roomTypeIds.sorted(),
                rule.cleaningTypeIds.sorted(),
                rule.areaIds.sorted(),
                rule.assetIds.sorted(),
            )
    }
}

@Schema(description = "Uma seção, com os itens na ordem de exibição (de 1 a 100).")
data class ChecklistSectionDto(
    @field:Schema(description = "Até 120 caracteres.", example = "Banheiro")
    val title: String,
    @field:Valid
    @field:Size(max = MAX_ITEMS)
    val items: List<ChecklistItemDto> = emptyList(),
) {
    companion object {
        fun of(section: ChecklistSection) = ChecklistSectionDto(section.title, section.items.map(ChecklistItemDto::of))
    }
}

@Schema(description = "Um item (pergunta) do checklist.")
data class ChecklistItemDto(
    @field:Schema(
        description =
            "Chave do item no modelo: minúsculas, dígitos, `_` e `-`, até 40, única no modelo. Fica igual entre as " +
                "versões para identificar o mesmo item; as condições a citam.",
        example = "towels",
    )
    val key: String,
    @field:Schema(description = "Até 200 caracteres.", example = "Toalhas repostas")
    val label: String,
    @field:NotNull
    val type: ChecklistItemType,
    @field:Schema(
        description =
            "Obrigatório quando aparece. Padrão `false` para todos os tipos (a foto nunca é exigida por padrão).",
    )
    val required: Boolean = false,
    @field:Schema(
        description = "Condição: o item só aparece se um item anterior tiver a resposta dada.",
        nullable = true,
    )
    @field:Valid
    val visibleWhen: ItemConditionDto? = null,
    @field:Schema(description = "Só nos itens de escolha (`SINGLE_CHOICE`, `MULTI_CHOICE`): de 2 a 50.")
    @field:Valid
    @field:Size(max = MAX_OPTIONS)
    val options: List<ChoiceOptionDto> = emptyList(),
    @field:Schema(description = "Só em `QUANTITY` e `NUMBER`: até 20 caracteres.", nullable = true, example = "°C")
    val unit: String? = null,
    @field:Schema(description = "Ações executadas ao concluir o checklist (RF-CHK-05), até 5.")
    @field:Valid
    @field:Size(max = MAX_ACTIONS)
    val actions: List<ItemActionDto> = emptyList(),
) {
    fun toItem(path: String) =
        ChecklistItem(
            key,
            label,
            type,
            required,
            visibleWhen?.let { ItemCondition(it.itemKey, it.answer) },
            options.map { ChoiceOption(it.value, it.label) },
            unit,
            actions.mapIndexed { index, action -> action.toAction("$path.actions[$index]") },
        )

    companion object {
        fun of(item: ChecklistItem) =
            ChecklistItemDto(
                item.key,
                item.label,
                item.type,
                item.required,
                item.visibleWhen?.let { ItemConditionDto(it.itemKey, it.answer) },
                item.options.map { ChoiceOptionDto(it.value, it.label) },
                item.unit,
                item.actions.map(ItemActionDto::of),
            )
    }
}

@Schema(
    description =
        "O item só aparece se o item `itemKey`, anterior a este, tiver a resposta `answer`: num item de marcação, " +
            "`OK`, `NOT_OK` ou `NOT_APPLICABLE`; num de escolha, o valor de uma das opções (na escolha múltipla, " +
            "basta a opção estar marcada).",
)
data class ItemConditionDto(
    @field:Schema(example = "shower")
    val itemKey: String,
    @field:Schema(example = "NOT_OK")
    val answer: String,
)

@Schema(description = "Uma opção de um item de escolha.")
data class ChoiceOptionDto(
    @field:Schema(
        description = "Valor estável: minúsculas, dígitos, `_` e `-`, até 40, único no item.",
        example = "strong",
    )
    val value: String,
    @field:Schema(description = "Até 120 caracteres.", example = "Forte")
    val label: String,
)

@Schema(
    description =
        "Ação vinculada ao item (RF-CHK-05), com os parâmetros do tipo e nenhum outro. `CONSUME_STOCK` " +
            "(`stockItemId`, `defaultQuantity` opcional) e `LINEN_EXCHANGE` (`linenTypeId`, `movement`) só num item " +
            "`QUANTITY`; `OPEN_OCCURRENCE` (`occurrenceKind`, `category` opcional) só num item `CHECK`, quando a " +
            "resposta é `NOT_OK`. Os IDs são de outros módulos e não são conferidos.",
)
data class ItemActionDto(
    @field:NotNull
    val type: ItemActionType,
    @field:Schema(description = "Insumo da baixa de estoque.", nullable = true)
    val stockItemId: UUID? = null,
    @field:Schema(
        description = "Quantidade sugerida na baixa: maior que zero, até 999999, até 3 casas.",
        nullable = true,
        example = "2",
    )
    val defaultQuantity: BigDecimal? = null,
    @field:Schema(description = "Tipo de enxoval da troca.", nullable = true)
    val linenTypeId: UUID? = null,
    @field:Schema(description = "O que a quantidade respondida representa na troca de enxoval.", nullable = true)
    val movement: LinenMovement? = null,
    @field:Schema(description = "O que a ocorrência gera.", nullable = true)
    val occurrenceKind: OccurrenceKind? = null,
    @field:Schema(
        description = "Categoria da ocorrência, do módulo que a trata: letras, dígitos, `_` e `-`, até 40.",
        nullable = true,
        example = "PLUMBING",
    )
    val category: String? = null,
) {
    /** A ação do tipo, com os parâmetros dele; faltando um, ou com um de outro tipo, `400 CHECKLIST_ACTION_INVALID`. */
    fun toAction(path: String): ItemAction {
        val allowed =
            when (type) {
                ItemActionType.CONSUME_STOCK -> setOf("stockItemId", "defaultQuantity")
                ItemActionType.LINEN_EXCHANGE -> setOf("linenTypeId", "movement")
                ItemActionType.OPEN_OCCURRENCE -> setOf("occurrenceKind", "category")
            }
        val present =
            mapOf(
                "stockItemId" to stockItemId,
                "defaultQuantity" to defaultQuantity,
                "linenTypeId" to linenTypeId,
                "movement" to movement,
                "occurrenceKind" to occurrenceKind,
                "category" to category,
            ).filterValues { it != null }.keys
        (present - allowed).firstOrNull()?.let {
            throw invalid(
                "$path.$it",
                "Este parâmetro não vale para a ação $type.",
            )
        }
        return when (type) {
            ItemActionType.CONSUME_STOCK -> {
                ConsumeStockAction(required(path, "stockItemId", stockItemId), defaultQuantity)
            }

            ItemActionType.LINEN_EXCHANGE -> {
                LinenExchangeAction(required(path, "linenTypeId", linenTypeId), required(path, "movement", movement))
            }

            ItemActionType.OPEN_OCCURRENCE -> {
                OpenOccurrenceAction(required(path, "occurrenceKind", occurrenceKind), category)
            }
        }
    }

    private fun <T : Any> required(
        path: String,
        field: String,
        value: T?,
    ): T = value ?: throw invalid("$path.$field", "Informe $field na ação $type.")

    private fun invalid(
        field: String,
        message: String,
    ): InvalidInputException {
        val code = ChecklistsErrorCodes.CHECKLIST_ACTION_INVALID
        return InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))
    }

    companion object {
        fun of(action: ItemAction) =
            when (action) {
                is ConsumeStockAction -> {
                    ItemActionDto(
                        action.type,
                        stockItemId = action.stockItemId,
                        defaultQuantity = action.defaultQuantity,
                    )
                }

                is LinenExchangeAction -> {
                    ItemActionDto(action.type, linenTypeId = action.linenTypeId, movement = action.movement)
                }

                is OpenOccurrenceAction -> {
                    ItemActionDto(action.type, occurrenceKind = action.kind, category = action.category)
                }
            }
    }
}

@Schema(description = "Um modelo de checklist: os dados, o rascunho e a última versão publicada.")
data class ChecklistTemplateResponse(
    val id: UUID,
    val name: String,
    val context: ChecklistContext,
    val active: Boolean,
    @field:Schema(description = "Número da última versão publicada; nulo se nunca foi publicado.", nullable = true)
    val publishedVersion: Int?,
    @field:Schema(nullable = true)
    val publishedAt: Instant?,
    @field:Schema(description = "Se o rascunho difere da última versão publicada (ou nunca foi publicado).")
    val hasUnpublishedChanges: Boolean,
    @field:Schema(description = "Aplicabilidade do rascunho.")
    val applicability: List<ApplicabilityRuleDto>,
    @field:Schema(description = "Seções do rascunho.")
    val sections: List<ChecklistSectionDto>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: ChecklistTemplateView) =
            ChecklistTemplateResponse(
                view.id.value,
                view.name,
                view.context,
                view.active,
                view.publishedVersion,
                view.publishedAt,
                view.hasUnpublishedChanges,
                view.draft.applicability.map(ApplicabilityRuleDto::of),
                view.draft.sections.map(ChecklistSectionDto::of),
                view.createdAt,
                view.updatedAt,
            )
    }
}

@Schema(description = "Um modelo de checklist na listagem, sem o conteúdo.")
data class ChecklistTemplateSummaryResponse(
    val id: UUID,
    val name: String,
    val context: ChecklistContext,
    val active: Boolean,
    @field:Schema(nullable = true)
    val publishedVersion: Int?,
    @field:Schema(nullable = true)
    val publishedAt: Instant?,
    val hasUnpublishedChanges: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    @field:Schema(description = "Versão do registro (a mesma do `ETag` do detalhe).")
    val version: Long,
) {
    companion object {
        fun of(view: ChecklistTemplateSummary) =
            ChecklistTemplateSummaryResponse(
                view.id.value,
                view.name,
                view.context,
                view.active,
                view.publishedVersion,
                view.publishedAt,
                view.hasUnpublishedChanges,
                view.createdAt,
                view.updatedAt,
                view.version,
            )
    }
}

@Schema(description = "Uma versão publicada de um modelo: imutável.")
data class ChecklistVersionResponse(
    val id: UUID,
    val templateId: UUID,
    val number: Int,
    @field:Schema(description = "Nome do modelo na publicação.")
    val name: String,
    val context: ChecklistContext,
    val applicability: List<ApplicabilityRuleDto>,
    val sections: List<ChecklistSectionDto>,
    val publishedAt: Instant,
    @field:Schema(description = "Usuário que publicou.", nullable = true)
    val publishedBy: UUID?,
) {
    companion object {
        fun of(view: ChecklistVersionView) =
            ChecklistVersionResponse(
                view.id.value,
                view.templateId.value,
                view.number,
                view.name,
                view.context,
                view.content.applicability.map(ApplicabilityRuleDto::of),
                view.content.sections.map(ChecklistSectionDto::of),
                view.publishedAt,
                view.publishedBy?.value,
            )
    }
}

@Schema(description = "Uma versão publicada na listagem, sem o conteúdo.")
data class ChecklistVersionSummaryResponse(
    val id: UUID,
    val number: Int,
    val name: String,
    val publishedAt: Instant,
    @field:Schema(nullable = true)
    val publishedBy: UUID?,
) {
    companion object {
        fun of(view: ChecklistVersionSummary) =
            ChecklistVersionSummaryResponse(
                view.id.value,
                view.number,
                view.name,
                view.publishedAt,
                view.publishedBy?.value,
            )
    }
}

private fun content(
    applicability: List<ApplicabilityRuleDto>,
    sections: List<ChecklistSectionDto>,
) = ChecklistContent(
    applicability.map(ApplicabilityRuleDto::toRule),
    sections.mapIndexed { s, section ->
        ChecklistSection(section.title, section.items.mapIndexed { i, item -> item.toItem("sections[$s].items[$i]") })
    },
)
