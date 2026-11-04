package com.zeny.hotel.fnrh.application

import com.zeny.hotel.fnrh.FnrhPermissions
import com.zeny.hotel.shared.ModulePermissions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Publica as permissões do `fnrh` no catálogo do `iam` (ARCHITECTURE §8.2). */
@Configuration(proxyBeanMethods = false)
class FnrhPermissionsConfiguration {
    @Bean
    fun fnrhPermissions(): ModulePermissions = FnrhPermissions
}
