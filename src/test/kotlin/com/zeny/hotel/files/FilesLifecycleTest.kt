package com.zeny.hotel.files

import com.zeny.hotel.files.FilesTestSupport.Companion.pdf
import com.zeny.hotel.files.FilesTestSupport.Companion.png
import com.zeny.hotel.files.TestImages.METADATA_MARKER
import com.zeny.hotel.files.application.FileOrphanPurge
import com.zeny.hotel.files.domain.FileContentRules
import com.zeny.hotel.files.infrastructure.FileOrphanPurgeJob
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
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import filesexample.FileExamplePermissions
import filesexample.FileExamplePermissions.definition
import filesexample.FileExamplePurposes
import filesexample.FileExamplesConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ciclo de vida dos anexos (M2 E11, H10 a H12 do roadmap do M2), contra o MinIO do Testcontainers:
 * `FilesApi.detach` e `delete` (objeto removido só depois do commit), o expurgo de órfãos com o
 * relógio controlado e a trilha com ator sistema, o upload idempotente por `clientFileId` e o
 * reencode das fotos de uma finalidade marcada. Mesmo contexto do [FilesApiTest].
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    FilesTestConfiguration::class,
    FileExamplesConfiguration::class,
    MutableClockConfiguration::class,
)
@TestPropertySource(properties = [FILES_TEST_URL_TTL])
@Suppress("LongParameterList") // os apoios de teste e os beans exercitados direto (API pública, expurgo, transação)
class FilesLifecycleTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val files: FilesTestSupport,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val filesApi: FilesApi,
    @Autowired private val purgeJob: FileOrphanPurgeJob,
    @Autowired private val purge: FileOrphanPurge,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbc: JdbcClient,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)
    private val photo = FileExamplePurposes.PHOTO
    private val photoUpload = TestGrant(definition(FileExamplePermissions.PHOTOS_UPLOAD), ALL)
    private val photoRead = TestGrant(definition(FileExamplePermissions.PHOTOS_READ), ALL)
    private val documentUpload = TestGrant(definition(FileExamplePermissions.DOCUMENTS_UPLOAD), ALL)

    @BeforeEach
    fun synchronizeThisContextCatalog() {
        synchronizer.synchronize()
    }

    // --- detach e delete ----------------------------------------------------------------------------

    @Test
    fun `detach turns an attached file into an orphan again, audited, and refuses another owner or purpose`(): Unit =
        with(iam) {
            val user = createUserWith(photoUpload, photoRead)
            val id = FileId(files.uploadOk(photo.value, asTestUser(user), png(seed = 11)))
            filesApi.attach(id, photo, "owner-1").ownerEntityId shouldBe "owner-1"

            shouldThrow<ConflictException> { filesApi.detach(id, photo, "owner-2") }
                .code.value shouldBe "FILE_ALREADY_ATTACHED"
            shouldThrow<BusinessRuleException> { filesApi.detach(id, FileExamplePurposes.DOCUMENT, "owner-1") }
                .code.value shouldBe "FILE_PURPOSE_MISMATCH"
            shouldThrow<NotFoundException> { filesApi.detach(FileId(UuidV7.generate()), photo, "owner-1") }
                .code.value shouldBe "FILE_NOT_FOUND"

            val detached = filesApi.detach(id, photo, "owner-1")

            detached.ownerEntityId.shouldBeNull()
            val row = files.storedRow(id.value)
            row["owner_entity_id"].shouldBeNull()
            row["attached_at"].shouldBeNull()
            (row["detached_at"] as Timestamp).toInstant().shouldBeNowAtDbPrecision()
            row["deleted_at"].shouldBeNull()
            filesApi
                .find(id)
                .shouldNotBeNull()
                .ownerEntityId
                .shouldBeNull()
            // Desvincular um órfão não muda nada (nem grava outra entrada).
            filesApi.detach(id, photo, "owner-1")
            val entries = files.auditOf(id.value)
            entries.map { it.action } shouldContainExactly
                listOf("files.file.upload", "files.file.attach", "files.file.detach")
            entries.last().afterState.shouldNotBeNull() shouldContain "\"ownerEntityId\": null"
            // O anexo desvinculado pode ser vinculado de novo.
            filesApi.attach(id, photo, "owner-3").ownerEntityId shouldBe "owner-3"
            files.objectExists("fileexamples/photo/${id.value}") shouldBe true
        }

    @Test
    fun `delete marks the row and removes the object only after the commit, and the file is then not found`(): Unit =
        with(iam) {
            val user = createUserWith(photoUpload, photoRead)
            val id = FileId(files.uploadOk(photo.value, asTestUser(user), png(seed = 12)))
            val key = "fileexamples/photo/${id.value}"
            filesApi.attach(id, photo, "owner-1")
            shouldThrow<ConflictException> { filesApi.delete(id, photo, "owner-2") }
                .code.value shouldBe "FILE_ALREADY_ATTACHED"
            shouldThrow<BusinessRuleException> { filesApi.delete(id, FileExamplePurposes.DOCUMENT, "owner-1") }
                .code.value shouldBe "FILE_PURPOSE_MISMATCH"

            transaction.executeWithoutResult {
                filesApi.delete(id, photo, "owner-1")
                // Dentro da transação o anexo já sumiu, mas o objeto continua no bucket: a exclusão pode desfazer.
                filesApi.find(id).shouldBeNull()
                files.objectExists(key) shouldBe true
            }

            files.objectExists(key) shouldBe false
            val row = files.storedRow(id.value)
            (row["deleted_at"] as Timestamp).toInstant().shouldBeNowAtDbPrecision()
            row["owner_entity_id"] shouldBe "owner-1" // a linha fica, com o dono, para a trilha
            filesApi.find(id).shouldBeNull()
            call(GET, "/api/v1/files/${id.value}", asTestUser(user)).problem(404, "FILE_NOT_FOUND")
            files.downloadUrl(id.value, asTestUser(user)).problem(404, "FILE_NOT_FOUND")
            shouldThrow<NotFoundException> { filesApi.attach(id, photo, "owner-1") }.code.value shouldBe
                "FILE_NOT_FOUND"
            shouldThrow<NotFoundException> { filesApi.detach(id, photo, "owner-1") }.code.value shouldBe
                "FILE_NOT_FOUND"
            shouldThrow<NotFoundException> { filesApi.delete(id, photo, "owner-1") }.code.value shouldBe
                "FILE_NOT_FOUND"
            val deletion = files.auditOf(id.value).last()
            deletion.action shouldBe "files.file.delete"
            deletion.outcome shouldBe "SUCCEEDED"
            deletion.afterState.shouldNotBeNull() shouldContain "\"deletedAt\": \""
        }

    @Test
    fun `a delete in a transaction that rolls back keeps the row and the object, and an orphan can be deleted`(): Unit =
        with(iam) {
            val user = createUserWith(photoUpload, photoRead)
            val id = FileId(files.uploadOk(photo.value, asTestUser(user), png(seed = 13)))
            val key = "fileexamples/photo/${id.value}"
            filesApi.attach(id, photo, "owner-1")

            transaction.executeWithoutResult { status ->
                filesApi.delete(id, photo, "owner-1")
                status.setRollbackOnly()
            }

            files.objectExists(key) shouldBe true
            files.storedRow(id.value)["deleted_at"].shouldBeNull()
            filesApi.find(id).shouldNotBeNull()
            files.auditOf(id.value).map { it.action } shouldNotContain "files.file.delete"
            files.httpGet(files.downloadUrlOk(id.value, asTestUser(user)).first).statusCode() shouldBe 200

            // Um órfão (nunca vinculado) é apagado por qualquer registro da finalidade.
            val orphan = FileId(files.uploadOk(photo.value, asTestUser(user), png(seed = 14)))
            filesApi.delete(orphan, photo, "owner-9")
            files.objectExists("fileexamples/photo/${orphan.value}") shouldBe false
            filesApi.find(orphan).shouldBeNull()
        }

    // --- Expurgo de órfãos ----------------------------------------------------------------------------

    @Test
    fun `the purge deletes orphans older than the retention with a system audit entry, and keeps the others`(): Unit =
        with(iam) {
            val user = createUserWith(photoUpload, photoRead, documentUpload)
            val auth = asTestUser(user)
            // O contexto e o banco são compartilhados: o relógio recua antes e volta no fim, para nada ser
            // gravado com um instante à frente da hora real (outro teste, que busca a entrada mais recente da
            // trilha, a pegaria), e o expurgo só alcança os órfãos deste teste.
            val start = clock.instant()
            clock.advance(Duration.ofHours(-48))

            fun advance(duration: Duration) = clock.advance(duration)
            try {
                val old = FileId(files.uploadOk(photo.value, auth, png(seed = 21)))
                val oldSensitive =
                    FileId(files.uploadOk(FileExamplePurposes.DOCUMENT.value, auth, pdf("antigo"), "a.pdf"))
                val attached = FileId(files.uploadOk(photo.value, auth, png(seed = 22)))
                filesApi.attach(attached, photo, "owner-1")
                val detachedLater = FileId(files.uploadOk(photo.value, auth, png(seed = 23)))
                filesApi.attach(detachedLater, photo, "owner-2")

                advance(Duration.ofHours(23))
                val recent = FileId(files.uploadOk(photo.value, auth, png(seed = 24)))
                filesApi.detach(detachedLater, photo, "owner-2")
                advance(Duration.ofHours(2)) // 25 h desde o primeiro upload; 2 h desde o recente e o desvínculo

                purgeJob.purgeOrphans()

                listOf(old, oldSensitive).forEach { id ->
                    withClue(id) {
                        filesApi.find(id).shouldBeNull()
                        (files.storedRow(id.value)["deleted_at"] as Timestamp).toInstant().shouldBeNowAtDbPrecision()
                        files.objectExists(objectKeyOf(id)) shouldBe false
                        // O nome original (pode citar o titular) sai com o anexo (M3 E03).
                        files.storedRow(id.value)["original_name"].shouldBeNull()
                        val entry = files.auditOf(id.value).last()
                        entry.action shouldBe "files.file.purge-orphan"
                        entry.outcome shouldBe "SUCCEEDED"
                        // Ator sistema: sem usuário nem sessão.
                        entry.userId.shouldBeNull()
                        entry.sessionId.shouldBeNull()
                        entry.afterState.shouldNotBeNull() shouldContain "\"deletedAt\": \""
                    }
                }
                listOf(attached, detachedLater, recent).forEach { id ->
                    withClue(id) {
                        filesApi.find(id).shouldNotBeNull()
                        files.objectExists(objectKeyOf(id)) shouldBe true
                        files.storedRow(id.value)["original_name"].shouldNotBeNull()
                        files.auditOf(id.value).map { it.action } shouldNotContain "files.file.purge-orphan"
                    }
                }

                // Um candidato vinculado entre a busca e o expurgo é conferido de novo, com a linha travada.
                purge.purge(attached) shouldBe false

                advance(Duration.ofHours(23)) // 25 h desde o recente e o desvínculo
                purgeJob.purgeOrphans()

                listOf(detachedLater, recent).forEach { id ->
                    withClue(id) {
                        filesApi.find(id).shouldBeNull()
                        files.objectExists(objectKeyOf(id)) shouldBe false
                        files.auditOf(id.value).last().action shouldBe "files.file.purge-orphan"
                    }
                }
                filesApi.find(attached).shouldNotBeNull().ownerEntityId shouldBe "owner-1"
                files.objectExists(objectKeyOf(attached)) shouldBe true
                // Nada vencido sobra.
                purge.candidates(FileOrphanPurgeJob.BATCH_SIZE) shouldBe emptyList()
            } finally {
                clock.advance(Duration.between(clock.instant(), start))
            }
        }

    // --- Upload idempotente (clientFileId) -----------------------------------------------------------

    @Test
    fun `an upload repeated with the same clientFileId returns the same file without storing it again`(): Unit =
        with(iam) {
            val user = createUserWith(photoUpload, photoRead, documentUpload)
            val clientFileId = UuidV7.generate()
            val content = png(seed = 31)

            val first = files.upload(photo.value, asTestUser(user), content, clientFileId = clientFileId.toString())

            withClue(first.getContentAsString(Charsets.UTF_8)) { first.status shouldBe 201 }
            first.getHeader(HttpHeaders.LOCATION) shouldBe "/api/v1/files/$clientFileId"
            files.json(first)["id"].asString() shouldBe clientFileId.toString()

            val repeated = files.upload(photo.value, asTestUser(user), content, clientFileId = clientFileId.toString())

            withClue(repeated.getContentAsString(Charsets.UTF_8)) { repeated.status shouldBe 200 }
            repeated.getContentAsString(Charsets.UTF_8) shouldBe first.getContentAsString(Charsets.UTF_8)
            rowsWithId(clientFileId) shouldBe 1
            files.objectKeys("fileexamples/photo/$clientFileId") shouldContainExactly
                listOf("fileexamples/photo/$clientFileId")
            files.auditOf(clientFileId).map { it.action } shouldContainExactly listOf("files.file.upload")

            // O mesmo id com outro conteúdo, outra finalidade ou de outro usuário: 409, sem gravar nada.
            files
                .upload(photo.value, asTestUser(user), png(seed = 32), clientFileId = clientFileId.toString())
                .problem(409, "CLIENT_FILE_ID_CONFLICT")
            files
                .upload(
                    FileExamplePurposes.DOCUMENT.value,
                    asTestUser(user),
                    pdf("x"),
                    "x.pdf",
                    clientFileId = clientFileId.toString(),
                ).problem(409, "CLIENT_FILE_ID_CONFLICT")
            val other = createUserWith(photoUpload)
            files
                .upload(photo.value, asTestUser(other), content, clientFileId = clientFileId.toString())
                .problem(409, "CLIENT_FILE_ID_CONFLICT")
            rowsWithId(clientFileId) shouldBe 1
            files.storedRow(clientFileId)["sha256"] shouldBe FileContentRules.sha256(content)

            // Apagado, o id não volta a valer.
            filesApi.delete(FileId(clientFileId), photo, "owner-1")
            files
                .upload(photo.value, asTestUser(user), content, clientFileId = clientFileId.toString())
                .problem(409, "CLIENT_FILE_ID_CONFLICT")
        }

    @Test
    fun `a clientFileId that is not a canonical uuid v7 is refused with the field, and nothing is stored`(): Unit =
        with(iam) {
            val user = createUserWith(photoUpload)
            val before = files.countRows(photo.value)
            listOf("abc", UUID.randomUUID().toString(), "1-1-1-1-1", UuidV7.generate().toString().replace("-", ""))
                .forEach { invalid ->
                    withClue(invalid) {
                        val problem =
                            files
                                .upload(photo.value, asTestUser(user), png(seed = 33), clientFileId = invalid)
                                .problem(400, "CLIENT_FILE_ID_INVALID")
                        problem["errors"][0]["field"].asString() shouldBe "clientFileId"
                    }
                }
            files.countRows(photo.value) shouldBe before
        }

    @Test
    fun `two simultaneous uploads with the same clientFileId store the file once`(): Unit =
        with(iam) {
            val user = createUserWith(photoUpload)
            val clientFileId = UuidV7.generate().toString()
            val content = png(seed = 34)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val responses =
                    List(2) {
                        CompletableFuture.supplyAsync({
                            start.await()
                            files.upload(photo.value, asTestUser(user), content, clientFileId = clientFileId)
                        }, executor)
                    }
                start.countDown()
                val statuses = responses.map { it.get(30, TimeUnit.SECONDS).status }

                statuses shouldContainExactlyInAnyOrder listOf(201, 200)
                rowsWithId(UUID.fromString(clientFileId)) shouldBe 1
                files.objectKeys("fileexamples/photo/$clientFileId") shouldContainExactly
                    listOf("fileexamples/photo/$clientFileId")
            } finally {
                executor.shutdownNow()
            }
        }

    // --- Reencode ------------------------------------------------------------------------------------

    @Test
    fun `a photo of a purpose with reencode is stored without exif or gps, within the maximum side`(): Unit =
        with(iam) {
            val user = createUserWith(photoUpload, photoRead)
            val purpose = FileExamplePurposes.REENCODED_PHOTO.value
            val original = TestImages.jpeg(3000, 1000, exif = true)
            val clientFileId = UuidV7.generate().toString()

            val response = files.upload(purpose, asTestUser(user), original, "foto.jpg", "image/jpeg", clientFileId)

            withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 201 }
            val id = UUID.fromString(files.json(response)["id"].asString())
            val stored = files.rawObject("fileexamples/reencoded-photo/$id")
            TestImages.jpegMarkers(stored) shouldNotContain APP1
            String(stored, Charsets.ISO_8859_1) shouldNotContain METADATA_MARKER
            String(stored, Charsets.ISO_8859_1) shouldNotContain "Exif"
            TestImages.read(stored).let {
                it.width shouldBe 2048
                it.height shouldBe 683
            }
            // O SHA-256 e o tamanho gravados são os do conteúdo regravado; o recebido fica à parte.
            val row = files.storedRow(id)
            row["sha256"] shouldBe FileContentRules.sha256(stored)
            row["received_sha256"] shouldBe FileContentRules.sha256(original)
            row["size_bytes"] shouldBe stored.size.toLong()
            files.json(response)["sizeBytes"].asLong() shouldBe stored.size.toLong()
            files.json(response)["contentType"].asString() shouldBe "image/jpeg"
            // O download entrega o regravado.
            files.httpGet(files.downloadUrlOk(id, asTestUser(user)).first).body() shouldBe stored
            // A repetição do mesmo arquivo original com o mesmo id é reconhecida (pelo SHA-256 do recebido).
            files.upload(purpose, asTestUser(user), original, "foto.jpg", "image/jpeg", clientFileId).status shouldBe
                200

            // Poliglota PNG: o HTML depois do IEND e os chunks de texto não chegam ao bucket.
            val polyglot = files.uploadOk(purpose, asTestUser(user), TestImages.png(64, 48, trailing = true), "a.png")
            val storedPng = files.rawObject("fileexamples/reencoded-photo/$polyglot")
            String(storedPng, Charsets.ISO_8859_1) shouldNotContain "<script"
            String(storedPng, Charsets.ISO_8859_1) shouldNotContain METADATA_MARKER
            TestImages.pngChunks(storedPng).second shouldBe false
        }

    @Test
    fun `a purpose with reencode refuses unreadable images and webp, and other purposes are not reencoded`(): Unit =
        with(iam) {
            val user = createUserWith(photoUpload)
            val purpose = FileExamplePurposes.REENCODED_PHOTO.value
            val before = files.countRows(purpose)

            files
                .upload(purpose, asTestUser(user), png(size = 200))
                .problem(400, "FILE_IMAGE_UNREADABLE")["errors"][0]["field"]
                .asString() shouldBe "file"
            files
                .upload(
                    purpose,
                    asTestUser(user),
                    TestImages.pngHeader(20_000, 20_000),
                ).problem(400, "FILE_IMAGE_TOO_LARGE")
            val webp = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBPVP8 ".toByteArray() + ByteArray(8)
            files.upload(purpose, asTestUser(user), webp, "a.webp", "image/webp").problem(400, "FILE_TYPE_NOT_ACCEPTED")
            files.countRows(purpose) shouldBe before

            // A foto comum (sem reencode) guarda o conteúdo como chegou, metadados inclusive.
            val small = TestImages.png(8, 8)
            small.size shouldBeLessThan FileExamplePurposes.PHOTO_MAX_SIZE.toInt()
            val id = files.uploadOk(photo.value, asTestUser(user), small)
            files.rawObject("fileexamples/photo/$id") shouldBe small
        }

    /** O instante gravado é o do relógio da aplicação, na precisão do banco (microssegundos, arredondados). */
    private fun Instant.shouldBeNowAtDbPrecision() {
        withClue("$this ~ ${clock.instant()}") {
            Duration.between(clock.instant(), this).abs() shouldBeLessThanOrEqualTo Duration.ofNanos(500)
        }
    }

    private fun objectKeyOf(id: FileId): String = files.storedRow(id.value)["object_key"] as String

    private fun rowsWithId(id: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM files.stored_file WHERE id = :id")
            .param("id", id)
            .query(Int::class.java)
            .single()

    private companion object {
        const val APP1 = 0xE1
    }
}
