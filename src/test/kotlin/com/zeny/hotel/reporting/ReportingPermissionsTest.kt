package com.zeny.hotel.reporting

import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * O catálogo de permissões do `reporting` (M6 E09, D2): quatro códigos, só `ALL`, nenhum sensível, e o mapa modelo ->
 * permissão que o boot sincroniza em `iam.role_grant`.
 */
@IntegrationTest
class ReportingPermissionsTest(
    @Autowired private val jdbc: JdbcClient,
) {
    private val expected =
        mapOf(
            "reporting.dashboard.read" to
                listOf(RoleTemplate.MANAGER, RoleTemplate.RECEPTIONIST, RoleTemplate.HOUSEKEEPER),
            "reporting.housekeeping.read" to listOf(RoleTemplate.MANAGER, RoleTemplate.HOUSEKEEPER),
            "reporting.maintenance.read" to listOf(RoleTemplate.MANAGER, RoleTemplate.MAINTENANCE),
            "reporting.pending.read" to
                listOf(RoleTemplate.MANAGER, RoleTemplate.FINANCE, RoleTemplate.RECEPTIONIST),
        )

    @Test
    fun `the module declares exactly the four read permissions, ALL only and not sensitive`() {
        ReportingPermissions.permissions.map { it.code.value } shouldContainExactlyInAnyOrder expected.keys.toList()
        ReportingPermissions.permissions.forEach {
            withClue(it.code.value) {
                it.scopes shouldBe setOf(ALL)
                it.sensitive shouldBe false
            }
        }
    }

    @Test
    fun `the role templates in the database match the D2 map, plus the administrator`() {
        expected.forEach { (code, templates) ->
            withClue(code) {
                jdbc
                    .sql("SELECT sensitive, module FROM iam.permission WHERE code = :code AND obsolete_at IS NULL")
                    .param("code", code)
                    .query { rs, _ -> rs.getBoolean("sensitive") to rs.getString("module") }
                    .single() shouldBe (false to "reporting")
                templatesOf(code) shouldContainExactlyInAnyOrder
                    (templates.map { it.name } + "ADMINISTRATOR").map { it to "ALL" }
            }
        }
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
}
