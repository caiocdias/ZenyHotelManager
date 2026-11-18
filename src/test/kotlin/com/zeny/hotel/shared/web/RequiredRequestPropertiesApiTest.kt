package com.zeny.hotel.shared.web

import com.zeny.hotel.billing.BillingTestSupport
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.rooms.RoomsTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper

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
) {
    private val api = BillingTestSupport(iam, mockMvc, jsonMapper)
    private val rooms = RoomsTestSupport(iam)

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
