package com.zeny.hotel.reservations

import com.zeny.hotel.notifications.ModuleNotificationTypes
import com.zeny.hotel.notifications.NotificationType
import com.zeny.hotel.notifications.NotificationTypeCode
import com.zeny.hotel.rooms.OutOfOrderId
import com.zeny.hotel.rooms.RoomFeatureId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomSummary
import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Id de uma alocação de UH a uma reserva (`RoomAssignment`, SPEC §8.2.6). */
@JvmInline
value class RoomAssignmentId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): RoomAssignmentId = RoomAssignmentId(UuidV7.generate())
    }
}

/**
 * Por que a UH foi alocada (M4 E10; SPEC §8.2.6 `RoomAssignment.reason`): [INITIAL] a primeira alocação da reserva;
 * numa realocação, [GUEST_REQUEST] a pedido do hóspede, [OUT_OF_ORDER] porque a UH entrou em OOO (RN-07),
 * [ROOM_ISSUE] por um problema na UH (OOS, manutenção) e [OPERATIONAL] por organização da recepção. Lista que pode
 * crescer (a troca de UH do hóspede em casa, M5): nas respostas é `x-extensible-enum`.
 */
enum class RoomAssignmentReason {
    INITIAL,
    GUEST_REQUEST,
    OUT_OF_ORDER,
    ROOM_ISSUE,
    OPERATIONAL,
}

/**
 * Por que a alocação deixou de valer (M4 E10): [UNASSIGNED] desalocada por quem opera, [REALLOCATED] substituída por
 * outra UH, [RESERVATION_CANCELLED] a reserva foi cancelada (ou a pré-reserva expirou), [RESERVATION_CHANGED] a
 * alteração da reserva (outro tipo de UH, ou datas em que a UH não está livre) a soltou. A alocação solta continua
 * gravada (o histórico das UHs da reserva) e não bloqueia mais a UH. Nas respostas é `x-extensible-enum`.
 */
enum class RoomAssignmentEndReason {
    UNASSIGNED,
    REALLOCATED,
    RESERVATION_CANCELLED,
    RESERVATION_CHANGED,
}

/**
 * A marca "requer realocação" (RN-07, H11 do M4): a UH alocada entrou num OOO que atinge alguma noite da estadia. Fica
 * na alocação ativa até a reserva ser realocada, desalocada ou cancelada, ou até o OOO deixar de atingir a estadia
 * (liberado ou alterado), quando sai sozinha. [outOfOrderId] é o período que a provocou.
 */
data class ReallocationMark(
    val outOfOrderId: OutOfOrderId,
    val markedAt: Instant,
)

/**
 * Uma alocação de UH a uma reserva (RF-RES-04, RN-04; M4 E10): a UH, o período (de [startsOn] a [endsOn], a saída; no
 * day use os dois são a data, e a alocação ocupa a UH naquela data, H5), o motivo, "não mover" ([doNotMove]: a UH não
 * é trocada nem solta sem tirar a marca antes), a marca de realocação e, quando deixou de valer, quando, por quem e
 * por quê. A reserva tem no máximo uma alocação ativa (`releasedAt` nulo).
 */
data class RoomAssignmentSummary(
    val id: RoomAssignmentId,
    val reservationId: ReservationId,
    val roomId: RoomId,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
    val reason: RoomAssignmentReason,
    val doNotMove: Boolean,
    val reallocation: ReallocationMark?,
    val assignedAt: Instant,
    val assignedBy: UserId?,
    val releasedAt: Instant? = null,
    val releasedBy: UserId? = null,
    val releaseReason: RoomAssignmentEndReason? = null,
) {
    val dayUse: Boolean get() = startsOn == endsOn
    val active: Boolean get() = releasedAt == null
}

/**
 * Uma UH sugerida a uma reserva (M4 E10): livre no período e sem OOO; [outOfService] se tem OOS em alguma noite (fica
 * por último), as características pedidas que ela tem ([matchedFeatureIds]), se o andar é o preferido pelo hóspede
 * principal ([matchesFloorPreference]) e se já é a UH da reserva ([current]).
 */
data class RoomSuggestion(
    val room: RoomSummary,
    val outOfService: Boolean,
    val current: Boolean,
    val matchedFeatureIds: Set<RoomFeatureId>,
    val matchesFloorPreference: Boolean,
)

/**
 * Os tipos de notificação do `reservations` (H11 do M4), publicados como bean para o catálogo do `notifications`.
 * [REALLOCATION_REQUIRED]: um OOO atingiu reservas com UH alocada (RN-07); vai a quem tem
 * `reservations.assignments.manage`, com o número da UH, as datas e os códigos das reservas, sem dado de hóspede. Não
 * obrigatório no painel.
 */
object ReservationsNotificationTypes : ModuleNotificationTypes {
    override val module = "reservations"

    /** Reservas alocadas numa UH que entrou em OOO precisam de realocação. */
    val REALLOCATION_REQUIRED = NotificationTypeCode("reservations.reallocation-required")

    override val types =
        listOf(
            NotificationType(
                REALLOCATION_REQUIRED,
                "Reservas alocadas numa UH que entrou em OOO precisam de realocação",
            ),
        )
}
