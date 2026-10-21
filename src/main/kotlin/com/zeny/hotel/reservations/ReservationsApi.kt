package com.zeny.hotel.reservations

import com.zeny.hotel.rooms.RoomTypeId
import java.time.LocalDate

/**
 * API pública do `reservations` para os outros módulos (sem checar permissão: quem chama já se autorizou).
 *
 * Inventário (M4 E05): a leitura dos contadores e a recontagem, que o fechamento do dia (M6) chama para reparar
 * qualquer divergência (uma publicação do outbox abandonada, por exemplo). Reservas (E07): a criação ([createBooking])
 * para o walk-in (M5), o channel manager (M8) e os eventos (M10), e as leituras ([booking], [reservation]); desde a
 * E08, o cancelamento ([cancelReservation]).
 */
interface ReservationsApi {
    /**
     * Cria a reserva agrupadora [booking] com as reservas dela (M4 E07; RF-RES-01 a 03 e 14), na transação de quem
     * chama (ou numa própria), auditada `reservations.booking.create` em nome do ator corrente ou do sistema, com as
     * mesmas regras de `POST /api/v1/bookings`, **menos as permissões e o step-up do desconto** (quem chama já se
     * autorizou: o channel manager grava a diária da OTA): hóspedes pela `GuestsApi` (mesclado vira o sobrevivente;
     * anonimizado `422 GUEST_ANONYMIZED`; inexistente `400 UNKNOWN_GUEST`), estadia vendável no canal da origem
     * (`RatesApi.isSellable`; recusa `422` com o código do `rates` e todos os motivos em `refusals`), preço congelado
     * por noite (`RatesApi.priceFor`, RN-14), políticas copiadas (RN-15), uma unidade do tipo tomada em cada noite
     * (`409 ROOM_TYPE_UNAVAILABLE`, nada gravado; o day use não toma, H5), a conta de cada reserva aberta
     * (`BillingApi.openFolio`, dono `RESERVATION`) e `ReservationCreated` por reserva, tudo na mesma transação.
     */
    fun createBooking(booking: NewBooking): BookingSummary

    /**
     * Cancela a reserva (M4 E08; RF-RES-06), na transação de quem chama (ou numa própria), auditada
     * `reservations.reservation.cancel` em nome do ator corrente ou do sistema, com as mesmas regras de
     * `POST /api/v1/reservations/{id}/cancel`, **menos a permissão e o step-up da isenção** (quem chama já se
     * autorizou: a expiração da pré-reserva na E09, o channel manager no M8): só uma pré-reserva ou uma confirmada
     * (`409 RESERVATION_TRANSITION_NOT_ALLOWED`); a multa pela política copiada na reserva e pelo prazo, lançada na
     * conta da reserva (`BillingApi.post`, código `CANCELLATION_FEE`, origem `MANUAL`) salvo com
     * [ReservationCancellationRequest.waivePenalty]; as unidades das noites a partir da data operacional devolvidas; o
     * histórico e `ReservationCancelled` pelo outbox, tudo na mesma transação.
     */
    fun cancelReservation(
        id: ReservationId,
        cancellation: ReservationCancellationRequest,
    ): ReservationSummary

    /** A reserva agrupadora com as reservas; `null` se não existe. */
    fun booking(id: BookingId): BookingSummary?

    /** A reserva; `null` se não existe. */
    fun reservation(id: ReservationId): ReservationSummary?

    /**
     * Os contadores do tipo de UH [roomTypeId] de [from] a [to] (inclusive, até 366 dias), em ordem de data; só as
     * datas mantidas no inventário (as de fora não aparecem).
     */
    fun inventory(
        roomTypeId: RoomTypeId,
        from: LocalDate,
        to: LocalDate,
    ): List<InventoryDay>

    /**
     * Recontagem (auditada `reservations.inventory.recount`): refaz, de [from] (padrão e mínimo: a data operacional) a
     * [to] (padrão e máximo: o fim do horizonte), o total e o OOO pela `RoomsApi` e as vendidas e bloqueadas pelas
     * reservas e bloqueios, de todos os tipos de UH ativos e dos que já têm inventário; cria as datas que faltam.
     * Publica [AvailabilityChanged] nos tipos que mudaram. Precisa de uma transação de escrita de quem chama ou abre a
     * própria.
     */
    fun recountInventory(
        from: LocalDate? = null,
        to: LocalDate? = null,
    ): InventoryRecount
}

/**
 * Os contadores de um tipo de UH numa data (RF-TAR-08 e 09): [total] de UHs ativas, [sold] a reservas, [blocked] a
 * bloqueios, [outOfOrder] UHs em OOO (o OOS não conta) e o [overbookingLimit]. [available] pode ser negativo (a data
 * está sobrevendida).
 */
data class InventoryDay(
    val roomTypeId: RoomTypeId,
    val date: LocalDate,
    val total: Int,
    val sold: Int,
    val blocked: Int,
    val outOfOrder: Int,
    val overbookingLimit: Int,
) {
    val available: Int get() = total - sold - blocked - outOfOrder + overbookingLimit
}

/** O resultado de uma recontagem: o período recontado, os tipos de UH e as linhas que mudaram. */
data class InventoryRecount(
    val from: LocalDate,
    val to: LocalDate,
    val roomTypes: Int,
    val changedRows: Int,
)
