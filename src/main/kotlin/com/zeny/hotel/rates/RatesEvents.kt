// Convenção do projeto (CLAUDE.md): os eventos de um módulo ficam em <Módulo>Events.kt, na raiz.
@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package com.zeny.hotel.rates

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * As tarifas do plano [ratePlanId] mudaram (RF-TAR-10, SPEC §8.3, H1 do roadmap do M4; M4 E02). Publicado pelo `rates`
 * pelo outbox, na transação da mudança (desfeita, não publica), **só quando algo mudou**:
 *
 * - [PRICES_SET][RatesChangeKind.PRICES_SET] e [PRICES_REMOVED][RatesChangeKind.PRICES_REMOVED]: preços gravados,
 *   alterados ou retirados nos tipos [roomTypeIds], com as datas mudadas entre [from] e [to] (inclusive; dentro do
 *   intervalo, só as datas dos dias da semana pedidos);
 * - [DERIVATION_CHANGED][RatesChangeKind.DERIVATION_CHANGED]: a regra de derivação do plano foi definida, trocada ou
 *   retirada; vale de [from] (a data operacional) em diante, sem fim ([to] nulo), em todos os tipos do plano.
 *
 * Os preços dos planos derivados são calculados na leitura a partir do plano base (H1): [derivedRatePlanIds] lista
 * **todos os planos que derivam deste**, direta ou indiretamente (ativos ou não), porque os preços deles mudaram junto
 * (nos tipos que eles também vendem). Quem escuta (o `distribution`, M8, para o ARI) relê os preços pela API.
 *
 * Só ids, datas e o instante; nada do autor (fica na trilha) nem dos valores (lidos pela API).
 */
data class RatesChanged(
    val ratePlanId: UUID,
    val change: RatesChangeKind,
    val roomTypeIds: List<UUID>,
    val from: LocalDate,
    val to: LocalDate?,
    val derivedRatePlanIds: List<UUID>,
    val changedAt: Instant,
)

/** O que mudou num [RatesChanged]. */
enum class RatesChangeKind {
    PRICES_SET,
    PRICES_REMOVED,
    DERIVATION_CHANGED,
}

/**
 * As restrições de venda do plano [ratePlanId] mudaram (RF-TAR-05 e 10, SPEC §8.3; M4 E03). Publicado pelo `rates` pelo
 * outbox, na transação da mudança (desfeita, não publica), **só quando algo mudou**, numa gravação de uma data ou em
 * massa:
 *
 * - [general]: a restrição **geral** do plano (que vale para todos os tipos de UH dele) mudou; [roomTypeIds] traz então
 *   todos os tipos do plano (a restrição efetiva de cada um pode ter mudado, salvo onde a do tipo prevalece);
 * - senão, mudaram as restrições **específicas** dos tipos [roomTypeIds].
 *
 * As datas mudadas ficam entre [from] e [to] (inclusive; dentro do intervalo, só as datas dos dias da semana pedidos);
 * [fields] lista os campos que mudaram em alguma delas. As restrições são de cada plano: um plano derivado tem as
 * próprias (a derivação é só de preço), então o evento não lista derivados. Quem escuta (o `distribution`, M8, para o
 * ARI) relê as restrições efetivas pela API.
 *
 * Só ids, datas, os campos e o instante; nada do autor (fica na trilha) nem dos valores (lidos pela API).
 */
data class RestrictionsChanged(
    val ratePlanId: UUID,
    val general: Boolean,
    val roomTypeIds: List<UUID>,
    val fields: List<RestrictionField>,
    val from: LocalDate,
    val to: LocalDate,
    val changedAt: Instant,
)
