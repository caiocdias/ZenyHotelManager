package com.zeny.hotel.files

import com.zeny.hotel.files.FilesTestSupport.Companion.jpeg
import com.zeny.hotel.files.FilesTestSupport.Companion.pdf
import com.zeny.hotel.files.FilesTestSupport.Companion.png
import com.zeny.hotel.files.application.DownloadGrant
import com.zeny.hotel.files.application.DownloadTokens
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.shared.FieldCipher
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MinioContainer
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.TestEncryptionKeys
import com.zeny.hotel.testsupport.asUser
import filesexample.FileExamplePermissions
import filesexample.FileExamplePermissions.definition
import filesexample.FileExamplePurposes
import filesexample.FileExamplesConfiguration
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.mock.web.MockMultipartFile
import org.springframework.mock.web.MockPart
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URI
import java.net.URLDecoder
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Anexos pela API, contra o MinIO do Testcontainers (M1 E08, ARCHITECTURE §8.3 e §14, RNF-09):
 * upload validado pelo conteúdo e pelos limites da finalidade, URL assinada que baixa e vence no
 * prazo configurado, anexo sensível cifrado no bucket e servido decifrado pelo servidor, com cada
 * acesso na trilha, permissões da finalidade e o usuário de serviço restrito ao bucket. O vencimento
 * é conferido sem esperar (M2 E11): o da URL do servidor com o relógio controlado ([MutableClock]) e
 * o da URL do bucket com uma URL assinada no passado.
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    FilesTestConfiguration::class,
    FileExamplesConfiguration::class,
    MutableClockConfiguration::class,
)
@TestPropertySource(properties = [FILES_TEST_URL_TTL])
@Suppress("LongParameterList") // os apoios de teste e os beans conferidos direto (S3, cifragem, tokens, relógio)
class FilesApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val files: FilesTestSupport,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val s3: S3Client,
    @Autowired private val fieldCipher: FieldCipher,
    @Autowired private val downloadTokens: DownloadTokens,
    @Autowired private val clock: MutableClock,
) {
    private val ttl = Duration.ofSeconds(FILES_TEST_URL_TTL_SECONDS)

    private val photo = FileExamplePurposes.PHOTO.value
    private val document = FileExamplePurposes.DOCUMENT.value
    private val photoUpload = TestGrant(definition(FileExamplePermissions.PHOTOS_UPLOAD), ALL)
    private val photoRead = TestGrant(definition(FileExamplePermissions.PHOTOS_READ), ALL)
    private val documentUpload = TestGrant(definition(FileExamplePermissions.DOCUMENTS_UPLOAD), ALL)
    private val documentRead = TestGrant(definition(FileExamplePermissions.DOCUMENTS_READ), ALL)

    /** O banco é compartilhado: outro contexto que subiu depois marcou as permissões de exemplo obsoletas. */
    @BeforeEach
    fun synchronizeThisContextCatalog() {
        synchronizer.synchronize()
    }

    // --- Upload e URL direta ------------------------------------------------------------------------

    @Test
    fun `a valid upload stores the object and its metadata, and the signed url downloads it`(): Unit =
        with(iam) {
            val user = createUserWith(photoUpload, photoRead)
            val content = png(seed = 1)

            val response = files.upload(photo, asTestUser(user), content, name = "../fotos/quarto 12.PNG")

            response.status shouldBe 201
            val body = files.json(response)
            val id = UUID.fromString(body["id"].asString())
            response.getHeader(HttpHeaders.LOCATION) shouldBe "/api/v1/files/$id"
            body["purpose"].asString() shouldBe photo
            body["contentType"].asString() shouldBe "image/png"
            body["sizeBytes"].asLong() shouldBe content.size.toLong()
            body["sensitive"].asBoolean() shouldBe false
            val row = files.storedRow(id)
            row["object_key"] shouldBe "fileexamples/photo/$id"
            row["owner_module"] shouldBe "fileexamples"
            row["created_by"] shouldBe user.id.value
            row["encryption_key_id"].shouldBeNull()
            // O nome original (pode ter dado pessoal) fica cifrado, sem o caminho.
            (row["original_name"] as String) shouldStartWith "v1:${TestEncryptionKeys.ACTIVE_KEY_ID}:"
            files.rawObject("fileexamples/photo/$id") shouldBe content

            val (url, expiresAt) = files.downloadUrlOk(id, asTestUser(user))

            url.toString() shouldStartWith "${MinioContainer.endpoint}/${MinioContainer.BUCKET}/fileexamples/photo/$id?"
            url.query.split('&').single { it.startsWith("X-Amz-Expires=") } shouldBe
                "X-Amz-Expires=$FILES_TEST_URL_TTL_SECONDS"
            expiresAt shouldBe clock.instant().plus(ttl)
            val download = files.httpGet(url)
            download.statusCode() shouldBe 200
            download.body() shouldBe content
            download.headers().firstValue("Content-Type").get() shouldBe "image/png"
            download.headers().firstValue("Content-Disposition").get() shouldContain "quarto"
            // A URL é do anexo: sem a assinatura, o bucket não entrega nada.
            files.httpGet(withoutQuery(url)).statusCode() shouldBe 403
            // Anexo comum: a geração da URL não vai para a trilha; o upload, sim.
            files.auditOf(id).map { it.action } shouldContainExactly listOf("files.file.upload")
        }

    @Test
    fun `the signed url is valid for the configured ttl from its signing time, and the bucket refuses it after that`():
        Unit =
        with(iam) {
            val user = createUserWith(photoUpload, photoRead)
            val id = files.uploadOk(photo, asTestUser(user), png(seed = 2))
            val key = "fileexamples/photo/$id"
            val before = Instant.now().truncatedTo(ChronoUnit.SECONDS)

            val (url, _) = files.downloadUrlOk(id, asTestUser(user))

            // A janela é a do S3: do instante da assinatura (X-Amz-Date) até X-Amz-Expires depois.
            val signedAt = AMZ_DATE.parse(queryParam(url, "X-Amz-Date"), Instant::from)
            signedAt.isBefore(before) shouldBe false
            signedAt.isAfter(Instant.now()) shouldBe false
            queryParam(url, "X-Amz-Expires") shouldBe "$FILES_TEST_URL_TTL_SECONDS"
            files.httpGet(url).statusCode() shouldBe 200
            // Os dois entram na assinatura: recuar a data ou alongar a validade invalida a URL.
            val backdated = AMZ_DATE.format(signedAt.minus(Duration.ofHours(1)))
            files.httpGet(withQueryParam(url, "X-Amz-Date", backdated)).statusCode() shouldBe 403
            files.httpGet(withQueryParam(url, "X-Amz-Expires", "604800")).statusCode() shouldBe 403
            // O bucket recusa, como vencida, uma URL legítima assinada há mais que a validade (sem esperar por ela)...
            val expired = files.httpGet(files.presignedAt(key, Instant.now().minus(ttl).minusSeconds(5), ttl))
            expired.statusCode() shouldBe 403
            String(expired.body()) shouldContain "expired"
            // ...e aceita a mesma assinatura feita agora (a URL de controle prova que a vencida só falha pelo prazo).
            files.httpGet(files.presignedAt(key, Instant.now(), ttl)).statusCode() shouldBe 200
        }

    @Test
    fun `size and type outside the purpose are rejected with stable codes, and nothing is stored`(): Unit =
        with(iam) {
            val user = createUserWith(photoUpload, documentUpload)
            val before = files.countRows(photo)
            val keysBefore = files.objectKeys("fileexamples/photo/")

            fun rejected(
                content: ByteArray,
                code: String,
                purpose: String = photo,
                name: String? = "arquivo.png",
                contentType: String = "image/png",
            ) {
                val problem = files.upload(purpose, asTestUser(user), content, name, contentType).problem(400, code)
                problem["errors"][0]["code"].asString() shouldBe code
            }

            assertSoftly {
                // Acima do tamanho da finalidade (1 KB).
                rejected(png(size = FileExamplePurposes.PHOTO_MAX_SIZE.toInt() + 1), "FILE_TOO_LARGE")
                // Tipo pelo conteúdo: um PDF chamado .png e declarado image/png continua PDF, que a foto não aceita.
                rejected(pdf("não é imagem"), "FILE_TYPE_NOT_ACCEPTED")
                // Texto qualquer com nome e Content-Type de imagem: a extensão não basta.
                rejected("<svg onload=alert(1)>".toByteArray(), "FILE_TYPE_NOT_ACCEPTED")
                // PNG de verdade com extensão de outro tipo.
                rejected(png(), "FILE_EXTENSION_MISMATCH", name = "foto.jpg", contentType = "image/jpeg")
                rejected(ByteArray(0), "FILE_EMPTY")
                files
                    .upload("fileexamples.unknown", asTestUser(user), png())
                    .problem(400, "UNKNOWN_FILE_PURPOSE")
                    .let { it["errors"][0]["field"].asString() shouldBe "purpose" }
                // O documento aceita PDF, mas não um texto que só se diz PDF.
                rejected("documento".toByteArray(), "FILE_TYPE_NOT_ACCEPTED", document, "doc.pdf", "application/pdf")
            }
            // Sem a finalidade ou sem o arquivo: 400 com o campo que falta.
            mockMvc
                .multipart("/api/v1/files") {
                    file(MockMultipartFile("file", "a.png", "image/png", png()))
                    with(asTestUser(user))
                }.andReturn()
                .response
                .problem(400, "VALIDATION_FAILED")["errors"][0]["field"]
                .asString() shouldBe "purpose"
            mockMvc
                .multipart("/api/v1/files") {
                    part(MockPart("purpose", photo.toByteArray()))
                    with(asTestUser(user))
                }.andReturn()
                .response
                .problem(400, "VALIDATION_FAILED")["errors"][0]["field"]
                .asString() shouldBe "file"
            // Um JPEG de verdade com nome sem extensão passa.
            files.uploadOk(photo, asTestUser(user), jpeg(), name = "foto")
            files.countRows(photo) shouldBe before + 1
            (files.objectKeys("fileexamples/photo/") - keysBefore.toSet()) shouldHaveSize 1
        }

    @Test
    fun `without the permission of the purpose the upload and the download are refused with 403`(): Unit =
        with(iam) {
            val nobody = createUser()
            val uploader = createUserWith(photoUpload)
            val ownReader = createUserWith(photoUpload, TestGrant(definition(FileExamplePermissions.PHOTOS_READ), OWN))
            val reader = createUserWith(photoRead)

            files.upload(photo, asTestUser(nobody), png()).problem(403, "PERMISSION_DENIED")
            // A permissão de outra finalidade não serve.
            files.upload(document, asTestUser(uploader), pdf("x")).problem(403, "PERMISSION_DENIED")
            val id = files.uploadOk(photo, asTestUser(uploader), png(seed = 3))
            val ownId = files.uploadOk(photo, asTestUser(ownReader), png(seed = 4))

            files.downloadUrl(id, asTestUser(nobody)).problem(403, "PERMISSION_DENIED")
            files.downloadUrl(id, asTestUser(uploader)).problem(403, "PERMISSION_DENIED")
            // Escopo OWN: só o que o próprio usuário enviou.
            files.downloadUrl(id, asTestUser(ownReader)).problem(403, "RESOURCE_OUT_OF_SCOPE")
            files.downloadUrlOk(ownId, asTestUser(ownReader))
            files.downloadUrlOk(id, asTestUser(reader))
            call(GET, "/api/v1/files/$id", asTestUser(nobody))
                .problem(403, "PERMISSION_DENIED")
            call(GET, "/api/v1/files/$id", asTestUser(reader))
                .ok()["id"]
                .asString() shouldBe id.toString()
            files.downloadUrl(UUID.randomUUID(), asTestUser(reader)).problem(404, "FILE_NOT_FOUND")
            // Sem token.
            mockMvc
                .get("/api/v1/files/$id/download-url")
                .andReturn()
                .response.status shouldBe 401
        }

    // --- Anexo sensível -----------------------------------------------------------------------------

    @Test
    fun `a sensitive file is encrypted in the bucket and served decrypted by the server, each access audited`(): Unit =
        with(iam) {
            val user = createUserWith(documentUpload, documentRead)
            val session = UUID.randomUUID()
            val auth = asUser(user.id.value, session)
            val secret = "RG 12.345.678-9 Maria da Silva CPF 123.456.789-09"
            val content = pdf(secret)

            val id = files.uploadOk(document, auth, content, name = "rg-maria.pdf")

            // No bucket, nem o conteúdo nem pedaços dele: só o formato cifrado com o id da chave.
            val raw = files.rawObject("fileexamples/identity-document/$id")
            raw shouldNotBe content
            String(raw.copyOfRange(0, 4), Charsets.US_ASCII) shouldBe "ZNC1"
            String(raw, Charsets.ISO_8859_1) shouldNotContain "Maria"
            String(raw, Charsets.ISO_8859_1) shouldNotContain "%PDF"
            raw.size shouldBe content.size + 4 + 1 + TestEncryptionKeys.ACTIVE_KEY_ID.length + 12 + 16
            val row = files.storedRow(id)
            row["sensitive"] shouldBe true
            row["encryption_key_id"] shouldBe TestEncryptionKeys.ACTIVE_KEY_ID
            row["size_bytes"] shouldBe content.size.toLong()

            val (url, expiresAt) = files.downloadUrlOk(id, auth)

            // URL do servidor, não do bucket (que entregaria o conteúdo cifrado).
            url.path shouldBe "/api/v1/files/$id/content"
            url.query shouldStartWith "token="
            url.toString() shouldNotContain "X-Amz"
            url.toString() shouldNotContain MinioContainer.BUCKET
            expiresAt shouldBe clock.instant().plus(ttl)

            // Sem access token: quem autoriza é o token da URL.
            val first = files.serverGet(url)
            first.status shouldBe 200
            first.contentAsByteArray shouldBe content
            first.contentType shouldBe "application/pdf"
            first.getHeader(HttpHeaders.CONTENT_DISPOSITION).shouldNotBeNull() shouldContain "rg-maria.pdf"
            first.getHeader(HttpHeaders.CACHE_CONTROL) shouldContain "no-store"
            files.serverGet(url).status shouldBe 200

            val entries = files.auditOf(id)
            entries.map { it.action } shouldContainExactly
                listOf(
                    "files.file.upload",
                    "files.file.issue-download-url",
                    "files.file.download",
                    "files.file.download",
                )
            entries.forEach { entry ->
                withClue(entry) {
                    entry.outcome shouldBe "SUCCEEDED"
                    entry.userId shouldBe user.id.value
                    entry.sessionId shouldBe session
                    // A trilha não leva o conteúdo, o nome original nem o token.
                    entry.afterState.orEmpty() shouldNotContain "Maria"
                    entry.afterState.orEmpty() shouldNotContain "rg-maria"
                    entry.afterState.orEmpty() shouldNotContain url.query.removePrefix("token=")
                }
            }
            entries[2].afterState.shouldNotBeNull() shouldContain "\"access\": \"download\""
        }

    @Test
    fun `a tampered or foreign server url is refused, and an expired one is refused and audited`(): Unit =
        with(iam) {
            // O relógio recua a validade antes e volta no fim: o vencimento acontece sem nada ficar à frente da hora
            // real (o contexto e o banco são compartilhados).
            val start = clock.instant()
            clock.advance(ttl.negated())
            try {
                expiredServerUrlIsRefusedAndAudited()
            } finally {
                clock.advance(Duration.between(clock.instant(), start))
            }
        }

    private fun IamTestSupport.expiredServerUrlIsRefusedAndAudited() {
        val user = createUserWith(documentUpload, documentRead)
        val id = files.uploadOk(document, asTestUser(user), pdf("documento A"), name = "a.pdf")
        val otherId = files.uploadOk(document, asTestUser(user), pdf("documento B"), name = "b.pdf")
        val (url, expiresAt) = files.downloadUrlOk(id, asTestUser(user))
        val token = URLDecoder.decode(url.rawQuery.removePrefix("token="), Charsets.UTF_8)

        fun content(
            fileId: UUID,
            token: String,
        ) = mockMvc.get("/api/v1/files/{id}/content", fileId) { param("token", token) }.andReturn().response

        // O próprio token, mandado como parâmetro, funciona.
        content(id, token).status shouldBe 200
        // Token de outro anexo, adulterado ou ilegível: 403, sem entregar nada nem gravar download.
        content(otherId, token).problem(403, "DOWNLOAD_URL_INVALID")
        val tampered = token.dropLast(2) + if (token.endsWith("AA")) "BB" else "AA"
        content(id, tampered).problem(403, "DOWNLOAD_URL_INVALID")
        content(id, "v1:test:x").problem(403, "DOWNLOAD_URL_INVALID")
        content(id, "").problem(403, "DOWNLOAD_URL_INVALID")
        files.auditOf(otherId).map { it.action } shouldContainExactly listOf("files.file.upload")
        // Um instante antes do vencimento, ainda vale.
        clock.advance(Duration.between(clock.instant(), expiresAt).minusMillis(1))
        files.serverGet(url).status shouldBe 200

        // No vencimento (o relógio da aplicação, sem esperar), não vale mais.
        clock.advance(Duration.ofMillis(1))

        files.serverGet(url).problem(403, "DOWNLOAD_URL_EXPIRED")
        val rejected = files.auditOf(id).last()
        rejected.action shouldBe "files.file.download"
        rejected.outcome shouldBe "REJECTED"
        rejected.errorCode shouldBe "DOWNLOAD_URL_EXPIRED"
        rejected.userId shouldBe user.id.value
        files.auditOf(id).count { it.action == "files.file.download" && it.outcome == "SUCCEEDED" } shouldBe 2
    }

    @Test
    fun `a field-cipher value with the token format or a token valid beyond the configured ttl is refused`(): Unit =
        with(iam) {
            val user = createUserWith(documentUpload, documentRead)
            val id = files.uploadOk(document, asTestUser(user), pdf("documento C"), name = "c.pdf")

            fun content(token: String) =
                mockMvc.get("/api/v1/files/{id}/content", id) { param("token", token) }.andReturn().response

            // Um valor que o FieldCipher cifraria numa coluna, com o formato do payload (e validade dentro
            // do teto, para só a separação de domínio o barrar): não vale como token.
            val soon = clock.instant().plusSeconds(2).toEpochMilli()
            val forged = fieldCipher.encrypt("files.download.v1|$id|$soon|${user.id.value}|")
            content(forged).problem(403, "DOWNLOAD_URL_INVALID")
            content("t1" + forged.removePrefix("v1")).problem(403, "DOWNLOAD_URL_INVALID")
            val forever = clock.instant().plus(Duration.ofDays(3650)).toEpochMilli()
            content(fieldCipher.encrypt("files.download.v1|$id|$forever|${user.id.value}|"))
                .problem(403, "DOWNLOAD_URL_INVALID")
            // Um token legítimo, mas com validade acima de agora + o TTL configurado: recusado.
            val longLived =
                downloadTokens.issue(
                    DownloadGrant(FileId(id), clock.instant().plus(Duration.ofHours(1)), user.id, null),
                )
            content(longLived).problem(403, "DOWNLOAD_URL_INVALID")
            // Dentro do teto, o mesmo emissor vale.
            val withinTtl =
                downloadTokens.issue(DownloadGrant(FileId(id), clock.instant().plusSeconds(2), user.id, null))
            content(withinTtl).status shouldBe 200
            files.auditOf(id).count { it.action == "files.file.download" } shouldBe 1
        }

    // --- Armazenamento --------------------------------------------------------------------------------

    @Test
    fun `the application talks to the bucket with a service user restricted to it, not the root`() {
        MinioContainer.APP_ACCESS_KEY shouldNotBe MinioContainer.instance.userName
        // O usuário de serviço não cria bucket nem muda a política (por exemplo, para torná-lo público).
        shouldThrow<S3Exception> {
            s3.createBucket(CreateBucketRequest.builder().bucket("zeny-not-allowed").build())
        }.statusCode() shouldBe 403
        val publicRead =
            """{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},""" +
                """"Action":["s3:GetObject"],"Resource":["arn:aws:s3:::${MinioContainer.BUCKET}/*"]}]}"""
        shouldThrow<S3Exception> {
            s3.putBucketPolicy(
                PutBucketPolicyRequest
                    .builder()
                    .bucket(MinioContainer.BUCKET)
                    .policy(publicRead)
                    .build(),
            )
        }.statusCode() shouldBe 403
        // Só enxerga o próprio bucket.
        s3
            .listBuckets()
            .buckets()
            .map { it.name() }
            .filter { it != MinioContainer.BUCKET }
            .shouldBeEmpty()
        // O bucket não é público: sem assinatura, nada sai.
        files.httpGet(URI.create("${MinioContainer.endpoint}/${MinioContainer.BUCKET}/")).statusCode() shouldBe 403
        files.objectKeys("nothing-here/").shouldBeEmpty()
    }

    private companion object {
        /** O formato do `X-Amz-Date` (SigV4), em UTC. */
        val AMZ_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        fun withoutQuery(url: URI): URI = URI(url.scheme, url.authority, url.path, null, null)

        fun queryParam(
            url: URI,
            name: String,
        ): String =
            url.rawQuery
                .split('&')
                .single { it.startsWith("$name=") }
                .substringAfter('=')

        fun withQueryParam(
            url: URI,
            name: String,
            value: String,
        ): URI {
            val query =
                url.rawQuery
                    .split(
                        '&',
                    ).joinToString("&") { if (it.startsWith("$name=")) "$name=$value" else it }
            return URI.create("${withoutQuery(url)}?$query")
        }
    }
}
