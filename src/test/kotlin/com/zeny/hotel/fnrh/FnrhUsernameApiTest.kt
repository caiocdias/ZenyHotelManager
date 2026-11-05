package com.zeny.hotel.fnrh

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient

/** O usuário da FNRH não pode ter `:` (RFC 7617, Basic Auth): a API responde 400 no campo (M5 E07, Task 4). */
@IntegrationTest
@Import(IamTestConfiguration::class)
class FnrhUsernameApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
) {
    private val path = "/api/v1/fnrh/settings"

    private fun body(username: String) =
        mapOf(
            "environment" to "HOMOLOGATION",
            "username" to username,
            "password" to "s3cr3t-pass",
            "defaultRequesterCpf" to "529.982.247-25",
            "enabled" to true,
        )

    @BeforeEach
    @AfterEach
    fun resetSeed() {
        jdbc
            .sql(
                "UPDATE fnrh.settings SET environment = 'HOMOLOGATION', username = NULL, password = NULL, " +
                    "default_cpf = NULL, enabled = FALSE",
            ).update()
    }

    @Test
    fun `a username with a colon is 400 on the field and changes nothing`(): Unit =
        with(iam) {
            val etag = call(GET, path, asAdmin()).getHeader(HttpHeaders.ETAG).shouldNotBeNull()

            val response = call(PUT, path, asAdmin(), body("dom:user"), ifMatch = etag)

            val fields = response.problem(400, "VALIDATION_FAILED")["errors"].values().map { it["field"].asString() }
            fields shouldContainExactly listOf("username")
            call(GET, path, asAdmin()).getHeader(HttpHeaders.ETAG) shouldBe etag
        }

    @Test
    fun `a username without a colon is still accepted`(): Unit =
        with(iam) {
            val etag = call(GET, path, asAdmin()).getHeader(HttpHeaders.ETAG).shouldNotBeNull()

            val written = call(PUT, path, asAdmin(), body("dom.user@hotel"), ifMatch = etag).ok()

            written["username"].asString() shouldBe "dom.user@hotel"
        }
}
