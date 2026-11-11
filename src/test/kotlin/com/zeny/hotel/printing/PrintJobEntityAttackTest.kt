package com.zeny.hotel.printing

import com.zeny.hotel.printing.domain.PrintAgent
import com.zeny.hotel.printing.domain.PrintJob
import com.zeny.hotel.printing.domain.Printer
import com.zeny.hotel.testsupport.IntegrationTest
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** O job contra o banco real (M5 E10, Task 3): cada estado persistido e relido passa pelos CHECKs do schema. */
@IntegrationTest
class PrintJobEntityAttackTest(
    @Autowired private val em: EntityManager,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private val tag = "job-attack-" + UUID.randomUUID()
    private val now = Instant.parse("2026-11-11T10:00:00Z").truncatedTo(ChronoUnit.MICROS)
    private lateinit var agentId: UUID
    private lateinit var printerId: UUID
    private val secret = byteArrayOf(0, 1, 2, -1, -128, 127)

    @BeforeEach
    fun setUp() {
        val (agent, _) = PrintAgent.create("$tag-a", now)
        val printer = Printer.create("$tag-p", PrinterKind.A4_PDF, "USB001", agent.id)
        agentId = agent.id
        printerId = printer.id
        inTx {
            em.persist(agent)
            em.flush()
            em.persist(printer)
        }
    }

    @AfterEach
    fun cleanUp() {
        jdbc.update("DELETE FROM printing.print_job WHERE printer_id = ?", printerId)
        jdbc.update("DELETE FROM printing.printer WHERE agent_id = ?", agentId)
        jdbc.update("DELETE FROM printing.print_agent WHERE id = ?", agentId)
    }

    private fun <T> inTx(block: () -> T): T = tx.execute { block() }!!

    private fun newJob(): PrintJob {
        val position = jdbc.queryForObject("SELECT nextval('printing.print_job_position_seq')", Long::class.java)!!
        return PrintJob.create(UUID.randomUUID(), printerId, PrinterKind.A4_PDF, "a.b", secret.copyOf(), position, now)
    }

    /** Persiste o job depois de aplicar [step] e devolve a entidade relida do banco com o contexto limpo. */
    private fun roundTrip(
        job: PrintJob,
        step: (PrintJob) -> Unit = {},
    ): PrintJob {
        step(job)
        inTx { em.persist(job) }
        return inTx {
            em.clear()
            em.find(PrintJob::class.java, job.id)!!
        }
    }

    private fun update(
        id: UUID,
        step: (PrintJob) -> Unit,
    ): PrintJob {
        inTx { step(em.find(PrintJob::class.java, id)!!) }
        return inTx {
            em.clear()
            em.find(PrintJob::class.java, id)!!
        }
    }

    @Test
    fun `PENDING persistido guarda conteudo binario exato e campos nulos`() {
        val loaded = roundTrip(newJob())

        assertThat(loaded.status).isEqualTo(PrintJobStatus.PENDING)
        assertThat(loaded.content).containsExactly(*secret)
        assertThat(loaded.attempts).isZero()
        assertThat(loaded.sentAt).isNull()
        assertThat(loaded.deliveredTo).isNull()
        assertThat(loaded.finishedAt).isNull()
        assertThat(loaded.failureCode).isNull()
        assertThat(loaded.contentPurgedAt).isNull()
        assertThat(loaded.mediaType).isEqualTo("application/pdf")
        assertThat(loaded.createdAt).isEqualTo(now)
        assertThat(loaded.version).isEqualTo(0L)
    }

    @Test
    fun `SENT persistido guarda entrega e instante`() {
        val loaded = roundTrip(newJob()) { it.claim(agentId, now.plusSeconds(1)) }

        assertThat(loaded.status).isEqualTo(PrintJobStatus.SENT)
        assertThat(loaded.attempts).isEqualTo(1)
        assertThat(loaded.deliveredTo).isEqualTo(agentId)
        assertThat(loaded.sentAt).isEqualTo(now.plusSeconds(1))
        assertThat(loaded.finishedAt).isNull()
    }

    @Test
    fun `PRINTED e FAILED persistidos guardam finishedAt e o codigo`() {
        val printed =
            roundTrip(newJob()) {
                it.claim(agentId, now.plusSeconds(1))
                it.acknowledge(agentId, true, null, now.plusSeconds(2))
            }
        val failed =
            roundTrip(newJob()) {
                it.claim(agentId, now.plusSeconds(1))
                it.acknowledge(agentId, false, "NO_PAPER", now.plusSeconds(2))
            }

        assertThat(printed.status).isEqualTo(PrintJobStatus.PRINTED)
        assertThat(printed.finishedAt).isEqualTo(now.plusSeconds(2))
        assertThat(printed.failureCode).isNull()
        assertThat(failed.status).isEqualTo(PrintJobStatus.FAILED)
        assertThat(failed.finishedAt).isEqualTo(now.plusSeconds(2))
        assertThat(failed.failureCode).isEqualTo("NO_PAPER")
    }

    @Test
    fun `fail de PENDING e de SENT persistem sem violar CHECK`() {
        val a = roundTrip(newJob()) { it.fail(PrintJob.EXPIRED, now.plusSeconds(5)) }
        val b =
            roundTrip(newJob()) {
                it.claim(agentId, now.plusSeconds(1))
                it.fail(PrintJob.PRINTER_INACTIVE, now.plusSeconds(5))
            }

        assertThat(a.status).isEqualTo(PrintJobStatus.FAILED)
        assertThat(a.failureCode).isEqualTo("EXPIRED")
        assertThat(a.finishedAt).isEqualTo(now.plusSeconds(5))
        assertThat(b.failureCode).isEqualTo("PRINTER_INACTIVE")
        assertThat(b.deliveredTo).isEqualTo(agentId)
    }

    @Test
    fun `claim, expireAck, claim, ack atravessam o banco passo a passo`() {
        val job = roundTrip(newJob()) { it.claim(agentId, now.plusSeconds(1)) }
        val newPosition = jdbc.queryForObject("SELECT nextval('printing.print_job_position_seq')", Long::class.java)!!

        val back = update(job.id) { it.expireAck(now.plusSeconds(200), 3, newPosition) }
        assertThat(back.status).isEqualTo(PrintJobStatus.PENDING)
        assertThat(back.position).isEqualTo(newPosition)
        assertThat(back.attempts).isEqualTo(1)
        assertThat(back.sentAt).isNull()
        assertThat(back.deliveredTo).isNull()

        val again = update(job.id) { it.claim(agentId, now.plusSeconds(201)) }
        assertThat(again.attempts).isEqualTo(2)
        assertThat(again.status).isEqualTo(PrintJobStatus.SENT)

        val done = update(job.id) { it.acknowledge(agentId, true, null, now.plusSeconds(202)) }
        assertThat(done.status).isEqualTo(PrintJobStatus.PRINTED)
        assertThat(done.version).isEqualTo(3L)
    }

    @Test
    fun `expireAck na tentativa max persiste FAILED ACK_TIMEOUT`() {
        val job =
            roundTrip(newJob()) {
                it.claim(agentId, now)
                it.expireAck(now, 3, 1_000_001)
                it.claim(agentId, now)
                it.expireAck(now, 3, 1_000_002)
                it.claim(agentId, now)
            }

        val done = update(job.id) { it.expireAck(now.plusSeconds(9), 3, 1_000_003) }

        assertThat(done.status).isEqualTo(PrintJobStatus.FAILED)
        assertThat(done.failureCode).isEqualTo("ACK_TIMEOUT")
        assertThat(done.attempts).isEqualTo(3)
        assertThat(done.finishedAt).isEqualTo(now.plusSeconds(9))
    }

    @Test
    fun `purgeContent persiste conteudo nulo e content_purged_at, em PRINTED e em FAILED`() {
        val printed =
            roundTrip(newJob()) {
                it.claim(agentId, now)
                it.acknowledge(agentId, true, null, now)
            }
        val failed = roundTrip(newJob()) { it.fail("EXPIRED", now) }

        val p = update(printed.id) { it.purgeContent(now.plusSeconds(60)) }
        val f = update(failed.id) { it.purgeContent(now.plusSeconds(60)) }

        listOf(p, f).forEach {
            assertThat(it.content).isNull()
            assertThat(it.contentPurgedAt).isEqualTo(now.plusSeconds(60))
        }
        assertThat(
            jdbc.queryForObject(
                "SELECT content IS NULL FROM printing.print_job WHERE id = ?",
                Boolean::class.java,
                p.id,
            ),
        ).isTrue()
    }

    @Test
    fun `toString da entidade relida nao traz o conteudo`() {
        val loaded = roundTrip(newJob())

        assertThat(loaded.toString()).doesNotContain(secret.contentToString()).doesNotContain("content")
    }

    @Test
    fun `posicao repetida e recusada pelo banco`() {
        val first = roundTrip(newJob())
        val dup =
            PrintJob.create(
                UUID.randomUUID(),
                printerId,
                PrinterKind.A4_PDF,
                "a.b",
                byteArrayOf(1),
                first.position,
                now,
            )

        val result = runCatching { inTx { em.persist(dup) } }

        assertThat(result.isFailure).isTrue()
    }
}
