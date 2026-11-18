package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.CLEANING_TYPES
import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.SCHEDULED_CLEANINGS
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * As recusas da faxina programada (M6 E01, Task 3): referência inexistente `400 …UNKNOWN_…` (H16), local inválido,
 * local e tipo inativos, `lastDoneOn` no futuro ou abaixo do piso do banco (Review Focus 5), a segunda faxina ativa no
 * mesmo (local, tipo) pelo índice único parcial e a frequência fora da faixa. Nada é gravado em nenhuma recusa.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class ScheduledCleaningsAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
) {
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)
    private val rooms = housekeeping.rooms

    @Test
    fun `an unknown location or cleaning type is 400 with the field`(): Unit =
        with(iam) {
            val type = housekeeping.createCleaningType()
            val missing = UUID.randomUUID()

            post(housekeeping.scheduleBody(type, roomId = missing))
                .problem(400, "SCHEDULED_CLEANING_UNKNOWN_LOCATION")
                .fields() shouldBe listOf("roomId")
            post(housekeeping.scheduleBody(type, areaId = missing))
                .problem(400, "SCHEDULED_CLEANING_UNKNOWN_LOCATION")
                .fields() shouldBe listOf("areaId")
            // Uma UH não vale como área, nem o contrário.
            post(housekeeping.scheduleBody(type, areaId = housekeeping.createRoom()))
                .problem(400, "SCHEDULED_CLEANING_UNKNOWN_LOCATION")
            post(housekeeping.scheduleBody(type, roomId = rooms.createArea()))
                .problem(400, "SCHEDULED_CLEANING_UNKNOWN_LOCATION")
            post(housekeeping.scheduleBody(missing, roomId = housekeeping.createRoom()))
                .problem(400, "SCHEDULED_CLEANING_UNKNOWN_CLEANING_TYPE")
                .fields() shouldBe listOf("cleaningTypeId")
            countSchedulesOf(type) shouldBe 0
        }

    @Test
    fun `both locations or none are 400`(): Unit =
        with(iam) {
            val type = housekeeping.createCleaningType()

            post(housekeeping.scheduleBody(type, roomId = housekeeping.createRoom(), areaId = rooms.createArea()))
                .problem(400, "SCHEDULED_CLEANING_LOCATION_INVALID")
            post(housekeeping.scheduleBody(type)).problem(400, "SCHEDULED_CLEANING_LOCATION_INVALID")
            countSchedulesOf(type) shouldBe 0
        }

    @Test
    fun `an inactive location or an inactive type is 422`(): Unit =
        with(iam) {
            val type = housekeeping.createCleaningType()
            val room = housekeeping.createRoom()
            call(POST, "/api/v1/rooms/$room/deactivate", asAdmin()).ok()
            val area = rooms.createArea()
            call(POST, "/api/v1/areas/$area/deactivate", asAdmin()).ok()

            post(housekeeping.scheduleBody(type, roomId = room)).problem(422, "SCHEDULED_CLEANING_LOCATION_INACTIVE")
            post(housekeeping.scheduleBody(type, areaId = area)).problem(422, "SCHEDULED_CLEANING_LOCATION_INACTIVE")

            val inactive = housekeeping.createCleaningType()
            call(POST, "$CLEANING_TYPES/$inactive/deactivate", asAdmin()).ok()
            post(housekeeping.scheduleBody(inactive, roomId = housekeeping.createRoom()))
                .problem(422, "CLEANING_TYPE_INACTIVE")
            countSchedulesOf(type) shouldBe 0
            countSchedulesOf(inactive) shouldBe 0
        }

    @Test
    fun `a last date after the business date is 422 and the business date itself is accepted`(): Unit =
        with(iam) {
            val type = housekeeping.createCleaningType()
            val today = hotel.currentBusinessDate()

            val tomorrow = "${today.plusDays(1)}"
            post(housekeeping.scheduleBody(type, roomId = housekeeping.createRoom(), lastDoneOn = tomorrow))
                .problem(422, "SCHEDULED_CLEANING_LAST_DONE_IN_FUTURE")
            countSchedulesOf(type) shouldBe 0
            post(housekeeping.scheduleBody(type, roomId = housekeeping.createRoom(), lastDoneOn = "$today"))
                .expectStatus(201)
        }

    @Test
    fun `a last date below the storable range is 400 INVALID_FORMAT on the field, and the floor is valid`(): Unit =
        with(iam) {
            val type = housekeeping.createCleaningType()

            listOf("-4713-12-31", "-10000-01-01").forEach { date ->
                withClue(date) {
                    val problem =
                        post(housekeeping.scheduleBody(type, roomId = housekeeping.createRoom(), lastDoneOn = date))
                            .problem(400, "VALIDATION_FAILED")
                    problem.fields() shouldBe listOf("lastDoneOn")
                    problem["errors"][0]["code"].asString() shouldBe "INVALID_FORMAT"
                }
            }
            countSchedulesOf(type) shouldBe 0

            try {
                val floor =
                    post(
                        housekeeping.scheduleBody(type, roomId = housekeeping.createRoom(), lastDoneOn = "-4712-01-01"),
                    ).expectStatus(201)!!
                floor["lastDoneOn"].asString() shouldBe "-4712-01-01"
                floor["nextDueOn"].asString() shouldBe "-4712-01-08"
            } finally {
                // A faxina vencida em -4712-01-08 não fica ativa para os outros testes (a E02 varre as vencidas).
                jdbc
                    .sql("UPDATE housekeeping.scheduled_cleaning SET active = false WHERE cleaning_type_id = :type")
                    .param("type", type)
                    .update()
            }
        }

    @Test
    fun `a second active schedule on the same location and type is 409, but an inactive one does not count`(): Unit =
        with(iam) {
            val type = housekeeping.createCleaningType()
            val room = housekeeping.createRoom()
            val first = housekeeping.createSchedule(housekeeping.scheduleBody(type, roomId = room))

            post(housekeeping.scheduleBody(type, roomId = room, frequencyDays = 30))
                .problem(409, "SCHEDULED_CLEANING_ALREADY_EXISTS")
            countSchedulesOf(type) shouldBe 1

            // Outro tipo no mesmo local vale; trocar para o tipo já usado no local, não.
            val other = housekeeping.createCleaningType()
            val second = housekeeping.createSchedule(housekeeping.scheduleBody(other, roomId = room))
            call(PUT, "$SCHEDULED_CLEANINGS/$second", asAdmin(), edit(type), ifMatch = "\"0\"")
                .problem(409, "SCHEDULED_CLEANING_ALREADY_EXISTS")

            // Com a primeira desativada, a nova é aceita, e a reativação da primeira vira 409.
            call(POST, "$SCHEDULED_CLEANINGS/$first/deactivate", asAdmin()).ok()
            val third = housekeeping.createSchedule(housekeeping.scheduleBody(type, roomId = room))
            call(POST, "$SCHEDULED_CLEANINGS/$first/reactivate", asAdmin())
                .problem(409, "SCHEDULED_CLEANING_ALREADY_EXISTS")
            call(GET, "$SCHEDULED_CLEANINGS/$first", asAdmin()).ok()["active"].asBoolean() shouldBe false
            call(GET, "$SCHEDULED_CLEANINGS/$third", asAdmin()).ok()["active"].asBoolean() shouldBe true

            // O mesmo vale para a área.
            val area = rooms.createArea()
            housekeeping.createSchedule(housekeeping.scheduleBody(type, areaId = area))
            post(housekeeping.scheduleBody(type, areaId = area)).problem(409, "SCHEDULED_CLEANING_ALREADY_EXISTS")
        }

    @Test
    fun `a frequency out of 1 to 365 or missing is 400 on the field`(): Unit =
        with(iam) {
            val type = housekeeping.createCleaningType()
            listOf(0, -1, 366, null).forEach { frequency ->
                withClue(frequency) {
                    post(housekeeping.scheduleBody(type, roomId = housekeeping.createRoom(), frequencyDays = frequency))
                        .problem(400, "VALIDATION_FAILED")
                        .fields() shouldContain "frequencyDays"
                }
            }
            val id = housekeeping.createSchedule(housekeeping.scheduleBody(type, roomId = housekeeping.createRoom()))
            call(PUT, "$SCHEDULED_CLEANINGS/$id", asAdmin(), edit(type, 366), ifMatch = "\"0\"")
                .problem(400, "VALIDATION_FAILED")
                .fields() shouldContain "frequencyDays"
            countSchedulesOf(type) shouldBe 1
            post(housekeeping.scheduleBody(type, roomId = housekeeping.createRoom(), frequencyDays = 365))
                .expectStatus(201)
        }

    @Test
    fun `an unknown schedule is 404 and a PUT to an unknown type is 400`(): Unit =
        with(iam) {
            val missing = UUID.randomUUID()
            call(GET, "$SCHEDULED_CLEANINGS/$missing", asAdmin()).problem(404, "SCHEDULED_CLEANING_NOT_FOUND")
            call(PUT, "$SCHEDULED_CLEANINGS/$missing", asAdmin(), edit(missing), ifMatch = "\"0\"")
                .problem(404, "SCHEDULED_CLEANING_NOT_FOUND")
            call(POST, "$SCHEDULED_CLEANINGS/$missing/deactivate", asAdmin())
                .problem(404, "SCHEDULED_CLEANING_NOT_FOUND")
            call(POST, "$SCHEDULED_CLEANINGS/$missing/reactivate", asAdmin())
                .problem(404, "SCHEDULED_CLEANING_NOT_FOUND")

            val type = housekeeping.createCleaningType()
            val id = housekeeping.createSchedule(housekeeping.scheduleBody(type, roomId = housekeeping.createRoom()))
            call(PUT, "$SCHEDULED_CLEANINGS/$id", asAdmin(), edit(missing), ifMatch = "\"0\"")
                .problem(400, "SCHEDULED_CLEANING_UNKNOWN_CLEANING_TYPE")
            call(GET, "$SCHEDULED_CLEANINGS/$id", asAdmin()).ok()["cleaningTypeId"].asString() shouldBe type.toString()
        }

    private fun edit(
        type: UUID,
        frequencyDays: Int = 7,
    ) = mapOf("cleaningTypeId" to type, "frequencyDays" to frequencyDays)

    private fun post(body: Map<String, Any?>) = iam.call(POST, SCHEDULED_CLEANINGS, iam.asAdmin(), body)

    private fun JsonNode.fields(): List<String> = this["errors"].values().map { it["field"].asString() }

    private fun countSchedulesOf(type: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM housekeeping.scheduled_cleaning WHERE cleaning_type_id = :type")
            .param("type", type)
            .query(Int::class.java)
            .single()
}
