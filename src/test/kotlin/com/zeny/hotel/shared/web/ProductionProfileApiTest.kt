package com.zeny.hotel.shared.web

import com.zeny.hotel.testsupport.OUTBOX_PURGE_DEFERRED
import com.zeny.hotel.testsupport.PostgresInitializer
import com.zeny.hotel.testsupport.RESUBMISSION_JOB_DEFERRED
import com.zeny.hotel.testsupport.TEST_MIGRATIONS
import com.zeny.hotel.testsupport.TestAuthInitializer
import com.zeny.hotel.testsupport.TestEncryptionKeysInitializer
import com.zeny.hotel.testsupport.TestStorageInitializer
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldNotContainIgnoringCase
import org.junit.jupiter.api.Test
import org.springdoc.webmvc.api.OpenApiResource
import org.springdoc.webmvc.ui.SwaggerWelcomeWebMvc
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** Com o profile `prod`, o Swagger UI e o `/v3/api-docs` não existem (ARCHITECTURE §7). */
@SpringBootTest(properties = [TEST_MIGRATIONS, RESUBMISSION_JOB_DEFERRED, OUTBOX_PURGE_DEFERRED])
@ActiveProfiles("prod")
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = [
        PostgresInitializer::class,
        TestEncryptionKeysInitializer::class,
        TestAuthInitializer::class,
        TestStorageInitializer::class,
    ],
)
class ProductionProfileApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val context: ApplicationContext,
) {
    @Test
    fun `swagger ui and api docs are not served in prod`() {
        context.getBeanNamesForType(SwaggerWelcomeWebMvc::class.java).toList().shouldBeEmpty()
        context.getBeanNamesForType(OpenApiResource::class.java).toList().shouldBeEmpty()

        listOf("/swagger-ui.html", "/swagger-ui/index.html", "/v3/api-docs", "/v3/api-docs.yaml").forEach { path ->
            val response = mockMvc.get(path).andReturn().response
            withClue(path) {
                response.status shouldNotBe 200
                response.status shouldBeGreaterThanOrEqual 400
                // Nem a página do Swagger UI nem o documento OpenAPI: só o problema da recusa.
                val body = response.getContentAsString(Charsets.UTF_8)
                body shouldNotContainIgnoringCase "Swagger UI"
                body shouldNotContain "\"openapi\""
                body shouldNotContain "openapi:"
            }
        }
    }

    @Test
    fun `the api itself still answers in prod`() {
        mockMvc.get("/api/v1/meta/version").andExpect { status { isOk() } }
    }
}
