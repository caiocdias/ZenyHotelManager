package com.zeny.hotel.maintenance

import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.rooms.RoomsTestSupport
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.staff.StaffTestSupport
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Apoio dos testes das ordens de serviço (M6 E07): abre ordens e chama a edição e as ações pela API com o `If-Match`
 * atual, cria UHs, áreas e colaboradores próprios (o banco é compartilhado) e lê a trilha e a linha gravada.
 */
class MaintenanceTestSupport(
    private val iam: IamTestSupport,
    private val jdbc: JdbcClient,
    private val staffApi: StaffApi,
) {
    val rooms = RoomsTestSupport(iam)
    val staff = StaffTestSupport(iam)

    fun body(
        roomId: UUID? = null,
        areaId: UUID? = null,
        category: String = "PLUMBING",
        priority: String = "NORMAL",
        description: String? = "Torneira pingando",
        origin: String = "RECEPTION",
        dueAt: String? = null,
    ): Map<String, Any?> =
        mapOf(
            "roomId" to roomId,
            "areaId" to areaId,
            "category" to category,
            "priority" to priority,
            "description" to description,
            "origin" to origin,
            "dueAt" to dueAt,
        )

    /** O corpo do `PUT`: a substituição inteira, com os campos de [base] (a resposta da ordem) trocados por [changes]. */
    fun updateBody(
        base: JsonNode,
        vararg changes: Pair<String, Any?>,
    ): Map<String, Any?> {
        val keys =
            listOf(
                "roomId",
                "areaId",
                "category",
                "priority",
                "description",
                "assigneeEmployeeId",
                "thirdPartyName",
                "dueAt",
                "laborCost",
                "thirdPartyCost",
                "partsCost",
            )
        val body: MutableMap<String, Any?> =
            keys.associateWith<String, Any?> { base[it].takeUnless { node -> node.isNull }?.asString() }.toMutableMap()
        changes.forEach { (key, value) -> body[key] = value }
        return body
    }

    fun room(): UUID = rooms.createRoom(rooms.createRoomType())

    fun area(): UUID = rooms.createArea()

    /** Um colaborador novo (de qualquer setor), vinculado ao usuário [userId], se houver. */
    fun employee(userId: UUID? = null): UUID =
        staff.createEmployee(
            staffApi
                .departments()
                .first()
                .id.value,
            userId = userId,
        )

    fun open(
        auth: RequestPostProcessor = iam.asAdmin(),
        body: Map<String, Any?> = body(roomId = room()),
    ): MockHttpServletResponse = iam.call(POST, WORK_ORDERS, auth, body)

    /** Abre uma ordem (`201`) e devolve a resposta. */
    fun opened(
        auth: RequestPostProcessor = iam.asAdmin(),
        body: Map<String, Any?> = body(roomId = room()),
    ): JsonNode = with(iam) { open(auth, body).expectStatus(201)!! }

    fun openedId(
        auth: RequestPostProcessor = iam.asAdmin(),
        body: Map<String, Any?> = body(roomId = room()),
    ): UUID = UUID.fromString(opened(auth, body)["id"].asString())

    /** A ação [action] (`start`, `hold`, `resume`, `complete`, `cancel`) com o [ifMatch] informado ou o atual. */
    fun act(
        id: UUID,
        action: String,
        auth: RequestPostProcessor = iam.asAdmin(),
        body: Any? = null,
        ifMatch: String? = etag(id),
    ): MockHttpServletResponse = iam.call(POST, "$WORK_ORDERS/$id/$action", auth, body, ifMatch)

    fun update(
        id: UUID,
        body: Map<String, Any?>,
        auth: RequestPostProcessor = iam.asAdmin(),
        ifMatch: String? = etag(id),
    ): MockHttpServletResponse = iam.call(PUT, "$WORK_ORDERS/$id", auth, body, ifMatch)

    fun etag(id: UUID): String {
        val response = iam.call(GET, "$WORK_ORDERS/$id", iam.asAdmin())
        withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 200 }
        return response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
    }

    fun order(id: UUID): JsonNode = with(iam) { iam.call(GET, "$WORK_ORDERS/$id", iam.asAdmin()).ok() }

    /** Os ids da lista (todas as páginas) de [path], como [auth]. */
    fun listedIds(
        path: String = WORK_ORDERS,
        auth: RequestPostProcessor = iam.asAdmin(),
    ): List<String> = rooms.listAll(path, auth).map { it["id"].asString() }

    fun deactivate(path: String) {
        with(iam) { call(POST, path, asAdmin()).expectStatus(200) }
    }

    /** A linha gravada da ordem. */
    fun row(id: UUID): Map<String, Any?> =
        jdbc
            .sql("SELECT * FROM maintenance.work_order WHERE id = :id")
            .param("id", id)
            .query()
            .singleRow()

    /** A trilha da ordem: as entradas com a ação, em ordem. */
    fun audit(id: UUID): List<Map<String, Any?>> =
        jdbc
            .sql(
                "SELECT * FROM audit.audit_entry WHERE entity_type = 'maintenance.work-order' AND entity_id = :id " +
                    "ORDER BY occurred_at, id",
            ).param("id", id.toString())
            .query()
            .listOfRows()

    companion object {
        const val WORK_ORDERS = "/api/v1/work-orders"
    }
}
