package com.zeny.hotel.reservations.application

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.rates.GuaranteeType
import com.zeny.hotel.reservations.PendingGuarantee
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationsErrorCodes
import com.zeny.hotel.reservations.ReservationsPermissions
import com.zeny.hotel.reservations.domain.ProvisionalValidity
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * A expiração de uma pré-reserva (M4 E09; RN-23), um caso de uso por reserva, **na transação própria** e auditado
 * (`reservations.reservation.expire`) com o ator sistema (o job não tem requisição): trava a linha, confere de novo
 * que ainda é pré-reserva vencida e a cancela com o motivo `EXPIRED`, sem multa, devolvendo o inventário
 * ([ReservationLifecycle.expire]). Devolve se expirou (uma confirmação ou um cancelamento que confirmaram antes da
 * trava a deixam como está).
 */
@Service
class ProvisionalExpiryService(
    private val lifecycle: ReservationLifecycle,
) {
    @Audited(ReservationsAudit.EXPIRE_RESERVATION)
    @Transactional
    fun expire(id: ReservationId): Boolean = lifecycle.expire(id) != null
}

/**
 * A varredura das pré-reservas vencidas (M4 E09; ARCHITECTURE §13, a cada 5 min pelo `ProvisionalExpiryJob`): lê os
 * candidatos sem trava (a validade igual ou anterior a agora, pelo `Clock`), em lotes de [BATCH] pela validade e pelo
 * id (no máximo [MAX_BATCHES] por execução; o resto fica para a próxima), e expira cada um na transação dele
 * ([ProvisionalExpiryService]). Uma reserva que falha vai para o log pelo id e não interrompe as outras (a próxima
 * execução tenta de novo). Repetir não muda nada: a expirada já não é pré-reserva.
 */
@Component
class ProvisionalExpiry(
    private val store: BookingStore,
    private val expiry: ProvisionalExpiryService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** O resultado de uma varredura: quantas expiraram e quantas falharam. */
    data class Run(
        val expired: Int,
        val failed: Int,
    )

    fun expireDue(): Run {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        var expired = 0
        var failed = 0
        var after: Pair<Instant, ReservationId>? = null
        var rounds = 0
        do {
            val batch = store.dueProvisionals(now, after, BATCH)
            batch.forEach { (_, id) ->
                runCatching { expiry.expire(id) }
                    .onSuccess { if (it) expired++ }
                    .onFailure {
                        failed++
                        log.warn("Expiração da pré-reserva {} falhou", id, it)
                    }
            }
            after = batch.lastOrNull()
            rounds++
        } while (batch.size == BATCH && rounds < MAX_BATCHES)
        return Run(expired, failed)
    }

    private companion object {
        const val BATCH = 100
        const val MAX_BATCHES = 10
    }
}

/**
 * As garantias pendentes que vencem num intervalo (M4 E09; RF-RES-08 "alerta de pré-reservas e reservas sem garantia
 * perto do vencimento", H15): a lista do painel (M6) e da recepção. Uma reserva deve a garantia quando é pré-reserva
 * (vence na validade) ou quando está confirmada sem garantia, embora a política copiada exija uma (vence no horário de
 * check-in do hotel, no fuso dele, do dia do prazo do depósito ou, no cartão, do dia da chegada). Cada item traz o
 * depósito exigido e o já recebido na conta (pela `BillingApi`, numa consulta).
 */
@Service
class PendingGuaranteesService(
    private val store: BookingStore,
    private val billing: BillingApi,
    private val hotel: HotelApi,
    private val authorization: Authorization,
    private val clock: Clock,
) {
    /**
     * Pela API HTTP (`reservations.reservations.read`): de [from] (inclusive; nulo = sem início) a [to] (exclusive;
     * nulo = agora mais a antecedência do alerta, `HotelApi.operationalSettings().preReservationAlertLead`), por
     * cursor.
     */
    @Transactional(readOnly = true)
    fun list(
        from: Instant?,
        to: Instant?,
        page: PageParams,
    ): CursorPage<PendingGuarantee> {
        authorization.require(ReservationsPermissions.RESERVATIONS_READ)
        val now = now()
        val until = to ?: now.plus(hotel.operationalSettings().preReservationAlertLead)
        requirePeriod(from, until)
        val cursor = page.decodedCursor()
        val after = cursor?.let { it.instant(0) to ReservationId(it.uuid(1)) }
        val rows = rows(from, until, after, page.limit + 1)
        val result = CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.dueAt.toString(), it.id.toString()) }
        return CursorPage(views(result.items, now), result.nextCursor)
    }

    /** Pela `ReservationsApi`, sem permissão: até [API_LIMIT], do que vence primeiro. */
    @Transactional(readOnly = true)
    fun listAuthorized(
        from: Instant?,
        to: Instant,
    ): List<PendingGuarantee> {
        requirePeriod(from, to)
        return views(rows(from, to, null, API_LIMIT), now())
    }

    private fun rows(
        from: Instant?,
        to: Instant,
        after: Pair<Instant, ReservationId>?,
        limit: Int,
    ) = store.pendingGuarantees(from, to, hotel.policy().checkInTime, hotel.timezone(), after, limit)

    private fun views(
        rows: List<PendingGuaranteeRow>,
        now: Instant,
    ): List<PendingGuarantee> {
        val deposits =
            billing.netDeposits(
                rows.filter { it.guaranteeTerms.type == GuaranteeType.DEPOSIT }.map { it.folioId },
            )
        return rows.map { row ->
            val deposit = row.guaranteeTerms.type == GuaranteeType.DEPOSIT
            PendingGuarantee(
                reservationId = row.id,
                code = row.code,
                bookingId = row.bookingId,
                holderGuestId = row.holderGuestId,
                kind = row.kind,
                status = row.status,
                dueAt = row.dueAt,
                overdue = ProvisionalValidity.expired(row.dueAt, now),
                provisionalUntil = row.provisionalUntil,
                roomTypeId = row.roomTypeId,
                ratePlanId = row.ratePlanId,
                arrival = row.arrival,
                departure = row.departure,
                total = row.total,
                guaranteeTerms = row.guaranteeTerms,
                folioId = row.folioId,
                requiredDeposit = row.guaranteeTerms.requiredDeposit(row.total),
                depositReceived = if (deposit) deposits[row.folioId] else null,
                depositDueOn = row.guaranteeTerms.depositDueOn(row.arrival),
            )
        }
    }

    private fun requirePeriod(
        from: Instant?,
        to: Instant,
    ) {
        if (from != null && to.isBefore(from)) {
            val code = ReservationsErrorCodes.RESERVATION_PERIOD_INVALID
            val message = "O fim do intervalo não pode ser antes do início."
            throw InvalidInputException(code, message, listOf(FieldViolation("to", code.value, message)))
        }
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private companion object {
        const val API_LIMIT = 1000
    }
}
