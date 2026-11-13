package com.zeny.hotel.endofday

import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.TestAdmin
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Catálogo de permissões do `endofday` (M6 E10, Task 2): sensibilidade, escopo e modelos sugeridos. */
@IntegrationTest
class EndOfDayPermissionsTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val json: JsonMapper,
) {
    @Test
    fun `the catalog lists the two end of day permissions with their sensitivity`() {
        val items = catalog().filter { it["code"].asString().startsWith("endofday.") }

        items.map { it["code"].asString() } shouldContainExactlyInAnyOrder
            listOf("endofday.runs.read", "endofday.runs.execute")
        val read = items.single { it["code"].asString() == "endofday.runs.read" }
        val execute = items.single { it["code"].asString() == "endofday.runs.execute" }
        read["sensitive"].asBoolean() shouldBe false
        execute["sensitive"].asBoolean() shouldBe true
        listOf(read, execute).forEach {
            it["module"].asString() shouldBe "endofday"
            it["scopes"].values().map { scope -> scope.asString() } shouldBe listOf("ALL")
        }
        read["description"].asString() shouldBe "Acompanhar o fechamento do dia: execuções, etapas e pendências"
        execute["description"].asString() shouldBe "Executar ou retomar o fechamento do dia manualmente"
    }

    @Test
    fun `the suggested role templates are manager, receptionist and finance for read and manager for execute`() {
        templatesOf("endofday.runs.read") shouldContainExactlyInAnyOrder
            listOf("ADMINISTRATOR" to "ALL", "MANAGER" to "ALL", "RECEPTIONIST" to "ALL", "FINANCE" to "ALL")
        templatesOf("endofday.runs.execute") shouldContainExactlyInAnyOrder
            listOf("ADMINISTRATOR" to "ALL", "MANAGER" to "ALL")
    }

    private fun catalog(): List<JsonNode> {
        val response =
            mockMvc
                .get("/api/v1/permissions") {
                    with(asUser(adminId()))
                    param("limit", "200")
                }.andReturn()
                .response
        withClue(response.contentAsString) { response.status shouldBe 200 }
        return json.readTree(response.contentAsString)["items"].values().toList()
    }

    private fun templatesOf(code: String): List<Pair<String, String>> =
        jdbc
            .sql(
                """
                SELECT r.template_key, g.scope FROM iam.role r
                JOIN iam.role_grant g ON g.role_id = r.id
                WHERE r.template_key IS NOT NULL AND g.permission_code = :code
                """.trimIndent(),
            ).param("code", code)
            .query { rs, _ -> rs.getString("template_key") to rs.getString("scope") }
            .list()

    private fun adminId(): UUID =
        jdbc
            .sql("SELECT id FROM iam.user_account WHERE login = :login")
            .param("login", TestAdmin.LOGIN)
            .query(UUID::class.java)
            .single()
}
