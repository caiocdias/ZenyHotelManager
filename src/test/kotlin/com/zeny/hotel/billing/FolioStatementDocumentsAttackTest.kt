package com.zeny.hotel.billing

import com.zeny.hotel.billing.BillingTestSupport.Companion.definition
import com.zeny.hotel.billing.application.FolioStatementDocuments
import com.zeny.hotel.billing.application.FolioStatementService
import com.zeny.hotel.billing.application.StatementAudience
import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.guests.GuestsTestSupport
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.printing.PrinterKind
import com.zeny.hotel.printing.PrintingApi
import com.zeny.hotel.printing.application.NewPrinter
import com.zeny.hotel.printing.application.PrintAgentsService
import com.zeny.hotel.printing.application.PrintJobsService
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.ContentCipher
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PermissionDeniedException
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.pdfPageCount
import com.zeny.hotel.testsupport.pdfText
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * O ataque ao extrato da conta como documento (M5 E11, Task 9; D-E11-9, D-E11-12 e D-E11-14): o que a prévia do
 * hóspede jamais leva, o texto hostil nos lançamentos, no motivo, na empresa pagadora e no descritor do dono, as
 * bordas da conta (vazia, encerrada, credora, dinheiro enorme, muitos dias), as portas do dono (`null`, exceção,
 * tipos distintos), a permissão, a conta e a impressora que não existem e a trilha do `print`.
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    MutableClockConfiguration::class,
    FolioStatementDocumentsAttackTest.DescribersConfiguration::class,
)
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
class FolioStatementDocumentsAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired private val documents: FolioStatementDocuments,
    @Autowired private val statements: FolioStatementService,
    @Autowired private val printing: PrintingApi,
    @Autowired private val guestsApi: GuestsApi,
    @Autowired private val authorization: Authorization,
    @Autowired private val audit: AuditContext,
    @Autowired private val agents: PrintAgentsService,
    @Autowired private val cipher: ContentCipher,
    @Autowired private val clock: Clock,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hostile: HostileOwner,
    @Autowired flyway: Flyway,
) {
    private val billing = BillingTestSupport(iam, mockMvc, jsonMapper)
    private val guests = GuestsTestSupport(iam)
    private val owner = JdbcClient.create(flyway.configuration.dataSource)
    private val ibm850 = charset("IBM850")

    /** O dono descrito pelo descritor de teste do tipo `EVENT`, trocável por teste. */
    class HostileOwner {
        @Volatile
        var description: FolioOwnerDescription? = null
    }

    /** Descritores de teste sem descritor de produção: `EVENT` (hostil), `COMPANY` (`null`), `WALK_IN` (lança). */
    @TestConfiguration(proxyBeanMethods = false)
    class DescribersConfiguration {
        @Bean
        fun hostileOwner() = HostileOwner()

        @Bean
        fun eventDescriber(holder: HostileOwner): FolioOwnerDescriber =
            object : FolioOwnerDescriber {
                override val ownerType = FolioOwnerType.EVENT

                override fun describe(ownerId: UUID) = holder.description
            }

        @Bean
        fun companyDescriber(): FolioOwnerDescriber =
            object : FolioOwnerDescriber {
                override val ownerType = FolioOwnerType.COMPANY

                override fun describe(ownerId: UUID): FolioOwnerDescription? = null
            }

        @Bean
        fun walkInDescriber(): FolioOwnerDescriber =
            object : FolioOwnerDescriber {
                override val ownerType = FolioOwnerType.WALK_IN

                override fun describe(ownerId: UUID): FolioOwnerDescription = error("descritor quebrado")
            }
    }

    @BeforeEach
    fun synchronizeThisContextCatalog() {
        synchronizer.synchronize()
        hostile.description = null
    }

    // --- a prévia do hóspede nunca leva o que é interno ---

    @Test
    fun `the guest statement never carries anything internal, in the PDF nor in ESC POS, and the internal one does`() {
        val s = richScenario()
        val internalWords = listOf(s.cashier.toString(), s.shift.toString(), *s.secrets.toTypedArray())

        val guestPdf = normalized(actingAsAdmin { pdfText(documents.pdf(s.folio, StatementAudience.GUEST).content) })
        val guestEscPos = escPosText(actingAsAdmin { documents.print(s.folio, StatementAudience.GUEST, thermal()) })
        val statement = actingAsAdmin { statements.statement(s.folio, StatementAudience.GUEST) }
        internalWords.forEach {
            guestPdf shouldNotContain it
            guestEscPos shouldNotContain it
        }
        listOf("Usuário", "Motivo", "Reaberturas").forEach { guestPdf shouldNotContain it }
        listOf("Usuario", "Motivo", "Reaberta").forEach { guestEscPos shouldNotContain it }

        // todas as linhas (estorno, ajuste, transferência, taxa de serviço, pagamentos) estão na prévia
        val lines = statement.days.flatMap { it.lines }
        lines.size shouldBeGreaterThan 8
        lines.forEach { guestPdf shouldContain normalized(it.description) }

        val internalPdf =
            normalized(actingAsAdmin { pdfText(documents.pdf(s.folio, StatementAudience.INTERNAL).content) })
        listOf(s.cashier.toString(), REVERSAL_REASON, ADJUSTMENT_REASON, TRANSFER_REASON, REFUND_REASON).forEach {
            internalPdf shouldContain it
        }
        val internalEscPos =
            escPosText(actingAsAdmin { documents.print(s.folio, StatementAudience.INTERNAL, thermal()) })
        listOf(s.cashier.toString(), REVERSAL_REASON).forEach { internalEscPos shouldContain it }
    }

    @Test
    fun `the reopening reason shows only in the internal statement`() {
        val folio = billing.openFolio()
        billing.call(POST, "/api/v1/folios/$folio/close", billing.admin).expect(200)
        billing.call(POST, "/api/v1/folios/$folio/reopen", billing.admin, mapOf("reason" to REOPEN_REASON)).expect(200)

        val guest =
            normalized(actingAsAdmin { pdfText(documents.pdf(FolioId(folio), StatementAudience.GUEST).content) })
        val internal =
            normalized(actingAsAdmin { pdfText(documents.pdf(FolioId(folio), StatementAudience.INTERNAL).content) })

        guest shouldNotContain REOPEN_REASON
        internal shouldContain "Reaberturas"
        internal shouldContain REOPEN_REASON
        val escGuest = escPosText(actingAsAdmin { documents.print(FolioId(folio), StatementAudience.GUEST, thermal()) })
        escGuest shouldNotContain REOPEN_REASON
    }

    // --- texto hostil ---

    @Test
    fun `hostile text in lines, reason, payer and owner descriptor is literal in PDF and ESC POS`() {
        val company = guests.createCompany(guests.companyBody() + ("tradeName" to PAYER))
        val folio = billing.openFolio()
        owner
            .sql("UPDATE billing.folio SET payer_company_id = :company WHERE id = :id")
            .param("company", company)
            .param("id", folio)
            .update()
        insertPosting(folio, HOSTILE_DESCRIPTION, "10.00", daysAgo = 0)
        val posting = billing.post(folio, billing.codeId("LAUNDRY"), "5.00")
        billing
            .call(
                POST,
                "/api/v1/folios/$folio/postings/${posting["id"].asString()}/reverse",
                billing.admin,
                mapOf("reason" to HOSTILE_REASON),
            ).expect(201)
        val eventFolio = billing.openFolio("EVENT")
        hostile.description =
            FolioOwnerDescription(
                label = HOSTILE_LABEL,
                guestName = HOSTILE_GUEST,
                roomNumber = "<i>12</i>",
                arrival = null,
                departure = null,
            )

        val pdf =
            normalized(actingAsAdmin { pdfText(documents.pdf(FolioId(folio), StatementAudience.INTERNAL).content) })
        val eventPdf =
            normalized(actingAsAdmin { pdfText(documents.pdf(FolioId(eventFolio), StatementAudience.GUEST).content) })
        val esc = escPosText(actingAsAdmin { documents.print(FolioId(folio), StatementAudience.INTERNAL, thermal()) })
        val eventEsc =
            escPosText(actingAsAdmin { documents.print(FolioId(eventFolio), StatementAudience.GUEST, thermal()) })

        listOf(HOSTILE_DESCRIPTION, HOSTILE_REASON, PAYER).forEach { pdf shouldContain it }
        listOf(HOSTILE_LABEL, HOSTILE_GUEST, "<i>12</i>").forEach {
            eventPdf shouldContain it
            eventEsc shouldContain it
        }
        listOf(HOSTILE_DESCRIPTION, HOSTILE_REASON, PAYER).forEach { esc shouldContain it.take(ESC_FRAGMENT) }
    }

    @Test
    fun `the PDF shows no stylesheet text in the page`() {
        val folio = FolioId(billing.openFolio())

        val text = actingAsAdmin { pdfText(documents.pdf(folio, StatementAudience.GUEST).content) }

        listOf("border-collapse", "td.amount", "font-weight", "vertical-align").forEach { text shouldNotContain it }
    }

    @Test
    fun `control characters in a posting description add no ESC POS command and no line over 48 columns`() {
        val plain = billing.openFolio()
        insertPosting(plain, "ABCDEFGHIJ", "10.00", daysAgo = 0)
        val evil = billing.openFolio()
        insertPosting(evil, "AB\u001B@C\u001DV\u0001DEF\u0007\u001B!\u0010", "10.00", daysAgo = 0)
        val printer = thermal()

        val plainBytes =
            escPosBytes(actingAsAdmin { documents.print(FolioId(plain), StatementAudience.GUEST, printer) })
        val evilBytes = escPosBytes(actingAsAdmin { documents.print(FolioId(evil), StatementAudience.GUEST, printer) })

        evilBytes.count { it == ESC } shouldBe plainBytes.count { it == ESC }
        evilBytes.count { it == GS } shouldBe plainBytes.count { it == GS }
        escPosText(evilBytes).lines().forEach { it.length shouldBeLessThanOrEqual COLUMNS }
        pdfText(actingAsAdmin { documents.pdf(FolioId(evil), StatementAudience.GUEST).content }) shouldContain "AB"
    }

    // --- bordas da conta ---

    @Test
    fun `an empty folio renders with zero totals in PDF and ESC POS`() {
        val folio = FolioId(billing.openFolio())

        val pdf = actingAsAdmin { pdfText(documents.pdf(folio, StatementAudience.GUEST).content) }
        val esc = escPosText(actingAsAdmin { documents.print(folio, StatementAudience.GUEST, thermal()) })

        pdf shouldContain "R$ 0,00"
        esc shouldContain "SALDO: R$ 0,00"
    }

    @Test
    fun `a closed folio says closed and a creditor balance is negative`() {
        val folio = billing.openFolio()
        val cashier = cashier()
        billing.openShift(cashier)
        billing.pay(folio, cashier, mapOf("method" to "CASH", "amount" to "50.00", "deposit" to true)).expect(201)

        val credit = actingAsAdmin { pdfText(documents.pdf(FolioId(folio), StatementAudience.GUEST).content) }
        val creditEsc =
            escPosText(actingAsAdmin { documents.print(FolioId(folio), StatementAudience.GUEST, thermal()) })

        credit shouldContain "-R$ 50,00"
        creditEsc shouldContain "SALDO: -R$ 50,00"

        val closedFolio = billing.openFolio()
        billing.call(POST, "/api/v1/folios/$closedFolio/close", billing.admin).expect(200)
        val closed = actingAsAdmin { pdfText(documents.pdf(FolioId(closedFolio), StatementAudience.GUEST).content) }
        closed shouldContain "Encerrada"
        closed shouldNotContain "Aberta\n"
    }

    @Test
    fun `a huge amount keeps every digit with the thousands separators`() {
        val folio = billing.openFolio()
        insertPosting(folio, "Pacote anual", "999999999999.99", daysAgo = 0)

        val pdf = actingAsAdmin { pdfText(documents.pdf(FolioId(folio), StatementAudience.GUEST).content) }
        val esc = escPosText(actingAsAdmin { documents.print(FolioId(folio), StatementAudience.GUEST, thermal()) })

        pdf shouldContain "R$ 999.999.999.999,99"
        esc.lines().forEach { it.length shouldBeLessThanOrEqual COLUMNS }
        esc shouldContain "999.999.999.999,99"
    }

    @Test
    fun `forty business days paginate and keep the first and the last date`() {
        val folio = billing.openFolio()
        repeat(DAYS) { insertPosting(folio, "Diária $it", "10.00", daysAgo = it) }

        val rendered = actingAsAdmin { documents.pdf(FolioId(folio), StatementAudience.GUEST).content }
        val text = normalized(pdfText(rendered))

        pdfPageCount(rendered) shouldBeGreaterThan 1
        text shouldContain "Página 2 de"
        text shouldContain "Diária 0"
        text shouldContain "Diária ${DAYS - 1}"
        text shouldContain "R$ 400,00"
    }

    // --- as portas do dono ---

    @Test
    fun `an owner type whose describer returns null shows only the type`() {
        val folio = FolioId(billing.openFolio("COMPANY", guests.createCompany()))

        val text = actingAsAdmin { pdfText(documents.pdf(folio, StatementAudience.GUEST).content) }

        text shouldContain "Empresa"
    }

    @Test
    fun `a describer that throws fails the print with no job queued and the PDF with the same exception`() {
        val folio = FolioId(billing.openFolio("WALK_IN"))
        val printer = thermal()
        val before = jobCount()

        val onPrint =
            shouldThrow<IllegalStateException> {
                actingAsAdmin { documents.print(folio, StatementAudience.GUEST, printer) }
            }
        val onPdf =
            shouldThrow<IllegalStateException> { actingAsAdmin { documents.pdf(folio, StatementAudience.GUEST) } }

        onPrint.message shouldBe "descritor quebrado"
        onPdf.message shouldBe "descritor quebrado"
        jobCount() shouldBe before
    }

    @Test
    fun `describers of different types coexist and none at all is accepted`() {
        fun describer(type: FolioOwnerType) =
            object : FolioOwnerDescriber {
                override val ownerType = type

                override fun describe(ownerId: UUID): FolioOwnerDescription? = null
            }

        FolioStatementDocuments(
            statements,
            printing,
            guestsApi,
            listOf(describer(FolioOwnerType.GROUP), describer(FolioOwnerType.EVENT)),
            authorization,
            audit,
        )
        FolioStatementDocuments(statements, printing, guestsApi, emptyList(), authorization, audit)
        shouldThrow<IllegalArgumentException> {
            FolioStatementDocuments(
                statements,
                printing,
                guestsApi,
                listOf(
                    describer(FolioOwnerType.GROUP),
                    describer(FolioOwnerType.EVENT),
                    describer(FolioOwnerType.GROUP),
                ),
                authorization,
                audit,
            )
        }
    }

    // --- permissão, conta e impressora ---

    @Test
    fun `an unknown folio is not found for who may read and denied for who may not, without revealing existence`() {
        val unknown = FolioId(UuidV7.generate())
        val stranger = iam.createUserWith(TestGrant(definition(BillingPermissions.CODES_READ), ALL))
        val printer = thermal()

        shouldThrow<NotFoundException> { actingAsAdmin { documents.pdf(unknown, StatementAudience.GUEST) } }
        shouldThrow<NotFoundException> { actingAsAdmin { documents.print(unknown, StatementAudience.GUEST, printer) } }
        shouldThrow<PermissionDeniedException> {
            acting(
                stranger.id.value,
            ) { documents.pdf(unknown, StatementAudience.GUEST) }
        }
        shouldThrow<PermissionDeniedException> {
            acting(stranger.id.value) { documents.print(unknown, StatementAudience.GUEST, printer) }
        }
    }

    @Test
    fun `a denied print queues no job`() {
        val folio = FolioId(billing.openFolio())
        val stranger = iam.createUserWith(TestGrant(definition(BillingPermissions.CODES_READ), ALL))
        val printer = thermal()
        val before = jobCount()

        shouldThrow<PermissionDeniedException> {
            acting(stranger.id.value) { documents.print(folio, StatementAudience.INTERNAL, printer) }
        }

        jobCount() shouldBe before
    }

    @Test
    fun `printing on a missing or deactivated printer queues no job`() {
        val folio = FolioId(billing.openFolio())
        val deactivated = thermal()
        actingAsAdmin { agents.deactivatePrinter(deactivated) }
        val before = jobCount()

        shouldThrow<com.zeny.hotel.shared.InvalidInputException> {
            actingAsAdmin { documents.print(folio, StatementAudience.GUEST, UuidV7.generate()) }
        }
        shouldThrow<com.zeny.hotel.shared.BusinessRuleException> {
            actingAsAdmin { documents.print(folio, StatementAudience.GUEST, deactivated) }
        }

        jobCount() shouldBe before
    }

    @Test
    fun `the print queues the job under the template code and the trail holds only ids`() {
        val s = richScenario()
        val printer = thermal()
        val job = actingAsAdmin { documents.print(s.folio, StatementAudience.INTERNAL, printer) }

        jdbc
            .sql("SELECT document_type FROM printing.print_job WHERE id = :id")
            .param("id", job)
            .query(String::class.java)
            .single() shouldBe "billing.statement"
        val states =
            jdbc
                .sql(
                    "SELECT CAST(after_state AS text) || ' ' || coalesce(CAST(before_state AS text), '') " +
                        "FROM audit.audit_entry WHERE action = 'billing.statement.print' " +
                        "AND CAST(after_state AS text) LIKE :like",
                ).param("like", "%$job%")
                .query(String::class.java)
                .list()

        states.size shouldBe 1
        val state = states.single()
        listOf(
            REVERSAL_REASON,
            ADJUSTMENT_REASON,
            TRANSFER_REASON,
            REFUND_REASON,
            "R$",
            "Lavanderia",
            s.cashier.toString(),
        ).plus(s.secrets)
            .forEach { state shouldNotContain it }
    }

    // --- apoio ---

    private class Rich(
        val folio: FolioId,
        val cashier: UUID,
        val shift: UUID,
        val secrets: List<String>,
    )

    /** A conta do `FolioStatementApiTest`: ajuste, estorno, transferência, taxa, caução, depósito e reembolso. */
    @Suppress("LongMethod")
    private fun richScenario(): Rich =
        with(billing) {
            val folio = openFolio()
            val other = openFolio()
            insertPosting(folio, "Diária de ontem", "100.00", daysAgo = 1)
            val room = post(folio, codeId("ROOM_CHARGE"), "300.00")
            val item = post(folio, createCode(serviceCharge = true, rate = "10"), "50.00")
            val laundry = post(folio, codeId("LAUNDRY"), "40.00")
            call(
                POST,
                "/api/v1/folios/$folio/postings/${laundry["id"].asString()}/reverse",
                admin,
                mapOf("reason" to REVERSAL_REASON),
            ).expect(201)
            call(
                POST,
                "/api/v1/folios/$folio/postings/${room["id"].asString()}/adjust",
                admin,
                mapOf("amount" to "20.00", "reason" to ADJUSTMENT_REASON),
            ).expect(201)
            val misc = post(folio, codeId("MISCELLANEOUS"), "30.00")
            transfer(folio, other, listOf(misc["id"].asString()), reason = TRANSFER_REASON).expect(201)
            check(item["id"].asString().isNotEmpty())
            val user =
                iam.createUserWith(
                    TestGrant(definition(BillingPermissions.PAYMENTS_RECEIVE), ALL),
                    TestGrant(definition(BillingPermissions.FOLIOS_READ), ALL),
                    TestGrant(definition(BillingPermissions.CASHIER_OPERATE), ALL),
                    TestGrant(
                        definition(BillingPermissions.PAYMENTS_REFUND),
                        ALL,
                        mapOf(BillingPermissions.MAX_AMOUNT to LimitValue.Unlimited),
                    ),
                )
            val auth = iam.asTestUser(user)
            val shift = openShift(auth)
            val pre =
                call(
                    POST,
                    "/api/v1/folios/$folio/card-pre-authorizations",
                    auth,
                    mapOf(
                        "amount" to "500.00",
                        "expiresAt" to
                            clock
                                .instant()
                                .plus(5, ChronoUnit.DAYS)
                                .truncatedTo(ChronoUnit.SECONDS)
                                .toString(),
                        "nsu" to PRE_NSU,
                        "authorizationCode" to PRE_CODE,
                        "cardBrand" to "VISA",
                        "cardLast4" to "4242",
                    ),
                ).expect(201)["id"].asString()
            call(
                POST,
                "/api/v1/folios/$folio/card-pre-authorizations/$pre/capture",
                auth,
                mapOf("amount" to "200.00", "nsu" to CAPTURE_NSU),
            ).expect(201)
            call(POST, "/api/v1/folios/$folio/card-pre-authorizations/$pre/release", auth).expect(200)
            val cash = pay(folio, auth, mapOf("method" to "CASH", "amount" to "100.00", "tendered" to "150.00"))
            pay(folio, auth, mapOf("method" to "PIX", "amount" to "20.00", "deposit" to true, "nsu" to DEPOSIT_NSU))
                .expect(201)
            call(
                POST,
                "/api/v1/folios/$folio/payments/${cash.expect(201)["id"].asString()}/refund",
                auth,
                mapOf("amount" to "10.00", "reason" to REFUND_REASON),
            ).expect(201)
            Rich(FolioId(folio), user.id.value, shift, listOf(PRE_NSU, PRE_CODE, CAPTURE_NSU, DEPOSIT_NSU))
        }

    private fun cashier() =
        iam.asTestUser(
            iam.createUserWith(
                TestGrant(definition(BillingPermissions.PAYMENTS_RECEIVE), ALL),
                TestGrant(definition(BillingPermissions.CASHIER_OPERATE), ALL),
            ),
        )

    /** Um lançamento direto no banco (o dono do schema), com a descrição que a API não aceitaria ou com data antiga. */
    private fun insertPosting(
        folio: UUID,
        description: String,
        amount: String,
        daysAgo: Int,
    ) {
        owner
            .sql(
                """
                INSERT INTO billing.posting (id, folio_id, transaction_code_id, posting_type, quantity, unit_price,
                    amount, description, source, business_date, posted_at)
                SELECT :id, :folio, c.id, 'REGULAR', 1, CAST(:amount AS numeric), CAST(:amount AS numeric), :description,
                    'END_OF_DAY', CURRENT_DATE - CAST(:days AS int), now() - make_interval(days => CAST(:days AS int))
                FROM billing.transaction_code c WHERE c.system_key = 'ROOM_CHARGE'
                """.trimIndent(),
            ).param("id", UuidV7.generate())
            .param("folio", folio)
            .param("amount", amount)
            .param("description", description)
            .param("days", daysAgo)
            .update()
    }

    private fun MockHttpServletResponse.expect(status: Int) = with(billing) { this@expect.expect(status) }

    private fun jobCount(): Long = jdbc.sql("SELECT count(*) FROM printing.print_job").query(Long::class.java).single()

    private fun <T> acting(
        userId: UUID,
        block: () -> T,
    ): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(userId.toString())
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

    private fun <T> actingAsAdmin(block: () -> T): T = acting(iam.adminId(), block)

    private fun thermal(): UUID =
        actingAsAdmin {
            val suffix = UuidV7.generate().toString().takeLast(SUFFIX)
            val agent = agents.createAgent("Agente $suffix").view.id
            agents.createPrinter(NewPrinter("Imp $suffix", PrinterKind.THERMAL_ESCPOS, "addr-$suffix", agent)).id
        }

    private fun escPosBytes(jobId: UUID): ByteArray {
        val stored =
            jdbc
                .sql("SELECT content FROM printing.print_job WHERE id = :id")
                .param("id", jobId)
                .query { rs, _ -> rs.getBytes(1) }
                .single()
        return cipher.decrypt(stored, PrintJobsService.associatedData(jobId))
    }

    private fun escPosText(bytes: ByteArray): String =
        String(bytes, ibm850).replace(Regex("\u001B@|\u001B[ta]|\u001DVB"), "").filter { it == '\n' || it >= ' ' }

    private fun escPosText(jobId: UUID): String = escPosText(escPosBytes(jobId))

    private fun normalized(text: String): String = text.replace(Regex("\\s+"), " ")

    private companion object {
        const val REVERSAL_REASON = "Hóspede não usou a lavanderia"
        const val ADJUSTMENT_REASON = "Desconto de cortesia da gerência"
        const val TRANSFER_REASON = "Avulso vai para a conta da empresa"
        const val REFUND_REASON = "Troco devolvido a mais"
        const val REOPEN_REASON = "Faltou lançar o frigobar"
        const val PRE_NSU = "004512"
        const val PRE_CODE = "A1B2C3"
        const val CAPTURE_NSU = "778899"
        const val DEPOSIT_NSU = "PIX55443"
        const val HOSTILE_DESCRIPTION = "<b>Diária</b> \${7*7} [[\${1+1}]] &amp; <script>alert-executado</script>"
        const val HOSTILE_REASON = "Motivo <i>x</i> __\${T(java.lang.Runtime)}__ ]]> &lt;"
        const val PAYER = "Ômega <b>&</b> Cia \${7*7}"
        const val HOSTILE_LABEL = "Grupo <u>Azul</u> [[\${7*7}]]"
        const val HOSTILE_GUEST = "Maria \${7*7} & <b>Souza</b>"
        const val ESC_FRAGMENT = 14
        const val ESC: Byte = 0x1B
        const val GS: Byte = 0x1D
        const val COLUMNS = 48
        const val DAYS = 40
        const val SUFFIX = 12
    }
}
