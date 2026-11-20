package com.zeny.hotel.reporting.application

import com.zeny.hotel.reporting.ArchivedReport
import com.zeny.hotel.reporting.ReportingPermissions
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.StorableRange
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * A consulta do arquivo por data (`GET /api/v1/reports/archive`, M6 E23, D5): só ids, sem URL assinada nem conteúdo.
 * O download é do `files` (`GET /api/v1/files/{id}/download-url`), com a trilha dele. Exige `reporting.archive.read`.
 */
@Service
class ArchivedReportsService(
    private val archive: ArchivedReportStore,
    private val authorization: Authorization,
) {
    @Transactional(readOnly = true)
    fun list(date: LocalDate): List<ArchivedReport> {
        authorization.require(ReportingPermissions.ARCHIVE_READ)
        // Nenhuma data fora do intervalo guardável tem arquivo, e o banco recusaria o parâmetro.
        return if (StorableRange.contains(date)) archive.list(date) else emptyList()
    }
}
