package com.zeny.hotel.printing

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/** A administração de impressoras pela API (M5 E10, Task 6). */
@IntegrationTest
@Import(IamTestConfiguration::class)
class PrintersApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
) {
    private val path = "/api/v1/printers"

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

    private fun newAgent(name: String = "Agente"): String =
        with(
            iam,
        ) { call(POST, "/api/v1/print-agents", asAdmin(), mapOf("name" to name)).expectStatus(201)!!["id"].asString() }

    private fun body(
        agentId: String,
        name: String = "Balcão",
        kind: String = "THERMAL_ESCPOS",
        address: String = "tcp://10.0.0.5:9100",
    ) = mapOf("name" to name, "kind" to kind, "address" to address, "agentId" to agentId)

    @Test
    fun `creating a printer is 201 with an ETag and the reads show it`(): Unit =
        with(iam) {
            val agentId = newAgent()

            val response = call(POST, path, asAdmin(), body(agentId))

            val created = response.expectStatus(201)!!
            created["kind"].asString() shouldBe "THERMAL_ESCPOS"
            created["agentId"].asString() shouldBe agentId
            created["active"].asBoolean() shouldBe true
            val id = created["id"].asString()
            val read = call(GET, "$path/$id", asAdmin())
            read.ok()["address"].asString() shouldBe "tcp://10.0.0.5:9100"
            read.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
            call(GET, path, asAdmin()).ok().values().map { it["id"].asString() } shouldContain id
        }

    @Test
    fun `a PUT needs If-Match, 428 without it and 412 with a stale one, and changes name, address and agent`(): Unit =
        with(iam) {
            val agentId = newAgent()
            val otherAgent = newAgent("Outro")
            val id = call(POST, path, asAdmin(), body(agentId)).expectStatus(201)!!["id"].asString()
            val etag = call(GET, "$path/$id", asAdmin()).getHeader(HttpHeaders.ETAG)!!
            val change = mapOf("name" to "Balcão 2", "address" to "tcp://10.0.0.6:9100", "agentId" to otherAgent)

            call(PUT, "$path/$id", asAdmin(), change).problem(428, "PRECONDITION_REQUIRED")
            call(PUT, "$path/$id", asAdmin(), change, ifMatch = "\"999999\"").problem(412, "PRECONDITION_FAILED")
            val updated = call(PUT, "$path/$id", asAdmin(), change, ifMatch = etag)

            val json = updated.ok()
            json["name"].asString() shouldBe "Balcão 2"
            json["agentId"].asString() shouldBe otherAgent
            updated.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
            call(PUT, "$path/$id", asAdmin(), change, ifMatch = etag).problem(412, "PRECONDITION_FAILED")
        }

    @Test
    fun `a PUT with a kind in the body never changes the kind`(): Unit =
        with(iam) {
            val agentId = newAgent()
            val id = call(POST, path, asAdmin(), body(agentId)).expectStatus(201)!!["id"].asString()
            val etag = call(GET, "$path/$id", asAdmin()).getHeader(HttpHeaders.ETAG)!!

            val response =
                call(
                    PUT,
                    "$path/$id",
                    asAdmin(),
                    mapOf(
                        "name" to "Balcão",
                        "address" to "tcp://10.0.0.5:9100",
                        "agentId" to agentId,
                        "kind" to "A4_PDF",
                    ),
                    ifMatch = etag,
                )

            response.ok()["kind"].asString() shouldBe "THERMAL_ESCPOS"
            call(GET, "$path/$id", asAdmin()).ok()["kind"].asString() shouldBe "THERMAL_ESCPOS"
        }

    @Test
    fun `invalid name, address and kind are 400 on the field, never 500`(): Unit =
        with(iam) {
            val agentId = newAgent()
            val blank = 0x200B.toChar()
            val nameProblem =
                call(
                    POST,
                    path,
                    asAdmin(),
                    body(agentId, name = "a${blank}b"),
                ).problem(400, "VALIDATION_FAILED")
            nameProblem["errors"].values().map { it["field"].asString() } shouldBe listOf("name")
            listOf("", "with space", "a${blank}b", "x".repeat(256), "a\nb").forEach { address ->
                val problem =
                    call(POST, path, asAdmin(), body(agentId, address = address)).problem(400, "VALIDATION_FAILED")
                problem["errors"].values().map { it["field"].asString() } shouldBe listOf("address")
            }
            call(POST, path, asAdmin(), body(agentId, kind = "LASER")).expectStatus(400)
            call(POST, path, asAdmin(), body(agentId).minus("kind")).expectStatus(400)
        }

    @Test
    fun `an unknown agent is 400, an inactive one is 422, and deactivating a printer keeps the row`(): Unit =
        with(iam) {
            call(POST, path, asAdmin(), body(UUID.randomUUID().toString())).problem(400, "UNKNOWN_PRINT_AGENT")
            val agentId = newAgent()
            call(POST, "/api/v1/print-agents/$agentId/deactivate", asAdmin()).ok()
            call(POST, path, asAdmin(), body(agentId)).problem(422, "PRINT_AGENT_INACTIVE")

            val liveAgent = newAgent()
            val id = call(POST, path, asAdmin(), body(liveAgent)).expectStatus(201)!!["id"].asString()
            call(POST, "$path/$id/deactivate", asAdmin()).ok()["active"].asBoolean() shouldBe false
            call(GET, "$path/$id", asAdmin()).ok()["active"].asBoolean() shouldBe false
            call(GET, "$path/${UUID.randomUUID()}", asAdmin()).problem(404, "PRINTER_NOT_FOUND")
        }

    @Test
    fun `manager and receptionist read printers but cannot create, change or deactivate them`(): Unit =
        with(iam) {
            val agentId = newAgent()
            val id = call(POST, path, asAdmin(), body(agentId)).expectStatus(201)!!["id"].asString()
            val etag = call(GET, "$path/$id", asAdmin()).getHeader(HttpHeaders.ETAG)!!
            listOf(RoleTemplate.MANAGER, RoleTemplate.RECEPTIONIST).forEach { template ->
                val user = userWithTemplate(template)
                call(GET, path, asTestUser(user)).ok().values().map { it["id"].asString() } shouldContain id
                call(GET, "$path/$id", asTestUser(user)).ok()["id"].asString() shouldBe id
                call(POST, path, asTestUser(user), body(agentId)).problem(403, "PERMISSION_DENIED")
                call(
                    PUT,
                    "$path/$id",
                    asTestUser(user),
                    mapOf("name" to "x", "address" to "y", "agentId" to agentId),
                    ifMatch = etag,
                ).problem(403, "PERMISSION_DENIED")
                call(POST, "$path/$id/deactivate", asTestUser(user)).problem(403, "PERMISSION_DENIED")
            }
            call(GET, "$path/$id", asAdmin()).ok()["active"].asBoolean() shouldBe true
        }
}
