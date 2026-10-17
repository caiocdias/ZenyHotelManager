package com.zeny.hotel.checklists.web

import com.fasterxml.jackson.annotation.JsonInclude
import com.zeny.hotel.checklists.CheckAnswer
import com.zeny.hotel.checklists.ChecklistAnswerInput
import com.zeny.hotel.checklists.ChecklistAnswerView
import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistExecutionStatus
import com.zeny.hotel.checklists.ChecklistExecutionView
import com.zeny.hotel.checklists.ChecklistItemType
import com.zeny.hotel.checklists.ChecklistRoutineView
import com.zeny.hotel.checklists.ChecklistSubject
import com.zeny.hotel.checklists.ChecklistTemplateId
import com.zeny.hotel.checklists.ChecklistsErrorCodes
import com.zeny.hotel.checklists.RoutineDefinition
import com.zeny.hotel.checklists.RoutineFrequency
import com.zeny.hotel.checklists.SubjectAttributes
import com.zeny.hotel.checklists.application.ChecklistExecutionDetail
import com.zeny.hotel.checklists.application.StartExecutionCommand
import com.zeny.hotel.files.FileId
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.staff.DepartmentId
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

private const val MAX_ANSWERS = 300

@Schema(
    description =
        "Iniciar uma execução avulsa: pelo modelo (`templateId`, a última versão publicada) ou pelo contexto e os " +
            "atributos do sujeito (`context` e `attributes`, o modelo que vale para ele, RF-CHK-04), um dos dois.",
)
data class StartChecklistExecutionRequest(
    @field:Schema(description = "O modelo a executar (ativo e publicado).", nullable = true)
    val templateId: UUID? = null,
    @field:Schema(description = "O contexto em que se escolhe o modelo do sujeito.", nullable = true)
    val context: ChecklistContext? = null,
    @field:Valid
    @field:Schema(description = "O sujeito da execução, de outro módulo (opcional).", nullable = true)
    val subject: ChecklistSubjectDto? = null,
    @field:Schema(description = "Os atributos do sujeito para escolher o modelo (só com `context`).", nullable = true)
    val attributes: SubjectAttributesDto? = null,
) {
    fun toCommand() =
        StartExecutionCommand(
            templateId?.let(::ChecklistTemplateId),
            context,
            subject?.toSubject("subject"),
            attributes?.toAttributes() ?: SubjectAttributes(),
        )
}

@Schema(
    description =
        "O sujeito ou local, de outro módulo: o tipo (minúsculas, dígitos, hífen e ponto, até 60, ex.: `rooms.room`, " +
            "`rooms.area`) e o id. Nenhum dos dois é conferido.",
)
data class ChecklistSubjectDto(
    @field:Size(max = ChecklistSubject.MAX_TYPE_LENGTH)
    @field:Schema(example = "rooms.area")
    val type: String,
    @field:NotNull
    val id: UUID?,
) {
    fun toSubject(field: String): ChecklistSubject {
        if (!ChecklistSubject.isValidType(type.trim())) {
            val code = ChecklistsErrorCodes.CHECKLIST_EXECUTION_INVALID
            val message = "Informe o tipo do sujeito em minúsculas, com ponto entre as partes (ex.: rooms.room)."
            throw InvalidInputException(code, message, listOf(FieldViolation("$field.type", code.value, message)))
        }
        return ChecklistSubject(type.trim(), checkNotNull(id))
    }

    companion object {
        fun of(subject: ChecklistSubject) = ChecklistSubjectDto(subject.type, subject.id)
    }
}

@Schema(description = "Os atributos do sujeito (IDs de outros módulos) para escolher o modelo (RF-CHK-04).")
data class SubjectAttributesDto(
    val roomTypeId: UUID? = null,
    val cleaningTypeId: UUID? = null,
    val areaId: UUID? = null,
    val assetId: UUID? = null,
) {
    fun toAttributes() = SubjectAttributes(roomTypeId, cleaningTypeId, areaId, assetId)
}

@Schema(description = "Todas as respostas da execução: a lista substitui a gravada.")
data class ReplaceChecklistAnswersRequest(
    @field:Size(max = MAX_ANSWERS)
    val answers: List<@Valid ChecklistAnswerDto> = emptyList(),
)

@Schema(
    description =
        "Uma resposta: a chave do item e só o campo do tipo dele — `check` (CHECK), `quantity` (QUANTITY, de 0 a " +
            "999999, até 3 casas), `number` (NUMBER, até 4 casas), `text` (TEXT, até 2000), `choice` " +
            "(SINGLE_CHOICE), `choices` (MULTI_CHOICE) ou `photoFileId` (PHOTO: um anexo da finalidade " +
            "`checklists.photo` enviado pelo executor) — e a observação opcional (até 500).",
)
data class ChecklistAnswerDto(
    @field:Size(max = 40)
    @field:Schema(example = "towels")
    val itemKey: String,
    @field:Schema(nullable = true)
    val check: CheckAnswer? = null,
    @field:Schema(nullable = true, example = "2")
    val quantity: BigDecimal? = null,
    @field:Schema(nullable = true, example = "4.5")
    val number: BigDecimal? = null,
    @field:Size(max = 2000)
    @field:Schema(nullable = true)
    val text: String? = null,
    @field:Size(max = 40)
    @field:Schema(nullable = true)
    val choice: String? = null,
    @field:Size(max = 50)
    @field:Schema(nullable = true)
    val choices: List<String>? = null,
    @field:Schema(nullable = true)
    val photoFileId: UUID? = null,
    @field:Size(max = 500)
    @field:Schema(nullable = true)
    val note: String? = null,
) {
    fun toInput() =
        ChecklistAnswerInput(itemKey, check, quantity, number, text, choice, choices, photoFileId?.let(::FileId), note)
}

@Schema(description = "Uma resposta gravada.")
data class ChecklistAnswerResponse(
    val itemKey: String,
    val itemType: ChecklistItemType,
    @field:Schema(nullable = true)
    val check: CheckAnswer?,
    @field:Schema(nullable = true)
    val quantity: BigDecimal?,
    @field:Schema(nullable = true)
    val number: BigDecimal?,
    @field:Schema(nullable = true)
    val text: String?,
    @field:Schema(nullable = true)
    val choice: String?,
    val choices: List<String>,
    @field:Schema(nullable = true)
    val photoFileId: UUID?,
    @field:Schema(
        description =
            "A foto citada offline não ficou com a resposta: ainda não tinha chegado ou não servia (outra " +
                "finalidade, outro autor ou outro registro). Conta como respondida num item de foto obrigatório.",
    )
    val photoMissing: Boolean,
    @field:Schema(nullable = true)
    val note: String?,
    @field:Schema(description = "Quando a resposta foi dada (offline, o horário do dispositivo corrigido).")
    val answeredAt: Instant,
) {
    companion object {
        fun of(view: ChecklistAnswerView) =
            ChecklistAnswerResponse(
                view.itemKey,
                view.itemType,
                view.check,
                view.quantity,
                view.number,
                view.text,
                view.choice,
                view.choices,
                view.photoFileId?.value,
                view.photoMissing,
                view.note,
                view.answeredAt,
            )
    }
}

@Schema(description = "Uma execução de checklist na listagem, sem as respostas.")
data class ChecklistExecutionSummaryResponse(
    val id: UUID,
    val status: ChecklistExecutionStatus,
    val templateId: UUID,
    @field:Schema(description = "A versão usada; nula na pendente de uma rotina.", nullable = true)
    val versionId: UUID?,
    @field:Schema(nullable = true)
    val versionNumber: Int?,
    val context: ChecklistContext,
    @field:Schema(nullable = true)
    val subject: ChecklistSubjectDto?,
    @field:Schema(description = "A rotina que gerou a execução.", nullable = true)
    val routineId: UUID?,
    @field:Schema(description = "A data operacional da rotina.", nullable = true)
    val businessDate: LocalDate?,
    @field:Schema(description = "O horário previsto da rotina.", nullable = true)
    val dueAt: Instant?,
    @field:Schema(description = "Os candidatos escalados da rotina (H14).")
    val candidateIds: List<UUID>,
    @field:Schema(
        description =
            "A execução offline de uma rotina já assumida por outra pessoa fica à parte, ligada à original (RN-22).",
        nullable = true,
    )
    val duplicateOf: UUID?,
    @field:Schema(nullable = true)
    val executorId: UUID?,
    @field:Schema(description = "Início real (offline, o horário do dispositivo corrigido).", nullable = true)
    val startedAt: Instant?,
    @field:Schema(description = "Fim real (offline, o horário do dispositivo corrigido).", nullable = true)
    val completedAt: Instant?,
    @field:Schema(description = "Iniciada ou concluída por comando offline.")
    val offline: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    @field:Schema(description = "Versão do registro (a mesma do `ETag` do detalhe).")
    val version: Long,
) {
    companion object {
        fun of(view: ChecklistExecutionView) =
            ChecklistExecutionSummaryResponse(
                view.id.value,
                view.status,
                view.templateId.value,
                view.versionId?.value,
                view.versionNumber,
                view.context,
                view.subject?.let(ChecklistSubjectDto::of),
                view.routineId?.value,
                view.businessDate,
                view.dueAt,
                view.candidateIds.map { it.value }.sorted(),
                view.duplicateOf?.value,
                view.executorId?.value,
                view.startedAt,
                view.completedAt,
                view.offline,
                view.createdAt,
                view.updatedAt,
                view.version,
            )
    }
}

@Schema(description = "Uma execução de checklist com as respostas e o conteúdo da versão usada.")
data class ChecklistExecutionResponse(
    val execution: ChecklistExecutionSummaryResponse,
    val answers: List<ChecklistAnswerResponse>,
    // Sem `nullable` no esquema: num `$ref` do OpenAPI 3.0 ele marcaria o próprio ChecklistVersionResponse como
    // anulável (incompatível para as operações que já o devolvem); o campo some do JSON na pendente.
    @field:Schema(description = "A versão usada; ausente na pendente de uma rotina, que ainda não tem versão.")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val checklistVersion: ChecklistVersionResponse?,
) {
    companion object {
        fun of(detail: ChecklistExecutionDetail) =
            ChecklistExecutionResponse(
                ChecklistExecutionSummaryResponse.of(detail.execution),
                detail.execution.answers.map(ChecklistAnswerResponse::of),
                detail.version?.let(ChecklistVersionResponse::of),
            )
    }
}

@Schema(
    description =
        "Uma rotina (RF-CHK-08): o modelo, o setor, a frequência (`DAILY`, sem dias; `WEEKLY`, com os dias da " +
            "semana), o horário (horas e minutos, no fuso do hotel) e o local opcional.",
)
data class ChecklistRoutineRequest(
    @field:Schema(description = "Até 120 caracteres.", example = "Abertura do restaurante")
    val name: String,
    @field:NotNull
    val templateId: UUID?,
    @field:NotNull
    val departmentId: UUID?,
    @field:NotNull
    val frequency: RoutineFrequency?,
    @field:Size(max = 7)
    val weekdays: Set<DayOfWeek> = emptySet(),
    @field:NotNull
    @field:Schema(type = "string", example = "06:00")
    val dueAt: LocalTime?,
    @field:Valid
    @field:Schema(nullable = true)
    val location: ChecklistSubjectDto? = null,
) {
    fun toDefinition() =
        RoutineDefinition(
            name,
            ChecklistTemplateId(checkNotNull(templateId)),
            DepartmentId(checkNotNull(departmentId)),
            checkNotNull(frequency),
            weekdays,
            checkNotNull(dueAt),
            location?.toSubject("location"),
        )
}

@Schema(description = "Uma rotina de checklist.")
data class ChecklistRoutineResponse(
    val id: UUID,
    val name: String,
    val templateId: UUID,
    val departmentId: UUID,
    val frequency: RoutineFrequency,
    val weekdays: List<DayOfWeek>,
    @field:Schema(type = "string", example = "06:00")
    val dueAt: String,
    @field:Schema(nullable = true)
    val location: ChecklistSubjectDto?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    @field:Schema(description = "Versão do registro (a mesma do `ETag` do detalhe).")
    val version: Long,
) {
    companion object {
        fun of(view: ChecklistRoutineView) =
            ChecklistRoutineResponse(
                view.id.value,
                view.name,
                view.templateId.value,
                view.departmentId.value,
                view.frequency,
                view.weekdays.sorted(),
                view.dueAt.toString(),
                view.location?.let(ChecklistSubjectDto::of),
                view.active,
                view.createdAt,
                view.updatedAt,
                view.version,
            )
    }
}
