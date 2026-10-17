// Convenção do projeto (CLAUDE.md): os eventos de um módulo ficam em <Módulo>Events.kt, na raiz.
@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package com.zeny.hotel.guests

import java.time.Instant
import java.util.UUID

/**
 * O perfil [mergedId] foi absorvido pelo [survivorId] numa mesclagem de duplicados (RF-HOS-05, H6 do M3). Publicado
 * por `guests` pelo outbox, na transação da mesclagem (a mesclagem desfeita não publica nada).
 *
 * **Todo módulo que guarda o id de um hóspede escuta este evento** (`@ApplicationModuleListener`) e reponta as
 * próprias referências de [mergedId] para [survivorId] (o primeiro é `reservations`, no M4). O listener precisa ser
 * idempotente (o outbox pode entregar mais de uma vez): repontar o que já foi repontado não muda nada. Um perfil
 * mesclado nunca volta a ser ativo nem é mesclado de novo, mas o sobrevivente pode ser absorvido depois por outro
 * (cadeia A → B → C, um evento por mesclagem); quem lê um id antigo pela `GuestsApi.guest` vê o `mergedInto`.
 *
 * Só ids e o instante; nada de dado pessoal nem do autor (ficam no perfil e na trilha, consultados com permissão).
 */
data class GuestsMerged(
    val survivorId: UUID,
    val mergedId: UUID,
    val mergedAt: Instant,
)
