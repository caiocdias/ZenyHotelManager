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

/** Ataques do testador ao `@Pattern` do usuário da FNRH (E07, Task 4, rodada 2). */
@IntegrationTest
@Import(IamTestConfiguration::class)
class FnrhUsernameApiAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
) {
    private val path = "/api/v1/fnrh/settings"

    private fun body(username: String?) =
        mapOf(
            "environment" to "HOMOLOGATION",
            "username" to username,
            "password" to "s3cr3t-pass",
            "defaultRequesterCpf" to "529.982.247-25",
            "enabled" to false,
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
    fun `colon variants are all 400 on the field, even padded by spaces or alone`(): Unit =
        with(iam) {
            for (name in listOf(":", " :", ": ", "a:", ":a", "a::b", "  dom:user  ", "a\n:b")) {
                val etag = call(GET, path, asAdmin()).getHeader(HttpHeaders.ETAG).shouldNotBeNull()
                val response = call(PUT, path, asAdmin(), body(name), ifMatch = etag)
                val errors = response.problem(400, "VALIDATION_FAILED")["errors"]
                val fields = errors.values().map { it["field"].asString() }
                fields shouldContainExactly listOf("username")
            }
        }

    @Test
    fun `a null username, a blank one and one with special characters but no ASCII colon are accepted`(): Unit =
        with(iam) {
            for (name in listOf(null, "", "   ", "usér.ñ@hotel", "a;b", "a：b")) {
                val etag = call(GET, path, asAdmin()).getHeader(HttpHeaders.ETAG).shouldNotBeNull()
                val written = call(PUT, path, asAdmin(), body(name), ifMatch = etag).ok()
                val node = written["username"]
                (node == null || node.isNull) shouldBe name.isNullOrBlank()
            }
        }
}
