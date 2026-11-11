package com.zeny.hotel.printing

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.printing.application.PrintAgentJobsService
import com.zeny.hotel.printing.application.PrintAgentsService
import com.zeny.hotel.printing.application.PrintJobsMaintenance
import com.zeny.hotel.printing.application.PrintJobsService
import com.zeny.hotel.printing.infrastructure.PrintAgentRealm
import com.zeny.hotel.printing.infrastructure.PrintJobsMaintenanceJob
import com.zeny.hotel.printing.infrastructure.PrintingProperties
import com.zeny.hotel.shared.ServicePrincipal
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.FILES_PURGE_DEFERRED
import com.zeny.hotel.testsupport.FNRH_SEND_DEFERRED
import com.zeny.hotel.testsupport.GUESTS_REKEY_DEFERRED
import com.zeny.hotel.testsupport.INVENTORY_HORIZON_DEFERRED
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.OUTBOX_PURGE_DEFERRED
import com.zeny.hotel.testsupport.PRINTING_MAINTENANCE_DEFERRED
import com.zeny.hotel.testsupport.PROVISIONAL_EXPIRY_DEFERRED
import com.zeny.hotel.testsupport.PostgresInitializer
import com.zeny.hotel.testsupport.RESUBMISSION_JOB_DEFERRED
import com.zeny.hotel.testsupport.TEST_MIGRATIONS
import com.zeny.hotel.testsupport.TestAuthInitializer
import com.zeny.hotel.testsupport.TestEncryptionKeysInitializer
import com.zeny.hotel.testsupport.TestStorageInitializer
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val TICK: Duration = Duration.ofNanos(1_000)
private val DAY: Duration = Duration.ofHours(24)
private val ACK_TIMEOUT: Duration = Duration.ofMinutes(2)
private const val MAINTENANCE_ACTIONS =
    "'printing.job.expire-ack', 'printing.job.expire', 'printing.job.fail-inactive', 'printing.job.purge-content'"

/**
 * Ataques do testador à manutenção da fila de impressão e à métrica (M5 E10, Task 12): limites exatos, corridas
 * com o ack, duas rodadas ao mesmo tempo, lotes, impressora e agente desativados, expurgo, trilha, gauge e o job
 * agendado. O banco é compartilhado e o relógio anda: só asserções relativas, relógio devolvido ao fim.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class)
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
class PrintJobsMaintenanceAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val jobs: PrintJobsService,
    @Autowired private val delivery: PrintAgentJobsService,
    @Autowired private val agents: PrintAgentsService,
    @Autowired private val maintenance: PrintJobsMaintenance,
    @Autowired private val maintenanceJob: PrintJobsMaintenanceJob,
    @Autowired private val properties: PrintingProperties,
    @Autowired private val realm: PrintAgentRealm,
    @Autowired private val registry: MeterRegistry,
    @Autowired private val txManager: PlatformTransactionManager,
    @Autowired private val clock: MutableClock,
) {
    private class Agent(
        val id: UUID,
        val token: String,
    )

    private class Printer(
        val id: UUID,
        val agent: Agent,
    )

    private data class Row(
        val status: String,
        val position: Long,
        val attempts: Int,
        val failure: String?,
        val deliveredTo: UUID?,
        val createdAt: Instant,
        val sentAt: Instant?,
        val finishedAt: Instant?,
        val noContent: Boolean,
        val purgedAt: Instant?,
    )

    private var advanced: Duration = Duration.ZERO

    @AfterEach
    fun restoreClock() {
        clock.advance(advanced.negated())
        advanced = Duration.ZERO
        // Nunca apaga a linha: o ShedLock lembra que ela existe e só a atualiza.
        jdbc
            .sql("UPDATE shared.shedlock SET lock_until = now() - interval '1 hour' WHERE name = :n")
            .param("n", PrintJobsMaintenanceJob.LOCK_NAME)
            .update()
    }

    private fun advance(duration: Duration) {
        clock.advance(duration)
        advanced = advanced.plus(duration)
    }

    private fun suffix() = UuidV7.generate().toString().takeLast(12)

    private fun newAgent(): Agent {
        val json = iam.call(POST, "/api/v1/print-agents", iam.asAdmin(), mapOf("name" to "Agente ${suffix()}"))
        val body = jsonMapper.readTree(json.contentAsString)
        return Agent(UUID.fromString(body["id"].asString()), body["token"].asString())
    }

    private fun newPrinter(agent: Agent = newAgent()): Printer {
        val body =
            mapOf(
                "name" to "Imp ${suffix()}",
                "kind" to "A4_PDF",
                "address" to "addr-${suffix()}",
                "agentId" to agent.id,
            )
        val json = iam.call(POST, "/api/v1/printers", iam.asAdmin(), body)
        return Printer(UUID.fromString(jsonMapper.readTree(json.contentAsString)["id"].asString()), agent)
    }

    private fun enqueue(printer: Printer): UUID =
        jobs.enqueue(
            NewPrintJob(printer.id, "frontdesk.registration-card", "%PDF-1.7 SEGREDO-${suffix()}".toByteArray()),
        )

    private fun principalOf(agent: Agent): ServicePrincipal = realm.authenticate(agent.token)!!

    private fun claim(
        agent: Agent,
        limit: Int = 10,
    ) = delivery.claim(principalOf(agent), limit)

    private fun ack(
        agent: Agent,
        job: UUID,
        result: String = "PRINTED",
        failureCode: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .request(POST, "/api/v1/print-agents/me/jobs/$job/ack") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${agent.token}")
                contentType = MediaType.APPLICATION_JSON
                content =
                    jsonMapper.writeValueAsString(
                        listOfNotNull(
                            "result" to result,
                            failureCode?.let {
                                "failureCode" to
                                    it
                            },
                        ).toMap(),
                    )
            }.andReturn()
            .response

    private fun row(id: UUID): Row =
        jdbc
            .sql(
                "SELECT status, position, attempts, failure_code, delivered_to, created_at, sent_at, finished_at, " +
                    "content IS NULL AS no_content, content_purged_at FROM printing.print_job WHERE id = :id",
            ).param("id", id)
            .query { rs, _ ->
                Row(
                    rs.getString("status"),
                    rs.getLong("position"),
                    rs.getInt("attempts"),
                    rs.getString("failure_code"),
                    rs.getObject("delivered_to", UUID::class.java),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("sent_at")?.toInstant(),
                    rs.getTimestamp("finished_at")?.toInstant(),
                    rs.getBoolean("no_content"),
                    rs.getTimestamp("content_purged_at")?.toInstant(),
                )
            }.single()

    private fun count(sql: String): Long = jdbc.sql(sql).query(Long::class.java).single()

    private fun trail(job: UUID): List<Triple<String, Boolean, String>> =
        jdbc
            .sql(
                "SELECT action, user_id IS NULL AS system, " +
                    "coalesce(before_state::text, '') || ' ' || coalesce(after_state::text, '') AS states " +
                    "FROM audit.audit_entry WHERE entity_id = :id AND action IN ($MAINTENANCE_ACTIONS) " +
                    "ORDER BY occurred_at, id",
            ).param("id", job.toString())
            .query { rs, _ -> Triple(rs.getString("action"), rs.getBoolean("system"), rs.getString("states")) }
            .list()

    private fun asAdmin(block: () -> Unit) {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(UserId(iam.adminId()).toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun <T> inParallel(vararg tasks: () -> T): List<T> {
        val barrier = CyclicBarrier(tasks.size)
        val pool = Executors.newFixedThreadPool(tasks.size)
        try {
            val futures =
                tasks.map { task ->
                    pool.submit<T> {
                        barrier.await(10, TimeUnit.SECONDS)
                        task()
                    }
                }
            return futures.map { it.get(120, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun awaitBlockedOnLock() {
        await().atMost(Duration.ofSeconds(10)).until {
            count(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()",
            ) > 0
        }
    }

    // ---- limites exatos ----

    @Test
    fun `a SENT job is requeued only after the ack timeout, not at the exact limit`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        claim(printer.agent) shouldHaveSize 1
        val sentAt = row(job).sentAt.shouldNotBeNull()

        maintenance.run(sentAt.plus(ACK_TIMEOUT).minus(TICK))
        row(job).status shouldBe "SENT"
        maintenance.run(sentAt.plus(ACK_TIMEOUT))
        row(job).status shouldBe "SENT"
        maintenance.run(sentAt.plus(ACK_TIMEOUT).plus(TICK))

        row(job).status shouldBe "PENDING"
        row(job).attempts shouldBe 1
    }

    @Test
    fun `a PENDING job expires only after the ttl, not at the exact limit, and finishes at the run instant`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        val created = row(job).createdAt

        maintenance.run(created.plus(DAY).minus(TICK))
        row(job).status shouldBe "PENDING"
        maintenance.run(created.plus(DAY))
        row(job).status shouldBe "PENDING"
        maintenance.run(created.plus(DAY).plus(TICK))

        val after = row(job)
        after.status shouldBe "FAILED"
        after.failure shouldBe "EXPIRED"
        after.finishedAt shouldBe created.plus(DAY).plus(TICK)
    }

    @Test
    fun `the content of PRINTED and FAILED jobs survives the exact retention limit and goes one tick after`() {
        val printer = newPrinter()
        val printed = enqueue(printer)
        val failed = enqueue(printer)
        claim(printer.agent) shouldHaveSize 2
        ack(printer.agent, printed).status shouldBe 204
        ack(printer.agent, failed, "FAILED", "PAPER_JAM").status shouldBe 204
        // O relógio de teste não anda sozinho: os dois terminam no mesmo instante.
        val finished = row(printed).finishedAt.shouldNotBeNull()
        row(failed).finishedAt shouldBe finished

        maintenance.run(finished.plus(DAY))
        listOf(printed, failed).forEach { withClue("no limite $it") { row(it).noContent shouldBe false } }
        maintenance.run(finished.plus(DAY).plus(TICK))

        listOf(printed, failed).forEach {
            withClue("1 tick depois $it") {
                row(it).noContent shouldBe true
                row(it).purgedAt shouldBe finished.plus(DAY).plus(TICK)
            }
        }
        row(failed).failure shouldBe "PAPER_JAM"
    }

    // ---- guardas dos passos (o que uma implementação apressada erraria) ----

    @Test
    fun `each step skips a job that is not in its state and the content of an unfinished job is never erased`() {
        val printer = newPrinter()
        val pending = enqueue(printer)
        val sent = enqueue(printer)
        val printed = enqueue(printer)
        claim(printer.agent, 3)
        ack(printer.agent, printed).status shouldBe 204
        // `pending` e `sent` foram reivindicados juntos: devolve o primeiro
        delivery.release(principalOf(printer.agent), pending)
        val far = clock.instant().plus(Duration.ofDays(10))
        val before = listOf(pending, sent, printed).associateWith { row(it) }

        maintenance.purge(pending, far) shouldBe null
        maintenance.purge(sent, far) shouldBe null
        maintenance.expireAck(pending, far) shouldBe null
        maintenance.expireAck(printed, far) shouldBe null
        maintenance.expire(sent, far) shouldBe null
        maintenance.expire(printed, far) shouldBe null
        maintenance.failInactive(printed, far) shouldBe null
        maintenance.purge(printed, clock.instant()) shouldBe null // antes da retenção

        listOf(pending, sent, printed).forEach { row(it) shouldBe before.getValue(it) }
        listOf(pending, sent, printed).forEach { trail(it).shouldBeEmpty() }
    }

    @Test
    fun `purge is idempotent, keeps the first purge instant and writes the trail once`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        claim(printer.agent)
        ack(printer.agent, job).status shouldBe 204
        val far =
            row(job)
                .finishedAt
                .shouldNotBeNull()
                .plus(DAY)
                .plus(Duration.ofMinutes(1))

        maintenance.purge(job, far) shouldBe PrintJobStatus.PRINTED
        val first = row(job)
        maintenance.purge(job, far.plus(Duration.ofHours(5))) shouldBe null

        row(job) shouldBe first
        trail(job).map { it.first } shouldContainExactly listOf("printing.job.purge-content")
    }

    // ---- corridas com o ack ----

    @Test
    fun `an ack that commits before the maintenance reads the job leaves it PRINTED and the trail empty`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        claim(printer.agent) shouldHaveSize 1
        val principal = principalOf(printer.agent)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val holder =
                pool.submit {
                    TransactionTemplate(txManager).executeWithoutResult {
                        jdbc
                            .sql("SELECT id FROM printing.print_job WHERE id = :id FOR UPDATE")
                            .param("id", job)
                            .query { rs, _ -> rs.getString(1) }
                            .single()
                        delivery.acknowledge(principal, job, true, null)
                        locked.countDown()
                        release.await(30, TimeUnit.SECONDS)
                    }
                }
            locked.await(10, TimeUnit.SECONDS) shouldBe true
            val maint = pool.submit<Any?> { maintenance.expireAck(job, clock.instant().plus(Duration.ofMinutes(5))) }
            awaitBlockedOnLock()
            maint.isDone shouldBe false
            release.countDown()
            holder.get(30, TimeUnit.SECONDS)

            maint.get(30, TimeUnit.SECONDS) shouldBe null
        } finally {
            pool.shutdownNow()
        }

        row(job).status shouldBe "PRINTED"
        row(job).attempts shouldBe 1
        trail(job).shouldBeEmpty()
    }

    @Test
    fun `an ack that arrives while the maintenance holds the lock is 409 and does not change the requeued job`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        claim(printer.agent) shouldHaveSize 1
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val holder =
                pool.submit {
                    TransactionTemplate(txManager).executeWithoutResult {
                        maintenance.expireAck(job, clock.instant().plus(Duration.ofMinutes(5))) shouldBe
                            PrintJobStatus.PENDING
                        locked.countDown()
                        release.await(30, TimeUnit.SECONDS)
                    }
                }
            locked.await(10, TimeUnit.SECONDS) shouldBe true
            val late = pool.submit<Int> { ack(printer.agent, job).status }
            awaitBlockedOnLock()
            late.isDone shouldBe false
            release.countDown()
            holder.get(30, TimeUnit.SECONDS)

            late.get(30, TimeUnit.SECONDS) shouldBe 409
        } finally {
            pool.shutdownNow()
        }

        row(job).status shouldBe "PENDING"
        row(job).deliveredTo shouldBe null
    }

    @Test
    fun `two maintenance runs at the same time process each job once and without error`() {
        val printer = newPrinter()
        val ids = (1..30).map { enqueue(printer) }
        claim(printer.agent, 50) shouldHaveSize 30
        val now = clock.instant().plus(Duration.ofMinutes(5))

        val runs = inParallel({ maintenance.run(now) }, { maintenance.run(now) })

        runs.forEach { it.failed shouldBe 0 }
        (runs.sumOf { it.requeued }) shouldBeGreaterThanOrEqualTo 30
        ids.forEach {
            withClue("job $it") {
                row(it).status shouldBe "PENDING"
                row(it).attempts shouldBe 1
                trail(it).map { entry -> entry.first } shouldContainExactly listOf("printing.job.expire-ack")
            }
        }
        ids.map { row(it).position }.toSet() shouldHaveSize 30
    }

    @Test
    fun `two runs at the same time over every kind of work leave one trail entry per change`() {
        val agent = newAgent()
        val live = newPrinter(agent)
        val off = newPrinter(agent)
        val sent = (1..8).map { enqueue(live) }
        claim(agent, 20) shouldHaveSize 8
        val printed = (1..8).map { enqueue(live) }
        claim(agent, 8).forEach { ack(agent, it.id).status shouldBe 204 }
        val stale = (1..8).map { enqueue(live) }
        val inactive = (1..8).map { enqueue(off) }
        asAdmin { agents.deactivatePrinter(off.id) }
        val now = clock.instant().plus(Duration.ofHours(25))

        val runs = inParallel({ maintenance.run(now) }, { maintenance.run(now) })

        runs.forEach { it.failed shouldBe 0 }
        // `sent` volta a PENDING e vence na mesma rodada; `stale` e `inactive` vencem; `printed` perde o conteúdo
        (sent + stale).forEach { withClue("fila $it") { row(it).failure shouldBe "EXPIRED" } }
        sent.forEach {
            trail(it).map { e -> e.first } shouldContainExactly
                listOf("printing.job.expire-ack", "printing.job.expire")
        }
        stale.forEach { trail(it).map { e -> e.first } shouldContainExactly listOf("printing.job.expire") }
        inactive.forEach { row(it).status shouldBe "FAILED" }
        inactive.forEach { trail(it).map { e -> e.first }.count { a -> a != "printing.job.purge-content" } shouldBe 1 }
        printed.forEach {
            row(it).noContent shouldBe true
            trail(it).map { e -> e.first } shouldContainExactly listOf("printing.job.purge-content")
        }
    }

    // ---- lotes ----

    @Test
    fun `more jobs than the batch size are all handled in one run, for every step`() {
        val printer = newPrinter()
        val ids = (1..120).map { enqueue(printer) }
        claim(printer.agent, 200) shouldHaveSize 120
        val sentAt = row(ids.first()).sentAt.shouldNotBeNull()

        val requeue = maintenance.run(sentAt.plus(Duration.ofMinutes(3)))
        requeue.requeued shouldBeGreaterThanOrEqualTo 120
        ids.forEach { row(it).status shouldBe "PENDING" }

        val expire = maintenance.run(sentAt.plus(Duration.ofHours(25)))
        expire.expired shouldBeGreaterThanOrEqualTo 120
        ids.forEach { withClue("job $it") { row(it).failure shouldBe "EXPIRED" } }
        ids.forEach { row(it).noContent shouldBe false }

        val purge = maintenance.run(sentAt.plus(Duration.ofHours(50)))
        purge.purged shouldBeGreaterThanOrEqualTo 120
        ids.forEach { row(it).noContent shouldBe true }
        expire.failed + purge.failed + requeue.failed shouldBe 0
    }

    // ---- tentativas, posição e reentrega ----

    @Test
    fun `a release does not spend an attempt, so claims returned by the stream never end as ACK_TIMEOUT`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        val principal = principalOf(printer.agent)
        repeat(5) {
            claim(printer.agent) shouldHaveSize 1
            delivery.release(principal, job)
        }
        row(job).attempts shouldBe 0
        claim(printer.agent) shouldHaveSize 1
        advance(Duration.ofMinutes(3))

        maintenance.run()

        row(job).status shouldBe "PENDING"
        row(job).attempts shouldBe 1
        row(job).failure shouldBe null
    }

    @Test
    fun `a requeued job goes behind the jobs enqueued meanwhile and the long-poll delivers it again`() {
        val printer = newPrinter()
        val first = enqueue(printer)
        claim(printer.agent) shouldHaveSize 1
        val second = enqueue(printer)
        advance(Duration.ofMinutes(3))

        maintenance.run()

        row(first).position shouldBeGreaterThan row(second).position
        val response = poll(printer.agent)
        response.status shouldBe 200
        jsonMapper.readTree(response.contentAsString).values().map { it["id"].asString() } shouldContainExactly
            listOf(second.toString(), first.toString())
    }

    private fun poll(agent: Agent): MockHttpServletResponse {
        val result: MvcResult =
            mockMvc
                .request(GET, "/api/v1/print-agents/me/jobs?wait=0") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer ${agent.token}")
                }.andReturn()
        if (!result.request.isAsyncStarted) return result.response
        result.asyncResult
        return mockMvc.perform(asyncDispatch(result)).andReturn().response
    }

    // ---- impressora e agente desativados ----

    @Test
    fun `a stale SENT job of a deactivated printer ends as PRINTER_INACTIVE and is not delivered again`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        claim(printer.agent) shouldHaveSize 1
        asAdmin { agents.deactivatePrinter(printer.id) }
        advance(Duration.ofMinutes(3))

        maintenance.run()

        row(job).status shouldBe "FAILED"
        row(job).failure shouldBe "PRINTER_INACTIVE"
        claim(printer.agent) shouldHaveSize 0
    }

    @Test
    fun `a deactivated agent fails only its own queue and nothing of another agent or of a finished job`() {
        val mine = newPrinter()
        val other = newPrinter()
        val sent = enqueue(mine)
        val printed = enqueue(mine)
        val failed = enqueue(mine)
        claim(mine.agent, 3) shouldHaveSize 3
        ack(mine.agent, printed).status shouldBe 204
        ack(mine.agent, failed, "FAILED", "PAPER_JAM").status shouldBe 204
        val pending = enqueue(mine)
        val otherSent = enqueue(other)
        claim(other.agent) shouldHaveSize 1
        val otherPending = enqueue(other)
        val printedBefore = row(printed)
        val failedBefore = row(failed)
        val otherBefore = listOf(otherSent, otherPending).associateWith { row(it) }
        asAdmin { agents.deactivateAgent(mine.agent.id) }

        val run = maintenance.run()

        run.inactive shouldBeGreaterThanOrEqualTo 2
        listOf(sent, pending).forEach {
            row(it).status shouldBe "FAILED"
            row(it).failure shouldBe "PRINTER_INACTIVE"
        }
        row(printed) shouldBe printedBefore
        row(failed) shouldBe failedBefore
        otherBefore.forEach { (id, before) -> row(id) shouldBe before }
    }

    // ---- trilha ----

    @Test
    fun `every maintenance action is audited by the system, never with the content, and a rerun adds nothing`() {
        val printer = newPrinter()
        val off = newPrinter()
        val requeued = enqueue(printer)
        claim(printer.agent) shouldHaveSize 1
        val onOff = enqueue(off)
        val printed = enqueue(printer)
        claim(printer.agent) shouldHaveSize 1
        ack(printer.agent, printed).status shouldBe 204
        asAdmin { agents.deactivatePrinter(off.id) }
        val base = row(requeued).sentAt.shouldNotBeNull()
        maintenance.run(base.plus(Duration.ofMinutes(3)))
        val far = base.plus(Duration.ofHours(26))

        maintenance.run(far)

        trail(requeued).map { it.first } shouldContainExactly listOf("printing.job.expire-ack", "printing.job.expire")
        trail(onOff).map { it.first } shouldContainExactly
            listOf("printing.job.fail-inactive", "printing.job.purge-content")
        trail(printed).map { it.first } shouldContainExactly listOf("printing.job.purge-content")
        listOf(requeued, onOff, printed).flatMap { trail(it) }.forEach { (action, system, states) ->
            withClue(action) {
                system shouldBe true
                states shouldNotContain "SEGREDO"
                states.lowercase() shouldNotContain "content"
                states shouldNotContain "%PDF"
            }
        }
        val sizes = listOf(requeued, onOff, printed).map { trail(it).size }

        maintenance.run(far)

        listOf(requeued, onOff, printed).map { trail(it).size } shouldBe sizes
    }

    // ---- métrica ----

    private fun gauge(status: String) =
        registry
            .get("zeny.printing.jobs")
            .tag("status", status)
            .gauge()
            .value()

    @Test
    fun `the gauge matches the table at the same instant, has no PRINTED series and the exposition agrees`() {
        val printer = newPrinter()
        enqueue(printer)
        val sent = enqueue(printer)
        claim(printer.agent, 1)
        row(sent)

        gauge("PENDING") shouldBe count("SELECT count(*) FROM printing.print_job WHERE status = 'PENDING'").toDouble()
        gauge("SENT") shouldBe count("SELECT count(*) FROM printing.print_job WHERE status = 'SENT'").toDouble()
        registry.find("zeny.printing.jobs").tag("status", "PRINTED").gauge() shouldBe null
        val body =
            mockMvc
                .get("/actuator/prometheus")
                .andReturn()
                .response.contentAsString
        listOf("PENDING", "SENT", "FAILED").forEach { status ->
            val line = body.lines().single { it.startsWith("zeny_printing_jobs{") && it.contains("status=\"$status\"") }
            line.substringAfterLast(' ').toDouble() shouldBe gauge(status)
        }
        body shouldNotContain "zeny_printing_jobs{application=\"zeny-hotel-manager\",status=\"PRINTED\"}"
    }

    @Test
    fun `a FAILED job counts at the exact retention limit and leaves the count one tick later`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        claim(printer.agent)
        ack(printer.agent, job, "FAILED", "PAPER_JAM").status shouldBe 204
        val finished = row(job).finishedAt.shouldNotBeNull()
        gauge("FAILED") shouldBeGreaterThanOrEqualTo 1.0

        advance(Duration.between(clock.instant(), finished.plus(DAY)))
        val atLimit = gauge("FAILED")
        advance(TICK)
        val after = gauge("FAILED")

        val exactlyAtLimit =
            count("SELECT count(*) FROM printing.print_job WHERE status = 'FAILED' AND finished_at = '$finished'")
        (atLimit - after) shouldBe exactlyAtLimit.toDouble()
        exactlyAtLimit shouldBeGreaterThanOrEqualTo 1L
    }

    @Test
    fun `failures made by the maintenance enter the FAILED gauge and leave the queue gauge`() {
        val printer = newPrinter()
        val jobsOfPrinter = (1..3).map { enqueue(printer) }
        val pendingBefore = gauge("PENDING")
        val failedBefore = gauge("FAILED")
        asAdmin { agents.deactivatePrinter(printer.id) }

        maintenance.run()

        jobsOfPrinter.forEach { row(it).failure shouldBe "PRINTER_INACTIVE" }
        gauge("FAILED") shouldBeGreaterThanOrEqualTo failedBefore + 3
        gauge("PENDING") shouldBe count("SELECT count(*) FROM printing.print_job WHERE status = 'PENDING'").toDouble()
        (pendingBefore - gauge("PENDING")) shouldBeGreaterThanOrEqualTo 3.0
    }

    // ---- job agendado ----

    @Test
    fun `the scheduled job is deferred in tests, locked by name for ten minutes and runs the maintenance`() {
        PRINTING_MAINTENANCE_DEFERRED shouldBe "zeny.printing.maintenance-initial-delay=PT24H"
        properties.maintenanceInitialDelay shouldBe DAY
        val method = PrintJobsMaintenanceJob::class.java.getMethod("maintain")
        val lock = method.getAnnotation(SchedulerLock::class.java).shouldNotBeNull()
        lock.name shouldBe "printing-jobs-maintenance"
        lock.lockAtMostFor shouldBe "PT10M"
        val scheduled = method.getAnnotation(Scheduled::class.java).shouldNotBeNull()
        scheduled.fixedDelayString shouldContain "zeny.printing.maintenance-interval"
        scheduled.initialDelayString shouldContain "zeny.printing.maintenance-initial-delay"
        method.returnType shouldBe Void.TYPE

        val printer = newPrinter()
        val job = enqueue(printer)
        claim(printer.agent) shouldHaveSize 1
        advance(Duration.ofMinutes(3))
        jdbc
            .sql(
                """
                INSERT INTO shared.shedlock (name, lock_until, locked_at, locked_by)
                VALUES (:name, now() + interval '5 minutes', now(), 'other-instance')
                ON CONFLICT (name) DO UPDATE SET lock_until = EXCLUDED.lock_until, locked_by = EXCLUDED.locked_by
                """.trimIndent(),
            ).param("name", "printing-jobs-maintenance")
            .update()

        maintenanceJob.maintain()
        row(job).status shouldBe "SENT"

        jdbc
            .sql("UPDATE shared.shedlock SET lock_until = now() - interval '1 hour' WHERE name = :n")
            .param("n", "printing-jobs-maintenance")
            .update()
        maintenanceJob.maintain()
        row(job).status shouldBe "PENDING"
    }
}

/** O stream do agente depois da manutenção: o job reenviado volta com posição maior (M5 E10, Task 12). */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        TEST_MIGRATIONS,
        RESUBMISSION_JOB_DEFERRED,
        OUTBOX_PURGE_DEFERRED,
        FILES_PURGE_DEFERRED,
        GUESTS_REKEY_DEFERRED,
        INVENTORY_HORIZON_DEFERRED,
        PROVISIONAL_EXPIRY_DEFERRED,
        FNRH_SEND_DEFERRED,
        PRINTING_MAINTENANCE_DEFERRED,
        "zeny.printing.delivery-poll-interval=PT0.1S",
        "zeny.printing.stream-heartbeat=PT0.3S",
    ],
)
@ActiveProfiles("local")
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = [
        PostgresInitializer::class,
        TestEncryptionKeysInitializer::class,
        TestAuthInitializer::class,
        TestStorageInitializer::class,
    ],
)
@Import(IamTestConfiguration::class, MutableClockConfiguration::class)
class PrintJobsMaintenanceStreamAttackTest(
    @Autowired private val maintenance: PrintJobsMaintenance,
    @Autowired private val clock: MutableClock,
) : PrintAgentStreamAttackSupport() {
    private var advanced: Duration = Duration.ZERO

    @AfterEach
    fun restoreClock() {
        clock.advance(advanced.negated())
        advanced = Duration.ZERO
    }

    private fun expireAckNow() {
        clock.advance(Duration.ofMinutes(3))
        advanced = advanced.plus(Duration.ofMinutes(3))
        maintenance.run()
    }

    @Test
    fun `an open stream receives the requeued job again with a greater event id`() {
        val printer = newPrinter()
        val stream = open(printer.agent.token)
        val job = enqueue(printer)
        val first = stream.nextEvent(Duration.ofSeconds(5)).shouldNotBeNull()
        json(first)["id"].asString() shouldBe job.toString()

        expireAckNow()

        val again = stream.nextEvent(Duration.ofSeconds(5)).shouldNotBeNull()
        json(again)["id"].asString() shouldBe job.toString()
        again.id.shouldNotBeNull().toLong() shouldBeGreaterThan first.id.shouldNotBeNull().toLong()
        column("attempts", job) shouldBe "2"
    }

    @Test
    fun `a reconnection with the old Last-Event-ID gets the requeued job once`() {
        val printer = newPrinter()
        val job = enqueue(printer)
        val firstStream = open(printer.agent.token)
        val first = firstStream.nextEvent(Duration.ofSeconds(5)).shouldNotBeNull()
        firstStream.close()
        await().atMost(Duration.ofSeconds(5)).until { connections.openCount(printer.agent.id) == 0 }
        expireAckNow()
        statusOf(job) shouldBe "PENDING"

        val second = open(printer.agent.token, first.id)

        second.status shouldBe 200
        jobIds(second, 1) shouldContainExactly listOf(job.toString())
        second.eventsWithin(Duration.ofMillis(500)).shouldBeEmpty()
        statusOf(job) shouldBe "SENT"
        column("attempts", job) shouldBe "2"
    }
}
