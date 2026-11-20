package com.zeny.hotel.reporting

import com.zeny.hotel.files.FilePurposeCode
import com.zeny.hotel.files.FilesTestConfiguration
import com.zeny.hotel.files.FilesTestSupport
import com.zeny.hotel.files.FilesTestSupport.Companion.pdf
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.reporting.application.ArchivedReportStore
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.pdfText
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doCallRealMethod
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.reset
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `ReportingApi.archiveDayReports` e `GET /api/v1/reports/archive` (M6 E23, D3 a D5) contra o banco e o armazenamento.
 * Cada teste usa uma data própria de 2056 (o banco é compartilhado; o arquivo e o snapshot são por data), com o
 * snapshot gravado por SQL válido de números reconhecíveis, para o PDF trazê-los.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, FilesTestConfiguration::class)
@Suppress("LongParameterList") // as portas e o apoio do cenário
class DayReportsArchiveTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val files: FilesTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val reporting: ReportingApi,
) {
    @Suppress("VarCouldBeVal") // o Spring injeta o campo lateinit
    @MockitoSpyBean
    private lateinit var store: ArchivedReportStore

    private val purpose = ReportingFilePurposes.DAY_REPORT.value

    @AfterEach
    fun restoreStore() {
        reset(store)
    }

    private fun snapshot(date: LocalDate) {
        jdbc
            .sql(
                """
                INSERT INTO reporting.daily_statistics (id, business_date, rooms_sold, rooms_complimentary,
                    rooms_house_use, rooms_available, rooms_out_of_order, arrivals, departures, no_shows,
                    cancellations, room_revenue, food_beverage_revenue, events_revenue, other_revenue, occupancy, adr,
                    revpar, created_at)
                VALUES (:id, :date, 41, 3, 2, 60, 5, 17, 19, 2, 6, 5432.10, 321.00, 0.00, 99.90, 73.33, 132.49, 90.54,
                    now())
                ON CONFLICT (business_date) DO NOTHING
                """.trimIndent(),
            ).param("id", UuidV7.generate())
            .param("date", date)
            .update()
    }

    private fun filesOf(owner: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM files.stored_file WHERE purpose = :purpose AND owner_entity_id = :owner")
            .param("purpose", purpose)
            .param("owner", owner.toString())
            .query(Int::class.java)
            .single()

    private fun indexRows(date: LocalDate): Int =
        jdbc
            .sql("SELECT count(*) FROM reporting.archived_report WHERE business_date = :date")
            .param("date", date)
            .query(Int::class.java)
            .single()

    private fun archiveReader() =
        iam.asTestUser(
            iam.createUserWith(
                TestGrant(
                    ReportingPermissions.permissions.single { it.code == ReportingPermissions.ARCHIVE_READ },
                    ALL,
                ),
            ),
        )

    @Test
    @Suppress("LongMethod") // o ciclo inteiro: gravação, listagem, download e trilha
    fun `archives the day report pdf with the snapshot numbers, lists it and serves it with the trail`() {
        val date = LocalDate.of(2056, 3, 14)
        snapshot(date)

        val archived = reporting.archiveDayReports(date).single()

        archived.businessDate shouldBe date
        archived.kind shouldBe ArchivedReportKind.DAY_REPORT
        val row = files.storedRow(archived.fileId)
        row["purpose"] shouldBe purpose
        row["sensitive"] shouldBe true
        row["content_type"] shouldBe "application/pdf"
        row["owner_entity_id"] shouldBe archived.id.toString()
        row["created_by"] shouldBe null
        // Cifrado no bucket: o objeto não é um PDF.
        String(files.rawObject("reporting/day-report/${archived.fileId}"), Charsets.ISO_8859_1) shouldNotContain
            "%PDF"
        filesOf(archived.id) shouldBe 1

        val reader = archiveReader()
        val listed = iam.call(GET, "/api/v1/reports/archive?date=$date", reader)
        listed.status shouldBe 200
        val body = files.json(listed)
        body["date"].asString() shouldBe date.toString()
        body["reports"].size() shouldBe 1
        body["reports"][0]["kind"].asString() shouldBe "DAY_REPORT"
        body["reports"][0]["fileId"].asString() shouldBe archived.fileId.toString()

        val (url, _) = files.downloadUrlOk(archived.fileId, reader)
        val response = files.serverGet(url)
        response.status shouldBe 200
        val content = response.contentAsByteArray
        String(content, 0, "%PDF".length, Charsets.US_ASCII) shouldBe "%PDF"
        val text = pdfText(content)
        text shouldContain "Fechamento do dia 14/03/2056"
        text shouldContain "Quartos vendidos: 41"
        text shouldContain "Quartos de cortesia: 3"
        text shouldContain "Quartos de uso da casa: 2"
        text shouldContain "Quartos disponiveis: 60"
        text shouldContain "Chegadas: 17"
        text shouldContain "Saidas: 19"
        text shouldContain "Receita de hospedagem: R$ 5.432,10"
        text shouldContain "Ocupacao: 73,33%"
        text shouldContain "Diaria media (ADR): R$ 132,49"
        text shouldContain "RevPAR: R$ 90,54"
        text shouldContain "Situacao das UHs"
        text shouldContain "Pendencias do fechamento"

        files.auditOf(archived.fileId).map { it.action } shouldContain "files.file.store"
        files.auditOf(archived.fileId).map { it.action } shouldContain "files.file.issue-download-url"
        files.auditOf(archived.fileId).map { it.action } shouldContain "files.file.download"
    }

    @Test
    fun `without the archive permission the list and the download are 403, and nobody uploads to the purpose`() {
        val date = LocalDate.of(2056, 4, 2)
        snapshot(date)
        val archived = reporting.archiveDayReports(date).single()
        val others =
            with(iam) {
                listOf(
                    asTestUser(createUserWith()),
                    asTestUser(
                        createUserWith(
                            TestGrant(
                                ReportingPermissions.permissions.single {
                                    it.code == ReportingPermissions.PENDING_READ
                                },
                                ALL,
                            ),
                        ),
                    ),
                )
            }

        others.forEach { other ->
            iam.call(GET, "/api/v1/reports/archive?date=$date", other).status shouldBe 403
            files.downloadUrl(archived.fileId, other).status shouldBe 403
        }
        iam.call(GET, "/api/v1/reports/archive?date=$date", iam.asAdmin()).status shouldBe 200
        files
            .upload(purpose, iam.asAdmin(), pdf("x"), "a.pdf", "application/pdf")
            .status shouldBe 400
    }

    @Test
    fun `a date without archive lists nothing, and the date is required`() {
        val reader = archiveReader()

        files.json(iam.call(GET, "/api/v1/reports/archive?date=2056-12-30", reader))["reports"].size() shouldBe 0
        iam.call(GET, "/api/v1/reports/archive", reader).status shouldBe 400
    }

    @Test
    fun `archiving again and in parallel gives one attachment linked to the owner and one index row`() {
        val date = LocalDate.of(2056, 5, 9)
        snapshot(date)
        val pool = Executors.newFixedThreadPool(PARALLEL)
        val results =
            try {
                pool
                    .invokeAll(List(PARALLEL) { Callable { reporting.archiveDayReports(date) } })
                    .map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
        val again = reporting.archiveDayReports(date)

        (results + listOf(again)).flatten().map { it.id }.toSet() shouldHaveSize 1
        (results + listOf(again)).flatten().map { it.fileId }.toSet() shouldHaveSize 1
        filesOf(again.single().id) shouldBe 1
        indexRows(date) shouldBe 1
    }

    @Test
    fun `a failure after the attachment is stored leaves no attachment and no index row, and a retry archives`() {
        val date = LocalDate.of(2056, 6, 21)
        snapshot(date)
        val before = files.countRows(purpose)
        doThrow(IllegalStateException("falha simulada do índice"))
            .doCallRealMethod()
            .`when`(store)
            .insert(ArgumentMatchers.any() ?: stubReport())

        shouldThrow<IllegalStateException> { reporting.archiveDayReports(date) }

        files.countRows(purpose) shouldBe before
        indexRows(date) shouldBe 0
        val archived = reporting.archiveDayReports(date).single()
        files.countRows(purpose) shouldBe before + 1
        filesOf(archived.id) shouldBe 1
        indexRows(date) shouldBe 1
    }

    @Test
    fun `the purpose is sensitive, server-only and read by the archive permission`() {
        val purposeDefinition = ReportingFilePurposes.purposes.single()

        purposeDefinition.code shouldBe FilePurposeCode("reporting.day-report")
        purposeDefinition.sensitive shouldBe true
        purposeDefinition.serverOnly shouldBe true
        purposeDefinition.downloadPermission shouldBe ReportingPermissions.ARCHIVE_READ
        ReportingFilePurposes.module shouldBe "reporting"
    }

    private fun stubReport() =
        ArchivedReport(
            UuidV7.generate(),
            LocalDate.of(2056, 1, 1),
            ArchivedReportKind.DAY_REPORT,
            UuidV7.generate(),
            Instant.EPOCH,
        )

    private companion object {
        const val PARALLEL = 3
        const val TIMEOUT_SECONDS = 120L
    }
}
