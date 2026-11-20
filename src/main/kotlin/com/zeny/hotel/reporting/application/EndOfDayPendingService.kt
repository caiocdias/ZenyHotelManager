package com.zeny.hotel.reporting.application

import com.zeny.hotel.reporting.ReportingPermissions
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.Money
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Uma conta aberta de reserva encerrada com saldo (positivo: o hóspede deve; negativo: o hotel deve). */
data class PendingFolio(
    val folioId: UUID,
    val folioNumber: String,
    val reservationId: UUID,
    val reservationCode: String,
    val departure: LocalDate,
    val balance: Money,
)

/** Um turno de caixa aberto. */
data class PendingCashierShift(
    val shiftId: UUID,
    val shiftNumber: String,
    val cashPointName: String,
    val operatorId: UUID,
    val openedOn: LocalDate,
    val openedAt: Instant,
)

/** Uma submissão à FNRH que ainda não foi enviada com sucesso. */
data class PendingFnrhSubmission(
    val operation: String,
    val status: String,
    val reservationId: UUID,
    val reservationCode: String?,
    val attempts: Int,
    val lastFailure: String?,
    val createdAt: Instant,
)

/** As pendências do fechamento do dia. */
data class EndOfDayPending(
    val folios: List<PendingFolio>,
    val cashierShifts: List<PendingCashierShift>,
    val fnrhSubmissions: List<PendingFnrhSubmission>,
)

/**
 * As pendências do fechamento do dia (RF-REL, M6 E09, D4), no estado corrente: contas abertas de reservas já
 * encerradas com saldo, turnos de caixa abertos e submissões à FNRH `PENDING`, `FAILED` e `INVALID`. Só números,
 * códigos e ids, sem nome de hóspede (RN-13). Exige `reporting.pending.read`.
 */
@Service
class EndOfDayPendingService(
    private val queries: ReportingQueries,
    private val authorization: Authorization,
) {
    @Transactional(readOnly = true)
    fun pending(): EndOfDayPending {
        authorization.require(ReportingPermissions.PENDING_READ)
        return EndOfDayPending(
            folios =
                queries.openFolioBalances().map {
                    PendingFolio(
                        it.folioId,
                        it.folioNumber,
                        it.reservationId,
                        it.reservationCode,
                        it.departure,
                        Money.of(it.balance),
                    )
                },
            cashierShifts =
                queries.openCashierShifts().map {
                    PendingCashierShift(
                        it.shiftId,
                        it.shiftNumber,
                        it.cashPointName,
                        it.operatorId,
                        it.openedOn,
                        it.openedAt,
                    )
                },
            fnrhSubmissions =
                queries.pendingFnrhSubmissions().map {
                    PendingFnrhSubmission(
                        it.operation,
                        it.status,
                        it.reservationId,
                        it.reservationCode,
                        it.attempts,
                        it.lastFailure,
                        it.createdAt,
                    )
                },
        )
    }
}
