package com.zeny.hotel.reservations.web

import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.RoomAssignmentEndReason
import com.zeny.hotel.reservations.RoomAssignmentReason
import com.zeny.hotel.reservations.RoomAssignmentSummary
import com.zeny.hotel.reservations.application.ReallocationView
import com.zeny.hotel.reservations.application.ReservationView
import com.zeny.hotel.reservations.application.RoomAssignmentService
import com.zeny.hotel.reservations.application.RoomAssignmentView
import com.zeny.hotel.reservations.application.RoomSuggestionsView
import com.zeny.hotel.rooms.RoomFeatureId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.PageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val EXTENSIBLE = "A lista pode crescer (`x-extensible-enum`): trate um valor desconhecido sem falhar."

@Schema(
    description =
        "A marca \"requer realocação\" (RN-07): a UH alocada entrou num OOO que atinge a estadia. Sai " +
            "sozinha se o OOO deixar de atingir a estadia (liberado ou alterado).",
)
data class ReallocationMarkResponse(
    @field:Schema(description = "O período OOO (`rooms`) que provocou a marca.")
    val outOfOrderId: UUID,
    val markedAt: Instant,
)

@Schema(
    description =
        "Uma alocação de UH a uma reserva: a UH, o período (de `startsOn` à saída `endsOn`; no day use, as " +
            "duas são a data), o motivo, \"não mover\", a marca de realocação e, se foi solta, quando, por quem " +
            "e por quê.",
)
data class RoomAssignmentResponse(
    val id: UUID,
    val reservationId: UUID,
    val roomId: UUID,
    @field:Schema(description = "O número atual da UH; nulo se a UH não existe mais.")
    val roomNumber: String?,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
    val dayUse: Boolean,
    @field:ExtensibleEnum
    @field:Schema(
        description =
            "`INITIAL` (a primeira alocação), `GUEST_REQUEST`, `OUT_OF_ORDER`, `ROOM_ISSUE` ou `OPERATIONAL`. " +
                EXTENSIBLE,
    )
    val reason: RoomAssignmentReason,
    @field:Schema(description = "\"Não mover\": a UH não é trocada nem solta sem tirar a marca antes.")
    val doNotMove: Boolean,
    val reallocation: ReallocationMarkResponse?,
    val active: Boolean,
    val assignedAt: Instant,
    val assignedBy: UUID?,
    val releasedAt: Instant?,
    val releasedBy: UUID?,
    @field:ExtensibleEnum
    @field:Schema(
        description =
            "Por que deixou de valer: `UNASSIGNED`, `REALLOCATED`, `RESERVATION_CANCELLED`, `RESERVATION_CHANGED`, " +
                "`CHECKED_OUT` (o check-out) ou `ROOM_MOVED` (a troca de UH do hospedado); " +
                "nulo na ativa. $EXTENSIBLE",
    )
    val releaseReason: RoomAssignmentEndReason?,
) {
    companion object {
        fun of(
            assignment: RoomAssignmentSummary,
            roomNumber: String?,
        ) = RoomAssignmentResponse(
            id = assignment.id.value,
            reservationId = assignment.reservationId.value,
            roomId = assignment.roomId.value,
            roomNumber = roomNumber,
            startsOn = assignment.startsOn,
            endsOn = assignment.endsOn,
            dayUse = assignment.dayUse,
            reason = assignment.reason,
            doNotMove = assignment.doNotMove,
            reallocation =
                assignment.reallocation?.let { ReallocationMarkResponse(it.outOfOrderId.value, it.markedAt) },
            active = assignment.active,
            assignedAt = assignment.assignedAt,
            assignedBy = assignment.assignedBy?.value,
            releasedAt = assignment.releasedAt,
            releasedBy = assignment.releasedBy?.value,
            releaseReason = assignment.releaseReason,
        )

        fun of(view: RoomAssignmentView) = of(view.assignment, view.roomNumber)
    }
}

@Schema(description = "A UH a alocar (ou a nova UH, numa realocação).")
data class RoomAssignRequest(
    @field:NotNull
    val roomId: UUID?,
    @field:Schema(
        description =
            "Motivo; sem ele, `INITIAL` na primeira alocação e `OPERATIONAL` numa realocação.",
    )
    val reason: RoomAssignmentReason? = null,
    @field:Schema(description = "Marca \"não mover\" na alocação; sem ele, falso numa UH nova e mantido na mesma UH.")
    val doNotMove: Boolean? = null,
)

@Schema(description = "Marca ou desmarca \"não mover\" na UH alocada.")
data class DoNotMoveRequest(
    @field:NotNull
    val doNotMove: Boolean?,
)

@Schema(description = "Uma UH sugerida: livre no período e sem OOO.")
data class RoomSuggestionResponse(
    val roomId: UUID,
    val number: String,
    val floor: String?,
    val featureIds: List<UUID>,
    @field:Schema(description = "As características pedidas que a UH tem.")
    val matchedFeatureIds: List<UUID>,
    @field:Schema(description = "Se o andar da UH é o preferido pelo hóspede principal.")
    val matchesFloorPreference: Boolean,
    @field:Schema(description = "A UH tem OOS em alguma noite da estadia (vendável, mas como última opção).")
    val outOfService: Boolean,
    @field:Schema(description = "É a UH já alocada à reserva.")
    val current: Boolean,
)

@Schema(
    description =
        "As UHs que a reserva pode receber, em ordem de sugestão: as ativas do tipo da reserva, livres em todo o " +
            "período e sem OOO; as com OOS por último; depois, as que têm mais das características pedidas; depois, " +
            "o andar preferido pelo hóspede principal; por fim, o número.",
)
data class RoomSuggestionsResponse(
    val reservationId: UUID,
    val roomTypeId: UUID,
    val arrival: LocalDate,
    val departure: LocalDate,
    @field:Schema(description = "Se o hóspede principal tem preferência de andar (considerada na ordem).")
    val floorPreferenceConsidered: Boolean,
    val items: List<RoomSuggestionResponse>,
) {
    companion object {
        fun of(view: RoomSuggestionsView) =
            RoomSuggestionsResponse(
                reservationId = view.reservation.id.value,
                roomTypeId = view.reservation.roomTypeId.value,
                arrival = view.reservation.arrival,
                departure = view.reservation.departure,
                floorPreferenceConsidered = view.floorPreferenceConsidered,
                items =
                    view.suggestions.map {
                        RoomSuggestionResponse(
                            roomId = it.room.id.value,
                            number = it.room.number,
                            floor = it.room.floor,
                            featureIds =
                                it.room.featureIds
                                    .map { id -> id.value }
                                    .sortedBy(UUID::toString),
                            matchedFeatureIds =
                                it.matchedFeatureIds
                                    .map { id ->
                                        id.value
                                    }.sortedBy(UUID::toString),
                            matchesFloorPreference = it.matchesFloorPreference,
                            outOfService = it.outOfService,
                            current = it.current,
                        )
                    },
            )
    }
}

@Schema(description = "As alocações de uma reserva, da mais recente à mais antiga (a ativa e as soltas).")
data class RoomAssignmentsResponse(
    val items: List<RoomAssignmentResponse>,
)

@Schema(description = "Uma reserva com a UH alocada marcada \"requer realocação\" (RN-07).")
data class ReallocationRequiredResponse(
    val reservationId: UUID,
    val reservationCode: String,
    @field:ExtensibleEnum
    @field:Schema(description = "Situação da reserva. $EXTENSIBLE")
    val status: ReservationStatus,
    val arrival: LocalDate,
    val departure: LocalDate,
    val assignment: RoomAssignmentResponse,
) {
    companion object {
        fun of(view: ReallocationView) =
            ReallocationRequiredResponse(
                reservationId = view.row.assignment.reservationId.value,
                reservationCode = view.row.reservationCode.toString(),
                status = view.row.status,
                arrival = view.row.arrival,
                departure = view.row.departure,
                assignment = RoomAssignmentResponse.of(view.row.assignment, view.roomNumber),
            )
    }
}

private const val ASSIGNABLE =
    "Só numa pré-reserva ou confirmada (senão 409 `ROOM_ASSIGNMENT_NOT_ALLOWED`; a troca de UH do hóspede em casa é " +
        "da recepção) com a estadia ainda por vir (422 `RESERVATION_STAY_ENDED`). Exige " +
        "`reservations.assignments.manage`."

/**
 * A alocação de UH das reservas (M4 E10; RF-RES-04, RN-04, RN-07, RF-UH-05): alocar e realocar, desalocar, "não mover",
 * as sugestões e o histórico de uma reserva.
 */
@ApiV1Controller
@RequestMapping("/reservations")
@Tag(name = RESERVATIONS_TAG, description = RESERVATIONS_TAG_DESCRIPTION)
class ReservationRoomsController(
    private val assignments: RoomAssignmentService,
) {
    @PostMapping("/{id}/assign-room")
    @Operation(
        operationId = "assignReservationRoom",
        summary = "Alocar (ou realocar) a UH de uma reserva",
        description =
            "Aloca a UH à reserva pela estadia inteira (de `arrival` a `departure`; no day use, a data) ou, se a " +
                "reserva já tem outra UH, a troca (a anterior fica solta como `REALLOCATED`; com \"não mover\", 409 " +
                "`ROOM_ASSIGNMENT_LOCKED`). $ASSIGNABLE A UH existe (400 `UNKNOWN_ROOM`, campo `roomId`), está ativa " +
                "(422 `ROOM_INACTIVE`) e é do tipo da reserva (422 `ROOM_TYPE_MISMATCH`: altere antes o tipo da " +
                "reserva), sem OOO em nenhuma noite (422 `ROOM_OUT_OF_ORDER`, com `dates`; o OOS não impede) e sem " +
                "outra reserva alocada em parte do período (409 `ROOM_ALREADY_ASSIGNED`, garantido pelo banco). A " +
                "saída de uma reserva e a chegada de outra no mesmo dia não conflitam; o day use não conflita com a " +
                "saída nem com a chegada de outras reservas na data, só com a estadia que atravessa a data e com " +
                "outro day use. A mesma UH de novo só troca " +
                "\"não mover\", se informado. Devolve a reserva com a UH alocada e o `ETag` novo.",
    )
    fun assign(
        @PathVariable id: UUID,
        @Valid @RequestBody request: RoomAssignRequest,
    ): ResponseEntity<ReservationDetailResponse> =
        detail(
            assignments.assign(
                ReservationId(id),
                RoomId(checkNotNull(request.roomId)),
                request.reason,
                request.doNotMove,
            ),
        )

    @PostMapping("/{id}/unassign-room")
    @Operation(
        operationId = "unassignReservationRoom",
        summary = "Desalocar a UH de uma reserva",
        description =
            "Solta a UH alocada (fica no histórico como `UNASSIGNED` e não bloqueia mais a UH). $ASSIGNABLE Sem UH " +
                "alocada, 409 `ROOM_NOT_ASSIGNED`; com \"não mover\", 409 `ROOM_ASSIGNMENT_LOCKED`.",
    )
    fun unassign(
        @PathVariable id: UUID,
    ): ResponseEntity<ReservationDetailResponse> = detail(assignments.unassign(ReservationId(id)))

    @PostMapping("/{id}/do-not-move")
    @Operation(
        operationId = "setReservationRoomDoNotMove",
        summary = "Marcar ou desmarcar \"não mover\" a UH alocada",
        description =
            "Com a marca, a UH não é trocada nem solta (nem pela alteração da reserva) até ela sair; o cancelamento " +
                "solta a UH mesmo assim. $ASSIGNABLE Sem UH alocada, 409 `ROOM_NOT_ASSIGNED`.",
    )
    fun doNotMove(
        @PathVariable id: UUID,
        @Valid @RequestBody request: DoNotMoveRequest,
    ): ResponseEntity<ReservationDetailResponse> =
        detail(assignments.setDoNotMove(ReservationId(id), checkNotNull(request.doNotMove)))

    @GetMapping("/{id}/room-suggestions")
    @Operation(
        operationId = "listReservationRoomSuggestions",
        summary = "Sugestões de UH para uma reserva",
        description =
            "As UHs ativas do tipo da reserva, livres em todo o período e sem OOO em nenhuma noite, em ordem: as com " +
                "OOS por último; depois, as que têm mais das características pedidas (`featureIds`, até 20, senão " +
                "400 `ROOM_SUGGESTION_INVALID`); depois, o andar preferido pelo hóspede principal (pelo perfil do " +
                "hóspede); por fim, o número. $ASSIGNABLE",
    )
    fun suggestions(
        @PathVariable id: UUID,
        @RequestParam(required = false) featureIds: List<UUID>?,
    ): RoomSuggestionsResponse =
        RoomSuggestionsResponse.of(
            assignments.suggestions(ReservationId(id), featureIds.orEmpty().map(::RoomFeatureId)),
        )

    @GetMapping("/{id}/room-assignments")
    @Operation(
        operationId = "listReservationRoomAssignments",
        summary = "Histórico das UHs alocadas a uma reserva",
        description =
            "Todas as alocações da reserva, da mais recente à mais antiga (a ativa e as soltas, com o motivo). Exige " +
                "`reservations.reservations.read`. Inexistente: 404 `RESERVATION_NOT_FOUND`.",
    )
    fun history(
        @PathVariable id: UUID,
    ): RoomAssignmentsResponse =
        RoomAssignmentsResponse(assignments.history(ReservationId(id)).map(RoomAssignmentResponse::of))

    private fun detail(view: ReservationView): ResponseEntity<ReservationDetailResponse> =
        ResponseEntity
            .ok()
            .eTag(ETags.of(view.reservation.version))
            .body(ReservationDetailResponse.of(view))
}

/** As alocações que requerem realocação (RN-07, H11). */
@ApiV1Controller
@RequestMapping("/room-assignments")
@Tag(name = RESERVATIONS_TAG, description = RESERVATIONS_TAG_DESCRIPTION)
class RoomAssignmentsController(
    private val assignments: RoomAssignmentService,
) {
    @GetMapping("/reallocation-required")
    @Operation(
        operationId = "listReallocationRequired",
        summary = "Reservas que requerem realocação",
        description =
            "As reservas cuja UH alocada entrou num OOO que atinge a estadia (RN-07), da estadia que começa " +
                "primeiro, por cursor. A marca sai quando a reserva é realocada, desalocada ou cancelada, ou quando " +
                "o OOO deixa de atingir a estadia. Exige `reservations.reservations.read`.",
    )
    fun reallocationRequired(
        @Valid page: PageParams,
    ): CursorPage<ReallocationRequiredResponse> =
        assignments.reallocationRequired(page).map(ReallocationRequiredResponse::of)
}
