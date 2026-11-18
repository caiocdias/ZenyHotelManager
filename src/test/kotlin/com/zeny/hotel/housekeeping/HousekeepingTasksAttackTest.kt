package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.CLEANING_TYPES
import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.TASKS
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Recusas da solicitação avulsa e da lista de tarefas (M6 E02, Task 1): referência inexistente `400 …UNKNOWN_…` (H16),
 * local ou tipo inativos `422`, local ausente ou duplo `400`, avulsa repetida no mesmo (local, data, tipo) `409` em
 * qualquer status (D5), `notes` longas ou com NUL `400`, data fora da janela `422` e cursor adulterado `400`, nunca
 * `500`. Nenhuma recusa grava tarefa.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class HousekeepingTasksAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired flyway: Flyway,
) {
    private val owner = JdbcClient.create(flyway.configuration.dataSource)
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)
    private val rooms = housekeeping.rooms

    @Test
    fun `an unknown room, area or cleaning type is 400 UNKNOWN on the field`(): Unit =
        with(iam) {
            val type = housekeeping.createCleaningType()
            val missing = UUID.randomUUID()

            post(housekeeping.taskBody(type, roomId = missing))
                .problem(400, "HOUSEKEEPING_TASK_UNKNOWN_LOCATION")
                .field() shouldBe "roomId"
            post(housekeeping.taskBody(type, areaId = missing))
                .problem(400, "HOUSEKEEPING_TASK_UNKNOWN_LOCATION")
                .field() shouldBe "areaId"
            val room = housekeeping.createRoom()
            post(housekeeping.taskBody(missing, roomId = room))
                .problem(400, "HOUSEKEEPING_TASK_UNKNOWN_CLEANING_TYPE")
                .field() shouldBe "cleaningTypeId"
            countTasksOf(room) shouldBe 0
        }

    @Test
    fun `an inactive room or area is 422 and an inactive type is 422 CLEANING_TYPE_INACTIVE`(): Unit =
        with(iam) {
            val type = housekeeping.createCleaningType()
            val room = housekeeping.createRoom()
            call(POST, "/api/v1/rooms/$room/deactivate", asAdmin()).ok()
            val area = rooms.createArea()
            call(POST, "/api/v1/areas/$area/deactivate", asAdmin()).ok()

            post(housekeeping.taskBody(type, roomId = room)).problem(422, "HOUSEKEEPING_TASK_LOCATION_INACTIVE")
            post(housekeeping.taskBody(type, areaId = area)).problem(422, "HOUSEKEEPING_TASK_LOCATION_INACTIVE")

            val inactive = housekeeping.createCleaningType()
            call(POST, "$CLEANING_TYPES/$inactive/deactivate", asAdmin()).ok()
            val other = housekeeping.createRoom()
            post(housekeeping.taskBody(inactive, roomId = other)).problem(422, "CLEANING_TYPE_INACTIVE")
            countTasksOf(room) shouldBe 0
            countTasksOf(other) shouldBe 0
        }

    @Test
    fun `both locations or none are 400`(): Unit =
        with(iam) {
            val type = housekeeping.createCleaningType()
            val room = housekeeping.createRoom()

            post(housekeeping.taskBody(type, roomId = room, areaId = rooms.createArea()))
                .problem(400, "HOUSEKEEPING_TASK_LOCATION_INVALID")
            post(housekeeping.taskBody(type)).problem(400, "HOUSEKEEPING_TASK_LOCATION_INVALID")
            countTasksOf(room) shouldBe 0
        }

    @Test
    fun `a repeated request on the same location, date and type is 409, even with the first one cancelled`(): Unit =
        with(iam) {
            val type = housekeeping.createCleaningType()
            val room = housekeeping.createRoom()
            val today = hotel.currentBusinessDate()
            val first = housekeeping.requestTask(housekeeping.taskBody(type, roomId = room))

            post(housekeeping.taskBody(type, roomId = room, date = "$today"))
                .problem(409, "HOUSEKEEPING_TASK_ALREADY_EXISTS")
            owner
                .sql("UPDATE housekeeping.housekeeping_task SET status = 'CANCELLED' WHERE id = :id")
                .param("id", UUID.fromString(first["id"].asString()))
                .update()
            post(housekeeping.taskBody(type, roomId = room)).problem(409, "HOUSEKEEPING_TASK_ALREADY_EXISTS")
            countTasksOf(room) shouldBe 1

            // Outro tipo, outra data ou outro local no mesmo dia são outra tarefa.
            housekeeping.requestTask(housekeeping.taskBody(housekeeping.createCleaningType(), roomId = room))
            housekeeping.requestTask(housekeeping.taskBody(type, roomId = room, date = "${today.plusDays(1)}"))
            val area = rooms.createArea()
            housekeeping.requestTask(housekeeping.taskBody(type, areaId = area))
            post(housekeeping.taskBody(type, areaId = area)).problem(409, "HOUSEKEEPING_TASK_ALREADY_EXISTS")
            countTasksOf(room) shouldBe 3
        }

    @Test
    fun `notes over 500 characters or with NUL are 400 on the field, and 500 are accepted`(): Unit =
        with(iam) {
            val type = housekeeping.createCleaningType()
            val room = housekeeping.createRoom()

            listOf("a".repeat(501), "Repor\u0000toalhas").forEach { notes ->
                withClue(notes.length) {
                    val problem = post(housekeeping.taskBody(type, roomId = room, notes = notes)).problem400()
                    problem.field() shouldBe "notes"
                    problem.toString().contains("Repor") shouldBe false
                }
            }
            countTasksOf(room) shouldBe 0
            housekeeping
                .requestTask(housekeeping.taskBody(type, roomId = room, notes = "b".repeat(500)))["notes"]
                .asString()
                .length shouldBe 500
        }

    @Test
    fun `the notes limit is 500 characters, counted as code points, also outside the BMP`(): Unit =
        with(iam) {
            val type = housekeeping.createCleaningType()
            val room = housekeeping.createRoom()
            val emoji = "😀"

            // 501 caracteres fora do plano básico: 400 no campo, nada gravado.
            post(housekeeping.taskBody(type, roomId = room, notes = emoji.repeat(501))).problem400().field() shouldBe
                "notes"
            countTasksOf(room) shouldBe 0
            // 500 (1000 unidades UTF-16) cabem no varchar(500) e são aceitos.
            housekeeping
                .requestTask(housekeeping.taskBody(type, roomId = room, notes = emoji.repeat(500)))["notes"]
                .asString() shouldBe emoji.repeat(500)
            // Espaços nas pontas não contam: o limite vale depois de aparar.
            housekeeping
                .requestTask(
                    housekeeping.taskBody(
                        housekeeping.createCleaningType(),
                        roomId = room,
                        notes = "  " + "c".repeat(500) + "  ",
                    ),
                )["notes"]
                .asString() shouldBe "c".repeat(500)
        }

    @Test
    fun `a list date outside the storable range is 400 INVALID_FORMAT, never 500`(): Unit =
        with(iam) {
            // Uma tarefa gravada em -infinity (o que o driver grava para uma data abaixo do piso) num local só dela.
            val room = UUID.randomUUID()
            owner
                .sql(
                    """
                    INSERT INTO housekeeping.housekeeping_task (id, room_id, area_id, business_date, cleaning_type_id,
                        source, status, priority, credits, arrival_expected, scheduled_cleaning_id, notes, created_by,
                        created_at, updated_at, version)
                    VALUES (:id, :room, NULL, '-infinity', :type, 'AD_HOC', 'PENDING', 3, 0, FALSE, NULL, NULL, NULL,
                        now(), now(), 0)
                    """.trimIndent(),
                ).param("id", UUID.randomUUID())
                .param("room", room)
                .param("type", housekeeping.createCleaningType())
                .update()

            // A conversão do parâmetro recusa o que está fora do StorableRange (M6 E20), antes de qualquer consulta.
            listOf("-5000-01-01", "-4713-12-31", "+5874898-01-01", "+999999999-12-31").forEach { date ->
                withClue(date) {
                    listOf("$TASKS?date=$date&roomId=$room", "$TASKS?date=$date").forEach { path ->
                        val response = call(GET, path, asAdmin())
                        response.status shouldBe 400
                        json(response)["errors"][0]["field"].asString() shouldBe "date"
                        json(response)["errors"][0]["code"].asString() shouldBe "INVALID_FORMAT"
                    }
                }
            }
        }

    @Test
    fun `a date before the business date or more than 30 days after it is 422`(): Unit =
        with(iam) {
            val type = housekeeping.createCleaningType()
            val room = housekeeping.createRoom()
            val today = hotel.currentBusinessDate()

            listOf(today.minusDays(1), today.plusDays(31), today.plusYears(5000), today.minusYears(3000)).forEach {
                withClue(it) {
                    post(housekeeping.taskBody(type, roomId = room, date = "$it"))
                        .problem(422, "HOUSEKEEPING_TASK_DATE_INVALID")
                }
            }
            // Fora do StorableRange, a leitura do corpo recusa antes da regra de negócio (M6 E20).
            post(housekeeping.taskBody(type, roomId = room, date = "-5000-01-01"))
                .problem(400, "VALIDATION_FAILED")
            countTasksOf(room) shouldBe 0
        }

    @Test
    fun `a tampered cursor is 400 INVALID_CURSOR, never 500`(): Unit =
        with(iam) {
            val id = UUID.randomUUID().toString()
            val cursors =
                listOf(
                    PageCursor.of("yes", "3", id),
                    PageCursor.of("TRUE", "3", id),
                    PageCursor.of("", "3", id),
                    PageCursor.of("true", "x", id),
                    PageCursor.of("true", "99999999999", id),
                    PageCursor.of("true", "-1", id),
                    PageCursor.of("true", "6", id),
                    PageCursor.of("true", "0", id),
                    PageCursor.of("true", "3", "not-a-uuid"),
                    PageCursor.of("true", "3"),
                    PageCursor.of("false\u0000", "3", id),
                )
            cursors.forEach { cursor ->
                withClue(cursor) {
                    call(GET, "$TASKS?cursor=${cursor.encode()}", asAdmin()).problem(400, "INVALID_CURSOR")
                }
            }
            // Uma chave válida é aceita, mesmo sem item depois dela.
            call(GET, "$TASKS?cursor=${PageCursor.of("false", "1", id).encode()}", asAdmin()).ok()
        }

    @Test
    fun `an unknown task is 404`(): Unit =
        with(iam) {
            call(GET, "$TASKS/${UUID.randomUUID()}", asAdmin()).problem(404, "HOUSEKEEPING_TASK_NOT_FOUND")
        }

    private fun post(body: Map<String, Any?>): MockHttpServletResponse = iam.call(POST, TASKS, iam.asAdmin(), body)

    private fun MockHttpServletResponse.problem400() =
        with(iam) {
            withClue(getContentAsString(Charsets.UTF_8)) { status shouldBe 400 }
            json(this@problem400)
        }

    private fun JsonNode.field(): String = this["errors"][0]["field"].asString()

    private fun countTasksOf(room: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM housekeeping.housekeeping_task WHERE room_id = :room")
            .param("room", room)
            .query(Int::class.java)
            .single()
}
