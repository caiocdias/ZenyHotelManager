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
 * (`x-extensible-enum`: a lista cresce), com a multa lançada na conta ([penaltyCharged]) ou isentada
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
