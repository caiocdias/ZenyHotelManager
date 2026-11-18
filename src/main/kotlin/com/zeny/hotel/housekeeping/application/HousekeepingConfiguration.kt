package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.housekeeping.HousekeepingPermissions
import com.zeny.hotel.shared.ModulePermissions
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** As permissões do `housekeeping` e a conferência no boot dos parâmetros ([HousekeepingProperties]). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HousekeepingProperties::class)
class HousekeepingConfiguration {
    /** As permissões do `housekeeping` no catálogo (sincronizado pelo `iam` no boot). */
    @Bean
    fun housekeepingPermissions(): ModulePermissions = HousekeepingPermissions
}
