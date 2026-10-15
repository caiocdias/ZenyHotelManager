package filesexample

import com.zeny.hotel.files.FilePurpose
import com.zeny.hotel.files.FilePurposeCode
import com.zeny.hotel.files.FileType
import com.zeny.hotel.files.ModuleFilePurposes
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * Permissões de um módulo de exemplo, só de teste, dono de duas finalidades de anexo: o download
 * aceita `OWN` (só quem enviou) e `ALL`.
 */
object FileExamplePermissions : ModulePermissions {
    override val module = "fileexamples"

    val DOCUMENTS_UPLOAD = PermissionCode("fileexamples.documents.upload")
    val DOCUMENTS_READ = PermissionCode("fileexamples.documents.read")
    val PHOTOS_UPLOAD = PermissionCode("fileexamples.photos.upload")
    val PHOTOS_READ = PermissionCode("fileexamples.photos.read")

    override val permissions =
        listOf(
            PermissionDefinition(DOCUMENTS_UPLOAD, "Enviar documento de exemplo", setOf(ALL)),
            PermissionDefinition(DOCUMENTS_READ, "Baixar documento de exemplo", setOf(OWN, ALL)),
            PermissionDefinition(PHOTOS_UPLOAD, "Enviar foto de exemplo", setOf(ALL)),
            PermissionDefinition(PHOTOS_READ, "Baixar foto de exemplo", setOf(OWN, ALL)),
        )

    fun definition(code: PermissionCode) = permissions.single { it.code == code }
}

/**
 * Finalidades de anexo de exemplo, só de teste (como um módulo de negócio declara as suas): um
 * documento **sensível** (PDF, JPEG ou PNG de até 64 KB, como o documento de hóspede do M3) e uma
 * foto comum (PNG ou JPEG de até 1 KB, para o teste do tamanho).
 */
object FileExamplePurposes : ModuleFilePurposes {
    override val module = "fileexamples"

    val DOCUMENT = FilePurposeCode("fileexamples.identity-document")
    val PHOTO = FilePurposeCode("fileexamples.photo")

    const val DOCUMENT_MAX_SIZE: Long = 64 * 1024
    const val PHOTO_MAX_SIZE: Long = 1024

    override val purposes =
        listOf(
            FilePurpose(
                DOCUMENT,
                "Documento de exemplo (sensível)",
                DOCUMENT_MAX_SIZE,
                setOf(FileType.PDF, FileType.JPEG, FileType.PNG),
                sensitive = true,
                uploadPermission = FileExamplePermissions.DOCUMENTS_UPLOAD,
                downloadPermission = FileExamplePermissions.DOCUMENTS_READ,
            ),
            FilePurpose(
                PHOTO,
                "Foto de exemplo",
                PHOTO_MAX_SIZE,
                setOf(FileType.PNG, FileType.JPEG),
                sensitive = false,
                uploadPermission = FileExamplePermissions.PHOTOS_UPLOAD,
                downloadPermission = FileExamplePermissions.PHOTOS_READ,
            ),
        )
}

/** Liga o módulo de exemplo ao contexto: as permissões e as finalidades entram nos catálogos no boot. */
@TestConfiguration(proxyBeanMethods = false)
class FileExamplesConfiguration {
    @Bean
    fun fileExamplePermissions(): ModulePermissions = FileExamplePermissions

    @Bean
    fun fileExamplePurposes(): ModuleFilePurposes = FileExamplePurposes
}
