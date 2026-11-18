package com.zeny.hotel.housekeeping.web

import com.zeny.hotel.housekeeping.CleaningTypeId
import com.zeny.hotel.housekeeping.ScheduledCleaningId
import com.zeny.hotel.housekeeping.application.ScheduledCleaningCommand
import com.zeny.hotel.housekeeping.application.ScheduledCleaningView
import com.zeny.hotel.housekeeping.application.ScheduledCleaningsService
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.PageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
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
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val MAX_FREQUENCY_DAYS = 365L
private const val FREQUENCY_DESCRIPTION = "A cada quantos dias a faxina se repete, de 1 a 365."
private const val NEXT_DUE_RULE =
    "A próxima data é `lastDoneOn + frequencyDays` ou, sem `lastDoneOn`, a data operacional."

/**
 * Faxinas programadas (RF-GOV-10, M6 E01): consultar exige `housekeeping.config.read`; escrever,
 * `housekeeping.config.manage`. Nunca são apagadas, só desativadas.
 */
@ApiV1Controller
@RequestMapping("/scheduled-cleanings")
@Tag(name = HOUSEKEEPING_TAG, description = HOUSEKEEPING_TAG_DESCRIPTION)
class ScheduledCleaningsController(
    private val schedules: ScheduledCleaningsService,
) {
    @GetMapping
    @Operation(
        operationId = "listScheduledCleanings",
        summary = "Listar faxinas programadas",
        description = "Em ordem de próxima data (e id). $READ",
    )
    fun list(
        @Parameter(description = "Só as ativas (`true`) ou só as inativas (`false`); omita para todas.")
        @RequestParam(required = false) active: Boolean?,
        @Valid page: PageParams,
    ): CursorPage<ScheduledCleaningResponse> = schedules.list(active, page).map(ScheduledCleaningResponse::of)

    @PostMapping
    @Operation(
        operationId = "createScheduledCleaning",
        summary = "Programar uma faxina",
        description =
            "Numa UH (`roomId`) ou numa área comum (`areaId`), exatamente um: os dois ou nenhum, 400 " +
                "`SCHEDULED_CLEANING_LOCATION_INVALID`. $NEXT_DUE_RULE $MANAGE Local inexistente: 400 " +
                "`SCHEDULED_CLEANING_UNKNOWN_LOCATION`; tipo inexistente: 400 " +
                "`SCHEDULED_CLEANING_UNKNOWN_CLEANING_TYPE`; local desativado: 422 " +
                "`SCHEDULED_CLEANING_LOCATION_INACTIVE`; tipo desativado: 422 `CLEANING_TYPE_INACTIVE`; `lastDoneOn` " +
                "depois da data operacional: 422 `SCHEDULED_CLEANING_LAST_DONE_IN_FUTURE`; antes de `-4712-01-01`: " +
                "400 `VALIDATION_FAILED`. Já há uma faxina ativa no mesmo local com o mesmo tipo: 409 " +
                "`SCHEDULED_CLEANING_ALREADY_EXISTS`.",
    )
    @ApiResponse(responseCode = "201", description = "Faxina programada.")
    fun create(
        @Valid @RequestBody request: ScheduledCleaningRequest,
    ): ResponseEntity<ScheduledCleaningResponse> {
        val view = schedules.create(request.toCommand())
        return ResponseEntity
            .created(URI.create("/api/v1/scheduled-cleanings/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(ScheduledCleaningResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getScheduledCleaning",
        summary = "Consultar uma faxina programada",
        description = "$READ Inexistente: 404 `SCHEDULED_CLEANING_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<ScheduledCleaningResponse> = ok(schedules.get(ScheduledCleaningId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateScheduledCleaning",
        summary = "Editar uma faxina programada",
        description =
            "Troca só o tipo de limpeza e a frequência e recalcula a próxima data; o local e a última faxina não " +
                "mudam. $NEXT_DUE_RULE $MANAGE Exige também o `If-Match` (ausente: 428; versão diferente: 412). " +
                "Tipo inexistente: 400 `SCHEDULED_CLEANING_UNKNOWN_CLEANING_TYPE`; tipo novo desativado (mesmo com a " +
                "faxina desativada): 422 `CLEANING_TYPE_INACTIVE`; já há uma faxina ativa no mesmo local com o tipo " +
                "novo: 409 `SCHEDULED_CLEANING_ALREADY_EXISTS`.",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: ScheduledCleaningUpdateRequest,
    ): ResponseEntity<ScheduledCleaningResponse> =
        ok(
            schedules.update(
                ScheduledCleaningId(id),
                ETags.expectedVersion(ifMatch),
                CleaningTypeId(checkNotNull(request.cleaningTypeId)),
                checkNotNull(request.frequencyDays),
            ),
        )

    @PostMapping("/{id}/deactivate")
    @Operation(
        operationId = "deactivateScheduledCleaning",
        summary = "Desativar uma faxina programada",
        description = "$MANAGE Nada é apagado; desativar de novo não muda nada.",
    )
    fun deactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<ScheduledCleaningResponse> = ok(schedules.deactivate(ScheduledCleaningId(id)))

    @PostMapping("/{id}/reactivate")
    @Operation(
        operationId = "reactivateScheduledCleaning",
        summary = "Reativar uma faxina programada",
        description =
            "$MANAGE Mantém a próxima data antiga, mesmo vencida. Tipo desativado: 422 `CLEANING_TYPE_INACTIVE`; " +
                "local desativado: 422 `SCHEDULED_CLEANING_LOCATION_INACTIVE`; já há outra ativa no mesmo local " +
                "com o mesmo tipo: 409 `SCHEDULED_CLEANING_ALREADY_EXISTS`. Reativar uma faxina ativa não muda nada.",
    )
    fun reactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<ScheduledCleaningResponse> = ok(schedules.reactivate(ScheduledCleaningId(id)))

    private fun ok(view: ScheduledCleaningView) =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(ScheduledCleaningResponse.of(view))
}

@Schema(description = "Uma faxina programada nova, numa UH ou numa área comum (exatamente um local).")
data class ScheduledCleaningRequest(
    @field:Schema(description = "A UH; informe esta ou `areaId`, uma só.", nullable = true)
    val roomId: UUID? = null,
    @field:Schema(description = "A área comum; informe esta ou `roomId`, uma só.", nullable = true)
    val areaId: UUID? = null,
    @field:NotNull
    val cleaningTypeId: UUID?,
    @field:Schema(description = FREQUENCY_DESCRIPTION)
    @field:NotNull
    @field:Min(1)
    @field:Max(MAX_FREQUENCY_DAYS)
    val frequencyDays: Int?,
    @field:Schema(
        description =
            "A última faxina, até a data operacional e a partir de `-4712-01-01`; nula se nunca foi feita (a próxima " +
                "data é então a data operacional).",
        nullable = true,
    )
    val lastDoneOn: LocalDate? = null,
) {
    fun toCommand() =
        ScheduledCleaningCommand(
            roomId?.let(::RoomId),
            areaId?.let(::AreaId),
            CleaningTypeId(checkNotNull(cleaningTypeId)),
            checkNotNull(frequencyDays),
            lastDoneOn,
        )
}

@Schema(description = "Os dados editáveis de uma faxina programada: o tipo de limpeza e a frequência.")
data class ScheduledCleaningUpdateRequest(
    @field:NotNull
    val cleaningTypeId: UUID?,
    @field:Schema(description = FREQUENCY_DESCRIPTION)
    @field:NotNull
    @field:Min(1)
    @field:Max(MAX_FREQUENCY_DAYS)
    val frequencyDays: Int?,
)

@Schema(description = "Uma faxina programada.")
data class ScheduledCleaningResponse(
    val id: UUID,
    @field:Schema(description = "A UH; nula numa faxina de área.", nullable = true)
    val roomId: UUID?,
    @field:Schema(description = "A área comum; nula numa faxina de UH.", nullable = true)
    val areaId: UUID?,
    val cleaningTypeId: UUID,
    @field:Schema(description = FREQUENCY_DESCRIPTION)
    val frequencyDays: Int,
    @field:Schema(description = "A última faxina; nula se nunca foi feita.", nullable = true)
    val lastDoneOn: LocalDate?,
    @field:Schema(description = "$NEXT_DUE_RULE A reativação mantém a data antiga, mesmo vencida.")
    val nextDueOn: LocalDate,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: ScheduledCleaningView) =
            ScheduledCleaningResponse(
                view.id.value,
                view.roomId?.value,
                view.areaId?.value,
                view.cleaningTypeId.value,
                view.frequencyDays,
                view.lastDoneOn,
                view.nextDueOn,
                view.active,
                view.createdAt,
                view.updatedAt,
            )
    }
}
