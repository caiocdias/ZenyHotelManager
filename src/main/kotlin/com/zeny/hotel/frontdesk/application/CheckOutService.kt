package com.zeny.hotel.frontdesk.application

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.billing.CardPreAuthorizationSummary
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.FolioOwner
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.billing.FolioSummary
import com.zeny.hotel.billing.NewPosting
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.PostingSummary
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.billing.TransactionCodeKey
import com.zeny.hotel.frontdesk.FrontdeskPermissions
import com.zeny.hotel.frontdesk.domain.EarlyLateQuotes
import com.zeny.hotel.frontdesk.domain.LateCheckOutRules
import com.zeny.hotel.frontdesk.domain.StayCharge
import com.zeny.hotel.frontdesk.domain.StayCharges
import com.zeny.hotel.frontdesk.domain.StayNotSettledException
import com.zeny.hotel.frontdesk.domain.StayRules
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.rooms.RoomStatus
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.Money
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** O check-out pedido à recepção: o valor do late check-out cotado (nulo = sem late; D4). */
data class CheckOutCommand(
    val lateCheckOutFee: Money?,
)

/**
 * O resultado do check-out: a reserva encerrada, a UH vaga e suja, as cobranças da noite da chegada lançadas (H14), o
 * late lançado (nulo sem late), as cauções liberadas e as contas encerradas.
 */
data class CheckOutOutcome(
    val reservation: ReservationSummary,
    val roomNumber: String,
    val room: RoomStatus,
    val stayCharges: List<PostingSummary>,
    val lateCheckOut: PostingSummary?,
    val releasedPreAuthorizations: List<CardPreAuthorizationSummary>,
    val closedFolios: List<FolioSummary>,
)

/**
 * O check-out orquestrado da recepção (M5 E04; RF-REC-06, RN-06, RN-09, RN-11, ARCHITECTURE §6.1, H12 a H14) **numa
 * transação só**: qualquer recusa desfaz tudo (as cobranças, o late, as cauções liberadas, as contas encerradas, a
 * transição da reserva, a devolução do inventário do antecipado, o status da UH, as entradas da trilha e os eventos do
 * outbox).
 *
 * **Ordem** (e das travas, decisão D1): a permissão; a reserva lida sem trava (inexistente `404`; só uma hospedada,
 * `409 RESERVATION_TRANSITION_NOT_ALLOWED`); a data operacional (RN-01); as cobranças pendentes da noite da chegada
 * ([StayCharges.pending], só no day use ou no pernoite que sai no dia em que chegou) e o late aceito pela política
 * **vigente** no fuso do hotel ([LateCheckOutRules.acceptedFee]: `422 EARLY_LATE_NOT_OFFERED`,
 * `409 EARLY_LATE_FEE_CHANGED`), calculados sobre a reserva lida **antes** da transição e recusados antes de qualquer
 * escrita; a transição (`ReservationsApi.checkOut`, que **trava a reserva**, devolve ao inventário as noites do
 * antecipado e encerra a alocação); as cobranças (`BillingApi.post`, cada uma com a chave da noite: uma repetição nunca
 * cobra de novo, H14) e o late no código `ROOM_CHARGE`, que **travam a conta**; o saldo de todas as contas da reserva
 * pela `BillingApi.balanceOf` (nunca recalculado aqui), já com as cobranças e o late: alguma conta com saldo,
 * `409 FOLIO_BALANCE_NOT_ZERO` com o saldo de cada conta e o total (RN-06; até o M10 só se quita por pagamento, sem
 * contas a receber); as cauções não encerradas de cada conta liberadas (inclusive as vencidas e as de um check-in
 * desfeito, D6); as contas abertas encerradas; e a UH Vaga e Suja (`RoomsApi.markVacant` com a origem `CHECK_OUT`,
 * RN-09), que **trava a UH**. A reserva é travada antes da conta e da UH, como no check-in e no cancelamento: sem
 * deadlock com eles. A captura da caução é feita antes, pelo `billing`; a nota fiscal é do M7.
 */
@Service
@Suppress("LongParameterList") // as APIs públicas que o check-out orquestra
class FrontdeskCheckOutService(
    private val reservations: ReservationsApi,
    private val rooms: RoomsApi,
    private val billing: BillingApi,
    private val hotel: HotelApi,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val steps: CheckInSteps,
) {
    @Audited(FrontdeskAudit.CHECK_OUT)
    @Transactional
    fun checkOut(
        id: ReservationId,
        command: CheckOutCommand,
    ): CheckOutOutcome {
        authorization.require(FrontdeskPermissions.STAYS_CHECK_OUT)
        val reservation = steps.reservation(id)
        StayRules.requireCheckOut(reservation)
        val roomId = checkNotNull(reservation.roomAssignment) { "Hospedada sem UH alocada" }.roomId
        val businessDate = hotel.currentBusinessDate()
        val pending = StayCharges.pending(reservation, businessDate)
        val lateFee =
            LateCheckOutRules.acceptedFee(
                EarlyLateQuotes.late(hotel.policy(), reservation.departure, hotel.timezone()),
                command.lateCheckOutFee,
            )

        val checkedOut = reservations.checkOut(id)
        val roomCharge = billing.transactionCode(TransactionCodeKey.ROOM_CHARGE).id
        val stayCharges = pending.map { post(reservation.folioId, it, roomCharge) }
        val lateCheckOut =
            lateFee?.let {
                billing.post(
                    reservation.folioId,
                    NewPosting(roomCharge, it, description = LATE_CHECK_OUT, source = PostingSource.MANUAL),
                )
            }
        val (released, closed) = settle(id)
        val outcome =
            CheckOutOutcome(
                reservation = checkedOut,
                roomNumber = steps.roomNumber(roomId),
                room = rooms.markVacant(roomId, RoomStatusSource.CHECK_OUT),
                stayCharges = stayCharges,
                lateCheckOut = lateCheckOut,
                releasedPreAuthorizations = released,
                closedFolios = closed,
            )
        audit.record(
            FrontdeskAudit.STAY,
            id.value,
            mapOf("status" to reservation.status.name, "roomId" to roomId.value),
            mapOf(
                "status" to checkedOut.status.name,
                "roomId" to roomId.value,
                "postingIds" to stayCharges.map { it.id.value },
                "latePostingId" to lateCheckOut?.id?.value,
                "releasedPreAuthorizationIds" to released.map { it.id.value },
                "closedFolioIds" to closed.map { it.id.value },
                "businessDate" to businessDate,
            ),
        )
        return outcome
    }

    /** Lança a cobrança da noite da chegada com a chave dela (H14): já lançada, a `BillingApi` devolve a existente. */
    private fun post(
        folioId: FolioId,
        charge: StayCharge,
        roomCharge: TransactionCodeId,
    ): PostingSummary =
        billing.post(
            folioId,
            NewPosting(
                transactionCodeId = charge.transactionCodeId ?: roomCharge,
                unitPrice = charge.unitPrice,
                quantity = charge.quantity,
                description = charge.description,
                source = PostingSource.END_OF_DAY,
                sourceReference = charge.sourceReference,
            ),
        )

    /**
     * Quita a reserva [id]: o saldo de todas as contas dela pela `BillingApi.balanceOf`, já com as cobranças e o late
     * (alguma com saldo, `409 FOLIO_BALANCE_NOT_ZERO`); as cauções não encerradas de cada conta liberadas, na ordem das
     * contas e, na conta, por id (D6); e as contas abertas encerradas. Devolve as cauções liberadas e as contas
     * encerradas.
     */
    private fun settle(id: ReservationId): Pair<List<CardPreAuthorizationSummary>, List<FolioSummary>> {
        val balance = billing.balanceOf(FolioOwner(FolioOwnerType.RESERVATION, id.value))
        if (!balance.settled) throw StayNotSettledException(balance)
        val released =
            balance.folios.flatMap { folio ->
                billing.unsettledCardPreAuthorizations(folio.id).map {
                    billing.releaseCardPreAuthorization(folio.id, it.id)
                }
            }
        return released to balance.openFolios.map { billing.closeFolio(it.id) }
    }

    private companion object {
        /** A descrição do lançamento do late check-out (sem dado do hóspede). */
        const val LATE_CHECK_OUT = "Late check-out"
    }
}
