package com.zeny.hotel.maintenance

import com.zeny.hotel.shared.OfflineCommandType
import com.zeny.hotel.shared.OfflineSource

/**
 * O `maintenance` na sincronização offline (ARCHITECTURE §9, M6 E08). Nomes publicados: não mudam.
 *
 * Fonte do download: [WORK_ORDERS] (`maintenance-work-orders`, exige [MaintenancePermissions.MANAGE], como a fonte de
 * execução da governança): as ordens de serviço **não finais**, com `OWN` só as que o usuário abriu ou de que o
 * colaborador dele é o responsável, ou todas com `ALL`. Cada item leva `number`, o local (`roomId` ou `areaId`, com o
 * `roomNumber` ou o `areaName` do momento: renomear depois deixa o aparelho defasado até a próxima mudança da ordem),
 * `category`, `priority`, `status`, a `description` (texto livre do operador, que o técnico precisa para trabalhar;
 * sem dado de hóspede, RN-13), o responsável (`assigneeEmployeeId`) e o prazo (`dueAt`), com a versão da ordem na
 * mudança. Ficam de fora o motivo da espera, as notas de conclusão, o terceiro e os custos. A ordem final vai como
 * *tombstone*, e a reatribuída (ou que perdeu o responsável) também, só ao responsável anterior.
 *
 * Comandos do upload (D5-E08), pelos mesmos casos de uso do online, sem a versão da ordem, com o `occurredAt` corrigido
 * como o instante (nunca antes da última mudança da ordem) e a trilha `<verbo>-offline`:
 * - [OPEN_WORK_ORDER], com o corpo do `POST /work-orders` (local, categoria, prioridade, descrição, origem, prazo); o
 *   `commandId` é o id da ordem (um id que já é de uma ordem dá `REJECTED WORK_ORDER_ID_IN_USE`); exige
 *   [MaintenancePermissions.REQUEST];
 * - [START_WORK_ORDER], [HOLD_WORK_ORDER] (`reason`), [RESUME_WORK_ORDER] e [COMPLETE_WORK_ORDER] (`notes` e os custos
 *   como texto decimal, opcionais; libera o bloqueio da UH como o online), todos com o `workOrderId`; exigem
 *   [MaintenancePermissions.MANAGE] no escopo do usuário;
 * - [ADD_WORK_ORDER_NOTE], a observação (`workOrderId` e `text`, de 1 a 1000); o `commandId` é o id da observação;
 *   `manage`, ou `request` na ordem que o usuário abriu.
 *
 * O offline não abre o bloqueio da UH nem cancela (pedem aprovação de superior, online). A recusa (ordem final, fora do
 * escopo, transição inválida, texto) é `REJECTED` com o código do online, sem registro de conflito.
 */
object MaintenanceOffline {
    val WORK_ORDERS = OfflineSource("maintenance-work-orders", MaintenancePermissions.MANAGE)

    val OPEN_WORK_ORDER = OfflineCommandType("maintenance.open-work-order")
    val START_WORK_ORDER = OfflineCommandType("maintenance.start-work-order")
    val HOLD_WORK_ORDER = OfflineCommandType("maintenance.hold-work-order")
    val RESUME_WORK_ORDER = OfflineCommandType("maintenance.resume-work-order")
    val COMPLETE_WORK_ORDER = OfflineCommandType("maintenance.complete-work-order")
    val ADD_WORK_ORDER_NOTE = OfflineCommandType("maintenance.add-work-order-note")
}
