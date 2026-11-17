package com.zeny.hotel.shared.web

import com.zeny.hotel.billing.BillingTestSupport
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper

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
    @Autowired jsonMapper: JsonMapper,
) {
    private val api = BillingTestSupport(iam, mockMvc, jsonMapper)

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
        val errors = problem["errors"]?.values()?.map { it["field"].asString() to it["code"].asString() }.orEmpty()
        errors shouldNotContain (field to "INVALID_FORMAT")
    }

    private companion object {
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
