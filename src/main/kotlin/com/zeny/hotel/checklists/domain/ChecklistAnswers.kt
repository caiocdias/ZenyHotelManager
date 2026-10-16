package com.zeny.hotel.checklists.domain

import com.zeny.hotel.checklists.CheckAnswer
import com.zeny.hotel.checklists.ChecklistAnswerInput
import com.zeny.hotel.checklists.ChecklistAnswerView
import com.zeny.hotel.checklists.ChecklistContent
import com.zeny.hotel.checklists.ChecklistItem
import com.zeny.hotel.checklists.ChecklistItemType
import com.zeny.hotel.checklists.ChecklistTriggeredAction
import com.zeny.hotel.checklists.ChecklistsErrorCodes
import com.zeny.hotel.checklists.ConsumeStockAction
import com.zeny.hotel.checklists.LinenExchangeAction
import com.zeny.hotel.checklists.OpenOccurrenceAction
import com.zeny.hotel.files.FileId
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import java.math.BigDecimal
import java.time.Instant

/**
 * Uma resposta gravada de uma execução (SPEC §8.2.11, `ChecklistAnswer`): a chave e o tipo do item na versão da
 * execução, o valor do tipo (só um dos campos), a foto ([photoFileId]) ou a marca de foto que não chegou
 * ([photoMissing], H10), a observação e o horário real.
 */
data class ChecklistAnswer(
    val itemKey: String,
    val itemType: ChecklistItemType,
    val check: CheckAnswer? = null,
    val quantity: BigDecimal? = null,
    val number: BigDecimal? = null,
    val text: String? = null,
    val choice: String? = null,
    val choices: List<String> = emptyList(),
    val photoFileId: FileId? = null,
    val photoMissing: Boolean = false,
    val note: String? = null,
    val answeredAt: Instant,
) {
    /** Se a resposta conta para um item obrigatório: tem o valor do tipo (a foto que não chegou também conta). */
    val answered: Boolean
        get() =
            when (itemType) {
                ChecklistItemType.CHECK -> check != null
                ChecklistItemType.QUANTITY -> quantity != null
                ChecklistItemType.NUMBER -> number != null
                ChecklistItemType.TEXT -> !text.isNullOrBlank()
                ChecklistItemType.SINGLE_CHOICE -> choice != null
                ChecklistItemType.MULTI_CHOICE -> choices.isNotEmpty()
                ChecklistItemType.PHOTO -> photoFileId != null || photoMissing
            }

    /** A mesma resposta, sem a foto, marcada como foto que não chegou (H10). */
    fun withoutPhoto(): ChecklistAnswer = copy(photoFileId = null, photoMissing = true)

    fun view() =
        ChecklistAnswerView(
            itemKey,
            itemType,
            check,
            quantity,
            number,
            text,
            choice,
            choices,
            photoFileId,
            photoMissing,
            note,
            answeredAt,
        )
}

/**
 * Regras das respostas (RF-CHK-02, 03 e 05): o formato de cada resposta pelo tipo do item, a visibilidade dos
 * itens condicionais, os obrigatórios e as ações disparadas na conclusão.
 */
object ChecklistAnswerRules {
    const val MAX_TEXT_LENGTH = 2000
    const val MAX_NOTE_LENGTH = 500
    const val MAX_QUANTITY_SCALE = 3
    const val MAX_NUMBER_SCALE = 4
    val MAX_QUANTITY: BigDecimal = BigDecimal("999999")
    val MAX_NUMBER: BigDecimal = BigDecimal("9999999999")

    /**
     * As respostas conferidas contra o [content] da versão, na ordem recebida; o horário de cada uma é o
     * informado ou [defaultAnsweredAt]. Chave inexistente: `400 CHECKLIST_ANSWER_UNKNOWN_ITEM`; repetida, `400
     * CHECKLIST_ANSWER_DUPLICATED`; valor que não cabe no tipo, `400 CHECKLIST_ANSWER_INVALID` (com o campo em
     * `errors[]`, `answers[2].quantity`).
     */
    fun normalize(
        content: ChecklistContent,
        inputs: List<ChecklistAnswerInput>,
        defaultAnsweredAt: Instant,
    ): List<ChecklistAnswer> {
        val items = content.items.associateBy { it.key }
        val seen = mutableSetOf<String>()
        return inputs.mapIndexed { index, input ->
            val path = "answers[$index]"
            val key = input.itemKey.trim()
            val item =
                items[key] ?: reject(
                    "$path.itemKey",
                    "A resposta cita um item que não existe nesta versão do checklist.",
                    ChecklistsErrorCodes.CHECKLIST_ANSWER_UNKNOWN_ITEM,
                )
            if (!seen.add(key)) {
                reject(
                    "$path.itemKey",
                    "O item \"$key\" tem mais de uma resposta.",
                    ChecklistsErrorCodes.CHECKLIST_ANSWER_DUPLICATED,
                )
            }
            answer(path, item, input, micros(input.answeredAt ?: defaultAnsweredAt))
        }
    }

    /**
     * As chaves dos itens que aparecem com as [answers] (RF-CHK-03): sem condição, sempre; com condição, só se o
     * item citado (anterior) aparece e tem a resposta da condição. A condição sobre um item oculto não é atendida.
     */
    fun visibleKeys(
        content: ChecklistContent,
        answers: Map<String, ChecklistAnswer>,
    ): Set<String> {
        val visible = mutableSetOf<String>()
        for (item in content.items) {
            val condition = item.visibleWhen
            val shown =
                condition == null ||
                    (condition.itemKey in visible && matches(answers[condition.itemKey], condition.answer))
            if (shown) visible += item.key
        }
        return visible
    }

    /** As chaves dos itens obrigatórios visíveis sem resposta, na ordem do modelo. */
    fun missingRequired(
        content: ChecklistContent,
        answers: Map<String, ChecklistAnswer>,
    ): List<String> {
        val visible = visibleKeys(content, answers)
        return content.items
            .filter { it.required && it.key in visible && answers[it.key]?.answered != true }
            .map { it.key }
    }

    /**
     * As ações disparadas pelas [answers] (RF-CHK-05), na ordem do modelo: baixa de estoque e troca de enxoval com
     * a quantidade respondida maior que zero; ocorrência com a marcação "não ok". Só itens visíveis.
     */
    fun triggeredActions(
        content: ChecklistContent,
        answers: Map<String, ChecklistAnswer>,
    ): List<ChecklistTriggeredAction> {
        val visible = visibleKeys(content, answers)
        return content.items
            .filter { it.key in visible }
            .flatMap { item ->
                val answer = answers[item.key] ?: return@flatMap emptyList()
                item.actions.mapNotNull { action ->
                    when (action) {
                        is ConsumeStockAction -> {
                            answer.quantity?.takeIf { it.signum() > 0 }?.let {
                                ChecklistTriggeredAction(
                                    item.key,
                                    action.type,
                                    quantity = it,
                                    stockItemId = action.stockItemId,
                                )
                            }
                        }

                        is LinenExchangeAction -> {
                            answer.quantity?.takeIf { it.signum() > 0 }?.let {
                                ChecklistTriggeredAction(
                                    item.key,
                                    action.type,
                                    quantity = it,
                                    linenTypeId = action.linenTypeId,
                                    movement = action.movement,
                                )
                            }
                        }

                        is OpenOccurrenceAction -> {
                            if (answer.check == CheckAnswer.NOT_OK) {
                                ChecklistTriggeredAction(
                                    item.key,
                                    action.type,
                                    occurrenceKind = action.kind,
                                    category = action.category,
                                    note = answer.note,
                                )
                            } else {
                                null
                            }
                        }
                    }
                }
            }
    }

    private fun matches(
        answer: ChecklistAnswer?,
        expected: String,
    ): Boolean =
        when {
            answer == null -> false
            answer.check != null -> answer.check.name == expected
            answer.choice != null -> answer.choice == expected
            else -> expected in answer.choices
        }

    @Suppress("CyclomaticComplexMethod") // uma conferência por tipo de item
    private fun answer(
        path: String,
        item: ChecklistItem,
        input: ChecklistAnswerInput,
        answeredAt: Instant,
    ): ChecklistAnswer {
        val provided =
            mapOf(
                "check" to input.check,
                "quantity" to input.quantity,
                "number" to input.number,
                "text" to input.text,
                "choice" to input.choice,
                "choices" to input.choices,
                "photoFileId" to input.photoFileId,
            ).filterValues { it != null }.keys
        val expected = FIELD_OF.getValue(item.type)
        (provided - expected).firstOrNull()?.let {
            reject("$path.$it", "Este campo não vale para o item \"${item.key}\" (${item.type}).")
        }
        if (expected !in provided) reject("$path.$expected", "Informe $expected para o item \"${item.key}\".")
        val note = input.note?.trim()?.ifEmpty { null }
        if (note != null && note.length > MAX_NOTE_LENGTH) {
            reject("$path.note", "Use até $MAX_NOTE_LENGTH caracteres na observação.")
        }
        val base = ChecklistAnswer(item.key, item.type, note = note, answeredAt = answeredAt)
        return when (item.type) {
            ChecklistItemType.CHECK -> {
                base.copy(check = input.check)
            }

            ChecklistItemType.QUANTITY -> {
                base.copy(quantity = quantity("$path.quantity", checkNotNull(input.quantity)))
            }

            ChecklistItemType.NUMBER -> {
                base.copy(number = number("$path.number", checkNotNull(input.number)))
            }

            ChecklistItemType.TEXT -> {
                val text = checkNotNull(input.text).trim()
                if (text.isEmpty() || text.length > MAX_TEXT_LENGTH) {
                    reject("$path.text", "Informe o texto com até $MAX_TEXT_LENGTH caracteres.")
                }
                base.copy(text = text)
            }

            ChecklistItemType.SINGLE_CHOICE -> {
                val choice = checkNotNull(input.choice).trim()
                if (item.options.none { it.value == choice }) {
                    reject("$path.choice", "Escolha uma das opções do item \"${item.key}\".")
                }
                base.copy(choice = choice)
            }

            ChecklistItemType.MULTI_CHOICE -> {
                val choices = checkNotNull(input.choices).map { it.trim() }
                val options = item.options.map { it.value }
                if (choices.isEmpty() || choices.toSet().size != choices.size || !options.containsAll(choices)) {
                    reject("$path.choices", "Marque uma ou mais opções do item \"${item.key}\", sem repetir.")
                }
                base.copy(choices = options.filter { it in choices })
            }

            ChecklistItemType.PHOTO -> {
                base.copy(photoFileId = input.photoFileId)
            }
        }
    }

    private fun quantity(
        path: String,
        value: BigDecimal,
    ): BigDecimal {
        val normalized = normalized(value)
        if (normalized.signum() < 0 || normalized > MAX_QUANTITY || normalized.scale() > MAX_QUANTITY_SCALE) {
            reject(path, "Informe a quantidade de 0 a $MAX_QUANTITY, com até $MAX_QUANTITY_SCALE casas.")
        }
        return normalized
    }

    private fun number(
        path: String,
        value: BigDecimal,
    ): BigDecimal {
        val normalized = normalized(value)
        if (normalized.abs() > MAX_NUMBER || normalized.scale() > MAX_NUMBER_SCALE) {
            reject(path, "Informe o número até $MAX_NUMBER (em módulo), com até $MAX_NUMBER_SCALE casas.")
        }
        return normalized
    }

    private fun normalized(value: BigDecimal): BigDecimal =
        value.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

    private val FIELD_OF =
        mapOf(
            ChecklistItemType.CHECK to "check",
            ChecklistItemType.QUANTITY to "quantity",
            ChecklistItemType.NUMBER to "number",
            ChecklistItemType.TEXT to "text",
            ChecklistItemType.SINGLE_CHOICE to "choice",
            ChecklistItemType.MULTI_CHOICE to "choices",
            ChecklistItemType.PHOTO to "photoFileId",
        )

    private fun reject(
        field: String,
        message: String,
        code: ErrorCode = ChecklistsErrorCodes.CHECKLIST_ANSWER_INVALID,
    ): Nothing = throw InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))
}
