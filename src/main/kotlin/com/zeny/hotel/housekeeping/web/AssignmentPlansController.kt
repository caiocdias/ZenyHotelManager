package com.zeny.hotel.housekeeping.web

import com.zeny.hotel.housekeeping.AssignmentPlanStatus
import com.zeny.hotel.housekeeping.HousekeepingErrorCodes
import com.zeny.hotel.housekeeping.HousekeepingTaskId
import com.zeny.hotel.housekeeping.NotNotifiedReason
import com.zeny.hotel.housekeeping.application.AssignmentAdjustment
import com.zeny.hotel.housekeeping.application.AssignmentItemCommand
import com.zeny.hotel.housekeeping.application.AssignmentPlanView
import com.zeny.hotel.housekeeping.application.AssignmentPlansService
import com.zeny.hotel.housekeeping.application.PublishResult
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.staff.EmployeeId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import java.time.LocalDate
import java.util.UUID

private const val PLAN_MANAGE = "Exige `housekeeping.assignments.manage`."
private const val MAX_CHANGES = 500
private const val MAX_EMPLOYEES = 200
private const val MAX_POSITION = 1000L
private const val PLAN_DATE_PARAMETER = "A data operacional do plano (YYYY-MM-DD)."
private const val PLAN_WINDOW =
    "A data vai da data operacional até 7 dias depois dela; fora disso, 422 `ASSIGNMENT_PLAN_DATE_INVALID` (o plano " +
        "de uma data que ficou para trás está congelado)."
private const val PLAN_SCHEDULE =
    "Só recebe tarefa quem está na escala da data no setor de governança; fora dela, ou colaborador inexistente, 422 " +
        "`ASSIGNMENT_EMPLOYEE_NOT_SCHEDULED`."

/**
 * O plano do dia da distribuição das tarefas de governança (RF-GOV-03, M6 E03): a sugestão, a leitura, o ajuste e a
 * publicação. Sugerir, ajustar e publicar exigem `housekeeping.assignments.manage`; ler, `housekeeping.tasks.read`. Não
 * há `DELETE`.
 */
@ApiV1Controller
@RequestMapping("/assignment-plans")
@Tag(name = HOUSEKEEPING_TAG, description = HOUSEKEEPING_TAG_DESCRIPTION)
class AssignmentPlansController(
    private val plans: AssignmentPlansService,
) {
    @PostMapping("/{date}/suggest")
    @Operation(
        operationId = "suggestAssignmentPlan",
        summary = "Sugerir a distribuição das tarefas do dia",
        description =
            "Refaz o rascunho inteiro do plano da data (cria o plano se ainda não há) e o deixa `DRAFT`; as tarefas " +
                "só mudam na publicação. Entram as tarefas `PENDING` e `ASSIGNED` da data e, no plano da data " +
                "operacional, também as de datas anteriores. A sugestão balanceia os créditos entre as camareiras " +
                "escaladas (ou só as de `employeeIds`), agrupa por andar e põe primeiro as UHs com chegada prevista " +
                "(recalculada agora); a permanência de UH em \"Não perturbe\" fica sem camareira. $PLAN_MANAGE " +
                "$PLAN_WINDOW $PLAN_SCHEDULE Com o plano já existente, exige o `If-Match` (ausente: 428; versão " +
                "diferente: 412).",
    )
    fun suggest(
        @Parameter(description = PLAN_DATE_PARAMETER) @PathVariable date: LocalDate,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody(required = false) request: AssignmentSuggestRequest?,
    ): ResponseEntity<AssignmentPlanResponse> =
        ok(plans.suggest(date, ifMatch?.let(ETags::expectedVersion), request?.employees()))

    @GetMapping("/{date}")
    @Operation(
        operationId = "getAssignmentPlan",
        summary = "Consultar o plano do dia",
        description =
            "Exige `housekeeping.tasks.read`. Sem plano na data: 404 `ASSIGNMENT_PLAN_NOT_FOUND`.",
    )
    fun get(
        @Parameter(description = PLAN_DATE_PARAMETER) @PathVariable date: LocalDate,
    ): ResponseEntity<AssignmentPlanResponse> = ok(plans.get(date))

    @PatchMapping("/{date}")
    @Operation(
        operationId = "adjustAssignmentPlan",
        summary = "Ajustar o plano do dia",
        description =
            "Cada item de `items` leva a tarefa para a camareira (`employeeId`; nulo: fica no plano sem " +
                "camareira) na posição dada (sem ela, no fim): move a tarefa que já está no plano ou acrescenta uma " +
                "elegível, inclusive gerada depois da sugestão. `remove` tira tarefas do plano (diferente de " +
                "deixá-las sem camareira). As posições de cada camareira voltam a ir de 1 a n. O plano volta a " +
                "`DRAFT`. $PLAN_MANAGE Exige o `If-Match` (ausente: 428; versão diferente: 412). $PLAN_WINDOW Sem " +
                "plano na data: 404 `ASSIGNMENT_PLAN_NOT_FOUND`. Tarefa inexistente: 400 `ASSIGNMENT_UNKNOWN_TASK`; " +
                "tarefa que já andou (fora de `PENDING` e `ASSIGNED`) ou de outra data: 422 " +
                "`ASSIGNMENT_TASK_NOT_ASSIGNABLE`. $PLAN_SCHEDULE Nenhuma mudança, ou a mesma tarefa duas vezes: " +
                "400 `HOUSEKEEPING_DATA_INVALID`.",
    )
    fun adjust(
        @Parameter(description = PLAN_DATE_PARAMETER) @PathVariable date: LocalDate,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: AssignmentAdjustRequest,
    ): ResponseEntity<AssignmentPlanResponse> =
        ok(plans.adjust(date, ifMatch?.let(ETags::expectedVersion), request.toAdjustment()))

    @PostMapping("/{date}/publish")
    @Operation(
        operationId = "publishAssignmentPlan",
        summary = "Publicar o plano do dia",
        description =
            "Grava o rascunho nas tarefas: cada tarefa com camareira fica `ASSIGNED`, com a camareira e a posição " +
                "(`assignedEmployeeId` e `assignmentPosition`, que um novo rascunho não muda); a sem camareira e a " +
                "retirada (`ASSIGNED` da data do plano, ou de antes no plano da data operacional, fora do rascunho) " +
                "voltam a `PENDING`. A tarefa terminada (`DONE` ou `CANCELLED`) nunca é tocada, e o item dela sai do " +
                "plano publicado. A tarefa que já andou (fora de `PENDING` e `ASSIGNED`, e não terminada) nunca é " +
                "tocada: o item dela com a mesma camareira é ignorado, inclusive a posição, e o item que a passaria a " +
                "outra " +
                "camareira recusa a publicação com 422 `ASSIGNMENT_TASK_NOT_ASSIGNABLE`, com os ids das tarefas em " +
                "`errors[].field`. O plano fica `PUBLISHED`. Avisa (`notifications.task-assigned`) só as camareiras " +
                "com tarefa incluída ou removida; as não avisadas vêm em `notNotified`. Publica " +
                "`housekeeping.assignment-plan` no tópico `housekeeping`. $PLAN_MANAGE Exige o `If-Match` (ausente: " +
                "428; versão diferente, ou outra escrita numa tarefa durante a publicação: 412). $PLAN_WINDOW Sem " +
                "plano na data: 404 `ASSIGNMENT_PLAN_NOT_FOUND`. $PLAN_SCHEDULE Nada é gravado quando a publicação " +
                "é recusada.",
    )
    fun publish(
        @Parameter(description = PLAN_DATE_PARAMETER) @PathVariable date: LocalDate,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<AssignmentPlanPublicationResponse> {
        val result = plans.publish(date, ifMatch?.let(ETags::expectedVersion))
        return ResponseEntity
            .ok()
            .eTag(ETags.of(result.version))
            .body(AssignmentPlanPublicationResponse.of(result))
    }

    private fun ok(view: AssignmentPlanView): ResponseEntity<AssignmentPlanResponse> =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(AssignmentPlanResponse.of(view))
}

@Schema(description = "Os parâmetros da sugestão; o corpo pode faltar.")
data class AssignmentSuggestRequest(
    @field:Size(max = MAX_EMPLOYEES)
    @field:Schema(
        description =
            "Só estas camareiras recebem tarefas; precisam estar na escala da data no setor de governança. Omita " +
                "para todas as escaladas.",
        nullable = true,
    )
    val employeeIds: List<UUID?>? = null,
) {
    fun employees(): List<EmployeeId>? =
        employeeIds?.let { ids ->
            ids.map {
                EmployeeId(it ?: throw nullId("employeeIds"))
            }
        }
}

@Schema(description = "O ajuste do plano: tarefas que vão para uma camareira e tarefas que saem do plano.")
data class AssignmentAdjustRequest(
    @field:Size(max = MAX_CHANGES)
    @field:Schema(description = "As tarefas que vão para uma camareira (mover ou acrescentar).", nullable = true)
    val items: List<@Valid AssignmentItemRequest?>? = null,
    @field:Size(max = MAX_CHANGES)
    @field:Schema(description = "As tarefas que saem do plano.", nullable = true)
    val remove: List<UUID?>? = null,
) {
    fun toAdjustment() =
        AssignmentAdjustment(
            items.orEmpty().map { item ->
                (item ?: throw nullId("items")).let {
                    AssignmentItemCommand(
                        HousekeepingTaskId(checkNotNull(it.taskId)),
                        it.employeeId?.let(::EmployeeId),
                        it.position,
                    )
                }
            },
            remove.orEmpty().map { HousekeepingTaskId(it ?: throw nullId("remove")) },
        )
}

@Schema(description = "Uma tarefa que vai para uma camareira.")
data class AssignmentItemRequest(
    @field:NotNull
    val taskId: UUID?,
    @field:Schema(description = "A camareira; nulo: a tarefa fica no plano sem camareira.", nullable = true)
    val employeeId: UUID? = null,
    @field:Min(1)
    @field:Max(MAX_POSITION)
    @field:Schema(description = "A posição na ordem da camareira, de 1 em diante; omita para o fim.", nullable = true)
    val position: Int? = null,
)

@Schema(description = "O plano do dia da distribuição.")
data class AssignmentPlanResponse(
    val id: UUID,
    @field:Schema(description = "A data operacional do plano.")
    val date: LocalDate,
    @field:ExtensibleEnum
    @field:Schema(description = "O status do plano (trate um valor desconhecido sem falhar).")
    val status: AssignmentPlanStatus,
    @field:Schema(description = "As tarefas com camareira, por camareira e posição (de 1 a n).")
    val items: List<AssignmentPlanItemResponse>,
    @field:Schema(
        description =
            "As tarefas do plano sem camareira, na ordem da sugestão: sem camareira escalada ou permanência de UH " +
                "em \"Não perturbe\".",
    )
    val unassigned: List<UUID>,
) {
    companion object {
        fun of(view: AssignmentPlanView) =
            AssignmentPlanResponse(
                view.id.value,
                view.date,
                view.status,
                view.items.map { AssignmentPlanItemResponse(it.taskId.value, it.employeeId.value, it.position) },
                view.unassigned.map { it.value },
            )
    }
}

@Schema(description = "A publicação do plano do dia.")
data class AssignmentPlanPublicationResponse(
    @field:Schema(description = "O plano publicado.")
    val id: UUID,
    @field:Schema(description = "A data operacional do plano.")
    val date: LocalDate,
    @field:ExtensibleEnum
    @field:Schema(description = "O status do plano (trate um valor desconhecido sem falhar).")
    val status: AssignmentPlanStatus,
    @field:Schema(description = "A versão do plano depois da publicação (a mesma do `ETag`).")
    val version: Long,
    @field:Schema(description = "Quantas camareiras com tarefa incluída ou removida foram avisadas.")
    val notified: Int,
    @field:Schema(description = "As camareiras com tarefa incluída ou removida que não foram avisadas, e o motivo.")
    val notNotified: List<NotNotifiedResponse>,
) {
    companion object {
        fun of(result: PublishResult) =
            AssignmentPlanPublicationResponse(
                result.id.value,
                result.date,
                result.status,
                result.version,
                result.notified,
                result.notNotified.map { NotNotifiedResponse(it.employeeId.value, it.reason) },
            )
    }
}

@Schema(description = "Uma camareira que não foi avisada da mudança nas tarefas dela.")
data class NotNotifiedResponse(
    val employeeId: UUID,
    @field:ExtensibleEnum
    @field:Schema(
        description =
            "`NO_USER`: o colaborador não tem usuário vinculado no cadastro (quem tem usuário é avisado mesmo " +
                "fora da escala da data, como a camareira que perdeu a tarefa e saiu da escala); `NOT_DELIVERED`: o " +
                "aviso não chegou ao painel (preferência desligada, usuário inativo ou que não recebe). Trate um " +
                "valor desconhecido sem falhar.",
    )
    val reason: NotNotifiedReason,
)

@Schema(description = "Uma tarefa do plano com camareira.")
data class AssignmentPlanItemResponse(
    val taskId: UUID,
    val employeeId: UUID,
    @field:Schema(description = "A posição na ordem de execução da camareira, de 1 em diante.")
    val position: Int,
)

private fun nullId(field: String): InvalidInputException {
    val message = "Informe ids, sem nulos."
    val code = HousekeepingErrorCodes.HOUSEKEEPING_DATA_INVALID
    return InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))
}
