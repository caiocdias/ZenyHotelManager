package com.zeny.hotel.frontdesk.web

import com.zeny.hotel.frontdesk.TraceId
import com.zeny.hotel.frontdesk.TraceStatus
import com.zeny.hotel.frontdesk.application.FrontdeskTraceService
import com.zeny.hotel.frontdesk.application.TraceFilter
import com.zeny.hotel.frontdesk.application.TraceView
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.staff.DepartmentId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.net.URI
import java.time.LocalDate
import java.util.UUID

/**
 * Os recados da recepção para os setores (M5 E06; RF-REC-08, RN-13): criar, consultar, listar, alterar e resolver. Não
 * há exclusão: a resolução encerra o recado. O texto nunca vai à trilha, a logs nem a problemas.
 */
@ApiV1Controller
@RequestMapping("/traces")
@Tag(name = FRONTDESK_TAG, description = FRONTDESK_TAG_DESCRIPTION)
class TracesController(
    private val traces: FrontdeskTraceService,
) {
    @PostMapping
    @Operation(
        operationId = "createTrace",
        summary = "Criar um recado para um setor",
        description =
            "Um recado (trace) para o setor `departmentId`, vinculado a uma reserva (`reservationId`) **ou** a uma " +
                "UH (`roomId`), com o vencimento e o responsável opcional. Exige `frontdesk.traces.manage`. Na " +
                "ordem: texto vazio ou acima de 500 caracteres (campo `text`), reserva e UH juntas ou nenhuma delas " +
                "(campo `reservationId`) e vencimento antes da data operacional (campo `dueOn`): 400 " +
                "`TRACE_INVALID`, com o campo e a regra, nunca o texto; setor inexistente 400 `UNKNOWN_DEPARTMENT`; " +
                "reserva inexistente 400 `UNKNOWN_RESERVATION` (qualquer situação serve); UH inexistente 400 " +
                "`UNKNOWN_ROOM`; UH desativada 422 `ROOM_INACTIVE` (campo em `field`); responsável que não é um " +
                "usuário vinculado a um colaborador 400 `UNKNOWN_ASSIGNEE`. O recado nasce `OPEN`.",
    )
    @ApiResponse(responseCode = "201", description = "Recado criado.")
    fun create(
        @Valid @RequestBody request: TraceRequest,
    ): ResponseEntity<TraceResponse> {
        val trace = traces.create(request.toCommand())
        return ResponseEntity
            .created(URI.create("/api/v1/traces/${trace.id}"))
            .eTag(ETags.of(trace.version))
            .body(TraceResponse.of(trace))
    }

    @GetMapping
    @Operation(
        operationId = "listTraces",
        summary = "Listar os recados",
        description =
            "Em ordem de vencimento e id, por cursor; os filtros preenchidos restringem juntos. Exige " +
                "`frontdesk.traces.read`.",
    )
    @Suppress("LongParameterList") // os filtros da lista
    fun list(
        @Parameter(description = "Só os recados deste setor.")
        @RequestParam(required = false) departmentId: UUID?,
        @Parameter(description = "Só os que vencem nesta data operacional (YYYY-MM-DD).")
        @RequestParam(required = false) dueOn: LocalDate?,
        @Parameter(description = "Só os abertos (`OPEN`) ou só os resolvidos (`RESOLVED`).")
        @RequestParam(required = false) status: TraceStatus?,
        @Parameter(description = "Só os vinculados a esta reserva.")
        @RequestParam(required = false) reservationId: UUID?,
        @Parameter(description = "Só os vinculados a esta UH.")
        @RequestParam(required = false) roomId: UUID?,
        @Valid page: PageParams,
    ): CursorPage<TraceResponse> {
        val filter =
            TraceFilter(
                departmentId = departmentId?.let(::DepartmentId),
                dueOn = dueOn,
                status = status,
                reservationId = reservationId?.let(::ReservationId),
                roomId = roomId?.let(::RoomId),
            )
        return traces.list(filter, page).map(TraceResponse::of)
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getTrace",
        summary = "Consultar um recado",
        description = "Exige `frontdesk.traces.read`. Inexistente: 404 `TRACE_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<TraceResponse> = detail(traces.trace(TraceId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateTrace",
        summary = "Alterar um recado aberto",
        description =
            "Troca o setor, o vencimento, o texto e o responsável (o vínculo com a reserva ou a UH não muda), com " +
                "`If-Match` (412 com outra versão; 428 sem ele). Exige `frontdesk.traces.manage`. Inexistente: 404 " +
                "`TRACE_NOT_FOUND`; resolvido: 409 `TRACE_ALREADY_RESOLVED` e nada muda. As regras da criação valem " +
                "para o texto e, quando mudam, para o vencimento, o setor e o responsável.",
    )
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: TraceChangeRequest,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<TraceResponse> {
        val expected = ETags.expectedVersion(ifMatch)
        return detail(traces.change(TraceId(id), expected, request.toCommand()))
    }

    @PostMapping("/{id}/resolve")
    @Operation(
        operationId = "resolveTrace",
        summary = "Resolver um recado",
        description =
            "Registra quem resolveu e quando (`resolvedBy`, `resolvedAt`) com o recado travado: de duas resoluções " +
                "simultâneas, só uma passa. Exige `frontdesk.traces.resolve` (o setor resolve sem poder editar). " +
                "Inexistente: 404 `TRACE_NOT_FOUND`; já resolvido: 409 `TRACE_ALREADY_RESOLVED`.",
    )
    fun resolve(
        @PathVariable id: UUID,
    ): ResponseEntity<TraceResponse> = detail(traces.resolve(TraceId(id)))

    private fun detail(trace: TraceView): ResponseEntity<TraceResponse> =
        ResponseEntity.ok().eTag(ETags.of(trace.version)).body(TraceResponse.of(trace))
}
