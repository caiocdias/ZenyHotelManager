package com.zeny.hotel.shared.web

import com.zeny.hotel.billing.BillingTestSupport
import com.zeny.hotel.housekeeping.HousekeepingTestSupport
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.rooms.RoomsTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
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
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue

/**
 * A regra do `required` dos requests (M6 E20, D1) presa ao que o servidor faz: a propriedade Kotlin de tipo primitivo
 * não anulável sem valor padrão, ausente ou `null` no corpo, é recusada com `REQUIRED` no campo (`""` nela segue
 * `INVALID_FORMAT`); a com valor padrão, que o contrato não marca `required`, pode faltar. Nenhuma chamada grava: o
 * corpo do tipo de UH é recusado e a reserva do cancelamento não existe.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class RequiredRequestPropertiesApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired jdbc: JdbcClient,
) {
    private val api = BillingTestSupport(iam, mockMvc, jsonMapper)
    private val rooms = RoomsTestSupport(iam)
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)

    @Test
    fun `a room type without maxAdults, or with it null, is refused with REQUIRED on the field`() {
        val absent = rooms.roomTypeBody() - "maxAdults"
        val nulled = rooms.roomTypeBody() + ("maxAdults" to null)
        val empty = rooms.roomTypeBody() + ("maxAdults" to "")

        listOf(absent, nulled, empty).map { postRoomType(it).answer() } shouldBe
            listOf(
                "400 VALIDATION_FAILED [maxAdults:REQUIRED]",
                "400 VALIDATION_FAILED [maxAdults:REQUIRED]",
                "400 VALIDATION_FAILED [maxAdults:INVALID_FORMAT]",
            )
    }

    /**
     * O payload offline passa pela mesma conversão do mapeador único (`convertValue` para o tipo do handler, em
     * `OfflineCommands`), que transforma qualquer [JacksonException] em `REJECTED COMMAND_PAYLOAD_INVALID`: o primitivo
     * ausente ou `null` continua recusado ali.
     */
    @Test
    fun `an offline payload without a primitive, or with it null, is still refused by the mapper conversion`() {
        listOf(mapOf("text" to "x"), mapOf("text" to "x", "count" to null)).forEach { payload ->
            withClue(payload) {
                shouldThrow<JacksonException> { jsonMapper.convertValue(payload, CountedPayload::class.java) }
            }
        }
        jsonMapper.convertValue(mapOf("text" to "x", "count" to 2), CountedPayload::class.java) shouldBe
            CountedPayload("x", 2)
    }

    @Test
    fun `a reservation cancellation without waivePenalty passes the body reading`() {
        val response =
            api.call(
                POST,
                "/api/v1/reservations/${UuidV7.generate()}/cancel",
                api.admin,
                mapOf("reason" to "GUEST_REQUEST"),
            )

        withClue("${response.status} ${response.getContentAsString(Charsets.UTF_8)}") {
            response.status shouldNotBe 500
            response.status shouldNotBe 400
            val code = iam.json(response)["code"].asString()
            code shouldNotBe "MALFORMED_REQUEST"
            code shouldNotBe "VALIDATION_FAILED"
        }
    }

    /**
     * Primitivo aninhado numa lista (`childAgeBands[1].minAge`, `Int` sem padrão): ausente ou `null` é `REQUIRED` com o
     * caminho completo, `""` é `INVALID_FORMAT`. Por um usuário sem permissão de gerir (a leitura do corpo vem antes da
     * autorização), para nada ser gravado.
     */
    @Test
    fun `a missing or null primitive inside a list item is REQUIRED on the full path`() {
        val current: Map<String, Any?> =
            jsonMapper.readValue(with(api) { call(GET, "/api/v1/hotel/policies", admin).expect(200) }.toString())
        val withoutPermission = asUser(iam.createUser().id.value)
        val band = mapOf("minAge" to 0, "maxAge" to 5, "label" to "A")
        val put = { second: Map<String, Any?> ->
            val bands = listOf(band, second)
            api.call(PUT, "/api/v1/hotel/policies", withoutPermission, current + ("childAgeBands" to bands))
        }
        val other = mapOf("maxAge" to 9, "label" to "B")

        put(other).answer() shouldBe "400 VALIDATION_FAILED [childAgeBands[1].minAge:REQUIRED]"
        val required = "400 VALIDATION_FAILED [childAgeBands[1].minAge:REQUIRED]"
        val invalid = "400 VALIDATION_FAILED [childAgeBands[1].minAge:INVALID_FORMAT]"
        put(other + ("minAge" to null)).answer() shouldBe required
        put(other + ("minAge" to "")).answer() shouldBe invalid
        put(other + ("minAge" to 6.5)).answer() shouldBe invalid
    }

    /**
     * `credits[0].credits` é `Int?` com `@NotNull`, não um primitivo sem padrão: quem recusa é o Bean Validation, que
     * devolve o nome da restrição (`NOT_NULL`) com o caminho completo, e não o `REQUIRED` do Jackson.
     */
    @Test
    fun `a cleaning type credit without credits is NOT_NULL on the full path credits-0-credits`() {
        val roomType = UuidV7.generate()
        val post = { credit: Map<String, Any?> ->
            api.call(POST, "/api/v1/cleaning-types", api.admin, housekeeping.cleaningTypeBody(credits = listOf(credit)))
        }

        post(mapOf("roomTypeId" to roomType)).answer() shouldBe "400 VALIDATION_FAILED [credits[0].credits:NOT_NULL]"
        post(mapOf("roomTypeId" to roomType, "credits" to null)).answer() shouldBe
            "400 VALIDATION_FAILED [credits[0].credits:NOT_NULL]"
    }

    private fun postRoomType(body: Map<String, Any?>) =
        api.call(POST, "/api/v1/room-types", api.admin, body, idempotencyKey = null)

    private fun MockHttpServletResponse.answer(): String {
        val problem = iam.json(this)
        val errors = problem["errors"]?.values()?.map { "${it["field"].asString()}:${it["code"].asString()}" }.orEmpty()
        return "$status ${problem["code"]?.asString()} $errors"
    }

    data class CountedPayload(
        val text: String,
        val count: Int,
    )
}
