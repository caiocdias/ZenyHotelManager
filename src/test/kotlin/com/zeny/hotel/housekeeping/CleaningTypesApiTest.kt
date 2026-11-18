package com.zeny.hotel.housekeeping

import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.CLEANING_TYPES
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Tipos de limpeza pela API (M6 E01, Task 1; RF-GOV-01): criação, leitura, edição com `If-Match`, desativação e
 * reativação, listagem por cursor do código, o aviso de crédito faltante (D6, Review Focus 1) e as recusas no corpo,
 * sem nada gravado (H16, Review Focus 2).
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class CleaningTypesApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
) {
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)
    private val rooms = housekeeping.rooms

    @Test
    fun `a cleaning type is created with Location and ETag and read back with ETag`() {
        val roomType = rooms.createRoomType()
        val code = housekeeping.uniqueCode("ck")
        val body =
            housekeeping.cleaningTypeBody(
                code = code,
                name = "  Saída especial  ",
                requiresInspection = true,
                defaultPriority = 4,
                linenChangeEveryNights = 2,
                credits = listOf(housekeeping.credit(roomType, 3)),
            )

        val created = admin(POST, CLEANING_TYPES, body)
        val createdBody = with(iam) { created.expectStatus(201) }.shouldNotBeNull()
        val id = UUID.fromString(createdBody["id"].asString())
        created.getHeader(HttpHeaders.LOCATION) shouldBe "$CLEANING_TYPES/$id"
        created.getHeader(HttpHeaders.ETAG) shouldBe "\"0\""
        createdBody["code"].asString() shouldBe code.uppercase()
        createdBody["name"].asString() shouldBe "Saída especial"
        createdBody["key"].isNull shouldBe true
        createdBody["requiresInspection"].asBoolean() shouldBe true
        createdBody["defaultPriority"].asInt() shouldBe 4
        createdBody["linenChangeEveryNights"].asInt() shouldBe 2
        createdBody["checklistTemplateId"].isNull shouldBe true
        createdBody["active"].asBoolean() shouldBe true
        creditsOf(createdBody) shouldBe mapOf(roomType to 3)

        val read = admin(GET, "$CLEANING_TYPES/$id")
        read.getHeader(HttpHeaders.ETAG) shouldBe "\"0\""
        with(iam) { read.ok() }["code"].asString() shouldBe code.uppercase()
        housekeeping.storedCredits(id) shouldBe mapOf(roomType to 3)
    }

    @Test
    fun `PUT with If-Match changes code, name, credits, inspection, priority and linen, and needs a current version`() {
        val first = rooms.createRoomType()
        val second = rooms.createRoomType()
        val id =
            housekeeping.createCleaningType(
                housekeeping.cleaningTypeBody(credits = listOf(housekeeping.credit(first, 2))),
            )
        val path = "$CLEANING_TYPES/$id"
        val etag = housekeeping.etagOf(path)
        val newCode = housekeeping.uniqueCode("ED")
        val edited =
            housekeeping.cleaningTypeBody(
                code = newCode,
                name = "Permanência longa",
                requiresInspection = true,
                defaultPriority = 5,
                linenChangeEveryNights = 7,
                credits = listOf(housekeeping.credit(first, 4), housekeeping.credit(second, 1)),
            )

        with(iam) {
            admin(PUT, path, edited).problem(428, "PRECONDITION_REQUIRED")
            call(PUT, path, asAdmin(), edited, ifMatch = "\"999\"").problem(412, "PRECONDITION_FAILED")
            val updated = call(PUT, path, asAdmin(), edited, ifMatch = etag).ok()
            updated["code"].asString() shouldBe newCode
            updated["name"].asString() shouldBe "Permanência longa"
            updated["requiresInspection"].asBoolean() shouldBe true
            updated["defaultPriority"].asInt() shouldBe 5
            updated["linenChangeEveryNights"].asInt() shouldBe 7
            creditsOf(updated) shouldBe mapOf(first to 4, second to 1)
            housekeeping.etagOf(path) shouldBe "\"1\""
            // O ETag velho, depois da edição: 412.
            call(PUT, path, asAdmin(), edited, ifMatch = etag).problem(412, "PRECONDITION_FAILED")

            // Tirar a regra do enxoval e um dos créditos.
            val trimmed =
                edited + mapOf("linenChangeEveryNights" to null, "credits" to listOf(housekeeping.credit(second, 2)))
            val again = call(PUT, path, asAdmin(), trimmed, ifMatch = "\"1\"").ok()
            again["linenChangeEveryNights"].isNull shouldBe true
            creditsOf(again) shouldBe mapOf(second to 2)
        }
        housekeeping.storedCredits(id) shouldBe mapOf(second to 2)
    }

    @Test
    fun `deactivate and reactivate switch the type, and repeating changes nothing`() {
        val id = housekeeping.createCleaningType()
        val path = "$CLEANING_TYPES/$id"

        with(iam) {
            admin(POST, "$path/deactivate").ok()["active"].asBoolean() shouldBe false
            admin(POST, "$path/deactivate").ok()["active"].asBoolean() shouldBe false
            housekeeping.etagOf(path) shouldBe "\"1\""
            admin(POST, "$path/reactivate").ok()["active"].asBoolean() shouldBe true
            admin(POST, "$path/reactivate").ok()["active"].asBoolean() shouldBe true
            housekeeping.etagOf(path) shouldBe "\"2\""
            admin(GET, "$CLEANING_TYPES/${UUID.randomUUID()}").problem(404, "CLEANING_TYPE_NOT_FOUND")
            admin(POST, "$CLEANING_TYPES/${UUID.randomUUID()}/deactivate").problem(404, "CLEANING_TYPE_NOT_FOUND")
        }
    }

    @Test
    fun `the list pages by code, with the active filter`() {
        val prefix = "LS" + rooms.suffix(6).uppercase()
        val ids = (1..3).map { housekeeping.createCleaningType(housekeeping.cleaningTypeBody(code = "$prefix$it")) }
        admin(POST, "$CLEANING_TYPES/${ids[1]}/deactivate")

        val all = housekeeping.listAll("$CLEANING_TYPES?limit=2")
        val codes = all.map { it["code"].asString() }
        codes.filter { it.startsWith(prefix) } shouldContainExactly listOf("${prefix}1", "${prefix}2", "${prefix}3")
        all.map { it["id"].asString() }.toSet().size shouldBe all.size

        val active = housekeeping.listAll("$CLEANING_TYPES?active=true&limit=3").map { it["id"].asString() }
        active shouldContain ids[0].toString()
        active shouldNotContain ids[1].toString()
        val inactive = housekeeping.listAll("$CLEANING_TYPES?active=false").map { it["id"].asString() }
        inactive shouldContain ids[1].toString()
        inactive shouldNotContain ids[0].toString()
    }

    @Test
    fun `a cursor whose code key carries NUL is refused as an invalid cursor`() {
        val cursor = PageCursor.of("A\u0000B", UUID.randomUUID().toString()).encode()

        with(iam) { admin(GET, "$CLEANING_TYPES?cursor=$cursor").problem(400, "INVALID_CURSOR") }
    }

    @Test
    fun `an active type without credit for an active room type lists it, also for a room type created later`() {
        val credited = rooms.createRoomType()
        val id =
            housekeeping.createCleaningType(
                housekeeping.cleaningTypeBody(credits = listOf(housekeeping.credit(credited, 2))),
            )
        val path = "$CLEANING_TYPES/$id"

        val before = missingOf(with(iam) { admin(GET, path).ok() })
        before shouldNotContain credited
        val later = rooms.createRoomType()
        val after = missingOf(with(iam) { admin(GET, path).ok() })
        after shouldContain later
        after shouldNotContain credited
        // A lista também traz o aviso.
        val listed = housekeeping.listAll("$CLEANING_TYPES?active=true").single { it["id"].asString() == id.toString() }
        missingOf(listed) shouldContain later
        // Um tipo de UH desativado não entra no aviso, e o crédito para ele é aceito (D6).
        with(iam) { admin(POST, "/api/v1/room-types/$later/deactivate").ok() }
        missingOf(with(iam) { admin(GET, path).ok() }) shouldNotContain later
        val withInactive =
            housekeeping.cleaningTypeBody(
                credits = listOf(housekeeping.credit(credited, 2), housekeeping.credit(later, 1)),
            )
        creditsOf(with(iam) { admin(POST, CLEANING_TYPES, withInactive).expectStatus(201)!! }) shouldBe
            mapOf(credited to 2, later to 1)
        // Um tipo de limpeza desativado não avisa nada.
        with(iam) { admin(POST, "$path/deactivate").ok() }
        missingOf(with(iam) { admin(GET, path).ok() }) shouldBe emptyList()
    }

    @Test
    fun `references and values out of range are refused in the body, and nothing is written`() {
        val roomType = rooms.createRoomType()
        val code = housekeeping.uniqueCode("RF")

        fun body(vararg changes: Pair<String, Any?>) = housekeeping.cleaningTypeBody(code = code) + changes.toMap()

        with(iam) {
            val unknown = UUID.randomUUID()
            fieldsOf(
                admin(POST, CLEANING_TYPES, body("credits" to listOf(housekeeping.credit(unknown, 1))))
                    .problem(400, "CLEANING_TYPE_UNKNOWN_ROOM_TYPE"),
            ) shouldContain "credits"
            fieldsOf(
                admin(
                    POST,
                    CLEANING_TYPES,
                    body("credits" to listOf(housekeeping.credit(roomType, 1), housekeeping.credit(roomType, 2))),
                ).problem(400, "CLEANING_TYPE_CREDIT_DUPLICATED"),
            ) shouldContain "credits"
            val outOfRange =
                listOf(
                    body("defaultPriority" to 0) to "defaultPriority",
                    body("defaultPriority" to 6) to "defaultPriority",
                    body("defaultPriority" to null) to "defaultPriority",
                    body("linenChangeEveryNights" to 0) to "linenChangeEveryNights",
                    body("linenChangeEveryNights" to 61) to "linenChangeEveryNights",
                    body("credits" to listOf(housekeeping.credit(roomType, 0))) to "credits[0].credits",
                    body("credits" to listOf(housekeeping.credit(roomType, -1))) to "credits[0].credits",
                    body("credits" to listOf(housekeeping.credit(roomType, 101))) to "credits[0].credits",
                    body("credits" to (1..201).map { housekeeping.credit(UUID.randomUUID(), 1) }) to "credits",
                    body("name" to "Faxina\u0000pesada") to "name",
                    body("name" to "   ") to "name",
                    body("code" to "com espaço") to "code",
                )
            outOfRange.forEach { (invalid, field) ->
                withClue(field) {
                    val response = admin(POST, CLEANING_TYPES, invalid)
                    withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 400 }
                    fieldsOf(json(response)) shouldContain field
                }
            }
            fieldsOf(
                admin(
                    POST,
                    CLEANING_TYPES,
                    body("name" to "Faxina\u0000pesada"),
                ).problem(400, "HOUSEKEEPING_DATA_INVALID"),
            ) shouldContain "name"
        }
        housekeeping.countByCode(code) shouldBe 0
        housekeeping.countByCode(code.uppercase()) shouldBe 0
    }

    @Test
    fun `a repeated code is a conflict, also after a case change, and the PUT refusals keep the type as it was`() {
        val roomType = rooms.createRoomType()
        val code = housekeeping.uniqueCode("DUP")
        val first =
            housekeeping.createCleaningType(
                housekeeping.cleaningTypeBody(code = code, credits = listOf(housekeeping.credit(roomType, 2))),
            )
        val other = housekeeping.createCleaningType()
        val path = "$CLEANING_TYPES/$other"
        val otherCode = with(iam) { admin(GET, path).ok() }["code"].asString()

        with(iam) {
            admin(POST, CLEANING_TYPES, housekeeping.cleaningTypeBody(code = code.lowercase()))
                .problem(409, "CLEANING_TYPE_CODE_ALREADY_IN_USE")
            housekeeping.countByCode(code) shouldBe 1

            val refusals =
                listOf(
                    housekeeping.cleaningTypeBody(code = code) to (409 to "CLEANING_TYPE_CODE_ALREADY_IN_USE"),
                    housekeeping.cleaningTypeBody(
                        code = otherCode,
                        credits = listOf(housekeeping.credit(UUID.randomUUID(), 1)),
                    ) to (400 to "CLEANING_TYPE_UNKNOWN_ROOM_TYPE"),
                    housekeeping.cleaningTypeBody(
                        code = otherCode,
                        credits = listOf(housekeeping.credit(roomType, 1), housekeeping.credit(roomType, 1)),
                    ) to (400 to "CLEANING_TYPE_CREDIT_DUPLICATED"),
                    housekeeping.cleaningTypeBody(
                        code = otherCode,
                        defaultPriority = 9,
                    ) to (400 to "VALIDATION_FAILED"),
                )
            refusals.forEach { (body, expected) ->
                call(PUT, path, asAdmin(), body, ifMatch = "\"0\"").problem(expected.first, expected.second)
            }
        }
        housekeeping.etagOf(path) shouldBe "\"0\""
        housekeeping.storedCredits(other) shouldBe emptyMap()
        housekeeping.storedCredits(first) shouldBe mapOf(roomType to 2)
    }

    @Test
    fun `a null credit item is a validation error on POST and PUT, and nothing is written`() {
        val roomType = rooms.createRoomType()
        val code = housekeeping.uniqueCode("NC")
        val existing =
            housekeeping.createCleaningType(
                housekeeping.cleaningTypeBody(credits = listOf(housekeeping.credit(roomType, 2))),
            )
        val path = "$CLEANING_TYPES/$existing"
        val existingCode = with(iam) { admin(GET, path).ok() }["code"].asString()

        with(iam) {
            val nullItem =
                admin(POST, CLEANING_TYPES, housekeeping.cleaningTypeBody(code = code) + ("credits" to listOf(null)))
                    .problem(400, "VALIDATION_FAILED")
            nullItem["errors"].values().map { it["field"].asString() to it["code"].asString() } shouldContain
                ("credits[0]" to "REQUIRED")
            val withNull =
                housekeeping.cleaningTypeBody(code = existingCode) +
                    ("credits" to listOf(housekeeping.credit(roomType, 3), null))
            fieldsOf(
                call(PUT, path, asAdmin(), withNull, ifMatch = "\"0\"").problem(400, "VALIDATION_FAILED"),
            ) shouldContain
                "credits[1]"
        }
        housekeeping.countByCode(code) shouldBe 0
        housekeeping.etagOf(path) shouldBe "\"0\""
        housekeeping.storedCredits(existing) shouldBe mapOf(roomType to 2)
    }

    private fun admin(
        method: HttpMethod,
        path: String,
        body: Any? = null,
    ): MockHttpServletResponse = iam.call(method, path, iam.asAdmin(), body)

    private fun creditsOf(body: JsonNode): Map<UUID, Int> =
        body["credits"].values().associate { UUID.fromString(it["roomTypeId"].asString()) to it["credits"].asInt() }

    private fun missingOf(body: JsonNode): List<UUID> =
        body["missingCreditRoomTypeIds"].values().map { UUID.fromString(it.asString()) }

    private fun fieldsOf(problem: JsonNode): List<String> = problem["errors"].values().map { it["field"].asString() }
}
