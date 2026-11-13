package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.FakeStepsProbe.Companion.THREE
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.TWO
import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.endofday.application.EndOfDayIssues
import com.zeny.hotel.endofday.application.EndOfDayRunTransitions
import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import com.zeny.hotel.endofday.domain.EndOfDayRun
import com.zeny.hotel.endofday.domain.RunTrigger
import com.zeny.hotel.endofday.domain.StepSlot
import com.zeny.hotel.endofday.infrastructure.EndOfDayIssueRepository
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
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Ataques ao aviso das pendências do fechamento (M6 E10, Task 6): RN-13 (nada da mensagem, nem com referência
 * estranha ou no limite), destinatários exatos, tipo obrigatório no painel, rollback da pendência e do aviso juntos,
 * `STEP_UNREGISTERED`, retomada sem aviso repetido. O contexto não tem FCM configurado: o push pelo outbox não é
 * exercitado aqui (ver o relatório).
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList", "LargeClass") // o motor, o apoio do iam e os apoios de transação e JDBC
class EndOfDayIssuesAttackTest(
    @Autowired private val engine: EndOfDayEngine,
    @Autowired private val registry: EndOfDayStepRegistry,
    @Autowired private val runs: EndOfDayRunRepository,
    @Autowired private val issues: EndOfDayIssues,
    @Autowired private val issueRows: EndOfDayIssueRepository,
    @Autowired private val probe: FakeStepsProbe,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val clock: Clock,
    @Autowired private val context: ApplicationContext,
) {
    private val readPermission get() =
        EndOfDayPermissions.permissions.single {
            it.code == EndOfDayPermissions.RUNS_READ
        }

    private fun readGrant() = TestGrant(readPermission, ALL)

    private fun follower(): TestUser = iam.createUserWith(readGrant())

    private fun noticesOf(userId: UUID): List<Pair<String, String>> =
        jdbc
            .sql(
                "SELECT title, body FROM notifications.notification " +
                    "WHERE type = 'notifications.end-of-day-pending' AND recipient_id = :id ORDER BY id",
            ).param("id", userId)
            .query { rs, _ -> rs.getString("title") to rs.getString("body") }
            .list()

    private fun bodiesOf(user: TestUser) = noticesOf(user.id.value).map { it.second }

    private fun newRun(slots: List<StepSlot> = registry.slots): EndOfDayRun =
        tx
            .execute {
                runs.save(
                    EndOfDayRun.start(
                        hotelApi.currentBusinessDate(),
                        RunTrigger.AUTOMATIC,
                        null,
                        slots,
                        clock.instant().truncatedTo(ChronoUnit.MICROS),
                    ),
                )
            }.shouldNotBeNull()

    private fun raiseDirect(
        run: EndOfDayRun,
        kind: String,
        reference: String?,
        message: String,
    ) = tx.executeWithoutResult { issues.raise(run.id, run.businessDate, TWO, kind, reference, message) }

    private fun issueKinds(run: EndOfDayRun) =
        issueRows.findByRunIdOrderByCreatedAtAscIdAsc(run.id.value).map { it.kind }

    @Test
    fun `the notice never carries the issue message nor guest data`() {
        val reader = follower()
        val run = newRun()
        try {
            val secret = "Maria Souza CPF 123.456.789-09 maria@example.com"
            raiseDirect(run, "GUEST_PENDING", "UH-101", secret)

            val notice = noticesOf(reader.id.value).single()
            notice.second shouldBe "GUEST_PENDING: UH-101"
            (notice.first + notice.second) shouldNotContain "Maria"
            (notice.first + notice.second) shouldNotContain "123.456"
            (notice.first + notice.second) shouldNotContain "example.com"
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `a null reference shows the dash and the longest kind and reference still fit the notice`() {
        val reader = follower()
        val run = newRun()
        try {
            val kind = "K" + "A".repeat(59)
            val reference = "r".repeat(120)
            raiseDirect(run, "NO_REF", null, "m")
            raiseDirect(run, kind, reference, "m")

            bodiesOf(reader) shouldContainExactly listOf("NO_REF: —", "$kind: $reference")
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `an odd but opaque reference is delivered literally and never breaks the raise`() {
        val reader = follower()
        val run = newRun()
        try {
            val reference = "'; DROP TABLE x; -- \"<b>é☃</b>\\n%s{}"
            raiseDirect(run, "ODD_REF", reference, "m")

            bodiesOf(reader) shouldContainExactly listOf("ODD_REF: $reference")
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `an empty reference does not make the raise fail because of the notice text`() {
        val reader = follower()
        val run = newRun()
        try {
            raiseDirect(run, "EMPTY_REF", "", "m")

            issueKinds(run) shouldContainExactly listOf("EMPTY_REF")
            noticesOf(reader.id.value).size shouldBe 1
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `a reference with trailing whitespace does not make the raise fail because of the notice text`() {
        val reader = follower()
        val run = newRun()
        try {
            raiseDirect(run, "SPACED_REF", "UH-101 ", "m")

            issueKinds(run) shouldContainExactly listOf("SPACED_REF")
            noticesOf(reader.id.value).size shouldBe 1
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `recipients are exactly the active users with the permission through an active role`() {
        val adminBefore = noticesOf(iam.adminId()).size
        val reader = follower()
        val outsider = iam.createUser()
        val inactive = follower().also { iam.deactivate(it) }
        val onDeactivatedRole = iam.createUser()
        val deactivatedRole = iam.createRole(readGrant())
        iam.assignRole(onDeactivatedRole, deactivatedRole)
        iam.deactivateRole(deactivatedRole)
        val revoked = iam.createUser()
        val revokedRole = iam.createRole(readGrant())
        iam.assignRole(revoked, revokedRole)
        iam.revokeGrant(revokedRole, readPermission.code)
        val unassigned = iam.createUser()
        val oldRole = iam.createRole(readGrant())
        iam.assignRole(unassigned, oldRole)
        iam.unassignRole(unassigned, oldRole)
        val run = newRun()
        try {
            raiseDirect(run, "WHO_GETS_IT", "R1", "m")

            noticesOf(reader.id.value).size shouldBe 1
            noticesOf(iam.adminId()).size shouldBe adminBefore + 1
            listOf(outsider, inactive, onDeactivatedRole, revoked, unassigned).forEach {
                noticesOf(it.id.value).shouldBeEmpty()
            }
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `two roles with the permission still produce a single notice per user`() {
        val user = follower()
        iam.assignRole(user, iam.createRole(readGrant()))
        val run = newRun()
        try {
            raiseDirect(run, "ONCE", "R1", "m")

            bodiesOf(user) shouldContainExactly listOf("ONCE: R1")
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `the notice is mandatory in the panel even when the user turned the panel preference off`() {
        val reader = follower()
        jdbc
            .sql(
                "INSERT INTO notifications.notification_preference (user_id, type, in_app, push, updated_at) " +
                    "VALUES (:id, 'notifications.end-of-day-pending', false, false, now())",
            ).param("id", reader.id.value)
            .update()
        val run = newRun()
        try {
            raiseDirect(run, "MANDATORY", "R1", "m")

            bodiesOf(reader) shouldContainExactly listOf("MANDATORY: R1")
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `a rolled back issue leaves neither the issue row nor a notice, only STEP_FAILED`() {
        val reader = follower()
        probe.raiseIssueOn(TWO, "TEST_PENDING")
        probe.failNext(TWO, 1)
        val run = newRun()
        try {
            engine.execute(run.id, WAIT)

            issueKinds(run) shouldContainExactly listOf(EndOfDayRunTransitions.STEP_FAILED)
            bodiesOf(reader) shouldContainExactly listOf("${EndOfDayRunTransitions.STEP_FAILED}: $TWO")
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `an exception message with personal data never reaches the notice of the failed step`() {
        val reader = follower()
        probe.raiseIssueOn(TWO, "TEST_PENDING")
        probe.throwNext(TWO, IllegalStateException("Maria Souza 123.456.789-09"))
        val run = newRun()
        try {
            engine.execute(run.id, WAIT)

            bodiesOf(reader) shouldContainExactly listOf("${EndOfDayRunTransitions.STEP_FAILED}: $TWO")
            noticesOf(reader.id.value).single().second shouldNotContain "Maria"
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `an unregistered step notifies STEP_UNREGISTERED with the step name`() {
        val reader = follower()
        val run = newRun(registry.slots + StepSlot("atk-gone-notify", 5))
        try {
            engine.execute(run.id, WAIT)

            bodiesOf(reader) shouldContainExactly
                listOf("${EndOfDayRunTransitions.STEP_UNREGISTERED}: atk-gone-notify")
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `item issues committed in their own transactions notify once each and match the stored issues`() {
        val reader = follower()
        probe.itemsOn(TWO, 3, 2)
        probe.failNext(TWO, 1)
        val run = newRun()
        try {
            engine.execute(run.id, WAIT)

            val stored =
                issueRows.findByRunIdOrderByCreatedAtAscIdAsc(run.id.value).map { "${it.kind}: ${it.reference}" }
            // item-1 e item-3 ficam (REQUIRES_NEW); item-2 (na transação da etapa) é desfeito com ela
            stored shouldContainExactlyInAnyOrder
                listOf("TEST_ITEM: item-1", "TEST_ITEM: item-3", "${EndOfDayRunTransitions.STEP_FAILED}: $TWO")
            bodiesOf(reader) shouldContainExactlyInAnyOrder stored
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `resuming a failed run notifies the failure once and the retry raises its own issue once`() {
        val reader = follower()
        probe.raiseIssueOn(THREE, "TEST_PENDING")
        probe.failNext(THREE, 1)
        val run = newRun()
        try {
            engine.execute(run.id, WAIT)
            tx.executeWithoutResult { _ -> checkNotNull(runs.lockForUpdate(run.id)).resume(clock.instant()) }
            engine.execute(run.id, WAIT)

            bodiesOf(reader) shouldContainExactlyInAnyOrder
                listOf("${EndOfDayRunTransitions.STEP_FAILED}: $THREE", "TEST_PENDING: $THREE")
            issueKinds(run) shouldContainExactlyInAnyOrder
                listOf(EndOfDayRunTransitions.STEP_FAILED, "TEST_PENDING")
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    private companion object {
        val WAIT: Duration = Duration.ofSeconds(60)
    }
}
