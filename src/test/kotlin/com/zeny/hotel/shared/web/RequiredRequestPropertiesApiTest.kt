package com.zeny.hotel.shared.web

import com.zeny.hotel.billing.BillingTestSupport
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper

/**
 * A regra do `required` dos requests (M6 E20, D1) presa ao que o servidor faz: a propriedade com valor padrão, que o
 * contrato não marca `required`, pode faltar no corpo. Nenhuma chamada grava: a reserva do cancelamento não existe.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class RequiredRequestPropertiesApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val api = BillingTestSupport(iam, mockMvc, jsonMapper)

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
}
