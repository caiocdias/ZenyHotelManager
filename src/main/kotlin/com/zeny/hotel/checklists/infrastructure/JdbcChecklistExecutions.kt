package com.zeny.hotel.checklists.infrastructure

import com.zeny.hotel.checklists.CheckAnswer
import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.checklists.ChecklistExecutionStatus
import com.zeny.hotel.checklists.ChecklistExecutionView
import com.zeny.hotel.checklists.ChecklistItemType
import com.zeny.hotel.checklists.ChecklistRoutineId
import com.zeny.hotel.checklists.ChecklistSubject
import com.zeny.hotel.checklists.ChecklistTemplateId
import com.zeny.hotel.checklists.ChecklistVersionId
import com.zeny.hotel.checklists.RoutineDefinition
import com.zeny.hotel.checklists.RoutineFrequency
import com.zeny.hotel.checklists.application.ChecklistExecutionDirectory
import com.zeny.hotel.checklists.application.ChecklistExecutionStore
import com.zeny.hotel.checklists.application.ChecklistRoutineStore
import com.zeny.hotel.checklists.application.ExecutionFilter
import com.zeny.hotel.checklists.application.GeneratingRoutine
import com.zeny.hotel.checklists.domain.ChecklistAnswer
import com.zeny.hotel.checklists.domain.ChecklistExecution
import com.zeny.hotel.checklists.domain.ChecklistRoutine
import com.zeny.hotel.files.FileId
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.staff.DepartmentId
import org.postgresql.util.PSQLException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Persistência das execuções, das respostas e das rotinas pelo `JdbcClient` (ARCHITECTURE §11), sempre
 * parametrizada. As respostas de uma execução são trocadas em bloco (apagadas e inseridas de novo) a cada
 * gravação; a execução e a rotina nunca são apagadas (trigger `checklists.refuse_delete`). A posição offline
 * (`change_seq`, `change_xid`) é do trigger, nunca da aplicação.
 */
@Component
@Suppress("TooManyFunctions") // a escrita e as leituras das execuções e das rotinas
class JdbcChecklistExecutions(
    private val jdbc: JdbcClient,
) : ChecklistExecutionStore,
    ChecklistExecutionDirectory,
    ChecklistRoutineStore {
    // --- Execuções ---------------------------------------------------------------------------------------

    override fun find(id: ChecklistExecutionId): ChecklistExecution? = loadFull(id.value)

    override fun findForUpdate(id: ChecklistExecutionId): ChecklistExecution? {
        val locked =
            jdbc
                .sql("SELECT id FROM checklists.checklist_execution WHERE id = :id FOR UPDATE")
                .param("id", id.value)
                .query(UUID::class.java)
                .optional()
        return if (locked.isEmpty) null else find(id)
    }

    override fun findDuplicateForUpdate(
        original: ChecklistExecutionId,
        executor: UserId,
    ): ChecklistExecution? {
        val locked =
            jdbc
                .sql(
                    """
                    SELECT id FROM checklists.checklist_execution
                    WHERE duplicate_of = :original AND executor_id = :executor
                    ORDER BY created_at, id LIMIT 1 FOR UPDATE
                    """.trimIndent(),
                ).param("original", original.value)
                .param("executor", executor.value)
                .query(UUID::class.java)
                .optional()
        return locked.map { find(ChecklistExecutionId(it)) }.orElse(null)
    }

    override fun add(execution: ChecklistExecution): Boolean {
        val inserted =
            unlessViolated(EXECUTION_PK) {
                jdbc
                    .sql("INSERT INTO checklists.checklist_execution ($COLUMNS) VALUES ($VALUES)")
                    .params(params(execution))
                    .update()
            }
        if (inserted) insertAnswers(execution)
        return inserted
    }

    override fun addScheduled(execution: ChecklistExecution): Boolean =
        jdbc
            .sql(
                """
                INSERT INTO checklists.checklist_execution ($COLUMNS) VALUES ($VALUES)
                ON CONFLICT (routine_id, business_date) WHERE routine_id IS NOT NULL AND duplicate_of IS NULL
                DO NOTHING
                """.trimIndent(),
            ).params(params(execution))
            .update() == 1

    override fun update(execution: ChecklistExecution) {
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE checklists.checklist_execution
                    SET version_id = :versionId, version_number = :versionNumber, status = :status,
                        executor_id = :executorId, started_at = :startedAt, completed_at = :completedAt,
                        device_session_id = :deviceSessionId, start_command_id = :startCommandId,
                        complete_command_id = :completeCommandId, updated_at = :updatedAt, version = :version
                    WHERE id = :id
                    """.trimIndent(),
                ).params(params(execution))
                .update()
        check(updated == 1) { "Execução ${execution.id} não encontrada para gravar." }
        jdbc
            .sql("DELETE FROM checklists.checklist_answer WHERE execution_id = :id")
            .param("id", execution.id.value)
            .update()
        insertAnswers(execution)
    }

    override fun executions(
        filter: ExecutionFilter,
        owner: UUID?,
        before: Pair<Instant, UUID>?,
        limit: Int,
    ): List<ChecklistExecutionView> =
        jdbc
            .sql(
                """
                SELECT e.* FROM checklists.checklist_execution e
                WHERE (CAST(:status AS text) IS NULL OR e.status = CAST(:status AS text))
                  AND (CAST(:context AS text) IS NULL OR e.context = CAST(:context AS text))
                  AND (CAST(:routineId AS uuid) IS NULL OR e.routine_id = CAST(:routineId AS uuid))
                  AND (CAST(:businessDate AS date) IS NULL OR e.business_date = CAST(:businessDate AS date))
                  AND (CAST(:subjectType AS text) IS NULL OR e.subject_type = CAST(:subjectType AS text))
                  AND (CAST(:subjectId AS uuid) IS NULL OR e.subject_id = CAST(:subjectId AS uuid))
                  AND (CAST(:owner AS uuid) IS NULL OR e.executor_id = CAST(:owner AS uuid)
                       OR CAST(:owner AS uuid) = ANY (e.candidate_ids))
                  AND (CAST(:beforeAt AS timestamptz) IS NULL
                       OR (e.created_at, e.id) < (CAST(:beforeAt AS timestamptz), CAST(:beforeId AS uuid)))
                ORDER BY e.created_at DESC, e.id DESC LIMIT :limit
                """.trimIndent(),
            ).param("status", filter.status?.name)
            .param("context", filter.context?.name)
            .param("routineId", filter.routineId?.value)
            .param("businessDate", filter.businessDate)
            .param("subjectType", filter.subjectType)
            .param("subjectId", filter.subjectId)
            .param("owner", owner)
            .param("beforeAt", before?.first?.let(::utc))
            .param("beforeId", before?.second)
            .param("limit", limit)
            .query { rs, _ -> executionOf(rs, emptyList()).view() }
            .list()

    private fun loadFull(id: UUID): ChecklistExecution? {
        val answers =
            jdbc
                .sql("SELECT * FROM checklists.checklist_answer WHERE execution_id = :id")
                .param("id", id)
                .query { rs, _ -> answerOf(rs) }
                .list()
        return jdbc
            .sql("SELECT e.* FROM checklists.checklist_execution e WHERE e.id = :id")
            .param("id", id)
            .query { rs, _ -> executionOf(rs, answers) }
            .optional()
            .orElse(null)
    }

    private fun insertAnswers(execution: ChecklistExecution) {
        for (answer in execution.answers) {
            jdbc
                .sql(
                    """
                    INSERT INTO checklists.checklist_answer
                        (execution_id, item_key, item_type, check_value, quantity, number_value, text_value,
                         choice_values, photo_file_id, photo_missing, note, answered_at)
                    VALUES (:executionId, :itemKey, :itemType, :check, :quantity, :number, :text,
                            CAST(:choices AS varchar(40)[]), :photo, :photoMissing, :note, :answeredAt)
                    """.trimIndent(),
                ).param("executionId", execution.id.value)
                .param("itemKey", answer.itemKey)
                .param("itemType", answer.itemType.name)
                .param("check", answer.check?.name)
                .param("quantity", answer.quantity)
                .param("number", answer.number)
                .param("text", answer.text)
                .param("choices", storedChoices(answer)?.let(::pgArray))
                .param("photo", answer.photoFileId?.value)
                .param("photoMissing", answer.photoMissing)
                .param("note", answer.note)
                .param("answeredAt", utc(answer.answeredAt))
                .update()
        }
    }

    /** As escolhas na coluna `choice_values`: a múltipla com as marcadas, a única com a escolhida. */
    private fun storedChoices(answer: ChecklistAnswer): List<String>? =
        when (answer.itemType) {
            ChecklistItemType.MULTI_CHOICE -> answer.choices
            ChecklistItemType.SINGLE_CHOICE -> listOfNotNull(answer.choice)
            else -> null
        }

    private fun params(execution: ChecklistExecution): Map<String, Any?> =
        mapOf(
            "id" to execution.id.value,
            "templateId" to execution.templateId.value,
            "versionId" to execution.versionId?.value,
            "versionNumber" to execution.versionNumber,
            "context" to execution.context.name,
            "subjectType" to execution.subject?.type,
            "subjectId" to execution.subject?.id,
            "routineId" to execution.routineId?.value,
            "businessDate" to execution.businessDate,
            "dueAt" to execution.dueAt?.let(::utc),
            "candidates" to pgArray(execution.candidates.map { it.value.toString() }.sorted()),
            "duplicateOf" to execution.duplicateOf?.value,
            "status" to execution.status.name,
            "executorId" to execution.executorId?.value,
            "startedAt" to execution.startedAt?.let(::utc),
            "completedAt" to execution.completedAt?.let(::utc),
            "deviceSessionId" to execution.deviceSessionId?.value,
            "startCommandId" to execution.startCommandId,
            "completeCommandId" to execution.completeCommandId,
            "createdAt" to utc(execution.createdAt),
            "updatedAt" to utc(execution.updatedAt),
            "version" to execution.version,
        )

    private fun executionOf(
        rs: ResultSet,
        answers: List<ChecklistAnswer>,
    ): ChecklistExecution {
        val subjectType = rs.getString("subject_type")
        return ChecklistExecution.restore(
            ChecklistExecutionId(rs.uuid("id")),
            ChecklistTemplateId(rs.uuid("template_id")),
            rs.uuidOrNull("version_id")?.let(::ChecklistVersionId),
            rs.intOrNull("version_number"),
            ChecklistContext.valueOf(rs.getString("context")),
            subjectType?.let { ChecklistSubject(it, rs.uuid("subject_id")) },
            rs.uuidOrNull("routine_id")?.let(::ChecklistRoutineId),
            rs.getObject("business_date", LocalDate::class.java),
            rs.instantOrNull("due_at"),
            uuidArray(rs, "candidate_ids").map(::UserId).toSet(),
            rs.uuidOrNull("duplicate_of")?.let(::ChecklistExecutionId),
            ChecklistExecutionStatus.valueOf(rs.getString("status")),
            rs.uuidOrNull("executor_id")?.let(::UserId),
            rs.instantOrNull("started_at"),
            rs.instantOrNull("completed_at"),
            rs.uuidOrNull("device_session_id")?.let(::DeviceSessionId),
            rs.uuidOrNull("start_command_id"),
            rs.uuidOrNull("complete_command_id"),
            answers,
            rs.instant("created_at"),
            rs.instant("updated_at"),
            rs.getLong("version"),
        )
    }

    private fun answerOf(rs: ResultSet) =
        ChecklistAnswer(
            itemKey = rs.getString("item_key"),
            itemType = ChecklistItemType.valueOf(rs.getString("item_type")),
            check = rs.getString("check_value")?.let(CheckAnswer::valueOf),
            quantity = rs.getBigDecimal("quantity")?.let(::normalized),
            number = rs.getBigDecimal("number_value")?.let(::normalized),
            text = rs.getString("text_value"),
            choice = null,
            choices = stringArray(rs, "choice_values"),
            photoFileId = rs.uuidOrNull("photo_file_id")?.let(::FileId),
            photoMissing = rs.getBoolean("photo_missing"),
            note = rs.getString("note"),
            answeredAt = rs.instant("answered_at"),
        ).let { answer ->
            // A escolha única vai na mesma coluna de escolhas, com um valor só.
            if (answer.itemType == ChecklistItemType.SINGLE_CHOICE) {
                answer.copy(choice = answer.choices.singleOrNull(), choices = emptyList())
            } else {
                answer
            }
        }

    // --- Rotinas -----------------------------------------------------------------------------------------

    override fun find(id: ChecklistRoutineId): ChecklistRoutine? = loadRoutine(id, forUpdate = false)

    override fun findForUpdate(id: ChecklistRoutineId): ChecklistRoutine? = loadRoutine(id, forUpdate = true)

    override fun add(routine: ChecklistRoutine) {
        jdbc
            .sql(
                """
                INSERT INTO checklists.checklist_routine
                    (id, name, template_id, department_id, frequency, weekdays, due_at, subject_type, subject_id,
                     active, created_at, updated_at, version)
                VALUES (:id, :name, :templateId, :departmentId, :frequency, :weekdays, :dueAt, :subjectType,
                        :subjectId, :active, :createdAt, :updatedAt, :version)
                """.trimIndent(),
            ).params(routineParams(routine))
            .update()
    }

    override fun update(routine: ChecklistRoutine) {
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE checklists.checklist_routine
                    SET name = :name, template_id = :templateId, department_id = :departmentId,
                        frequency = :frequency, weekdays = :weekdays, due_at = :dueAt, subject_type = :subjectType,
                        subject_id = :subjectId, active = :active, updated_at = :updatedAt, version = :version
                    WHERE id = :id
                    """.trimIndent(),
                ).params(routineParams(routine))
                .update()
        check(updated == 1) { "Rotina ${routine.id} não encontrada para gravar." }
    }

    override fun routines(
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<ChecklistRoutine> =
        jdbc
            .sql(
                """
                SELECT * FROM checklists.checklist_routine r
                WHERE (CAST(:active AS boolean) IS NULL OR r.active = CAST(:active AS boolean))
                  AND (CAST(:afterName AS text) IS NULL
                       OR (r.name, r.id) > (CAST(:afterName AS text), CAST(:afterId AS uuid)))
                ORDER BY r.name, r.id LIMIT :limit
                """.trimIndent(),
            ).param("active", active)
            .param("afterName", after?.first)
            .param("afterId", after?.second)
            .param("limit", limit)
            .query { rs, _ -> routineOf(rs) }
            .list()

    override fun generating(): List<GeneratingRoutine> =
        jdbc
            .sql(
                """
                SELECT r.*, t.context AS template_context
                FROM checklists.checklist_routine r
                JOIN checklists.checklist_template t ON t.id = r.template_id
                WHERE r.active AND t.active AND t.published_version IS NOT NULL
                ORDER BY r.id
                """.trimIndent(),
            ).query { rs, _ ->
                GeneratingRoutine(routineOf(rs), ChecklistContext.valueOf(rs.getString("template_context")))
            }.list()

    private fun loadRoutine(
        id: ChecklistRoutineId,
        forUpdate: Boolean,
    ): ChecklistRoutine? =
        jdbc
            .sql("SELECT * FROM checklists.checklist_routine WHERE id = :id" + if (forUpdate) " FOR UPDATE" else "")
            .param("id", id.value)
            .query { rs, _ -> routineOf(rs) }
            .optional()
            .orElse(null)

    private fun routineParams(routine: ChecklistRoutine): Map<String, Any?> {
        val definition = routine.definition
        return mapOf(
            "id" to routine.id.value,
            "name" to definition.name,
            "templateId" to definition.templateId.value,
            "departmentId" to definition.departmentId.value,
            "frequency" to definition.frequency.name,
            "weekdays" to definition.weekdays.sorted().joinToString(","),
            "dueAt" to definition.dueAt,
            "subjectType" to definition.location?.type,
            "subjectId" to definition.location?.id,
            "active" to routine.active,
            "createdAt" to utc(routine.createdAt),
            "updatedAt" to utc(routine.updatedAt),
            "version" to routine.version,
        )
    }

    private fun routineOf(rs: ResultSet): ChecklistRoutine {
        val subjectType = rs.getString("subject_type")
        val weekdays = rs.getString("weekdays")
        return ChecklistRoutine.restore(
            ChecklistRoutineId(rs.uuid("id")),
            RoutineDefinition(
                rs.getString("name"),
                ChecklistTemplateId(rs.uuid("template_id")),
                DepartmentId(rs.uuid("department_id")),
                RoutineFrequency.valueOf(rs.getString("frequency")),
                if (weekdays.isEmpty()) emptySet() else weekdays.split(',').map(DayOfWeek::valueOf).toSet(),
                rs.getObject("due_at", LocalTime::class.java),
                subjectType?.let { ChecklistSubject(it, rs.uuid("subject_id")) },
            ),
            rs.getBoolean("active"),
            rs.instant("created_at"),
            rs.instant("updated_at"),
            rs.getLong("version"),
        )
    }

    private companion object {
        const val EXECUTION_PK = "checklist_execution_pk"
        const val COLUMNS =
            "id, template_id, version_id, version_number, context, subject_type, subject_id, routine_id, " +
                "business_date, due_at, candidate_ids, duplicate_of, status, executor_id, started_at, completed_at, " +
                "device_session_id, start_command_id, complete_command_id, created_at, updated_at, version"
        const val VALUES =
            ":id, :templateId, :versionId, :versionNumber, :context, :subjectType, :subjectId, :routineId, " +
                ":businessDate, :dueAt, CAST(:candidates AS uuid[]), :duplicateOf, :status, :executorId, :startedAt, " +
                ":completedAt, :deviceSessionId, :startCommandId, :completeCommandId, :createdAt, :updatedAt, :version"
    }
}

/**
 * Violação da unicidade [constraint] (`23505`), reconhecida pelo campo `constraint` do erro do servidor na cadeia de
 * causas, nunca pelo texto (como os adaptadores do `iam`, do `staff` e do `rooms`), vira `false`; qualquer outra
 * segue.
 */
private fun unlessViolated(
    constraint: String,
    write: () -> Unit,
): Boolean =
    try {
        write()
        true
    } catch (e: DataIntegrityViolationException) {
        val violated =
            generateSequence<Throwable>(e) { it.cause }
                .filterIsInstance<PSQLException>()
                .any { it.sqlState == UNIQUE_VIOLATION && it.serverErrorMessage?.constraint == constraint }
        if (!violated) throw e
        false
    }

private const val UNIQUE_VIOLATION = "23505"

/** Um literal de array do PostgreSQL (`{a,b}`) com valores sem vírgula, aspas nem chaves (UUIDs e chaves). */
private fun pgArray(values: List<String>): String = values.joinToString(",", "{", "}")

private fun uuidArray(
    rs: ResultSet,
    column: String,
): List<UUID> = stringArray(rs, column).map(UUID::fromString)

private fun stringArray(
    rs: ResultSet,
    column: String,
): List<String> {
    val array = rs.getArray(column) ?: return emptyList()
    @Suppress("UNCHECKED_CAST")
    return (array.array as Array<Any>).map { it.toString() }
}

private fun normalized(value: BigDecimal): BigDecimal =
    value.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

private fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)

private fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

private fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

private fun ResultSet.instantOrNull(column: String): Instant? =
    getObject(column, OffsetDateTime::class.java)?.toInstant()

private fun utc(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)
