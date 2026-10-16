package com.zeny.hotel.files.application

import com.zeny.hotel.files.FilePurpose
import com.zeny.hotel.files.FilePurposeCode
import com.zeny.hotel.files.ModuleFilePurposes
import com.zeny.hotel.shared.ModulePermissions
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.servlet.autoconfigure.MultipartProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.util.unit.DataSize
import java.time.Duration

/**
 * Parâmetros dos anexos (`zeny.files.*`, ARCHITECTURE §14):
 *
 * - [downloadUrlTtl]: validade das URLs de download (padrão 5 min), de 1 s a 15 min: URL assinada
 *   de **curta** duração;
 * - [maxUploadSize]: maior tamanho aceito por qualquer finalidade (padrão 20 MB). Os limites do
 *   multipart do servidor (`spring.servlet.multipart.max-file-size` e `max-request-size`) são
 *   configurados à parte e não o acompanham sozinhos: o [FilePurposeCatalog] recusa o boot se
 *   algum deles ficar abaixo deste valor;
 * - [orphanRetention]: quanto tempo um anexo fica sem vínculo (desde a criação ou o último
 *   desvínculo) antes de o expurgo o apagar (padrão 24 h; ao menos 1 h, para o módulo dono ter
 *   tempo de vincular um anexo recém-enviado).
 */
@ConfigurationProperties("zeny.files")
class FilesProperties(
    val downloadUrlTtl: Duration,
    val maxUploadSize: DataSize,
    val orphanRetention: Duration = DEFAULT_ORPHAN_RETENTION,
) {
    init {
        require(downloadUrlTtl >= MIN_TTL && downloadUrlTtl <= MAX_TTL) {
            "zeny.files.download-url-ttl precisa ficar entre $MIN_TTL e $MAX_TTL (URL assinada de curta duração)"
        }
        require(maxUploadSize.toBytes() > 0) { "zeny.files.max-upload-size precisa ser positivo" }
        require(orphanRetention >= MIN_ORPHAN_RETENTION) {
            "zeny.files.orphan-retention precisa ser de ao menos $MIN_ORPHAN_RETENTION"
        }
    }

    companion object {
        val MIN_TTL: Duration = Duration.ofSeconds(1)
        val MAX_TTL: Duration = Duration.ofMinutes(15)
        val MIN_ORPHAN_RETENTION: Duration = Duration.ofHours(1)
        val DEFAULT_ORPHAN_RETENTION: Duration = Duration.ofHours(24)
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FilesProperties::class)
class FilesConfiguration

/**
 * O catálogo de finalidades (ARCHITECTURE §14): a união dos [ModuleFilePurposes] dos módulos,
 * conferida no boot. Recusa a partida com código fora do módulo declarado, finalidade ou módulo
 * repetidos, tamanho acima de `zeny.files.max-upload-size`, permissão que nenhum módulo declara
 * ou limite do multipart (`spring.servlet.multipart.*`) que barraria com `413` um anexo aceito:
 * `max-file-size` menor que `zeny.files.max-upload-size` e que a maior finalidade, ou
 * `max-request-size` que não os supera (o pedido leva também a parte `purpose` e os delimitadores).
 * Valor negativo no multipart é "sem limite" e passa.
 */
@Component
class FilePurposeCatalog(
    modules: List<ModuleFilePurposes>,
    permissions: List<ModulePermissions>,
    properties: FilesProperties,
    multipart: MultipartProperties,
) {
    private val purposes: Map<FilePurposeCode, FilePurpose>

    init {
        val repeatedModules = modules.groupBy { it.module }.filterValues { it.size > 1 }.keys
        require(repeatedModules.isEmpty()) { "Finalidades de anexo declaradas mais de uma vez para $repeatedModules" }
        val declared = permissions.flatMap { it.permissions }.map { it.code }.toSet()
        val all =
            modules.flatMap { module ->
                module.purposes.onEach { purpose ->
                    require(purpose.code.module == module.module) {
                        "A finalidade ${purpose.code} não é do módulo ${module.module}"
                    }
                }
            }
        val repeated = all.groupBy { it.code }.filterValues { it.size > 1 }.keys
        require(repeated.isEmpty()) { "Finalidade de anexo repetida: $repeated" }
        all.forEach { purpose ->
            require(purpose.maxSizeBytes <= properties.maxUploadSize.toBytes()) {
                "A finalidade ${purpose.code} aceita mais que zeny.files.max-upload-size (${properties.maxUploadSize})"
            }
            listOf(purpose.uploadPermission, purpose.downloadPermission).forEach { permission ->
                require(permission in declared) {
                    "A finalidade ${purpose.code} usa a permissão $permission, que não está no catálogo"
                }
            }
        }
        requireMultipartFits(multipart, properties.maxUploadSize, all.maxOfOrNull { it.maxSizeBytes } ?: 0)
        purposes = all.associateBy { it.code }
    }

    private fun requireMultipartFits(
        multipart: MultipartProperties,
        maxUploadSize: DataSize,
        largestPurposeBytes: Long,
    ) {
        val required = maxOf(maxUploadSize.toBytes(), largestPurposeBytes)
        val fileLimit = multipart.maxFileSize
        require(fileLimit.isNegative || fileLimit.toBytes() >= required) {
            "spring.servlet.multipart.max-file-size ($fileLimit) é menor que zeny.files.max-upload-size " +
                "($maxUploadSize) ou que a maior finalidade ($largestPurposeBytes bytes): um anexo aceito " +
                "receberia 413. Aumente o max-file-size para ao menos $required bytes."
        }
        val requestLimit = multipart.maxRequestSize
        require(requestLimit.isNegative || requestLimit.toBytes() > required) {
            "spring.servlet.multipart.max-request-size ($requestLimit) não supera zeny.files.max-upload-size " +
                "($maxUploadSize) e a maior finalidade ($largestPurposeBytes bytes): o pedido também leva a " +
                "parte purpose e os delimitadores, e um anexo aceito receberia 413. Use um valor maior que " +
                "$required bytes (ex.: 1 MB a mais)."
        }
    }

    fun find(code: FilePurposeCode): FilePurpose? = purposes[code]

    fun all(): Collection<FilePurpose> = purposes.values
}
