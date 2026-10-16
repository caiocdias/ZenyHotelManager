package com.zeny.hotel.checklists.domain

import com.zeny.hotel.checklists.ApplicabilityRule
import com.zeny.hotel.checklists.CheckAnswer
import com.zeny.hotel.checklists.ChecklistContent
import com.zeny.hotel.checklists.ChecklistItem
import com.zeny.hotel.checklists.ChecklistItemType
import com.zeny.hotel.checklists.ChecklistSection
import com.zeny.hotel.checklists.ChecklistsErrorCodes
import com.zeny.hotel.checklists.ChoiceOption
import com.zeny.hotel.checklists.ConsumeStockAction
import com.zeny.hotel.checklists.ItemAction
import com.zeny.hotel.checklists.ItemCondition
import com.zeny.hotel.checklists.LinenExchangeAction
import com.zeny.hotel.checklists.OpenOccurrenceAction
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import java.math.BigDecimal

/**
 * Validação e normalização do conteúdo de um modelo (RF-CHK-01 a 05): textos sem espaços nas pontas, chaves
 * únicas, opções só nos itens de escolha, unidade só nos de quantidade e número, condições só para itens
 * anteriores com resposta compatível, ações só nos tipos de item que as disparam e regras de aplicabilidade com
 * ao menos um critério. A recusa sai como `400` com o caminho do campo em `errors[]`
 * (`sections[0].items[2].visibleWhen.itemKey`).
 *
 * A linguagem de condição é deliberadamente simples (risco da E06): "o item X, anterior, tem a resposta Y", só
 * sobre itens de marcação e de escolha (respostas discretas). Como a condição só olha para trás, não há ciclo.
 */
@Suppress("TooManyFunctions") // uma validação por parte do conteúdo (seção, item, opções, condição, ação…)
internal object ChecklistContentRules {
    const val MAX_TITLE_LENGTH = 120
    const val MAX_LABEL_LENGTH = 200
    const val MAX_OPTION_LABEL_LENGTH = 120
    const val MAX_UNIT_LENGTH = 20
    const val MAX_SECTIONS = 30
    const val MAX_ITEMS_PER_SECTION = 100
    const val MAX_ITEMS = 300
    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 50
    const val MAX_ACTIONS = 5
    const val MAX_RULES = 20
    const val MAX_IDS_PER_CRITERION = 200
    const val MAX_QUANTITY_SCALE = 3
    val MAX_QUANTITY: BigDecimal = BigDecimal("999999")

    /** Chave de item ou valor de opção: minúsculas, dígitos, `_` e `-`, até 40. */
    val KEY = Regex("[a-z0-9][a-z0-9_-]{0,39}")

    /** Categoria de ocorrência: maiúsculas, dígitos, `_` e `-`, até 40 (guardada em maiúsculas). */
    val CATEGORY = Regex("[A-Z0-9][A-Z0-9_-]{0,39}")

    private val QUANTITY_ACTIONS = setOf(ChecklistItemType.QUANTITY)
    private val OCCURRENCE_ITEMS = setOf(ChecklistItemType.CHECK)
    private val CHOICES = setOf(ChecklistItemType.SINGLE_CHOICE, ChecklistItemType.MULTI_CHOICE)
    private val MEASURED = setOf(ChecklistItemType.QUANTITY, ChecklistItemType.NUMBER)

    /** O conteúdo validado e normalizado; o primeiro problema encontrado sai como `400`. */
    fun normalize(content: ChecklistContent): ChecklistContent {
        val applicability = applicability(content.applicability)
        if (content.sections.isEmpty() || content.sections.size > MAX_SECTIONS) {
            reject("sections", "Informe de 1 a $MAX_SECTIONS seções.")
        }
        if (content.items.size > MAX_ITEMS) reject("sections", "Use no máximo $MAX_ITEMS itens no modelo.")
        val allKeys = content.items.map { it.key.trim() }.toSet()
        val earlier = mutableMapOf<String, ChecklistItem>()
        val sections =
            content.sections.mapIndexed { s, section ->
                val path = "sections[$s]"
                val title = required("$path.title", section.title, MAX_TITLE_LENGTH)
                if (section.items.isEmpty() || section.items.size > MAX_ITEMS_PER_SECTION) {
                    reject("$path.items", "Informe de 1 a $MAX_ITEMS_PER_SECTION itens na seção.")
                }
                val items =
                    section.items.mapIndexed { i, item ->
                        item("$path.items[$i]", item, earlier, allKeys).also { earlier[it.key] = it }
                    }
                ChecklistSection(title, items)
            }
        return ChecklistContent(applicability, sections)
    }

    private fun applicability(rules: List<ApplicabilityRule>): List<ApplicabilityRule> {
        if (rules.size > MAX_RULES) {
            reject(
                "applicability",
                "Use no máximo $MAX_RULES regras de aplicabilidade.",
                ChecklistsErrorCodes.CHECKLIST_APPLICABILITY_INVALID,
            )
        }
        rules.forEachIndexed { index, rule ->
            val sets = listOf(rule.roomTypeIds, rule.cleaningTypeIds, rule.areaIds, rule.assetIds)
            if (rule.specificity == 0) {
                reject(
                    "applicability[$index]",
                    "Cada regra de aplicabilidade precisa de ao menos um critério; um modelo sem regras vale para " +
                        "todo o contexto.",
                    ChecklistsErrorCodes.CHECKLIST_APPLICABILITY_INVALID,
                )
            }
            if (sets.any { it.size > MAX_IDS_PER_CRITERION }) {
                reject(
                    "applicability[$index]",
                    "Use no máximo $MAX_IDS_PER_CRITERION IDs por critério.",
                    ChecklistsErrorCodes.CHECKLIST_APPLICABILITY_INVALID,
                )
            }
        }
        return rules.distinct()
    }

    private fun item(
        path: String,
        item: ChecklistItem,
        earlier: Map<String, ChecklistItem>,
        allKeys: Set<String>,
    ): ChecklistItem {
        val key = item.key.trim()
        if (!KEY.matches(key)) {
            reject("$path.key", "Informe a chave do item com até 40 letras minúsculas, dígitos, \"_\" ou \"-\".")
        }
        if (key in earlier) {
            reject(
                "$path.key",
                "Já existe um item com a chave \"$key\" no modelo.",
                ChecklistsErrorCodes.CHECKLIST_ITEM_KEY_DUPLICATED,
            )
        }
        val label = required("$path.label", item.label, MAX_LABEL_LENGTH)
        val options = options(path, item)
        val unit = optional("$path.unit", item.unit, MAX_UNIT_LENGTH)
        if (unit != null && item.type !in MEASURED) {
            reject("$path.unit", "A unidade só vale em itens de quantidade ou número.")
        }
        val condition = item.visibleWhen?.let { condition("$path.visibleWhen", it, earlier, allKeys) }
        val actions = actions(path, item.type, item.actions)
        return ChecklistItem(key, label, item.type, item.required, condition, options, unit, actions)
    }

    private fun options(
        path: String,
        item: ChecklistItem,
    ): List<ChoiceOption> {
        if (item.type !in CHOICES) {
            if (item.options.isNotEmpty()) {
                reject(
                    "$path.options",
                    "Só itens de escolha têm opções.",
                    ChecklistsErrorCodes.CHECKLIST_ITEM_OPTIONS_INVALID,
                )
            }
            return emptyList()
        }
        if (item.options.size !in MIN_OPTIONS..MAX_OPTIONS) {
            reject(
                "$path.options",
                "Informe de $MIN_OPTIONS a $MAX_OPTIONS opções no item de escolha.",
                ChecklistsErrorCodes.CHECKLIST_ITEM_OPTIONS_INVALID,
            )
        }
        val options =
            item.options.mapIndexed { index, option ->
                val value = option.value.trim()
                val label = option.label.trim()
                if (!KEY.matches(value) || label.isEmpty() || label.length > MAX_OPTION_LABEL_LENGTH) {
                    reject(
                        "$path.options[$index]",
                        "Informe o valor da opção com até 40 letras minúsculas, dígitos, \"_\" ou \"-\" e o rótulo " +
                            "com até $MAX_OPTION_LABEL_LENGTH caracteres.",
                        ChecklistsErrorCodes.CHECKLIST_ITEM_OPTIONS_INVALID,
                    )
                }
                ChoiceOption(value, label)
            }
        if (options.map { it.value }.toSet().size != options.size) {
            reject(
                "$path.options",
                "As opções de um item não se repetem.",
                ChecklistsErrorCodes.CHECKLIST_ITEM_OPTIONS_INVALID,
            )
        }
        return options
    }

    private fun condition(
        path: String,
        condition: ItemCondition,
        earlier: Map<String, ChecklistItem>,
        allKeys: Set<String>,
    ): ItemCondition {
        val itemKey = condition.itemKey.trim()
        val answer = condition.answer.trim()
        val target =
            earlier[itemKey] ?: if (itemKey in allKeys) {
                reject(
                    "$path.itemKey",
                    "A condição só pode citar um item anterior a este.",
                    ChecklistsErrorCodes.CHECKLIST_CONDITION_FORWARD_REFERENCE,
                )
            } else {
                reject(
                    "$path.itemKey",
                    "A condição cita um item que não existe no modelo.",
                    ChecklistsErrorCodes.CHECKLIST_CONDITION_UNKNOWN_ITEM,
                )
            }
        val compatible =
            when (target.type) {
                ChecklistItemType.CHECK -> {
                    CheckAnswer.entries.any { it.name == answer }
                }

                ChecklistItemType.SINGLE_CHOICE, ChecklistItemType.MULTI_CHOICE -> {
                    target.options.any {
                        it.value ==
                            answer
                    }
                }

                else -> {
                    false
                }
            }
        if (!compatible) {
            reject(
                "$path.answer",
                "A resposta da condição não cabe no item citado: use OK, NOT_OK ou NOT_APPLICABLE num item de " +
                    "marcação, ou uma das opções num item de escolha.",
                ChecklistsErrorCodes.CHECKLIST_CONDITION_INCOMPATIBLE,
            )
        }
        return ItemCondition(itemKey, answer)
    }

    private fun actions(
        path: String,
        type: ChecklistItemType,
        actions: List<ItemAction>,
    ): List<ItemAction> {
        if (actions.size > MAX_ACTIONS) {
            reject(
                "$path.actions",
                "Use no máximo $MAX_ACTIONS ações por item.",
                ChecklistsErrorCodes.CHECKLIST_ACTION_INVALID,
            )
        }
        val normalized = actions.mapIndexed { index, action -> action("$path.actions[$index]", type, action) }
        if (normalized.map(::target).toSet().size != normalized.size) {
            reject(
                "$path.actions",
                "O item não repete a mesma ação.",
                ChecklistsErrorCodes.CHECKLIST_ACTION_INVALID,
            )
        }
        return normalized
    }

    private fun action(
        path: String,
        type: ChecklistItemType,
        action: ItemAction,
    ): ItemAction {
        val allowedOn = if (action is OpenOccurrenceAction) OCCURRENCE_ITEMS else QUANTITY_ACTIONS
        if (type !in allowedOn) {
            val message =
                when (action) {
                    is ConsumeStockAction -> "A baixa de estoque só vale num item de quantidade."
                    is LinenExchangeAction -> "A troca de enxoval só vale num item de quantidade."
                    is OpenOccurrenceAction -> "Abrir ocorrência só vale num item de marcação (\"não ok\")."
                }
            reject("$path.type", message, ChecklistsErrorCodes.CHECKLIST_ACTION_ITEM_TYPE_MISMATCH)
        }
        return when (action) {
            is ConsumeStockAction -> action.copy(defaultQuantity = action.defaultQuantity?.let { quantity(path, it) })
            is LinenExchangeAction -> action
            is OpenOccurrenceAction -> action.copy(category = action.category?.let { category(path, it) })
        }
    }

    private fun quantity(
        path: String,
        value: BigDecimal,
    ): BigDecimal {
        val normalized = value.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
        if (normalized.signum() <= 0 || normalized > MAX_QUANTITY || normalized.scale() > MAX_QUANTITY_SCALE) {
            reject(
                "$path.defaultQuantity",
                "Informe a quantidade padrão maior que zero, até $MAX_QUANTITY e com até $MAX_QUANTITY_SCALE casas.",
                ChecklistsErrorCodes.CHECKLIST_ACTION_INVALID,
            )
        }
        return normalized
    }

    private fun category(
        path: String,
        value: String,
    ): String? {
        val normalized = value.trim().uppercase().ifEmpty { return null }
        if (!CATEGORY.matches(normalized)) {
            reject(
                "$path.category",
                "Informe a categoria com até 40 letras, dígitos, \"_\" ou \"-\".",
                ChecklistsErrorCodes.CHECKLIST_ACTION_INVALID,
            )
        }
        return normalized
    }

    /** O que uma ação faz, para barrar a mesma ação duas vezes no item. */
    private fun target(action: ItemAction): Any =
        when (action) {
            is ConsumeStockAction -> action.type to action.stockItemId
            is LinenExchangeAction -> Triple(action.type, action.linenTypeId, action.movement)
            is OpenOccurrenceAction -> action.type to action.kind
        }

    /** Texto obrigatório, sem espaços nas pontas, de 1 a [max] caracteres. */
    fun required(
        field: String,
        value: String,
        max: Int,
    ): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > max) reject(field, "Informe $field com até $max caracteres.")
        return trimmed
    }

    private fun optional(
        field: String,
        value: String?,
        max: Int,
    ): String? {
        val trimmed = value?.trim()?.ifEmpty { null } ?: return null
        if (trimmed.length > max) reject(field, "Use até $max caracteres em $field.")
        return trimmed
    }

    /** Recusa com `400`, o [code] e o [field] em `errors[]`. */
    fun reject(
        field: String,
        message: String,
        code: ErrorCode = ChecklistsErrorCodes.CHECKLIST_DATA_INVALID,
    ): Nothing = throw InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))
}
