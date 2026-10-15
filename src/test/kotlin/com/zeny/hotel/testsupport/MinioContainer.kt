package com.zeny.hotel.testsupport

import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64

/**
 * Um único MinIO (D14) para todos os testes de integração da JVM, com a mesma imagem e tag do
 * `docker/compose.yaml` (a `minio/minio` deixou de ser publicada) e preparado pelo mesmo script do
 * `storage-init` (`docker/storage/init-storage.sh`): o bucket [BUCKET] e um usuário de serviço com
 * política só dele, com senha sorteada por JVM. Os testes conectam com esse usuário, como a `api`
 * do compose, nunca com o root. Sobe na primeira vez que um contexto o pede e é derrubado pelo
 * Ryuk quando a JVM termina.
 */
object MinioContainer {
    /** A mesma imagem do `storage` do compose. */
    const val IMAGE = "pgsty/minio:RELEASE.2026-08-04T00-00-00Z"
    const val BUCKET = "zeny-files-test"
    const val APP_ACCESS_KEY = "zeny-app"
    private const val ROOT_USER = "zeny-root"

    private val INIT_SCRIPT = Path.of("docker", "storage", "init-storage.sh")
    private const val EXECUTABLE = 0b111_101_101 // 0755
    private const val SECRET_BYTES = 18

    val rootPassword: String by lazy(::randomSecret)
    val appSecretKey: String by lazy(::randomSecret)

    val instance: MinIOContainer by lazy {
        MinIOContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("minio/minio"))
            .withUserName(ROOT_USER)
            .withPassword(rootPassword)
            .withCopyFileToContainer(MountableFile.forHostPath(INIT_SCRIPT, EXECUTABLE), "/tmp/init-storage.sh")
            .also { it.start() }
            .also(::prepare)
    }

    /** O endereço S3 do container, visto da máquina dos testes. */
    val endpoint: String get() = instance.s3URL

    private fun prepare(container: MinIOContainer) {
        val result =
            container.execInContainer(
                "env",
                "ZENY_STORAGE_INIT_ENDPOINT=http://127.0.0.1:9000",
                "MINIO_ROOT_USER=${container.userName}",
                "MINIO_ROOT_PASSWORD=${container.password}",
                "ZENY_STORAGE_BUCKET=$BUCKET",
                "ZENY_STORAGE_ACCESS_KEY=$APP_ACCESS_KEY",
                "ZENY_STORAGE_SECRET_KEY=$appSecretKey",
                "sh",
                "/tmp/init-storage.sh",
            )
        check(result.exitCode == 0) { "init-storage.sh falhou: ${result.stderr}" }
    }

    private fun randomSecret(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes))
}

/**
 * Aponta o armazenamento de anexos do contexto para o MinIO compartilhado, com o usuário de serviço
 * (nunca o root). As URLs assinadas usam o mesmo endereço, alcançável pelos testes.
 */
class TestStorageInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(context: ConfigurableApplicationContext) {
        TestPropertyValues
            .of(
                "zeny.files.storage.endpoint=${MinioContainer.endpoint}",
                "zeny.files.storage.public-endpoint=${MinioContainer.endpoint}",
                "zeny.files.storage.bucket=${MinioContainer.BUCKET}",
                "zeny.files.storage.access-key=${MinioContainer.APP_ACCESS_KEY}",
                "zeny.files.storage.secret-key=${MinioContainer.appSecretKey}",
            ).applyTo(context)
    }
}
