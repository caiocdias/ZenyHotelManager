package com.zeny.hotel.maintenance.application

import com.zeny.hotel.maintenance.MaintenancePermissions
import com.zeny.hotel.shared.ModulePermissions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** As permissões do `maintenance` no catálogo (sincronizado pelo `iam` no boot). */
@Configuration(proxyBeanMethods = false)
class MaintenanceConfiguration {
    @Bean
    fun maintenancePermissions(): ModulePermissions = MaintenancePermissions
}
