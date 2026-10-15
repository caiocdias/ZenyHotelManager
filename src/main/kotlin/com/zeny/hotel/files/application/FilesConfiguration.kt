package com.zeny.hotel.files.application

import com.zeny.hotel.files.FilePurpose
import com.zeny.hotel.files.FilePurposeCode
import com.zeny.hotel.files.ModuleFilePurposes
import com.zeny.hotel.shared.ModulePermissions
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.util.unit.DataSize
import java.time.Duration

/**
 * Parâmetros dos anexos (`zeny.files.*`, ARCHITECTURE §14):
 *
 * - [downloadUrlTtl]: validade das URLs de download (padrão 5 min), de 1 s a 15 min: URL assinada
 *   de **curta** duração;
 * - [maxUploadSize]: maior tamanho aceito por qualquer finalidade (padrão 20 MB); o limite do
 *   multipart do servidor (`spring.servlet.multipart.*`) acompanha este valor.
 */
@ConfigurationProperties("zeny.files")
class FilesProperties(
    val downloadUrlTtl: Duration,
    val maxUploadSize: DataSize,
) {
    init {
        require(downloadUrlTtl >= MIN_TTL && downloadUrlTtl <= MAX_TTL) {
            "zeny.files.download-url-ttl precisa ficar entre $MIN_TTL e $MAX_TTL (URL assinada de curta duração)"
        }
        require(maxUploadSize.toBytes() > 0) { "zeny.files.max-upload-size precisa ser positivo" }
    }

    companion object {
        val MIN_TTL: Duration = Duration.ofSeconds(1)
        val MAX_TTL: Duration = Duration.ofMinutes(15)
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FilesProperties::class)
class FilesConfiguration

/**
 * O catálogo de finalidades (ARCHITECTURE §14): a união dos [ModuleFilePurposes] dos módulos,
 * conferida no boot. Recusa a partida com código fora do módulo declarado, finalidade ou módulo
 * repetidos, tamanho acima de `zeny.files.max-upload-size` ou permissão que nenhum módulo declara.
 */
@Component
class FilePurposeCatalog(
    modules: List<ModuleFilePurposes>,
    permissions: List<ModulePermissions>,
    properties: FilesProperties,
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
        purposes = all.associateBy { it.code }
    }

    fun find(code: FilePurposeCode): FilePurpose? = purposes[code]

    fun all(): Collection<FilePurpose> = purposes.values
}
