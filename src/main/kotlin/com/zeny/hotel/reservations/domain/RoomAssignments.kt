package com.zeny.hotel.reservations.domain

import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.ReservationsErrorCodes
import com.zeny.hotel.reservations.RoomAssignmentSummary
import com.zeny.hotel.reservations.RoomSuggestion
import com.zeny.hotel.rooms.OutOfOrderId
import com.zeny.hotel.rooms.RoomFeatureId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomSummary
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * O período de uma alocação (M4 E10, RN-04, H5). Em datas ([lastDate], [datesBlockedBy]; a coluna
 * `room_assignment.stay`): as noites de [startsOn] até a véspera de [endsOn]; no day use ([startsOn] = [endsOn]), a
 * própria data, inteira (um OOO na data o atinge).
 *
 * O que a alocação **ocupa** na UH ([conflictsWith]; a coluna `room_assignment.slots`, da restrição
 * `room_assignment_overlap_ex`) é contado em meios-dias: com `k` = dias desde 2000-01-01, o meio-dia `2k` é o dia `k`
 * entre o check-out e o check-in e o `2k+1` é a noite `k`. O pernoite ocupa `[2·chegada+1, 2·saída)` (as noites e os
 * dias do meio, nunca o dia da chegada nem o da saída); o day use, `[2·data, 2·data+1)` (só o dia). Por isso a saída de
 * uma reserva e a chegada de outra no mesmo dia não conflitam, e o day use só conflita com a estadia que atravessa o
 * dia (chegada < data < saída) e com outro day use da data, nunca com a saída nem com a chegada de outras reservas na
 * data (H5).
 */
data class AssignmentPeriod(
    val startsOn: LocalDate,
    val endsOn: LocalDate,
) {
    init {
        require(!endsOn.isBefore(startsOn)) { "Período de alocação invertido: $startsOn a $endsOn" }
    }

    /** A última data ocupada (inclusive). */
    val lastDate: LocalDate get() = if (endsOn == startsOn) startsOn else endsOn.minusDays(1)

    val dayUse: Boolean get() = startsOn == endsOn

    /** O primeiro meio-dia ocupado (inclusive). */
    val firstSlot: Long get() = if (dayUse) 2 * epochDay(startsOn) else 2 * epochDay(startsOn) + 1

    /** O fim dos meios-dias ocupados (exclusive). */
    val endSlot: Long get() = if (dayUse) 2 * epochDay(startsOn) + 1 else 2 * epochDay(endsOn)

    /** Se as duas alocações ocupam algum meio-dia em comum na mesma UH (a regra de `room_assignment_overlap_ex`). */
    fun conflictsWith(other: AssignmentPeriod): Boolean = firstSlot < other.endSlot && other.firstSlot < endSlot

    /** As datas deste período bloqueadas pelo período de OOO [blocked] (de `startsOn` a `blockedUntil`, inclusive). */
    fun datesBlockedBy(blocked: BlockingPeriod): List<LocalDate> {
        val from = maxOf(startsOn, blocked.startsOn)
        val to = minOf(lastDate, blocked.until)
        return if (to.isBefore(from)) {
            emptyList()
        } else {
            generateSequence(from) {
                it.plusDays(1)
            }.takeWhile { !it.isAfter(to) }.toList()
        }
    }

    companion object {
        private val SLOT_EPOCH: LocalDate = LocalDate.of(2000, 1, 1)

        private fun epochDay(date: LocalDate): Long = ChronoUnit.DAYS.between(SLOT_EPOCH, date)

        fun of(reservation: ReservationSummary) = AssignmentPeriod(reservation.arrival, reservation.departure)

        fun of(assignment: RoomAssignmentSummary) = AssignmentPeriod(assignment.startsOn, assignment.endsOn)
    }
}

/**
 * Os dias que um período OOO (ou OOS) bloqueia de fato numa UH: de [startsOn] até [until] (o fim, ou a véspera da
 * liberação; `RoomsApi.outOfOrderPeriods`, `blockedUntil`).
 */
data class BlockingPeriod(
    val id: OutOfOrderId,
    val roomId: RoomId,
    val startsOn: LocalDate,
    val until: LocalDate,
    val outOfService: Boolean,
)

/**
 * As regras da alocação de UH (M4 E10; RF-RES-04, RN-04, RN-07; decisões do implementador, pendentes de confirmação do
 * usuário):
 * - só uma pré-reserva ou uma confirmada aceita alocação, realocação ou desalocação (a troca de UH do hóspede em casa é
 *   da recepção, M5): `409 ROOM_ASSIGNMENT_NOT_ALLOWED`; com a estadia ainda por vir (alguma noite a partir da data
 *   operacional; no day use, a data não passou): `422 RESERVATION_STAY_ENDED`;
 * - a UH existe (`400 UNKNOWN_ROOM`), está ativa (`422 ROOM_INACTIVE`) e é do tipo da reserva
 *   (`422 ROOM_TYPE_MISMATCH`: para outro tipo, altera-se antes o tipo da reserva, que move o inventário);
 * - nenhuma noite da estadia em OOO na UH (`422 ROOM_OUT_OF_ORDER`); o OOS não impede (a UH continua vendável, como
 *   última opção, RF-UH-05);
 * - "não mover" impede trocar ou soltar a UH sem tirar a marca antes (`409 ROOM_ASSIGNMENT_LOCKED`).
 */
object AssignmentRules {
    fun requireAssignable(
        reservation: ReservationSummary,
        businessDate: LocalDate,
    ) {
        if (reservation.status != ReservationStatus.PROVISIONAL && reservation.status != ReservationStatus.CONFIRMED) {
            throw ReservationNotAssignableException(reservation.status)
        }
        if (!InventoryNights.hasFutureNight(reservation.arrival, reservation.departure, businessDate)) {
            throw BusinessRuleException(
                ReservationsErrorCodes.RESERVATION_STAY_ENDED,
                "A estadia da reserva já terminou: a UH não é mais alocada.",
            )
        }
    }

    @Suppress("ThrowsCount") // inexistente, desativada e de outro tipo
    fun requireRoom(
        room: RoomSummary?,
        reservation: ReservationSummary,
    ): RoomSummary {
        if (room == null) {
            val message = "A UH não existe."
            throw InvalidInputException(
                ReservationsErrorCodes.UNKNOWN_ROOM,
                message,
                listOf(FieldViolation("roomId", ReservationsErrorCodes.UNKNOWN_ROOM.value, message)),
            )
        }
        if (!room.active) {
            throw BusinessRuleException(ReservationsErrorCodes.ROOM_INACTIVE, "A UH ${room.number} está desativada.")
        }
        if (room.roomTypeId != reservation.roomTypeId) {
            throw BusinessRuleException(
                ReservationsErrorCodes.ROOM_TYPE_MISMATCH,
                "A UH ${room.number} é de outro tipo que o da reserva: altere antes o tipo da reserva.",
            )
        }
        return room
    }

    /** As datas da estadia em OOO na UH; vazio se nenhuma. */
    fun outOfOrderDates(
        period: AssignmentPeriod,
        periods: List<BlockingPeriod>,
    ): List<LocalDate> =
        periods
            .filter { !it.outOfService }
            .flatMap { period.datesBlockedBy(it) }
            .distinct()
            .sorted()

    fun requireNotOutOfOrder(
        room: RoomSummary,
        period: AssignmentPeriod,
        periods: List<BlockingPeriod>,
    ) {
        val dates = outOfOrderDates(period, periods.filter { it.roomId == room.id })
        if (dates.isNotEmpty()) throw RoomOutOfOrderException(room.number, dates)
    }

    fun requireMovable(assignment: RoomAssignmentSummary) {
        if (assignment.doNotMove) {
            throw ConflictException(
                ReservationsErrorCodes.ROOM_ASSIGNMENT_LOCKED,
                "A UH da reserva está marcada \"não mover\": tire a marca antes de trocá-la ou soltá-la.",
            )
        }
    }
}

/** A reserva não aceita alocação na situação em que está (`409 ROOM_ASSIGNMENT_NOT_ALLOWED`). */
class ReservationNotAssignableException(
    val status: ReservationStatus,
) : ConflictException(
        ReservationsErrorCodes.ROOM_ASSIGNMENT_NOT_ALLOWED,
        "Só uma pré-reserva ou uma reserva confirmada tem a UH alocada, trocada ou solta aqui.",
    ) {
    override val problemProperties: Map<String, Any> get() = mapOf("reservationStatus" to status.name)
}

/** A UH está em OOO em noites da estadia (`422 ROOM_OUT_OF_ORDER`, as datas em `dates`). */
class RoomOutOfOrderException(
    number: String,
    val dates: List<LocalDate>,
) : BusinessRuleException(
        ReservationsErrorCodes.ROOM_OUT_OF_ORDER,
        "A UH $number está em OOO em ${dates.size} data(s) da estadia.",
    ) {
    override val problemProperties: Map<String, Any> get() = mapOf("dates" to dates.map { it.toString() })
}

/** A UH já está alocada a outra reserva em parte do período (`409 ROOM_ALREADY_ASSIGNED`). */
class RoomAlreadyAssignedException(
    number: String,
) : ConflictException(
        ReservationsErrorCodes.ROOM_ALREADY_ASSIGNED,
        "A UH $number já está alocada a outra reserva em parte do período.",
    )

/** Uma UH candidata a uma reserva: livre no período e sem OOO; [outOfService] se tem OOS em alguma noite. */
data class RoomCandidate(
    val room: RoomSummary,
    val outOfService: Boolean,
    val current: Boolean,
)

/**
 * A ordem das sugestões de UH (RF-RES-04 "sugestão por características e preferências", RF-UH-05 "OOS como última
 * opção"; decisão do implementador, pendente de confirmação do usuário): as UHs com OOS em alguma noite vão sempre por
 * último; depois, quem tem mais das características pedidas; depois, o andar preferido pelo hóspede principal
 * (comparado sem diferenciar maiúsculas, acentos e espaços); por fim, a ordem de número da UH (a de
 * `RoomsApi.activeRooms`). As outras preferências do hóspede (camas, travesseiro) não distinguem UHs do mesmo tipo e
 * não entram na ordem.
 */
object RoomSuggestions {
    fun rank(
        candidates: List<RoomCandidate>,
        requestedFeatures: Set<RoomFeatureId>,
        preferredFloor: String?,
    ): List<RoomSuggestion> {
        val floor = preferredFloor?.let(::normalized)?.takeIf { it.isNotEmpty() }
        return candidates
            .map { candidate ->
                RoomSuggestion(
                    room = candidate.room,
                    outOfService = candidate.outOfService,
                    current = candidate.current,
                    matchedFeatureIds = candidate.room.featureIds intersect requestedFeatures,
                    matchesFloorPreference = floor != null && candidate.room.floor?.let(::normalized) == floor,
                )
            }.sortedWith(
                compareBy<RoomSuggestion> { it.outOfService }
                    .thenByDescending { it.matchedFeatureIds.size }
                    .thenByDescending { it.matchesFloorPreference },
            )
    }

    /** Sem diferenciar maiúsculas, acentos e espaços repetidos. */
    fun normalized(text: String): String =
        Normalizer
            .normalize(text.trim().lowercase(), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .replace(SPACES, " ")

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val SPACES = Regex("\\s+")
}

/**
 * A marca "requer realocação" (RN-07, H11): o período OOO (não o OOS) da UH que atinge alguma noite da alocação, ou
 * `null`. Com mais de um, o primeiro por início (desempate pelo id).
 */
object ReallocationMarks {
    fun hitting(
        period: AssignmentPeriod,
        periods: List<BlockingPeriod>,
    ): BlockingPeriod? = hittingAll(period, periods).firstOrNull()

    /**
     * O período OOO que a marca da alocação deve citar, contra **todos** os OOO da UH que atingem a estadia: o que ela
     * já cita ([current]), se ainda a atinge; senão o primeiro que a atinge (o OOO liberado ou alterado cede a marca a
     * outro que ainda atinge a estadia); `null` se nenhum a atinge mais.
     */
    fun markFor(
        period: AssignmentPeriod,
        periods: List<BlockingPeriod>,
        current: OutOfOrderId?,
    ): BlockingPeriod? {
        val hits = hittingAll(period, periods)
        return hits.firstOrNull { it.id == current } ?: hits.firstOrNull()
    }

    private fun hittingAll(
        period: AssignmentPeriod,
        periods: List<BlockingPeriod>,
    ): List<BlockingPeriod> =
        periods
            .filter { !it.outOfService && period.datesBlockedBy(it).isNotEmpty() }
            .sortedWith(compareBy<BlockingPeriod> { it.startsOn }.thenBy { it.id.value.toString() })
}

/**
 * O texto da notificação de realocação (H11): o número da UH, as datas do OOO e os códigos das reservas atingidas,
 * nunca dado de hóspede (vai ao painel e ao push).
 */
object ReallocationNotice {
    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    /** Quantos códigos de reserva cabem no texto; os demais viram "e mais N". */
    private const val MAX_CODES = 20

    fun title(roomNumber: String): String = "Realocação necessária na UH $roomNumber"

    fun body(
        roomNumber: String,
        from: LocalDate,
        to: LocalDate,
        reservationCodes: List<String>,
    ): String {
        val shown = reservationCodes.take(MAX_CODES).joinToString(", ")
        val rest = reservationCodes.size - MAX_CODES
        val codes = if (rest > 0) "$shown e mais $rest" else shown
        val count = reservationCodes.size
        val subject = if (count == 1) "1 reserva alocada nela precisa" else "$count reservas alocadas nela precisam"
        return "A UH $roomNumber está em OOO de ${DATE.format(from)} a ${DATE.format(to)}: $subject de outra UH " +
            "($codes)."
    }
}
