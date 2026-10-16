package com.zeny.hotel.notifications.application

import com.zeny.hotel.notifications.ModuleNotificationTypes
import com.zeny.hotel.notifications.NotificationType
import com.zeny.hotel.notifications.NotificationTypeCode
import com.zeny.hotel.notifications.NotificationsRealtime
import com.zeny.hotel.notifications.NotificationsTypes
import com.zeny.hotel.shared.AppendOnlyTables
import com.zeny.hotel.shared.RealtimeTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component

/** Os beans do módulo: o catálogo de tipos dele, o tópico pessoal e a tabela *append-only*. */
@Configuration(proxyBeanMethods = false)
class NotificationsConfiguration {
    @Bean
    fun notificationsTypes(): ModuleNotificationTypes = NotificationsTypes

    @Bean
    fun notificationsRealtimeTopic(): RealtimeTopic = NotificationsRealtime.TOPIC

    @Bean
    fun notificationsAppendOnlyTables(): AppendOnlyTables = AppendOnlyTables { setOf("notifications.push_delivery") }
}

/**
 * O catálogo de tipos de notificação (RF-NOT-01), juntado no boot dos [ModuleNotificationTypes] dos módulos, como
 * o de permissões e o de finalidades de anexo: recusa a partida com código fora do módulo, repetido ou módulo
 * declarado duas vezes.
 */
@Component
class NotificationTypeCatalog(
    modules: List<ModuleNotificationTypes>,
) {
    private val types: Map<NotificationTypeCode, NotificationType>

    init {
        val repeatedModules = modules.groupBy { it.module }.filterValues { it.size > 1 }.keys
        require(repeatedModules.isEmpty()) { "Tipos de notificação declarados mais de uma vez para $repeatedModules" }
        val all =
            modules.flatMap { module ->
                module.types.onEach { type ->
                    require(type.code.module == module.module) {
                        "O tipo de notificação ${type.code} não é do módulo ${module.module}"
                    }
                }
            }
        val repeated = all.groupBy { it.code }.filterValues { it.size > 1 }.keys
        require(repeated.isEmpty()) { "Tipo de notificação repetido: $repeated" }
        types = all.associateBy { it.code }
    }

    /** Todos os tipos, em ordem de código. */
    val all: List<NotificationType> get() = types.values.sortedBy { it.code.value }

    fun find(code: NotificationTypeCode): NotificationType? = types[code]

    /** O tipo do [code]; fora do catálogo é erro de programação de quem notifica. */
    fun require(code: NotificationTypeCode): NotificationType =
        requireNotNull(types[code]) { "Tipo de notificação fora do catálogo: $code" }
}
