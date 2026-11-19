package com.zeny.hotel.housekeeping

import com.zeny.hotel.shared.OfflineCommandType
import com.zeny.hotel.shared.OfflineSource

/**
 * O `housekeeping` na sincronização offline (ARCHITECTURE §9 e §6.2, RN-22, M6 E19). Nomes publicados: não mudam.
 *
 * Fonte do download: [TASKS] (`housekeeping-tasks`, exige [HousekeepingPermissions.TASKS_EXECUTE]), as tarefas da
 * camareira (com `OWN`) ou todas (com `ALL`).
 *
 * Comandos do upload, a execução da tarefa (D1-E19), todos com o `taskId` e o `occurredAt` corrigido pela defasagem
 * como o instante da transição (D2-E19); a idempotência é o `commandId`, sem versão da tarefa:
 * - [START_TASK], com a versão do checklist que o aparelho tem (`checklistVersionId`, opcional);
 * - [PAUSE_TASK], [RESUME_TASK] e [RETRY_TASK], só com o `taskId`;
 * - [REFUSE_TASK], com o motivo (`reason`);
 * - [COMPLETE_TASK], com as respostas do checklist (`answers`, com o `answeredAt` de cada uma) e a ocupação encontrada
 *   (`foundOccupancy`, `foundGuestCount`).
 */
object HousekeepingOffline {
    val TASKS = OfflineSource("housekeeping-tasks", HousekeepingPermissions.TASKS_EXECUTE)

    val START_TASK = OfflineCommandType("housekeeping.start-task")
    val PAUSE_TASK = OfflineCommandType("housekeeping.pause-task")
    val RESUME_TASK = OfflineCommandType("housekeeping.resume-task")
    val COMPLETE_TASK = OfflineCommandType("housekeeping.complete-task")
    val REFUSE_TASK = OfflineCommandType("housekeeping.refuse-task")
    val RETRY_TASK = OfflineCommandType("housekeeping.retry-task")
}
