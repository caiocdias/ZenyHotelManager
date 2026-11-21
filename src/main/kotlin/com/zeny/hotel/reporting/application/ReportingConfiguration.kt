package com.zeny.hotel.reporting.application

import com.zeny.hotel.files.ModuleFilePurposes
import com.zeny.hotel.reporting.ReportingFilePurposes
import com.zeny.hotel.reporting.ReportingPermissions
import com.zeny.hotel.shared.AppendOnlyTables
import com.zeny.hotel.shared.ModulePermissions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * As permissões e as finalidades de anexo do `reporting` nos catálogos (sincronizados no boot) e as tabelas só de
 * inserção.
 */
@Configuration(proxyBeanMethods = false)
class ReportingConfiguration {
    @Bean
    fun reportingPermissions(): ModulePermissions = ReportingPermissions

    /** A finalidade só do servidor do PDF da data ([ReportingFilePurposes.DAY_REPORT]). */
    @Bean
    fun reportingFilePurposes(): ModuleFilePurposes = ReportingFilePurposes

    /**
     * As tabelas *append-only* do módulo, que a partida confere ([AppendOnlyTables]): o snapshot diário e o índice dos
     * PDFs arquivados.
     */
    @Bean
    fun reportingAppendOnlyTables(): AppendOnlyTables =
        AppendOnlyTables { setOf("reporting.daily_statistics", "reporting.archived_report") }
}
