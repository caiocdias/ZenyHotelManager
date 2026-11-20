package com.zeny.hotel.maintenance

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.InventoryListenersQuiescence
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/**
 * O ataque à fonte offline das ordens (M6 E08, D4): os valores de `number`, `category`, `priority` e `dueAt` são os da
 * ordem; a nota de conclusão, as observações, o motivo da espera e a descrição de uma ordem final nunca viajam (a
 * ordem final vai como *tombstone* sem dados); o técnico `OWN` sem colaborador vinculado recebe só as que abriu.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class MaintenanceOfflineFeedAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired staffApi: StaffApi,
) {
    private val support = MaintenanceTestSupport(iam, jdbc, staffApi)

    private fun technician(withEmployee: Boolean) =
        iam
            .createUserWith(
                TestGrant(MaintenancePermissions.permissions.single { it.code == MaintenancePermissions.READ }, OWN),
                TestGrant(MaintenancePermissions.permissions.single { it.code == MaintenancePermissions.MANAGE }, OWN),
            ).let { user ->
                Triple(user, if (withEmployee) support.employee(userId = user.id.value) else null, iam.asTestUser(user))
            }

    private fun download(
        auth: RequestPostProcessor,
        from: String? = null,
    ): Pair<List<JsonNode>, String> =
        with(iam) {
            val changes = mutableListOf<JsonNode>()
            var cursor = from
            val raw = StringBuilder()
            do {
                InventoryListenersQuiescence.await(jdbc)
                val response =
                    call(GET, "/api/v1/sync/changes?limit=200" + cursor?.let { "&cursor=$it" }.orEmpty(), auth)
                val page = response.ok()
                raw.append(response.getContentAsString(Charsets.UTF_8))
                changes += page["changes"].values()
                cursor = page["cursor"].asString()
            } while (page["hasMore"].asBoolean())
            changes
                .filter { it["source"].asString() == MaintenanceOffline.WORK_ORDERS.name }
                .also { lastRaw = raw.toString() } to checkNotNull(cursor)
        }

    private var lastRaw = ""

    @Test
    fun `the item carries the number, the category, the priority and the due date of the order`() {
        val tech = technician(withEmployee = true)
        val room = support.room()
        val id =
            support.openedId(
                body =
                    support.body(
                        roomId = room,
                        category = "ELECTRICAL",
                        priority = "HIGH",
                        description = "Tomada solta",
                        dueAt = "2031-05-06T07:08:09Z",
                    ),
            )
        with(iam) {
            support.update(id, support.updateBody(support.order(id), "assigneeEmployeeId" to tech.second)).ok()
        }
        val online = support.order(id)

        val item = download(tech.third).first.single { it["id"].asString() == id.toString() }["data"]

        assertSoftly {
            item["number"].asString() shouldBe online["number"].asString()
            item["category"].asString() shouldBe "ELECTRICAL"
            item["priority"].asString() shouldBe "HIGH"
            item["status"].asString() shouldBe "OPEN"
            Instant.parse(item["dueAt"].asString()) shouldBe Instant.parse("2031-05-06T07:08:09Z")
            item["description"].asString() shouldBe "Tomada solta"
        }
    }

    @Test
    fun `completion notes, observations and the description of a final order never reach the device`() {
        val tech = technician(withEmployee = true)
        val open = support.openedId(body = support.body(roomId = support.room(), description = "Descricao reservada"))
        val finished = support.openedId(body = support.body(roomId = support.room(), description = "Outra reservada"))
        listOf(open, finished).forEach {
            with(
                iam,
            ) { support.update(it, support.updateBody(support.order(it), "assigneeEmployeeId" to tech.second)).ok() }
        }
        val first = download(tech.third)
        with(iam) {
            call(
                POST,
                "/api/v1/work-orders/$open/notes",
                asAdmin(),
                mapOf("text" to "Observacao sigilosa"),
            ).expectStatus(201)
            call(
                POST,
                "/api/v1/work-orders/$finished/notes",
                asAdmin(),
                mapOf("text" to "Observacao final"),
            ).expectStatus(201)
            support.act(finished, "complete", body = mapOf("notes" to "Feito", "thirdPartyCost" to "9.00")).ok()
        }

        val second = download(tech.third, first.second)
        val bodies = lastRaw
        val full = download(tech.third).also { }
        val everything = lastRaw

        assertSoftly {
            second.first.map { it["id"].asString() to it["deleted"].asBoolean() } shouldContainExactly
                listOf(finished.toString() to true)
            second.first.single { it["id"].asString() == finished.toString() }["data"].let {
                (it == null || it.isNull) shouldBe true
            }
            listOf(bodies, everything).forEach {
                it shouldNotContain "Feito"
                it shouldNotContain "Observacao"
                it shouldNotContain "thirdPartyCost"
                it shouldNotContain "Outra reservada"
            }
            full.first.none { it["id"].asString() == finished.toString() && !it["deleted"].asBoolean() } shouldBe true
        }
    }

    @Test
    fun `an OWN technician with no linked employee receives only the orders he opened`() {
        val tech = technician(withEmployee = false)
        val opened = support.openedId(body = support.body(roomId = support.room()))
        jdbc
            .sql("UPDATE maintenance.work_order SET opened_by = :u WHERE id = :id")
            .param("u", tech.first.id.value)
            .param("id", opened)
            .update() shouldBe 1
        val unassigned = support.openedId(body = support.body(roomId = support.room()))

        val ids = download(tech.third).first.map { UUID.fromString(it["id"].asString()) }

        assertSoftly {
            ids shouldContainExactly listOf(opened)
            ids.contains(unassigned) shouldBe false
        }
    }
}
