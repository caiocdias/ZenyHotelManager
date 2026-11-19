package com.zeny.hotel.housekeeping

import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistsTestSupport
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.RealtimeTopic
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.application.realtime.RealtimeHub
import com.zeny.hotel.shared.application.realtime.StreamFrame
import com.zeny.hotel.shared.application.realtime.StreamSink
import com.zeny.hotel.shared.application.realtime.StreamSubscription
import com.zeny.hotel.staff.StaffApi
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpMethod.GET
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Apoio dos testes da inspeção (M6 E05, Task 2): tipos de limpeza novos, um modelo de inspeção só para o tipo do
 * teste, tarefas `AWAITING_INSPECTION` em UHs novas, inspetores (Governanta), o painel de avisos de um usuário e um
 * assinante de tempo real. Quem usa neutraliza os modelos gerais de governança e de inspeção no `@BeforeEach`.
 */
class InspectionTestSupport(
    private val iam: IamTestSupport,
    private val jdbc: JdbcClient,
    staffApi: StaffApi,
) {
    val execution = HousekeepingExecutionTestSupport(iam, jdbc, staffApi)
    val housekeeping = execution.housekeeping
    private val templates = ChecklistsTestSupport(iam)

    /** Um tipo de limpeza novo, com ou sem inspeção. */
    fun cleaningType(requiresInspection: Boolean = true): UUID =
        housekeeping.createCleaningType(housekeeping.cleaningTypeBody(requiresInspection = requiresInspection))

    /** Um modelo de inspeção publicado, com `bed` obrigatório e `towels` e `floor` opcionais, só para o [type]. */
    fun inspectionTemplate(type: UUID): UUID {
        val id =
            templates.create(
                templates.templateBody(
                    context = ChecklistContext.INSPECTION,
                    applicability = listOf(mapOf("cleaningTypeIds" to listOf(type))),
                    sections =
                        listOf(
                            templates.section(
                                "Quarto",
                                templates.item("bed", extra = mapOf("required" to true)),
                                templates.item("towels"),
                                templates.item("floor"),
                            ),
                        ),
                ),
            )
        templates.publish(id)
        return id
    }

    /** Uma tarefa nova do [type] numa UH nova, em `ASSIGNED` com a [attendant]. */
    fun assignedTask(
        type: UUID,
        attendant: UUID,
    ): Pair<UUID, RoomId> {
        val room = RoomId(housekeeping.createRoom())
        val task =
            UUID.fromString(
                housekeeping.requestTask(housekeeping.taskBody(type, roomId = room.value))["id"].asString(),
            )
        execution.assign(task, attendant)
        return task to room
    }

    /**
     * Uma tarefa do [type] numa UH nova, limpa pela camareira [attendant] (`start` e `complete`):
     * `AWAITING_INSPECTION`, com a UH Limpa e a inspeção pendente.
     */
    fun awaitingInspection(
        type: UUID,
        attendant: HousekeepingExecutionTestSupport.Attendant = execution.attendant(),
    ): Pair<UUID, RoomId> {
        val (task, room) = assignedTask(type, attendant.employee)
        val auth = iam.asTestUser(attendant.user)
        execution.acted(task, "start", auth)
        iam.json(execution.acted(task, "complete", auth).second)["status"].asString() shouldBe "AWAITING_INSPECTION"
        return task to room
    }

    /** Uma Governanta nova (o modelo da carga, com `housekeeping.inspections.perform` e `housekeeping.tasks.read`). */
    fun inspector(): TestUser = execution.userWithTemplate(RoleTemplate.HOUSEKEEPER)

    /** As notificações do usuário no painel dele, da mais nova para a mais velha. */
    fun panel(user: TestUser): List<JsonNode> =
        with(iam) { call(GET, "/api/v1/notifications?limit=100", asTestUser(user)).ok() }["items"].values().toList()

    /** As notificações do [type] no painel do [user]. */
    fun notices(
        user: TestUser,
        type: String,
    ): List<JsonNode> = panel(user).filter { it["type"].asString() == type }

    fun complete(
        result: String,
        rejectedItems: List<String?>? = null,
        answers: List<Map<String, Any?>>? = null,
    ): Map<String, Any?> =
        buildMap {
            put("result", result)
            if (rejectedItems != null) put("rejectedItems", rejectedItems)
            if (answers != null) put("answers", answers)
        }

    fun check(key: String): Map<String, Any?> = mapOf("itemKey" to key, "check" to "OK")

    fun inspectionRow(task: UUID): Map<String, Any?> =
        jdbc
            .sql("SELECT * FROM housekeeping.inspection WHERE task_id = :id ORDER BY started_at DESC LIMIT 1")
            .param("id", task)
            .query()
            .singleRow()

    /** As chaves reprovadas gravadas na última inspeção da [task]. */
    fun rejectedItemsOf(task: UUID): List<String> =
        jdbc
            .sql(
                "SELECT rejected_items FROM housekeeping.inspection WHERE task_id = :id ORDER BY started_at DESC " +
                    "LIMIT 1",
            ).param("id", task)
            .query { rs, _ -> (rs.getArray(1).array as Array<*>).map { it.toString() } }
            .single()

    /** Um assinante dos [topics] do hub de tempo real, com os quadros recebidos. */
    class Listener(
        private val hub: RealtimeHub,
        private val json: JsonMapper,
        clock: Clock,
        vararg topics: RealtimeTopic,
    ) : StreamSink,
        AutoCloseable {
        val events = CopyOnWriteArrayList<StreamFrame.Event>()
        private val connection =
            hub.connect(
                StreamSubscription(
                    Actor(UserId.new(), DeviceSessionId.new()),
                    topics.associateWith { PermissionScope.ALL },
                    clock.instant().plus(1, ChronoUnit.HOURS),
                ),
                null,
                this,
            )

        override fun send(frame: StreamFrame) {
            if (frame is StreamFrame.Event) events += frame
        }

        override fun complete() = Unit

        /** Se chegou a mensagem [type] do [id]. O primeiro quadro (`ready`) não é uma mensagem de tópico. */
        fun received(
            type: String,
            id: UUID,
        ): Boolean =
            events.any { event ->
                val message = json.readTree(event.data)
                message["type"]?.asString() == type && message["id"]?.asString() == id.toString()
            }

        override fun close() = hub.release(connection)
    }
}
