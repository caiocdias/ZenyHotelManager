package com.zeny.hotel.frontdesk.web

import com.zeny.hotel.frontdesk.TraceStatus
import com.zeny.hotel.frontdesk.application.NewTraceCommand
import com.zeny.hotel.frontdesk.application.TraceChangeCommand
import com.zeny.hotel.frontdesk.application.TraceView
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.staff.DepartmentId
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val TEXT_DESCRIPTION =
    "O recado (texto livre, aparado, de 1 a 500 caracteres; senão 400 `TRACE_INVALID` no campo `text`). Nunca vai à " +
        "trilha, a logs nem a problemas."
private const val ASSIGNEE_DESCRIPTION =
    "O responsável opcional: o id de um usuário vinculado a um colaborador (senão 400 `UNKNOWN_ASSIGNEE`)."
private const val DUE_ON_DESCRIPTION =
    "O vencimento (data operacional, `YYYY-MM-DD`): não antes da data operacional do hotel (senão 400 " +
        "`TRACE_INVALID` no campo `dueOn`)."

// --- Pedidos --------------------------------------------------------------------------------------------------------

@Schema(
    description =
        "Um recado para um setor, vinculado a uma reserva **ou** a uma UH (exatamente um dos dois, fixo depois da " +
            "criação), com o vencimento e o responsável opcional.",
)
data class TraceRequest(
    @field:NotNull
    val departmentId: UUID?,
    @field:NotNull
    @field:Schema(description = DUE_ON_DESCRIPTION)
    val dueOn: LocalDate?,
    @field:NotNull
    @field:Schema(description = TEXT_DESCRIPTION)
    val text: String?,
    @field:Schema(description = "A reserva do recado (qualquer situação); sem `roomId`.", nullable = true)
    val reservationId: UUID? = null,
    @field:Schema(description = "A UH do recado (ativa); sem `reservationId`.", nullable = true)
    val roomId: UUID? = null,
    @field:Schema(description = ASSIGNEE_DESCRIPTION, nullable = true)
    val assigneeUserId: UUID? = null,
) {
    fun toCommand() =
        NewTraceCommand(
            departmentId = DepartmentId(checkNotNull(departmentId)),
            dueOn = checkNotNull(dueOn),
            text = checkNotNull(text),
            reservationId = reservationId?.let(::ReservationId),
            roomId = roomId?.let(::RoomId),
            assigneeUserId = assigneeUserId?.let(::UserId),
        )

    /** Sem o texto (RN-13). */
    override fun toString(): String = "TraceRequest(departmentId=$departmentId, dueOn=$dueOn)"
}

@Schema(
    description =
        "A alteração de um recado aberto: o setor, o vencimento, o texto e o responsável (sem ele, o recado fica sem " +
            "responsável). O vínculo não muda.",
)
data class TraceChangeRequest(
    @field:NotNull
    val departmentId: UUID?,
    @field:NotNull
    @field:Schema(description = "$DUE_ON_DESCRIPTION Conferido só quando muda.")
    val dueOn: LocalDate?,
    @field:NotNull
    @field:Schema(description = TEXT_DESCRIPTION)
    val text: String?,
    @field:Schema(description = ASSIGNEE_DESCRIPTION, nullable = true)
    val assigneeUserId: UUID? = null,
) {
    fun toCommand() =
        TraceChangeCommand(
            departmentId = DepartmentId(checkNotNull(departmentId)),
            dueOn = checkNotNull(dueOn),
            text = checkNotNull(text),
            assigneeUserId = assigneeUserId?.let(::UserId),
        )

    /** Sem o texto (RN-13). */
    override fun toString(): String = "TraceChangeRequest(departmentId=$departmentId, dueOn=$dueOn)"
}

// --- Resposta -------------------------------------------------------------------------------------------------------

@Schema(
    description =
        "Um recado da recepção para um setor, com os nomes do setor, da UH e do responsável lidos na hora da consulta.",
)
data class TraceResponse(
    val id: UUID,
    val departmentId: UUID,
    val departmentName: String,
    val dueOn: LocalDate,
    val text: String,
    @field:Schema(nullable = true)
    val reservationId: UUID?,
    @field:Schema(nullable = true)
    val roomId: UUID?,
    @field:Schema(description = "O número da UH do vínculo (nulo no recado de reserva).", nullable = true)
    val roomNumber: String?,
    @field:Schema(nullable = true)
    val assigneeUserId: UUID?,
    @field:Schema(
        description = "O nome do colaborador do responsável (nulo se o usuário não tem mais colaborador).",
        nullable = true,
    )
    val assigneeName: String?,
    @field:ExtensibleEnum
    @field:Schema(
        description =
            "`OPEN` (sem resolução) ou `RESOLVED`. A lista pode crescer (`x-extensible-enum`): trate um valor " +
                "desconhecido sem falhar.",
    )
    val status: TraceStatus,
    val createdAt: Instant,
    val createdBy: UUID,
    @field:Schema(nullable = true)
    val resolvedAt: Instant?,
    @field:Schema(nullable = true)
    val resolvedBy: UUID?,
    val version: Long,
) {
    /** Sem o texto (RN-13). */
    override fun toString(): String = "TraceResponse(id=$id, departmentId=$departmentId, status=$status)"

    companion object {
        fun of(view: TraceView) =
            TraceResponse(
                id = view.id.value,
                departmentId = view.departmentId.value,
                departmentName = view.departmentName,
                dueOn = view.dueOn,
                text = view.text,
                reservationId = view.reservationId?.value,
                roomId = view.roomId?.value,
                roomNumber = view.roomNumber,
                assigneeUserId = view.assigneeUserId?.value,
                assigneeName = view.assigneeName,
                status = view.status,
                createdAt = view.createdAt,
                createdBy = view.createdBy.value,
                resolvedAt = view.resolvedAt,
                resolvedBy = view.resolvedBy?.value,
                version = view.version,
            )
    }
}
