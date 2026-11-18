package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.CLEANING_TYPES
import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.SCHEDULED_CLEANINGS
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.util.UUID

/**
 * Faxinas programadas pela API (M6 E01, Task 3; RF-GOV-10): criação por UH ou área com a próxima data da D7, leitura
 * com `ETag`, edição com `If-Match` (só tipo e frequência), desativação e reativação (que mantém a próxima data antiga
 * e confere tipo e local ativos, Review Focus 5), listagem por cursor de (`nextDueOn`, `id`) com o filtro `active` e
 * a desativação do tipo recusada enquanto houver faxina ativa nele.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class ScheduledCleaningsApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
) {
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)
    private val rooms = housekeeping.rooms

    @Test
    fun `a schedule is created for a room with Location and ETag, and the next date is the last plus the frequency`() {
        val type = housekeeping.createCleaningType()
        val room = housekeeping.createRoom()
        val lastDone = hotel.currentBusinessDate().minusDays(3)

        val created =
            iam.call(
                POST,
                SCHEDULED_CLEANINGS,
                iam.asAdmin(),
                housekeeping.scheduleBody(type, roomId = room, frequencyDays = 10, lastDoneOn = lastDone.toString()),
            )
        val body = with(iam) { created.expectStatus(201) }.shouldNotBeNull()
        val id = UUID.fromString(body["id"].asString())
        created.getHeader(HttpHeaders.LOCATION) shouldBe "$SCHEDULED_CLEANINGS/$id"
        created.getHeader(HttpHeaders.ETAG) shouldBe "\"0\""
        body["roomId"].asString() shouldBe room.toString()
        body["areaId"].isNull shouldBe true
        body["cleaningTypeId"].asString() shouldBe type.toString()
        body["frequencyDays"].asInt() shouldBe 10
        body["lastDoneOn"].asString() shouldBe lastDone.toString()
        body["nextDueOn"].asString() shouldBe lastDone.plusDays(10).toString()
        body["active"].asBoolean() shouldBe true

        val read = iam.call(GET, "$SCHEDULED_CLEANINGS/$id", iam.asAdmin())
        read.getHeader(HttpHeaders.ETAG) shouldBe "\"0\""
        with(iam) { read.ok() }["nextDueOn"].asString() shouldBe lastDone.plusDays(10).toString()
    }

    @Test
    fun `a schedule for an area without the last date is due on the business date`() {
        val type = housekeeping.createCleaningType()
        val area = rooms.createArea()

        val body =
            with(iam) {
                call(POST, SCHEDULED_CLEANINGS, asAdmin(), housekeeping.scheduleBody(type, areaId = area))
                    .expectStatus(201)
            }.shouldNotBeNull()

        body["areaId"].asString() shouldBe area.toString()
        body["roomId"].isNull shouldBe true
        body["lastDoneOn"].isNull shouldBe true
        body["nextDueOn"].asString() shouldBe hotel.currentBusinessDate().toString()
    }

    @Test
    fun `PUT with If-Match changes only the type and the frequency and recalculates the next date`(): Unit =
        with(iam) {
            val type = housekeeping.createCleaningType()
            val other = housekeeping.createCleaningType()
            val room = housekeeping.createRoom()
            val lastDone = hotel.currentBusinessDate().minusDays(1)
            val id =
                housekeeping.createSchedule(
                    housekeeping.scheduleBody(type, roomId = room, frequencyDays = 7, lastDoneOn = lastDone.toString()),
                )
            val path = "$SCHEDULED_CLEANINGS/$id"
            val edit = mapOf("cleaningTypeId" to other, "frequencyDays" to 14)

            call(PUT, path, asAdmin(), edit).problem(428, "PRECONDITION_REQUIRED")
            call(PUT, path, asAdmin(), edit, ifMatch = "\"9\"").problem(412, "PRECONDITION_FAILED")
            val updated = call(PUT, path, asAdmin(), edit, ifMatch = "\"0\"").ok()
            updated["cleaningTypeId"].asString() shouldBe other.toString()
            updated["frequencyDays"].asInt() shouldBe 14
            updated["nextDueOn"].asString() shouldBe lastDone.plusDays(14).toString()
            updated["roomId"].asString() shouldBe room.toString()
            updated["lastDoneOn"].asString() shouldBe lastDone.toString()
            housekeeping.etagOf(path) shouldBe "\"1\""

            // O local e a última faxina no corpo do PUT não mudam nada (só tipo e frequência são editáveis).
            val area = rooms.createArea()
            val withExtras = edit + mapOf("frequencyDays" to 3, "areaId" to area, "lastDoneOn" to "2000-01-01")
            val again = call(PUT, path, asAdmin(), withExtras, ifMatch = "\"1\"").ok()
            again["roomId"].asString() shouldBe room.toString()
            again["areaId"].isNull shouldBe true
            again["lastDoneOn"].asString() shouldBe lastDone.toString()
            again["nextDueOn"].asString() shouldBe lastDone.plusDays(3).toString()
        }

    @Test
    fun `deactivate and reactivate work, and the reactivated schedule keeps the old next date`(): Unit =
        with(iam) {
            val lastDone = hotel.currentBusinessDate().minusDays(30)
            val id =
                housekeeping.createSchedule(
                    housekeeping.scheduleBody(
                        housekeeping.createCleaningType(),
                        roomId = housekeeping.createRoom(),
                        frequencyDays = 5,
                        lastDoneOn = lastDone.toString(),
                    ),
                )
            val path = "$SCHEDULED_CLEANINGS/$id"

            call(POST, "$path/deactivate", asAdmin()).ok()["active"].asBoolean() shouldBe false
            call(POST, "$path/deactivate", asAdmin()).ok()["active"].asBoolean() shouldBe false
            val reactivated = call(POST, "$path/reactivate", asAdmin()).ok()
            reactivated["active"].asBoolean() shouldBe true
            // Vencida há 25 dias: a reativação não recalcula (a E02 decide).
            reactivated["nextDueOn"].asString() shouldBe lastDone.plusDays(5).toString()
            call(POST, "$path/reactivate", asAdmin()).ok()["active"].asBoolean() shouldBe true
        }

    @Test
    fun `reactivation with an inactive type or an inactive location is refused`(): Unit =
        with(iam) {
            val type = housekeeping.createCleaningType()
            val room = housekeeping.createRoom()
            val id = housekeeping.createSchedule(housekeeping.scheduleBody(type, roomId = room))
            val path = "$SCHEDULED_CLEANINGS/$id"
            call(POST, "$path/deactivate", asAdmin()).ok()

            call(POST, "$CLEANING_TYPES/$type/deactivate", asAdmin()).ok()
            call(POST, "$path/reactivate", asAdmin()).problem(422, "CLEANING_TYPE_INACTIVE")
            call(POST, "$CLEANING_TYPES/$type/reactivate", asAdmin()).ok()

            call(POST, "/api/v1/rooms/$room/deactivate", asAdmin()).ok()
            call(POST, "$path/reactivate", asAdmin()).problem(422, "SCHEDULED_CLEANING_LOCATION_INACTIVE")
            call(GET, path, asAdmin()).ok()["active"].asBoolean() shouldBe false

            val areaType = housekeeping.createCleaningType()
            val area = rooms.createArea()
            val areaSchedule = housekeeping.createSchedule(housekeeping.scheduleBody(areaType, areaId = area))
            call(POST, "$SCHEDULED_CLEANINGS/$areaSchedule/deactivate", asAdmin()).ok()
            call(POST, "/api/v1/areas/$area/deactivate", asAdmin()).ok()
            call(POST, "$SCHEDULED_CLEANINGS/$areaSchedule/reactivate", asAdmin())
                .problem(422, "SCHEDULED_CLEANING_LOCATION_INACTIVE")
        }

    @Test
    fun `a type with an active schedule is not deactivated until the schedule is`(): Unit =
        with(iam) {
            val type = housekeeping.createCleaningType()
            val id = housekeeping.createSchedule(housekeeping.scheduleBody(type, roomId = housekeeping.createRoom()))

            call(POST, "$CLEANING_TYPES/$type/deactivate", asAdmin()).problem(422, "CLEANING_TYPE_HAS_ACTIVE_SCHEDULES")
            call(GET, "$CLEANING_TYPES/$type", asAdmin()).ok()["active"].asBoolean() shouldBe true

            call(POST, "$SCHEDULED_CLEANINGS/$id/deactivate", asAdmin()).ok()
            call(POST, "$CLEANING_TYPES/$type/deactivate", asAdmin()).ok()["active"].asBoolean() shouldBe false
        }

    @Test
    fun `the PUT that changes the type requires an active type, even on an inactive schedule`(): Unit =
        with(iam) {
            val type = housekeeping.createCleaningType()
            val inactive = housekeeping.createCleaningType()
            call(POST, "$CLEANING_TYPES/$inactive/deactivate", asAdmin()).ok()
            val id = housekeeping.createSchedule(housekeeping.scheduleBody(type, roomId = housekeeping.createRoom()))
            val path = "$SCHEDULED_CLEANINGS/$id"
            call(POST, "$path/deactivate", asAdmin()).ok()

            call(PUT, path, asAdmin(), mapOf("cleaningTypeId" to inactive, "frequencyDays" to 7), ifMatch = "\"1\"")
                .problem(422, "CLEANING_TYPE_INACTIVE")
            call(GET, path, asAdmin()).ok()["cleaningTypeId"].asString() shouldBe type.toString()
        }

    @Test
    fun `the list pages by next date and id, with the active filter`(): Unit =
        with(iam) {
            val type = housekeeping.createCleaningType()
            val today = hotel.currentBusinessDate()
            val ids =
                (1..3).map { offset ->
                    housekeeping.createSchedule(
                        housekeeping.scheduleBody(
                            type,
                            roomId = housekeeping.createRoom(),
                            frequencyDays = 1,
                            lastDoneOn = today.minusDays(offset.toLong()).toString(),
                        ),
                    )
                }
            call(POST, "$SCHEDULED_CLEANINGS/${ids[1]}/deactivate", asAdmin()).ok()

            val firstPage = call(GET, "$SCHEDULED_CLEANINGS?limit=1", asAdmin()).ok()
            firstPage["items"].size() shouldBe 1
            firstPage["nextCursor"].isNull shouldBe false

            val all = housekeeping.listAll(SCHEDULED_CLEANINGS)
            all.map(::orderKey) shouldBe all.map(::orderKey).sortedWith(compareBy({ it.first }, { it.second }))
            all.map { UUID.fromString(it["id"].asString()) }.let { listed -> ids.forEach { listed shouldContain it } }

            val active = housekeeping.listAll("$SCHEDULED_CLEANINGS?active=true").map { it["id"].asString() }
            val inactive = housekeeping.listAll("$SCHEDULED_CLEANINGS?active=false").map { it["id"].asString() }
            withClue("filtro active") {
                active shouldContain ids[0].toString()
                active shouldContain ids[2].toString()
                active shouldNotContain ids[1].toString()
                inactive shouldContain ids[1].toString()
                inactive shouldNotContain ids[0].toString()
            }
        }

    /** A chave da ordenação; o texto do UUID em minúsculas ordena como o `uuid` do PostgreSQL (byte a byte). */
    private fun orderKey(item: JsonNode): Pair<LocalDate, String> =
        LocalDate.parse(item["nextDueOn"].asString()) to item["id"].asString()
}
