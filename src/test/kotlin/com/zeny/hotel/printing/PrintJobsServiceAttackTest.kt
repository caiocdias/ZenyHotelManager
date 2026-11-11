package com.zeny.hotel.printing

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.printing.application.NewPrinter
import com.zeny.hotel.printing.application.PrintAgentsService
import com.zeny.hotel.printing.application.PrintJobStore
import com.zeny.hotel.printing.application.PrintJobView
import com.zeny.hotel.printing.application.PrintJobsService
import com.zeny.hotel.shared.ContentCipher
import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.ErrorKind
import com.zeny.hotel.shared.FieldDecryptionException
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ataque ao `PrintJobsService` (M5 E10, Task 8): cifragem com o id como dado associado, tetos exatos, validações de
 * conteúdo e tipo, impressora inexistente, inativa ou de agente desativado, permissões por role, posições sob
 * criação concorrente, página por cursor sem conteúdo, trilha e log sem conteúdo. Banco compartilhado: dados próprios.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LargeClass", "TooManyFunctions", "LongParameterList")
class PrintJobsServiceAttackTest(
    @Autowired private val jobs: PrintJobsService,
    @Autowired private val agents: PrintAgentsService,
    @Autowired private val store: PrintJobStore,
    @Autowired private val cipher: ContentCipher,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val properties: com.zeny.hotel.printing.infrastructure.PrintingProperties,
    @Autowired private val txManager: org.springframework.transaction.PlatformTransactionManager,
) {
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
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun <T> asAdmin(block: () -> T): T = actingAs(UserId(iam.adminId()), block)

    private fun userWith(code: PermissionCode): UserId =
        iam.createUserWith(TestGrant(PrintingPermissions.permissions.single { it.code == code }, ALL)).id

    private fun userWithTemplate(template: RoleTemplate): UserId {
        val roleId =
            jdbc
                .sql("SELECT id FROM iam.role WHERE template_key = :k")
                .param("k", template.name)
                .query(UUID::class.java)
                .single()
        val user = iam.createUser()
        iam.assignRole(user, RoleId(roleId))
        return user.id
    }

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

    private fun jobCount(printerId: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM printing.print_job WHERE printer_id = :p")
            .param("p", printerId)
            .query(Int::class.java)
            .single()

    private fun auditStates(jobId: UUID): List<String> =
        jdbc
            .sql(
                "SELECT CAST(after_state AS text) FROM audit.audit_entry " +
                    "WHERE action = 'printing.job.create' AND CAST(after_state AS text) LIKE :like",
            ).param("like", "%$jobId%")
            .query { rs, _ -> rs.getString(1) as String }
            .list()

    private fun associated(id: UUID) = PrintJobsService.associatedData(id)

    // --- cifragem ------------------------------------------------------------------------------

    @Test
    fun `the associated data of the cipher is the UTF-8 text of the job id`() {
        val id = UUID.randomUUID()

        associated(id).contentEquals(id.toString().toByteArray(Charsets.UTF_8)) shouldBe true
    }

    @Test
    fun `the stored content never shows the plaintext and the same content encrypts differently in two jobs`() {
        val printer = newPrinter()
        val content = pdf("IGUAL")

        val a = jobs.enqueue(NewPrintJob(printer, "frontdesk.card", content))
        val b = jobs.enqueue(NewPrintJob(printer, "frontdesk.card", content))

        val storedA = storedContent(a)
        val storedB = storedContent(b)
        storedA.contentEquals(storedB) shouldBe false
        String(storedA, Charsets.ISO_8859_1) shouldNotContain "SEGREDO"
        String(storedB, Charsets.ISO_8859_1) shouldNotContain "%PDF"
        (storedA.size > content.size) shouldBe true
        cipher.decrypt(storedA, associated(a)).contentEquals(content) shouldBe true
        cipher.decrypt(storedB, associated(b)).contentEquals(content) shouldBe true
    }

    @Test
    fun `the content of one job does not decrypt as the content of another, even after swapping the columns`() {
        val printer = newPrinter()
        val a = jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf("A")))
        val b = jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf("B")))
        val storedA = storedContent(a)
        val storedB = storedContent(b)

        jdbc
            .sql("UPDATE printing.print_job SET content = :c WHERE id = :id")
            .param("c", storedB)
            .param("id", a)
            .update()
        jdbc
            .sql("UPDATE printing.print_job SET content = :c WHERE id = :id")
            .param("c", storedA)
            .param("id", b)
            .update()

        shouldThrow<FieldDecryptionException> { cipher.decrypt(storedContent(a), associated(a)) }
        shouldThrow<FieldDecryptionException> { cipher.decrypt(storedContent(b), associated(b)) }
        cipher.decrypt(storedContent(a), associated(b)).contentEquals(pdf("B")) shouldBe true
    }

    @Test
    fun `decrypting with no associated data, with another id or with a tampered byte fails`() {
        val printer = newPrinter()
        val id = jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf()))
        val stored = storedContent(id)
        val tampered = stored.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }

        shouldThrow<FieldDecryptionException> { cipher.decrypt(stored) }
        shouldThrow<FieldDecryptionException> { cipher.decrypt(stored, associated(UUID.randomUUID())) }
        shouldThrow<FieldDecryptionException> {
            cipher.decrypt(stored, id.toString().uppercase().toByteArray())
        }
        shouldThrow<FieldDecryptionException> { cipher.decrypt(tampered, associated(id)) }
    }

    // --- tetos ---------------------------------------------------------------------------------

    @Test
    fun `the configured ceilings are the documented 5 MiB and 700 KiB`() {
        properties.maxContentBytes shouldBe 5_242_880
        properties.maxManualContentBytes shouldBe 716_800
    }

    @Test
    fun `the PrintingApi takes a PDF at exactly 5242880 bytes, round-trips it and refuses one byte more`() {
        val printer = newPrinter(PrinterKind.A4_PDF)
        val header = "%PDF-".toByteArray()
        val exact = ByteArray(5_242_880) { 7 }.also { header.copyInto(it) }

        val id = jobs.enqueue(NewPrintJob(printer, "frontdesk.big", exact))
        val error = errorOf { jobs.enqueue(NewPrintJob(printer, "frontdesk.big", exact + byteArrayOf(7))) }

        cipher.decrypt(storedContent(id), associated(id)).contentEquals(exact) shouldBe true
        error.code shouldBe PrintingErrorCodes.PRINT_CONTENT_TOO_LARGE
        jobCount(printer) shouldBe 1
    }

    @Test
    fun `the manual route takes exactly 716800 bytes and refuses 716801, while the PrintingApi takes both`() {
        val printer = newPrinter(PrinterKind.THERMAL_ESCPOS)
        val exact = ByteArray(716_800) { 3 }
        val over = ByteArray(716_801) { 3 }

        asAdmin {
            jobs.create(NewPrintJob(printer, "frontdesk.manual", exact)).status shouldBe PrintJobStatus.PENDING
            errorOf { jobs.create(NewPrintJob(printer, "frontdesk.manual", over)) }.code shouldBe
                PrintingErrorCodes.PRINT_CONTENT_TOO_LARGE
        }
        val api = jobs.enqueue(NewPrintJob(printer, "frontdesk.manual", over))

        cipher.decrypt(storedContent(api), associated(api)).size shouldBe 716_801
        jobCount(printer) shouldBe 2
    }

    @Test
    fun `an oversized or invalid request leaves no job row behind`() {
        val a4 = newPrinter(PrinterKind.A4_PDF)

        errorOf { jobs.enqueue(NewPrintJob(a4, "frontdesk.card", ByteArray(properties.maxContentBytes + 1) { 1 })) }
        errorOf { jobs.enqueue(NewPrintJob(a4, "frontdesk.card", ByteArray(0))) }
        errorOf { jobs.enqueue(NewPrintJob(a4, "frontdesk.card", "texto".toByteArray())) }
        errorOf { jobs.enqueue(NewPrintJob(a4, "Invalido", pdf())) }

        jobCount(a4) shouldBe 0
    }

    // --- validações ----------------------------------------------------------------------------

    @Test
    fun `empty content is EMPTY for both kinds, also from the manual route, never NOT_PDF`() {
        val a4 = newPrinter(PrinterKind.A4_PDF)
        val thermal = newPrinter(PrinterKind.THERMAL_ESCPOS)

        for (printer in listOf(a4, thermal)) {
            val error = errorOf { jobs.enqueue(NewPrintJob(printer, "frontdesk.card", ByteArray(0))) }
            error.code shouldBe PrintingErrorCodes.PRINT_CONTENT_EMPTY
            error.kind shouldBe ErrorKind.RULE_VIOLATION
        }
        asAdmin {
            errorOf { jobs.create(NewPrintJob(a4, "frontdesk.card", ByteArray(0))) }.code shouldBe
                PrintingErrorCodes.PRINT_CONTENT_EMPTY
        }
    }

    @Test
    fun `only a content that starts exactly with percent PDF dash passes on an A4 printer`() {
        val a4 = newPrinter(PrinterKind.A4_PDF)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val refused =
            mapOf(
                "BOM antes" to bom + "%PDF-1.7".toByteArray(),
                "espaço antes" to " %PDF-1.7".toByteArray(),
                "quebra de linha antes" to "\n%PDF-1.7".toByteArray(),
                "nulo antes" to byteArrayOf(0) + "%PDF-1.7".toByteArray(),
                "minúsculas" to "%pdf-1.7".toByteArray(),
                "sem o traço" to "%PDF1.7 conteudo".toByteArray(),
                "magic no meio" to "xx%PDF-1.7".toByteArray(),
                "só 4 bytes" to "%PDF".toByteArray(),
                "um byte" to "%".toByteArray(),
            )

        refused.forEach { (why, bytes) ->
            val error = errorOf { jobs.enqueue(NewPrintJob(a4, "frontdesk.card", bytes)) }
            withClue(why) { error.code shouldBe PrintingErrorCodes.PRINT_CONTENT_NOT_PDF }
        }
        jobs.enqueue(NewPrintJob(a4, "frontdesk.card", "%PDF-".toByteArray()))
        asAdmin { jobs.create(NewPrintJob(a4, "frontdesk.card", pdf())).printerId shouldBe a4 }
        jobCount(a4) shouldBe 2
    }

    @Test
    fun `a thermal printer takes bytes that are not a PDF and the PDF check does not apply to it`() {
        val thermal = newPrinter(PrinterKind.THERMAL_ESCPOS)

        jobs.enqueue(NewPrintJob(thermal, "frontdesk.receipt", byteArrayOf(0x1B, 0x40, 0x00)))
        jobs.enqueue(NewPrintJob(thermal, "frontdesk.receipt", "%PDF-".toByteArray()))
        asAdmin { jobs.create(NewPrintJob(thermal, "frontdesk.receipt", byteArrayOf(1))) }

        jobCount(thermal) shouldBe 3
    }

    @Test
    fun `the manual route applies the same content validations as the PrintingApi`() {
        val a4 = newPrinter(PrinterKind.A4_PDF)

        asAdmin {
            errorOf { jobs.create(NewPrintJob(a4, "frontdesk.card", "x".toByteArray())) }.code shouldBe
                PrintingErrorCodes.PRINT_CONTENT_NOT_PDF
            jobs.create(NewPrintJob(a4, "frontdesk.card", pdf())).printerId shouldBe a4
        }
    }

    @Test
    fun `the document type accepts the limits of the format and refuses everything around them`() {
        val printer = newPrinter()
        val invisible = 0x200B.toChar()
        val longest = "a." + "b".repeat(98)
        val tooLong = "a." + "b".repeat(99)

        listOf("a.b", "frontdesk.registration-card", "a1.b2", "a.b-c-d", "a.b-", "a.b.c", longest).forEach {
            withClue(it) { jobs.enqueue(NewPrintJob(printer, it, pdf())) }
        }
        longest.length shouldBe 100
        val refused =
            listOf(
                tooLong,
                "a.b$invisible",
                "${invisible}a.b",
                "a$invisible.b",
                "A.b",
                "a.B",
                "1a.b",
                "a.1b",
                "a.-b",
                "a..b",
                ".a.b",
                "a.b.",
                "a b.c",
                "a.b\n",
                "a.b ",
                " a.b",
                "a_b.c",
                "ação.b",
                "a.b;DROP",
                "",
            )
        refused.forEach { type ->
            withClue(type) {
                val error = errorOf { jobs.enqueue(NewPrintJob(printer, type, pdf())) }
                error.code shouldBe ErrorCodes.VALIDATION_FAILED
                error.kind shouldBe ErrorKind.INVALID_INPUT
            }
        }
        jobCount(printer) shouldBe 7
    }

    @Test
    fun `an unknown printer is 400 UNKNOWN_PRINTER with the printerId violation and creates nothing`() {
        val error = errorOf { jobs.enqueue(NewPrintJob(UUID(0, 0), "frontdesk.card", pdf())) }

        error.code shouldBe PrintingErrorCodes.UNKNOWN_PRINTER
        error.kind shouldBe ErrorKind.INVALID_INPUT
        error.violations.map { it.field } shouldBe listOf("printerId")
        asAdmin {
            errorOf { jobs.create(NewPrintJob(UUID.randomUUID(), "frontdesk.card", pdf())) }.code shouldBe
                PrintingErrorCodes.UNKNOWN_PRINTER
        }
    }

    @Test
    fun `an inactive printer is refused by both entry points and keeps no job`() {
        val printer = newPrinter()
        asAdmin { agents.deactivatePrinter(printer) }

        errorOf { jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf())) }.code shouldBe
            PrintingErrorCodes.PRINTER_INACTIVE
        asAdmin {
            errorOf { jobs.create(NewPrintJob(printer, "frontdesk.card", pdf())) }.code shouldBe
                PrintingErrorCodes.PRINTER_INACTIVE
        }
        jobCount(printer) shouldBe 0
    }

    @Test
    fun `a printer of a deactivated agent never has its job handed to that agent`() {
        val agent = newAgent()
        val printer = newPrinter(agentId = agent)
        asAdmin { agents.deactivateAgent(agent) }

        val outcome = runCatching { jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf())) }

        outcome.exceptionOrNull()?.let { (it is DomainException) shouldBe true }
        val claimed =
            org.springframework.transaction.support
                .TransactionTemplate(txManager)
                .execute { _ -> store.claimPending(agent, 10).map { job -> job.id } }
        claimed shouldHaveSize 0
    }

    @Test
    fun `an error never echoes the content or the secret in its message or violations`() {
        val a4 = newPrinter()
        val secret = "SEGREDO-${suffix()}"

        val notPdf = errorOf { jobs.enqueue(NewPrintJob(a4, "frontdesk.card", secret.toByteArray())) }
        val badType = errorOf { jobs.enqueue(NewPrintJob(a4, "Tipo-$secret", pdf())) }

        listOf(notPdf, badType).forEach { error ->
            error.message shouldNotContain "SEGREDO"
            error.violations.joinToString { "${it.field}${it.code}${it.message}" } shouldNotContain "SEGREDO"
        }
    }

    // --- permissões ----------------------------------------------------------------------------

    @Test
    fun `the Receptionist reads the jobs but cannot create, the Manager does both and the admin does both`() {
        val printer = newPrinter()
        val receptionist = userWithTemplate(RoleTemplate.RECEPTIONIST)
        val manager = userWithTemplate(RoleTemplate.MANAGER)

        actingAs(receptionist) {
            jobs.jobs(null, printer, PageParams()).items shouldHaveSize 0
            errorOf { jobs.create(NewPrintJob(printer, "frontdesk.card", pdf())) }.kind shouldBe
                ErrorKind.PERMISSION_DENIED
        }
        jobCount(printer) shouldBe 0
        actingAs(manager) {
            jobs.create(NewPrintJob(printer, "frontdesk.card", pdf())).printerId shouldBe printer
            jobs.jobs(null, printer, PageParams()).items shouldHaveSize 1
        }
        asAdmin {
            jobs.create(NewPrintJob(printer, "frontdesk.card", pdf()))
            jobs.jobs(null, printer, PageParams()).items shouldHaveSize 2
        }
    }

    @Test
    fun `jobs manage alone creates but cannot list, jobs read alone lists but cannot create`() {
        val printer = newPrinter()
        val onlyManage = userWith(PrintingPermissions.JOBS_MANAGE)
        val onlyRead = userWith(PrintingPermissions.JOBS_READ)
        val onlyAgents = userWith(PrintingPermissions.AGENTS_MANAGE)

        actingAs(onlyManage) {
            jobs.create(NewPrintJob(printer, "frontdesk.card", pdf())).status shouldBe PrintJobStatus.PENDING
            errorOf { jobs.jobs(null, null, PageParams()) }.kind shouldBe ErrorKind.PERMISSION_DENIED
        }
        actingAs(onlyRead) {
            errorOf { jobs.create(NewPrintJob(printer, "frontdesk.card", pdf())) }.kind shouldBe
                ErrorKind.PERMISSION_DENIED
            jobs.jobs(null, printer, PageParams()).items shouldHaveSize 1
        }
        actingAs(onlyAgents) {
            errorOf { jobs.create(NewPrintJob(printer, "frontdesk.card", pdf())) }.kind shouldBe
                ErrorKind.PERMISSION_DENIED
            errorOf { jobs.jobs(null, null, PageParams()) }.kind shouldBe ErrorKind.PERMISSION_DENIED
        }
        jobCount(printer) shouldBe 1
    }

    @Test
    fun `a user with no role is denied create and jobs and enqueue still needs nothing`() {
        val printer = newPrinter()
        val nobody = iam.createUser().id

        actingAs(nobody) {
            errorOf { jobs.create(NewPrintJob(printer, "frontdesk.card", pdf())) }.kind shouldBe
                ErrorKind.PERMISSION_DENIED
            errorOf { jobs.jobs(null, null, PageParams()) }.kind shouldBe ErrorKind.PERMISSION_DENIED
        }
        jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf())) shouldNotBe null
        actingAs(nobody) { jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf())) shouldNotBe null }
        jobCount(printer) shouldBe 2
    }

    @Test
    fun `an anonymous caller is 401 on create and jobs, and a denied create writes no row and no audit entry`() {
        val printer = newPrinter()
        val before = auditCountOfCreates()
        SecurityContextHolder.clearContext()

        errorOf { jobs.create(NewPrintJob(printer, "frontdesk.card", pdf())) }.kind shouldBe ErrorKind.UNAUTHENTICATED
        errorOf { jobs.jobs(null, null, PageParams()) }.kind shouldBe ErrorKind.UNAUTHENTICATED
        actingAs(userWith(PrintingPermissions.JOBS_READ)) {
            errorOf { jobs.create(NewPrintJob(printer, "frontdesk.card", pdf())) }
        }

        jobCount(printer) shouldBe 0
        auditCountOfCreates() shouldBe before
    }

    private fun auditCountOfCreates(): Int =
        jdbc
            .sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'printing.job.create'")
            .query(Int::class.java)
            .single()

    @Test
    fun `permission is checked before validation, so a denied caller learns nothing about the printer`() {
        val reader = userWith(PrintingPermissions.JOBS_READ)

        actingAs(reader) {
            errorOf { jobs.create(NewPrintJob(UUID.randomUUID(), "Invalido", ByteArray(0))) }.kind shouldBe
                ErrorKind.PERMISSION_DENIED
        }
    }

    // --- posições ------------------------------------------------------------------------------

    @Test
    fun `positions are strictly increasing and unique under concurrent creation`() {
        val printer = newPrinter(PrinterKind.THERMAL_ESCPOS)
        val threads = 8
        val perThread = 5
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val go = CountDownLatch(1)
            val futures =
                (1..threads).map {
                    pool.submit<List<UUID>> {
                        go.await()
                        (1..perThread).map { jobs.enqueue(NewPrintJob(printer, "frontdesk.card", byteArrayOf(1))) }
                    }
                }
            go.countDown()
            val perThreadIds = futures.map { it.get(60, TimeUnit.SECONDS) }

            val all = perThreadIds.flatten()
            all.toSet() shouldHaveSize threads * perThread
            all.map { positionOf(it) }.toSet() shouldHaveSize threads * perThread
            perThreadIds.forEach { ids ->
                val positions = ids.map { positionOf(it) }
                positions shouldBe positions.sorted()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a rejected request does not leave a hole that breaks the order of the next positions`() {
        val printer = newPrinter()
        val first = jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf()))
        errorOf { jobs.enqueue(NewPrintJob(printer, "frontdesk.card", "x".toByteArray())) }
        val second = jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf()))

        (positionOf(second) > positionOf(first)) shouldBe true
    }

    // --- página --------------------------------------------------------------------------------

    @Test
    fun `the page walks newest first with limit 1 without repeating or skipping and ends with a null cursor`() {
        val printer = newPrinter()
        val ids = (1..4).map { jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf())) }

        val seen = mutableListOf<UUID>()
        asAdmin {
            var cursor: String? = null
            do {
                val page = jobs.jobs(null, printer, PageParams(cursor = cursor, limit = 1))
                page.items shouldHaveSize 1
                seen += page.items.map { it.id }
                cursor = page.nextCursor
            } while (cursor != null)
        }

        seen shouldContainExactly ids.reversed()
    }

    @Test
    fun `a page that exactly fits the items has no next cursor and a page above the limit has one`() {
        val printer = newPrinter()
        (1..3).forEach { jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf())) }

        asAdmin {
            val exact = jobs.jobs(null, printer, PageParams(limit = 3))
            exact.items shouldHaveSize 3
            exact.nextCursor shouldBe null
            jobs.jobs(null, printer, PageParams(limit = 2)).nextCursor?.isNotEmpty() shouldBe true
        }
    }

    @Test
    fun `the page filters by status and printer, and an unknown printer filter is just empty`() {
        val agent = newAgent()
        val printer = newPrinter(agentId = agent)
        val other = newPrinter()
        val pending = jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf()))
        val sent = jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf()))
        jobs.enqueue(NewPrintJob(other, "frontdesk.card", pdf()))
        org.springframework.transaction.support
            .TransactionTemplate(txManager)
            .executeWithoutResult { store.lockById(sent)!!.claim(agent, java.time.Instant.now()) }

        asAdmin {
            jobs.jobs(PrintJobStatus.PENDING, printer, PageParams()).items.map { it.id } shouldBe listOf(pending)
            jobs.jobs(PrintJobStatus.SENT, printer, PageParams()).items.map { it.id } shouldBe listOf(sent)
            jobs.jobs(null, printer, PageParams()).items.map { it.id } shouldBe listOf(sent, pending)
            jobs.jobs(PrintJobStatus.FAILED, printer, PageParams()).items shouldHaveSize 0
            jobs.jobs(null, UUID.randomUUID(), PageParams()).items shouldHaveSize 0
            jobs.jobs(PrintJobStatus.PENDING, other, PageParams()).items shouldHaveSize 1
        }
    }

    @Test
    fun `invalid cursors are 400 INVALID_CURSOR and never a server error`() {
        asAdmin {
            val forged = { raw: String -> Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray()) }
            listOf(
                "!!!",
                "abc",
                "a".repeat(2000),
                forged("c1:abc"),
                forged("c1:"),
                forged("c1:99999999999999999999"),
                forged("c1:1.5"),
                forged("c2:5"),
            ).forEach { cursor ->
                val error = errorOf { jobs.jobs(null, null, PageParams(cursor = cursor)) }
                withClue(cursor.take(20)) {
                    error.code shouldBe ErrorCodes.INVALID_CURSOR
                    error.kind shouldBe ErrorKind.INVALID_INPUT
                }
            }
        }
    }

    @Test
    fun `a cursor from the far past or negative just returns an empty page`() {
        val printer = newPrinter()
        jobs.enqueue(NewPrintJob(printer, "frontdesk.card", pdf()))
        val forged = { raw: String -> Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray()) }

        asAdmin {
            jobs.jobs(null, printer, PageParams(cursor = forged("c1:-1"))).items shouldHaveSize 0
            jobs.jobs(null, printer, PageParams(cursor = forged("c1:0"))).items shouldHaveSize 0
            jobs.jobs(null, printer, PageParams(cursor = forged("c1:${Long.MAX_VALUE}"))).items shouldHaveSize 1
        }
    }

    @Test
    fun `the view has no content property and its text never carries the secret`() {
        val printer = newPrinter()
        val view: PrintJobView = asAdmin { jobs.create(NewPrintJob(printer, "frontdesk.card", pdf("VIEW"))) }
        val listed = asAdmin { jobs.jobs(null, printer, PageParams()).items.single() }

        PrintJobView::class.java.declaredFields
            .map { it.name.lowercase() }
            .none { "content" in it } shouldBe true
        view.toString() shouldNotContain "VIEW"
        listed.toString() shouldNotContain "SEGREDO"
        listed.status shouldBe PrintJobStatus.PENDING
        listed.attempts shouldBe 0
        listed.sentAt shouldBe null
        listed.finishedAt shouldBe null
        listed.failureCode shouldBe null
    }

    // --- trilha e log --------------------------------------------------------------------------

    @Test
    fun `the audit entry of enqueue and of create have the job snapshot and no content`() {
        val printer = newPrinter()
        val marker = suffix()
        val content = pdf(marker)
        val viaApi = jobs.enqueue(NewPrintJob(printer, "frontdesk.card", content))
        val viaRoute = asAdmin { jobs.create(NewPrintJob(printer, "frontdesk.card", content)).id }

        for (id in listOf(viaApi, viaRoute)) {
            val state = auditStates(id).single()
            state shouldNotContain marker
            state shouldNotContain "SEGREDO"
            state shouldNotContain Base64.getEncoder().encodeToString(content)
            state shouldNotContain "%PDF"
            state shouldNotContain "\"content\""
            state.contains(printer.toString()) shouldBe true
            state.contains("PENDING") shouldBe true
        }
    }

    @Test
    fun `no audit row of any action mentions the secret content of a created job`() {
        val printer = newPrinter()
        val marker = "MARCADOR-${suffix()}"
        jobs.enqueue(NewPrintJob(printer, "frontdesk.card", "%PDF-$marker".toByteArray()))
        asAdmin { jobs.create(NewPrintJob(printer, "frontdesk.card", "%PDF-$marker".toByteArray())) }
        errorOf { jobs.enqueue(NewPrintJob(printer, "frontdesk.card", marker.toByteArray())) }

        jdbc
            .sql(
                "SELECT count(*) FROM audit.audit_entry WHERE CAST(after_state AS text) LIKE :m OR " +
                    "CAST(before_state AS text) LIKE :m",
            ).param("m", "%$marker%")
            .query(Int::class.java)
            .single() shouldBe 0
    }

    @Test
    fun `nothing the printing code logs while queueing, refusing and listing carries the content`() {
        val logger = LoggerFactory.getLogger("com.zeny.hotel") as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val previous = logger.level
        logger.level = Level.TRACE
        logger.addAppender(appender)
        val marker = "LOGSEGREDO${suffix()}"
        try {
            val a4 = newPrinter()
            val thermal = newPrinter(PrinterKind.THERMAL_ESCPOS)
            jobs.enqueue(NewPrintJob(a4, "frontdesk.card", "%PDF-$marker".toByteArray()))
            errorOf { jobs.enqueue(NewPrintJob(a4, "frontdesk.card", marker.toByteArray())) }
            errorOf { jobs.enqueue(NewPrintJob(thermal, "Invalido-$marker", marker.toByteArray())) }
            errorOf { jobs.enqueue(NewPrintJob(thermal, "frontdesk.card", ByteArray(properties.maxContentBytes + 1))) }
            actingAs(userWith(PrintingPermissions.JOBS_READ)) {
                errorOf { jobs.create(NewPrintJob(thermal, "frontdesk.card", marker.toByteArray())) }
                jobs.jobs(null, a4, PageParams())
            }
            asAdmin { jobs.create(NewPrintJob(thermal, "frontdesk.card", marker.toByteArray())) }
        } finally {
            logger.detachAppender(appender)
            logger.level = previous
        }

        val rendered = appender.list.map { it.formattedMessage + it.throwableProxy?.message.orEmpty() }
        rendered.none { "LOGSEGREDO" in it } shouldBe true
        rendered.none { Base64.getEncoder().encodeToString(marker.toByteArray()).take(12) in it } shouldBe true
    }
}
