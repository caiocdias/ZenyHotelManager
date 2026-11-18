package com.zeny.hotel.housekeeping

import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.CLEANING_TYPES
import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.definition
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Permissões e auditoria do `housekeeping` (M6 E01, Task 1): `housekeeping.config.read` e `housekeeping.config.manage`,
 * só de escopo `ALL`, não sensíveis, nos modelos Gerente e Governanta; a autorização antes de qualquer recusa que
 * revele estado; e a trilha de cada escrita.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class HousekeepingPermissionsTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val json: JsonMapper,
) {
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)

    @Test
    fun `the catalog has the two permissions, ALL only and not sensitive, in the manager and housekeeper templates`() {
        HousekeepingPermissions.CONFIG_READ.value shouldBe "housekeeping.config.read"
        HousekeepingPermissions.CONFIG_MANAGE.value shouldBe "housekeeping.config.manage"
        HousekeepingPermissions.permissions.map { it.code.value } shouldContainExactlyInAnyOrder
            listOf("housekeeping.config.read", "housekeeping.config.manage")
        HousekeepingPermissions.permissions.forEach {
            withClue(it.code.value) {
                it.scopes shouldBe setOf(ALL)
                it.sensitive shouldBe false
            }
        }

        listOf("housekeeping.config.read", "housekeeping.config.manage").forEach { code ->
            withClue(code) {
                jdbc
                    .sql("SELECT sensitive, module FROM iam.permission WHERE code = :code AND obsolete_at IS NULL")
                    .param("code", code)
                    .query { rs, _ -> rs.getBoolean("sensitive") to rs.getString("module") }
                    .single() shouldBe (false to "housekeeping")
                templatesOf(code) shouldContainExactlyInAnyOrder
                    listOf("ADMINISTRATOR" to "ALL", "MANAGER" to "ALL", "HOUSEKEEPER" to "ALL")
            }
        }
    }

    @Test
    fun `without manage the writes are 403, before any not found, conflict or unknown reference`(): Unit =
        with(iam) {
            val reader = asTestUser(createUserWith(TestGrant(definition(HousekeepingPermissions.CONFIG_READ), ALL)))
            val existing = housekeeping.createCleaningType()
            val existingCode = admin(GET, "$CLEANING_TYPES/$existing")["code"].asString()
            val missing = UUID.randomUUID()
            val unknownRoomType = housekeeping.cleaningTypeBody(credits = listOf(housekeeping.credit(missing, 1)))

            call(POST, CLEANING_TYPES, reader, housekeeping.cleaningTypeBody()).problem(403, "PERMISSION_DENIED")
            call(POST, CLEANING_TYPES, reader, housekeeping.cleaningTypeBody(code = existingCode))
                .problem(403, "PERMISSION_DENIED")
            call(POST, CLEANING_TYPES, reader, unknownRoomType).problem(403, "PERMISSION_DENIED")
            call(PUT, "$CLEANING_TYPES/$missing", reader, housekeeping.cleaningTypeBody(), ifMatch = "\"0\"")
                .problem(403, "PERMISSION_DENIED")
            call(PUT, "$CLEANING_TYPES/$existing", reader, unknownRoomType, ifMatch = "\"7\"")
                .problem(403, "PERMISSION_DENIED")
            call(POST, "$CLEANING_TYPES/$missing/deactivate", reader).problem(403, "PERMISSION_DENIED")
            call(POST, "$CLEANING_TYPES/$existing/deactivate", reader).problem(403, "PERMISSION_DENIED")
            call(POST, "$CLEANING_TYPES/$missing/reactivate", reader).problem(403, "PERMISSION_DENIED")
            call(POST, "$CLEANING_TYPES/$existing/reactivate", reader).problem(403, "PERMISSION_DENIED")

            // O leitor lê.
            call(GET, "$CLEANING_TYPES/$existing", reader).ok()["id"].asString() shouldBe existing.toString()
            call(GET, CLEANING_TYPES, reader).ok()
        }

    @Test
    fun `without read the reads are 403, before any not found`(): Unit =
        with(iam) {
            val manager = asTestUser(createUserWith(TestGrant(definition(HousekeepingPermissions.CONFIG_MANAGE), ALL)))
            val nobody = asTestUser(createUser())
            val existing = housekeeping.createCleaningType()

            listOf(manager, nobody).forEach { user ->
                call(GET, CLEANING_TYPES, user).problem(403, "PERMISSION_DENIED")
                call(GET, "$CLEANING_TYPES/$existing", user).problem(403, "PERMISSION_DENIED")
                call(GET, "$CLEANING_TYPES/${UUID.randomUUID()}", user).problem(403, "PERMISSION_DENIED")
            }
            // Quem só mantém escreve.
            call(POST, CLEANING_TYPES, manager, housekeeping.cleaningTypeBody()).expectStatus(201)
        }

    @Test
    fun `each write records its action in the audit trail with the configuration snapshot`(): Unit =
        with(iam) {
            val roomType = housekeeping.rooms.createRoomType()
            val id = housekeeping.createCleaningType()
            val path = "$CLEANING_TYPES/$id"
            val edited =
                housekeeping.cleaningTypeBody(
                    defaultPriority = 1,
                    credits = listOf(housekeeping.credit(roomType, 5)),
                )
            call(PUT, path, asAdmin(), edited, ifMatch = "\"0\"").ok()
            call(POST, "$path/deactivate", asAdmin()).ok()
            call(POST, "$path/reactivate", asAdmin()).ok()

            housekeeping.auditActions("housekeeping.cleaning-type", id) shouldContainExactly
                listOf(
                    "housekeeping.cleaning-type.create",
                    "housekeeping.cleaning-type.update",
                    "housekeeping.cleaning-type.deactivate",
                    "housekeeping.cleaning-type.reactivate",
                )
            val after =
                jdbc
                    .sql(
                        "SELECT after_state::text FROM audit.audit_entry WHERE entity_type = :type AND " +
                            "entity_id = :id AND action = 'housekeeping.cleaning-type.update'",
                    ).param("type", "housekeeping.cleaning-type")
                    .param("id", id.toString())
                    .query(String::class.java)
                    .single()
            val state = json.readTree(after)
            state["defaultPriority"].asInt() shouldBe 1
            state["credits"][roomType.toString()].asInt() shouldBe 5
        }

    private fun IamTestSupport.admin(
        method: HttpMethod,
        path: String,
    ) = call(method, path, asAdmin()).ok()

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
