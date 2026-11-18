package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.TASKS
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.util.UUID

/**
 * Tarefas de governança pela API (M6 E02, Task 1; RF-GOV-02): a solicitação avulsa da recepção (`PENDING`, `AD_HOC`,
 * com a prioridade e os créditos do tipo, D2), a leitura com `ETag` e a lista do dia por cursor de (`arrivalExpected`
 * desc, `priority` desc, `id`) com os filtros. O banco é compartilhado: cada teste afirma só sobre as tarefas das UHs e
 * áreas que criou.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class HousekeepingTasksApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
) {
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)
    private val rooms = housekeeping.rooms

    @Test
    fun `an ad hoc request for a room is PENDING, AD_HOC, with the type priority and credits, Location and ETag`() {
        val roomType = rooms.createRoomType()
        val room = rooms.createRoom(roomType)
        val type =
            housekeeping.createCleaningType(
                housekeeping.cleaningTypeBody(defaultPriority = 2, credits = listOf(housekeeping.credit(roomType, 7))),
            )

        val created =
            iam.call(
                POST,
                TASKS,
                iam.asAdmin(),
                housekeeping.taskBody(type, roomId = room, notes = "  Repor toalhas  "),
            )
        val body = with(iam) { created.expectStatus(201) }.shouldNotBeNull()
        val id = UUID.fromString(body["id"].asString())
        created.getHeader(HttpHeaders.LOCATION) shouldBe "$TASKS/$id"
        created.getHeader(HttpHeaders.ETAG) shouldBe "\"0\""
        body["roomId"].asString() shouldBe room.toString()
        body["areaId"].isNull shouldBe true
        body["date"].asString() shouldBe hotel.currentBusinessDate().toString()
        body["cleaningTypeId"].asString() shouldBe type.toString()
        body["source"].asString() shouldBe "AD_HOC"
        body["status"].asString() shouldBe "PENDING"
        body["priority"].asInt() shouldBe 2
        body["credits"].asInt() shouldBe 7
        body["arrivalExpected"].asBoolean() shouldBe false
        body["scheduledCleaningId"].isNull shouldBe true
        body["notes"].asString() shouldBe "Repor toalhas"

        val read = iam.call(GET, "$TASKS/$id", iam.asAdmin())
        read.getHeader(HttpHeaders.ETAG) shouldBe "\"0\""
        with(iam) { read.ok() } shouldBe body
    }

    @Test
    fun `an area has no credits, a room type without credit gives 0, and the date goes up to 30 days ahead`() {
        val roomType = rooms.createRoomType()
        val type =
            housekeeping.createCleaningType(
                housekeeping.cleaningTypeBody(credits = listOf(housekeeping.credit(roomType, 9))),
            )
        val today = hotel.currentBusinessDate()

        val area = rooms.createArea()
        val areaTask =
            housekeeping.requestTask(
                housekeeping.taskBody(type, areaId = area, date = "${today.plusDays(30)}"),
            )
        areaTask["areaId"].asString() shouldBe area.toString()
        areaTask["roomId"].isNull shouldBe true
        areaTask["credits"].asInt() shouldBe 0
        areaTask["date"].asString() shouldBe today.plusDays(30).toString()
        areaTask["notes"].isNull shouldBe true

        val otherRoom = housekeeping.createRoom()
        val roomTask = housekeeping.requestTask(housekeeping.taskBody(type, roomId = otherRoom, date = "$today"))
        roomTask["credits"].asInt() shouldBe 0
        roomTask["date"].asString() shouldBe today.toString()
    }

    @Test
    fun `the list of the day is ordered by arrival, priority and id, with the filters`() {
        val room = housekeeping.createRoom()
        val area = rooms.createArea()
        val high = housekeeping.createCleaningType(housekeeping.cleaningTypeBody(defaultPriority = 5))
        val low = housekeeping.createCleaningType(housekeeping.cleaningTypeBody(defaultPriority = 1))
        val middle = housekeeping.createCleaningType(housekeeping.cleaningTypeBody(defaultPriority = 3))
        val date = hotel.currentBusinessDate().plusDays(1)
        val lowTask = request(low, room, date)
        val highTask = request(high, room, date)
        val middleTask = request(middle, room, date)
        val areaTask = id(housekeeping.requestTask(housekeeping.taskBody(middle, areaId = area, date = "$date")))
        // Uma tarefa com chegada prevista (a geração da Task 2 a marca), de prioridade mais baixa, vem antes de todas.
        val arrival = insertArrivalTask(room, date, priority = 1)

        ids("$TASKS?date=$date&roomId=$room") shouldContainExactly
            listOf(arrival, highTask, middleTask, lowTask)
        ids("$TASKS?date=$date&areaId=$area") shouldContainExactly listOf(areaTask)
        ids("$TASKS?date=$date&roomId=$room&cleaningTypeId=$middle") shouldContainExactly listOf(middleTask)
        ids("$TASKS?date=$date&roomId=$room&status=PENDING") shouldContainExactly
            listOf(arrival, highTask, middleTask, lowTask)
        ids("$TASKS?date=$date&roomId=$room&status=DONE").shouldBeEmpty()
        ids("$TASKS?date=$date&roomId=$room&source=AD_HOC") shouldContainExactly listOf(highTask, middleTask, lowTask)
        ids("$TASKS?date=$date&roomId=$room&source=PRE_ARRIVAL") shouldContainExactly listOf(arrival)
        ids("$TASKS?date=${date.plusDays(1)}&roomId=$room").shouldBeEmpty()

        // Na lista do dia inteira, as minhas aparecem, na mesma ordem relativa.
        val all = ids("$TASKS?date=$date")
        all.filter { it in setOf(arrival, highTask, middleTask, lowTask) } shouldContainExactly
            listOf(arrival, highTask, middleTask, lowTask)
    }

    @Test
    fun `without date the list is of the business date`() {
        val room = housekeeping.createRoom()
        val task = request(housekeeping.createCleaningType(), room, hotel.currentBusinessDate())

        ids("$TASKS?roomId=$room") shouldContainExactly listOf(task)
    }

    @Test
    fun `the pages go through every item once, without repeating`() {
        val room = housekeeping.createRoom()
        val date = hotel.currentBusinessDate()
        val tasks =
            (1..5).map { priority ->
                request(
                    housekeeping.createCleaningType(housekeeping.cleaningTypeBody(defaultPriority = priority)),
                    room,
                    date,
                )
            }
        insertArrivalTask(room, date, priority = 3).let { arrival ->
            val paged = ids("$TASKS?date=$date&roomId=$room&limit=1")
            paged shouldContainExactly listOf(arrival) + tasks.reversed()
            paged.toSet().size shouldBe paged.size
        }
        val two = ids("$TASKS?date=$date&roomId=$room&limit=2")
        two shouldContainExactlyInAnyOrder ids("$TASKS?date=$date&roomId=$room&limit=200")
    }

    private fun request(
        type: UUID,
        room: UUID,
        date: LocalDate,
    ): UUID = id(housekeeping.requestTask(housekeeping.taskBody(type, roomId = room, date = "$date")))

    private fun id(node: JsonNode): UUID = UUID.fromString(node["id"].asString())

    private fun ids(path: String): List<UUID> = housekeeping.listAll(path).map { id(it) }

    /** Uma tarefa de pré-chegada com a chegada prevista, gravada direto (a geração é da Task 2). */
    private fun insertArrivalTask(
        room: UUID,
        date: LocalDate,
        priority: Int,
    ): UUID {
        val id = UuidV7.generate()
        jdbc
            .sql(
                """
                INSERT INTO housekeeping.housekeeping_task (id, room_id, area_id, business_date, cleaning_type_id,
                    source, status, priority, credits, arrival_expected, scheduled_cleaning_id, notes, created_by,
                    created_at, updated_at, version)
                VALUES (:id, :room, NULL, :date, :type, 'PRE_ARRIVAL', 'PENDING', :priority, 0, TRUE, NULL, NULL, NULL,
                    now(), now(), 0)
                """.trimIndent(),
            ).param("id", id)
            .param("room", room)
            .param("date", date)
            .param("type", PRE_ARRIVAL_TYPE)
            .param("priority", priority)
            .update()
        return id
    }

    private companion object {
        /** O tipo `PRE_ARRIVAL` da carga (D5), só lido. */
        val PRE_ARRIVAL_TYPE: UUID = UUID.fromString("01a1e8f2-f904-7004-8000-000000000005")
    }
}
