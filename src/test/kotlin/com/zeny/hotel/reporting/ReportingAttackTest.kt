package com.zeny.hotel.reporting

import com.zeny.hotel.billing.BillingPermissions
import com.zeny.hotel.billing.BillingTestSupport.Companion.definition
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.reporting.application.MaintenanceCategory
import com.zeny.hotel.reporting.application.MaintenanceStatus
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Ataques da E09 (testador): 401 e 403 cruzado nas rotas, período fora da faixa ou malformado, texto livre fora da
 * exportação, injeção de fórmula ponta a ponta e os CHECKs de `maintenance` que o `reporting` espelha.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("TooManyFunctions")
class ReportingAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
) {
    private val admin = iam.asAdmin()

    // Ano que nenhuma outra classe usa (2033-2045 estão tomados).
    private val day = LocalDate.of(2048, 3, 10)
    private val period = "from=$day&to=$day"

    private val reportRoutes =
        listOf(
            "day-panel" to ReportingPermissions.DASHBOARD_READ,
            "end-of-day-pending" to ReportingPermissions.PENDING_READ,
            "housekeeping?$period" to ReportingPermissions.HOUSEKEEPING_READ,
            "maintenance?$period" to ReportingPermissions.MAINTENANCE_READ,
        )

    private fun routes(): List<Pair<String, PermissionCode>> =
        reportRoutes.flatMap { (route, permission) ->
            val path = route.substringBefore('?')
            val query = route.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
            listOf("$path$query", "$path/csv$query", "$path/xlsx$query").map { it to permission }
        }

    private fun get(
        path: String,
        auth: RequestPostProcessor? = admin,
    ): MockHttpServletResponse =
        mockMvc.request(GET, "/api/v1/reports/$path") { if (auth != null) with(auth) }.andReturn().response

    private fun readerOf(permission: PermissionCode) =
        iam.asTestUser(
            iam.createUserWith(TestGrant(ReportingPermissions.permissions.single { it.code == permission }, ALL)),
        )

    @Test
    fun `without authentication all twelve routes are 401`() {
        val all = routes()
        all.size shouldBe 12
        all.forEach { (path, _) -> withClue(path) { get(path, null).status shouldBe 401 } }
    }

    @Test
    fun `each reader gets only the routes of its own permission, in JSON, CSV and XLSX`() {
        reportRoutes.map { it.second }.forEach { held ->
            val user = readerOf(held)
            routes().forEach { (path, needed) ->
                val response = get(path, user)
                withClue("$held em $path: ${response.status}") {
                    response.status shouldBe if (needed == held) 200 else 403
                }
            }
        }
    }

    @Test
    fun `a date outside the storable range is a 400 problem on the JSON and on the exports, never a 500`() {
        listOf("housekeeping", "maintenance").forEach { report ->
            listOf("", "/csv", "/xlsx").forEach { suffix ->
                listOf(
                    "from=-4713-01-01&to=-4712-01-02",
                    "from=2030-01-01&to=+5874898-01-01",
                ).forEach { query ->
                    val response = get("$report$suffix?$query")
                    withClue("$report$suffix?$query") {
                        withClue(response.contentAsString) {
                            response.status shouldBe 400
                            response.contentType.toString() shouldContain "problem+json"
                            // O conversor de data da web recusa a data fora da faixa antes do serviço (INVALID_FORMAT
                            // em `from` ou `to`); o `REPORT_PERIOD_INVALID` do serviço é o respaldo.
                            with(iam) { json(response)["code"].asString() } shouldBeIn
                                listOf("VALIDATION_FAILED", "REPORT_PERIOD_INVALID")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a malformed date is 400 with the format code, not a 500`() {
        listOf("housekeeping", "maintenance/csv").forEach { report ->
            listOf("from=2033-13-01&to=2033-12-31", "from=2033-02-30&to=2033-12-31", "from=2033-01-01&to=").forEach {
                val response = get("$report?$it")
                withClue("$report?$it: ${response.status} ${response.contentAsString}") {
                    response.status shouldBe 400
                    response.contentType.toString() shouldContain "problem+json"
                }
            }
        }
        with(iam) {
            val problem = get("housekeeping?from=2033-13-01&to=2033-12-31").problem(400, "VALIDATION_FAILED")
            problem["errors"][0]["field"].asString() shouldBe "from"
            problem["errors"][0]["code"].asString() shouldBe "INVALID_FORMAT"
        }
    }

    @Test
    fun `free text of a work order never reaches the CSV or the XLSX`() {
        val marker = "SIGILO${UuidV7.generate().toString().takeLast(MARKER)}"
        val id = UuidV7.generate()
        val openedAt = day.atTime(HOUR, 0).toInstant(ZoneOffset.UTC).atOffset(ZoneOffset.UTC)
        jdbc
            .sql(
                "INSERT INTO maintenance.work_order (id, number, room_id, category, priority, description, origin, " +
                    "third_party_name, status, labor_cost, third_party_cost, parts_cost, opened_by, opened_at, " +
                    "closed_at, completion_notes, updated_at, version) VALUES (:id, :number, :room, 'PLUMBING', " +
                    "'HIGH', :marker, 'RECEPTION', :marker, 'DONE', 1, 1, 1, :by, :opened, :closed, :marker, " +
                    "now(), 0)",
            ).param("id", id)
            .param(
                "number",
                com.zeny.hotel.testsupport.WorkOrderNumbers
                    .next(),
            ).param("room", UuidV7.generate())
            .param("marker", marker)
            .param("by", UuidV7.generate())
            .param("opened", openedAt)
            .param("closed", openedAt.plusHours(2))
            .update()

        val csv = get("maintenance/csv?$period").getContentAsString(Charsets.UTF_8)
        csv shouldContain "Concluídas"
        csv shouldNotContain marker
        val xlsx = get("maintenance/xlsx?$period")
        xlsx.status shouldBe 200
        val texts =
            ReadableWorkbook(ByteArrayInputStream(xlsx.contentAsByteArray)).use { book ->
                book.sheets.toList().flatMap { sheet -> sheet.read().flatMap { row -> row.map { it?.text.orEmpty() } } }
            }
        texts.none { it.contains(marker) } shouldBe true
        texts.any { it == "PLUMBING" } shouldBe true
    }

    @Test
    fun `a cash point name starting with equals leaves the CSV with the quote prefix and the XLSX with the raw text`() {
        val suffix = UuidV7.generate().toString().takeLast(MARKER)
        val name = "=HYPERLINK(\"http://e.example\";\"x\") $suffix"
        val cashier = iam.createUserWith(TestGrant(definition(BillingPermissions.CASHIER_OPERATE), ALL))
        val point =
            with(iam) { call(POST, "/api/v1/cash-points", admin, mapOf("name" to name)).expectStatus(201)!!["id"] }
        val shift =
            mockMvc
                .request(POST, "/api/v1/cashier-shifts") {
                    with(iam.asTestUser(cashier))
                    header("Idempotency-Key", UuidV7.generate().toString())
                    contentType = org.springframework.http.MediaType.APPLICATION_JSON
                    content = """{"cashPointId":"${point.asString()}","openingFloat":"0.00"}"""
                }.andReturn()
                .response
                .let { with(iam) { it.expectStatus(201)!!["id"].asString() } }

        try {
            val csv = get("end-of-day-pending/csv").getContentAsString(Charsets.UTF_8)
            val line = csv.split("\r\n").single { it.contains(shift) }
            line shouldContain "\"'=HYPERLINK(\"\"http://e.example\"\";\"\"x\"\") $suffix\""
            csv shouldNotContain ";=HYPERLINK"
            csv shouldNotContain ";\"=HYPERLINK"

            val xlsx = get("end-of-day-pending/xlsx").contentAsByteArray
            val cells =
                ReadableWorkbook(ByteArrayInputStream(xlsx)).use { book ->
                    book.sheets.toList().flatMap { s -> s.read().flatMap { r -> r.map { it?.text.orEmpty() } } }
                }
            cells.contains(name) shouldBe true
        } finally {
            // O turno aberto ficaria na pendência do banco compartilhado, e o `ReportExportTest` compara o CSV com o
            // JSON linha a linha (o nome com `=` sai neutralizado no CSV).
            val closed =
                mockMvc
                    .request(POST, "/api/v1/cashier-shifts/$shift/close") {
                        with(iam.asTestUser(cashier))
                        header("Idempotency-Key", UuidV7.generate().toString())
                        contentType = org.springframework.http.MediaType.APPLICATION_JSON
                        content = """{"counts":[{"method":"CASH","amount":"0.00"}]}"""
                    }.andReturn()
                    .response
            withClue(closed.contentAsString) { closed.status shouldBe 200 }
        }
    }

    private fun insertOrderNumbered(number: String) =
        jdbc
            .sql(
                "INSERT INTO maintenance.work_order (id, number, room_id, category, priority, description, origin, " +
                    "status, labor_cost, third_party_cost, parts_cost, opened_by, opened_at, updated_at, version) " +
                    "VALUES (:id, :number, :room, 'OTHER', 'LOW', 'x', 'RECEPTION', 'OPEN', 0, 0, 0, :by, now(), " +
                    "now(), 0)",
            ).param("id", UuidV7.generate())
            .param("number", number)
            .param("room", UuidV7.generate())
            .param("by", UuidV7.generate())
            .update()

    @Test
    fun `the work order number check refuses a malformed format`() {
        listOf(
            "OS-2099-12345",
            "os-2099-123456",
            "OS-209-123456",
            " OS-2099-123456",
            "OS-2099-123456\n",
            "OS-2099-12a456",
            "",
        ).forEach { bad ->
            withClue("'$bad'") {
                shouldThrow<DataIntegrityViolationException> { insertOrderNumbered(bad) }
                    .message shouldContain "work_order_number_format_ck"
            }
        }
    }

    @Test
    fun `the work order number check accepts the real format`() {
        val ok =
            com.zeny.hotel.testsupport.WorkOrderNumbers
                .next()
        insertOrderNumbered(ok)
        jdbc
            .sql("SELECT count(*) FROM maintenance.work_order WHERE number = :n")
            .param("n", ok)
            .query(Long::class.java)
            .single() shouldBe 1
    }

    @Test
    fun `the work order number check refuses a year outside 2000 to 9999`() {
        listOf("OS-1999-100000", "OS-0000-100000", "OS-1000-100000").forEach { bad ->
            withClue("'$bad'") {
                shouldThrow<DataIntegrityViolationException> { insertOrderNumbered(bad) }
                    .message shouldContain "work_order_number_format_ck"
            }
        }
    }

    @Test
    fun `the reporting maintenance enums are the same as the work order CHECKs`() {
        fun valuesOf(constraint: String): List<String> =
            Regex("'([A-Z_]+)'")
                .findAll(
                    jdbc
                        .sql(
                            "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = :n AND " +
                                "conrelid = 'maintenance.work_order'::regclass",
                        ).param("n", constraint)
                        .query(String::class.java)
                        .single(),
                ).map { it.groupValues[1] }
                .toList()

        valuesOf("work_order_category_ck") shouldContainExactlyInAnyOrder MaintenanceCategory.entries.map { it.name }
        valuesOf("work_order_status_ck") shouldContainExactlyInAnyOrder MaintenanceStatus.entries.map { it.name }
    }

    private companion object {
        const val MARKER = 12
        const val HOUR = 12
    }
}
