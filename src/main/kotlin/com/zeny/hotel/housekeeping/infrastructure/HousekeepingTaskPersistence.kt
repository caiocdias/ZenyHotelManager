package com.zeny.hotel.housekeeping.infrastructure

import com.zeny.hotel.housekeeping.HousekeepingTaskId
import com.zeny.hotel.housekeeping.ScheduledCleaningId
import com.zeny.hotel.housekeeping.application.HousekeepingTaskStore
import com.zeny.hotel.housekeeping.application.TaskFilter
import com.zeny.hotel.housekeeping.application.TaskPageKey
import com.zeny.hotel.housekeeping.domain.HousekeepingTask
import com.zeny.hotel.rooms.RoomId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

interface HousekeepingTaskJpaRepository : JpaRepository<HousekeepingTask, UUID>

/**
 * As tarefas de governança: a leitura por JPA; a gravação (`INSERT … ON CONFLICT DO NOTHING` na unicidade por local,
 * data e tipo, para que a tarefa repetida não aborte a transação de quem gera) e as páginas por `JdbcClient`, sempre
 * parametrizadas e só no schema `housekeeping`.
 */
@Component
class JpaHousekeepingTaskStore(
    private val jdbc: JdbcClient,
    private val repository: HousekeepingTaskJpaRepository,
) : HousekeepingTaskStore {
    override fun task(id: HousekeepingTaskId): HousekeepingTask? = repository.findById(id.value).orElse(null)

    override fun tasks(ids: List<UUID>): List<HousekeepingTask> {
        val byId = repository.findAllById(ids).associateBy { it.id.value }
        return ids.mapNotNull(byId::get)
    }

    /**
     * A ordem (chegada desc, prioridade desc, id) é a ordem crescente de (`NOT arrival_expected`, `-priority`, `id`),
     * então a página seguinte é a comparação de linha com as chaves do cursor.
     */
    override fun page(
        date: LocalDate,
        filter: TaskFilter,
        after: TaskPageKey?,
        limit: Int,
    ): List<TaskPageKey> =
        jdbc
            .sql(
                """
                SELECT t.arrival_expected, t.priority, t.id FROM housekeeping.housekeeping_task t
                WHERE t.business_date = :date
                  AND (CAST(:roomId AS uuid) IS NULL OR t.room_id = CAST(:roomId AS uuid))
                  AND (CAST(:areaId AS uuid) IS NULL OR t.area_id = CAST(:areaId AS uuid))
                  AND (CAST(:status AS text) IS NULL OR t.status = CAST(:status AS text))
                  AND (CAST(:typeId AS uuid) IS NULL OR t.cleaning_type_id = CAST(:typeId AS uuid))
                  AND (CAST(:source AS text) IS NULL OR t.source = CAST(:source AS text))
                  AND (CAST(:afterArrival AS boolean) IS NULL
                       OR (NOT t.arrival_expected, -t.priority, t.id)
                          > (NOT CAST(:afterArrival AS boolean), -CAST(:afterPriority AS int), CAST(:afterId AS uuid)))
                ORDER BY t.arrival_expected DESC, t.priority DESC, t.id LIMIT :limit
                """.trimIndent(),
            ).param("date", date)
            .param("roomId", filter.roomId?.value)
            .param("areaId", filter.areaId?.value)
            .param("status", filter.status?.name)
            .param("typeId", filter.cleaningTypeId?.value)
            .param("source", filter.source?.name)
            .param("afterArrival", after?.arrivalExpected)
            .param("afterPriority", after?.priority)
            .param("afterId", after?.id)
            .param("limit", limit)
            .query { rs, _ ->
                TaskPageKey(
                    rs.getBoolean("arrival_expected"),
                    rs.getInt("priority"),
                    rs.getObject("id", UUID::class.java),
                )
            }.list()

    override fun insertIfAbsent(task: HousekeepingTask): Boolean =
        jdbc
            .sql(
                """
                INSERT INTO housekeeping.housekeeping_task (id, room_id, area_id, business_date, cleaning_type_id,
                    source, status, priority, credits, arrival_expected, scheduled_cleaning_id, notes, created_by,
                    created_at, updated_at, version)
                VALUES (:id, :roomId, :areaId, :date, :typeId, :source, :status, :priority, :credits, :arrival,
                    :scheduleId, :notes, :createdBy, :createdAt, :updatedAt, 0)
                ON CONFLICT ON CONSTRAINT housekeeping_task_location_date_type_uk DO NOTHING
                """.trimIndent(),
            ).param("id", task.id.value)
            .param("roomId", task.roomId?.value)
            .param("areaId", task.areaId?.value)
            .param("date", task.businessDate)
            .param("typeId", task.cleaningTypeId.value)
            .param("source", task.source.name)
            .param("status", task.status.name)
            .param("priority", task.priority)
            .param("credits", task.credits)
            .param("arrival", task.arrivalExpected)
            .param("scheduleId", task.scheduledCleaningId?.value)
            .param("notes", task.notes)
            .param("createdBy", task.createdBy?.value)
            .param("createdAt", task.createdAt.atOffset(ZoneOffset.UTC))
            .param("updatedAt", task.updatedAt.atOffset(ZoneOffset.UTC))
            .update() == 1

    override fun hasOpenTaskFor(scheduledCleaningId: ScheduledCleaningId): Boolean =
        jdbc
            .sql(
                "SELECT EXISTS (SELECT 1 FROM housekeeping.housekeeping_task WHERE scheduled_cleaning_id = :id " +
                    "AND status NOT IN ('DONE', 'CANCELLED'))",
            ).param("id", scheduledCleaningId.value)
            .query(Boolean::class.java)
            .single()

    override fun assignableUpTo(
        date: LocalDate,
        includePrevious: Boolean,
    ): List<HousekeepingTask> =
        tasks(
            jdbc
                .sql(
                    """
                    SELECT id FROM housekeeping.housekeeping_task
                    WHERE status IN ('PENDING', 'ASSIGNED')
                      AND (business_date = :date OR (CAST(:previous AS boolean) AND business_date < :date))
                    ORDER BY id
                    """.trimIndent(),
                ).param("date", date)
                .param("previous", includePrevious)
                .query { rs, _ -> rs.getObject("id", UUID::class.java) }
                .list(),
        )

    override fun update(task: HousekeepingTask) {
        repository.saveAndFlush(task)
    }

    override fun lastTaskDate(
        roomId: RoomId,
        onOrBefore: LocalDate,
    ): LocalDate? =
        jdbc
            .sql(
                "SELECT max(business_date) FROM housekeeping.housekeeping_task " +
                    "WHERE room_id = :roomId AND business_date <= :onOrBefore AND status <> 'CANCELLED'",
            ).param("roomId", roomId.value)
            .param("onOrBefore", onOrBefore)
            .query { rs, _ -> rs.getObject(1, LocalDate::class.java) }
            .list()
            .single() // o max() devolve sempre uma linha, nula sem tarefa
}
