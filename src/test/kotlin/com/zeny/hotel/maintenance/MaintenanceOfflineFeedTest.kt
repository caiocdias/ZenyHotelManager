package com.zeny.hotel.maintenance

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.InventoryListenersQuiescence
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * A fonte offline `maintenance-work-orders` (M6 E08, Task 2, D4): o técnico (`OWN`) recebe as ordens não finais que
 * abriu ou de que é o responsável, com o local, e só as chaves do item (sem motivo da espera, notas, terceiro, custos
 * nem dado de hóspede); a ordem concluída, a reatribuída e a que perdeu o responsável saem do aparelho do antigo
 * responsável por *tombstone*; quem só tem `read` não recebe a fonte. O banco é compartilhado: cada teste afirma só
 * sobre as próprias ordens.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class MaintenanceOfflineFeedTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired staffApi: StaffApi,
) {
    private val support = MaintenanceTestSupport(iam, jdbc, staffApi)

    private data class Technician(
        val user: TestUser,
        val employee: UUID,
        val auth: RequestPostProcessor,
    )

    private fun definition(code: com.zeny.hotel.shared.PermissionCode) =
        MaintenancePermissions.permissions.single { it.code == code }

    /** Um técnico com `manage` e `read` `OWN`, vinculado a um colaborador novo. */
    private fun technician(): Technician {
        val user =
            iam.createUserWith(
                TestGrant(definition(MaintenancePermissions.READ), OWN),
                TestGrant(definition(MaintenancePermissions.MANAGE), OWN),
            )
        return Technician(user, support.employee(userId = user.id.value), iam.asTestUser(user))
    }

    /** Abre uma ordem (como o administrador) com o responsável [assignee], se houver, e devolve o id. */
    private fun assigned(
        assignee: UUID?,
        roomId: UUID? = support.room(),
        areaId: UUID? = null,
    ): UUID {
        val id = support.openedId(body = support.body(roomId = roomId, areaId = areaId))
        if (assignee != null) assign(id, assignee)
        return id
    }

    private fun assign(
        id: UUID,
        assignee: UUID?,
    ) {
        with(iam) {
            support.update(id, support.updateBody(support.order(id), "assigneeEmployeeId" to assignee)).ok()
        }
    }

    @Test
    fun `the technician receives his open orders with the place and exactly the item keys`() {
        val tech = technician()
        val room = support.room()
        val area = support.area()
        val ofRoom = assigned(tech.employee, roomId = room)
        val ofArea = assigned(tech.employee, roomId = null, areaId = area)
        val other = assigned(support.employee())
        // O responsável é o técnico, mas custos, terceiro e motivo da espera existem na linha: nada disso vai.
        with(iam) {
            support
                .update(
                    ofRoom,
                    support.updateBody(
                        support.order(ofRoom),
                        "thirdPartyName" to "Encanador Ltda",
                        "laborCost" to "10.00",
                        "partsCost" to "5.00",
                        "dueAt" to "2030-01-01T10:00:00Z",
                    ),
                ).ok()
            support.act(ofRoom, "start", auth = iam.asAdmin()).ok()
            support.act(ofRoom, "hold", auth = iam.asAdmin(), body = mapOf("reason" to "Aguardando peça")).ok()
        }

        val download = download(tech.auth)

        val items = orders(download.changes)
        items.keys.toList().let {
            it shouldContainExactlyInAnyOrder listOf(ofRoom, ofArea)
            it shouldNotContain other
        }
        val roomItem = items.getValue(ofRoom)
        val areaItem = items.getValue(ofArea)
        roomItem["deleted"].asBoolean() shouldBe false
        roomItem["version"].asLong() shouldBe jdbcVersion(ofRoom)
        assertItems(roomItem["data"], areaItem["data"], room, area, tech.employee)
        download.bodies.forEach {
            it shouldNotContain "Aguardando peça"
            it shouldNotContain "Encanador"
            it shouldNotContain "laborCost"
        }
    }

    private fun assertItems(
        data: JsonNode,
        areaData: JsonNode,
        room: UUID,
        area: UUID,
        employee: UUID,
    ) {
        assertSoftly {
            data.propertyNames().toList() shouldContainExactlyInAnyOrder
                listOf(
                    "number",
                    "roomId",
                    "roomNumber",
                    "areaId",
                    "areaName",
                    "category",
                    "priority",
                    "status",
                    "description",
                    "assigneeEmployeeId",
                    "dueAt",
                )
            data["roomId"].asString() shouldBe room.toString()
            data["roomNumber"].asString().shouldNotBeNull()
            data["areaId"].isNull shouldBe true
            data["status"].asString() shouldBe "ON_HOLD"
            data["description"].asString() shouldBe "Torneira pingando"
            data["assigneeEmployeeId"].asString() shouldBe employee.toString()
            areaData["areaId"].asString() shouldBe area.toString()
            areaData["areaName"].asString().shouldNotBeNull()
            areaData["roomId"].isNull shouldBe true
        }
    }

    @Test
    fun `OWN covers the orders the technician opened, and ALL sees every order`() {
        val tech = technician()
        val room = support.room()
        val opened = support.openedId(iam.asAdmin(), support.body(roomId = room))
        val mine =
            jdbc
                .sql("UPDATE maintenance.work_order SET opened_by = :user WHERE id = :id")
                .param("user", tech.user.id.value)
                .param("id", opened)
                .update()
        mine shouldBe 1
        val foreign = assigned(null)

        orders(download(tech.auth).changes).keys.toList().let {
            it shouldContainExactlyInAnyOrder listOf(opened)
        }
        val all = orders(download(iam.asAdmin()).changes).keys
        all.contains(opened) shouldBe true
        all.contains(foreign) shouldBe true
    }

    @Test
    fun `a completed, a reassigned and an unassigned order leave the old assignee's device by tombstone`() {
        val old = technician()
        val next = technician()
        val toComplete = assigned(old.employee)
        val toReassign = assigned(old.employee)
        val toUnassign = assigned(old.employee)
        val stays = assigned(old.employee)
        val first = download(old.auth)
        orders(first.changes).keys.toList() shouldContainExactlyInAnyOrder
            listOf(toComplete, toReassign, toUnassign, stays)

        with(iam) {
            support.act(toComplete, "complete", body = mapOf("notes" to "Feito")).ok()
        }
        assign(toReassign, next.employee)
        assign(toUnassign, null)

        val changes = orders(download(old.auth, first.cursor).changes)
        assertSoftly {
            changes.getValue(toComplete)["deleted"].asBoolean() shouldBe true
            changes.getValue(toReassign)["deleted"].asBoolean() shouldBe true
            changes.getValue(toUnassign)["deleted"].asBoolean() shouldBe true
            changes.containsKey(stays) shouldBe false
        }
        // O novo responsável a recebe; a perda do escopo não toca no feed dele.
        val received = orders(download(next.auth).changes)
        received.getValue(toReassign)["deleted"].asBoolean() shouldBe false
    }

    @Test
    fun `the opener keeps the order when the assignee changes`() {
        val tech = technician()
        val other = support.employee()
        val id =
            support.openedId(iam.asAdmin(), support.body(roomId = support.room())).also {
                jdbc
                    .sql("UPDATE maintenance.work_order SET opened_by = :user WHERE id = :id")
                    .param("user", tech.user.id.value)
                    .param("id", it)
                    .update()
            }
        assign(id, tech.employee)
        val first = download(tech.auth)
        orders(first.changes).keys.toList() shouldContainExactlyInAnyOrder listOf(id)

        assign(id, other)

        val changes = orders(download(tech.auth, first.cursor).changes)
        changes.getValue(id)["deleted"].asBoolean() shouldBe false
    }

    @Test
    fun `whoever only has read does not receive the source`() {
        val reader =
            iam.createUserWith(TestGrant(definition(MaintenancePermissions.READ), ALL))
        assigned(null)

        val changes = download(iam.asTestUser(reader)).changes

        withClue("a fonte de ordens não vem sem manage") {
            changes.none { it["source"].asString() == MaintenanceOffline.WORK_ORDERS.name } shouldBe true
        }
    }

    // --- Apoio -------------------------------------------------------------------------------------------------------

    private data class Download(
        val changes: List<JsonNode>,
        val cursor: String,
        val bodies: List<String>,
    )

    /** O download desde [from] (sem cursor: o completo), página a página, com os corpos crus. */
    private fun download(
        auth: RequestPostProcessor,
        from: String? = null,
    ): Download =
        with(iam) {
            val changes = mutableListOf<JsonNode>()
            val bodies = mutableListOf<String>()
            var cursor = from
            var pages = 0
            do {
                // O download só serve transações terminadas: espere os listeners do inventário.
                InventoryListenersQuiescence.await(jdbc)
                val response =
                    call(GET, "/api/v1/sync/changes?limit=200" + cursor?.let { "&cursor=$it" }.orEmpty(), auth)
                val page = response.ok()
                bodies += response.getContentAsString(Charsets.UTF_8)
                changes += page["changes"].values()
                cursor = page["cursor"].asString()
                pages++
                withClue("download sem fim") { (pages < MAX_PAGES) shouldBe true }
            } while (page["hasMore"].asBoolean())
            Download(changes, checkNotNull(cursor), bodies)
        }

    /** As mudanças da fonte `maintenance-work-orders`, a última de cada ordem. */
    private fun orders(changes: List<JsonNode>): Map<UUID, JsonNode> =
        changes
            .filter { it["source"].asString() == MaintenanceOffline.WORK_ORDERS.name }
            .associateBy { UUID.fromString(it["id"].asString()) }

    private fun jdbcVersion(id: UUID): Long =
        jdbc
            .sql("SELECT version FROM maintenance.work_order WHERE id = :id")
            .param("id", id)
            .query(Long::class.java)
            .single()

    private companion object {
        const val MAX_PAGES = 200
    }
}
