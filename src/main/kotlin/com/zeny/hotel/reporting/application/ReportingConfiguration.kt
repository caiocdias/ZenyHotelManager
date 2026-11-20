package com.zeny.hotel.reporting.application

import com.zeny.hotel.reporting.ReportingPermissions
import com.zeny.hotel.shared.ModulePermissions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** As permissões do `reporting` no catálogo (sincronizado no boot). */
@Configuration(proxyBeanMethods = false)
class ReportingConfiguration {
    @Bean
    fun reportingPermissions(): ModulePermissions = ReportingPermissions
}
