package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.FakeStepsProbe.Companion.TWO
import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.endofday.application.EndOfDayRunTransitions
import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import com.zeny.hotel.endofday.domain.EndOfDayRun
import com.zeny.hotel.endofday.domain.RunTrigger
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * As pendências levantadas por uma etapa são gravadas na hora, mas o aviso sai agrupado: um por tipo, ao concluir a
 * etapa, com a contagem (M6 E11 Task 19). Etapa que falha não avisa as pendências dela.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList") // o motor, a execução, o apoio do iam e os apoios de transação e JDBC
class EndOfDayIssueGroupingTest(
    @Autowired private val engine: EndOfDayEngine,
    @Autowired private val registry: EndOfDayStepRegistry,
    @Autowired private val runs: EndOfDayRunRepository,
    @Autowired private val probe: FakeStepsProbe,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val clock: Clock,
    @Autowired private val context: ApplicationContext,
) {
    private fun follower(): TestUser =
        iam.createUserWith(
            TestGrant(EndOfDayPermissions.permissions.single { it.code == EndOfDayPermissions.RUNS_READ }, ALL),
        )

    private fun titleOf(date: LocalDate) =
        "Pendência no fechamento de ${date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}"

    private fun noticesOf(user: TestUser): List<Pair<String, String>> =
        jdbc
            .sql(
                "SELECT title, body FROM notifications.notification " +
                    "WHERE type = 'notifications.end-of-day-pending' AND recipient_id = :id ORDER BY id",
            ).param("id", user.id.value)
            .query { rs, _ -> rs.getString("title") to rs.getString("body") }
            .list()

    private fun issuesOf(
        runId: Any,
        kind: String,
    ): Int =
        jdbc
            .sql("SELECT count(*) FROM endofday.run_issue WHERE run_id = :id AND kind = :kind")
            .param("id", runId)
            .param("kind", kind)
            .query(Int::class.java)
            .single()

    private fun executeCurrentDate(): EndOfDayRun {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val run =
            tx
                .execute {
                    runs.save(
                        EndOfDayRun.start(
                            hotelApi.currentBusinessDate(),
                            RunTrigger.AUTOMATIC,
                            null,
                            registry.slots,
                            now,
                        ),
                    )
                }.shouldNotBeNull()
        engine.execute(run.id, WAIT)
        return run
    }

    @Test
    fun `issues of the same kind in one step produce one notice per user with the count`() {
        val date = hotelApi.currentBusinessDate()
        val outsider = iam.createUser()
        val readers = listOf(follower(), follower())
        try {
            probe.raiseIssuesOn(TWO, "TEST_PENDING", 3)
            val run = executeCurrentDate()

            issuesOf(run.id.value, "TEST_PENDING") shouldBeExactly 3
            readers.forEach {
                noticesOf(it) shouldContainExactly listOf(titleOf(date) to "TEST_PENDING: 3 pendência(s)")
            }
            noticesOf(outsider).shouldBeEmpty()
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `two kinds produce two notices`() {
        val date = hotelApi.currentBusinessDate()
        val reader = follower()
        try {
            probe.raiseIssuesOn(TWO, "TEST_PENDING", 2)
            probe.raiseIssuesOn(TWO, "TEST_OTHER", 4)
            executeCurrentDate()

            noticesOf(reader) shouldContainExactlyInAnyOrder
                listOf(
                    titleOf(date) to "TEST_PENDING: 2 pendência(s)",
                    titleOf(date) to "TEST_OTHER: 4 pendência(s)",
                )
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `a single issue keeps its reference in the notice`() {
        val date = hotelApi.currentBusinessDate()
        val reader = follower()
        try {
            probe.raiseIssuesOn(TWO, "TEST_PENDING", 1)
            executeCurrentDate()

            noticesOf(reader) shouldContainExactly listOf(titleOf(date) to "TEST_PENDING: $TWO-1")
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `a step that fails sends no grouped notice`() {
        val date = hotelApi.currentBusinessDate()
        val reader = follower()
        try {
            probe.raiseIssuesOn(TWO, "TEST_PENDING", 3)
            probe.failNext(TWO, 1)
            executeCurrentDate()

            noticesOf(reader) shouldContainExactly
                listOf(titleOf(date) to "${EndOfDayRunTransitions.STEP_FAILED}: $TWO")
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    private companion object {
        val WAIT: Duration = Duration.ofSeconds(60)
    }
}
