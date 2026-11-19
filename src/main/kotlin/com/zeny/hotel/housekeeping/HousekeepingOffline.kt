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
 *
 * A inspeção (M6 E05, D7): a fonte [INSPECTIONS] (`housekeeping-inspections`, exige
 * [HousekeepingPermissions.INSPECTIONS_PERFORM], só `ALL`) com as tarefas `AWAITING_INSPECTION`, e os comandos, pelo
 * mesmo caso de uso da inspeção online, com o `occurredAt` corrigido como o instante:
 * - [START_INSPECTION], com o `taskId` e a versão do checklist de inspeção que o aparelho tem (`checklistVersionId`,
 *   opcional); o `commandId` é o id da inspeção;
 * - [COMPLETE_INSPECTION], com o `taskId`, o `result`, as respostas (`answers`) e os itens reprovados
 *   (`rejectedItems`); sem início, numa tarefa sem modelo de inspeção, o `commandId` é o id da inspeção;
 * - [INSPECT_ROOM], a inspeção voluntária, com o `roomId`; o `commandId` é o id da inspeção.
 *
 * O registro de ocorrências (M6 E06, D6), pela camareira com `housekeeping.occurrences.report` (qualquer escopo), com o
 * `commandId` como id do registro e o `occurredAt` corrigido como o instante do fato; sem fonte de download (o aparelho
 * só envia), e a revisão, a cobrança e o destino ficam online:
 * - [REPORT_LOST_FOUND], o achado: `roomId` **ou** `areaId`, `description` e, opcional, `storagePlace`;
 * - [REPORT_DAMAGE], o dano: `roomId` **ou** `areaId`, `description` e, opcional, `estimatedCost` (texto decimal como
 *   na API online, até 2 casas: número JSON ou mais casas dão `COMMAND_PAYLOAD_INVALID`; de 0 a 99999.99). O resultado
 *   não leva a reserva vinculada (RN-13).
 */
object HousekeepingOffline {
    val TASKS = OfflineSource("housekeeping-tasks", HousekeepingPermissions.TASKS_EXECUTE)

    val START_TASK = OfflineCommandType("housekeeping.start-task")
    val PAUSE_TASK = OfflineCommandType("housekeeping.pause-task")
    val RESUME_TASK = OfflineCommandType("housekeeping.resume-task")
    val COMPLETE_TASK = OfflineCommandType("housekeeping.complete-task")
    val REFUSE_TASK = OfflineCommandType("housekeeping.refuse-task")
    val RETRY_TASK = OfflineCommandType("housekeeping.retry-task")

    val INSPECTIONS = OfflineSource("housekeeping-inspections", HousekeepingPermissions.INSPECTIONS_PERFORM)

    val START_INSPECTION = OfflineCommandType("housekeeping.start-inspection")
    val COMPLETE_INSPECTION = OfflineCommandType("housekeeping.complete-inspection")
    val INSPECT_ROOM = OfflineCommandType("housekeeping.inspect-room")

    val REPORT_LOST_FOUND = OfflineCommandType("housekeeping.report-lost-found")
    val REPORT_DAMAGE = OfflineCommandType("housekeeping.report-damage")
}
