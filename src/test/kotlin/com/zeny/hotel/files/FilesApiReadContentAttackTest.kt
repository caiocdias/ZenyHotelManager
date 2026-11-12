package com.zeny.hotel.files

import com.zeny.hotel.files.FilesTestSupport.Companion.pdf
import com.zeny.hotel.files.FilesTestSupport.Companion.png
import com.zeny.hotel.files.infrastructure.StorageProperties
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import filesexample.FileExamplePermissions
import filesexample.FileExamplePermissions.definition
import filesexample.FileExamplePurposes
import filesexample.FileExamplesConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest

/** Ataques a `FilesApi.readContent` (M5 E11, D-E11-7, Task 4): dono, finalidade, objeto ausente e leitura sem efeito. */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    FilesTestConfiguration::class,
    FileExamplesConfiguration::class,
    MutableClockConfiguration::class,
)
@TestPropertySource(properties = [FILES_TEST_URL_TTL])
@Suppress("LongParameterList") // os apoios de teste e os beans exercitados direto
class FilesApiReadContentAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val files: FilesTestSupport,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val filesApi: FilesApi,
    @Autowired private val s3: S3Client,
    @Autowired private val storage: StorageProperties,
    @Autowired private val jdbc: JdbcClient,
) {
    private val photo = FileExamplePurposes.PHOTO
    private val photoUpload = TestGrant(definition(FileExamplePermissions.PHOTOS_UPLOAD), ALL)
    private val documentUpload = TestGrant(definition(FileExamplePermissions.DOCUMENTS_UPLOAD), ALL)

    @BeforeEach
    fun synchronizeThisContextCatalog() {
        synchronizer.synchronize()
    }

    private fun uploadPhoto(seed: Int): Pair<FileId, ByteArray> =
        with(iam) {
            val user = createUserWith(photoUpload)
            val content = png(seed = seed)
            FileId(files.uploadOk(photo.value, asTestUser(user), content)) to content
        }

    private fun sensitiveDocument(): FileId {
        val user = with(iam) { createUserWith(documentUpload) }
        return FileId(
            files.uploadOk(
                FileExamplePurposes.DOCUMENT.value,
                with(iam) { asTestUser(user) },
                pdf("sensível"),
                "doc.pdf",
            ),
        )
    }

    private fun keyOf(id: FileId): String =
        files.objectKeys("fileexamples/").single { it.endsWith(id.value.toString()) }

    private fun auditCount(): Int = jdbc.sql("SELECT count(*) FROM audit.audit_entry").query(Int::class.java).single()

    @Test
    fun `refuses an empty, blank or differently cased owner id as a different owner`() {
        val (id, _) = uploadPhoto(31)
        filesApi.attach(id, photo, "owner-1")

        for (owner in listOf("", " ", "owner-1 ", " owner-1", "OWNER-1")) {
            shouldThrow<ConflictException> { filesApi.readContent(id, photo, owner) }
                .code.value shouldBe "FILE_ALREADY_ATTACHED"
        }
    }

    @Test
    fun `refuses an orphan even with an empty owner id`() {
        val (id, _) = uploadPhoto(32)

        shouldThrow<ConflictException> { filesApi.readContent(id, photo, "") }
            .code.value shouldBe "FILE_ALREADY_ATTACHED"
    }

    @Test
    fun `refuses an unregistered purpose as a purpose mismatch`() {
        val (id, _) = uploadPhoto(33)
        filesApi.attach(id, photo, "owner-1")

        shouldThrow<BusinessRuleException> { filesApi.readContent(id, FilePurposeCode("nope.nothing"), "owner-1") }
            .code.value shouldBe "FILE_PURPOSE_MISMATCH"
    }

    @Test
    fun `does not reveal sensitivity to a caller with another owner or purpose`() {
        val id = sensitiveDocument()
        filesApi.attach(id, FileExamplePurposes.DOCUMENT, "owner-1")

        shouldThrow<ConflictException> { filesApi.readContent(id, FileExamplePurposes.DOCUMENT, "owner-2") }
            .code.value shouldBe "FILE_ALREADY_ATTACHED"
        shouldThrow<BusinessRuleException> { filesApi.readContent(id, photo, "owner-1") }
            .code.value shouldBe "FILE_PURPOSE_MISMATCH"
    }

    @Test
    fun `refuses a sensitive file even when the object is intact`() {
        val id = sensitiveDocument()
        filesApi.attach(id, FileExamplePurposes.DOCUMENT, "owner-1")

        shouldThrow<IllegalArgumentException> { filesApi.readContent(id, FileExamplePurposes.DOCUMENT, "owner-1") }
    }

    @Test
    fun `fails instead of returning empty bytes when the object is missing from the bucket`() {
        val (id, _) = uploadPhoto(34)
        filesApi.attach(id, photo, "owner-1")
        s3.deleteObject(
            DeleteObjectRequest
                .builder()
                .bucket(storage.bucket)
                .key(keyOf(id))
                .build(),
        )

        shouldThrow<Exception> { filesApi.readContent(id, photo, "owner-1") }
    }

    @Test
    fun `fails with IllegalStateException when the object was replaced by empty content`() {
        val (id, _) = uploadPhoto(35)
        filesApi.attach(id, photo, "owner-1")
        s3.putObject(
            PutObjectRequest
                .builder()
                .bucket(storage.bucket)
                .key(keyOf(id))
                .build(),
            RequestBody.fromBytes(ByteArray(0)),
        )

        shouldThrow<IllegalStateException> { filesApi.readContent(id, photo, "owner-1") }
    }

    @Test
    fun `reads again after the file was detached and attached back`() {
        val (id, content) = uploadPhoto(36)
        filesApi.attach(id, photo, "owner-1")
        filesApi.detach(id, photo, "owner-1")
        filesApi.attach(id, photo, "owner-2")

        shouldThrow<ConflictException> { filesApi.readContent(id, photo, "owner-1") }
        filesApi.readContent(id, photo, "owner-2").content shouldBe content
    }

    @Test
    fun `reading changes neither the row nor the audit trail nor the bucket`() {
        val (id, _) = uploadPhoto(37)
        filesApi.attach(id, photo, "owner-1")
        val rowBefore = files.storedRow(id.value)
        val keysBefore = files.objectKeys("fileexamples/")
        val auditBefore = auditCount()

        repeat(3) { filesApi.readContent(id, photo, "owner-1") }
        runCatching { filesApi.readContent(id, photo, "owner-2") }

        files.storedRow(id.value) shouldBe rowBefore
        files.objectKeys("fileexamples/") shouldBe keysBefore
        auditCount() shouldBe auditBefore
    }

    @Test
    fun `toString shows the size and never the content`() {
        val (id, content) = uploadPhoto(38)
        filesApi.attach(id, photo, "owner-1")

        val text = filesApi.readContent(id, photo, "owner-1").toString()

        text shouldContain "sizeBytes=${content.size}"
        text shouldNotContain content.joinToString(",")
        text shouldNotContain "[B@"
    }
}
