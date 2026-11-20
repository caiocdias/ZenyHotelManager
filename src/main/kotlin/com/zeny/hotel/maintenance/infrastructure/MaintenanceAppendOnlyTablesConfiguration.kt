package com.zeny.hotel.maintenance.infrastructure

import com.zeny.hotel.shared.AppendOnlyTables
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * As tabelas só de inserção do `maintenance` (ARCHITECTURE §11), que a partida confere (`DatabaseRoleGuard`): as
 * observações das ordens de serviço (D3-E08).
 */
@Configuration(proxyBeanMethods = false)
class MaintenanceAppendOnlyTablesConfiguration {
    @Bean
    fun maintenanceAppendOnlyTables(): AppendOnlyTables = AppendOnlyTables { setOf("maintenance.work_order_note") }
}
