package com.zeny.hotel.checklists.application

import com.zeny.hotel.checklists.ChecklistContent
import com.zeny.hotel.checklists.ConsumeStockAction
import com.zeny.hotel.checklists.ItemAction
import com.zeny.hotel.checklists.LinenExchangeAction
import com.zeny.hotel.checklists.OpenOccurrenceAction
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
}

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
