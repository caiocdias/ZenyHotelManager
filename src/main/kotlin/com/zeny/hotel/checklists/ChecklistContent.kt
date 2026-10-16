package com.zeny.hotel.checklists

import java.math.BigDecimal
import java.util.UUID

/** Contexto de um modelo de checklist (RF-CHK-01). Não muda depois de criado o modelo. */
enum class ChecklistContext {
    HOUSEKEEPING,
    INSPECTION,
    MAINTENANCE,
    COMMON_AREA,
    RESTAURANT,
    EVENT,
    OTHER,
}

/** Tipo de item (RF-CHK-02, SPEC §8.2.11). */
enum class ChecklistItemType {
    /** Marcação: ok, não ok ou não se aplica ([CheckAnswer]). */
    CHECK,

    /** Quantidade (peças, unidades); o único tipo que dispara baixa de estoque e troca de enxoval. */
    QUANTITY,

    /** Número ou medição (temperatura da câmara fria). */
    NUMBER,

    /** Texto livre. */
    TEXT,

    /** Escolha de uma das [ChecklistItem.options]. */
    SINGLE_CHOICE,

    /** Escolha de várias das [ChecklistItem.options]. */
    MULTI_CHOICE,

    /** Foto: opcional e não exigida por padrão (o item só é obrigatório se o modelo disser). */
    PHOTO,
}

/** As respostas possíveis de um item de marcação. */
enum class CheckAnswer {
    OK,
    NOT_OK,
    NOT_APPLICABLE,
}

/**
 * O conteúdo de um modelo, o que uma versão publicada congela (RF-CHK-06): a aplicabilidade e as seções com
 * os itens, na ordem em que aparecem (a ordem da lista é a ordem de exibição).
 */
data class ChecklistContent(
    val applicability: List<ApplicabilityRule>,
    val sections: List<ChecklistSection>,
) {
    /** Os itens de todas as seções, na ordem do modelo. */
    val items: List<ChecklistItem> get() = sections.flatMap { it.items }
}

/**
 * Regra de aplicabilidade (RF-CHK-04, H3): o modelo vale para um sujeito quando cada conjunto não vazio contém
 * o atributo dele (conjunto vazio: o critério não restringe). Os IDs são opacos: o `checklists` não os valida
 * (tipos de UH, tipos de limpeza, áreas e ativos são de outros módulos). Um modelo sem regras é o modelo geral
 * do contexto; com várias, vale qualquer uma delas.
 */
data class ApplicabilityRule(
    val roomTypeIds: Set<UUID> = emptySet(),
    val cleaningTypeIds: Set<UUID> = emptySet(),
    val areaIds: Set<UUID> = emptySet(),
    val assetIds: Set<UUID> = emptySet(),
) {
    /** Quantos critérios a regra restringe (a especificidade: quanto mais, mais específica). */
    val specificity: Int
        get() = listOf(roomTypeIds, cleaningTypeIds, areaIds, assetIds).count { it.isNotEmpty() }

    /** Se a regra vale para o [subject]: todo critério restrito contém o atributo dele. */
    fun matches(subject: SubjectAttributes): Boolean =
        accepts(roomTypeIds, subject.roomTypeId) &&
            accepts(cleaningTypeIds, subject.cleaningTypeId) &&
            accepts(areaIds, subject.areaId) &&
            accepts(assetIds, subject.assetId)

    private fun accepts(
        allowed: Set<UUID>,
        value: UUID?,
    ): Boolean = allowed.isEmpty() || (value != null && value in allowed)
}

/** Os atributos do sujeito de uma tarefa ou rotina, informados por quem pede o modelo (RF-CHK-04, H3). */
data class SubjectAttributes(
    val roomTypeId: UUID? = null,
    val cleaningTypeId: UUID? = null,
    val areaId: UUID? = null,
    val assetId: UUID? = null,
)

/** Uma seção do modelo, com os itens na ordem de exibição. */
data class ChecklistSection(
    val title: String,
    val items: List<ChecklistItem>,
)

/**
 * Um item (pergunta) do modelo (RF-CHK-02 e 03). [key] identifica o item no modelo e entre as versões (as
 * condições e, na execução, as respostas a citam). [required] vale só quando o item aparece (um item com
 * [visibleWhen] some se a condição não for atendida). [options] só nos itens de escolha; [unit] só nos de
 * quantidade e número; [actions] são executadas ao concluir o checklist (RF-CHK-05).
 */
data class ChecklistItem(
    val key: String,
    val label: String,
    val type: ChecklistItemType,
    val required: Boolean = false,
    val visibleWhen: ItemCondition? = null,
    val options: List<ChoiceOption> = emptyList(),
    val unit: String? = null,
    val actions: List<ItemAction> = emptyList(),
)

/**
 * Condição de um item condicional (RF-CHK-03): o item só aparece se o item [itemKey], anterior a ele, tiver a
 * resposta [answer]. Num item de marcação, a resposta é um [CheckAnswer]; num de escolha única, a opção
 * escolhida; num de escolha múltipla, uma das opções marcadas.
 */
data class ItemCondition(
    val itemKey: String,
    val answer: String,
)

/** Uma opção de um item de escolha: o valor (estável, citado pelas condições e respostas) e o rótulo. */
data class ChoiceOption(
    val value: String,
    val label: String,
)

/** Tipo de uma ação vinculada a um item (RF-CHK-05). */
enum class ItemActionType {
    CONSUME_STOCK,
    LINEN_EXCHANGE,
    OPEN_OCCURRENCE,
}

/**
 * Ação vinculada a um item, executada ao concluir o checklist (RF-CHK-05, SPEC §8.2.11). O `checklists` só a
 * guarda e, na conclusão (E07), a publica no `ChecklistCompleted`; quem trata é o módulo dono (estoque,
 * rouparia, manutenção, governança), que o `checklists` não conhece.
 */
sealed interface ItemAction {
    val type: ItemActionType
}

/** Baixa de estoque do insumo [stockItemId] (ID opaco), pela quantidade respondida num item de quantidade. */
data class ConsumeStockAction(
    val stockItemId: UUID,
    val defaultQuantity: BigDecimal? = null,
) : ItemAction {
    override val type: ItemActionType get() = ItemActionType.CONSUME_STOCK
}

/** O movimento de enxoval que a quantidade respondida representa. */
enum class LinenMovement {
    /** Peças retiradas (sujas). */
    REMOVED,

    /** Peças colocadas. */
    PLACED,
}

/** Troca de enxoval do tipo [linenTypeId] (ID opaco): a quantidade respondida é o [movement]. */
data class LinenExchangeAction(
    val linenTypeId: UUID,
    val movement: LinenMovement,
) : ItemAction {
    override val type: ItemActionType get() = ItemActionType.LINEN_EXCHANGE
}

/** O que uma ocorrência aberta por um item "não ok" gera. */
enum class OccurrenceKind {
    /** Ordem de manutenção. */
    MAINTENANCE,

    /** Achado (achados e perdidos). */
    LOST_AND_FOUND,

    /** Dano. */
    DAMAGE,
}

/** Abrir ocorrência ([kind], com a [category] opcional do módulo dono) quando o item de marcação é "não ok". */
data class OpenOccurrenceAction(
    val kind: OccurrenceKind,
    val category: String? = null,
) : ItemAction {
    override val type: ItemActionType get() = ItemActionType.OPEN_OCCURRENCE
}
