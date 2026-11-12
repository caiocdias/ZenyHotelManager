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

/**
 * `FilesApi.readContent` (M5 E11, D-E11-7): o servidor lê o conteúdo de um anexo comum conferindo
 * finalidade e dono; o sensível é recusado antes de tocar o objeto; sem trilha. Mesmo contexto do
 * [FilesApiTest].
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
class FilesApiReadContentTest(
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

    @Test
    fun `returns the bytes and the summary of a common file attached to the owner`() {
        val (id, content) = uploadPhoto(21)
        filesApi.attach(id, photo, "owner-1")

        val read = filesApi.readContent(id, photo, "owner-1")

        read.content shouldBe content
        read.summary.id shouldBe id
        read.summary.ownerEntityId shouldBe "owner-1"
        read.summary.sensitive shouldBe false
        read.toString() shouldNotContain content.toString()
        read.toString() shouldNotContain "content="
    }

    @Test
    fun `refuses another owner, an orphan, another purpose, a deleted and an unknown file`() {
        val (id, _) = uploadPhoto(22)
        shouldThrow<ConflictException> { filesApi.readContent(id, photo, "owner-1") }
            .code.value shouldBe "FILE_ALREADY_ATTACHED"
        filesApi.attach(id, photo, "owner-1")
        shouldThrow<ConflictException> { filesApi.readContent(id, photo, "owner-2") }
            .code.value shouldBe "FILE_ALREADY_ATTACHED"
        shouldThrow<BusinessRuleException> { filesApi.readContent(id, FileExamplePurposes.DOCUMENT, "owner-1") }
            .code.value shouldBe "FILE_PURPOSE_MISMATCH"
        filesApi.detach(id, photo, "owner-1")
        shouldThrow<ConflictException> { filesApi.readContent(id, photo, "owner-1") }
            .code.value shouldBe "FILE_ALREADY_ATTACHED"
        filesApi.attach(id, photo, "owner-1")
        filesApi.delete(id, photo, "owner-1")
        shouldThrow<NotFoundException> { filesApi.readContent(id, photo, "owner-1") }
            .code.value shouldBe "FILE_NOT_FOUND"
        shouldThrow<NotFoundException> { filesApi.readContent(FileId(UuidV7.generate()), photo, "owner-1") }
            .code.value shouldBe "FILE_NOT_FOUND"
    }

    @Test
    fun `refuses a sensitive file with IllegalArgumentException before reading the object`() {
        val user = with(iam) { createUserWith(documentUpload) }
        val id =
            FileId(
                files.uploadOk(
                    FileExamplePurposes.DOCUMENT.value,
                    with(iam) { asTestUser(user) },
                    pdf("sensível"),
                    "doc.pdf",
                ),
            )
        filesApi.attach(id, FileExamplePurposes.DOCUMENT, "owner-1")
        // Sem o objeto no bucket, ler falharia de outro jeito: só a recusa antes da leitura dá IAE.
        s3.deleteObject(
            DeleteObjectRequest
                .builder()
                .bucket(storage.bucket)
                .key(keyOf(id))
                .build(),
        )

        shouldThrow<IllegalArgumentException> { filesApi.readContent(id, FileExamplePurposes.DOCUMENT, "owner-1") }
    }

    @Test
    fun `fails with IllegalStateException when the object in the bucket no longer matches the SHA-256`() {
        val (id, _) = uploadPhoto(23)
        filesApi.attach(id, photo, "owner-1")
        s3.putObject(
            PutObjectRequest
                .builder()
                .bucket(storage.bucket)
                .key(keyOf(id))
                .build(),
            RequestBody.fromBytes(png(seed = 99)),
        )

        shouldThrow<IllegalStateException> { filesApi.readContent(id, photo, "owner-1") }
    }

    @Test
    fun `leaves no audit entry`() {
        val (id, _) = uploadPhoto(24)
        filesApi.attach(id, photo, "owner-1")
        val before = files.auditOf(id.value).size

        filesApi.readContent(id, photo, "owner-1")

        files.auditOf(id.value).size shouldBe before
        jdbc
            .sql("SELECT count(*) FROM audit.audit_entry WHERE action LIKE 'files.file.%read%'")
            .query(Int::class.java)
            .single() shouldBe 0
    }

    private fun keyOf(id: FileId): String =
        files.objectKeys("fileexamples/").single { it.endsWith(id.value.toString()) }
}
