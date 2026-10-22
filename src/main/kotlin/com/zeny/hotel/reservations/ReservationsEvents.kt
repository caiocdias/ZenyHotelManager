// Convenção do projeto (CLAUDE.md): os eventos de um módulo ficam em <Módulo>Events.kt, na raiz.
@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package com.zeny.hotel.reservations

import com.zeny.hotel.rates.SalesChannel
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A disponibilidade do tipo de UH [roomTypeId] mudou em alguma data de [from] a [to] (inclusive; SPEC §8.3, RF-TAR-08):
 * um contador do inventário mudou (vendidas, bloqueadas, total de UHs ativas, OOO ou o limite de overbooking) ou o
 * horizonte ganhou datas. Publicado por `reservations` pelo outbox na transação da mudança, uma vez por tipo e por
 * transação, só quando algo mudou. O channel manager (M8) o escuta para enviar a disponibilidade (ARI) e relê os
 * contadores do intervalo (a mensagem não traz os números: um evento reentregue ou fora de ordem não envia um valor
 * velho).
 */
data class AvailabilityChanged(
    val roomTypeId: UUID,
    val from: LocalDate,
    val to: LocalDate,
    val changedAt: Instant,
)

/**
 * A reserva [reservationId] da agrupadora [bookingId] foi criada (SPEC §8.3; M4 E07), na [status] inicial
 * (`PROVISIONAL` ou `CONFIRMED`), no tipo de UH [roomTypeId] e no plano [ratePlanId], de [arrival] a [departure]
 * (iguais no day use), para [adults] adultos e [children] crianças, vendida no [channel] pela [origin]. Publicado por
 * `reservations` pelo outbox na transação da criação, um por reserva. Só ids, datas, contagens e situações: nada de
 * hóspede, pedido especial nem preço (quem consome relê a reserva pela `ReservationsApi`). Escutado pela distribuição
 * (M8), pela FNRH (M5) e pela governança (M6).
 */
data class ReservationCreated(
    val reservationId: UUID,
    val bookingId: UUID,
    val status: ReservationStatus,
    val roomTypeId: UUID,
    val ratePlanId: UUID,
    val arrival: LocalDate,
    val departure: LocalDate,
    val dayUse: Boolean,
    val adults: Int,
    val children: Int,
    val origin: ReservationOrigin,
    val channel: SalesChannel,
    val createdAt: Instant,
)

/**
 * A reserva [reservationId] foi alterada (SPEC §8.3; M4 E08, RF-RES-05): a estadia, a ocupação, o plano ou o segmento
 * ([changedFields], os nomes dos campos da reserva). Traz a situação, a estadia de agora e a anterior (tipo de UH,
 * plano, chegada e saída), as contagens e a [version] depois da alteração. Publicado por `reservations` pelo outbox na
 * transação da alteração, só quando algo mudou. Sem hóspede, pedido especial nem preço. Escutado pela distribuição (M8)
 * e pela FNRH (M5).
 */
data class ReservationChanged(
    val reservationId: UUID,
    val bookingId: UUID,
    val status: ReservationStatus,
    val roomTypeId: UUID,
    val previousRoomTypeId: UUID,
    val ratePlanId: UUID,
    val previousRatePlanId: UUID,
    val arrival: LocalDate,
    val previousArrival: LocalDate,
    val departure: LocalDate,
    val previousDeparture: LocalDate,
    val dayUse: Boolean,
    val adults: Int,
    val children: Int,
    val changedFields: List<String>,
    val version: Long,
    val changedAt: Instant,
)

/**
 * A reserva [reservationId] foi cancelada (SPEC §8.3; M4 E08, RF-RES-06) a partir da [previousStatus], pelo [reason]
 * (`x-extensible-enum`: a lista cresce; desde a E09, `EXPIRED` quando a pré-reserva venceu sem garantia e o job de
 * expiração a cancelou, sem multa), com a multa lançada na conta ([penaltyCharged]) ou isentada
 * ([penaltyWaived]). As unidades das noites a partir da data operacional voltaram ao inventário. Publicado por
 * `reservations` pelo outbox na transação do cancelamento. Sem hóspede, observação nem valores. Escutado pela
 * distribuição (M8) e pela governança (M6).
 */
data class ReservationCancelled(
    val reservationId: UUID,
    val bookingId: UUID,
    val previousStatus: ReservationStatus,
    val roomTypeId: UUID,
    val arrival: LocalDate,
    val departure: LocalDate,
    val dayUse: Boolean,
    val reason: CancellationReason,
    val penaltyCharged: Boolean,
    val penaltyWaived: Boolean,
    val version: Long,
    val cancelledAt: Instant,
)

/**
 * A reserva [reservationId], cancelada ou no-show ([previousStatus]), foi reativada e está confirmada de novo (SPEC
 * §8.4; M4 E08), com as unidades das noites a partir da data operacional tomadas outra vez. Publicado por
 * `reservations` pelo outbox na transação da reativação.
 */
data class ReservationReactivated(
    val reservationId: UUID,
    val bookingId: UUID,
    val previousStatus: ReservationStatus,
    val roomTypeId: UUID,
    val arrival: LocalDate,
    val departure: LocalDate,
    val dayUse: Boolean,
    val version: Long,
    val reactivatedAt: Instant,
)

/**
 * A pré-reserva [reservationId] foi confirmada com a garantia [guaranteeMethod] (SPEC §8.4 "garantia recebida"; M4 E09,
 * RF-RES-08 no recorte manual) e está `CONFIRMED`, sem validade. O inventário não muda (a pré-reserva já tinha as
 * unidades). Publicado por `reservations` pelo outbox na transação da confirmação. Sem hóspede nem valores. Escutado
 * pela distribuição (M8).
 */
data class ReservationConfirmed(
    val reservationId: UUID,
    val bookingId: UUID,
    val roomTypeId: UUID,
    val arrival: LocalDate,
    val departure: LocalDate,
    val dayUse: Boolean,
    val guaranteeMethod: GuaranteeMethod,
    val version: Long,
    val confirmedAt: Instant,
)

// --- Hospedagem (M5 E01) ------------------------------------------------------------------------------------------
//
// Os eventos de hospedagem levam `performedBy` (o id opaco do usuário que fez a operação; nulo = sistema), exceção
// justificada à convenção "sem autor" dos eventos do módulo (H16 do M5): a FNRH (E08) exige o solicitante. Nada de
// hóspede: só ids, datas, contagens e situações (quem consome relê a reserva pela `ReservationsApi`).

/**
 * O check-in da reserva [reservationId] foi feito (SPEC §8.3, RF-REC-01): a reserva está `IN_HOUSE` na UH [roomId], do
 * tipo [roomTypeId], de [arrival] a [departure], com [guestCount] hóspedes ([adults] adultos e [children] crianças
 * reservados), na data operacional [businessDate]. Publicado por `reservations` pelo outbox na transação do check-in.
 * Escutado pela FNRH (M5 E08) e pela governança (M6).
 */
data class GuestCheckedIn(
    val reservationId: UUID,
    val bookingId: UUID,
    val roomId: UUID,
    val roomTypeId: UUID,
    val arrival: LocalDate,
    val departure: LocalDate,
    val dayUse: Boolean,
    val adults: Int,
    val children: Int,
    val guestCount: Int,
    val businessDate: LocalDate,
    val performedBy: UUID?,
    val version: Long,
    val checkedInAt: Instant,
)

/**
 * O check-in da reserva [reservationId] foi desfeito na mesma data operacional [businessDate] (SPEC §8.4, ação
 * sensível no `frontdesk`): a reserva voltou a `CONFIRMED`, com a UH [roomId] ainda alocada. Publicado por
 * `reservations` pelo outbox na transação. Escutado pela FNRH (M5 E08) e pela governança (M6).
 */
data class GuestCheckInUndone(
    val reservationId: UUID,
    val bookingId: UUID,
    val roomId: UUID,
    val businessDate: LocalDate,
    val performedBy: UUID?,
    val version: Long,
    val undoneAt: Instant,
)

/**
 * O check-out da reserva [reservationId] foi feito (SPEC §8.3, RF-REC-06) na data operacional [businessDate]: a
 * reserva está `CHECKED_OUT` e a alocação da UH [roomId] foi encerrada. No check-out antecipado ([early]), a saída
 * passou de [previousDeparture] a [departure] e as noites seguintes voltaram ao inventário. Publicado por
 * `reservations` pelo outbox na transação. Escutado pela FNRH (M5 E08) e pela governança (M6).
 */
data class GuestCheckedOut(
    val reservationId: UUID,
    val bookingId: UUID,
    val roomId: UUID,
    val roomTypeId: UUID,
    val arrival: LocalDate,
    val departure: LocalDate,
    val previousDeparture: LocalDate,
    val early: Boolean,
    val businessDate: LocalDate,
    val performedBy: UUID?,
    val version: Long,
    val checkedOutAt: Instant,
)

/**
 * O hospedado da reserva [reservationId] trocou da UH [fromRoomId] para a [toRoomId] (SPEC §8.3, RF-REC-04) na data
 * operacional [businessDate], pelo [reason], com a tarifa das noites restantes mantida ou recalculada ([tariff]).
 * Publicado por `reservations` pelo outbox na transação. Escutado pela governança (M6: a UH de origem fica para
 * limpeza).
 */
data class RoomMoved(
    val reservationId: UUID,
    val bookingId: UUID,
    val fromRoomId: UUID,
    val toRoomId: UUID,
    val roomTypeId: UUID,
    val businessDate: LocalDate,
    val reason: RoomAssignmentReason,
    val tariff: RoomMoveTariff,
    val performedBy: UUID?,
    val version: Long,
    val movedAt: Instant,
)
