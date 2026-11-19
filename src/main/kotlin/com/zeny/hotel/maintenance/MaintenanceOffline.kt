package com.zeny.hotel.maintenance

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
 */
object MaintenanceOffline {
    val WORK_ORDERS = OfflineSource("maintenance-work-orders", MaintenancePermissions.MANAGE)
}
