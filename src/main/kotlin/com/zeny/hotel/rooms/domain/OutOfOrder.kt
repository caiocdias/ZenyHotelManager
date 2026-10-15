package com.zeny.hotel.rooms.domain

import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OccupancyStatus
import com.zeny.hotel.rooms.OutOfOrderId
import com.zeny.hotel.rooms.OutOfOrderKind
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsErrorCodes
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.UserId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Os dados editáveis de um período OOO/OOS (RF-UH-05): de [startsOn] a [endsOn] em datas de negócio,
 * inclusive, o motivo, o responsável (pessoa ou empresa, texto) e o status de governança de retorno.
 */
data class OutOfOrderDetails(
    val startsOn: LocalDate,
    val endsOn: LocalDate,
    val reason: String,
    val responsible: String,
    val returnStatus: HousekeepingStatus,
) {
    /**
     * Os dados conferidos e sem espaços nas pontas: o fim não vem antes do início e o período tem até
     * [OutOfOrder.MAX_DAYS] dias (`400 OUT_OF_ORDER_PERIOD_INVALID`); motivo e responsável preenchidos
     * e no tamanho (`400 ROOMS_DATA_INVALID`).
     */
    fun normalized(): OutOfOrderDetails {
        if (endsOn.isBefore(startsOn) || ChronoUnit.DAYS.between(startsOn, endsOn) >= OutOfOrder.MAX_DAYS) {
            throw RoomsRules.invalid(
                RoomsErrorCodes.OUT_OF_ORDER_PERIOD_INVALID,
                "endsOn",
                "Informe um período de até ${OutOfOrder.MAX_DAYS} dias, com o fim igual ou depois do início.",
            )
        }
        return copy(
            reason = RoomsRules.required("reason", reason, OutOfOrder.MAX_REASON_LENGTH),
            responsible = RoomsRules.required("responsible", responsible, OutOfOrder.MAX_RESPONSIBLE_LENGTH),
        )
    }
}

/** O que a liberação de um período fez ([OutOfOrder.release]). */
enum class OutOfOrderRelease {
    /** Estava em vigor na data operacional: a UH volta ao status de retorno. */
    RETURNED,

    /** Ainda não tinha começado: cancelado, não bloqueia nenhum dia. */
    CANCELLED,

    /** Já tinha terminado: só fica encerrado, sem mexer no status. */
    ENDED,

    /** Já estava liberado: nada muda. */
    ALREADY_RELEASED,
}

/**
 * Período fora de ordem (OOO) ou fora de serviço (OOS) de uma UH (RF-UH-05, SPEC §8.2.3), em datas de
 * negócio (RN-01), inclusive nas duas pontas. Bloqueia a UH de [startsOn] a [endsOn], ou até a véspera
 * da liberação ([blockedUntil]); dois períodos da mesma UH nunca bloqueiam o mesmo dia
 * ([requireNoOverlap], e o banco repete com `EXCLUDE USING gist`). Nunca é apagado.
 *
 * Regras (a data de referência é a data operacional corrente, `today`):
 * - abrir: UH ativa; o período não começa antes de `today`; OOO não numa UH ocupada se o período inclui
 *   `today` (RN-07; OOS pode);
 * - editar (o tipo não muda): não depois de liberado; um início ou fim alterado não fica antes de
 *   `today`; um OOO que passa a incluir `today` também não numa UH ocupada;
 * - liberar: marca `today` como data da liberação; o status de retorno só se aplica se o período estava
 *   em vigor ([OutOfOrderRelease]).
 */
@Entity
@Table(name = "out_of_order", schema = "rooms")
@Suppress("LongParameterList") // os dados de abertura do período
class OutOfOrder private constructor(
    @Id
    val id: OutOfOrderId,
    @Column(name = "room_id", nullable = false, updatable = false)
    val roomId: RoomId,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    val kind: OutOfOrderKind,
    details: OutOfOrderDetails,
    @Column(name = "opened_at", nullable = false, updatable = false)
    val openedAt: Instant,
    @Column(name = "opened_by", updatable = false)
    private val openedByValue: UUID?,
) {
    @Column(name = "starts_on", nullable = false)
    var startsOn: LocalDate = details.startsOn
        protected set

    @Column(name = "ends_on", nullable = false)
    var endsOn: LocalDate = details.endsOn
        protected set

    @Column(nullable = false)
    var reason: String = details.reason
        protected set

    @Column(nullable = false)
    var responsible: String = details.responsible
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "return_status", nullable = false)
    var returnStatus: HousekeepingStatus = details.returnStatus
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = openedAt
        protected set

    /** A data operacional da liberação: a partir dela, o período não bloqueia mais a UH. */
    @Column(name = "released_on")
    var releasedOn: LocalDate? = null
        protected set

    @Column(name = "released_at")
    var releasedAt: Instant? = null
        protected set

    @Column(name = "released_by")
    private var releasedByValue: UUID? = null

    @Version
    var version: Long? = null
        protected set

    /** Quem abriu; `null` = o sistema. */
    val openedBy: UserId? get() = openedByValue?.let(::UserId)

    /** Quem liberou; `null` = o sistema (ou ainda não liberado). */
    val releasedBy: UserId? get() = releasedByValue?.let(::UserId)

    /** O último dia bloqueado: o fim, ou a véspera da liberação; `null` se não bloqueia nenhum. */
    val blockedUntil: LocalDate?
        get() = blockedUntil(startsOn, endsOn, releasedOn)

    /** Se o período bloqueia a UH na data [date]. */
    fun blocks(date: LocalDate): Boolean = blockedUntil?.let { !date.isBefore(startsOn) && !date.isAfter(it) } ?: false

    /** Se o período bloqueia algum dia de [from] a [to], inclusive. */
    fun blocksAnyOf(
        from: LocalDate,
        to: LocalDate,
    ): Boolean = blockedUntil?.let { !startsOn.isAfter(to) && !it.isBefore(from) } ?: false

    /**
     * Troca o período, o motivo, o responsável e o status de retorno; devolve se algo mudou. A
     * sobreposição com os outros períodos da UH é conferida pelo caso de uso ([requireNoOverlap]).
     */
    fun update(
        room: Room,
        details: OutOfOrderDetails,
        today: LocalDate,
        now: Instant,
    ): Boolean {
        val normalized = details.normalized()
        if (releasedOn != null) {
            throw BusinessRuleException(
                RoomsErrorCodes.OUT_OF_ORDER_RELEASED,
                "O período já foi liberado e não pode ser alterado.",
            )
        }
        if (movesIntoPast(normalized, today)) throw periodInPast()
        if (!blocks(today) && covers(normalized, today)) requireVacantForOutOfOrder(room)
        val changed =
            normalized !=
                OutOfOrderDetails(startsOn, endsOn, reason, responsible, returnStatus)
        if (!changed) return false
        startsOn = normalized.startsOn
        endsOn = normalized.endsOn
        reason = normalized.reason
        responsible = normalized.responsible
        returnStatus = normalized.returnStatus
        updatedAt = now
        return true
    }

    /** Libera o período na data operacional [today] ([OutOfOrderRelease] diz o que isso fez). */
    fun release(
        today: LocalDate,
        now: Instant,
        releasedBy: UserId?,
    ): OutOfOrderRelease {
        if (releasedOn != null) return OutOfOrderRelease.ALREADY_RELEASED
        val outcome =
            when {
                blocks(today) -> OutOfOrderRelease.RETURNED
                today.isBefore(startsOn) -> OutOfOrderRelease.CANCELLED
                else -> OutOfOrderRelease.ENDED
            }
        releasedOn = today
        releasedAt = now
        releasedByValue = releasedBy?.value
        updatedAt = now
        return outcome
    }

    /** Se o início ou o fim alterado ficaria antes da data operacional [today]. */
    private fun movesIntoPast(
        details: OutOfOrderDetails,
        today: LocalDate,
    ): Boolean {
        val startMoved = details.startsOn != startsOn && details.startsOn.isBefore(today)
        val endMoved = details.endsOn != endsOn && details.endsOn.isBefore(today)
        return startMoved || endMoved
    }

    private fun requireVacantForOutOfOrder(room: Room) {
        if (kind == OutOfOrderKind.OUT_OF_ORDER && room.occupancy == OccupancyStatus.OCCUPIED) {
            throw BusinessRuleException(
                RoomsErrorCodes.OUT_OF_ORDER_ROOM_OCCUPIED,
                "A UH está ocupada: OOO não se aplica a UH com hóspede. Use OOS ou espere a saída.",
            )
        }
    }

    companion object {
        /** Maior período, em dias (as duas pontas inclusive). */
        const val MAX_DAYS = 365L
        const val MAX_REASON_LENGTH = 500
        const val MAX_RESPONSIBLE_LENGTH = 120

        /** Abre um período OOO/OOS na UH [room], com a data operacional [today] (regras na classe). */
        @Suppress("LongParameterList") // os dados de abertura do período
        fun open(
            room: Room,
            kind: OutOfOrderKind,
            details: OutOfOrderDetails,
            today: LocalDate,
            now: Instant,
            openedBy: UserId?,
        ): OutOfOrder {
            val normalized = details.normalized()
            if (!room.active) {
                throw BusinessRuleException(RoomsErrorCodes.ROOM_INACTIVE, "A UH está desativada.")
            }
            if (normalized.startsOn.isBefore(today)) throw periodInPast()
            val period = OutOfOrder(OutOfOrderId.new(), room.id, kind, normalized, now, openedBy?.value)
            if (covers(normalized, today)) period.requireVacantForOutOfOrder(room)
            return period
        }

        /**
         * Recusa (`409 OUT_OF_ORDER_OVERLAP`) um período de [startsOn] a [endsOn] que bloquearia algum dia
         * já bloqueado por um dos [others] (os outros períodos da mesma UH, sem o que está sendo editado).
         */
        fun requireNoOverlap(
            others: Collection<OutOfOrder>,
            startsOn: LocalDate,
            endsOn: LocalDate,
        ) {
            if (others.any { it.blocksAnyOf(startsOn, endsOn) }) {
                throw ConflictException(
                    RoomsErrorCodes.OUT_OF_ORDER_OVERLAP,
                    "A UH já tem um período OOO ou OOS nessas datas; altere ou libere o outro antes.",
                )
            }
        }

        /** O último dia que um período bloqueia (regra repetida pela coluna `blocked_period` do banco). */
        fun blockedUntil(
            startsOn: LocalDate,
            endsOn: LocalDate,
            releasedOn: LocalDate?,
        ): LocalDate? {
            val last = if (releasedOn == null || releasedOn.isAfter(endsOn)) endsOn else releasedOn.minusDays(1)
            return last.takeUnless { it.isBefore(startsOn) }
        }

        private fun covers(
            details: OutOfOrderDetails,
            date: LocalDate,
        ): Boolean = !date.isBefore(details.startsOn) && !date.isAfter(details.endsOn)

        private fun periodInPast() =
            BusinessRuleException(
                RoomsErrorCodes.OUT_OF_ORDER_PERIOD_IN_PAST,
                "O período não pode começar nem terminar antes da data operacional.",
            )
    }
}
