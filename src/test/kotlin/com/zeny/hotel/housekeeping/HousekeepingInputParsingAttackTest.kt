package com.zeny.hotel.housekeeping

import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.CLEANING_TYPES
import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.SCHEDULED_CLEANINGS
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * Entrada externa malformada no `housekeeping` (M6 E01; risco de parsing): datas fora do formato estrito da E18,
 * números e identificadores com o tipo errado e coleções com a forma errada. Toda recusa é `4xx` (nunca `500`), e nada
 * é gravado. Número fracionário num campo inteiro (`7.5`, `1.5`) e `""` num UUID opcional (`checklistTemplateId`) são
 * recusados com `INVALID_FORMAT` no campo desde a M6 E20; o `StrictDateTimeJsonApiTest` cobre esses casos.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class HousekeepingInputParsingAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
) {
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)

    @Test
    fun `a malformed last date is 400 on the field, and nothing is stored`(): Unit =
        with(iam) {
            val type = housekeeping.createCleaningType()
            listOf<Any>("", "2026-02-30", "17/11/2026", "2026-11-17T00:00:00Z", 20261117, true).forEach { date ->
                withClue("lastDoneOn = $date") {
                    val problem =
                        post(
                            housekeeping.scheduleBody(
                                type,
                                roomId = housekeeping.createRoom(),
                            ) + ("lastDoneOn" to date),
                        )
                    withClue(problem.getContentAsString(Charsets.UTF_8)) { problem.status shouldBe 400 }
                    json(problem)["errors"].values().map { it["field"].asString() } shouldBe listOf("lastDoneOn")
                }
            }
            countSchedulesOf(type) shouldBe 0
        }

    @Test
    fun `a last date far beyond the business date is refused as 400 or 422, never 500, and nothing is stored`(): Unit =
        with(iam) {
            val type = housekeeping.createCleaningType()
            listOf("+999999999-12-31", "9999-12-31", "+5874897-12-31").forEach { date ->
                withClue("lastDoneOn = $date") {
                    val response =
                        post(housekeeping.scheduleBody(type, roomId = housekeeping.createRoom(), lastDoneOn = date))
                    withClue(
                        response.getContentAsString(Charsets.UTF_8),
                    ) { response.status shouldBeIn listOf(400, 422) }
                }
            }
            countSchedulesOf(type) shouldBe 0
        }

    @Test
    fun `a frequency or a reference of the wrong type is 400, on POST and on PUT`(): Unit =
        with(iam) {
            val type = housekeeping.createCleaningType()
            val bad =
                listOf(
                    housekeeping.scheduleBody(type, roomId = housekeeping.createRoom()) +
                        ("frequencyDays" to 2_147_483_648L),
                    housekeeping.scheduleBody(type) + ("roomId" to "not-a-uuid"),
                    housekeeping.scheduleBody(type, roomId = housekeeping.createRoom()) +
                        ("cleaningTypeId" to listOf(type)),
                )
            bad.forEachIndexed { index, body ->
                withClue("corpo $index") {
                    val response = post(body)
                    withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 400 }
                }
            }
            countSchedulesOf(type) shouldBe 0

            val id = housekeeping.createSchedule(housekeeping.scheduleBody(type, roomId = housekeeping.createRoom()))
            try {
                listOf(
                    mapOf("cleaningTypeId" to "not-a-uuid", "frequencyDays" to 7),
                    mapOf("cleaningTypeId" to type, "frequencyDays" to "sete"),
                    mapOf("cleaningTypeId" to null, "frequencyDays" to 7),
                ).forEachIndexed { index, body ->
                    withClue("PUT $index") {
                        val response = call(PUT, "$SCHEDULED_CLEANINGS/$id", asAdmin(), body, ifMatch = "\"0\"")
                        withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 400 }
                    }
                }
                housekeeping.etagOf("$SCHEDULED_CLEANINGS/$id") shouldBe "\"0\""
            } finally {
                call(POST, "$SCHEDULED_CLEANINGS/$id/deactivate", asAdmin()).ok()
            }
        }

    @Test
    fun `credits, template and numbers with the wrong shape are 400 on the cleaning type, nothing stored`(): Unit =
        with(iam) {
            val roomType = housekeeping.rooms.createRoomType()
            val code = housekeeping.uniqueCode("SH")

            fun body(vararg changes: Pair<String, Any?>) = housekeeping.cleaningTypeBody(code = code) + changes.toMap()
            val bad =
                listOf(
                    body("credits" to mapOf("roomTypeId" to roomType, "credits" to 1)),
                    body("credits" to listOf(mapOf("roomTypeId" to roomType, "credits" to 2_147_483_648L))),
                    body("credits" to listOf(mapOf("roomTypeId" to "not-a-uuid", "credits" to 1))),
                    body("credits" to listOf(mapOf("roomTypeId" to roomType))),
                    body("credits" to listOf(mapOf("credits" to 1))),
                    body("credits" to "nenhum"),
                    body("checklistTemplateId" to "not-a-uuid"),
                    body("requiresInspection" to null),
                    body("code" to null),
                    body("name" to null),
                )
            bad.forEachIndexed { index, invalid ->
                withClue("corpo $index: $invalid") {
                    val response = call(POST, CLEANING_TYPES, asAdmin(), invalid)
                    withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 400 }
                }
            }
            housekeeping.countByCode(code) shouldBe 0

            // A chave (`key`) não vem do corpo: um `key` enviado é ignorado, e o tipo criado não tem chave.
            val created = call(POST, CLEANING_TYPES, asAdmin(), body("key" to "CHECKOUT")).expectStatus(201)!!
            created["key"].isNull shouldBe true
            val id = created["id"].asString()
            call(GET, "$CLEANING_TYPES/$id", asAdmin()).ok()["key"].isNull shouldBe true
        }

    private fun post(body: Map<String, Any?>) = iam.call(POST, SCHEDULED_CLEANINGS, iam.asAdmin(), body)

    private fun countSchedulesOf(type: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM housekeeping.scheduled_cleaning WHERE cleaning_type_id = :type")
            .param("type", type)
            .query(Int::class.java)
            .single()
}
