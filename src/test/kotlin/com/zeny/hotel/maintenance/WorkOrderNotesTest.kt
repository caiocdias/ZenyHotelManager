package com.zeny.hotel.maintenance

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.maintenance.MaintenanceTestSupport.Companion.WORK_ORDERS
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.util.UUID

/**
 * As observações das ordens de serviço (M6 E08, Task 3, D3): `POST /api/v1/work-orders/{id}/notes` com `manage` (no
 * escopo) ou `request` na ordem que o ator abriu; ordem alheia `403`; ordem final `409`; texto de 1 a 1000 caracteres;
 * a resposta da ordem lista as observações em ordem, sem mudar a versão; a trilha leva o fato e o tamanho, nunca o
 * texto. A tabela só de inserção é conferida no `DatabaseRolesTest`.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class WorkOrderNotesTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired staffApi: StaffApi,
) {
    private val support = MaintenanceTestSupport(iam, jdbc, staffApi)

    private fun reception(): TestUser =
        iam.createUser().also {
            val roleId =
                jdbc
                    .sql("SELECT id FROM iam.role WHERE template_key = :key")
                    .param("key", RoleTemplate.RECEPTIONIST.name)
                    .query(UUID::class.java)
                    .single()
            iam.assignRole(it, RoleId(roleId))
        }

    private fun note(
        order: UUID,
        text: String?,
        auth: RequestPostProcessor = iam.asAdmin(),
    ): MockHttpServletResponse = iam.call(POST, "$WORK_ORDERS/$order/notes", auth, mapOf("text" to text))

    @Test
    fun `a manager adds notes, listed in order on the order without changing its version`(): Unit =
        with(iam) {
            val order = support.openedId()
            val before = support.etag(order)

            val first = note(order, "Chamei o encanador").expectStatus(201)!!
            note(order, "Peça chega amanhã").expectStatus(201)

            val notes = support.order(order)["notes"].values().toList()
            assertSoftly {
                first["notes"].values().map { it["text"].asString() } shouldContainExactly
                    listOf("Chamei o encanador")
                notes.map { it["text"].asString() } shouldContainExactly
                    listOf("Chamei o encanador", "Peça chega amanhã")
                notes[0]["authorId"].asString().isNotBlank() shouldBe true
                notes[0]["createdAt"].asString().isNotBlank() shouldBe true
                support.etag(order) shouldBe before
            }
        }

    @Test
    fun `a requester adds a note to the order he opened, and is refused on someone else's`(): Unit =
        with(iam) {
            val requester = reception()
            val auth = asTestUser(requester)
            val own = support.openedId(auth = auth)
            val alien = support.openedId()

            note(own, "Mais um detalhe", auth).expectStatus(201)
            val refused = note(alien, "Intrometido", auth)

            assertSoftly {
                refused.status shouldBe 403
                support.order(alien)["notes"].size() shouldBe 0
                support.order(own)["notes"].size() shouldBe 1
            }
        }

    @Test
    fun `a manager with the own scope is refused on an order that is not his`(): Unit =
        with(iam) {
            val user =
                createUserWith(
                    TestGrant(
                        MaintenancePermissions.permissions.single { it.code == MaintenancePermissions.MANAGE },
                        OWN,
                    ),
                )
            val order = support.openedId()

            val refused = note(order, "Não é minha", asTestUser(user))

            refused.status shouldBe 403
        }

    @Test
    fun `a final order refuses notes with 409, and a missing order is 404`(): Unit =
        with(iam) {
            val done = support.openedId()
            support.act(done, "complete", body = emptyMap<String, Any>()).ok()

            val refused = note(done, "Tarde demais")

            assertSoftly {
                refused.status shouldBe 409
                refused.getContentAsString(Charsets.UTF_8) shouldContain "WORK_ORDER_TRANSITION_NOT_ALLOWED"
                support.order(done)["notes"].size() shouldBe 0
                note(UUID.randomUUID(), "Nada").status shouldBe 404
            }
        }

    @Test
    fun `an empty text, a blank one and one of 1001 characters are 400, and 1000 is accepted`(): Unit =
        with(iam) {
            val order = support.openedId()

            assertSoftly {
                note(order, "").status shouldBe 400
                note(order, "   ").status shouldBe 400
                note(order, null).status shouldBe 400
                note(order, "x".repeat(1001)).status shouldBe 400
                note(order, "x".repeat(1000)).status shouldBe 201
                support.order(order)["notes"].size() shouldBe 1
            }
        }

    @Test
    fun `the audit trail records the fact and the size of the note, never the text`(): Unit =
        with(iam) {
            val order = support.openedId()
            note(order, "Segredo da manutenção").expectStatus(201)
            val id = support.order(order)["notes"][0]["id"].asString()

            val entries =
                jdbc
                    .sql(
                        "SELECT action, after_state::text AS state FROM audit.audit_entry " +
                            "WHERE entity_type = 'maintenance.work-order-note' AND entity_id = :id",
                    ).param("id", id)
                    .query()
                    .listOfRows()

            assertSoftly {
                entries.map { it["action"] } shouldContainExactly listOf("maintenance.work-order-note.add")
                (entries.single()["state"] as String) shouldNotContain "Segredo"
                (entries.single()["state"] as String) shouldNotContain "manutenção"
            }
        }
}
