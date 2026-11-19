package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.definition
import com.zeny.hotel.housekeeping.LostAndFoundTestSupport.Companion.ITEMS
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.LocalTime
import java.util.UUID

/**
 * As permissões das ocorrências da governança (M6 E06, Task 1, D1): `housekeeping.occurrences.report` (`OWN` e `ALL`),
 * `housekeeping.lostfound.manage` e `housekeeping.damages.manage` (só `ALL`), nenhuma sensível, nos modelos da D1. Sem
 * a permissão, o registro, a leitura e as ações de destino do achado dão `403` antes de `404`, `409` e `412`; com ela,
 * as recusas de estado.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class HousekeepingOccurrencePermissionsTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired staffApi: StaffApi,
) {
    private val support = LostAndFoundTestSupport(iam, jdbc)
    private val execution = HousekeepingExecutionTestSupport(iam, jdbc, staffApi)

    @Test
    fun `the three occurrence permissions, not sensitive, with the templates of D1`() {
        val report = definition(HousekeepingPermissions.OCCURRENCES_REPORT)
        report.code.value shouldBe "housekeeping.occurrences.report"
        report.scopes shouldBe setOf(OWN, ALL)
        report.templates shouldBe
            mapOf(
                RoleTemplate.MANAGER to TemplateGrant(ALL),
                RoleTemplate.HOUSEKEEPER to TemplateGrant(ALL),
                RoleTemplate.RECEPTIONIST to TemplateGrant(ALL),
                RoleTemplate.ROOM_ATTENDANT to TemplateGrant(OWN),
            )
        val lostFound = definition(HousekeepingPermissions.LOSTFOUND_MANAGE)
        lostFound.code.value shouldBe "housekeeping.lostfound.manage"
        lostFound.scopes shouldBe setOf(ALL)
        lostFound.templates shouldBe
            mapOf(
                RoleTemplate.MANAGER to TemplateGrant(ALL),
                RoleTemplate.HOUSEKEEPER to TemplateGrant(ALL),
                RoleTemplate.RECEPTIONIST to TemplateGrant(ALL),
            )
        val damages = definition(HousekeepingPermissions.DAMAGES_MANAGE)
        damages.code.value shouldBe "housekeeping.damages.manage"
        damages.scopes shouldBe setOf(ALL)
        damages.templates shouldBe
            mapOf(RoleTemplate.MANAGER to TemplateGrant(ALL), RoleTemplate.RECEPTIONIST to TemplateGrant(ALL))

        mapOf(
            report.code.value to
                listOf(
                    "ADMINISTRATOR" to "ALL",
                    "MANAGER" to "ALL",
                    "HOUSEKEEPER" to "ALL",
                    "RECEPTIONIST" to "ALL",
                    "ROOM_ATTENDANT" to "OWN",
                ),
            lostFound.code.value to
                listOf("ADMINISTRATOR" to "ALL", "MANAGER" to "ALL", "HOUSEKEEPER" to "ALL", "RECEPTIONIST" to "ALL"),
            damages.code.value to listOf("ADMINISTRATOR" to "ALL", "MANAGER" to "ALL", "RECEPTIONIST" to "ALL"),
        ).forEach { (code, templates) ->
            withClue(code) {
                definition(PermissionCode(code)).sensitive shouldBe false
                jdbc
                    .sql("SELECT sensitive, module FROM iam.permission WHERE code = :code AND obsolete_at IS NULL")
                    .param("code", code)
                    .query { rs, _ -> rs.getBoolean("sensitive") to rs.getString("module") }
                    .single() shouldBe (false to "housekeeping")
                jdbc
                    .sql(
                        """
                        SELECT r.template_key, g.scope FROM iam.role r
                        JOIN iam.role_grant g ON g.role_id = r.id
                        WHERE r.template_key IS NOT NULL AND g.permission_code = :code
                        """.trimIndent(),
                    ).param("code", code)
                    .query { rs, _ -> rs.getString("template_key") to rs.getString("scope") }
                    .list() shouldContainExactlyInAnyOrder templates
            }
        }
    }

    @Test
    fun `without report the register and the reads are 403, before any 400, 404 or unknown location`(): Unit =
        with(iam) {
            val damagesOnly =
                asTestUser(createUserWith(TestGrant(definition(HousekeepingPermissions.DAMAGES_MANAGE), ALL)))
            val nobody = asTestUser(createUser())
            val existing = support.registered()

            listOf(damagesOnly, nobody).forEach { user ->
                support.register(user).problem(403, "PERMISSION_DENIED")
                support.register(user, support.body(roomId = UUID.randomUUID())).problem(403, "PERMISSION_DENIED")
                support.register(user, support.body()).problem(403, "PERMISSION_DENIED")
                call(GET, ITEMS, user).problem(403, "PERMISSION_DENIED")
                call(GET, "$ITEMS?expired=true", user).problem(403, "PERMISSION_DENIED")
                call(GET, "$ITEMS/$existing", user).problem(403, "PERMISSION_DENIED")
                call(GET, "$ITEMS/${UUID.randomUUID()}", user).problem(403, "PERMISSION_DENIED")
            }
        }

    @Test
    fun `who only manages lost and found reads all items`(): Unit =
        with(iam) {
            val manager =
                asTestUser(createUserWith(TestGrant(definition(HousekeepingPermissions.LOSTFOUND_MANAGE), ALL)))
            val existing = support.registered()
            call(GET, "$ITEMS/$existing", manager).ok()["id"].asString() shouldBe existing.toString()
            call(GET, ITEMS, manager).ok()
            support.register(manager).problem(403, "PERMISSION_DENIED")
        }

    @Test
    fun `without manage return, discard and donate are 403 before 404, 409 and 412, even for who found it`(): Unit =
        with(iam) {
            val attendant = execution.userWithTemplate(RoleTemplate.ROOM_ATTENDANT)
            val finder = asTestUser(attendant)
            val reporter =
                asTestUser(createUserWith(TestGrant(definition(HousekeepingPermissions.OCCURRENCES_REPORT), ALL)))
            val fresh = support.registered(finder)
            val returned = support.registered(finder)
            support.act(returned, "return", asAdmin(), mapOf("returnedTo" to "Dono")).ok()
            val expired = support.registered(finder)
            support.foundAt(expired, expiredFoundAt())
            val missing = UUID.randomUUID()

            listOf(finder, reporter).forEach { user ->
                ACTIONS.forEach { (action, body) ->
                    withClue(action) {
                        support.act(fresh, action, user, body).problem(403, "PERMISSION_DENIED")
                        support.act(fresh, action, user, body, "\"999\"").problem(403, "PERMISSION_DENIED")
                        support.act(returned, action, user, body).problem(403, "PERMISSION_DENIED")
                        support.act(expired, action, user, body).problem(403, "PERMISSION_DENIED")
                        support.act(missing, action, user, body, "\"0\"").problem(403, "PERMISSION_DENIED")
                    }
                }
            }
            listOf(fresh, expired).forEach { support.item(it)["status"].asString() shouldBe "STORED" }
        }

    @Test
    fun `with manage the state refusals come out - 404, 409, 412 and 428`(): Unit =
        with(iam) {
            val reception = asTestUser(execution.userWithTemplate(RoleTemplate.RECEPTIONIST))
            val fresh = support.registered()
            val returned = support.registered()
            support.act(returned, "return", asAdmin(), mapOf("returnedTo" to "Dono")).ok()

            ACTIONS.forEach { (action, body) ->
                withClue(action) {
                    support
                        .act(UUID.randomUUID(), action, reception, body, "\"0\"")
                        .problem(404, "HOUSEKEEPING_LOST_FOUND_NOT_FOUND")
                    support
                        .act(returned, action, reception, body)
                        .problem(409, "HOUSEKEEPING_LOST_FOUND_TRANSITION_NOT_ALLOWED")
                    support.act(fresh, action, reception, body, "\"999\"").status shouldBe 412
                    support.act(fresh, action, reception, body, null).status shouldBe 428
                }
            }
        }

    private fun expiredFoundAt() =
        hotel
            .currentBusinessDate()
            .minusDays(hotel.operationalSettings().lostAndFoundRetentionDays.toLong())
            .atTime(LocalTime.NOON)
            .atZone(hotel.timezone())
            .toInstant()

    private companion object {
        val ACTIONS =
            listOf(
                "return" to mapOf("returnedTo" to "Alguém"),
                "discard" to mapOf("reason" to "Vencido"),
                "donate" to mapOf("reason" to "Vencido"),
            )
    }
}
