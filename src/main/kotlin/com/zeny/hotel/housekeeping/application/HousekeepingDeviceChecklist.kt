package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistVersionId
import com.zeny.hotel.checklists.ChecklistVersionView
import com.zeny.hotel.checklists.ChecklistsApi
import com.zeny.hotel.checklists.ChecklistsErrorCodes
import com.zeny.hotel.housekeeping.HousekeepingErrorCodes
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.OfflineCommandWarning

// A versão do checklist que o aparelho tem nos comandos offline que abrem um checklist: o início da tarefa
// (`housekeeping.start-task`, D1-E19) e o da inspeção (`housekeeping.start-inspection`, D7-E05).

/**
 * Compara a versão do aparelho ([device]) com a vigente do modelo que vale para a tarefa ou para a inspeção
 * ([current]; D1-E19 na execução, D7-E05 na inspeção): do mesmo modelo e já substituída, `CHECKLIST_VERSION_OUTDATED`;
 * de outro modelo (ou sem modelo que valha), `HOUSEKEEPING_TASK_CHECKLIST_TEMPLATE_MISMATCH` e o modelo esperado e o
 * recebido em [remarks] (a trilha).
 */
internal fun compareDeviceVersion(
    device: ChecklistVersionView,
    current: ChecklistVersionView?,
    warnings: MutableList<OfflineCommandWarning>,
    remarks: MutableMap<String, Any?>,
) {
    if (current != null && device.templateId == current.templateId) {
        if (device.id != current.id) {
            warnings +=
                OfflineCommandWarning(
                    ChecklistsErrorCodes.CHECKLIST_VERSION_OUTDATED,
                    "O checklist usou a versão ${device.number} do modelo, já substituída; ela ficou guardada.",
                )
        }
        return
    }
    remarks["expectedChecklistTemplateId"] = current?.templateId?.toString()
    remarks["receivedChecklistTemplateId"] = device.templateId.toString()
    warnings +=
        OfflineCommandWarning(
            HousekeepingErrorCodes.HOUSEKEEPING_TASK_CHECKLIST_TEMPLATE_MISMATCH,
            "O aparelho abriu o checklist de outro modelo; a execução ficou com ele e a troca foi registrada.",
        )
}

/**
 * A versão do checklist informada pelo aparelho (D1-E19; D7-E05 na inspeção): publicada e de um modelo do [context],
 * senão `422 HOUSEKEEPING_TASK_CHECKLIST_VERSION_INVALID` (o payload vem do aparelho, entrada não confiável).
 */
internal fun ChecklistsApi.deviceVersion(
    id: ChecklistVersionId,
    context: ChecklistContext,
): ChecklistVersionView =
    version(id)?.takeIf { it.context == context }
        ?: throw BusinessRuleException(
            HousekeepingErrorCodes.HOUSEKEEPING_TASK_CHECKLIST_VERSION_INVALID,
            if (context == ChecklistContext.INSPECTION) {
                "A versão do checklist informada não existe ou não é de um modelo de inspeção."
            } else {
                "A versão do checklist informada não existe ou não é de um modelo de governança."
            },
        )
