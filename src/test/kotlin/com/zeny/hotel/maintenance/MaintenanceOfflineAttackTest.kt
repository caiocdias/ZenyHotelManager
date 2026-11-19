package com.zeny.hotel.maintenance

import com.zeny.hotel.checklists.SyncCommand
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/**
 * O ataque aos comandos offline da manutenção (M6 E08, D3 e D5): os códigos de recusa do texto da observação, o id da
 * observação já usado por uma observação online, o campo extra no payload, a origem `CHECKLIST` forjada na abertura,
 * a observação de quem só pede ou só lê, e a UH ou a área inexistente.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class MaintenanceOfflineAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired staffApi: StaffApi,
) {
    private val support = MaintenanceTestSupport(iam, jdbc, staffApi)

    private fun command(
        verb: String,
        payload: Map<String, Any?>,
        id: UUID = UuidV7.generate(),
    ) = SyncCommand(id, "maintenance.$verb", payload, Instant.now(), Instant.now())

    private fun sync(
        auth: RequestPostProcessor,
        vararg commands: SyncCommand,
    ): List<JsonNode> =
        with(iam) {
            call(POST, "/api/v1/sync/commands", auth, mapOf("commands" to commands.map { it.body() }))
                .ok()["results"]
                .values()
                .toList()
        }

    private fun requester(): RequestPostProcessor {
        val user = iam.createUser()
        val role =
            jdbc
                .sql("SELECT id FROM iam.role WHERE template_key = :key")
                .param("key", RoleTemplate.RECEPTIONIST.name)
                .query(UUID::class.java)
                .single()
        iam.assignRole(user, RoleId(role))
        return iam.asTestUser(user)
    }

    @Test
    fun `an empty text and a text of 1001 characters are rejected with a stable code and store nothing`() {
        val order = support.openedId()
        val ref = mapOf("workOrderId" to order)
        val blank = command("add-work-order-note", ref + ("text" to "   "))
        val missing = command("add-work-order-note", ref)
        val long = command("add-work-order-note", ref + ("text" to "x".repeat(1001)))
        val exact = command("add-work-order-note", ref + ("text" to "y".repeat(1000)))

        val results = sync(iam.asAdmin(), blank, missing, long, exact)

        assertSoftly {
            results.map { it["status"].asString() } shouldBe listOf("REJECTED", "REJECTED", "REJECTED", "APPLIED")
            results
                .take(3)
                .map { it["code"].asString() }
                .toSet()
                .size shouldBe 1
            results[0]["code"].asString() shouldBe "VALIDATION_FAILED"
            support.order(order)["notes"].size() shouldBe 1
        }
    }

    @Test
    fun `an offline note with the id of an existing note is rejected with WORK_ORDER_ID_IN_USE`() {
        val order = support.openedId()
        // O online dá ao servidor um id aleatório (não UUIDv7, que o offline recusaria antes): grava-se a linha com
        // um UUIDv7 para o id de uma observação existente chegar ao caso de uso.
        val noteId = UuidV7.generate()
        jdbc
            .sql(
                "INSERT INTO maintenance.work_order_note (id, work_order_id, text, author_id, created_at) " +
                    "VALUES (:id, :order, 'Original', :author, now())",
            ).param("id", noteId)
            .param("order", order)
            .param("author", UUID.randomUUID())
            .update() shouldBe 1

        val results =
            sync(
                iam.asAdmin(),
                command("add-work-order-note", mapOf("workOrderId" to order, "text" to "Offline"), id = noteId),
            )

        withClue(results.toString()) {
            results[0]["status"].asString() shouldBe "REJECTED"
            results[0]["code"].asString() shouldBe "WORK_ORDER_ID_IN_USE"
            support.order(order)["notes"].values().map { it["text"].asString() } shouldBe listOf("Original")
        }
    }

    @Test
    fun `an unknown field in the payload does not break the command and writes nothing extra`() {
        val order = support.openedId()
        val note =
            command(
                "add-work-order-note",
                mapOf(
                    "workOrderId" to order,
                    "text" to "Com extra",
                    "authorId" to UUID.randomUUID(),
                    "createdAt" to "2001-01-01T00:00:00Z",
                ),
            )

        val results = sync(iam.asAdmin(), note)

        val stored = support.order(order)["notes"][0]
        assertSoftly {
            results[0]["status"].asString() shouldBe "APPLIED"
            stored["text"].asString() shouldBe "Com extra"
            stored["authorId"].asString() shouldBe
                jdbc
                    .sql("SELECT author_id FROM maintenance.work_order_note WHERE id = :id")
                    .param("id", note.id)
                    .query(UUID::class.java)
                    .single()
                    .toString()
            (Instant.parse(stored["createdAt"].asString()).isAfter(Instant.parse("2020-01-01T00:00:00Z"))) shouldBe true
        }
    }

    @Test
    fun `an offline order cannot be forged with the CHECKLIST origin`() {
        val command = command("open-work-order", support.body(roomId = support.room(), origin = "CHECKLIST"))

        val results = sync(iam.asAdmin(), command)

        assertSoftly {
            results[0]["status"].asString() shouldBe "REJECTED"
            jdbc
                .sql("SELECT count(*) FROM maintenance.work_order WHERE id = :id")
                .param("id", command.id)
                .query(Int::class.java)
                .single() shouldBe 0
        }
    }

    @Test
    fun `a requester notes his own order offline but a foreign or a missing one gets the same refusal`() {
        val auth = requester()
        val own = support.openedId(auth = auth)
        val alien = support.openedId()
        val ownNote = command("add-work-order-note", mapOf("workOrderId" to own, "text" to "Meu"))
        val alienNote = command("add-work-order-note", mapOf("workOrderId" to alien, "text" to "Alheio"))
        val missing = command("add-work-order-note", mapOf("workOrderId" to UUID.randomUUID(), "text" to "Nada"))

        val results = sync(auth, ownNote, alienNote, missing)

        assertSoftly {
            results.map { it["status"].asString() } shouldBe listOf("APPLIED", "REJECTED", "REJECTED")
            results[1]["code"].asString() shouldBe results[2]["code"].asString()
            support.order(alien)["notes"].size() shouldBe 0
            support.order(own)["notes"].size() shouldBe 1
        }
    }

    @Test
    fun `a user with only read cannot open or note offline`() {
        val reader =
            iam.asTestUser(
                iam.createUserWith(
                    TestGrant(
                        MaintenancePermissions.permissions.single { it.code == MaintenancePermissions.READ },
                        ALL,
                    ),
                ),
            )
        val order = support.openedId()
        val open = command("open-work-order", support.body(roomId = support.room()))
        val note = command("add-work-order-note", mapOf("workOrderId" to order, "text" to "Leitor"))

        val results = sync(reader, open, note)

        assertSoftly {
            results.map { it["status"].asString() } shouldBe listOf("REJECTED", "REJECTED")
            support.order(order)["notes"].size() shouldBe 0
        }
    }

    @Test
    fun `opening offline in a room or an area that does not exist is rejected`() {
        val ghostRoom = command("open-work-order", support.body(roomId = UUID.randomUUID()))
        val ghostArea = command("open-work-order", support.body(areaId = UUID.randomUUID()))
        val both = command("open-work-order", support.body(roomId = support.room(), areaId = support.area()))
        val none = command("open-work-order", support.body())

        val results = sync(iam.asAdmin(), ghostRoom, ghostArea, both, none)

        results.map { it["status"].asString() } shouldBe List(4) { "REJECTED" }
    }

    @Test
    fun `cancel and room block are not offline commands, so the step-up cannot be bypassed`() {
        val order = support.openedId()
        val cancel = command("cancel-work-order", mapOf("workOrderId" to order, "reason" to "Duplicada"))
        val block =
            command(
                "block-room",
                mapOf(
                    "workOrderId" to order,
                    "kind" to "OUT_OF_ORDER",
                    "startsOn" to "2030-01-01",
                    "endsOn" to "2030-01-03",
                ),
            )

        val results = sync(iam.asAdmin(), cancel, block)

        assertSoftly {
            withClue(results.toString()) {
                results.map { it["status"].asString() } shouldBe listOf("REJECTED", "REJECTED")
                results.map { it["code"].asString() } shouldBe List(2) { "UNKNOWN_COMMAND_TYPE" }
            }
            support.order(order)["status"].asString() shouldBe "OPEN"
            support.row(order)["out_of_order_id"] shouldBe null
        }
    }
}
