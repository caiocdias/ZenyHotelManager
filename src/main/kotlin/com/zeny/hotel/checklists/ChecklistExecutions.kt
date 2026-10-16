package com.zeny.hotel.checklists

import com.zeny.hotel.files.FileId
import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.DepartmentId
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Id de uma execução de checklist (RF-CHK-07; criada offline, é o `commandId` do dispositivo). */
@JvmInline
value class ChecklistExecutionId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): ChecklistExecutionId = ChecklistExecutionId(UuidV7.generate())
    }
}

/** Id de uma rotina de checklist (RF-CHK-08). */
@JvmInline
value class ChecklistRoutineId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): ChecklistRoutineId = ChecklistRoutineId(UuidV7.generate())
    }
}

/** Situação de uma execução. */
enum class ChecklistExecutionStatus {
    /** Gerada por uma rotina para a data, à espera de um dos candidatos (H14). */
    PENDING,

    /** Iniciada: tem executor e versão; aceita respostas. */
    IN_PROGRESS,

    /** Concluída: as respostas não mudam mais; `ChecklistCompleted` publicado uma vez. */
    COMPLETED,
}

/**
 * O sujeito de uma execução ou o local de uma rotina, de outro módulo (H3): [type] identifica o tipo
 * (`rooms.room`, `rooms.area`, `housekeeping.task`; minúsculas, dígitos, hífen e ponto, até 60) e [id] o
 * registro. O `checklists` não confere nenhum dos dois.
 */
data class ChecklistSubject(
    val type: String,
    val id: UUID,
) {
    init {
        require(isValidType(type)) { "Tipo de sujeito inválido: '$type'" }
    }

    companion object {
        const val MAX_TYPE_LENGTH = 60
        private val TYPE = Regex("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)*")

        fun isValidType(type: String): Boolean = type.length <= MAX_TYPE_LENGTH && TYPE.matches(type)
    }
}

/**
 * Uma resposta como chega ao `checklists` (API HTTP, comando offline ou outro módulo): a chave do item na versão
 * da execução e só o campo do tipo dele — [check] (marcação), [quantity], [number], [text], [choice] (escolha
 * única), [choices] (múltipla) ou [photoFileId] (foto, um anexo da finalidade `checklists.photo`) — mais a
 * observação opcional e, offline, o horário do dispositivo ([answeredAt], corrigido pela defasagem).
 */
data class ChecklistAnswerInput(
    val itemKey: String,
    val check: CheckAnswer? = null,
    val quantity: BigDecimal? = null,
    val number: BigDecimal? = null,
    val text: String? = null,
    val choice: String? = null,
    val choices: List<String>? = null,
    val photoFileId: FileId? = null,
    val note: String? = null,
    val answeredAt: Instant? = null,
)

/**
 * Uma resposta gravada. [photoMissing]: a foto citada offline (ou pela API pública) não ficou com a resposta,
 * porque ainda não tinha chegado ao servidor (H10) ou não servia (outra finalidade, outro autor ou outro
 * registro); o alerta do comando diz qual. Conta como respondida num item de foto obrigatório.
 */
data class ChecklistAnswerView(
    val itemKey: String,
    val itemType: ChecklistItemType,
    val check: CheckAnswer?,
    val quantity: BigDecimal?,
    val number: BigDecimal?,
    val text: String?,
    val choice: String?,
    val choices: List<String>,
    val photoFileId: FileId?,
    val photoMissing: Boolean,
    val note: String?,
    val answeredAt: Instant,
)

/**
 * Uma execução de checklist (SPEC §8.2.11): a versão usada (nula só na pendente, que ainda não começou), o
 * sujeito, a rotina e a data operacional (só nas de rotina), o executor e os horários reais. [duplicateOf]: a
 * execução offline de uma rotina que outra pessoa já tinha assumido (RN-22). [offline]: iniciada ou concluída
 * por comando offline.
 */
data class ChecklistExecutionView(
    val id: ChecklistExecutionId,
    val status: ChecklistExecutionStatus,
    val templateId: ChecklistTemplateId,
    val versionId: ChecklistVersionId?,
    val versionNumber: Int?,
    val context: ChecklistContext,
    val subject: ChecklistSubject?,
    val routineId: ChecklistRoutineId?,
    val businessDate: LocalDate?,
    val dueAt: Instant?,
    val candidateIds: Set<UserId>,
    val duplicateOf: ChecklistExecutionId?,
    val executorId: UserId?,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val offline: Boolean,
    val answers: List<ChecklistAnswerView>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

/**
 * O resultado de uma conclusão: a execução e as chaves dos itens cuja foto ficou de fora — a que ainda não tinha
 * chegado ([missingPhotos], H10) e a inválida ([rejectedPhotos]: outra finalidade, outro autor ou outro registro).
 * Nos dois casos a resposta fica com `photoMissing` e nada é vinculado.
 */
data class ChecklistCompletion(
    val execution: ChecklistExecutionView,
    val missingPhotos: List<String>,
    val rejectedPhotos: List<String> = emptyList(),
)

/** Frequência de uma rotina (RF-CHK-08). */
enum class RoutineFrequency {
    /** Todo dia. */
    DAILY,

    /** Nos dias da semana escolhidos. */
    WEEKLY,
}

/**
 * Uma rotina (RF-CHK-08, SPEC §8.2.11, H14): o checklist recorrente sem tarefa associada (abertura do restaurante
 * às 6h, vistoria da piscina), do [templateId], no setor [departmentId], na [frequency] e no horário [dueAt] (no
 * fuso do hotel), com o local opcional ([location], de outro módulo).
 */
data class ChecklistRoutineView(
    val id: ChecklistRoutineId,
    val name: String,
    val templateId: ChecklistTemplateId,
    val departmentId: DepartmentId,
    val frequency: RoutineFrequency,
    val weekdays: Set<DayOfWeek>,
    val dueAt: LocalTime,
    val location: ChecklistSubject?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

/** Os dados de uma rotina como a gestão os informa ([ChecklistRoutineView] sem o estado). */
data class RoutineDefinition(
    val name: String,
    val templateId: ChecklistTemplateId,
    val departmentId: DepartmentId,
    val frequency: RoutineFrequency,
    val weekdays: Set<DayOfWeek>,
    val dueAt: LocalTime,
    val location: ChecklistSubject?,
)
