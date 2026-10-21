// Convenção do projeto (CLAUDE.md): os eventos de um módulo ficam em <Módulo>Events.kt, na raiz.
@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package com.zeny.hotel.rooms

import java.time.Instant
import java.time.LocalDate
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

/** O que aconteceu com um período OOO/OOS ([OutOfOrderChanged]). */
enum class OutOfOrderAction {
    OPENED,
    UPDATED,
    RELEASED,
}

/**
 * Um período OOO/OOS da UH [roomId] foi aberto, alterado ou liberado ([action]; RF-UH-05, SPEC §8.3).
 * Publicado por `rooms` pelo outbox na transação da mudança; o inventário (M4) e o channel manager (M8)
 * recalculam a disponibilidade e alertam as reservas alocadas no período (RN-07), e precisam ser
 * idempotentes (descartam um evento com [version] menor que a que já viram).
 *
 * Leva o período inteiro, para o consumidor decidir: o planejado ([startsOn] a [endsOn], inclusive), a
 * data operacional da liberação ([releasedOn]) e o último dia bloqueado de fato ([blockedUntil]; `null`
 * = nenhum, liberado antes de começar); numa alteração, também o período anterior ([previousStartsOn] a
 * [previousEndsOn]), para recalcular os dias que deixaram de ser bloqueados. Sem motivo, responsável nem
 * autor (RN-13; ficam no período e na trilha, consultados com permissão).
 */
data class OutOfOrderChanged(
    val outOfOrderId: UUID,
    val roomId: UUID,
    val kind: OutOfOrderKind,
    val action: OutOfOrderAction,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
    val releasedOn: LocalDate?,
    val blockedUntil: LocalDate?,
    val previousStartsOn: LocalDate?,
    val previousEndsOn: LocalDate?,
    val version: Long,
    val changedAt: Instant,
)

/** O que mudou no cadastro de uma UH ([RoomRegistryChanged]). */
enum class RoomRegistryChange {
    /** A UH foi cadastrada (nasce ativa). */
    CREATED,

    /** A UH mudou de tipo ([RoomRegistryChanged.previousRoomTypeId] é o anterior). */
    ROOM_TYPE_CHANGED,

    /** A UH foi desativada. */
    DEACTIVATED,

    /** A UH foi reativada. */
    REACTIVATED,
}

/**
 * O cadastro da UH [roomId] mudou de um jeito que afeta o inventário por tipo (H4 do roadmap do M4, M4 E05): criação,
 * troca de tipo, desativação ou reativação ([change]). Leva o tipo depois da mudança ([roomTypeId]), o anterior numa
 * troca de tipo ([previousRoomTypeId], senão `null`), se a UH ficou [active], a [version] da UH e o instante
 * [changedAt]. Publicado por `rooms` pelo outbox na transação do cadastro (outras edições, como o número ou as
 * características, não publicam). O `reservations` o escuta e recalcula o total dos tipos afetados a partir da
 * [RoomsApi] (idempotente: um evento reentregue ou fora de ordem não muda o resultado).
 *
 * Payload mínimo: só ids, a situação, a versão e o instante; nada de número, andar, observações nem autor.
 */
data class RoomRegistryChanged(
    val roomId: UUID,
    val roomTypeId: UUID,
    val previousRoomTypeId: UUID?,
    val active: Boolean,
    val change: RoomRegistryChange,
    val version: Long,
    val changedAt: Instant,
)
