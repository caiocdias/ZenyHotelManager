package com.zeny.hotel.housekeeping

import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.CLEANING_TYPES
import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.SCHEDULED_CLEANINGS
import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.definition
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant
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
 * Permissões e auditoria do `housekeeping` (M6 E01, Tasks 1 e 3): `housekeeping.config.read` e
 * `housekeeping.config.manage`, só de escopo `ALL`, não sensíveis, nos modelos Gerente e Governanta; a autorização
 * antes de qualquer recusa que revele estado, nos tipos de limpeza e nas faxinas programadas; e a trilha de cada
 * escrita.
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
    fun `the catalog has the ten permissions, not sensitive, config for manager and housekeeper`() {
        HousekeepingPermissions.CONFIG_READ.value shouldBe "housekeeping.config.read"
        HousekeepingPermissions.CONFIG_MANAGE.value shouldBe "housekeeping.config.manage"
        // As permissões das tarefas (E02) estão no HousekeepingTaskPermissionsTest; a da distribuição (E03), no
        // AssignmentPlanPermissionsTest; a da execução (E04), no HousekeepingTaskExecutionPermissionsTest.
        HousekeepingPermissions.permissions.map { it.code.value } shouldContainExactlyInAnyOrder
            listOf(
                "housekeeping.config.read",
                "housekeeping.config.manage",
                "housekeeping.tasks.read",
                "housekeeping.tasks.request",
                "housekeeping.assignments.manage",
                "housekeeping.tasks.execute",
                "housekeeping.inspections.perform",
                "housekeeping.occurrences.report",
                "housekeeping.lostfound.manage",
                "housekeeping.damages.manage",
            )
        HousekeepingPermissions.permissions.forEach {
            withClue(it.code.value) {
                // Só a execução (E04, D1) e o registro de ocorrências (E06, D1) têm dono, com OWN.
                val owned =
                    it.code == HousekeepingPermissions.TASKS_EXECUTE ||
                        it.code == HousekeepingPermissions.OCCURRENCES_REPORT
                it.scopes shouldBe if (owned) setOf(OWN, ALL) else setOf(ALL)
                it.sensitive shouldBe false
            }
        }
        definition(HousekeepingPermissions.TASKS_EXECUTE).templates shouldBe
            mapOf(
                RoleTemplate.MANAGER to TemplateGrant(ALL),
                RoleTemplate.HOUSEKEEPER to TemplateGrant(ALL),
                RoleTemplate.ROOM_ATTENDANT to TemplateGrant(OWN),
            )

        definition(HousekeepingPermissions.INSPECTIONS_PERFORM).templates shouldBe
            mapOf(
                RoleTemplate.MANAGER to TemplateGrant(ALL),
                RoleTemplate.HOUSEKEEPER to TemplateGrant(ALL),
            )

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
    fun `the occurrence permissions go to the reporting and managing templates, the attendant only with OWN`() {
        HousekeepingPermissions.OCCURRENCES_REPORT.value shouldBe "housekeeping.occurrences.report"
        HousekeepingPermissions.LOSTFOUND_MANAGE.value shouldBe "housekeeping.lostfound.manage"
        HousekeepingPermissions.DAMAGES_MANAGE.value shouldBe "housekeeping.damages.manage"
        definition(HousekeepingPermissions.OCCURRENCES_REPORT).templates shouldBe
            mapOf(
                RoleTemplate.MANAGER to TemplateGrant(ALL),
                RoleTemplate.HOUSEKEEPER to TemplateGrant(ALL),
                RoleTemplate.RECEPTIONIST to TemplateGrant(ALL),
                RoleTemplate.ROOM_ATTENDANT to TemplateGrant(OWN),
            )

        definition(HousekeepingPermissions.LOSTFOUND_MANAGE).templates shouldBe
            mapOf(
                RoleTemplate.MANAGER to TemplateGrant(ALL),
                RoleTemplate.HOUSEKEEPER to TemplateGrant(ALL),
                RoleTemplate.RECEPTIONIST to TemplateGrant(ALL),
            )

        definition(HousekeepingPermissions.DAMAGES_MANAGE).templates shouldBe
            mapOf(
                RoleTemplate.MANAGER to TemplateGrant(ALL),
                RoleTemplate.RECEPTIONIST to TemplateGrant(ALL),
            )
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

    @Test
    fun `without manage the schedule writes are 403, before any 400, 404, 409 or 422`(): Unit =
        with(iam) {
            val reader = asTestUser(createUserWith(TestGrant(definition(HousekeepingPermissions.CONFIG_READ), ALL)))
            val type = housekeeping.createCleaningType()
            val room = housekeeping.createRoom()
            val existing = housekeeping.createSchedule(housekeeping.scheduleBody(type, roomId = room))
            val inactiveType = housekeeping.createCleaningType()
            call(POST, "$CLEANING_TYPES/$inactiveType/deactivate", asAdmin()).ok()
            val missing = UUID.randomUUID()
            val refusedBodies =
                listOf(
                    housekeeping.scheduleBody(type, roomId = missing), // 400 UNKNOWN_LOCATION
                    housekeeping.scheduleBody(missing, roomId = housekeeping.createRoom()), // 400 UNKNOWN_CLEANING_TYPE
                    housekeeping.scheduleBody(type), // 400 LOCATION_INVALID
                    housekeeping.scheduleBody(type, roomId = room, lastDoneOn = "9999-01-01"), // 422 LAST_DONE
                    housekeeping.scheduleBody(inactiveType, roomId = housekeeping.createRoom()), // 422 INACTIVE
                    housekeeping.scheduleBody(type, roomId = room), // 409 ALREADY_EXISTS
                )
            refusedBodies.forEach { body ->
                withClue(body) { call(POST, SCHEDULED_CLEANINGS, reader, body).problem(403, "PERMISSION_DENIED") }
            }
            val edit = mapOf("cleaningTypeId" to missing, "frequencyDays" to 7)
            call(PUT, "$SCHEDULED_CLEANINGS/$missing", reader, edit, ifMatch = "\"0\"")
                .problem(403, "PERMISSION_DENIED")
            call(PUT, "$SCHEDULED_CLEANINGS/$existing", reader, edit, ifMatch = "\"7\"")
                .problem(403, "PERMISSION_DENIED")
            listOf(missing, existing).forEach { id ->
                call(POST, "$SCHEDULED_CLEANINGS/$id/deactivate", reader).problem(403, "PERMISSION_DENIED")
                call(POST, "$SCHEDULED_CLEANINGS/$id/reactivate", reader).problem(403, "PERMISSION_DENIED")
            }
            // A desativação do tipo com faxina ativa (422) também vem depois da permissão.
            call(POST, "$CLEANING_TYPES/$type/deactivate", reader).problem(403, "PERMISSION_DENIED")

            // O leitor lê.
            call(GET, "$SCHEDULED_CLEANINGS/$existing", reader).ok()["id"].asString() shouldBe existing.toString()
            call(GET, SCHEDULED_CLEANINGS, reader).ok()
        }

    @Test
    fun `without read the schedule reads are 403, before any not found`(): Unit =
        with(iam) {
            val manager = asTestUser(createUserWith(TestGrant(definition(HousekeepingPermissions.CONFIG_MANAGE), ALL)))
            val nobody = asTestUser(createUser())
            val existing = housekeeping.createSchedule()

            listOf(manager, nobody).forEach { user ->
                call(GET, SCHEDULED_CLEANINGS, user).problem(403, "PERMISSION_DENIED")
                call(GET, "$SCHEDULED_CLEANINGS/$existing", user).problem(403, "PERMISSION_DENIED")
                call(GET, "$SCHEDULED_CLEANINGS/${UUID.randomUUID()}", user).problem(403, "PERMISSION_DENIED")
            }
            // Quem só mantém escreve.
            val body = housekeeping.scheduleBody(housekeeping.createCleaningType(), roomId = housekeeping.createRoom())
            call(POST, SCHEDULED_CLEANINGS, manager, body).expectStatus(201)
        }

    @Test
    fun `each schedule write records its action in the audit trail with the configuration snapshot`(): Unit =
        with(iam) {
            val type = housekeeping.createCleaningType()
            val other = housekeeping.createCleaningType()
            val id = housekeeping.createSchedule(housekeeping.scheduleBody(type, roomId = housekeeping.createRoom()))
            val path = "$SCHEDULED_CLEANINGS/$id"
            call(PUT, path, asAdmin(), mapOf("cleaningTypeId" to other, "frequencyDays" to 21), ifMatch = "\"0\"").ok()
            call(POST, "$path/deactivate", asAdmin()).ok()
            call(POST, "$path/reactivate", asAdmin()).ok()

            housekeeping.auditActions("housekeeping.scheduled-cleaning", id) shouldContainExactly
                listOf(
                    "housekeeping.scheduled-cleaning.create",
                    "housekeeping.scheduled-cleaning.update",
                    "housekeeping.scheduled-cleaning.deactivate",
                    "housekeeping.scheduled-cleaning.reactivate",
                )
            val after =
                jdbc
                    .sql(
                        "SELECT after_state::text FROM audit.audit_entry WHERE entity_type = :type AND " +
                            "entity_id = :id AND action = 'housekeeping.scheduled-cleaning.update'",
                    ).param("type", "housekeeping.scheduled-cleaning")
                    .param("id", id.toString())
                    .query(String::class.java)
                    .single()
            val state = json.readTree(after)
            state["cleaningTypeId"].asString() shouldBe other.toString()
            state["frequencyDays"].asInt() shouldBe 21
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
