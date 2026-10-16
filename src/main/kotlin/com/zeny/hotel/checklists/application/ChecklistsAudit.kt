package com.zeny.hotel.checklists.application

import com.zeny.hotel.checklists.ChecklistContent
import com.zeny.hotel.checklists.ConsumeStockAction
import com.zeny.hotel.checklists.ItemAction
import com.zeny.hotel.checklists.LinenExchangeAction
import com.zeny.hotel.checklists.OpenOccurrenceAction
import com.zeny.hotel.checklists.domain.ChecklistExecution
import com.zeny.hotel.checklists.domain.ChecklistRoutine
import com.zeny.hotel.checklists.domain.ChecklistTemplate
import com.zeny.hotel.checklists.domain.ChecklistVersion

/** Tipos de entidade, ações e retratos do `checklists` na trilha de auditoria (RF-ACS-11). Nenhum dado pessoal. */
internal object ChecklistsAudit {
    const val TEMPLATE = "checklists.template"
    const val VERSION = "checklists.version"

    const val CREATE_TEMPLATE = "checklists.template.create"
    const val UPDATE_TEMPLATE = "checklists.template.update"
    const val PUBLISH_TEMPLATE = "checklists.template.publish"
    const val DEACTIVATE_TEMPLATE = "checklists.template.deactivate"
    const val REACTIVATE_TEMPLATE = "checklists.template.reactivate"

    const val EXECUTION = "checklists.execution"
    const val ROUTINE = "checklists.routine"

    const val START_EXECUTION = "checklists.execution.start"
    const val ANSWER_EXECUTION = "checklists.execution.answer"
    const val COMPLETE_EXECUTION = "checklists.execution.complete"
    const val START_EXECUTION_OFFLINE = "checklists.execution.start-offline"
    const val COMPLETE_EXECUTION_OFFLINE = "checklists.execution.complete-offline"

    const val CREATE_ROUTINE = "checklists.routine.create"
    const val UPDATE_ROUTINE = "checklists.routine.update"
    const val DEACTIVATE_ROUTINE = "checklists.routine.deactivate"
    const val REACTIVATE_ROUTINE = "checklists.routine.reactivate"
    const val GENERATE_ROUTINES = "checklists.routine.generate"
}

/**
 * O retrato de uma execução na trilha: situação, versão, executor, horários e, por resposta, o tipo e o valor
 * discreto (marcação, quantidade, número, escolhas, foto); o texto livre e a observação ficam só como "tem".
 */
internal fun ChecklistExecution.auditState(): Map<String, Any?> =
    mapOf(
        "status" to status,
        "templateId" to templateId.value,
        "versionId" to versionId?.value,
        "versionNumber" to versionNumber,
        "subjectType" to subject?.type,
        "subjectId" to subject?.id,
        "routineId" to routineId?.value,
        "businessDate" to businessDate,
        "candidateIds" to candidates.map { it.value }.sorted(),
        "duplicateOf" to duplicateOf?.value,
        "executorId" to executorId?.value,
        "startedAt" to startedAt,
        "completedAt" to completedAt,
        "startCommandId" to startCommandId,
        "completeCommandId" to completeCommandId,
        "answers" to
            answers.sortedBy { it.itemKey }.associate { answer ->
                answer.itemKey to
                    mapOf(
                        "type" to answer.itemType,
                        "check" to answer.check,
                        "quantity" to answer.quantity,
                        "number" to answer.number,
                        "choice" to answer.choice,
                        "choices" to answer.choices,
                        "photoFileId" to answer.photoFileId?.value,
                        "photoMissing" to answer.photoMissing,
                        "hasText" to (answer.text != null),
                        "hasNote" to (answer.note != null),
                    )
            },
    )

internal fun ChecklistRoutine.auditState(): Map<String, Any?> =
    mapOf(
        "name" to definition.name,
        "templateId" to definition.templateId.value,
        "departmentId" to definition.departmentId.value,
        "frequency" to definition.frequency,
        "weekdays" to definition.weekdays.sorted(),
        "dueAt" to definition.dueAt.toString(),
        "locationType" to definition.location?.type,
        "locationId" to definition.location?.id,
        "active" to active,
    )

internal fun ChecklistTemplate.auditState(): Map<String, Any?> =
    mapOf(
        "name" to name,
        "context" to context,
        "active" to active,
        "publishedVersion" to publishedVersion,
        "hasUnpublishedChanges" to hasUnpublishedChanges,
        "draft" to draft.auditState(),
    )

internal fun ChecklistVersion.auditState(): Map<String, Any?> =
    mapOf("templateId" to templateId.value, "number" to number, "name" to name, "context" to context)

private fun ChecklistContent.auditState(): Map<String, Any?> =
    mapOf(
        "applicability" to
            applicability.map {
                mapOf(
                    "roomTypeIds" to it.roomTypeIds.sorted(),
                    "cleaningTypeIds" to it.cleaningTypeIds.sorted(),
                    "areaIds" to it.areaIds.sorted(),
                    "assetIds" to it.assetIds.sorted(),
                )
            },
        "sections" to
            sections.map { section ->
                mapOf(
                    "title" to section.title,
                    "items" to
                        section.items.map { item ->
                            mapOf(
                                "key" to item.key,
                                "label" to item.label,
                                "type" to item.type,
                                "required" to item.required,
                                "visibleWhen" to
                                    item.visibleWhen?.let { mapOf("itemKey" to it.itemKey, "answer" to it.answer) },
                                "options" to item.options.map { mapOf("value" to it.value, "label" to it.label) },
                                "unit" to item.unit,
                                "actions" to item.actions.map(::actionState),
                            )
                        },
                )
            },
    )

private fun actionState(action: ItemAction): Map<String, Any?> =
    when (action) {
        is ConsumeStockAction -> {
            mapOf(
                "type" to action.type,
                "stockItemId" to action.stockItemId,
                "defaultQuantity" to action.defaultQuantity,
            )
        }

        is LinenExchangeAction -> {
            mapOf("type" to action.type, "linenTypeId" to action.linenTypeId, "movement" to action.movement)
        }

        is OpenOccurrenceAction -> {
            mapOf("type" to action.type, "kind" to action.kind, "category" to action.category)
        }
    }
