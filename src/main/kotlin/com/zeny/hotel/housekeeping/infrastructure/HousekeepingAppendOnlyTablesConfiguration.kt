package com.zeny.hotel.housekeeping.infrastructure

import com.zeny.hotel.shared.AppendOnlyTables
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * As tabelas só de inserção do `housekeeping` (ARCHITECTURE §11), que a partida confere (`DatabaseRoleGuard`): o
 * histórico de posse das tarefas (D3-E19) e o registro dos conflitos offline (D4-E19).
 */
@Configuration(proxyBeanMethods = false)
class HousekeepingAppendOnlyTablesConfiguration {
    @Bean
    fun housekeepingAppendOnlyTables(): AppendOnlyTables = AppendOnlyTables { setOf(TASK_ASSIGNMENT_TABLE, TASK_CONFLICT_TABLE) }
}
