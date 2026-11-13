package com.zeny.hotel.endofday.application

import com.zeny.hotel.endofday.EndOfDayPermissions
import com.zeny.hotel.endofday.EndOfDayRealtime
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.RealtimeTopic
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Publica as permissões do `endofday` no catálogo do `iam` (ARCHITECTURE §8.2) e o tópico do tempo real, e confere no
 * boot a configuração do gatilho automático ([EndOfDayTriggerProperties]).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EndOfDayTriggerProperties::class)
class EndOfDayConfiguration {
    @Bean
    fun endOfDayPermissions(): ModulePermissions = EndOfDayPermissions

    @Bean
    fun endOfDayRealtimeTopic(): RealtimeTopic = EndOfDayRealtime.TOPIC
}
