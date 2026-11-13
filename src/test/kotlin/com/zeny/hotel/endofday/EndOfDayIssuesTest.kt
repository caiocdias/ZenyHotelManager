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
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldNotContain
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
 * As pendências do fechamento avisam, pelo `notifications`, quem acompanha o fechamento (RF-NOT-01, M6 E10 Task 6):
 * título com a data, texto com o código e a referência, nunca a mensagem da pendência (RN-13), e nada sai quando a
 * pendência é desfeita junto com a transação da etapa.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList") // o motor, a execução, o apoio do iam e os apoios de transação e JDBC
class EndOfDayIssuesTest(
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
    private data class Notice(
        val title: String,
        val body: String,
    )

    private fun follower(): TestUser =
        iam.createUserWith(
            TestGrant(EndOfDayPermissions.permissions.single { it.code == EndOfDayPermissions.RUNS_READ }, ALL),
        )

    private fun titleOf(date: LocalDate) =
        "Pendência no fechamento de ${date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}"

    private fun noticesOf(user: TestUser): List<Notice> =
        jdbc
            .sql(
                "SELECT title, body FROM notifications.notification " +
                    "WHERE type = 'notifications.end-of-day-pending' AND recipient_id = :id ORDER BY id",
            ).param("id", user.id.value)
            .query { rs, _ -> Notice(rs.getString("title"), rs.getString("body")) }
            .list()

    private fun executeCurrentDate() {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val run =
            tx
                .execute {
                    val start =
                        EndOfDayRun.start(
                            hotelApi.currentBusinessDate(),
                            RunTrigger.AUTOMATIC,
                            null,
                            registry.slots,
                            now,
                        )
                    runs.save(start)
                }.shouldNotBeNull()
        engine.execute(run.id, WAIT)
    }

    @Test
    fun `an issue raised by a step notifies who follows the end of day`() {
        val date = hotelApi.currentBusinessDate()
        val outsider = iam.createUser()
        val reader = follower()
        try {
            probe.raiseIssueOn(TWO, "TEST_PENDING")
            executeCurrentDate()

            noticesOf(reader) shouldContainExactly listOf(Notice(titleOf(date), "TEST_PENDING: $TWO"))
            noticesOf(reader).single().body shouldNotContain "Pendência de teste"
            noticesOf(outsider).shouldBeEmpty()
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `a failed step notifies STEP_FAILED`() {
        val date = hotelApi.currentBusinessDate()
        val outsider = iam.createUser()
        val reader = follower()
        try {
            probe.failNext(TWO, 1)
            executeCurrentDate()

            noticesOf(reader) shouldContainExactly
                listOf(Notice(titleOf(date), "${EndOfDayRunTransitions.STEP_FAILED}: $TWO"))
            noticesOf(outsider).shouldBeEmpty()
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `a notification of a rolled back issue is not sent`() {
        val reader = follower()
        try {
            probe.raiseIssueOn(TWO, "TEST_PENDING")
            probe.failNext(TWO, 1)
            executeCurrentDate()

            noticesOf(reader).map { it.body } shouldContainExactly
                listOf("${EndOfDayRunTransitions.STEP_FAILED}: $TWO")
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    private companion object {
        val WAIT: Duration = Duration.ofSeconds(60)
    }
}
