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
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID

/**
 * Validade das URLs de download nos testes de anexos: curta, para o teste de expiração esperar pouco.
 * As classes que a usam (e o mesmo `@Import`) compartilham um contexto.
 */
const val FILES_TEST_URL_TTL = "zeny.files.download-url-ttl=PT4S"
const val FILES_TEST_URL_TTL_SECONDS = 4L

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
    private val bucket: String,
) {
    private val http: HttpClient = HttpClient.newHttpClient()

    fun upload(
        purpose: String,
        auth: RequestPostProcessor,
        content: ByteArray,
        name: String? = "arquivo.png",
        contentType: String = "image/png",
    ): MockHttpServletResponse =
        mockMvc
            .multipart("/api/v1/files") {
                file(MockMultipartFile("file", name, contentType, content))
                part(MockPart("purpose", purpose.toByteArray(Charsets.UTF_8)))
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
    ) = FilesTestSupport(mockMvc, jsonMapper, jdbc, s3, storage.bucket)
}
