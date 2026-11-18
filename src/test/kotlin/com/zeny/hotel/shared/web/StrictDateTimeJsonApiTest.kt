package com.zeny.hotel.shared.web

import com.zeny.hotel.billing.BillingTestSupport
import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistsTestSupport
import com.zeny.hotel.housekeeping.HousekeepingTestSupport
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.StaffTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.util.UUID

/**
 * A leitura estrita de datas e instantes nos corpos da API (M6 E18; ARCHITECTURE §7, "Datas e valores"): fora do
 * texto ISO-8601, `400 VALIDATION_FAILED` com `INVALID_FORMAT` no campo; o texto ISO passa da leitura do corpo. Datas
 * no `businessDate` do fechamento manual, por um usuário **sem** `endofday.runs.execute` (H10: a leitura do corpo vem
 * antes da autorização, e nenhum fechamento roda no banco compartilhado), e no `dueOn` dos recados; instantes no
 * `expiresAt` da caução. Nenhuma chamada grava: os ids do corpo e do caminho não existem.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class StrictDateTimeJsonApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired jdbc: JdbcClient,
) {
    private val api = BillingTestSupport(iam, mockMvc, jsonMapper)
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)
    private val checklists = ChecklistsTestSupport(iam)

    @Test
    fun `the businessDate of the manual end of day accepts only ISO date text`() {
        val withoutPermission = asUser(iam.createUser().id.value)
        val post = { value: Any ->
            api.call(POST, "/api/v1/end-of-day/runs", withoutPermission, mapOf("businessDate" to value))
        }

        REFUSED_DATES.associateWith { post(it).answer() } shouldBe
            REFUSED_DATES.associateWith { invalidFormat("businessDate") }
        post(ACCEPTED_DATE).shouldPassBodyReading("businessDate", ACCEPTED_DATE)
    }

    @Test
    fun `the dueOn of a trace accepts only ISO date text`() {
        val post = { value: Any ->
            api.call(
                POST,
                "/api/v1/traces",
                api.admin,
                mapOf(
                    "departmentId" to UuidV7.generate(),
                    "dueOn" to value,
                    "text" to "Recado",
                    "roomId" to UuidV7.generate(),
                ),
            )
        }

        REFUSED_DATES.associateWith { post(it).answer() } shouldBe
            REFUSED_DATES.associateWith { invalidFormat("dueOn") }
        post(ACCEPTED_DATE).shouldPassBodyReading("dueOn", ACCEPTED_DATE)
    }

    @Test
    fun `the expiresAt of a card pre-authorization accepts only ISO instant text`() {
        val post = { value: Any ->
            api.call(
                POST,
                "/api/v1/folios/${UuidV7.generate()}/card-pre-authorizations",
                api.admin,
                mapOf("amount" to "100.00", "expiresAt" to value, "authorizationCode" to "A1B2C3"),
            )
        }

        REFUSED_INSTANTS.associateWith { post(it).answer() } shouldBe
            REFUSED_INSTANTS.associateWith { invalidFormat("expiresAt") }
        post(ACCEPTED_INSTANT).shouldPassBodyReading("expiresAt", ACCEPTED_INSTANT)
    }

    // --- M6 E20: faixa do StorableRange, LocalTime, inteiro e UUID --------------------------------------------

    @Test
    fun `a dueOn outside the storable range is refused on the field, and the extremes pass the body reading`() {
        val department = StaffTestSupport(iam).createDepartment()
        val room = housekeeping.createRoom()
        val post = { value: Any ->
            api.call(POST, "/api/v1/traces", api.admin, traceBody(value, department, room))
        }

        OUT_OF_RANGE_DATES.associateWith { post(it).answer() } shouldBe
            OUT_OF_RANGE_DATES.associateWith { invalidFormat("dueOn") }
        EXTREME_DATES.forEach { post(it).shouldBeRead("dueOn", it) }
    }

    @Test
    fun `an expiresAt outside the storable range is refused on the field, and the extremes pass the body reading`() {
        val post = { value: Any ->
            api.call(
                POST,
                "/api/v1/folios/${UuidV7.generate()}/card-pre-authorizations",
                api.admin,
                mapOf("amount" to "100.00", "expiresAt" to value, "authorizationCode" to "A1B2C3"),
            )
        }

        OUT_OF_RANGE_INSTANTS.associateWith { post(it).answer() } shouldBe
            OUT_OF_RANGE_INSTANTS.associateWith { invalidFormat("expiresAt") }
        EXTREME_INSTANTS.forEach { post(it).shouldPassBodyReading("expiresAt", it) }
    }

    /** Sem a permissão de gerir (H10: a leitura do corpo vem antes da autorização), para nada ser gravado. */
    @Test
    fun `the checkInTime of the hotel policy accepts only HH-mm or HH-mm-ss`() {
        val current: Map<String, Any?> =
            jsonMapper.readValue(with(api) { call(GET, "/api/v1/hotel/policies", admin).expect(200) }.toString())
        val withoutPermission = asUser(iam.createUser().id.value)
        val put = { value: Any ->
            api.call(PUT, "/api/v1/hotel/policies", withoutPermission, current + ("checkInTime" to value))
        }

        REFUSED_TIMES.associateWith { put(it).answer() } shouldBe
            REFUSED_TIMES.associateWith { invalidFormat("checkInTime") }
        ACCEPTED_TIMES.forEach { put(it).shouldPassBodyReading("checkInTime", it) }
    }

    @Test
    fun `a fractional number in an integer field is refused on the field, the integer is accepted`() {
        val type = housekeeping.createCleaningType()
        val roomType = housekeeping.rooms.createRoomType()
        val typePath = "/api/v1/cleaning-types/${housekeeping.createCleaningType()}"
        val post = { value: Any ->
            val body = housekeeping.scheduleBody(type, roomId = housekeeping.createRoom()) + ("frequencyDays" to value)
            api.call(POST, "/api/v1/scheduled-cleanings", api.admin, body)
        }
        val put = { value: Any ->
            val credits = listOf(mapOf("roomTypeId" to roomType, "credits" to value))
            val body = housekeeping.cleaningTypeBody(credits = credits)
            api.call(PUT, typePath, api.admin, body, ifMatch = housekeeping.etagOf(typePath))
        }

        FRACTIONAL_NUMBERS.associateWith { post(it).answer() } shouldBe
            FRACTIONAL_NUMBERS.associateWith { invalidFormat("frequencyDays") }
        FRACTIONAL_NUMBERS.associateWith { put(it).answer() } shouldBe
            FRACTIONAL_NUMBERS.associateWith { invalidFormat("credits[0].credits") }
        post(7).shouldBeRead("frequencyDays", 7)
        put(7).shouldBeRead("credits[0].credits", 7)
    }

    @Test
    fun `an empty checklistTemplateId is refused on the field and keeps the link, null removes it`() {
        val template = checklists.create(checklists.templateBody(context = ChecklistContext.HOUSEKEEPING))
        checklists.publish(template)
        val id = housekeeping.createCleaningType(housekeeping.cleaningTypeBody(checklistTemplateId = template))
        val path = "/api/v1/cleaning-types/$id"
        val put = { value: Any? ->
            val body = housekeeping.cleaningTypeBody(checklistTemplateId = null) + ("checklistTemplateId" to value)
            api.call(PUT, path, api.admin, body, ifMatch = housekeeping.etagOf(path))
        }
        val linked = { with(api) { call(GET, path, admin).expect(200) }["checklistTemplateId"] }

        put("").answer() shouldBe invalidFormat("checklistTemplateId")
        linked().asString() shouldBe template.toString()
        put(null).status shouldBe 200
        linked().isNull shouldBe true
    }

    private fun traceBody(
        dueOn: Any,
        department: UUID,
        room: UUID,
    ) = mapOf("departmentId" to department, "dueOn" to dueOn, "text" to "Recado", "roomId" to room)

    /** O status, o código e os erros por campo de uma resposta, para comparar todos os valores de uma vez. */
    private fun MockHttpServletResponse.answer(): String {
        val problem = iam.json(this)
        val errors = problem["errors"]?.values()?.map { "${it["field"].asString()}:${it["code"].asString()}" }.orEmpty()
        return "$status ${problem["code"]?.asString()} $errors"
    }

    private fun invalidFormat(field: String) = "400 VALIDATION_FAILED [$field:INVALID_FORMAT]"

    /** O corpo foi lido: a resposta é a da regra seguinte (permissão, conta ou setor inexistente), nunca a do campo. */
    private fun MockHttpServletResponse.shouldPassBodyReading(
        field: String,
        value: Any,
    ) = withClue("$field = <$value>: $status ${getContentAsString(Charsets.UTF_8)}") {
        status shouldNotBe 500
        val problem = iam.json(this)
        problem["code"].asString() shouldNotBe "MALFORMED_REQUEST"
        problem["code"].asString() shouldNotBe "VALIDATION_FAILED"
        val errors = problem["errors"]?.values()?.map { it["field"].asString() to it["code"].asString() }.orEmpty()
        errors shouldNotContain (field to "INVALID_FORMAT")
    }

    /** Como [shouldPassBodyReading], mas com a chamada de dados reais, que pode dar certo (2xx). */
    private fun MockHttpServletResponse.shouldBeRead(
        field: String,
        value: Any,
    ) = if (status < 400) Unit else shouldPassBodyReading(field, value)

    private companion object {
        val OUT_OF_RANGE_DATES: List<Any> = listOf("+5874898-01-01", "-4713-12-31")
        val EXTREME_DATES: List<Any> = listOf("+5874897-12-31", "-4712-01-01")
        val OUT_OF_RANGE_INSTANTS: List<Any> = listOf("+294277-01-01T00:00:00Z", "-4713-12-31T23:59:59Z")
        val EXTREME_INSTANTS: List<Any> = listOf("-4712-01-01T00:00:00Z", "+294276-12-31T23:59:59.999999Z")
        val REFUSED_TIMES: List<Any> = listOf(905, "9:5", "09:05:00Z", "09:05:30.5", "24:00", "")
        val ACCEPTED_TIMES: List<Any> = listOf("09:05", "09:05:30")
        val FRACTIONAL_NUMBERS: List<Any> = listOf(7.5, 7.0)
        const val ACCEPTED_DATE = "2026-11-13"
        const val ACCEPTED_INSTANT = "2026-11-13T10:00:00Z"

        /** Dia da época em número, data-hora com e sem `Z`, array `[a,m,d]`, vazio e a forma sem zeros. */
        val REFUSED_DATES: List<Any> =
            listOf(20405, "2026-11-13T00:00:00", "2026-11-13T00:00:00Z", listOf(2026, 11, 13), "", "2026-1-3")

        /** Época em segundos e em milissegundos, em número e em texto, texto sem `T` e vazio. */
        val REFUSED_INSTANTS: List<Any> =
            listOf(
                1_763_028_000L,
                1_763_028_000_000L,
                "1763028000",
                "1763028000000",
                "2026-11-13 10:00:00Z",
                "",
            )
    }
}
