package com.zeny.hotel.reservations.domain

import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.reservations.CheckIn
import com.zeny.hotel.reservations.CheckInGuest
import com.zeny.hotel.reservations.NightlyRateSummary
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.ReservationsErrorCodes
import com.zeny.hotel.reservations.RoomAssignmentReason
import com.zeny.hotel.reservations.RoomAssignmentSummary
import com.zeny.hotel.reservations.RoomMove
import com.zeny.hotel.reservations.StayTransitionRecord
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import java.time.LocalDate

/**
 * Uma recusa do check-in sobre um hóspede (RN-24): `422` com o hóspede em `guestId` (o id opaco, nunca o nome).
 */
class GuestCheckInRefusedException(
    code: ErrorCode,
    message: String,
    val guestId: GuestId,
) : BusinessRuleException(code, message) {
    override val problemProperties: Map<String, Any> get() = mapOf("guestId" to guestId.toString())
}

/**
 * O formato e a data do check-in (M5 E01; RF-REC-01, SPEC §8.2.6 `StayGuest`, H10):
 * - a lista **completa** dos hóspedes da estadia: de 1 até a ocupação reservada (adultos + crianças), sem repetir, com
 *   exatamente um principal; motivo de viagem, meio de transporte e parentesco como códigos opacos ([CODE]: letras,
 *   dígitos, `_`, `.` e `-`, até 40; o `fnrh` confere contra os domínios oficiais, H21); o anexo obrigatório na
 *   autorização escrita e na judicial e ausente na presença dos pais; o responsável nunca o próprio menor. Recusas
 *   `400 CHECK_IN_INVALID` com o campo.
 * - a data operacional dentro da estadia (decisão provisória da E01, a chegada atrasada aceita): no pernoite, da
 *   chegada até a véspera da saída; no day use, a própria data. Antes da chegada, `422 CHECK_IN_BEFORE_ARRIVAL`;
 *   depois do fim, `422 RESERVATION_STAY_ENDED`. As noites anteriores à data operacional (chegada atrasada) ficam como
 *   estão (o preço congelado e a unidade vendida).
 */
object CheckInRules {
    const val MAX_CODE = 40

    val CODE = Regex("[A-Za-z0-9_.-]{1,$MAX_CODE}")

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth") // uma conferência por campo, com o caminho dele
    fun requireValid(
        checkIn: CheckIn,
        occupancy: Int,
    ) {
        val code = ReservationsErrorCodes.CHECK_IN_INVALID.value
        val violations = mutableListOf<FieldViolation>()
        val guests = checkIn.guests
        if (guests.isEmpty() || guests.size > occupancy) {
            violations += FieldViolation("guests", code, "Informe de 1 a $occupancy hóspedes (a ocupação da reserva).")
        }
        if (guests.isNotEmpty() && guests.count { it.primary } != 1) {
            violations += FieldViolation("guests", code, "Informe exatamente um hóspede principal.")
        }
        val seen = mutableSetOf<GuestId>()
        guests.forEachIndexed { index, guest ->
            val path = "guests[$index]"
            if (!seen.add(guest.guestId)) {
                violations += FieldViolation("$path.guestId", code, "O hóspede aparece mais de uma vez.")
            }
            guest.travelReason?.let {
                if (!CODE.matches(it)) violations += FieldViolation("$path.travelReason", code, CODE_MESSAGE)
            }
            guest.transportMode?.let {
                if (!CODE.matches(it)) violations += FieldViolation("$path.transportMode", code, CODE_MESSAGE)
            }
            guest.minorAuthorization?.let { authorization ->
                val authPath = "$path.minorAuthorization"
                if (!CODE.matches(authorization.relationship)) {
                    violations += FieldViolation("$authPath.relationship", code, CODE_MESSAGE)
                }
                if (authorization.responsibleGuestId == guest.guestId) {
                    violations +=
                        FieldViolation("$authPath.responsibleGuestId", code, "O responsável não é o próprio menor.")
                }
                if (authorization.proofType.requiresAttachment != (authorization.attachmentId != null)) {
                    val message =
                        if (authorization.proofType.requiresAttachment) {
                            "Anexe a autorização (escrita ou judicial)."
                        } else {
                            "Com os pais presentes, não há anexo."
                        }
                    violations += FieldViolation("$authPath.attachmentId", code, message)
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw InvalidInputException(ReservationsErrorCodes.CHECK_IN_INVALID, violations.first().message, violations)
        }
    }

    fun requireDate(
        reservation: ReservationSummary,
        businessDate: LocalDate,
    ) {
        if (businessDate.isBefore(reservation.arrival)) {
            throw BusinessRuleException(
                ReservationsErrorCodes.CHECK_IN_BEFORE_ARRIVAL,
                "A chegada da reserva ${reservation.code} é depois da data operacional: altere a chegada antes.",
            )
        }
        if (!InHouseDates.hasRemaining(reservation, businessDate)) {
            throw BusinessRuleException(
                ReservationsErrorCodes.RESERVATION_STAY_ENDED,
                "A estadia da reserva ${reservation.code} já terminou: não há mais check-in.",
            )
        }
    }

    /** Os hóspedes depois da resolução das mesclagens: o mesmo perfil duas vezes é recusado (`400`). */
    fun requireDistinctResolved(guests: List<CheckInGuest>) {
        val seen = mutableSetOf<GuestId>()
        val code = ReservationsErrorCodes.CHECK_IN_INVALID
        guests.forEachIndexed { index, guest ->
            if (!seen.add(guest.guestId)) {
                val message = "O hóspede é o mesmo perfil de outro da lista (mesclados)."
                throw InvalidInputException(
                    code,
                    message,
                    listOf(FieldViolation("guests[$index].guestId", code.value, message)),
                )
            }
        }
    }

    private const val CODE_MESSAGE = "Use um código de até $MAX_CODE letras, dígitos, \"_\", \".\" ou \"-\"."
}

/**
 * Um hóspede do check-in já resolvido (os ids finais das mesclagens) com a menoridade na data ([minor], nulo
 * = sem data de nascimento).
 */
data class CheckInGuestFacts(
    val guest: CheckInGuest,
    val minor: Boolean?,
)

/**
 * A RN-24 no domínio da reserva (M5 E01, H10): nenhuma reserva fica hospedada com um menor sem comprovação, por
 * qualquer caminho (o check-in da reserva e o walk-in passam por aqui). Na data operacional do check-in:
 * - todo hóspede tem data de nascimento (sem ela, a menoridade não se confere): `422 GUEST_BIRTH_DATE_REQUIRED`;
 * - todo menor tem a comprovação: `422 MINOR_AUTHORIZATION_REQUIRED`;
 * - a comprovação vale: o responsável é um hóspede **adulto da mesma estadia**, a validade (se houver) não passou, e só
 *   o menor a tem: `422 MINOR_AUTHORIZATION_INVALID`.
 *
 * Todas com o hóspede em `guestId`.
 */
object MinorRules {
    @Suppress("ThrowsCount") // data de nascimento, comprovação faltando e comprovação inválida
    fun require(
        guests: List<CheckInGuestFacts>,
        businessDate: LocalDate,
    ) {
        guests.firstOrNull { it.minor == null }?.let {
            throw GuestCheckInRefusedException(
                ReservationsErrorCodes.GUEST_BIRTH_DATE_REQUIRED,
                "Um hóspede da estadia não tem data de nascimento: cadastre-a para conferir a menoridade.",
                it.guest.guestId,
            )
        }
        val adults = guests.filter { it.minor == false }.map { it.guest.guestId }.toSet()
        guests.forEach { facts ->
            val authorization = facts.guest.minorAuthorization
            if (facts.minor == true && authorization == null) {
                throw GuestCheckInRefusedException(
                    ReservationsErrorCodes.MINOR_AUTHORIZATION_REQUIRED,
                    "Um hóspede menor de idade precisa da comprovação (pais presentes, autorização escrita ou " +
                        "judicial).",
                    facts.guest.guestId,
                )
            }
            if (authorization == null) return@forEach
            val reason =
                when {
                    facts.minor == false -> {
                        "A comprovação de menoridade é só para o hóspede menor de idade."
                    }

                    authorization.responsibleGuestId !in adults -> {
                        "O responsável pelo menor precisa ser um hóspede adulto da mesma estadia."
                    }

                    authorization.validUntil?.isBefore(businessDate) == true -> {
                        "A autorização do menor já venceu."
                    }

                    else -> {
                        null
                    }
                }
            reason?.let {
                throw GuestCheckInRefusedException(
                    ReservationsErrorCodes.MINOR_AUTHORIZATION_INVALID,
                    it,
                    facts.guest.guestId,
                )
            }
        }
    }
}

/** As datas de uma estadia em casa (M5 E01). */
object InHouseDates {
    /**
     * Se a estadia ainda tem o que usar na data operacional: no pernoite, alguma noite a partir dela (a data antes da
     * saída); no day use, a própria data.
     */
    fun hasRemaining(
        reservation: ReservationSummary,
        businessDate: LocalDate,
    ): Boolean =
        if (reservation.dayUse) {
            businessDate == reservation.arrival
        } else {
            businessDate.isBefore(reservation.departure)
        }
}

/**
 * O check-in só é desfeito na **mesma data operacional** em que foi feito (SPEC §8.4; a permissão sensível é do
 * `frontdesk`, M5 E02): depois, `422 UNDO_CHECK_IN_EXPIRED`.
 */
object UndoCheckInRules {
    fun require(
        checkIn: StayTransitionRecord,
        businessDate: LocalDate,
    ) {
        if (checkIn.businessDate != businessDate) {
            throw BusinessRuleException(
                ReservationsErrorCodes.UNDO_CHECK_IN_EXPIRED,
                "O check-in foi feito noutra data operacional e não é mais desfeito.",
            )
        }
    }
}

/**
 * O que o check-out faz com a estadia (M5 E01; RF-REC-06, Ajuste D 2 da M4 E10; decisões provisórias da E01):
 * - **no dia da saída** (ou com o day use na data): nada muda na estadia;
 * - **antecipado** (pernoite com a data operacional antes da saída): a saída passa à data operacional, e as noites a
 *   partir dela saem da reserva (e voltam ao inventário, ou ao bloqueio do pick-up); as noites anteriores guardam o
 *   preço congelado (RN-14). No check-out no próprio dia da chegada, a estadia vira **day use** na data da chegada: a
 *   noite da chegada fica (o preço congelado dela é o da estadia, que o check-out da E04 lança, H14) e a unidade dela
 *   volta ao inventário (o day use não toma inventário, H5);
 * - **atrasado** (a data operacional depois da saída, sem extensão): a estadia fica como está (o check-out só
 *   registra a data; as noites a mais não foram vendidas).
 */
data class CheckOutPlan(
    val departure: LocalDate,
    val nights: List<NightlyRateSummary>,
    val early: Boolean,
) {
    companion object {
        fun of(
            reservation: ReservationSummary,
            businessDate: LocalDate,
        ): CheckOutPlan {
            val early = !reservation.dayUse && businessDate.isBefore(reservation.departure)
            if (!early) return CheckOutPlan(reservation.departure, reservation.nightlyRates, early = false)
            val nights =
                reservation.nightlyRates.filter {
                    it.date.isBefore(businessDate) || (businessDate == reservation.arrival && it.date == businessDate)
                }
            return CheckOutPlan(businessDate, nights, early = true)
        }
    }
}

/**
 * A alteração de uma reserva hospedada pelo `PUT` (M5 E01; RF-REC-05): só a **saída** muda (extensão ou redução),
 * além do segmento e dos pedidos especiais; a chegada, o tipo de UH, o plano e a ocupação ficam
 * (`409 IN_HOUSE_STAY_FIXED`, com os campos em `fields`; a ocupação do hospedado é decisão da E05). A saída nova vem
 * depois da data operacional (`422 IN_HOUSE_DEPARTURE_INVALID`; para sair hoje, o check-out). As noites anteriores à
 * data operacional nunca são recalculadas.
 */
object InHouseChangeRules {
    fun require(
        old: StayShape,
        new: StayShape,
        businessDate: LocalDate,
    ) {
        val fields =
            buildList {
                if (old.arrival != new.arrival) add("arrival")
                if (old.roomTypeId != new.roomTypeId) add("roomTypeId")
                if (old.ratePlanId != new.ratePlanId) add("ratePlanId")
                if (old.adults != new.adults) add("adults")
                if (old.childAges != new.childAges) add("childAges")
            }
        if (fields.isNotEmpty()) throw InHouseStayFixedException(fields)
        if (old.departure != new.departure && !new.departure.isAfter(businessDate)) {
            throw BusinessRuleException(
                ReservationsErrorCodes.IN_HOUSE_DEPARTURE_INVALID,
                "A saída nova do hospedado precisa ser depois da data operacional; para sair hoje, faça o check-out.",
            )
        }
    }

    /**
     * Uma noite já congelada do hospedado e anterior à data operacional nunca é recalculada (RN-14): a alteração que a
     * recalcularia (o day use já passado transformado em pernoite) é `422 IN_HOUSE_PAST_NIGHT_FIXED`. As noites
     * **novas** anteriores à data operacional (a extensão de um hospedado com a saída atrasada, que o check-out
     * atrasado manteria sem registro) são precificadas como as outras noites novas, pela tarifa delas.
     */
    fun requireNoPastNight(
        affected: Collection<LocalDate>,
        frozen: Collection<LocalDate>,
        businessDate: LocalDate,
    ) {
        val existing = frozen.toSet()
        val past = affected.filter { it.isBefore(businessDate) && it in existing }.sorted()
        if (past.isNotEmpty()) throw InHousePastNightFixedException(past)
    }
}

/** `422 IN_HOUSE_PAST_NIGHT_FIXED`, com as noites passadas que a alteração recalcularia em `dates`. */
class InHousePastNightFixedException(
    val dates: List<LocalDate>,
) : BusinessRuleException(
        ReservationsErrorCodes.IN_HOUSE_PAST_NIGHT_FIXED,
        "A alteração recalcularia uma noite já passada da estadia, que guarda o preço congelado.",
    ) {
    override val problemProperties: Map<String, Any> get() = mapOf("dates" to dates.map { it.toString() })
}

/** `409 IN_HOUSE_STAY_FIXED`, com os campos que a alteração tentou mudar em `fields`. */
class InHouseStayFixedException(
    val fields: List<String>,
) : ConflictException(
        ReservationsErrorCodes.IN_HOUSE_STAY_FIXED,
        "A reserva está hospedada: a chegada, o tipo de UH, o plano e a ocupação não mudam por aqui.",
    ) {
    override val problemProperties: Map<String, Any> get() = mapOf("fields" to fields)
}

/**
 * A troca de UH do hospedado (M5 E01; RF-REC-04, SPEC §8.2.6 `moveRoom`; decisões provisórias da E01):
 * - o motivo não é `INITIAL` (`400 ROOM_MOVE_INVALID`, campo `reason`);
 * - a estadia ainda tem o que usar na data operacional ([InHouseDates.hasRemaining]; senão
 *   `422 RESERVATION_STAY_ENDED`);
 * - a UH nova existe, está ativa, é do **mesmo tipo** da reserva nesta etapa (`422 ROOM_TYPE_MISMATCH`; a troca para
 *   outro tipo é decisão da E05) e não é a atual (`422 ROOM_MOVE_SAME_ROOM`);
 * - a alocação atual termina na data operacional (no day use, fica como está) e a nova vai da data operacional à saída
 *   ([newPeriod]); "não mover" não impede a troca do hospedado (a marca protege a reserva antes da chegada) e passa à
 *   alocação nova.
 */
object RoomMoveRules {
    fun requireValid(move: RoomMove) {
        if (move.reason == RoomAssignmentReason.INITIAL) {
            val code = ReservationsErrorCodes.ROOM_MOVE_INVALID
            val message = "Informe por que o hóspede troca de UH (o motivo \"inicial\" é da primeira alocação)."
            throw InvalidInputException(code, message, listOf(FieldViolation("reason", code.value, message)))
        }
    }

    fun requireRemaining(
        reservation: ReservationSummary,
        businessDate: LocalDate,
    ) {
        if (!InHouseDates.hasRemaining(reservation, businessDate)) {
            throw BusinessRuleException(
                ReservationsErrorCodes.RESERVATION_STAY_ENDED,
                "A estadia da reserva ${reservation.code} já terminou: a UH não é mais trocada.",
            )
        }
    }

    fun requireOtherRoom(
        current: RoomAssignmentSummary,
        move: RoomMove,
    ) {
        if (current.roomId == move.roomId) {
            throw BusinessRuleException(
                ReservationsErrorCodes.ROOM_MOVE_SAME_ROOM,
                "O hóspede já está nesta UH.",
            )
        }
    }

    /** O período da alocação nova: da data operacional à saída (no day use, a própria data). */
    fun newPeriod(
        reservation: ReservationSummary,
        businessDate: LocalDate,
    ): AssignmentPeriod =
        if (reservation.dayUse) {
            AssignmentPeriod(reservation.arrival, reservation.departure)
        } else {
            AssignmentPeriod(businessDate, reservation.departure)
        }

    /**
     * Até quando a alocação atual fica (a data operacional; no day use ou com a alocação começando nela, o
     * fim atual).
     */
    fun oldEnd(
        current: RoomAssignmentSummary,
        businessDate: LocalDate,
    ): LocalDate = if (current.dayUse) current.endsOn else maxOf(current.startsOn, businessDate)

    /**
     * As noites que a tarifa recalculada reprecifica: as da data operacional em diante (no day use, a
     * própria data).
     */
    fun repricedNights(
        reservation: ReservationSummary,
        businessDate: LocalDate,
    ): List<LocalDate> =
        if (reservation.dayUse) {
            listOf(reservation.arrival)
        } else {
            Stays.nights(reservation.arrival, reservation.departure).filter { !it.isBefore(businessDate) }
        }
}
