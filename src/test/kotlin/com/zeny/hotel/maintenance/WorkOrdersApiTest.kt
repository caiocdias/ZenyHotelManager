package com.zeny.hotel.maintenance

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.maintenance.MaintenanceTestSupport.Companion.WORK_ORDERS
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * As ordens de serviço pela API (M6 E07, Task 1, D2 a D4): abertura de UH e de área, validações do corpo, referências
 * inexistentes (`400`) e inativas (`422`), o ciclo das ações com o `If-Match`, o `PUT` (substituição inteira, custos
 * obrigatórios e limitados, ordem final), a lista com filtros, a trilha sem texto livre e o contrato.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class WorkOrdersApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired staffApi: StaffApi,
) {
    private val support = MaintenanceTestSupport(iam, jdbc, staffApi)

    @Test
    fun `opens an order of a room and of an area, open, numbered and with zero costs`(): Unit =
        with(iam) {
            val room = support.room()
            val area = support.area()
            val year =
                java.time.LocalDate
                    .now()
                    .year

            val ofRoom = support.opened(body = support.body(roomId = room, priority = "HIGH", origin = "HOUSEKEEPING"))
            val ofArea = support.opened(body = support.body(areaId = area, category = "FURNITURE"))

            ofRoom["number"].asString() shouldMatch Regex("OS-\\d{4}-\\d{6}")
            ofRoom["number"].asString().substring(3, 7).toInt() shouldBe year
            ofRoom["status"].asString() shouldBe "OPEN"
            ofRoom["roomId"].asString() shouldBe room.toString()
            ofRoom["areaId"].isNull shouldBe true
            ofRoom["priority"].asString() shouldBe "HIGH"
            ofRoom["origin"].asString() shouldBe "HOUSEKEEPING"
            listOf("laborCost", "thirdPartyCost", "partsCost").forEach { ofRoom[it].asString() shouldBe "0.00" }
            ofRoom["assigneeEmployeeId"].isNull shouldBe true
            ofRoom["openedBy"].asString() shouldBe adminId().toString()
            ofArea["areaId"].asString() shouldBe area.toString()
            ofArea["roomId"].isNull shouldBe true
            ofArea["number"].asString() shouldNotBe ofRoom["number"].asString()

            val response = support.open(body = support.body(roomId = room))
            response.status shouldBe 201
            response.getHeader("Location") shouldBe "/api/v1/work-orders/${json(response)["id"].asString()}"
            response.getHeader("ETag").shouldNotBeNull()
        }

    @Test
    fun `the location must be the room or the area, one only, and must exist and be active`(): Unit =
        with(iam) {
            val room = support.room()
            val area = support.area()
            support.open(body = support.body(roomId = room, areaId = area)).problem(400, "WORK_ORDER_LOCATION_INVALID")
            support.open(body = support.body()).problem(400, "WORK_ORDER_LOCATION_INVALID")
            val missingRoom = support.open(body = support.body(roomId = UUID.randomUUID())).problem(400, "UNKNOWN_ROOM")
            missingRoom["errors"][0]["field"].asString() shouldBe "roomId"
            val missingArea = support.open(body = support.body(areaId = UUID.randomUUID())).problem(400, "UNKNOWN_AREA")
            missingArea["errors"][0]["field"].asString() shouldBe "areaId"

            support.deactivate("/api/v1/rooms/$room/deactivate")
            support.deactivate("/api/v1/areas/$area/deactivate")
            support.open(body = support.body(roomId = room)).problem(422, "WORK_ORDER_LOCATION_INACTIVE")
            support.open(body = support.body(areaId = area)).problem(422, "WORK_ORDER_LOCATION_INACTIVE")
        }

    @Test
    fun `origin CHECKLIST, blank description and unknown enums are 400 and open nothing`(): Unit =
        with(iam) {
            val room = support.room()
            val before = rowCount()
            support
                .open(body = support.body(roomId = room, origin = "CHECKLIST"))
                .problem(400, "VALIDATION_FAILED")
                .let { it["errors"][0]["field"].asString() shouldBe "origin" }
            support.open(body = support.body(roomId = room, description = "  ")).status shouldBe 400
            support.open(body = support.body(roomId = room, description = "a".repeat(2001))).status shouldBe 400
            support.open(body = support.body(roomId = room, category = "PAINTING")).status shouldBe 400
            support.open(body = support.body(roomId = room, priority = "CRITICAL")).status shouldBe 400
            support.open(body = support.body(roomId = room, origin = "GUEST")).status shouldBe 400
            rowCount() shouldBe before
        }

    @Test
    fun `the cycle start, hold, resume, complete keeps startedAt and sets closedAt, with the If-Match`(): Unit =
        with(iam) {
            val id = support.openedId()
            val started = support.act(id, "start").ok()
            started["status"].asString() shouldBe "IN_PROGRESS"
            val startedAt = started["startedAt"].asString()

            val held = support.act(id, "hold", body = mapOf("reason" to "Aguardando a peça")).ok()
            held["status"].asString() shouldBe "ON_HOLD"
            held["holdReason"].asString() shouldBe "Aguardando a peça"

            val resumed = support.act(id, "resume").ok()
            resumed["status"].asString() shouldBe "IN_PROGRESS"
            resumed["holdReason"].isNull shouldBe true
            resumed["startedAt"].asString() shouldBe startedAt

            val done =
                support
                    .act(id, "complete", body = mapOf("notes" to "Trocado o vedante", "partsCost" to "12.50"))
                    .ok()
            done["status"].asString() shouldBe "DONE"
            done["closedAt"].asString().shouldNotBeNull()
            done["completionNotes"].asString() shouldBe "Trocado o vedante"
            done["partsCost"].asString() shouldBe "12.50"
            done["laborCost"].asString() shouldBe "0.00"
            support.row(id)["status"] shouldBe "DONE"
        }

    @Test
    fun `cancel records the reason and the end, and hold and cancel need a reason`(): Unit =
        with(iam) {
            val id = support.openedId()
            support.act(id, "cancel", body = mapOf("reason" to " ")).status shouldBe 400
            support.act(id, "cancel", body = emptyMap<String, Any>()).status shouldBe 400
            val cancelled = support.act(id, "cancel", body = mapOf("reason" to "Duplicada")).ok()
            cancelled["status"].asString() shouldBe "CANCELLED"
            cancelled["cancellationReason"].asString() shouldBe "Duplicada"
            cancelled["closedAt"].asString().shouldNotBeNull()

            val inProgress = support.openedId().also { support.act(it, "start").ok() }
            support.act(inProgress, "hold", body = mapOf("reason" to "")).status shouldBe 400
            support.act(inProgress, "hold", body = emptyMap<String, Any>()).status shouldBe 400
            support.order(inProgress)["status"].asString() shouldBe "IN_PROGRESS"
        }

    @Test
    fun `an action in the wrong status or on a final order is a 409 and changes nothing`(): Unit =
        with(iam) {
            val open = support.openedId()
            support.act(open, "resume").problem(409, "WORK_ORDER_TRANSITION_NOT_ALLOWED")
            support
                .act(
                    open,
                    "hold",
                    body = mapOf("reason" to "Peça"),
                ).problem(409, "WORK_ORDER_TRANSITION_NOT_ALLOWED")
            support.order(open)["status"].asString() shouldBe "OPEN"

            val done = support.openedId().also { support.act(it, "complete", body = emptyMap<String, Any>()).ok() }
            val cancelled = support.openedId().also { support.act(it, "cancel", body = mapOf("reason" to "X")).ok() }
            listOf(done, cancelled).forEach { id ->
                val version = support.etag(id)
                val problem = support.act(id, "start").problem(409, "WORK_ORDER_TRANSITION_NOT_ALLOWED")
                problem["action"].asString() shouldBe "START"
                support
                    .act(
                        id,
                        "complete",
                        body = emptyMap<String, Any>(),
                    ).problem(409, "WORK_ORDER_TRANSITION_NOT_ALLOWED")
                support
                    .act(
                        id,
                        "cancel",
                        body = mapOf("reason" to "Y"),
                    ).problem(409, "WORK_ORDER_TRANSITION_NOT_ALLOWED")
                support.etag(id) shouldBe version
            }
        }

    @Test
    fun `a wrong If-Match is 412 and a missing one is 428, and neither changes the order`(): Unit =
        with(iam) {
            val id = support.openedId()
            support.act(id, "start", ifMatch = "\"999\"").status shouldBe 412
            support.act(id, "start", ifMatch = null).status shouldBe 428
            support.order(id)["status"].asString() shouldBe "OPEN"
            val stale = support.etag(id)
            support.act(id, "start", ifMatch = stale).ok()
            support.act(id, "hold", body = mapOf("reason" to "Peça"), ifMatch = stale).status shouldBe 412
        }

    @Test
    fun `PUT replaces the editable fields, with the assignee, the third party and the costs`(): Unit =
        with(iam) {
            val room = support.room()
            val order = support.opened(body = support.body(roomId = room))
            val id = UUID.fromString(order["id"].asString())
            val assignee = support.employee()
            val changed =
                support
                    .update(
                        id,
                        support.updateBody(
                            order,
                            "priority" to "URGENT",
                            "description" to "Vazamento no registro",
                            "assigneeEmployeeId" to assignee,
                            "thirdPartyName" to "Hidráulica Silva",
                            "laborCost" to "80.00",
                            "thirdPartyCost" to "250.00",
                            "partsCost" to "99999.99",
                        ),
                    ).ok()
            changed["priority"].asString() shouldBe "URGENT"
            changed["description"].asString() shouldBe "Vazamento no registro"
            changed["assigneeEmployeeId"].asString() shouldBe assignee.toString()
            changed["thirdPartyName"].asString() shouldBe "Hidráulica Silva"
            changed["laborCost"].asString() shouldBe "80.00"
            changed["partsCost"].asString() shouldBe "99999.99"
            changed["status"].asString() shouldBe "OPEN"
        }

    @Test
    fun `PUT without one of the costs is a 400 REQUIRED on the field`(): Unit =
        with(iam) {
            val order = support.opened()
            val id = UUID.fromString(order["id"].asString())
            listOf("laborCost", "thirdPartyCost", "partsCost").forEach { cost ->
                withClue(cost) {
                    val problem = support.update(id, support.updateBody(order) - cost).problem(400, "VALIDATION_FAILED")
                    problem["errors"].values().map { it["field"].asString() to it["code"].asString() } shouldContain
                        (cost to "REQUIRED")
                }
            }
        }

    @Test
    fun `costs of -1, 1_234 and 100000_00 are a 400 on the field and change nothing`(): Unit =
        with(iam) {
            val order = support.opened()
            val id = UUID.fromString(order["id"].asString())
            listOf("-1", "1.234", "100000.00").forEach { value ->
                listOf("laborCost", "thirdPartyCost", "partsCost").forEach { cost ->
                    withClue("$cost=$value") {
                        val problem =
                            support
                                .update(
                                    id,
                                    support.updateBody(order, cost to value),
                                ).problem(400, "VALIDATION_FAILED")
                        problem["errors"].values().map { it["field"].asString() } shouldContain cost
                    }
                }
                support.act(id, "complete", body = mapOf("laborCost" to value)).problem(400, "VALIDATION_FAILED")
            }
            support.order(id)["laborCost"].asString() shouldBe "0.00"
            support.update(id, support.updateBody(order, "laborCost" to "99999.99", "partsCost" to "0")).ok()
        }

    @Test
    fun `PUT refuses an unknown assignee with 400, and an inactive assignee or new location with 422`(): Unit =
        with(iam) {
            val order = support.opened()
            val id = UUID.fromString(order["id"].asString())
            val unknown =
                support
                    .update(id, support.updateBody(order, "assigneeEmployeeId" to UUID.randomUUID()))
                    .problem(400, "UNKNOWN_EMPLOYEE")
            unknown["errors"][0]["field"].asString() shouldBe "assigneeEmployeeId"

            val inactive = support.employee()
            support.deactivate("/api/v1/employees/$inactive/deactivate")
            support
                .update(id, support.updateBody(order, "assigneeEmployeeId" to inactive))
                .problem(422, "WORK_ORDER_ASSIGNEE_INACTIVE")

            val newRoom = support.room()
            support.deactivate("/api/v1/rooms/$newRoom/deactivate")
            support
                .update(id, support.updateBody(order, "roomId" to newRoom))
                .problem(422, "WORK_ORDER_LOCATION_INACTIVE")
            support
                .update(id, support.updateBody(order, "roomId" to UUID.randomUUID()))
                .problem(400, "UNKNOWN_ROOM")
            support
                .update(id, support.updateBody(order, "areaId" to support.area()))
                .problem(400, "WORK_ORDER_LOCATION_INVALID")
            support.order(id)["assigneeEmployeeId"].isNull shouldBe true
        }

    @Test
    fun `PUT on a final order is a 409 and an edit on hold works`(): Unit =
        with(iam) {
            val order = support.opened()
            val id = UUID.fromString(order["id"].asString())
            support.act(id, "start").ok()
            support.act(id, "hold", body = mapOf("reason" to "Peça")).ok()
            support.update(id, support.updateBody(order, "laborCost" to "10.00")).ok()["status"].asString() shouldBe
                "ON_HOLD"
            support.act(id, "complete", body = emptyMap<String, Any>()).ok()
            val problem =
                support
                    .update(id, support.updateBody(order, "laborCost" to "20.00"))
                    .problem(409, "WORK_ORDER_TRANSITION_NOT_ALLOWED")
            problem["action"].asString() shouldBe "UPDATE"
            support.order(id)["laborCost"].asString() shouldBe "10.00"
        }

    @Test
    fun `the list filters by status, room, area, priority and assignee, newest first, with the cursor`(): Unit =
        with(iam) {
            val room = support.room()
            val area = support.area()
            val assignee = support.employee()
            val first = support.openedId(body = support.body(roomId = room, priority = "LOW"))
            val second = support.openedId(body = support.body(roomId = room, priority = "URGENT"))
            val third = support.openedId(body = support.body(areaId = area))
            support.update(third, support.updateBody(support.order(third), "assigneeEmployeeId" to assignee)).ok()
            support.act(first, "start").ok()

            support.listedIds("$WORK_ORDERS?roomId=$room") shouldContainExactly
                listOf(second, first).map { it.toString() }
            support.listedIds("$WORK_ORDERS?areaId=$area") shouldContainExactly listOf(third.toString())
            support.listedIds("$WORK_ORDERS?roomId=$room&status=IN_PROGRESS") shouldContainExactly
                listOf(first.toString())
            support.listedIds("$WORK_ORDERS?roomId=$room&priority=URGENT") shouldContainExactly
                listOf(second.toString())
            support.listedIds("$WORK_ORDERS?assigneeEmployeeId=$assignee") shouldContainExactly listOf(third.toString())

            val page = call(GET, "$WORK_ORDERS?roomId=$room&limit=1", asAdmin()).ok()
            page["items"].size() shouldBe 1
            page["nextCursor"].isNull shouldBe false
            call(GET, "$WORK_ORDERS?status=NOPE", asAdmin()).status shouldBe 400
        }

    @Test
    fun `an unknown order is 404, and the audit trail has the ids and states but no free text`(): Unit =
        with(iam) {
            call(GET, "$WORK_ORDERS/${UUID.randomUUID()}", asAdmin()).problem(404, "WORK_ORDER_NOT_FOUND")

            val order =
                support.opened(
                    body = support.body(roomId = support.room(), description = "Segredo da descrição"),
                )
            val id = UUID.fromString(order["id"].asString())
            support
                .update(
                    id,
                    support.updateBody(order, "thirdPartyName" to "Hidráulica Sigilosa", "laborCost" to "5.00"),
                ).ok()
            support.act(id, "start").ok()
            support.act(id, "hold", body = mapOf("reason" to "Motivo sigiloso da espera")).ok()
            support.act(id, "resume").ok()
            support.act(id, "complete", body = mapOf("notes" to "Notas sigilosas da conclusão")).ok()
            val cancelled = support.openedId()
            support.act(cancelled, "cancel", body = mapOf("reason" to "Cancelamento sigiloso")).ok()

            support.audit(id).map { it["action"] } shouldContainExactly
                listOf(
                    "maintenance.work-order.open",
                    "maintenance.work-order.update",
                    "maintenance.work-order.start",
                    "maintenance.work-order.hold",
                    "maintenance.work-order.resume",
                    "maintenance.work-order.complete",
                )
            val trail =
                (
                    support.audit(
                        id,
                    ) + support.audit(cancelled)
                ).joinToString { "${it["before_state"]}|${it["after_state"]}" }
            listOf(
                "Segredo da descrição",
                "Hidráulica Sigilosa",
                "Motivo sigiloso da espera",
                "Notas sigilosas da conclusão",
                "Cancelamento sigiloso",
            ).forEach { withClue(it) { trail.contains(it) shouldBe false } }
            trail shouldContain "IN_PROGRESS"
        }

    @Test
    fun `the contract publishes origin, category, priority and status as extensible enums, and no DELETE`(): Unit =
        with(iam) {
            val contract =
                java.nio.file.Files
                    .readString(
                        java.nio.file.Path
                            .of("api/openapi.yaml"),
                    )
            val schema =
                contract
                    .substringAfter(
                        "    WorkOrderResponse:",
                    ).substringBefore("\n    WorkOrderUpdateRequest:")
            listOf("origin", "category", "priority", "status").forEach {
                withClue(it) {
                    val property = schema.substringAfter("\n        $it:").split(Regex("\n {8}\\S")).first()
                    property shouldContain "x-extensible-enum"
                }
            }
            contract shouldNotContain "deleteWorkOrder"
            // A faixa dos custos vem na descrição das operações, porque o schema de Money sobrescreve a do campo.
            val paths = json(call(GET, "/v3/api-docs", asAdmin()))["paths"]
            listOf(
                "put" to "/api/v1/work-orders/{id}",
                "post" to "/api/v1/work-orders/{id}/complete",
            ).forEach { (method, path) ->
                withClue(path) { paths[path][method]["description"].asString() shouldContain "0.00 a 99999.99" }
            }
            contract shouldContain "/api/v1/work-orders/{id}/cancel"
        }

    private fun rowCount(): Int =
        jdbc.sql("SELECT count(*) FROM maintenance.work_order").query(Int::class.java).single()
}
