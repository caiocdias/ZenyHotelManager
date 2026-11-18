package com.zeny.hotel.housekeeping.web

import com.zeny.hotel.housekeeping.CleaningTypeId
import com.zeny.hotel.housekeeping.HousekeepingTaskId
import com.zeny.hotel.housekeeping.TaskSource
import com.zeny.hotel.housekeeping.TaskStatus
import com.zeny.hotel.housekeeping.application.HousekeepingTaskView
import com.zeny.hotel.housekeeping.application.HousekeepingTasksService
import com.zeny.hotel.housekeeping.application.TaskFilter
import com.zeny.hotel.housekeeping.application.TaskRequestCommand
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.PageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
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
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val TASKS_READ = "Exige `housekeeping.tasks.read`."
private const val TASKS_REQUEST = "Exige `housekeeping.tasks.request`."
private const val MAX_NOTES = 500
private const val NOTES_DESCRIPTION =
    "Observações da recepção para a governança, até 500 caracteres. **Não escreva dado do hóspede** (nome, " +
        "documento, contato): a tarefa é vista por toda a governança."
private const val ARRIVAL_DESCRIPTION =
    "Se a UH tinha chegada prevista na data: **retrato do momento da geração**. Alocação, cancelamento ou no-show " +
        "posteriores não o atualizam. Chegada prevista é uma reserva confirmada com chegada até a data e a UH " +
        "alocada; numa área, sempre `false`."

/**
 * Tarefas de governança (RF-GOV-02, M6 E02): a lista do dia, a leitura e a solicitação avulsa da recepção. Consultar
 * exige `housekeeping.tasks.read`; solicitar, `housekeeping.tasks.request`. Não há `DELETE`.
 */
@ApiV1Controller
@RequestMapping("/housekeeping-tasks")
@Tag(name = HOUSEKEEPING_TAG, description = HOUSEKEEPING_TAG_DESCRIPTION)
class HousekeepingTasksController(
    private val tasks: HousekeepingTasksService,
) {
    @GetMapping
    @Operation(
        operationId = "listHousekeepingTasks",
        summary = "Listar as tarefas de governança do dia",
        description =
            "As tarefas da data (padrão: a data operacional), primeiro as das UHs com chegada prevista, depois por " +
                "prioridade (da mais alta para a mais baixa) e id. $TASKS_READ",
    )
    @Suppress("LongParameterList") // os filtros da listagem
    fun list(
        @Parameter(description = "A data operacional das tarefas (YYYY-MM-DD); omita para a data operacional corrente.")
        @RequestParam(required = false) date: LocalDate?,
        @Parameter(description = "Só as tarefas desta UH.")
        @RequestParam(required = false) roomId: UUID?,
        @Parameter(description = "Só as tarefas desta área comum.")
        @RequestParam(required = false) areaId: UUID?,
        @Parameter(description = "Só as tarefas neste status.")
        @RequestParam(required = false) status: TaskStatus?,
        @Parameter(description = "Só as tarefas deste tipo de limpeza.")
        @RequestParam(required = false) cleaningTypeId: UUID?,
        @Parameter(description = "Só as tarefas desta origem.")
        @RequestParam(required = false) source: TaskSource?,
        @Valid page: PageParams,
    ): CursorPage<HousekeepingTaskResponse> =
        tasks
            .list(
                TaskFilter(
                    date,
                    roomId?.let(::RoomId),
                    areaId?.let(::AreaId),
                    status,
                    cleaningTypeId?.let(::CleaningTypeId),
                    source,
                ),
                page,
            ).map(HousekeepingTaskResponse::of)

    @PostMapping
    @Operation(
        operationId = "requestHousekeepingTask",
        summary = "Solicitar uma tarefa de governança avulsa",
        description =
            "Numa UH (`roomId`) ou numa área comum (`areaId`), exatamente um: os dois ou nenhum, 400 " +
                "`HOUSEKEEPING_TASK_LOCATION_INVALID`. A tarefa nasce `PENDING`, com origem `AD_HOC`, a prioridade " +
                "padrão do tipo e os créditos do tipo para o tipo da UH (área ou tipo de UH sem crédito: 0). " +
                "$TASKS_REQUEST Local inexistente: 400 `HOUSEKEEPING_TASK_UNKNOWN_LOCATION`; tipo inexistente: 400 " +
                "`HOUSEKEEPING_TASK_UNKNOWN_CLEANING_TYPE`; local desativado: 422 " +
                "`HOUSEKEEPING_TASK_LOCATION_INACTIVE`; tipo desativado: 422 `CLEANING_TYPE_INACTIVE`; data antes da " +
                "operacional ou mais de 30 dias depois dela: 422 `HOUSEKEEPING_TASK_DATE_INVALID`. Já há tarefa no " +
                "mesmo local, na mesma data e com o mesmo tipo, em qualquer status (inclusive cancelada): 409 " +
                "`HOUSEKEEPING_TASK_ALREADY_EXISTS`; para uma segunda limpeza no dia, use outro tipo (como o retoque).",
    )
    @ApiResponse(responseCode = "201", description = "Tarefa solicitada.")
    fun request(
        @Valid @RequestBody request: HousekeepingTaskRequest,
    ): ResponseEntity<HousekeepingTaskResponse> {
        val view = tasks.request(request.toCommand())
        return ResponseEntity
            .created(URI.create("/api/v1/housekeeping-tasks/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(HousekeepingTaskResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getHousekeepingTask",
        summary = "Consultar uma tarefa de governança",
        description = "$TASKS_READ Inexistente: 404 `HOUSEKEEPING_TASK_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<HousekeepingTaskResponse> {
        val view = tasks.get(HousekeepingTaskId(id))
        return ResponseEntity.ok().eTag(ETags.of(view.version)).body(HousekeepingTaskResponse.of(view))
    }
}

@Schema(description = "Uma solicitação avulsa de limpeza, numa UH ou numa área comum (exatamente um local).")
data class HousekeepingTaskRequest(
    @field:Schema(description = "A UH; informe esta ou `areaId`, uma só.", nullable = true)
    val roomId: UUID? = null,
    @field:Schema(description = "A área comum; informe esta ou `roomId`, uma só.", nullable = true)
    val areaId: UUID? = null,
    @field:NotNull
    val cleaningTypeId: UUID?,
    @field:Schema(
        description = "A data operacional da tarefa, da corrente até 30 dias depois; omita para a corrente.",
        nullable = true,
    )
    val date: LocalDate? = null,
    // O limite é do domínio (500 caracteres, em code points, depois de aparar); aqui só o documenta.
    @field:Schema(description = NOTES_DESCRIPTION, nullable = true, maxLength = MAX_NOTES)
    val notes: String? = null,
) {
    fun toCommand() =
        TaskRequestCommand(
            roomId?.let(::RoomId),
            areaId?.let(::AreaId),
            CleaningTypeId(checkNotNull(cleaningTypeId)),
            date,
            notes,
        )
}

@Schema(description = "Uma tarefa de governança.")
data class HousekeepingTaskResponse(
    val id: UUID,
    @field:Schema(description = "A UH; nula numa tarefa de área.", nullable = true)
    val roomId: UUID?,
    @field:Schema(description = "A área comum; nula numa tarefa de UH.", nullable = true)
    val areaId: UUID?,
    @field:Schema(description = "A data operacional da tarefa.")
    val date: LocalDate,
    val cleaningTypeId: UUID,
    @field:ExtensibleEnum
    @field:Schema(description = "A origem da tarefa (trate um valor desconhecido sem falhar).")
    val source: TaskSource,
    @field:ExtensibleEnum
    @field:Schema(description = "O status da tarefa (trate um valor desconhecido sem falhar).")
    val status: TaskStatus,
    @field:Schema(description = "A prioridade, de 1 (mais baixa) a 5 (mais alta): a padrão do tipo de limpeza.")
    val priority: Int,
    @field:Schema(description = "Os créditos do tipo de limpeza para o tipo da UH; 0 numa área ou sem crédito.")
    val credits: Int,
    @field:Schema(description = ARRIVAL_DESCRIPTION)
    val arrivalExpected: Boolean,
    @field:Schema(description = "A faxina programada que gerou a tarefa; nula nas outras origens.", nullable = true)
    val scheduledCleaningId: UUID?,
    @field:Schema(description = NOTES_DESCRIPTION, nullable = true)
    val notes: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: HousekeepingTaskView) =
            HousekeepingTaskResponse(
                view.id.value,
                view.roomId?.value,
                view.areaId?.value,
                view.date,
                view.cleaningTypeId.value,
                view.source,
                view.status,
                view.priority,
                view.credits,
                view.arrivalExpected,
                view.scheduledCleaningId?.value,
                view.notes,
                view.createdAt,
                view.updatedAt,
            )
    }
}
