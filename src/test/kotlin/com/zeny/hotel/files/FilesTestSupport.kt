package com.zeny.hotel.files

import com.zeny.hotel.files.infrastructure.StorageProperties
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockMultipartFile
import org.springframework.mock.web.MockPart
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.request.RequestPostProcessor
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpRequest
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner
import software.amazon.awssdk.http.auth.spi.signer.SignRequest
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Validade das URLs de download nos testes de anexos, com folga para uma CI lenta: o vencimento é
 * conferido com o relógio controlado ([com.zeny.hotel.testsupport.MutableClock]) e, no bucket, com
 * uma URL assinada no passado ([FilesTestSupport.presignedAt]), sem esperar. As classes que a usam
 * (com o mesmo `@Import`, inclusive o `MutableClockConfiguration`) compartilham um contexto.
 */
const val FILES_TEST_URL_TTL = "zeny.files.download-url-ttl=PT2M"
const val FILES_TEST_URL_TTL_SECONDS = 120L

/** Uma entrada da trilha de auditoria de um anexo. */
data class FileAuditRow(
    val action: String,
    val outcome: String,
    val errorCode: String?,
    val userId: UUID?,
    val sessionId: UUID?,
    val entityId: String?,
    val afterState: String?,
)

/** Apoio dos testes de anexos: upload multipart, download pela URL e leitura direta do bucket e da trilha. */
class FilesTestSupport(
    private val mockMvc: MockMvc,
    private val jsonMapper: JsonMapper,
    private val jdbc: JdbcClient,
    private val s3: S3Client,
    private val storage: StorageProperties,
) {
    private val bucket = storage.bucket
    private val http: HttpClient = HttpClient.newHttpClient()

    @Suppress("LongParameterList") // as partes do multipart, com os padrões de um PNG sem clientFileId
    fun upload(
        purpose: String,
        auth: RequestPostProcessor,
        content: ByteArray,
        name: String? = "arquivo.png",
        contentType: String = "image/png",
        clientFileId: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .multipart("/api/v1/files") {
                file(MockMultipartFile("file", name, contentType, content))
                part(MockPart("purpose", purpose.toByteArray(Charsets.UTF_8)))
                clientFileId?.let { part(MockPart("clientFileId", it.toByteArray(Charsets.UTF_8))) }
                with(auth)
            }.andReturn()
            .response

    /** Envia e confere o `201`; devolve o id do anexo. */
    fun uploadOk(
        purpose: String,
        auth: RequestPostProcessor,
        content: ByteArray,
        name: String? = "arquivo.png",
    ): UUID {
        val response = upload(purpose, auth, content, name)
        withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 201 }
        return UUID.fromString(json(response)["id"].asString())
    }

    fun downloadUrl(
        id: UUID,
        auth: RequestPostProcessor,
    ): MockHttpServletResponse = mockMvc.get("/api/v1/files/$id/download-url") { with(auth) }.andReturn().response

    /** Gera a URL e confere o `200`. */
    fun downloadUrlOk(
        id: UUID,
        auth: RequestPostProcessor,
    ): Pair<URI, Instant> {
        val response = downloadUrl(id, auth)
        withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 200 }
        val body = json(response)
        return URI.create(body["url"].asString()) to Instant.parse(body["expiresAt"].asString())
    }

    /** Baixa uma URL direta ao armazenamento, sem credencial nenhuma, como o cliente faria. */
    fun httpGet(url: URI): HttpResponse<ByteArray> =
        http.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray())

    /**
     * Uma URL pré-assinada do objeto [key] com as credenciais da aplicação, como se assinada em
     * [signedAt] e válida por [ttl]: prova, sem esperar, que o bucket recusa a URL vencida.
     */
    fun presignedAt(
        key: String,
        signedAt: Instant,
        ttl: Duration,
    ): URI {
        val request =
            SdkHttpRequest
                .builder()
                .method(SdkHttpMethod.GET)
                .uri(URI.create("${storage.endpoint}/$bucket/$key"))
                .build()
        val signRequest =
            SignRequest
                .builder(AwsCredentialsIdentity.create(storage.accessKey, storage.secretKey))
                .request(request)
                .putProperty(AwsV4HttpSigner.REGION_NAME, storage.region)
                .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, "s3")
                .putProperty(AwsV4FamilyHttpSigner.AUTH_LOCATION, AwsV4FamilyHttpSigner.AuthLocation.QUERY_STRING)
                .putProperty(AwsV4FamilyHttpSigner.EXPIRATION_DURATION, ttl)
                .putProperty(AwsV4FamilyHttpSigner.PAYLOAD_SIGNING_ENABLED, false)
                .putProperty(AwsV4FamilyHttpSigner.DOUBLE_URL_ENCODE, false)
                .putProperty(AwsV4FamilyHttpSigner.NORMALIZE_PATH, false)
                .putProperty(HttpSigner.SIGNING_CLOCK, Clock.fixed(signedAt, ZoneOffset.UTC))
                .build()
        return AwsV4HttpSigner
            .create()
            .sign(signRequest)
            .request()
            .uri
    }

    /** Baixa uma URL do servidor (`/api/v1/files/{id}/content?token=…`) sem access token. */
    fun serverGet(url: URI): MockHttpServletResponse =
        mockMvc.get(URI.create(url.rawPath + "?" + url.rawQuery)).andReturn().response

    /** O objeto como está no bucket (lido com o usuário de serviço da aplicação). */
    fun rawObject(key: String): ByteArray =
        s3
            .getObject(
                GetObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key(key)
                    .build(),
                ResponseTransformer.toBytes(),
            ).asByteArray()

    fun objectKeys(prefix: String): List<String> =
        s3
            .listObjectsV2(
                ListObjectsV2Request
                    .builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .build(),
            ).contents()
            .map { it.key() }

    fun storedRow(id: UUID): Map<String, Any?> =
        jdbc
            .sql("SELECT * FROM files.stored_file WHERE id = :id")
            .param("id", id)
            .query()
            .singleRow()

    fun objectExists(key: String): Boolean = objectKeys(key).contains(key)

    fun countRows(purpose: String): Int =
        jdbc
            .sql("SELECT count(*) FROM files.stored_file WHERE purpose = :purpose")
            .param("purpose", purpose)
            .query(Int::class.java)
            .single()

    fun auditOf(id: UUID): List<FileAuditRow> =
        jdbc
            .sql(
                """
                SELECT action, outcome, error_code, user_id, device_session_id, entity_id, after_state::text AS after
                FROM audit.audit_entry WHERE entity_type = 'files.file' AND entity_id = :id
                ORDER BY occurred_at, id
                """.trimIndent(),
            ).param("id", id.toString())
            .query { rs, _ ->
                FileAuditRow(
                    action = rs.getString("action"),
                    outcome = rs.getString("outcome"),
                    errorCode = rs.getString("error_code"),
                    userId = rs.getObject("user_id", UUID::class.java),
                    sessionId = rs.getObject("device_session_id", UUID::class.java),
                    entityId = rs.getString("entity_id"),
                    afterState = rs.getString("after"),
                )
            }.list()

    fun json(response: MockHttpServletResponse): JsonNode =
        jsonMapper.readTree(response.getContentAsString(Charsets.UTF_8))

    companion object {
        /** Um PNG: a assinatura de 8 bytes e o resto de enchimento até [size]. */
        fun png(
            size: Int = 64,
            seed: Int = 0,
        ): ByteArray {
            val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            return signature + ByteArray(size - signature.size) { (it * 31 + seed).toByte() }
        }

        fun jpeg(size: Int = 64): ByteArray =
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
                ByteArray(size - 4) { it.toByte() }

        fun pdf(text: String): ByteArray = "%PDF-1.7\n% documento\n$text\n%%EOF\n".toByteArray(Charsets.UTF_8)
    }
}

@TestConfiguration(proxyBeanMethods = false)
class FilesTestConfiguration {
    @Bean
    fun filesTestSupport(
        mockMvc: MockMvc,
        jsonMapper: JsonMapper,
        jdbc: JdbcClient,
        s3: S3Client,
        storage: StorageProperties,
    ) = FilesTestSupport(mockMvc, jsonMapper, jdbc, s3, storage)
}
