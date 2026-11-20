package com.zeny.hotel.reporting.application

import com.zeny.hotel.reporting.ReportingPermissions
import com.zeny.hotel.shared.AppendOnlyTables
import com.zeny.hotel.shared.ModulePermissions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** As permissões do `reporting` no catálogo (sincronizado no boot) e as tabelas só de inserção. */
@Configuration(proxyBeanMethods = false)
class ReportingConfiguration {
    @Bean
    fun reportingPermissions(): ModulePermissions = ReportingPermissions

    /** As tabelas *append-only* do módulo, que a partida confere ([AppendOnlyTables]): o snapshot diário. */
    @Bean
    fun reportingAppendOnlyTables(): AppendOnlyTables = AppendOnlyTables { setOf("reporting.daily_statistics") }
}
