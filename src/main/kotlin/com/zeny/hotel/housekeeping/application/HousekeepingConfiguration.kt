package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.housekeeping.HousekeepingNotificationTypes
import com.zeny.hotel.housekeeping.HousekeepingPermissions
import com.zeny.hotel.housekeeping.HousekeepingRealtime
import com.zeny.hotel.notifications.ModuleNotificationTypes
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.RealtimeTopic
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * As permissões do `housekeeping`, o tópico de tempo real, os tipos de notificação e a conferência no boot dos
 * parâmetros ([HousekeepingProperties]).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HousekeepingProperties::class)
class HousekeepingConfiguration {
    /** As permissões do `housekeeping` no catálogo (sincronizado pelo `iam` no boot). */
    @Bean
    fun housekeepingPermissions(): ModulePermissions = HousekeepingPermissions

    /** O tópico `housekeeping` do canal de tempo real (D9-E03). */
    @Bean
    fun housekeepingRealtimeTopic(): RealtimeTopic = HousekeepingRealtime.TOPIC

    /** Os tipos de notificação do módulo no catálogo do `notifications` (D5-E19). */
    @Bean
    fun housekeepingNotificationTypes(): ModuleNotificationTypes = HousekeepingNotificationTypes
}
