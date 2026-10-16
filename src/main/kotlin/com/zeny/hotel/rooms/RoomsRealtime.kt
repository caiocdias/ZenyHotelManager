package com.zeny.hotel.rooms

import com.zeny.hotel.shared.RealtimeTopic

/**
 * O tópico `rooms` do canal de tempo real (ARCHITECTURE §10, RNF-04): o painel de status das UHs. Exige
 * [RoomsPermissions.STATUS_READ] (só escopo `ALL`). As mensagens levam só `type`, `id` e `version`
 * (nada de hóspede, RN-13):
 *
 * - [ROOM_STATUS]: o status de uma UH mudou (`id` = a UH, `version` = a versão dela, a mesma do `ETag` de
 *   `GET /rooms/{id}/status`); o cliente rebusca o status ou o painel;
 * - [OUT_OF_ORDER]: um período OOO/OOS foi aberto, alterado ou liberado (`id` = o período, `version` = a
 *   versão dele); a sobreposição e a prontidão do painel podem ter mudado.
 */
object RoomsRealtime {
    val TOPIC = RealtimeTopic("rooms", RoomsPermissions.STATUS_READ)

    const val ROOM_STATUS = "rooms.room-status"

    const val OUT_OF_ORDER = "rooms.out-of-order"
}
