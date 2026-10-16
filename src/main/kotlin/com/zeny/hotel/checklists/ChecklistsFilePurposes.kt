package com.zeny.hotel.checklists

import com.zeny.hotel.files.FilePurpose
import com.zeny.hotel.files.FilePurposeCode
import com.zeny.hotel.files.FileType
import com.zeny.hotel.files.ImageReencoding
import com.zeny.hotel.files.ModuleFilePurposes

/**
 * Finalidades de anexo do módulo `checklists` (ARCHITECTURE §14, H10, H11 e H15): a foto de um item de checklist,
 * JPEG ou PNG de até 10 MB, **reencodada** no servidor (sem EXIF/GPS nem metadados, lado de até 2048 px), não
 * sensível. Envia quem executa checklists (`checklists.executions.execute`); baixa quem consulta as execuções
 * (`checklists.executions.read`: com `OWN`, só quem enviou, que é o executor). O app offline envia a foto com o
 * `clientFileId` antes do comando que a cita.
 */
object ChecklistsFilePurposes : ModuleFilePurposes {
    override val module = "checklists"

    val PHOTO = FilePurposeCode("checklists.photo")

    const val PHOTO_MAX_SIZE_BYTES: Long = 10L * 1024 * 1024

    override val purposes =
        listOf(
            FilePurpose(
                code = PHOTO,
                description = "Foto de item de checklist",
                maxSizeBytes = PHOTO_MAX_SIZE_BYTES,
                acceptedTypes = setOf(FileType.JPEG, FileType.PNG),
                sensitive = false,
                uploadPermission = ChecklistsPermissions.EXECUTIONS_EXECUTE,
                downloadPermission = ChecklistsPermissions.EXECUTIONS_READ,
                reencode = ImageReencoding(),
            ),
        )
}
