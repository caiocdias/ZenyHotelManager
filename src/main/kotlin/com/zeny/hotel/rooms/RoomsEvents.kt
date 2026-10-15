// Convenção do projeto (CLAUDE.md): os eventos de um módulo ficam em <Módulo>Events.kt, na raiz.
@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package com.zeny.hotel.rooms

import java.time.Instant
import java.util.UUID

/**
 * O status da UH [roomId] mudou (RF-UH-04 e 07, SPEC §8.3): as dimensões depois da mudança, a
 * [version] da UH (cresce a cada mudança: o consumidor descarta um evento mais velho que o que já
 * tem) e o instante [changedAt]. Publicado por `rooms` pelo outbox (Event Publication Registry) na
 * transação da mudança; os consumidores (tempo real, governança) precisam ser idempotentes.
 *
 * Payload mínimo e sem dados de hóspede (RN-13): nem nome, nem documento, nem o número de hóspedes,
 * nem quem fez a mudança (isso fica no histórico, consultado com permissão).
 */
data class RoomStatusChanged(
    val roomId: UUID,
    val occupancy: OccupancyStatus,
    val housekeepingStatus: HousekeepingStatus,
    val inspectionPending: Boolean,
    val serviceFlag: GuestServiceFlag,
    val version: Long,
    val changedAt: Instant,
)
