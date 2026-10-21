package com.zeny.hotel.reservations

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UserId
import java.time.Instant
import java.time.LocalDate

/**
 * Como a garantia de uma pré-reserva (ou de uma reserva confirmada sem garantia) foi recebida na confirmação (M4
 * E09; RF-RES-08 no recorte manual, até o M7): o cartão **informado** pelo hóspede e registrado por quem opera (os
 * dados do cartão nunca entram no sistema; a tokenização é do M7) ou o **depósito** pago na conta da reserva,
 * conferido pelo `billing`. Lista que cresce (o M7 acrescenta o cartão tokenizado e o Pix): nas respostas é
 * `x-extensible-enum`.
 */
enum class GuaranteeMethod {
    CARD,
    DEPOSIT,
}

/**
 * A garantia registrada na confirmação da pré-reserva, ou depois numa reserva confirmada sem garantia (M4 E09): o
 * [method], quando e por quem ([confirmedBy]; nulo =
 * sistema) e, no depósito, o exigido pela política copiada ([requiredDeposit]) e o total líquido de depósitos da conta
 * da reserva naquele momento ([depositReceived], que cobria o exigido). Um reembolso posterior do depósito não desfaz a
 * confirmação.
 */
data class ReservationGuarantee(
    val method: GuaranteeMethod,
    val confirmedAt: Instant,
    val confirmedBy: UserId?,
    val requiredDeposit: Money?,
    val depositReceived: Money?,
)

/**
 * O que um item da lista de garantias pendentes é (M4 E09): uma pré-reserva ([PROVISIONAL]: vence na validade e o job a
 * cancela) ou uma reserva confirmada **sem garantia** ([UNGUARANTEED]: a política copiada exige cartão ou depósito e
 * nenhuma garantia foi registrada, como depois da troca para um plano com garantia na alteração ou da reativação de
 * uma pré-reserva expirada, ou a registrada é de outro meio, como a garantida por cartão levada a um plano de depósito;
 * não expira, só alerta). Nas respostas é `x-extensible-enum`.
 */
enum class PendingGuaranteeKind {
    PROVISIONAL,
    UNGUARANTEED,
}

/**
 * Uma reserva que ainda deve a garantia e vence num intervalo (M4 E09; RF-RES-08 "alerta de pré-reservas e reservas
 * sem garantia perto do vencimento", para o painel do M6): o [kind], o vencimento ([dueAt]: na pré-reserva, a
 * validade; na reserva sem garantia, o prazo da política no horário de check-in do hotel, no fuso do hotel: o dia do
 * prazo do depósito na política `DEPOSIT`, o dia da chegada na política `CARD`; [overdue] quando já passou), a estadia,
 * o total, a garantia que a política copiada pede e, no depósito, o exigido, o total líquido de depósitos já recebidos
 * na conta (pela `BillingApi`) e o prazo da política para o depósito ([depositDueOn]). Sem dado de hóspede além do id
 * do titular.
 */
data class PendingGuarantee(
    val reservationId: ReservationId,
    val code: DocumentNumber,
    val bookingId: BookingId,
    val holderGuestId: GuestId,
    val kind: PendingGuaranteeKind,
    val status: ReservationStatus,
    val dueAt: Instant,
    val overdue: Boolean,
    val provisionalUntil: Instant?,
    val roomTypeId: RoomTypeId,
    val ratePlanId: RatePlanId,
    val arrival: LocalDate,
    val departure: LocalDate,
    val total: Money,
    val guaranteeTerms: GuaranteeTerms,
    val folioId: FolioId,
    val requiredDeposit: Money?,
    val depositReceived: Money?,
    val depositDueOn: LocalDate?,
)
