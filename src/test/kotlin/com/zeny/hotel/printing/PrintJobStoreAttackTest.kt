package com.zeny.hotel.printing

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.printing.application.NewPrinter
import com.zeny.hotel.printing.application.PrintAgentsService
import com.zeny.hotel.printing.application.PrintJobStore
import com.zeny.hotel.printing.application.PrintJobsService
import com.zeny.hotel.printing.application.PrinterChange
import com.zeny.hotel.printing.domain.PrintJob
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ataque ao `PrintJobStore` (M5 E10, Task 8): `claimPending` atômico com várias threads, escopo por agente e
 * impressora ativa, ordem de posição, reenvio com posição nova, e as consultas de manutenção nos limites exatos.
 * Banco compartilhado: dados próprios; as consultas globais usam instantes antigos ou filtram pelos ids do teste.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LargeClass", "TooManyFunctions", "LongParameterList")
class PrintJobStoreAttackTest(
    @Autowired private val jobs: PrintJobsService,
    @Autowired private val agents: PrintAgentsService,
    @Autowired private val store: PrintJobStore,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)

    @Suppress("UNCHECKED_CAST")
    private fun <T> inTx(block: () -> T): T {
        var result: Any? = null
        tx.executeWithoutResult { _ -> result = block() }
        return result as T
    }

    private fun suffix() = UuidV7.generate().toString().takeLast(12)

    private fun <T> asAdmin(block: () -> T): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(UserId(iam.adminId()).toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun pdf() = "%PDF-1.7 ${suffix()}".toByteArray()

    private fun newAgent(): UUID = asAdmin { agents.createAgent("Agente ${suffix()}").view.id }

    private fun newPrinter(agentId: UUID): UUID =
        asAdmin {
            agents.createPrinter(NewPrinter("Imp ${suffix()}", PrinterKind.A4_PDF, "addr-${suffix()}", agentId)).id
        }

    private fun enqueue(printer: UUID) = jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf()))

    private fun positionOf(id: UUID): Long =
        jdbc
            .sql("SELECT position FROM printing.print_job WHERE id = :id")
            .param("id", id)
            .query(Long::class.java)
            .single()

    private fun createdAtOf(id: UUID): Instant =
        jdbc
            .sql("SELECT created_at FROM printing.print_job WHERE id = :id")
            .param("id", id)
            .query { rs, _ -> rs.getTimestamp(1).toInstant() }
            .single()

    private fun claim(
        id: UUID,
        agent: UUID,
        now: Instant = Instant.now(),
    ) = inTx { store.lockById(id)!!.claim(agent, now) }

    private fun claimedIds(
        agent: UUID,
        limit: Int,
    ): List<UUID> = inTx { store.claimPending(agent, limit).map { it.id } }

    // --- claimPending: escopo e ordem ----------------------------------------------------------

    @Test
    fun `claimPending returns the pending jobs in position order and honours the limit`() {
        val agent = newAgent()
        val printer = newPrinter(agent)
        val ids = (1..5).map { enqueue(printer) }

        claimedIds(agent, 3) shouldContainExactly ids.take(3)
        claimedIds(agent, 100) shouldContainExactly ids
        claimedIds(agent, 1) shouldContainExactly ids.take(1)
        claimedIds(agent, 0) shouldHaveSize 0
    }

    @Test
    fun `claimPending mixes the printers of one agent by position and never takes another agent's job`() {
        val agent = newAgent()
        val other = newAgent()
        val p1 = newPrinter(agent)
        val p2 = newPrinter(agent)
        val foreign = newPrinter(other)
        val a = enqueue(p1)
        val x = enqueue(foreign)
        val b = enqueue(p2)
        val c = enqueue(p1)
        val y = enqueue(foreign)

        claimedIds(agent, 100) shouldContainExactly listOf(a, b, c)
        claimedIds(other, 100) shouldContainExactly listOf(x, y)
    }

    @Test
    fun `claimPending ignores SENT, PRINTED and FAILED jobs and only returns PENDING ones`() {
        val agent = newAgent()
        val printer = newPrinter(agent)
        val sent = enqueue(printer)
        val printed = enqueue(printer)
        val failed = enqueue(printer)
        val pending = enqueue(printer)
        claim(sent, agent)
        claim(printed, agent)
        claim(failed, agent)
        inTx { store.lockById(printed)!!.acknowledge(agent, true, null, Instant.now()) }
        inTx { store.lockById(failed)!!.acknowledge(agent, false, "BROKEN", Instant.now()) }

        claimedIds(agent, 100) shouldContainExactly listOf(pending)
    }

    @Test
    fun `claimPending skips an inactive printer but still serves the active ones of the same agent`() {
        val agent = newAgent()
        val off = newPrinter(agent)
        val on = newPrinter(agent)
        enqueue(off)
        val kept = enqueue(on)
        asAdmin { agents.deactivatePrinter(off) }

        claimedIds(agent, 100) shouldContainExactly listOf(kept)
        claimedIds(agent, 100) shouldContainExactly listOf(kept)
    }

    @Test
    fun `claimPending of a deactivated agent is empty and does not leak to another agent`() {
        val agent = newAgent()
        val other = newAgent()
        val printer = newPrinter(agent)
        enqueue(printer)
        asAdmin { agents.deactivateAgent(agent) }

        claimedIds(agent, 100) shouldHaveSize 0
        claimedIds(other, 100) shouldHaveSize 0
        claimedIds(UUID.randomUUID(), 100) shouldHaveSize 0
    }

    @Test
    fun `a printer moved to another agent takes its pending jobs with it`() {
        val first = newAgent()
        val second = newAgent()
        val printer = newPrinter(first)
        val job = enqueue(printer)
        asAdmin {
            val view = agents.printer(printer)
            agents.updatePrinter(printer, view.version, PrinterChange(view.name, view.address, second))
        }

        claimedIds(first, 100) shouldHaveSize 0
        claimedIds(second, 100) shouldContainExactly listOf(job)
    }

    @Test
    fun `a pending job with a lower position than an already sent one is returned, and the sent one is not`() {
        val agent = newAgent()
        val printer = newPrinter(agent)
        val low = enqueue(printer)
        val mid = enqueue(printer)
        val high = enqueue(printer)
        claim(high, agent)

        claimedIds(agent, 100) shouldContainExactly listOf(low, mid)
    }

    @Test
    fun `a re-enqueued job with a new position is claimed after the ones created in the meantime`() {
        val agent = newAgent()
        val printer = newPrinter(agent)
        val a = enqueue(printer)
        val b = enqueue(printer)
        claim(a, agent)
        inTx {
            val job = store.lockById(a)!!
            job.expireAck(Instant.now(), 3, store.nextPosition())
        }
        val c = enqueue(printer)

        (positionOf(a) > positionOf(b)) shouldBe true
        claimedIds(agent, 100) shouldContainExactly listOf(b, a, c)
    }

    // --- claimPending: concorrência ------------------------------------------------------------

    @Test
    fun `four concurrent transactions of the same agent each get a disjoint set and together all the jobs`() {
        val agent = newAgent()
        val printer = newPrinter(agent)
        val ids = (1..12).map { enqueue(printer) }.toSet()
        val threads = 4
        val claimed = Array(threads) { CountDownLatch(1) }
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures =
                (0 until threads).map { me ->
                    pool.submit<List<UUID>> {
                        inTx {
                            val rows = store.claimPending(agent, 3)
                            rows.forEach { it.claim(agent, Instant.now()) }
                            store.flush()
                            claimed[me].countDown()
                            claimed.forEach { it.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true }
                            rows.map { it.id }
                        }
                    }
                }
            val results = futures.map { it.get(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS) }

            results.forEach { it shouldHaveSize 3 }
            results.flatten() shouldHaveSize 12
            results.flatten().toSet() shouldBe ids
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `eight transactions racing for few jobs never hand one job twice and never block`() {
        val agent = newAgent()
        val printer = newPrinter(agent)
        val ids = (1..5).map { enqueue(printer) }.toSet()
        val threads = 8
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures =
                (1..threads).map {
                    pool.submit<List<UUID>> {
                        inTx {
                            ready.countDown()
                            go.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            val rows = store.claimPending(agent, 2)
                            rows.forEach { it.claim(agent, Instant.now()) }
                            store.flush()
                            done.countDown()
                            done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
                            rows.map { it.id }
                        }
                    }
                }
            ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
            go.countDown()
            val flat = futures.flatMap { it.get(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS) }

            flat shouldHaveSize flat.toSet().size
            flat.toSet().all { it in ids } shouldBe true
            (flat.size <= ids.size) shouldBe true
        } finally {
            pool.shutdownNow()
        }
        claimedIds(agent, 100).size shouldBe ids.size -
            ids.count { id ->
                jdbc
                    .sql("SELECT status FROM printing.print_job WHERE id = :id")
                    .param("id", id)
                    .query(String::class.java)
                    .single() == "SENT"
            }
    }

    @Test
    fun `a transaction holding rows does not block another claim, which gets only the rest`() {
        val agent = newAgent()
        val printer = newPrinter(agent)
        val ids = (1..3).map { enqueue(printer) }
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(1)
        try {
            val first =
                pool.submit<List<UUID>> {
                    inTx {
                        val rows = store.claimPending(agent, 2)
                        holding.countDown()
                        release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        rows.map { it.id }
                    }
                }
            holding.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
            val second = claimedIds(agent, 10)
            release.countDown()
            val firstRows = first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

            firstRows shouldContainExactly ids.take(2)
            second shouldContainExactly ids.drop(2)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `a job locked by lockById is skipped by a concurrent claimPending and a rolled back claim frees it`() {
        val agent = newAgent()
        val printer = newPrinter(agent)
        val id = enqueue(printer)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(1)
        try {
            val holder =
                pool.submit {
                    runCatching {
                        inTx {
                            store.lockById(id)
                            locked.countDown()
                            release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            error("desfaz")
                        }
                    }
                }
            locked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
            val during = claimedIds(agent, 10)
            release.countDown()
            holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

            during shouldHaveSize 0
            claimedIds(agent, 10) shouldContainExactly listOf(id)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `two transactions that claim the same job one after the other cannot both mark it SENT`() {
        val agent = newAgent()
        val printer = newPrinter(agent)
        val id = enqueue(printer)

        val first = inTx { store.claimPending(agent, 1).onEach { it.claim(agent, Instant.now()) }.map { it.id } }
        val second = claimedIds(agent, 1)

        first shouldContainExactly listOf(id)
        second shouldHaveSize 0
        jdbc
            .sql("SELECT attempts FROM printing.print_job WHERE id = :id")
            .param("id", id)
            .query(Int::class.java)
            .single() shouldBe 1
    }

    // --- unackedSent ---------------------------------------------------------------------------

    @Test
    fun `unackedSent returns only SENT jobs delivered to that agent after the position, in order`() {
        val agent = newAgent()
        val other = newAgent()
        val printer = newPrinter(agent)
        val a = enqueue(printer)
        val b = enqueue(printer)
        val c = enqueue(printer)
        val printed = enqueue(printer)
        val foreign = enqueue(printer)
        enqueue(printer) // PENDING
        listOf(a, b, c, printed).forEach { claim(it, agent) }
        claim(foreign, other)
        inTx { store.lockById(printed)!!.acknowledge(agent, true, null, Instant.now()) }

        inTx { store.unackedSent(agent, 0).map { it.id } } shouldContainExactly listOf(a, b, c)
        inTx { store.unackedSent(agent, positionOf(a)).map { it.id } } shouldContainExactly listOf(b, c)
        inTx { store.unackedSent(agent, positionOf(c)).map { it.id } } shouldHaveSize 0
        inTx { store.unackedSent(agent, Long.MAX_VALUE) } shouldHaveSize 0
        inTx { store.unackedSent(other, 0).map { it.id } } shouldContainExactly listOf(foreign)
    }

    @Test
    fun `unackedSent after a printer transfer still follows the agent the job was delivered to`() {
        val first = newAgent()
        val second = newAgent()
        val printer = newPrinter(first)
        val id = enqueue(printer)
        claim(id, first)
        asAdmin {
            val view = agents.printer(printer)
            agents.updatePrinter(printer, view.version, PrinterChange(view.name, view.address, second))
        }

        inTx { store.unackedSent(first, 0).map { it.id } } shouldContainExactly listOf(id)
        inTx { store.unackedSent(second, 0) } shouldHaveSize 0
    }

    // --- manutenção ----------------------------------------------------------------------------

    @Test
    fun `expiredSent is strict at the exact instant, oldest first, and honours the limit`() {
        val agent = newAgent()
        val printer = newPrinter(agent)
        val old = Instant.parse("2001-01-01T00:00:00Z")
        val newer = Instant.parse("2001-01-02T00:00:00Z")
        val a = enqueue(printer)
        val b = enqueue(printer)
        val pending = enqueue(printer)
        claim(b, agent, newer)
        claim(a, agent, old)
        val cutoff = Instant.parse("2001-06-01T00:00:00Z")
        val mine = setOf(a, b, pending)

        fun expired(
            before: Instant,
            limit: Int = MANY,
        ) = inTx { store.expiredSent(before, limit).map { it.id }.filter { it in mine } }

        expired(old) shouldHaveSize 0
        expired(old.plusMillis(1)) shouldContainExactly listOf(a)
        expired(cutoff) shouldContainExactly listOf(a, b)
        // O banco é compartilhado: outros testes deixam SENT antigos, então o limite só vale como teto.
        inTx { store.expiredSent(cutoff, 1) } shouldHaveSize 1
    }

    @Test
    fun `expiredSent leaves out jobs already acknowledged`() {
        val agent = newAgent()
        val printer = newPrinter(agent)
        val old = Instant.parse("2002-01-01T00:00:00Z")
        val done = enqueue(printer)
        val waiting = enqueue(printer)
        claim(done, agent, old)
        claim(waiting, agent, old)
        inTx { store.lockById(done)!!.acknowledge(agent, true, null, old) }

        inTx { store.expiredSent(old.plusSeconds(1), MANY).map { it.id }.filter { it == done || it == waiting } }
            .shouldContainExactly(listOf(waiting))
    }

    @Test
    fun `stalePending is strict at the creation instant and ignores jobs that are not pending`() {
        val agent = newAgent()
        val printer = newPrinter(agent)
        val pending = enqueue(printer)
        val sent = enqueue(printer)
        claim(sent, agent)
        val created = createdAtOf(pending)
        val mine = setOf(pending, sent)

        fun stale(before: Instant) = inTx { store.stalePending(before, MANY).map { it.id }.filter { it in mine } }

        stale(created) shouldHaveSize 0
        stale(created.plusSeconds(1)) shouldContainExactly listOf(pending)
        stale(created.minusSeconds(1)) shouldHaveSize 0
    }

    @Test
    fun `stalePending lists the oldest first and honours the limit`() {
        val agent = newAgent()
        val printer = newPrinter(agent)
        val oldest = enqueue(printer)
        val younger = enqueue(printer)
        jdbc
            .sql("UPDATE printing.print_job SET created_at = :t WHERE id = :id")
            .param("t", java.sql.Timestamp.from(Instant.parse("2003-01-01T00:00:00Z")))
            .param("id", oldest)
            .update()
        jdbc
            .sql("UPDATE printing.print_job SET created_at = :t WHERE id = :id")
            .param("t", java.sql.Timestamp.from(Instant.parse("2003-01-02T00:00:00Z")))
            .param("id", younger)
            .update()
        val cutoff = Instant.parse("2003-06-01T00:00:00Z")

        inTx { store.stalePending(cutoff, 10).map { it.id } } shouldContainExactly listOf(oldest, younger)
        inTx { store.stalePending(cutoff, 1).map { it.id } } shouldContainExactly listOf(oldest)
    }

    @Test
    fun `ofInactivePrinters returns PENDING and SENT of inactive printers only and honours the limit`() {
        val agent = newAgent()
        val off = newPrinter(agent)
        val on = newPrinter(agent)
        val pending = enqueue(off)
        val sent = enqueue(off)
        val printed = enqueue(off)
        val failed = enqueue(off)
        val activeOne = enqueue(on)
        claim(sent, agent)
        claim(printed, agent)
        claim(failed, agent)
        inTx { store.lockById(printed)!!.acknowledge(agent, true, null, Instant.now()) }
        inTx { store.lockById(failed)!!.acknowledge(agent, false, null, Instant.now()) }
        val mine = setOf(pending, sent, printed, failed, activeOne)

        fun found(limit: Int = MANY) = inTx { store.ofInactivePrinters(limit).map { it.id }.filter { it in mine } }

        found() shouldHaveSize 0
        asAdmin { agents.deactivatePrinter(off) }
        found() shouldContainExactly listOf(pending, sent)
        inTx { store.ofInactivePrinters(1) } shouldHaveSize 1
        asAdmin { agents.deactivateAgent(agent) }
        found() shouldContainExactly listOf(pending, sent)
    }

    @Test
    fun `a deactivated agent alone does not make its active printers count as inactive`() {
        val agent = newAgent()
        val printer = newPrinter(agent)
        val id = enqueue(printer)
        asAdmin { agents.deactivateAgent(agent) }

        inTx { store.ofInactivePrinters(MANY).map { it.id } }.contains(id) shouldBe false
    }

    @Test
    fun `purgeable is strict at the finish instant and only has finished jobs that still keep content`() {
        val agent = newAgent()
        val printer = newPrinter(agent)
        val finished = Instant.parse("2004-01-01T00:00:00Z")
        val printed = enqueue(printer)
        val failed = enqueue(printer)
        val running = enqueue(printer)
        val pending = enqueue(printer)
        claim(printed, agent)
        claim(failed, agent)
        claim(running, agent)
        inTx { store.lockById(printed)!!.acknowledge(agent, true, null, finished) }
        inTx { store.lockById(failed)!!.acknowledge(agent, false, "BROKEN", finished.plusSeconds(10)) }
        val cutoff = Instant.parse("2004-06-01T00:00:00Z")
        val mine = setOf(printed, failed, running, pending)

        fun purgeable(
            before: Instant,
            limit: Int = MANY,
        ) = inTx { store.purgeable(before, limit).map { it.id }.filter { it in mine } }

        purgeable(finished) shouldHaveSize 0
        purgeable(finished.plusMillis(1)) shouldContainExactly listOf(printed)
        purgeable(cutoff) shouldContainExactly listOf(printed, failed)
        inTx { store.purgeable(cutoff, 1) } shouldHaveSize 1

        inTx { store.lockById(printed)!!.purgeContent(cutoff) }
        purgeable(cutoff) shouldContainExactly listOf(failed)
        inTx { store.lockById(failed)!!.purgeContent(cutoff) }
        purgeable(cutoff) shouldHaveSize 0
    }

    @Test
    fun `countByStatus counts at least the jobs of this test in each status and moves with the claims`() {
        val agent = newAgent()
        val printer = newPrinter(agent)
        val ids = (1..4).map { enqueue(printer) }
        claim(ids[0], agent)
        claim(ids[1], agent)
        inTx { store.lockById(ids[0])!!.acknowledge(agent, true, null, Instant.now()) }
        inTx { store.lockById(ids[1])!!.acknowledge(agent, false, "X", Instant.now()) }
        claim(ids[2], agent)

        val counts = inTx { store.countByStatus() }

        (counts.getValue(PrintJobStatus.PRINTED) >= 1L) shouldBe true
        (counts.getValue(PrintJobStatus.FAILED) >= 1L) shouldBe true
        (counts.getValue(PrintJobStatus.SENT) >= 1L) shouldBe true
        (counts.getValue(PrintJobStatus.PENDING) >= 1L) shouldBe true
        counts.values.all { it > 0L } shouldBe true
    }

    // --- página do store -----------------------------------------------------------------------

    @Test
    fun `store page orders by position descending and the cursor is the last position returned`() {
        val agent = newAgent()
        val printer = newPrinter(agent)
        val ids = (1..3).map { enqueue(printer) }

        val first = inTx { store.page(null, printer, PageParams(limit = 2)) }
        val second = inTx { store.page(null, printer, PageParams(cursor = first.nextCursor, limit = 2)) }

        first.items.map { it.id } shouldContainExactly ids.reversed().take(2)
        second.items.map { it.id } shouldContainExactly listOf(ids.first())
        second.nextCursor shouldBe null
        first.items.map { it.position } shouldBe first.items.map { it.position }.sortedDescending()
    }

    @Test
    fun `store save and lockById round trip keep the content as given, byte for byte`() {
        val agent = newAgent()
        val printer = newPrinter(agent)
        val id = enqueue(printer)

        val job: PrintJob = inTx { store.lockById(id)!! }

        job.printerId shouldBe printer
        job.status shouldBe PrintJobStatus.PENDING
        inTx { store.lockById(UUID.randomUUID()) } shouldBe null
        job.toString().contains("content") shouldBe false
    }

    private companion object {
        const val TIMEOUT_SECONDS = 20L
        const val MANY = 100_000
    }
}
