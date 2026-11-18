package com.zeny.hotel.housekeeping

import com.zeny.hotel.shared.RealtimeTopic

/**
 * O tópico `housekeeping` do canal de tempo real (ARCHITECTURE §10, D9-E03): o painel da governança. Exige
 * [HousekeepingPermissions.TASKS_READ] (só escopo `ALL`); a E04 o reaproveita para as mensagens das tarefas. As
 * mensagens levam só `type`, `id` e `version` (nada de hóspede, RN-13):
 *
 * - [ASSIGNMENT_PLAN]: o plano do dia foi publicado (`id` = o plano, `version` = a versão dele, a mesma do `ETag` de
 *   `GET /assignment-plans/{date}`); o cliente rebusca o plano e as tarefas. A sugestão e o ajuste não publicam.
 */
object HousekeepingRealtime {
    val TOPIC = RealtimeTopic("housekeeping", HousekeepingPermissions.TASKS_READ)

    const val ASSIGNMENT_PLAN = "housekeeping.assignment-plan"
}
