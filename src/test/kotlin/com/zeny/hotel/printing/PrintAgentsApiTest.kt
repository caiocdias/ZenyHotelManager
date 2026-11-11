package com.zeny.hotel.printing

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

/** A administração de agentes de impressão pela API (M5 E10, Task 6). */
@IntegrationTest
@Import(IamTestConfiguration::class)
class PrintAgentsApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val mockMvc: MockMvc,
) {
    private val path = "/api/v1/print-agents"

    private fun userWithTemplate(template: RoleTemplate) =
        iam.createUser().also {
            val roleId =
                jdbc
                    .sql("SELECT id FROM iam.role WHERE template_key = :key")
                    .param("key", template.name)
                    .query(UUID::class.java)
                    .single()
            iam.assignRole(it, RoleId(roleId))
        }

    @Test
    fun `creating an agent is 201 with the token once, no-store, and the reads never show it`(): Unit =
        with(iam) {
            val response = call(POST, path, asAdmin(), mapOf("name" to "Agente recepção"))

            val created = response.expectStatus(201)!!
            val token = created["token"].asString()
            token.startsWith("zpa_") shouldBe true
            created["name"].asString() shouldBe "Agente recepção"
            created["active"].asBoolean() shouldBe true
            response.getHeader(HttpHeaders.CACHE_CONTROL) shouldBe "no-store"
            val id = created["id"].asString()

            val read = call(GET, "$path/$id", asAdmin())
            read.ok()["name"].asString() shouldBe "Agente recepção"
            read.contentAsString shouldNotContain token
            read.contentAsString shouldNotContain "token"
            read.contentAsString shouldNotContain "hash"

            val list = call(GET, path, asAdmin())
            list.contentAsString shouldNotContain token
            list.contentAsString shouldNotContain "Hash"
            list.ok().values().map { it["id"].asString() } shouldContain id
        }

    @Test
    fun `rotating returns a new token, no-store, and the old one is gone`(): Unit =
        with(iam) {
            val created = call(POST, path, asAdmin(), mapOf("name" to "Agente rotação")).expectStatus(201)!!
            val id = created["id"].asString()

            val rotated = call(POST, "$path/$id/rotate-token", asAdmin())

            val body = rotated.ok()
            body["token"].asString().startsWith("zpa_") shouldBe true
            (body["token"].asString() == created["token"].asString()) shouldBe false
            rotated.getHeader(HttpHeaders.CACHE_CONTROL) shouldBe "no-store"
        }

    @Test
    fun `deactivating revokes and rotating an inactive agent is 422`(): Unit =
        with(iam) {
            val id = call(POST, path, asAdmin(), mapOf("name" to "Agente saída")).expectStatus(201)!!["id"].asString()

            val deactivated = call(POST, "$path/$id/deactivate", asAdmin()).ok()

            deactivated["active"].asBoolean() shouldBe false
            call(POST, "$path/$id/rotate-token", asAdmin()).problem(422, "PRINT_AGENT_INACTIVE")
            call(GET, "$path/$id", asAdmin()).ok()["active"].asBoolean() shouldBe false
        }

    @Test
    fun `an unknown agent is 404 and a bad name is 400 on the field, never 500`(): Unit =
        with(iam) {
            val missing = UUID.randomUUID()
            call(GET, "$path/$missing", asAdmin()).problem(404, "PRINT_AGENT_NOT_FOUND")
            call(POST, "$path/$missing/rotate-token", asAdmin()).problem(404, "PRINT_AGENT_NOT_FOUND")
            call(POST, "$path/$missing/deactivate", asAdmin()).problem(404, "PRINT_AGENT_NOT_FOUND")

            listOf("", "   ", "a".repeat(101), "ab${0x200B.toChar()}cd", "ab\ncd").forEach { name ->
                val problem = call(POST, path, asAdmin(), mapOf("name" to name)).problem(400, "VALIDATION_FAILED")
                problem["errors"].values().map { it["field"].asString() } shouldBe listOf("name")
            }
            call(POST, path, asAdmin(), emptyMap<String, String>()).expectStatus(400)
        }

    @Test
    fun `the real manager and receptionist are forbidden on every agent route`(): Unit =
        with(iam) {
            val id =
                call(
                    POST,
                    path,
                    asAdmin(),
                    mapOf("name" to "Agente protegido"),
                ).expectStatus(201)!!["id"].asString()
            listOf(RoleTemplate.MANAGER, RoleTemplate.RECEPTIONIST).forEach { template ->
                val user = userWithTemplate(template)
                call(POST, path, asTestUser(user), mapOf("name" to "Intruso")).problem(403, "PERMISSION_DENIED")
                call(GET, path, asTestUser(user)).problem(403, "PERMISSION_DENIED")
                call(GET, "$path/$id", asTestUser(user)).problem(403, "PERMISSION_DENIED")
                call(POST, "$path/$id/rotate-token", asTestUser(user)).problem(403, "PERMISSION_DENIED")
                call(POST, "$path/$id/deactivate", asTestUser(user)).problem(403, "PERMISSION_DENIED")
            }
            call(GET, "$path/$id", asAdmin()).ok()["active"].asBoolean() shouldBe true
        }

    @Test
    fun `anonymous callers are unauthenticated`() {
        mockMvc
            .get(path)
            .andReturn()
            .response.status shouldBe 401
        mockMvc
            .post(path)
            .andReturn()
            .response.status shouldBe 401
        mockMvc
            .get("$path/${UUID.randomUUID()}")
            .andReturn()
            .response.status shouldBe 401
    }
}
