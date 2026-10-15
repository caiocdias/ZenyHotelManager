package com.zeny.hotel.files.infrastructure

import com.zeny.hotel.files.application.FileStorage
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.ContentDisposition
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.net.URI
import java.time.Duration

/**
 * Conexão com o armazenamento S3 (`zeny.files.storage.*`, D14), sem valor padrão no `prod`:
 *
 * - [endpoint]: onde o servidor fala com o S3 (no compose, `http://storage:9000`);
 * - [publicEndpoint]: o endereço que os clientes alcançam, usado nas URLs assinadas dos anexos
 *   comuns (no compose, o proxy, que repassa só o `GET` do bucket ao MinIO); vazio = [endpoint];
 * - [region], [bucket] e a credencial ([accessKey]/[secretKey]) de um **usuário de serviço** com
 *   política só do bucket, nunca a do root do MinIO;
 * - [pathStyle]: endereço `endpoint/bucket/chave` (MinIO); `false` para `bucket.endpoint/chave`.
 */
@ConfigurationProperties("zeny.files.storage")
@Suppress("LongParameterList") // os parâmetros da conexão S3
class StorageProperties(
    val endpoint: URI,
    publicEndpoint: String?,
    val region: String,
    val bucket: String,
    val accessKey: String,
    val secretKey: String,
    val pathStyle: Boolean = true,
) {
    val publicEndpoint: URI = publicEndpoint?.trim()?.takeIf { it.isNotEmpty() }?.let(URI::create) ?: endpoint

    init {
        listOf(
            "ZENY_STORAGE_ENDPOINT" to endpoint.toString(),
            "ZENY_STORAGE_REGION" to region,
            "ZENY_STORAGE_BUCKET" to bucket,
            "ZENY_STORAGE_ACCESS_KEY" to accessKey,
            "ZENY_STORAGE_SECRET_KEY" to secretKey,
        ).forEach { (variable, value) ->
            // Variável ausente chega aqui como o próprio texto `${...}` do application.yaml.
            require(value.isNotBlank() && !value.contains("\${")) { "Armazenamento de anexos sem $variable" }
        }
        require(endpoint.scheme in WEB_SCHEMES && this.publicEndpoint.scheme in WEB_SCHEMES) {
            "ZENY_STORAGE_ENDPOINT e ZENY_STORAGE_PUBLIC_ENDPOINT precisam ser URLs http(s)"
        }
    }

    /** Nunca mostra a credencial. */
    override fun toString(): String =
        "StorageProperties(endpoint=$endpoint, publicEndpoint=$publicEndpoint, region=$region, bucket=$bucket)"

    private companion object {
        val WEB_SCHEMES = setOf("http", "https")
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties::class)
class S3StorageConfiguration {
    @Bean(destroyMethod = "close")
    fun s3Client(properties: StorageProperties): S3Client =
        S3Client
            .builder()
            .endpointOverride(properties.endpoint)
            .region(Region.of(properties.region))
            .credentialsProvider(properties.credentials())
            .serviceConfiguration(properties.serviceConfiguration())
            .build()

    @Bean(destroyMethod = "close")
    fun s3Presigner(properties: StorageProperties): S3Presigner =
        S3Presigner
            .builder()
            .endpointOverride(properties.publicEndpoint)
            .region(Region.of(properties.region))
            .credentialsProvider(properties.credentials())
            .serviceConfiguration(properties.serviceConfiguration())
            .build()

    @Bean
    fun s3FileStorage(
        client: S3Client,
        presigner: S3Presigner,
        properties: StorageProperties,
    ): FileStorage = S3FileStorage(client, presigner, properties.bucket)

    private fun StorageProperties.credentials() =
        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))

    private fun StorageProperties.serviceConfiguration() =
        S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build()
}

/**
 * [FileStorage] sobre a API S3 (AWS SDK v2): os objetos ficam privados no bucket, e o download
 * direto é por URL pré-assinada (SigV4) com validade curta e os cabeçalhos da resposta (tipo e nome)
 * assinados junto.
 */
class S3FileStorage(
    private val client: S3Client,
    private val presigner: S3Presigner,
    private val bucket: String,
) : FileStorage {
    override fun put(
        key: String,
        content: ByteArray,
        contentType: String,
    ) {
        client.putObject(
            PutObjectRequest
                .builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(content.size.toLong())
                .build(),
            RequestBody.fromBytes(content),
        )
    }

    override fun get(key: String): ByteArray =
        client
            .getObjectAsBytes(
                GetObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key(key)
                    .build(),
            ).asByteArray()

    override fun delete(key: String) {
        client.deleteObject(
            DeleteObjectRequest
                .builder()
                .bucket(bucket)
                .key(key)
                .build(),
        )
    }

    override fun presignedDownload(
        key: String,
        ttl: Duration,
        contentType: String,
        downloadName: String,
    ): URI {
        val request =
            GetObjectRequest
                .builder()
                .bucket(bucket)
                .key(key)
                .responseContentType(contentType)
                .responseContentDisposition(
                    ContentDisposition
                        .attachment()
                        .filename(downloadName, Charsets.UTF_8)
                        .build()
                        .toString(),
                ).responseCacheControl("private, no-store")
                .build()
        val presigned =
            presigner.presignGetObject(
                GetObjectPresignRequest
                    .builder()
                    .signatureDuration(ttl)
                    .getObjectRequest(request)
                    .build(),
            )
        return presigned.url().toURI()
    }
}
