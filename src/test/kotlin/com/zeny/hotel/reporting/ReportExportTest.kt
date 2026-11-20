package com.zeny.hotel.reporting

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.HousekeepingReportSeed
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.WorkOrderNumbers
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.dhatim.fastexcel.reader.CellType
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * A exportação CSV e XLSX dos quatro relatórios (M6 E09, D6): o arquivo, relido aqui, tem as mesmas linhas da resposta
 * JSON da mesma operação; cabeçalhos HTTP; e um parâmetro inválido é `400` em problem+json, mesmo com
 * `Accept: text/csv`.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("TooManyFunctions")
class ReportExportTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
) {
    private val admin = iam.asAdmin()
    private val seed = HousekeepingReportSeed(jdbc)
    private val day = LocalDate.of(2037, 1, 1).plusDays(Random.nextLong(DAY_SPREAD))

    /** Uma aba esperada: o nome, as linhas do JSON e os campos (nome e se é decimal) na ordem das colunas. */
    private class Sheet(
        val name: String,
        val rows: List<JsonNode>,
        val fields: List<String>,
        val decimals: Set<String> = emptySet(),
        val integers: Set<String> = emptySet(),
    )

    private fun get(
        path: String,
        auth: RequestPostProcessor = admin,
        accept: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .request(GET, path) {
                with(auth)
                if (accept != null) header(HttpHeaders.ACCEPT, accept)
            }.andReturn()
            .response

    private fun json(path: String): JsonNode = with(iam) { get(path).ok() }

    private fun cellText(node: JsonNode?): String = if (node == null || node.isNull) "" else node.asString()

    // --- leitura do CSV (RFC 4180, separador ';') ---------------------------------------------------------------

    private fun parseCsv(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> {
                    field.append('"')
                    i++
                }

                quoted && c == '"' -> {
                    quoted = false
                }

                quoted -> {
                    field.append(c)
                }

                c == '"' -> {
                    quoted = true
                }

                c == ';' -> {
                    fields.add(field.toString())
                    field.clear()
                }

                c == '\r' && text.getOrNull(i + 1) == '\n' -> {
                    fields.add(field.toString())
                    field.clear()
                    records.add(fields.toList())
                    fields.clear()
                    i++
                }

                else -> {
                    field.append(c)
                }
            }
            i++
        }
        return records
    }

    private fun expected(
        sheet: Sheet,
        csv: Boolean,
    ): List<List<String>> =
        sheet.rows.map { row ->
            sheet.fields.map { f ->
                val text = cellText(row[f])
                if (csv && f in sheet.decimals) text.replace('.', ',') else text
            }
        }

    private fun assertCsv(
        response: MockHttpServletResponse,
        sheets: List<Sheet>,
    ) {
        val text = response.getContentAsString(Charsets.UTF_8)
        text shouldStartWith Char(0xFEFF).toString()
        val records = parseCsv(text.removePrefix(Char(0xFEFF).toString()))
        var at = 0
        sheets.forEachIndexed { index, sheet ->
            if (index > 0) {
                records[at++] shouldBe listOf("")
            }
            records[at++] shouldBe listOf(sheet.name)
            at++ // cabeçalho
            val body = records.drop(at).take(sheet.rows.size)
            withClue("aba ${sheet.name}") { body shouldBe expected(sheet, csv = true) }
            at += sheet.rows.size
        }
        at shouldBe records.size
    }

    private fun assertXlsx(
        response: MockHttpServletResponse,
        sheets: List<Sheet>,
    ) {
        ReadableWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { workbook ->
            workbook.sheets.map { it.name }.toList() shouldBe sheets.map { it.name }
            workbook.sheets.toList().zip(sheets).forEach { (read, sheet) ->
                val rows = read.read().drop(1)
                withClue("aba ${sheet.name}") {
                    rows.size shouldBe sheet.rows.size
                    rows.zip(sheet.rows).forEach { (row, json) ->
                        sheet.fields.forEachIndexed { c, f ->
                            val node = json[f]
                            val cell = row.getOptionalCell(c)
                            when {
                                node == null || node.isNull -> {
                                    cell.isPresent shouldBe false
                                }

                                f in sheet.decimals || f in sheet.integers -> {
                                    cell.get().type shouldBe CellType.NUMBER
                                    cell.get().asNumber().compareTo(BigDecimal(node.asString())) shouldBe 0
                                }

                                else -> {
                                    cell.get().text shouldBe node.asString()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun assertHeaders(
        response: MockHttpServletResponse,
        contentType: String,
        fileStart: String,
        extension: String,
    ) {
        response.status shouldBe 200
        response.contentType.toString() shouldStartWith contentType
        response.getHeaders(HttpHeaders.CACHE_CONTROL).single() shouldBe "no-store"
        val disposition = response.getHeaders(HttpHeaders.CONTENT_DISPOSITION).single()
        disposition shouldStartWith "attachment"
        disposition shouldContain "filename=\"$fileStart"
        disposition shouldContain ".$extension\""
    }

    private fun check(
        path: String,
        query: String,
        fileStart: String,
        sheets: (JsonNode) -> List<Sheet>,
    ) {
        val expectedSheets = sheets(json("/api/v1/reports/$path$query"))
        val csv = get("/api/v1/reports/$path/csv$query")
        assertHeaders(csv, "text/csv", fileStart, "csv")
        assertCsv(csv, expectedSheets)
        val xlsx = get("/api/v1/reports/$path/xlsx$query")
        assertHeaders(xlsx, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", fileStart, "xlsx")
        assertXlsx(xlsx, expectedSheets)
    }

    // --- dados ----------------------------------------------------------------------------------------------------

    private fun seedHousekeeping() {
        val type = seed.cleaningType()
        val room = UuidV7.generate()
        val employee = UuidV7.generate()
        val task = seed.task(type, day, room = room, status = "DONE", employee = employee, worked = 1800)
        seed.inspection(task, room, "REJECTED")
        seed.inspection(null, room, "APPROVED", day.atTime(HOUR, 0).toInstant(ZoneOffset.UTC))
    }

    private fun seedMaintenance() {
        listOf("DONE" to "PLUMBING", "OPEN" to "ELECTRICAL").forEach { (status, category) ->
            val id = UuidV7.generate()
            val opened = day.atTime(HOUR, 0).toInstant(ZoneOffset.UTC).atOffset(ZoneOffset.UTC)
            jdbc
                .sql(
                    "INSERT INTO maintenance.work_order (id, number, room_id, category, priority, description, " +
                        "origin, status, labor_cost, third_party_cost, parts_cost, opened_by, opened_at, closed_at, " +
                        "updated_at, version) VALUES (:id, :number, :room, :category, 'HIGH', 'x', 'RECEPTION', " +
                        ":status, 10.50, 0, 2.25, :by, :opened, :closed, now(), 0)",
                ).param("id", id)
                .param("number", WorkOrderNumbers.next())
                .param("room", UuidV7.generate())
                .param("category", category)
                .param("status", status)
                .param("by", UuidV7.generate())
                .param("opened", opened)
                .param("closed", if (status == "DONE") opened.plusHours(3) else null)
                .update()
        }
    }

    private fun seedFnrh() {
        jdbc
            .sql(
                "INSERT INTO fnrh.submission (id, reservation_id, reservation_code, operation, operation_rank, " +
                    "event_key, causal_version, status, attempts, next_attempt_at, last_failure, in_flight, " +
                    "occurred_at, created_at, version) VALUES (:id, :reservation, 'R-EXPORT', 'CHECK_IN', 0, " +
                    ":key, 1, 'FAILED', 2, now() + interval '30 days', 'UNAVAILABLE', false, now(), now(), 0)",
            ).param("id", UuidV7.generate())
            .param("reservation", UuidV7.generate())
            .param("key", "export-" + UuidV7.generate())
            .update()
    }

    private fun list(
        body: JsonNode,
        field: String,
    ) = body[field].values().toList()

    // --- testes ---------------------------------------------------------------------------------------------------

    @Test
    fun `the housekeeping export has the same rows as the JSON`() {
        seedHousekeeping()

        check("housekeeping", "?from=$day&to=${day.plusDays(1)}", "governanca-${day}_${day.plusDays(1)}") { body ->
            listOf(
                Sheet(
                    "Camareiras",
                    list(body, "attendants"),
                    listOf("employeeId", "rooms", "credits"),
                    integers = setOf("rooms", "credits"),
                ),
                Sheet(
                    "Tipos de limpeza",
                    list(body, "cleaningTypes"),
                    listOf(
                        "cleaningTypeId",
                        "roomTypeId",
                        "tasks",
                        "averageMinutes",
                        "inspections",
                        "rejections",
                        "rejectionRate",
                    ),
                    decimals = setOf("averageMinutes", "rejectionRate"),
                    integers = setOf("tasks", "inspections", "rejections"),
                ),
                Sheet(
                    "Inspeções voluntárias",
                    listOf(body["voluntaryInspections"]),
                    listOf("inspections", "rejections", "rejectionRate"),
                    decimals = setOf("rejectionRate"),
                    integers = setOf("inspections", "rejections"),
                ),
            )
        }
    }

    @Test
    fun `the maintenance export has the same rows as the JSON`() {
        seedMaintenance()

        check("maintenance", "?from=$day&to=$day", "manutencao-$day") { body ->
            listOf(
                Sheet("Por status", list(body, "byStatus"), listOf("status", "orders"), integers = setOf("orders")),
                Sheet(
                    "Por categoria",
                    list(body, "byCategory"),
                    listOf("category", "orders"),
                    integers = setOf("orders"),
                ),
                Sheet(
                    "Concluídas",
                    listOf(body["completed"]),
                    listOf("orders", "averageHours", "laborCost", "thirdPartyCost", "partsCost", "totalCost"),
                    decimals = setOf("averageHours", "laborCost", "thirdPartyCost", "partsCost", "totalCost"),
                    integers = setOf("orders"),
                ),
            )
        }
    }

    @Test
    fun `the day panel export has the same rows as the JSON`() {
        check("day-panel", "?days=3", "painel-") { body ->
            val roomFields =
                listOf(
                    "active",
                    "occupied",
                    "vacant",
                    "dirty",
                    "clean",
                    "inspected",
                    "inspectionPending",
                    "outOfOrder",
                    "outOfService",
                )
            listOf(
                Sheet(
                    "Dias",
                    list(body, "days"),
                    listOf("date", "arrivals", "departures", "hosted", "occupancy"),
                    decimals = setOf("occupancy"),
                    integers = setOf("arrivals", "departures", "hosted"),
                ),
                Sheet("UHs de hoje", listOf(body["rooms"]), roomFields, integers = roomFields.toSet()),
            )
        }
    }

    @Test
    fun `the pending export has the same rows as the JSON`() {
        seedFnrh()

        check("end-of-day-pending", "", "pendencias-") { body ->
            listOf(
                Sheet(
                    "Contas",
                    list(body, "folios"),
                    listOf("folioId", "folioNumber", "reservationId", "reservationCode", "departure", "balance"),
                    decimals = setOf("balance"),
                ),
                Sheet(
                    "Caixas abertos",
                    list(body, "cashierShifts"),
                    listOf("shiftId", "shiftNumber", "cashPointName", "operatorId", "openedOn", "openedAt"),
                ),
                Sheet(
                    "FNRH pendente",
                    list(body, "fnrhSubmissions"),
                    listOf(
                        "operation",
                        "status",
                        "reservationId",
                        "reservationCode",
                        "attempts",
                        "lastFailure",
                        "createdAt",
                    ),
                    integers = setOf("attempts"),
                ),
            )
        }
    }

    @Test
    fun `an invalid parameter is 400 problem json on every export route, even with Accept csv`() {
        val bad =
            listOf(
                "housekeeping/csv?from=$day&to=${day.minusDays(1)}",
                "housekeeping/xlsx?from=$day&to=${day.plusDays(366)}",
                "maintenance/csv?from=$day&to=${day.minusDays(1)}",
                "maintenance/xlsx?from=abc&to=$day",
                "day-panel/csv?days=0",
                "day-panel/xlsx?days=31",
            )
        for (accept in listOf("text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", null)) {
            bad.forEach { path ->
                val response = get("/api/v1/reports/$path", accept = accept)
                withClue("$path Accept $accept: ${response.status} ${response.contentType}") {
                    response.status shouldBe 400
                    response.contentType.toString() shouldContain "application/problem+json"
                }
            }
        }
    }

    @Test
    fun `without the permission every export route is 403 problem json`() {
        val none = iam.asTestUser(iam.createUser())
        listOf(
            "housekeeping/csv?from=$day&to=$day",
            "maintenance/xlsx?from=$day&to=$day",
            "day-panel/csv",
            "end-of-day-pending/xlsx",
        ).forEach { path ->
            val response = get("/api/v1/reports/$path", none, "text/csv")
            withClue(path) {
                response.status shouldBe 403
                response.contentType.toString() shouldContain "application/problem+json"
            }
        }
    }

    private companion object {
        const val DAY_SPREAD = 3000L
        const val HOUR = 12
    }
}
