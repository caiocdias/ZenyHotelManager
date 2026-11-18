package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.CLEANING_TYPES
import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.TASKS
import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.definition
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Permissões e auditoria das tarefas de governança (M6 E02, Task 1): `housekeeping.tasks.read` e
 * `housekeeping.tasks.request`, só de escopo `ALL`, não sensíveis, nos modelos Gerente, Governanta e Recepcionista; a
 * autorização antes de qualquer recusa que revele estado; e a trilha `housekeeping.task.request`, com o retrato sem o
 * texto das `notes` (RN-13).
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class HousekeepingTaskPermissionsTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val json: JsonMapper,
    @Autowired private val hotel: HotelApi,
) {
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)

    @Test
    fun `the two task permissions are ALL only, not sensitive, for manager, housekeeper and receptionist`() {
        listOf(TASKS_READ, TASKS_REQUEST).forEach { code ->
            withClue(code) {
                val definition = definition(PermissionCode(code))
                definition.scopes shouldBe setOf(ALL)
                definition.sensitive shouldBe false
                jdbc
                    .sql("SELECT sensitive, module FROM iam.permission WHERE code = :code AND obsolete_at IS NULL")
                    .param("code", code)
                    .query { rs, _ -> rs.getBoolean("sensitive") to rs.getString("module") }
                    .single() shouldBe (false to "housekeeping")
                templatesOf(code) shouldContainExactlyInAnyOrder
                    listOf(
                        "ADMINISTRATOR" to "ALL",
                        "MANAGER" to "ALL",
                        "HOUSEKEEPER" to "ALL",
                        "RECEPTIONIST" to "ALL",
                    )
            }
        }
    }

    @Test
    fun `without request the ad hoc request is 403, before any 400 UNKNOWN, 409 or 422`(): Unit =
        with(iam) {
            val reader = asTestUser(createUserWith(TestGrant(definition(PermissionCode(TASKS_READ)), ALL)))
            val type = housekeeping.createCleaningType()
            val room = housekeeping.createRoom()
            housekeeping.requestTask(housekeeping.taskBody(type, roomId = room))
            val inactiveType = housekeeping.createCleaningType()
            call(POST, "$CLEANING_TYPES/$inactiveType/deactivate", asAdmin()).ok()
            val inactiveRoom = housekeeping.createRoom()
            call(POST, "/api/v1/rooms/$inactiveRoom/deactivate", asAdmin()).ok()
            val missing = UUID.randomUUID()
            val yesterday = "${hotel.currentBusinessDate().minusDays(1)}"
            val refused =
                listOf(
                    housekeeping.taskBody(type, roomId = missing), // 400 UNKNOWN_LOCATION
                    housekeeping.taskBody(type, areaId = missing), // 400 UNKNOWN_LOCATION
                    housekeeping.taskBody(missing, roomId = housekeeping.createRoom()), // 400 UNKNOWN_CLEANING_TYPE
                    housekeeping.taskBody(type), // 400 LOCATION_INVALID
                    housekeeping.taskBody(type, roomId = inactiveRoom), // 422 LOCATION_INACTIVE
                    housekeeping.taskBody(inactiveType, roomId = housekeeping.createRoom()), // 422 TYPE_INACTIVE
                    housekeeping.taskBody(type, roomId = housekeeping.createRoom(), date = yesterday), // 422 DATE
                    housekeeping.taskBody(type, roomId = room), // 409 ALREADY_EXISTS
                    housekeeping.taskBody(type, roomId = housekeeping.createRoom()), // 201 com a permissão
                )
            refused.forEach { body ->
                withClue(body) { call(POST, TASKS, reader, body).problem(403, "PERMISSION_DENIED") }
            }

            // O leitor lê.
            call(GET, "$TASKS?roomId=$room", reader).ok()["items"].size() shouldBe 1
        }

    @Test
    fun `without read the reads are 403, before any not found`(): Unit =
        with(iam) {
            val requesterUser = createUserWith(TestGrant(definition(PermissionCode(TASKS_REQUEST)), ALL))
            val requester = asTestUser(requesterUser)
            val nobody = asTestUser(createUser())
            val existing =
                housekeeping
                    .requestTask(
                        housekeeping.taskBody(housekeeping.createCleaningType(), roomId = housekeeping.createRoom()),
                    )["id"]
                    .asString()

            listOf(requester, nobody).forEach { user ->
                call(GET, TASKS, user).problem(403, "PERMISSION_DENIED")
                call(GET, "$TASKS/$existing", user).problem(403, "PERMISSION_DENIED")
                call(GET, "$TASKS/${UUID.randomUUID()}", user).problem(403, "PERMISSION_DENIED")
            }
            // Sem permissão nenhuma, o POST também é 403; quem só solicita solicita, e a tarefa guarda quem pediu.
            val body = housekeeping.taskBody(housekeeping.createCleaningType(), roomId = housekeeping.createRoom())
            call(POST, TASKS, nobody, body).problem(403, "PERMISSION_DENIED")
            val created = call(POST, TASKS, requester, body).expectStatus(201)!!
            createdBy(UUID.fromString(created["id"].asString())) shouldBe requesterUser.id.value
        }

    @Test
    fun `the request is audited as housekeeping task request, with a snapshot without the notes text`(): Unit =
        with(iam) {
            val type = housekeeping.createCleaningType()
            val room = housekeeping.createRoom()
            val created =
                housekeeping.requestTask(housekeeping.taskBody(type, roomId = room, notes = "Segredo das notas"))
            val id = UUID.fromString(created["id"].asString())

            housekeeping.auditActions("housekeeping.task", id) shouldContainExactly listOf("housekeeping.task.request")
            val after =
                jdbc
                    .sql(
                        "SELECT after_state::text FROM audit.audit_entry WHERE entity_type = 'housekeeping.task' " +
                            "AND entity_id = :id AND action = 'housekeeping.task.request'",
                    ).param("id", id.toString())
                    .query(String::class.java)
                    .single()
            after shouldNotContain "Segredo"
            val state = json.readTree(after)
            state.propertyNames().asSequence().toList() shouldContainExactlyInAnyOrder
                listOf(
                    "roomId",
                    "areaId",
                    "date",
                    "cleaningTypeId",
                    "source",
                    "status",
                    "priority",
                    "credits",
                    "arrivalExpected",
                    "scheduledCleaningId",
                    "notesLength",
                )
            state["roomId"].asString() shouldBe room.toString()
            state["source"].asString() shouldBe "AD_HOC"
            state["notesLength"].asInt() shouldBe "Segredo das notas".length
        }

    private fun createdBy(task: UUID): UUID? =
        jdbc
            .sql("SELECT created_by FROM housekeeping.housekeeping_task WHERE id = :id")
            .param("id", task)
            .query(UUID::class.java)
            .single()

    private fun templatesOf(code: String): List<Pair<String, String>> =
        jdbc
            .sql(
                """
                SELECT r.template_key, g.scope FROM iam.role r
                JOIN iam.role_grant g ON g.role_id = r.id
                WHERE r.template_key IS NOT NULL AND g.permission_code = :code
                """.trimIndent(),
            ).param("code", code)
            .query { rs, _ -> rs.getString("template_key") to rs.getString("scope") }
            .list()

    private companion object {
        const val TASKS_READ = "housekeeping.tasks.read"
        const val TASKS_REQUEST = "housekeeping.tasks.request"
    }
}
