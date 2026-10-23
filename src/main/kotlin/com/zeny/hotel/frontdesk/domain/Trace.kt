package com.zeny.hotel.frontdesk.domain

import com.zeny.hotel.frontdesk.FrontdeskErrorCodes
import com.zeny.hotel.frontdesk.TraceId
import com.zeny.hotel.frontdesk.TraceStatus
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.staff.DepartmentId
import java.time.Instant
import java.time.LocalDate

/** A que o recado se refere: uma reserva ou uma UH, nunca os dois (D3; fixo desde a criação). */
sealed interface TraceLink {
    data class Reservation(
        val id: ReservationId,
    ) : TraceLink

    data class Room(
        val id: RoomId,
    ) : TraceLink
}

/**
 * Um recado da recepção para um setor (M5 E06, RF-REC-07). O [text] é texto livre do operador e **nunca** vai à
 * trilha, a eventos, a logs nem a problemas (RN-13): por isso [auditState] leva só o tamanho e [toString] não o mostra.
 * Resolvido, o recado não muda mais.
 */
data class Trace(
    val id: TraceId,
    val departmentId: DepartmentId,
    val dueOn: LocalDate,
    val text: String,
    val link: TraceLink,
    val assigneeUserId: UserId?,
    val createdAt: Instant,
    val createdBy: UserId,
    val updatedAt: Instant,
    val resolvedAt: Instant?,
    val resolvedBy: UserId?,
    val version: Long,
) {
    val status: TraceStatus get() = if (resolvedAt == null) TraceStatus.OPEN else TraceStatus.RESOLVED

    /** Altera setor, vencimento, texto e responsável (D3); `409 TRACE_ALREADY_RESOLVED` se já resolvido. */
    fun change(
        departmentId: DepartmentId,
        dueOn: LocalDate,
        text: String,
        assigneeUserId: UserId?,
        at: Instant,
    ): Trace {
        ensureOpen()
        return copy(
            departmentId = departmentId,
            dueOn = dueOn,
            text = text,
            assigneeUserId = assigneeUserId,
            updatedAt = at,
        )
    }

    /** Resolve o recado; `409 TRACE_ALREADY_RESOLVED` se já resolvido. */
    fun resolve(
        by: UserId,
        at: Instant,
    ): Trace {
        ensureOpen()
        return copy(resolvedAt = at, resolvedBy = by, updatedAt = at)
    }

    /** O retrato da trilha: ids, setor, datas, situação e o tamanho do texto, **sem** o texto (RN-13). */
    fun auditState(): Map<String, Any?> =
        mapOf(
            "departmentId" to departmentId.value,
            "dueOn" to dueOn,
            "textLength" to text.codePointCount(0, text.length),
            "reservationId" to (link as? TraceLink.Reservation)?.id?.value,
            "roomId" to (link as? TraceLink.Room)?.id?.value,
            "assigneeUserId" to assigneeUserId?.value,
            "status" to status.name,
            "resolvedAt" to resolvedAt,
            "resolvedBy" to resolvedBy?.value,
        )

    override fun toString(): String = "Trace(id=$id, departmentId=$departmentId, dueOn=$dueOn, status=$status)"

    private fun ensureOpen() {
        if (resolvedAt != null) {
            throw ConflictException(
                FrontdeskErrorCodes.TRACE_ALREADY_RESOLVED,
                "O recado já foi resolvido e não muda mais.",
            )
        }
    }
}

/** As regras de entrada do recado (D3, D4, D6); o problema `400` diz o campo e a regra, nunca o texto recebido. */
object TraceRules {
    const val MAX_TEXT_LENGTH = 500

    /** O texto aparado, de 1 a [MAX_TEXT_LENGTH] caracteres (D6). */
    fun text(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty() || text.codePointCount(0, text.length) > MAX_TEXT_LENGTH) {
            throw invalid("text", "O texto do recado é obrigatório e tem de 1 a $MAX_TEXT_LENGTH caracteres.")
        }
        return text
    }

    /** O vencimento não pode ser anterior à data operacional (D4). */
    fun dueOn(
        date: LocalDate,
        businessDate: LocalDate,
    ): LocalDate {
        if (date.isBefore(businessDate)) {
            throw invalid("dueOn", "O vencimento do recado não pode ser anterior à data operacional.")
        }
        return date
    }

    /** Exatamente um dos dois: a reserva ou a UH (D3). */
    fun link(
        reservationId: ReservationId?,
        roomId: RoomId?,
    ): TraceLink =
        when {
            reservationId != null && roomId == null -> TraceLink.Reservation(reservationId)
            reservationId == null && roomId != null -> TraceLink.Room(roomId)
            else -> throw invalid("reservationId", "Informe a reserva ou a UH do recado, e apenas uma delas.")
        }

    private fun invalid(
        field: String,
        message: String,
    ) = InvalidInputException(
        FrontdeskErrorCodes.TRACE_INVALID,
        message,
        listOf(FieldViolation(field, FrontdeskErrorCodes.TRACE_INVALID.value, message)),
    )
}

/** `422 ROOM_INACTIVE` do recado: a UH do vínculo está desativada, com o campo em `field` (`roomId`). */
class TraceRoomInactiveException(
    number: String,
) : BusinessRuleException(FrontdeskErrorCodes.ROOM_INACTIVE, "A UH $number está desativada.") {
    override val problemProperties: Map<String, Any> get() = mapOf("field" to "roomId")
}
