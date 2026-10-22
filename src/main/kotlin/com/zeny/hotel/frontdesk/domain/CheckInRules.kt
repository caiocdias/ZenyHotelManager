package com.zeny.hotel.frontdesk.domain

import com.zeny.hotel.files.FileSummary
import com.zeny.hotel.frontdesk.EarlyLateOffer
import com.zeny.hotel.frontdesk.FrontdeskErrorCodes
import com.zeny.hotel.guests.DocumentType
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestIdentification
import com.zeny.hotel.guests.GuestsFilePurposes
import com.zeny.hotel.hotel.EarlyLateTerms
import com.zeny.hotel.hotel.PolicySettings
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.rooms.RoomStatus
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Period
import java.time.ZoneId

/** O que falta a um hóspede para o check-in (H11). */
enum class MissingGuestData {
    /** A nacionalidade do perfil. */
    NATIONALITY,

    /** A data de nascimento (sem ela a menoridade não se confere, RN-24; a FNRH a exige de todos). */
    BIRTH_DATE,

    /** Um documento aceito para a nacionalidade ([IncompleteGuest.acceptedDocumentTypes]). */
    DOCUMENT,
}

/** Um hóspede do pedido sem os dados mínimos: o id pedido, o que falta e os documentos que valeriam. */
data class IncompleteGuest(
    val guestId: GuestId,
    val missing: List<MissingGuestData>,
    val acceptedDocumentTypes: List<DocumentType>,
)

/**
 * A regra mínima de dados do hóspede no check-in (M5 E02, H11), conferida sobre a identificação **sem número** da
 * `GuestsApi`: cada hóspede com a nacionalidade, a data de nascimento e ao menos um documento aceito — o CPF do
 * brasileiro adulto; o CPF ou o RG do brasileiro menor; o passaporte, o RNM ou a identidade estrangeira do estrangeiro
 * (sem nacionalidade, qualquer um deles). A
 * regra fina da FNRH (domínios, formato, campos de endereço) é do `fnrh`, no envio (H21), e nunca bloqueia o check-in.
 */
object GuestDataRules {
    const val BRAZIL = "BR"

    val BRAZILIAN_DOCUMENTS: List<DocumentType> = listOf(DocumentType.CPF)
    val FOREIGN_DOCUMENTS: List<DocumentType> = listOf(DocumentType.PASSPORT, DocumentType.RNM, DocumentType.FOREIGN_ID)

    /** O menor brasileiro também entra com o RG (B2 da revisão humana: a criança sem CPF não bloqueia a família). */
    val BRAZILIAN_MINOR_DOCUMENTS: List<DocumentType> = listOf(DocumentType.CPF, DocumentType.RG)

    const val ADULT_AGE = 18

    /**
     * Os documentos aceitos pela [nationality] e pela menoridade ([minor]): o brasileiro adulto, o CPF; o brasileiro
     * menor, o CPF ou o RG; o estrangeiro, o passaporte, o RNM ou a identidade estrangeira; sem nacionalidade, qualquer
     * um dos do brasileiro (da idade) e do estrangeiro.
     */
    fun acceptedDocuments(
        nationality: String?,
        minor: Boolean = false,
    ): List<DocumentType> {
        val brazilian = if (minor) BRAZILIAN_MINOR_DOCUMENTS else BRAZILIAN_DOCUMENTS
        return when (nationality) {
            null -> brazilian + FOREIGN_DOCUMENTS
            BRAZIL -> brazilian
            else -> FOREIGN_DOCUMENTS
        }
    }

    /** Menor de idade na data (RN-24): menos de 18 anos completos, como `GuestsApi.isMinorOn` (`Period`). */
    fun isMinor(
        birthDate: LocalDate?,
        on: LocalDate,
    ): Boolean = birthDate != null && Period.between(birthDate, on).years < ADULT_AGE

    /**
     * O que falta ao hóspede pedido como [requested], identificado por [identification], na data operacional
     * [businessDate] (a da menoridade); `null` se nada falta.
     */
    fun incomplete(
        requested: GuestId,
        identification: GuestIdentification,
        businessDate: LocalDate,
    ): IncompleteGuest? {
        val accepted =
            acceptedDocuments(identification.nationality, isMinor(identification.birthDate, businessDate))
        val missing =
            buildList {
                if (identification.nationality == null) add(MissingGuestData.NATIONALITY)
                if (identification.birthDate == null) add(MissingGuestData.BIRTH_DATE)
                if (identification.documents.none { it.type in accepted }) add(MissingGuestData.DOCUMENT)
            }
        return if (missing.isEmpty()) null else IncompleteGuest(requested, missing, accepted)
    }

    /** `422 GUEST_DATA_INCOMPLETE` com todos os hóspedes incompletos de uma vez. */
    fun require(incomplete: List<IncompleteGuest>) {
        if (incomplete.isNotEmpty()) throw GuestDataIncompleteException(incomplete)
    }
}

/** `422 GUEST_DATA_INCOMPLETE`: os hóspedes e o que falta em `incompleteGuests`, sem nome nem número de documento. */
class GuestDataIncompleteException(
    val guests: List<IncompleteGuest>,
) : BusinessRuleException(
        FrontdeskErrorCodes.GUEST_DATA_INCOMPLETE,
        "Faltam dados de ${guests.size} hóspede(s) para o check-in: complete o cadastro (nacionalidade, data de " +
            "nascimento e documento) antes.",
    ) {
    override val problemProperties: Map<String, Any>
        get() =
            mapOf(
                "incompleteGuests" to
                    guests.map { guest ->
                        mapOf(
                            "guestId" to guest.guestId.toString(),
                            "missing" to guest.missing.map { it.name },
                            "acceptedDocumentTypes" to guest.acceptedDocumentTypes.map { it.name },
                        )
                    },
            )
}

/**
 * O anexo da comprovação do menor (RN-24, RF-REC-08; M5 E02): o `reservations` guarda o id opaco, e a recepção confere
 * que ele existe, é de uma das finalidades de autorização do `guests` ([PURPOSES]) e está vinculado ao perfil do menor
 * (o pedido ou o corrente, num mesclado).
 */
object MinorAttachmentRules {
    val PURPOSES = setOf(GuestsFilePurposes.MINOR_AUTHORIZATION_PHOTO, GuestsFilePurposes.DOCUMENT_PDF)

    fun valid(
        file: FileSummary?,
        owners: Set<GuestId>,
    ): Boolean = file != null && file.purpose in PURPOSES && file.ownerEntityId in owners.map { it.toString() }

    fun invalid(
        guestId: GuestId,
        field: String,
    ): MinorAttachmentInvalidException = MinorAttachmentInvalidException(guestId, field)
}

/** `422 MINOR_AUTHORIZATION_ATTACHMENT_INVALID`, com o menor em `guestId` e o campo em `field`. */
class MinorAttachmentInvalidException(
    val guestId: GuestId,
    val fieldPath: String,
) : BusinessRuleException(
        FrontdeskErrorCodes.MINOR_AUTHORIZATION_ATTACHMENT_INVALID,
        "O anexo da comprovação do menor não serve: envie a autorização (foto ou PDF) e vincule-a ao perfil do menor.",
    ) {
    override val problemProperties: Map<String, Any> get() =
        mapOf(
            "guestId" to guestId.toString(),
            "field" to fieldPath,
        )
}

/** `409 ROOM_NOT_READY`, com a situação da UH (sem dado de hóspede). */
class RoomNotReadyException(
    val status: RoomStatus,
) : ConflictException(
        FrontdeskErrorCodes.ROOM_NOT_READY,
        "A UH alocada não está pronta para o check-in (precisa estar vaga, fora de OOO e inspecionada, ou limpa sem " +
            "inspeção pendente).",
    ) {
    override val problemProperties: Map<String, Any>
        get() =
            buildMap {
                put("roomId", status.roomId.toString())
                put("active", status.active)
                put("occupancy", status.occupancy.name)
                put("housekeepingStatus", status.housekeepingStatus.name)
                put("inspectionPending", status.inspectionPending)
                status.outOfOrder?.let { put("outOfOrder", it.name) }
            }
}

/**
 * As transições de hospedagem como a recepção as confere **antes** de chamar o `reservations` (que confere de novo com
 * a reserva travada, e é a autoridade): para devolver o erro certo antes dos outros passos (e antes de pedir a
 * aprovação de superior no desfazer).
 */
object StayRules {
    const val CHECK_IN = "CHECK_IN"
    const val UNDO_CHECK_IN = "UNDO_CHECK_IN"

    /** Só uma confirmada (`409 RESERVATION_TRANSITION_NOT_ALLOWED`) que já chegou (`422 CHECK_IN_BEFORE_ARRIVAL`). */
    fun requireCheckIn(
        reservation: ReservationSummary,
        businessDate: LocalDate,
    ) {
        if (reservation.status != ReservationStatus.CONFIRMED) {
            throw StayTransitionNotAllowedException(reservation.status, CHECK_IN)
        }
        if (reservation.arrival.isAfter(businessDate)) {
            throw BusinessRuleException(
                FrontdeskErrorCodes.CHECK_IN_BEFORE_ARRIVAL,
                "A chegada da reserva ${reservation.code} ainda não chegou: altere a chegada antes do check-in.",
            )
        }
    }

    /** Só uma hospedada (`409`), na mesma data operacional do check-in (`422 UNDO_CHECK_IN_EXPIRED`). */
    fun requireUndo(
        reservation: ReservationSummary,
        businessDate: LocalDate,
    ) {
        if (reservation.status != ReservationStatus.IN_HOUSE) {
            throw StayTransitionNotAllowedException(reservation.status, UNDO_CHECK_IN)
        }
        if (reservation.checkIn?.businessDate != businessDate) {
            throw BusinessRuleException(
                FrontdeskErrorCodes.UNDO_CHECK_IN_EXPIRED,
                "O check-in só pode ser desfeito na mesma data operacional em que foi feito.",
            )
        }
    }

    /** O número de hóspedes que ocupa a UH: o informado (de 1 a 40, não abaixo da lista) ou o tamanho da lista. */
    fun guestCount(
        requested: Int?,
        listed: Int,
    ): Int {
        val count = requested ?: listed
        if (count !in 1..MAX_GUEST_COUNT || count < listed) {
            throw InvalidInputException(
                FrontdeskErrorCodes.CHECK_IN_REQUEST_INVALID,
                "O número de hóspedes vai de 1 a $MAX_GUEST_COUNT e não fica abaixo da lista de hóspedes.",
                listOf(
                    FieldViolation(
                        "guestCount",
                        FrontdeskErrorCodes.CHECK_IN_REQUEST_INVALID.value,
                        "O número de hóspedes não pode ficar abaixo da lista de hóspedes.",
                    ),
                ),
            )
        }
        return count
    }

    const val MAX_GUEST_COUNT = 40
}

/** `409 RESERVATION_TRANSITION_NOT_ALLOWED`, como o do `reservations`: `reservationStatus` e `action`. */
class StayTransitionNotAllowedException(
    val status: ReservationStatus,
    val action: String,
) : ConflictException(
        FrontdeskErrorCodes.RESERVATION_TRANSITION_NOT_ALLOWED,
        if (action == StayRules.CHECK_IN) {
            "Só uma reserva confirmada faz o check-in (a pré-reserva é confirmada antes)."
        } else {
            "Só uma reserva hospedada tem o check-in desfeito."
        },
    ) {
    override val problemProperties: Map<String, Any>
        get() = mapOf("reservationStatus" to status.name, "action" to action)
}

/** A cotação de early/late de uma reserva pela política vigente do hotel (RN-11; a cobrança é da E04 e da E05). */
object EarlyLateQuotes {
    fun early(
        policy: PolicySettings,
        arrival: LocalDate,
        zone: ZoneId,
    ): EarlyLateOffer =
        offer(policy.earlyCheckIn) { terms ->
            instant(arrival, terms.limitTime, zone) to instant(arrival, policy.checkInTime, zone)
        }

    fun late(
        policy: PolicySettings,
        departure: LocalDate,
        zone: ZoneId,
    ): EarlyLateOffer =
        offer(policy.lateCheckOut) { terms ->
            instant(departure, policy.checkOutTime, zone) to instant(departure, terms.limitTime, zone)
        }

    private fun offer(
        terms: EarlyLateTerms?,
        window: (EarlyLateTerms) -> Pair<Instant, Instant>,
    ): EarlyLateOffer =
        if (terms == null) {
            EarlyLateOffer(offered = false, limitTime = null, fee = null, from = null, until = null)
        } else {
            val (from, until) = window(terms)
            EarlyLateOffer(offered = true, limitTime = terms.limitTime, fee = terms.fee, from = from, until = until)
        }

    private fun instant(
        date: LocalDate,
        time: LocalTime,
        zone: ZoneId,
    ): Instant = date.atTime(time).atZone(zone).toInstant()
}
