package com.zeny.hotel.printing

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.printing.application.NewPrinter
import com.zeny.hotel.printing.application.PrintAgentsService
import com.zeny.hotel.printing.application.PrintJobStore
import com.zeny.hotel.printing.application.PrintJobsService
import com.zeny.hotel.printing.domain.PrintJobSummary
import com.zeny.hotel.shared.ContentCipher
import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.ErrorKind
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A fila de jobs (M5 E10, Task 8): cifragem, tetos, permissões, trilha sem conteúdo e a reivindicação atômica. */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("TooManyFunctions", "LongParameterList")
class PrintJobsServiceTest(
    @Autowired private val jobs: PrintJobsService,
    @Autowired private val agents: PrintAgentsService,
    @Autowired private val store: PrintJobStore,
    @Autowired private val cipher: ContentCipher,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired transactionManager: PlatformTransactionManager,
    @Autowired private val clock: Clock,
    @Autowired private val properties: com.zeny.hotel.printing.infrastructure.PrintingProperties,
) {
    private val tx = TransactionTemplate(transactionManager)

    /** Uma transação própria; o `it` do callback do Spring fica fora do bloco. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> inTx(block: () -> T): T {
        var result: Any? = null
        tx.executeWithoutResult { _ -> result = block() }
        return result as T
    }

    private fun suffix() = UuidV7.generate().toString().takeLast(12)

    private fun <T> actingAs(
        user: UserId,
        block: () -> T,
    ): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(user.toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        val previous = SecurityContextHolder.getContext().authentication
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.getContext().authentication = previous
        }
    }

    private fun <T> asAdmin(block: () -> T): T = actingAs(UserId(iam.adminId()), block)

    private fun userWith(code: com.zeny.hotel.shared.PermissionCode): UserId =
        iam.createUserWith(TestGrant(PrintingPermissions.permissions.single { it.code == code }, ALL)).id

    private fun errorOf(block: () -> Unit): DomainException = shouldThrow<DomainException>(block)

    private fun pdf(marker: String = suffix()) = "%PDF-1.7 SEGREDO-$marker".toByteArray()

    private fun newAgent(): UUID = asAdmin { agents.createAgent("Agente ${suffix()}").view.id }

    private fun newPrinter(
        kind: PrinterKind = PrinterKind.A4_PDF,
        agentId: UUID = newAgent(),
    ): UUID = asAdmin { agents.createPrinter(NewPrinter("Imp ${suffix()}", kind, "addr-${suffix()}", agentId)).id }

    private fun storedContent(id: UUID): ByteArray =
        jdbc
            .sql("SELECT content FROM printing.print_job WHERE id = :id")
            .param("id", id)
            .query { rs, _ -> rs.getBytes(1) }
            .single()

    private fun positionOf(id: UUID): Long =
        jdbc
            .sql("SELECT position FROM printing.print_job WHERE id = :id")
            .param("id", id)
            .query(Long::class.java)
            .single()

    @Test
    fun `enqueue stores the content encrypted with the job id as associated data`() {
        val printer = newPrinter()
        val content = pdf()

        val id = jobs.enqueue(NewPrintJob(printer, "frontdesk.registration-card", content))

        val stored = storedContent(id)
        stored.contentEquals(content) shouldBe false
        cipher.decrypt(stored, id.toString().toByteArray()).contentEquals(content) shouldBe true
        shouldThrow<com.zeny.hotel.shared.FieldDecryptionException> {
            cipher.decrypt(stored, UUID.randomUUID().toString().toByteArray())
        }
    }

    @Test
    fun `positions grow with each job`() {
        val printer = newPrinter()

        val first = jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf()))
        val second = jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf()))

        (positionOf(second) > positionOf(first)) shouldBe true
    }

    @Test
    fun `the PrintingApi accepts up to the general ceiling and refuses above it`() {
        val printer = newPrinter(PrinterKind.THERMAL_ESCPOS)
        val ceiling = properties.maxContentBytes

        jobs.enqueue(NewPrintJob(printer, "frontdesk.big", ByteArray(ceiling) { 1 }))
        val error = errorOf { jobs.enqueue(NewPrintJob(printer, "frontdesk.big", ByteArray(ceiling + 1) { 1 })) }

        error.code shouldBe PrintingErrorCodes.PRINT_CONTENT_TOO_LARGE
        error.kind shouldBe ErrorKind.RULE_VIOLATION
    }

    @Test
    fun `the manual route refuses above the manual ceiling`(): Unit =
        asAdmin {
            val printer = newPrinter(PrinterKind.THERMAL_ESCPOS)
            val ceiling = properties.maxManualContentBytes

            jobs.create(NewPrintJob(printer, "frontdesk.manual", ByteArray(ceiling) { 1 })).printerId shouldBe printer
            val error = errorOf { jobs.create(NewPrintJob(printer, "frontdesk.manual", ByteArray(ceiling + 1) { 1 })) }

            error.code shouldBe PrintingErrorCodes.PRINT_CONTENT_TOO_LARGE
        }

    @Test
    fun `empty content is refused`() {
        val printer = newPrinter(PrinterKind.THERMAL_ESCPOS)

        errorOf { jobs.enqueue(NewPrintJob(printer, "frontdesk.card", ByteArray(0))) }.code shouldBe
            PrintingErrorCodes.PRINT_CONTENT_EMPTY
    }

    @Test
    fun `an A4_PDF printer needs a PDF and a thermal printer takes any bytes`() {
        val a4 = newPrinter(PrinterKind.A4_PDF)
        val thermal = newPrinter(PrinterKind.THERMAL_ESCPOS)

        errorOf { jobs.enqueue(NewPrintJob(a4, "frontdesk.card", "nao e pdf".toByteArray())) }.code shouldBe
            PrintingErrorCodes.PRINT_CONTENT_NOT_PDF
        errorOf { jobs.enqueue(NewPrintJob(a4, "frontdesk.card", "%PDF".toByteArray())) }.code shouldBe
            PrintingErrorCodes.PRINT_CONTENT_NOT_PDF
        jobs.enqueue(NewPrintJob(thermal, "frontdesk.card", byteArrayOf(0x1B, 0x40))) shouldNotBe null
    }

    @Test
    fun `an unknown printer is 400 and an inactive one is 422`() {
        val inactive = newPrinter()
        asAdmin { agents.deactivatePrinter(inactive) }

        val unknown = errorOf { jobs.enqueue(NewPrintJob(UUID.randomUUID(), "frontdesk.card", pdf())) }
        val off = errorOf { jobs.enqueue(NewPrintJob(inactive, "frontdesk.card", pdf())) }

        unknown.code shouldBe PrintingErrorCodes.UNKNOWN_PRINTER
        unknown.kind shouldBe ErrorKind.INVALID_INPUT
        off.code shouldBe PrintingErrorCodes.PRINTER_INACTIVE
        off.kind shouldBe ErrorKind.RULE_VIOLATION
    }

    @Test
    fun `the document type follows the module-dot-name format and invisibles do not pass`() {
        val printer = newPrinter()
        val invisible = 0x200B.toChar()

        listOf("Frontdesk.card", "card", "frontdesk.card$invisible", "frontdesk card", "a.".padEnd(101, 'a'), "")
            .forEach { type ->
                val error = errorOf { jobs.enqueue(NewPrintJob(printer, type, pdf())) }
                error.code shouldBe ErrorCodes.VALIDATION_FAILED
                error.violations.map { it.field } shouldBe listOf("documentType")
            }
    }

    @Test
    fun `jobs needs printing jobs read, create needs jobs manage and enqueue needs nothing`() {
        val printer = newPrinter()
        val reader = userWith(PrintingPermissions.JOBS_READ)
        val manager = userWith(PrintingPermissions.JOBS_MANAGE)

        actingAs(reader) {
            errorOf { jobs.create(NewPrintJob(printer, "frontdesk.card", pdf())) }.kind shouldBe
                ErrorKind.PERMISSION_DENIED
            jobs.jobs(null, printer, PageParams()).items shouldHaveSize 0
        }
        actingAs(manager) {
            errorOf { jobs.jobs(null, printer, PageParams()) }.kind shouldBe ErrorKind.PERMISSION_DENIED
            jobs.create(NewPrintJob(printer, "frontdesk.card", pdf())).status shouldBe PrintJobStatus.PENDING
        }
        SecurityContextHolder.clearContext()
        jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf())) shouldNotBe null
    }

    @Test
    fun `jobs filters by status and printer, pages by cursor and never shows the content`(): Unit =
        asAdmin {
            val printer = newPrinter()
            val ids = (1..3).map { jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf())) }
            val other = newPrinter()
            jobs.enqueue(NewPrintJob(other, "frontdesk.card", pdf()))

            val first = jobs.jobs(PrintJobStatus.PENDING, printer, PageParams(limit = 2))
            val second = jobs.jobs(PrintJobStatus.PENDING, printer, PageParams(cursor = first.nextCursor, limit = 2))

            first.items shouldHaveSize 2
            second.items shouldHaveSize 1
            (first.items + second.items).map { it.id } shouldContainExactly ids.reversed()
            jobs.jobs(PrintJobStatus.PRINTED, printer, PageParams()).items shouldHaveSize 0
            first.items.first().toString() shouldNotContain "SEGREDO"
        }

    @Test
    fun `the audit trail of the creation has no content`(): Unit =
        asAdmin {
            val printer = newPrinter()
            val marker = suffix()
            val id = jobs.create(NewPrintJob(printer, "frontdesk.card", pdf(marker))).id

            val states =
                jdbc
                    .sql(
                        "SELECT CAST(after_state AS text) FROM audit.audit_entry " +
                            "WHERE action = 'printing.job.create' AND CAST(after_state AS text) LIKE :like",
                    ).param("like", "%$id%")
                    .query(String::class.java)
                    .list()

            states shouldHaveSize 1
            states.single() shouldNotContain marker
            states.single() shouldNotContain "content"
            states.single() shouldNotContain
                java.util.Base64
                    .getEncoder()
                    .encodeToString(pdf(marker))
        }

    @Test
    fun `NewPrintJob keeps the content out of toString, equals and hashCode`() {
        val printer = UUID.randomUUID()
        val job = NewPrintJob(printer, "frontdesk.card", "%PDF-SEGREDO".toByteArray())

        job.toString() shouldNotContain "SEGREDO"
        job.toString() shouldNotContain "PDF"
        job.hashCode() shouldBe NewPrintJob(printer, "frontdesk.card", ByteArray(1)).hashCode()
        job shouldBe NewPrintJob(printer, "frontdesk.card", ByteArray(1))
    }

    @Test
    fun `claimPending never hands the same job to two concurrent transactions`() {
        val agent = newAgent()
        val printer = newPrinter(agentId = agent)
        val ids = (1..4).map { jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf())) }.toSet()
        val claimedLatches = listOf(CountDownLatch(1), CountDownLatch(1))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures =
                (0..1).map { me ->
                    pool.submit<List<UUID>> {
                        inTx {
                            val claimed = store.claimPending(agent, 2)
                            claimed.forEach { it.claim(agent, clock.instant()) }
                            store.flush()
                            claimedLatches[me].countDown()
                            claimedLatches[1 - me].await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
                            claimed.map { it.id }
                        }
                    }
                }
            val results = futures.map { it.get(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS) }

            results.forEach { it shouldHaveSize 2 }
            (results[0] + results[1]).toSet() shouldBe ids
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `claimPending delivers a pending job with a lower position than an already sent one`() {
        val agent = newAgent()
        val printer = newPrinter(agentId = agent)
        val low = jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf()))
        val high = jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf()))
        inTx { store.lockById(high)!!.claim(agent, clock.instant()) }

        val claimed = inTx { store.claimPending(agent, 10).map { it.id } }

        claimed shouldContainExactly listOf(low)
    }

    @Test
    fun `claimPending only takes pending jobs of the active printers of that agent`() {
        val agent = newAgent()
        val otherAgent = newAgent()
        val active = newPrinter(agentId = agent)
        val inactive = newPrinter(agentId = agent)
        val foreign = newPrinter(agentId = otherAgent)
        val mine = jobs.enqueue(NewPrintJob(active, "frontdesk.card", pdf()))
        jobs.enqueue(NewPrintJob(inactive, "frontdesk.card", pdf()))
        jobs.enqueue(NewPrintJob(foreign, "frontdesk.card", pdf()))
        asAdmin { agents.deactivatePrinter(inactive) }

        val claimed = inTx { store.claimPending(agent, 10).map { it.id } }

        claimed shouldContainExactly listOf(mine)
    }

    @Test
    fun `claimPending hands nothing to an inactive agent`() {
        val agent = newAgent()
        val printer = newPrinter(agentId = agent)
        jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf()))
        asAdmin { agents.deactivateAgent(agent) }

        inTx { store.claimPending(agent, 10) } shouldHaveSize 0
    }

    @Test
    fun `unackedSent returns only the sent jobs of that agent after the position`() {
        val agent = newAgent()
        val printer = newPrinter(agentId = agent)
        val a = jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf()))
        val b = jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf()))
        inTx { store.claimPending(agent, 10).forEach { it.claim(agent, clock.instant()) } }

        inTx { store.unackedSent(agent, positionOf(a)).map { it.id } } shouldContainExactly listOf(b)
        inTx { store.unackedSent(agent, 0).map { it.id } } shouldContainExactly listOf(a, b)
        inTx { store.unackedSent(UUID.randomUUID(), 0) } shouldHaveSize 0
    }

    @Test
    fun `maintenance queries find expired, stale, inactive-printer and purgeable jobs`() {
        val agent = newAgent()
        val printer = newPrinter(agentId = agent)
        val sent = jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf()))
        inTx { store.lockById(sent)!!.claim(agent, clock.instant()) }
        val pending = jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf()))
        val mine = setOf(sent, pending)
        val later = clock.instant().plusSeconds(3600)

        // O banco é compartilhado: cada consulta é filtrada pelos jobs deste teste.
        fun ids(rows: List<PrintJobSummary>) = rows.map { it.id }.filter { it in mine }

        ids(inTx { store.expiredSent(later, MANY) }) shouldContainExactly listOf(sent)
        ids(inTx { store.stalePending(later, MANY) }) shouldContainExactly listOf(pending)
        ids(inTx { store.stalePending(clock.instant().minusSeconds(3600), MANY) }) shouldHaveSize 0
        ids(inTx { store.purgeable(later, MANY) }) shouldHaveSize 0

        inTx { store.lockById(sent)!!.acknowledge(agent, true, null, clock.instant()) }
        ids(inTx { store.purgeable(later, MANY) }) shouldContainExactly listOf(sent)
        ids(inTx { store.purgeable(clock.instant().minusSeconds(3600), MANY) }) shouldHaveSize 0

        ids(inTx { store.ofInactivePrinters(MANY) }) shouldHaveSize 0
        asAdmin { agents.deactivatePrinter(printer) }
        ids(inTx { store.ofInactivePrinters(MANY) }) shouldContainExactly listOf(pending)
        (store.countByStatus()[PrintJobStatus.PENDING] ?: 0L) shouldNotBe 0L
    }

    private companion object {
        const val TIMEOUT_SECONDS = 15L
        const val MANY = 100_000
    }
}
