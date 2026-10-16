package com.zeny.hotel.rooms

import com.zeny.hotel.shared.OfflineSource

/**
 * A fonte `rooms` do download offline (`GET /api/v1/sync/changes`, ARCHITECTURE §9, M2 E05): as UHs ativas,
 * com só o que o app operacional precisa (RN-13): `number`, `floor`, `occupancy`, `housekeepingStatus`,
 * `inspectionPending` e `serviceFlag`, mais o id e a versão da UH na mudança. Nada de número de hóspedes,
 * observações nem dado de hóspede. Uma UH desativada vai como *tombstone*. Exige
 * [RoomsPermissions.OFFLINE_READ] (só `ALL`).
 */
object RoomsOffline {
    val SOURCE = OfflineSource("rooms", RoomsPermissions.OFFLINE_READ)
}
