package com.zeny.hotel.files

import com.zeny.hotel.files.FilesTestSupport.Companion.pdf
import com.zeny.hotel.files.FilesTestSupport.Companion.png
import com.zeny.hotel.files.domain.FileContentRules
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import filesexample.FileExamplePermissions
import filesexample.FileExamplePermissions.definition
import filesexample.FileExamplePurposes
import filesexample.FileExamplesConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * `FilesApi.store` (E23 do M6, D2): a gravação de conteúdo pelo servidor, fora de requisição. Mesmo
 * contexto do [FilesApiTest].
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    FilesTestConfiguration::class,
    FileExamplesConfiguration::class,
    MutableClockConfiguration::class,
)
@TestPropertySource(properties = [FILES_TEST_URL_TTL])
@Suppress("LongParameterList") // os apoios de teste e os beans exercitados direto
class FilesServerStoreTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val files: FilesTestSupport,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val filesApi: FilesApi,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)
    private val serverOnly = FileExamplePurposes.SERVER_ONLY_DOCUMENT
    private val documentRead = TestGrant(definition(FileExamplePermissions.DOCUMENTS_READ), ALL)

    @BeforeEach
    fun synchronizeThisContextCatalog() {
        synchronizer.synchronize()
    }

    @Test
    fun `stores a sensitive pdf encrypted, linked to the owner, audited without name or content, and downloadable`() {
        val content = pdf("conteudo-secreto-do-fechamento")

        val summary = filesApi.store(serverOnly, "owner-77", "fechamento-secreto.pdf", content)

        summary.purpose shouldBe serverOnly
        summary.ownerEntityId shouldBe "owner-77"
        summary.sensitive shouldBe true
        summary.contentType shouldBe "application/pdf"
        summary.sizeBytes shouldBe content.size.toLong()
        summary.createdBy.shouldBeNull()
        filesApi.find(summary.id).shouldNotBeNull().ownerEntityId shouldBe "owner-77"

        val row = files.storedRow(summary.id.value)
        row["sha256"] shouldBe FileContentRules.sha256(content)
        row["created_by"].shouldBeNull()
        row["owner_entity_id"] shouldBe "owner-77"
        val raw = files.rawObject("fileexamples/server-only-document/${summary.id.value}")
        String(raw, Charsets.ISO_8859_1) shouldNotContain "conteudo-secreto"
        raw.size shouldBe (row["stored_size_bytes"] as Number).toInt()

        val trail = files.auditOf(summary.id.value)
        trail.map { it.action } shouldContainExactly listOf("files.file.store")
        trail.single().userId.shouldBeNull()
        trail.single().outcome shouldBe "SUCCEEDED"
        val after = trail.single().afterState.shouldNotBeNull()
        after shouldContain "\"ownerEntityId\": \"owner-77\""
        after shouldNotContain "fechamento-secreto"
        after shouldNotContain "conteudo-secreto"

        with(iam) {
            val reader = createUserWith(documentRead)
            val (url, _) = files.downloadUrlOk(summary.id.value, asTestUser(reader))
            val response = files.serverGet(url)
            response.status shouldBe 200
            response.contentAsByteArray shouldBe content
        }
    }

    @Test
    fun `stores into a common purpose too, in clear`() {
        val content = png(seed = 5)

        val summary = filesApi.store(FileExamplePurposes.PHOTO, "owner-78", "foto.png", content)

        summary.sensitive shouldBe false
        files.rawObject("fileexamples/photo/${summary.id.value}") shouldBe content
        files.auditOf(summary.id.value).map { it.action } shouldContainExactly listOf("files.file.store")
    }

    @Test
    fun `refuses content that does not match the purpose, with IllegalArgumentException and nothing stored`() {
        val before = files.countRows(serverOnly.value)

        shouldThrow<IllegalArgumentException> { filesApi.store(serverOnly, "owner-79", "a.pdf", png()) }
        shouldThrow<IllegalArgumentException> { filesApi.store(serverOnly, "owner-79", "a.pdf", ByteArray(0)) }
        shouldThrow<IllegalArgumentException> {
            filesApi.store(
                serverOnly,
                "owner-79",
                "a.pdf",
                pdf("x") + ByteArray(FileExamplePurposes.DOCUMENT_MAX_SIZE.toInt()),
            )
        }
        shouldThrow<IllegalArgumentException> { filesApi.store(serverOnly, "owner-79", "a.png", pdf("x")) }
        shouldThrow<IllegalArgumentException> {
            filesApi.store(
                FilePurposeCode("fileexamples.nope"),
                "o",
                "a.pdf",
                pdf("x"),
            )
        }

        files.countRows(serverOnly.value) shouldBe before
    }

    @Test
    fun `a transaction that rolls back removes the stored object`() {
        var key = ""

        transaction.executeWithoutResult { status ->
            val summary = filesApi.store(serverOnly, "owner-80", "a.pdf", pdf("desfeito"))
            key = "fileexamples/server-only-document/${summary.id.value}"
            files.objectExists(key) shouldBe true
            status.setRollbackOnly()
        }

        files.objectExists(key) shouldBe false
    }
}
