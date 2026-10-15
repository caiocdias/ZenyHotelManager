package com.zeny.hotel.hotel

import com.zeny.hotel.files.FilePurpose
import com.zeny.hotel.files.FilePurposeCode
import com.zeny.hotel.files.FileType
import com.zeny.hotel.files.ModuleFilePurposes

/**
 * Finalidades de anexo do módulo `hotel` (ARCHITECTURE §14): o logotipo do hotel (RF-CFG-01, H8),
 * uma imagem PNG, JPEG ou WebP de até 2 MB, não sensível. Envia quem edita a configuração
 * (`hotel.settings.manage`); baixa quem a consulta (`hotel.settings.read`).
 */
object HotelFilePurposes : ModuleFilePurposes {
    override val module = "hotel"

    val LOGO = FilePurposeCode("hotel.logo")

    const val LOGO_MAX_SIZE_BYTES: Long = 2L * 1024 * 1024

    override val purposes =
        listOf(
            FilePurpose(
                code = LOGO,
                description = "Logotipo do hotel",
                maxSizeBytes = LOGO_MAX_SIZE_BYTES,
                acceptedTypes = setOf(FileType.PNG, FileType.JPEG, FileType.WEBP),
                sensitive = false,
                uploadPermission = HotelPermissions.SETTINGS_MANAGE,
                downloadPermission = HotelPermissions.SETTINGS_READ,
            ),
        )
}
