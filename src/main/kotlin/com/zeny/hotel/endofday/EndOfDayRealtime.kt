package com.zeny.hotel.endofday

import com.zeny.hotel.shared.RealtimeTopic

/**
 * O tópico `end-of-day` do canal de tempo real (ARCHITECTURE §10, M6 E10): o andamento do fechamento do dia. Exige
 * [EndOfDayPermissions.RUNS_READ] (só escopo `ALL`). As mensagens levam só `type`, `id` e `version` (nada de hóspede,
 * RN-13):
 *
 * - [RUN]: a execução mudou (etapa iniciada, concluída ou falha, execução concluída ou retomada); `id` = a execução,
 *   `version` = a versão dela depois da mudança. O cliente rebusca a execução, as etapas e as pendências.
 */
object EndOfDayRealtime {
    val TOPIC = RealtimeTopic("end-of-day", EndOfDayPermissions.RUNS_READ)

    const val RUN = "endofday.run"
}
