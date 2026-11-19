package com.zeny.hotel.maintenance.application

import com.zeny.hotel.files.ModuleFilePurposes
import com.zeny.hotel.maintenance.MaintenanceFilePurposes
import com.zeny.hotel.maintenance.MaintenancePermissions
import com.zeny.hotel.shared.ModulePermissions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** As permissões e as finalidades de anexo do `maintenance` nos catálogos (sincronizados no boot). */
@Configuration(proxyBeanMethods = false)
class MaintenanceConfiguration {
    @Bean
    fun maintenancePermissions(): ModulePermissions = MaintenancePermissions

    @Bean
    fun maintenanceFilePurposes(): ModuleFilePurposes = MaintenanceFilePurposes
}
