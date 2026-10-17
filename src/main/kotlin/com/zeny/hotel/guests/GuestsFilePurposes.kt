package com.zeny.hotel.guests

import com.zeny.hotel.files.FilePurpose
import com.zeny.hotel.files.FilePurposeCode
import com.zeny.hotel.files.FileType
import com.zeny.hotel.files.ImageReencoding
import com.zeny.hotel.files.ModuleFilePurposes

/**
 * Finalidades de anexo do módulo `guests` (RF-HOS-08, RNF-09, ARCHITECTURE §8.3 e §14; decisões do usuário em
 * 2026-10-17): as primeiras **sensíveis** de produção. O `files` cifra o conteúdo antes de ir ao armazenamento,
 * serve-o só pelo servidor (`GET /api/v1/files/{id}/download-url` → URL de curta duração do próprio servidor) e
 * grava na trilha cada URL emitida e cada download. Envia quem tem `guests.attachments.manage`; baixa quem tem
 * `guests.attachments.read`, as duas só com escopo `ALL` (o `OWN` do `files` seria "quem enviou"). A regra de
 * acesso é só a permissão da finalidade (H11: sem a porta `FileAccessPolicy`).
 *
 * - [ID_DOCUMENT_PHOTO]: foto de documento de identidade, JPEG ou PNG, **reencodada** (sem EXIF/GPS, lado de até
 *   2048 px).
 * - [MINOR_AUTHORIZATION_PHOTO]: foto da autorização de viagem ou hospedagem de menor (RN-24), idem.
 * - [DOCUMENT_PDF]: documento ou autorização digitalizados em PDF, sem reencode.
 *
 * O vínculo com o perfil é por `POST /api/v1/guests/{id}/attachments` (o `files` guarda o id do perfil como dono);
 * retirar o anexo, ou anonimizar o perfil, o apaga.
 */
object GuestsFilePurposes : ModuleFilePurposes {
    override val module = "guests"

    val ID_DOCUMENT_PHOTO = FilePurposeCode("guests.id-document-photo")
    val MINOR_AUTHORIZATION_PHOTO = FilePurposeCode("guests.minor-authorization-photo")
    val DOCUMENT_PDF = FilePurposeCode("guests.document-pdf")

    const val MAX_SIZE_BYTES: Long = 10L * 1024 * 1024

    private fun photo(
        code: FilePurposeCode,
        description: String,
    ) = FilePurpose(
        code = code,
        description = description,
        maxSizeBytes = MAX_SIZE_BYTES,
        acceptedTypes = setOf(FileType.JPEG, FileType.PNG),
        sensitive = true,
        uploadPermission = GuestsPermissions.ATTACHMENTS_MANAGE,
        downloadPermission = GuestsPermissions.ATTACHMENTS_READ,
        reencode = ImageReencoding(),
    )

    override val purposes =
        listOf(
            photo(ID_DOCUMENT_PHOTO, "Foto de documento de identidade de hóspede"),
            photo(MINOR_AUTHORIZATION_PHOTO, "Foto de autorização de hospedagem de menor"),
            FilePurpose(
                code = DOCUMENT_PDF,
                description = "Documento ou autorização de hóspede digitalizados em PDF",
                maxSizeBytes = MAX_SIZE_BYTES,
                acceptedTypes = setOf(FileType.PDF),
                sensitive = true,
                uploadPermission = GuestsPermissions.ATTACHMENTS_MANAGE,
                downloadPermission = GuestsPermissions.ATTACHMENTS_READ,
            ),
        )

    /** As finalidades que um perfil aceita como anexo. */
    val codes: Set<FilePurposeCode> = purposes.map { it.code }.toSet()
}
