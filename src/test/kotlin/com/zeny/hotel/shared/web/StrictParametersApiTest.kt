package com.zeny.hotel.shared.web

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request

/**
 * Datas e instantes estritos em query string e caminho (M6 E20; ARCHITECTURE §7, "Datas e valores"): só o texto
 * ISO-8601 dentro do `StorableRange`; o resto é `400 VALIDATION_FAILED` com o parâmetro em `errors[]`
 * (`INVALID_FORMAT`), nunca 500. O administrador tem a permissão de cada endpoint, então a resposta vem da conversão
 * e não do `403`. O valor vai como parâmetro já decodificado (`+5874898-01-01` é o `%2B5874898-01-01` da URL).
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class StrictParametersApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `the dueOn of the traces list accepts only an ISO date inside the storable range`() =
        shouldRefuseOnlyTheInvalid("/api/v1/traces", "dueOn", REFUSED_DATES, ACCEPTED_DATES)

    @Test
    fun `the to of the expiring pre-authorizations accepts only an ISO date inside the storable range`() =
        shouldRefuseOnlyTheInvalid(
            "/api/v1/card-pre-authorizations/expiring",
            "to",
            REFUSED_DATES,
            listOf("2026-11-13"),
        )

    @Test
    fun `the businessDate of the checklist executions refuses a date with an offset`() =
        shouldRefuseOnlyTheInvalid(
            "/api/v1/checklist-executions",
            "businessDate",
            REFUSED_DATES + listOf("2026-11-13Z", "2026-11-13+03:00"),
            ACCEPTED_DATES,
        )

    @Test
    fun `the from of the audit entries accepts only an ISO instant inside the storable range`() =
        shouldRefuseOnlyTheInvalid("/api/v1/audit-entries", "from", REFUSED_INSTANTS, ACCEPTED_INSTANTS)

    @Test
    fun `the to of the audit entries accepts only an ISO instant inside the storable range`() =
        shouldRefuseOnlyTheInvalid("/api/v1/audit-entries", "to", REFUSED_INSTANTS, ACCEPTED_INSTANTS)

    @Test
    fun `the date of an assignment plan path accepts only an ISO date inside the storable range`() {
        assertSoftly {
            (REFUSED_DATES - SLASH_SHORT_DATE).forEach { value ->
                withClue("/assignment-plans/$value") {
                    answer(get("/api/v1/assignment-plans/{date}", pathValue = value)) shouldBe invalidFormat("date")
                }
            }
            ACCEPTED_DATES.forEach { value ->
                withClue(
                    "/assignment-plans/$value",
                ) { shouldPassConversion(get("/api/v1/assignment-plans/{date}", pathValue = value)) }
            }
        }
    }

    @Test
    fun `an empty optional query parameter is absent, and an empty required one is a 400 on the parameter`() {
        // O filtro por um setor que ninguém usa restringe a lista aos próprios dados: o banco é compartilhado e há
        // recados de setor inexistente deixados por testes de repositório.
        val optional =
            get("/api/v1/traces", queryParams = mapOf("dueOn" to "", "departmentId" to UuidV7.generate().toString()))
        withClue("dueOn= -> ${optional.status}") { optional.status shouldBe 200 }

        val required = get("/api/v1/card-pre-authorizations/expiring", queryParams = mapOf("to" to ""))
        withClue("to= -> ${required.status}") {
            required.status shouldBe 400
            iam.json(required)["code"].asString() shouldBe "VALIDATION_FAILED"
            errorFields(required).map { it.substringBefore(':') } shouldBe listOf("to")
        }
    }

    @Test
    fun `a repeated query parameter is never a 500`() {
        val bothInvalid = get("/api/v1/audit-entries", repeatedParams = "from" to listOf("a", "b"))
        val firstInvalid = get("/api/v1/audit-entries", repeatedParams = "from" to listOf("a", "2026-11-13T10:00:00Z"))
        val bothValid =
            get(
                "/api/v1/audit-entries",
                repeatedParams =
                    "from" to listOf("2026-11-13T10:00:00Z", "2026-11-14T10:00:00Z"),
            )

        answer(bothInvalid) shouldBe invalidFormat("from")
        answer(firstInvalid) shouldBe invalidFormat("from")
        bothValid.status shouldNotBe 500
    }

    private fun shouldRefuseOnlyTheInvalid(
        path: String,
        parameter: String,
        refused: List<String>,
        accepted: List<String>,
    ) = assertSoftly {
        refused.forEach { value ->
            withClue("$path?$parameter=$value") {
                answer(get(path, queryParams = mapOf(parameter to value))) shouldBe invalidFormat(parameter)
            }
        }
        accepted.forEach { value ->
            withClue("$path?$parameter=$value") {
                shouldPassConversion(get(path, queryParams = mapOf(parameter to value)))
            }
        }
    }

    private fun get(
        template: String,
        pathValue: String? = null,
        queryParams: Map<String, String> = emptyMap(),
        repeatedParams: Pair<String, List<String>>? = null,
    ): MockHttpServletResponse =
        mockMvc
            .request(GET, template, *listOfNotNull(pathValue).toTypedArray()) {
                with(iam.asAdmin())
                queryParams.forEach { (name, value) -> param(name, value) }
                repeatedParams?.let { (name, values) -> param(name, *values.toTypedArray()) }
            }.andReturn()
            .response

    private fun errorFields(response: MockHttpServletResponse): List<String> =
        iam
            .json(response)["errors"]
            ?.values()
            ?.map { "${it["field"].asString()}:${it["code"].asString()}" }
            .orEmpty()

    private fun answer(response: MockHttpServletResponse): String {
        val problem = iam.json(response)
        return "${response.status} ${problem["code"]?.asString()} ${errorFields(response)}"
    }

    private fun invalidFormat(parameter: String) = "400 VALIDATION_FAILED [$parameter:INVALID_FORMAT]"

    /** A conversão passou: a resposta pode ser 2xx ou a da regra seguinte, nunca 500 nem erro no parâmetro. */
    private fun shouldPassConversion(response: MockHttpServletResponse) {
        withClue("${response.status} ${response.getContentAsString(Charsets.UTF_8)}") {
            response.status shouldNotBe 500
            if (response.status == 400) {
                errorFields(response).none { it.endsWith(":INVALID_FORMAT") } shouldBe true
            }
        }
    }

    private companion object {
        const val SLASH_SHORT_DATE = "13/11/25"

        /** Formato curto do locale, época em ms, data-hora numa data e fora da faixa por cima e por baixo. */
        val REFUSED_DATES =
            listOf(SLASH_SHORT_DATE, "1763000000000", "2026-11-13T00:00:00", "+5874898-01-01", "-4713-12-31")

        /** O teto e o piso exatos, e uma data comum. */
        val ACCEPTED_DATES = listOf("2026-11-13", "+5874897-12-31", "-4712-01-01")

        val REFUSED_INSTANTS =
            listOf("13/11/25", "1763000000000", "2026-11-13", "+294277-01-01T00:00:00Z", "-4713-12-31T23:59:59Z")

        val ACCEPTED_INSTANTS =
            listOf("2026-11-13T10:00:00Z", "+294276-12-31T23:59:59.999999Z", "-4712-01-01T00:00:00Z")
    }
}
