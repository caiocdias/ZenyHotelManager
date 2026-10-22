package com.zeny.hotel.reservations

import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Como a menoridade de um hóspede foi comprovada no check-in (RN-24, SPEC §8.2.6 `MinorAuthorization.proofType`):
 * [PARENT_PRESENT] o pai, a mãe ou o responsável legal está hospedado junto; [WRITTEN_AUTHORIZATION] uma autorização
 * escrita dos pais, anexada; [COURT_ORDER] uma autorização judicial, anexada. Nas respostas é `x-extensible-enum`.
 */
enum class MinorProofType {
    PARENT_PRESENT,
    WRITTEN_AUTHORIZATION,
    COURT_ORDER,
    ;

    /** A autorização escrita e a judicial exigem o anexo; a presença dos pais, não. */
    val requiresAttachment: Boolean get() = this != PARENT_PRESENT
}

/**
 * A comprovação do menor (RN-24; M5 E01, H10): o [proofType], o hóspede adulto da mesma estadia responsável por ele
 * ([responsibleGuestId]), o parentesco ([relationship], código opaco do domínio da FNRH, conferido pelo `fnrh` no
 * envio), o anexo ([attachmentId], id opaco de um anexo do `files`, obrigatório na autorização escrita e na judicial;
 * quem chama confere a finalidade) e a validade opcional da autorização ([validUntil], não antes da data operacional).
 */
data class MinorAuthorization(
    val proofType: MinorProofType,
    val responsibleGuestId: GuestId,
    val relationship: String,
    val attachmentId: UUID? = null,
    val validUntil: LocalDate? = null,
)

/**
 * Um hóspede no check-in (M5 E01, H10): o perfil, se é o principal, o motivo de viagem e o meio de transporte (códigos
 * opacos dos domínios da FNRH, H21: o `reservations` só confere o formato) e, se for menor na data operacional, a
 * comprovação.
 */
data class CheckInGuest(
    val guestId: GuestId,
    val primary: Boolean = false,
    val travelReason: String? = null,
    val transportMode: String? = null,
    val minorAuthorization: MinorAuthorization? = null,
)

/** O check-in de uma reserva (M5 E01): a lista **completa** dos hóspedes da estadia, com os dados de check-in. */
data class CheckIn(
    val guests: List<CheckInGuest>,
)

/** Uma transição de hospedagem gravada na reserva: a data operacional, o instante e quem (nulo = sistema). */
data class StayTransitionRecord(
    val businessDate: LocalDate,
    val at: Instant,
    val by: UserId?,
)

/** O que fazer com as diárias das noites restantes na troca de UH do hospedado (M5 E01). */
enum class RoomMoveTariff {
    /** As noites ficam como estão (o preço congelado, RN-14). */
    KEEP,

    /** As noites a partir da data operacional são reprecificadas pela tarifa atual; as anteriores ficam. */
    RECALCULATE,
}

/**
 * A troca de UH do hospedado (M5 E01; SPEC §8.2.6 `moveRoom(room, reason)`): a UH nova (do tipo da reserva, nesta
 * etapa), o motivo (`GUEST_REQUEST`, `OUT_OF_ORDER`, `ROOM_ISSUE` ou `OPERATIONAL`; `INITIAL` é recusado) e a tarifa.
 */
data class RoomMove(
    val roomId: RoomId,
    val reason: RoomAssignmentReason,
    val tariff: RoomMoveTariff = RoomMoveTariff.KEEP,
)
