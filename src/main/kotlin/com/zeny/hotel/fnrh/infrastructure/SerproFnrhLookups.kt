package com.zeny.hotel.fnrh.infrastructure

import com.zeny.hotel.fnrh.application.FnrhRemoteGuestRef
import com.zeny.hotel.fnrh.domain.FnrhDocumentType
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import tools.jackson.databind.JsonNode

// A leitura das respostas das consultas do Serpro. As respostas trazem dado pessoal: cada função lê só os ids.

/** O `tipo` do documento na API v2.4. */
internal fun documentTypeId(type: FnrhDocumentType): String = if (type == FnrhDocumentType.CPF) "CPF" else "PASSAPORTE"

/** O Serpro devolveu uma página da qual não se pode dizer qual item é a reserva pedida. */
internal class AmbiguousMatchException : RuntimeException()

/**
 * Os `reserva_id` da página que são da reserva [reservationCode], sem repetição; `null` se `dados` não é uma lista.
 * Falha fechado (I2, D-E08-6): os itens com `numero_reserva` igual ao código valem; um item sem `numero_reserva` só
 * vale se for o único da página e o `pagination.TotalRegistros`, quando vem, for 1. Fora disso, com mais de um id
 * casando ou com um item considerado sem `reserva_id` válido, lança [AmbiguousMatchException], sem escolher item: se
 * o Serpro ignorasse o filtro, ou tivesse a reserva duas vezes, ela seria ligada ao id de outra.
 */
internal fun reservationIdsFor(
    tree: JsonNode,
    reservationCode: String,
): List<String>? {
    val items = tree.get("dados")?.takeIf { it.isArray } ?: return null
    val (numbered, unnumbered) = items.partition { it.get("numero_reserva")?.isNull == false }
    val matched = numbered.filter { matchesCode(it, reservationCode) }
    val total = tree.get("pagination")?.get("TotalRegistros")
    // Sem `pagination` (ou sem o total), um item só basta: o total não pode desmentir o que não foi dito.
    val onlyOne =
        items.size() == 1 && (total == null || total.isNull || (total.isIntegralNumber && total.asLong() == 1L))
    if (matched.isEmpty() && unnumbered.isNotEmpty() && !onlyOne) incomplete()
    // Um item considerado sem `reserva_id` válido não diz que a reserva não existe: falha fechado.
    val ids = (matched.ifEmpty { unnumbered }).map { it.text("reserva_id") ?: incomplete() }.distinct()
    if (ids.size > 1) incomplete()
    return ids
}

/**
 * Uma resposta de consulta ambígua ou que não traz o esperado: o resultado é incerto e falha fechado
 * (`UNKNOWN_OUTCOME`).
 */
private fun incomplete(): Nothing = throw AmbiguousMatchException()

/**
 * Os hóspedes de `GET /reservas/{id}/hospedes`: só `hospede.hospede_id` e `hospede.pessoa_id` de cada item. Falha
 * fechado: sem `dados` em lista, ou com item sem os dois ids, lança [AmbiguousMatchException] (`UNKNOWN_OUTCOME`).
 */
internal fun guestRefsOf(tree: JsonNode): List<FnrhRemoteGuestRef> {
    val items = tree.get("dados")?.takeIf { it.isArray } ?: incomplete()
    return items.toList().map { item ->
        val guest = item.get("hospede")
        val guestId = guest?.text("hospede_id")
        val personId = guest?.text("pessoa_id")
        if (guestId == null || personId == null) incomplete()
        FnrhRemoteGuestRef(FnrhRemoteId(guestId), FnrhRemoteId(personId))
    }
}

/**
 * O `dados.id` de `GET /pessoas/documento/…` (lista vazia se `dados` não tem a chave `id`, isto é, a pessoa não está
 * cadastrada neste meio de hospedagem). Sem `dados` como objeto, ou com `id` que não é texto não vazio, lança
 * [AmbiguousMatchException] (`UNKNOWN_OUTCOME`). Nenhum outro campo é lido.
 */
internal fun personIdsOf(tree: JsonNode): List<String> {
    val data = tree.get("dados")?.takeIf { it.isObject } ?: incomplete()
    if (data.get("id") == null) return emptyList()
    // `id` presente mas vazio, nulo ou não-texto: não se sabe se a pessoa existe; criar duplicaria (falha fechado).
    return listOf(data.text("id") ?: incomplete())
}
