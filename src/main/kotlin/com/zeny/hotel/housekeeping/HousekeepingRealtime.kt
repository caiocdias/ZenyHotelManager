package com.zeny.hotel.housekeeping

import com.zeny.hotel.shared.RealtimeTopic

/**
 * O tópico `housekeeping` do canal de tempo real (ARCHITECTURE §10, D9-E03): o painel da governança. Exige
 * [HousekeepingPermissions.TASKS_READ] (só escopo `ALL`); a E04 o reaproveita para as mensagens das tarefas. As
 * mensagens levam só `type`, `id` e `version` (nada de hóspede, RN-13):
 *
 * - [ASSIGNMENT_PLAN]: o plano do dia foi publicado (`id` = o plano, `version` = a versão dele, a mesma do `ETag` de
 *   `GET /assignment-plans/{date}`); o cliente rebusca o plano e as tarefas. A sugestão e o ajuste não publicam.
 * - [TASK]: uma tarefa mudou de status pela execução (D8 da E04: `start`, `pause`, `resume`, `refuse`, `retry` e
 *   `complete`; `id` = a tarefa, `version` = a versão nova, a mesma do `ETag` da ação e de
 *   `GET /housekeeping-tasks/{id}`); o cliente rebusca a tarefa. Uma ação recusada não publica.
 */
object HousekeepingRealtime {
    val TOPIC = RealtimeTopic("housekeeping", HousekeepingPermissions.TASKS_READ)

    const val ASSIGNMENT_PLAN = "housekeeping.assignment-plan"

    const val TASK = "housekeeping.task"
}
