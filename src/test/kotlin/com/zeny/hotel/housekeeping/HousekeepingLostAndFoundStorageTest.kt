package com.zeny.hotel.housekeeping

import com.zeny.hotel.housekeeping.LostAndFoundTestSupport.Companion.ITEMS
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.util.UUID

/**
 * O local de guarda de um achado (M6 E06, ajuste da revisão da Task 1): `PUT …/{id}/storage-place` define ou troca o
 * local do item guardado, com o `If-Match`, só por quem gere os achados (`403` antes de `404` e `409`); fora de
 * `STORED`, `409` de transição; a trilha leva o local só pelo tamanho.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class HousekeepingLostAndFoundStorageTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired staffApi: StaffApi,
) {
    private val support = LostAndFoundTestSupport(iam, jdbc)
    private val execution = HousekeepingExecutionTestSupport(iam, jdbc, staffApi)

    @Test
    fun `who manages sets and changes the storage place of a stored item, with If-Match and in the trail`(): Unit =
        with(iam) {
            val keeper = execution.userWithTemplate(RoleTemplate.HOUSEKEEPER)
            val id =
                UUID.fromString(
                    support
                        .register(
                            asAdmin(),
                            support.body(roomId = support.housekeeping.createRoom(), storagePlace = null),
                        ).expectStatus(201)!!["id"]
                        .asString(),
                )

            val response = storage(id, asTestUser(keeper), " Cofre da recepção ")
            response.ok()["storagePlace"].asString() shouldBe "Cofre da recepção"
            support.etagHeader(response) shouldBe "\"1\""
            storage(id, asTestUser(keeper), "Armário 2").ok()["storagePlace"].asString() shouldBe "Armário 2"
            support.item(id)["status"].asString() shouldBe "STORED"

            support.housekeeping.auditActions(ENTITY, id) shouldContainExactly
                listOf("$ENTITY.register", "$ENTITY.change-storage-place", "$ENTITY.change-storage-place")
            jdbc
                .sql("SELECT after_state::text FROM audit.audit_entry WHERE entity_id = :id AND action = :action")
                .param("id", id.toString())
                .param("action", "$ENTITY.change-storage-place")
                .query(String::class.java)
                .list()
                .joinToString() shouldNotContain "Cofre"
        }

    @Test
    fun `the storage place needs manage before 404 and 409, If-Match, a valid text and a stored item`(): Unit =
        with(iam) {
            val attendant = execution.userWithTemplate(RoleTemplate.ROOM_ATTENDANT)
            val own = support.registered(asTestUser(attendant))
            storage(own, asTestUser(attendant), "Gaveta").problem(403, "PERMISSION_DENIED")
            storage(UUID.randomUUID(), asTestUser(attendant), "Gaveta", "\"0\"").problem(403, "PERMISSION_DENIED")
            storage(UUID.randomUUID(), asAdmin(), "Gaveta", "\"0\"").problem(404, "HOUSEKEEPING_LOST_FOUND_NOT_FOUND")
            storage(own, asAdmin(), "Gaveta", null).status shouldBe 428
            storage(own, asAdmin(), "Gaveta", "\"7\"").status shouldBe 412
            storage(own, asAdmin(), "  ").status shouldBe 400
            storage(own, asAdmin(), "b".repeat(101)).problem(400, "HOUSEKEEPING_DATA_INVALID")

            support.act(own, "return", asAdmin(), mapOf("returnedTo" to "Dono")).ok()
            val problem =
                storage(own, asAdmin(), "Gaveta").problem(409, "HOUSEKEEPING_LOST_FOUND_TRANSITION_NOT_ALLOWED")
            problem["itemStatus"].asString() shouldBe "RETURNED"
            problem["action"].asString() shouldBe "CHANGE_STORAGE_PLACE"
            support.item(own)["storagePlace"].asString() shouldBe "Armário da governança"
        }

    private fun storage(
        id: UUID,
        auth: RequestPostProcessor,
        place: String?,
        ifMatch: String? = support.etag(id),
    ): MockHttpServletResponse =
        iam.call(PUT, "$ITEMS/$id/storage-place", auth, mapOf("storagePlace" to place), ifMatch)

    private companion object {
        const val ENTITY = "housekeeping.lost-found-item"
    }
}
