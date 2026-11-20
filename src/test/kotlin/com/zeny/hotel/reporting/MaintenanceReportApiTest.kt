package com.zeny.hotel.reporting

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.WorkOrderNumbers
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * `GET /api/v1/reports/maintenance` (M6 E09, D5): ordens por status e por categoria, tempo médio de atendimento e
 * custos das concluídas, sobre ordens semeadas por SQL num ano que nenhum outro teste usa. Os totais saem por
 * diferença (lê antes, semeia, lê depois); o tempo médio, que não se subtrai, é exato porque o dia é só deste teste.
 * Também: período inválido, permissão, o DTO tipado e a ausência de texto livre.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class MaintenanceReportApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
) {
    private val admin = iam.asAdmin()

    // Um dia por teste, num ano que nenhum outro usa: o relatório soma o período todo.
    private val day = LocalDate.of(2036, 1, 1).plusDays(DAY_STEP * sequence.getAndIncrement())

    private fun report(
        from: LocalDate,
        to: LocalDate,
        auth: RequestPostProcessor = admin,
    ) = iam.call(GET, "/api/v1/reports/maintenance?from=$from&to=$to", auth)

    @Suppress("LongParameterList") // os campos que variam nos casos
    private fun order(
        status: String,
        category: String,
        opened: LocalDate = day,
        hoursToClose: Long? = null,
        labor: String = "0.00",
        thirdParty: String = "0.00",
        parts: String = "0.00",
    ) {
        val id = UuidV7.generate()
        val openedAt = opened.atTime(HOUR_OPEN, 0).toInstant(ZoneOffset.UTC).atOffset(ZoneOffset.UTC)
        jdbc
            .sql(
                "INSERT INTO maintenance.work_order (id, number, room_id, area_id, category, priority, description, " +
                    "origin, third_party_name, status, labor_cost, third_party_cost, parts_cost, opened_by, " +
                    "opened_at, started_at, closed_at, completion_notes, cancellation_reason, updated_at, version) " +
                    "VALUES (:id, :number, " +
                    ":room, null, :category, 'HIGH', 'Texto livre sigiloso', 'RECEPTION', 'Terceiro Sigiloso Ltda', " +
                    ":status, :labor, :thirdParty, :parts, :by, :openedAt, null, :closedAt, :notes, :cancellation, " +
                    "now(), 0)",
            ).param("id", id)
            .param("number", WorkOrderNumbers.next())
            .param("room", UuidV7.generate())
            .param("category", category)
            .param("status", status)
            .param("labor", BigDecimal(labor))
            .param("thirdParty", BigDecimal(thirdParty))
            .param("parts", BigDecimal(parts))
            .param("by", UuidV7.generate())
            .param("openedAt", openedAt)
            .param("closedAt", hoursToClose?.let { openedAt.plusHours(it) })
            .param("notes", hoursToClose?.let { "Notas sigilosas" })
            .param("cancellation", if (status == "CANCELLED") "Motivo sigiloso" else null)
            .update()
    }

    private fun seed() {
        order("DONE", "PLUMBING", hoursToClose = 2, labor = "10.00", thirdParty = "5.00", parts = "1.00")
        order("DONE", "ELECTRICAL", hoursToClose = 4, labor = "20.00", parts = "2.50")
        order("OPEN", "PLUMBING")
        order("IN_PROGRESS", "FURNITURE")
        // Cancelada: conta por status e categoria, mas não entre as concluídas (nem nos custos).
        order("CANCELLED", "PLUMBING", hoursToClose = 1, labor = "99.00")
        // Concluída fora do período: não conta em nada.
        order("DONE", "PLUMBING", opened = day.plusDays(10), hoursToClose = 50, labor = "500.00")
    }

    private fun JsonNode.status(value: String) =
        this["byStatus"]
            .values()
            .singleOrNull { it["status"].asString() == value }
            ?.get("orders")
            ?.asLong() ?: 0L

    private fun JsonNode.category(value: String) =
        this["byCategory"]
            .values()
            .singleOrNull { it["category"].asString() == value }
            ?.get("orders")
            ?.asLong() ?: 0L

    private fun JsonNode.money(field: String) = BigDecimal(this["completed"][field].asString())

    @Test
    fun `orders are counted by status and by category and the completed ones carry the time and the costs`() {
        val before = with(iam) { report(day, day).ok() }
        seed()
        val after = with(iam) { report(day, day).ok() }

        fun statusDelta(value: String) = after.status(value) - before.status(value)

        fun categoryDelta(value: String) = after.category(value) - before.category(value)
        statusDelta("DONE") shouldBe 2
        statusDelta("OPEN") shouldBe 1
        statusDelta("IN_PROGRESS") shouldBe 1
        statusDelta("CANCELLED") shouldBe 1
        statusDelta("ON_HOLD") shouldBe 0
        categoryDelta("PLUMBING") shouldBe 3
        categoryDelta("ELECTRICAL") shouldBe 1
        categoryDelta("FURNITURE") shouldBe 1
        after["completed"]["orders"].asLong() - before["completed"]["orders"].asLong() shouldBe 2
        (after.money("laborCost") - before.money("laborCost")) shouldBe BigDecimal("30.00")
        (after.money("thirdPartyCost") - before.money("thirdPartyCost")) shouldBe BigDecimal("5.00")
        (after.money("partsCost") - before.money("partsCost")) shouldBe BigDecimal("3.50")
        (after.money("totalCost") - before.money("totalCost")) shouldBe BigDecimal("38.50")
        // O dia é só deste teste: (2 h + 4 h) / 2 = 3,0 h.
        BigDecimal(after["completed"]["averageHours"].asString()) shouldBe BigDecimal("3.0")
        after["from"].asString() shouldBe "$day"
        after["to"].asString() shouldBe "$day"
    }

    @Test
    fun `a period with no order has empty lists, zero costs and a null average time`() {
        val body = with(iam) { report(day, day).ok() }

        body["byStatus"].size() shouldBe 0
        body["byCategory"].size() shouldBe 0
        body["completed"]["orders"].asLong() shouldBe 0
        body["completed"]["averageHours"].isNull shouldBe true
        body.money("totalCost") shouldBe BigDecimal("0.00")
    }

    @Test
    fun `an invalid period is refused with 400 and the limit of 366 days is inclusive`() {
        with(iam) {
            report(day, day.minusDays(1))
                .problem(400, "REPORT_PERIOD_INVALID")["errors"][0]["field"]
                .asString() shouldBe "to"
            report(day, day.plusDays(366)).problem(400, "REPORT_PERIOD_INVALID")
            report(day, day.plusDays(365)).ok()
            call(GET, "/api/v1/reports/maintenance?to=$day", admin).problem(400, "VALIDATION_FAILED")
            call(GET, "/api/v1/reports/maintenance?from=abc&to=$day", admin).problem(400, "VALIDATION_FAILED")
        }
    }

    @Test
    fun `without the permission the report is refused with 403, and with it the report is served`() {
        fun userWith(code: com.zeny.hotel.shared.PermissionCode) =
            iam.asTestUser(
                iam.createUserWith(TestGrant(ReportingPermissions.permissions.single { it.code == code }, ALL)),
            )
        val none = iam.asTestUser(iam.createUser())
        val onlyHousekeeping = userWith(ReportingPermissions.HOUSEKEEPING_READ)
        val reader = userWith(ReportingPermissions.MAINTENANCE_READ)

        with(iam) {
            report(day, day, none).problem(403, "PERMISSION_DENIED")
            report(day, day, onlyHousekeeping).problem(403, "PERMISSION_DENIED")
            // A permissão vem antes da validação do período.
            report(day, day.minusDays(1), none).problem(403, "PERMISSION_DENIED")
            report(day, day, reader).ok()
        }
    }

    @Test
    fun `the response is a typed object with no free text`() {
        seed()

        val response = with(iam) { report(day, day).also { it.status shouldBe 200 } }
        val raw = response.getContentAsString(Charsets.UTF_8)
        val body = iam.json(response)

        body.propertyNames().toSet() shouldBe setOf("from", "to", "byStatus", "byCategory", "completed")
        body["byStatus"].values().forEach { it.propertyNames().toSet() shouldBe setOf("status", "orders") }
        body["byCategory"].values().forEach { it.propertyNames().toSet() shouldBe setOf("category", "orders") }
        body["completed"].propertyNames().toSet() shouldBe
            setOf("orders", "averageHours", "laborCost", "thirdPartyCost", "partsCost", "totalCost")
        raw shouldNotContain "sigiloso"
        raw shouldNotContain "Sigiloso"
        raw shouldNotContain WorkOrderNumbers.PREFIX
    }

    private companion object {
        // Um dia por instância (uma por teste), 30 dias à parte: sem colisão entre métodos nem entre classes.
        const val DAY_STEP = 30L
        val sequence = AtomicInteger()
        const val HOUR_OPEN = 12
    }
}
