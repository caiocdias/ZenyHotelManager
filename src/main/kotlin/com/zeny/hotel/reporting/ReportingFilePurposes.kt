package com.zeny.hotel.reporting

import com.zeny.hotel.files.FilePurpose
import com.zeny.hotel.files.FilePurposeCode
import com.zeny.hotel.files.FileType
import com.zeny.hotel.files.ModuleFilePurposes

/**
 * Finalidades de anexo do módulo `reporting` (M6 E23, D2 e D3).
 *
 * - [DAY_REPORT]: o PDF do fechamento de uma data, **sensível** (sem nome de hóspede, mas com códigos de reserva,
 *   saldos e ids de operador; passar de sensível a comum é configuração, o contrário não recifra) e **só do servidor**
 *   (`serverOnly`): quem grava é o arquivo por data ([ReportingApi.archiveDayReports]), nunca um upload, nem do
 *   Administrador. A permissão de envio é exigida pelo tipo e nunca usada. Baixa quem tem
 *   `reporting.archive.read`, pela URL do `files` (com a trilha de anexo sensível). O dono do anexo é o id da linha
 *   do índice `reporting.archived_report`.
 */
object ReportingFilePurposes : ModuleFilePurposes {
    override val module = "reporting"

    val DAY_REPORT = FilePurposeCode("reporting.day-report")

    const val MAX_SIZE_BYTES: Long = 5L * 1024 * 1024

    override val purposes =
        listOf(
            FilePurpose(
                code = DAY_REPORT,
                description = "PDF do fechamento de uma data operacional",
                maxSizeBytes = MAX_SIZE_BYTES,
                acceptedTypes = setOf(FileType.PDF),
                sensitive = true,
                uploadPermission = ReportingPermissions.ARCHIVE_READ,
                downloadPermission = ReportingPermissions.ARCHIVE_READ,
                serverOnly = true,
            ),
        )
}
