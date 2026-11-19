package com.zeny.hotel.maintenance

import com.zeny.hotel.files.FilePurpose
import com.zeny.hotel.files.FilePurposeCode
import com.zeny.hotel.files.FileType
import com.zeny.hotel.files.ImageReencoding
import com.zeny.hotel.files.ModuleFilePurposes

/**
 * Finalidades de anexo do módulo `maintenance` (RF-MAN-01, D5-E07), como o precedente do `guests`, mas **comuns** (não
 * sensíveis: foto de um defeito e PDF de orçamento ou nota, sem dado de hóspede). Envia quem tem
 * `maintenance.workorders.request`; baixa quem tem `maintenance.workorders.read`.
 *
 * - [WORK_ORDER_PHOTO]: foto do problema, JPEG ou PNG, **reencodada** (sem EXIF/GPS, lado de até 2048 px).
 * - [WORK_ORDER_DOCUMENT]: orçamento, nota ou laudo em PDF, sem reencode.
 *
 * O vínculo com a ordem é por `POST /api/v1/work-orders/{id}/attachments` (o `files` guarda o id da ordem como dono).
 *
 * Limitação: no `files`, o escopo `OWN` da permissão de download é "quem enviou o anexo", não "dono da ordem". Uma
 * role criada pelo administrador com `maintenance.workorders.read` em `OWN` só baixa os anexos que ela mesma enviou,
 * mesmo nas ordens que acompanha; os modelos de role (`ALL`) não são afetados.
 */
object MaintenanceFilePurposes : ModuleFilePurposes {
    override val module = "maintenance"

    val WORK_ORDER_PHOTO = FilePurposeCode("maintenance.work-order-photo")
    val WORK_ORDER_DOCUMENT = FilePurposeCode("maintenance.work-order-document")

    const val MAX_SIZE_BYTES: Long = 10L * 1024 * 1024

    override val purposes =
        listOf(
            FilePurpose(
                code = WORK_ORDER_PHOTO,
                description = "Foto de uma ordem de serviço de manutenção",
                maxSizeBytes = MAX_SIZE_BYTES,
                acceptedTypes = setOf(FileType.JPEG, FileType.PNG),
                sensitive = false,
                uploadPermission = MaintenancePermissions.REQUEST,
                downloadPermission = MaintenancePermissions.READ,
                reencode = ImageReencoding(),
            ),
            FilePurpose(
                code = WORK_ORDER_DOCUMENT,
                description = "Documento (PDF) de uma ordem de serviço de manutenção",
                maxSizeBytes = MAX_SIZE_BYTES,
                acceptedTypes = setOf(FileType.PDF),
                sensitive = false,
                uploadPermission = MaintenancePermissions.REQUEST,
                downloadPermission = MaintenancePermissions.READ,
            ),
        )

    /** As finalidades que uma ordem aceita como anexo. */
    val codes: Set<FilePurposeCode> = purposes.map { it.code }.toSet()
}
