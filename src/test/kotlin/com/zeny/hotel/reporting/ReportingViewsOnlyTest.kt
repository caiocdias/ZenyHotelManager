package com.zeny.hotel.reporting

import com.zeny.hotel.reporting.application.ReportingQueries
import com.zeny.hotel.reporting.infrastructure.JdbcReportingQueries
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import kotlin.reflect.full.declaredMemberFunctions

/**
 * O adaptador `JdbcReportingQueries` lê só as views `report_*` (ARCHITECTURE §11): todo método da porta roda com um
 * papel novo, sem login, que só tem `USAGE` nos schemas lidos e `SELECT` nas views (`SET ROLE` numa conexão do dono),
 * como o `ChecklistReportsTest`; tocar uma tabela dá `42501`. Cada fatia que acrescenta consulta estende
 * [VIEWS] e [callEveryMethod].
 */
@IntegrationTest
class ReportingViewsOnlyTest(
    @Autowired private val flyway: Flyway,
) {
    @Test
    fun `every method of the queries port runs with a role that only reads the views`() {
        withViewsOnlyRole { reader, queries ->
            callEveryMethod(queries)
            // O papel de prova é de fato restrito: uma tabela interna é negada.
            val failure =
                shouldThrow<DataAccessException> {
                    reader.sql("SELECT count(*) FROM housekeeping.housekeeping_task").query(Long::class.java).single()
                }
            (failure.rootCause as SQLException).sqlState shouldBe INSUFFICIENT_PRIVILEGE
        }
    }

    @Test
    fun `the test calls every method the port declares`() {
        val declared = ReportingQueries::class.declaredMemberFunctions.map { it.name }.toSet()

        declared shouldBe CALLED
    }

    private fun callEveryMethod(queries: ReportingQueries) {
        val day = LocalDate.of(2032, 6, 15)
        val now = Instant.parse("2032-06-15T12:00:00Z")
        queries.attendantProductivity(day, day)
        queries.cleaningByType(day, day)
        queries.voluntaryInspections(now, now.plusSeconds(1))
        queries.dayMovements(day, day.plusDays(1))
        queries.dayCapacity(day, day.plusDays(1))
        queries.roomCounts()
        queries.outOfOrderOn(day)
        queries.openFolioBalances()
        queries.openCashierShifts()
        queries.pendingFnrhSubmissions()
    }

    private fun withViewsOnlyRole(block: (JdbcClient, ReportingQueries) -> Unit) {
        val owner = JdbcClient.create(flyway.configuration.dataSource)
        val role = "report_probe_${UuidV7.generate().toString().takeLast(PROBE_SUFFIX).replace("-", "")}"
        owner.sql("CREATE ROLE $role NOLOGIN").update()
        try {
            VIEWS.keys.forEach { owner.sql("GRANT USAGE ON SCHEMA $it TO $role").update() }
            VIEWS.forEach { (schema, views) ->
                views.forEach { owner.sql("GRANT SELECT ON $schema.$it TO $role").update() }
            }
            flyway.configuration.dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("SET ROLE $role") }
                val client = JdbcClient.create(SingleConnectionDataSource(connection, true))
                client.sql("SELECT current_user").query(String::class.java).single() shouldStartWith "report_probe_"
                block(client, JdbcReportingQueries(client))
                connection.createStatement().use { it.execute("RESET ROLE") }
            }
        } finally {
            owner.sql("DROP OWNED BY $role").update()
            owner.sql("DROP ROLE $role").update()
        }
    }

    private companion object {
        const val PROBE_SUFFIX = 12
        const val INSUFFICIENT_PRIVILEGE = "42501"

        /** As views que a porta lê, por schema. */
        val VIEWS =
            mapOf(
                "housekeeping" to listOf("report_tasks", "report_inspections"),
                "rooms" to listOf("report_room_status", "report_out_of_order"),
                "reservations" to listOf("report_reservations", "report_room_nights", "report_inventory"),
                "billing" to listOf("report_folios", "report_postings", "report_payments", "report_cashier_shifts"),
                "fnrh" to listOf("report_submissions"),
            )

        /** Os métodos da porta que [callEveryMethod] chama. */
        val CALLED =
            setOf(
                "attendantProductivity",
                "cleaningByType",
                "voluntaryInspections",
                "dayMovements",
                "dayCapacity",
                "roomCounts",
                "outOfOrderOn",
                "openFolioBalances",
                "openCashierShifts",
                "pendingFnrhSubmissions",
            )
    }
}
