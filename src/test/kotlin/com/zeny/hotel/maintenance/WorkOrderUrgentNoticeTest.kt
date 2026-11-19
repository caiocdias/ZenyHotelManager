package com.zeny.hotel.maintenance

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * O aviso de OS urgente (M6 E07, Task 2, D6 e Review Focus 5): uma ordem criada `URGENT`, ou editada de outra
 * prioridade para `URGENT`, avisa quem tem `maintenance.workorders.manage`, menos o ator, com o número, o local e a
 * categoria e sem a descrição; editar uma ordem que já era urgente não avisa de novo.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class WorkOrderUrgentNoticeTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired staffApi: StaffApi,
) {
    private val support = MaintenanceTestSupport(iam, jdbc, staffApi)

    private fun grant(code: PermissionCode) =
        TestGrant(MaintenancePermissions.permissions.single { it.code == code }, ALL)

    /** Quem abre e edita: `request` e `manage`, como a Manutenção. */
    private fun actor(): TestUser =
        iam.createUserWith(grant(MaintenancePermissions.REQUEST), grant(MaintenancePermissions.MANAGE))

    private fun manager(): TestUser = iam.createUserWith(grant(MaintenancePermissions.MANAGE))

    private fun readerOnly(): TestUser = iam.createUserWith(grant(MaintenancePermissions.READ))

    /** Os avisos de OS urgente da ordem [number] para o usuário (título e texto). */
    private fun notices(
        user: TestUser,
        number: String,
    ): List<Pair<String, String>> =
        jdbc
            .sql(
                "SELECT title, body FROM notifications.notification WHERE type = 'notifications.urgent-work-order' " +
                    "AND recipient_id = :id AND body LIKE :number ORDER BY created_at, id",
            ).param("id", user.id.value)
            .param("number", "%$number%")
            .query { rs, _ -> rs.getString("title") to rs.getString("body") }
            .list()

    private fun roomNumber(id: UUID): String =
        jdbc
            .sql("SELECT number FROM rooms.room WHERE id = :id")
            .param("id", id)
            .query(String::class.java)
            .single()

    private fun areaName(id: UUID): String =
        jdbc
            .sql("SELECT name FROM rooms.area WHERE id = :id")
            .param("id", id)
            .query(String::class.java)
            .single()

    @Test
    fun `an order opened URGENT notifies the managers but not the actor or a reader, without the description`(): Unit =
        with(iam) {
            val actor = actor()
            val manager = manager()
            val reader = readerOnly()
            val room = support.room()
            val body =
                support.body(
                    roomId = room,
                    priority = "URGENT",
                    category = "ELECTRICAL",
                    description = "Fio descascado perto do hóspede SIGILOSO",
                )

            val order = support.opened(asTestUser(actor), body)
            val number = order["number"].asString()

            val received = notices(manager, number)
            received shouldHaveSize 1
            received.single().second shouldContain number
            received.single().second shouldContain roomNumber(room)
            received.single().first shouldContain number
            received.single().second shouldContain "Elétrica"
            (received.single().first + received.single().second) shouldNotContain "SIGILOSO"
            (received.single().first + received.single().second) shouldNotContain "Fio descascado"
            notices(actor, number).shouldBeEmpty()
            notices(reader, number).shouldBeEmpty()
        }

    @Test
    fun `an order of an area names the area, and a non urgent order notifies nobody`(): Unit =
        with(iam) {
            val actor = actor()
            val manager = manager()
            val area = support.area()

            val urgent = support.opened(asTestUser(actor), support.body(areaId = area, priority = "URGENT"))
            notices(manager, urgent["number"].asString()).single().second shouldContain areaName(area)

            val normal = support.opened(asTestUser(actor), support.body(roomId = support.room(), priority = "HIGH"))
            notices(manager, normal["number"].asString()).shouldBeEmpty()
        }

    @Test
    fun `an edit to URGENT notifies once, and editing an order that is already urgent does not notify again`(): Unit =
        with(iam) {
            val actor = actor()
            val auth = asTestUser(actor)
            val manager = manager()
            val order = support.opened(auth, support.body(roomId = support.room(), priority = "HIGH"))
            val id = UUID.fromString(order["id"].asString())
            val number = order["number"].asString()
            notices(manager, number).shouldBeEmpty()

            val urgent = support.update(id, support.updateBody(support.order(id), "priority" to "URGENT"), auth)
            urgent.status shouldBe 200
            notices(manager, number) shouldHaveSize 1
            notices(actor, number).shouldBeEmpty()

            // Já era urgente: outra edição (descrição, custo) não reavisa.
            val again =
                support.update(
                    id,
                    support.updateBody(support.order(id), "description" to "Outro texto", "partsCost" to "10.00"),
                    auth,
                )
            again.status shouldBe 200
            notices(manager, number) shouldHaveSize 1

            // Sair de urgente e voltar é uma nova passagem a urgente: avisa de novo.
            support.update(id, support.updateBody(support.order(id), "priority" to "NORMAL"), auth).status shouldBe 200
            notices(manager, number) shouldHaveSize 1
            support.update(id, support.updateBody(support.order(id), "priority" to "URGENT"), auth).status shouldBe 200
            notices(manager, number) shouldHaveSize 2
        }

    @Test
    fun `a refused edit or opening notifies nobody`(): Unit =
        with(iam) {
            val actor = actor()
            val auth = asTestUser(actor)
            val manager = manager()
            val order = support.opened(auth, support.body(roomId = support.room(), priority = "HIGH"))
            val id = UUID.fromString(order["id"].asString())
            val number = order["number"].asString()

            // O custo acima do teto recusa a edição inteira, inclusive o aviso.
            support
                .update(
                    id,
                    support.updateBody(support.order(id), "priority" to "URGENT", "laborCost" to "100000.00"),
                    auth,
                ).status shouldBe 400
            notices(manager, number).shouldBeEmpty()
        }
}
