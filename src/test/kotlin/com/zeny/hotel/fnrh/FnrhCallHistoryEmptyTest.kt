package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.infrastructure.JdbcFnrhCallHistory
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.PostgresContainer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource

/**
 * A leitura do histórico de chamadas sem nenhum registro (instalação nova) devolve "nenhum", não erro. O registro é
 * *append-only* e compartilhado, então o teste esvazia a tabela só numa transação do dono do schema (com os gatilhos
 * desligados) e a desfaz no fim: nada fica gravado e o resultado não depende dos outros testes.
 */
@IntegrationTest
class FnrhCallHistoryEmptyTest {
    @Test
    fun `with no call registered the last success and the last failure are null`() {
        SingleConnectionDataSource(
            PostgresContainer.instance.jdbcUrl,
            PostgresContainer.ownerUsername,
            PostgresContainer.ownerPassword,
            true,
        ).use { dataSource ->
            val connection = dataSource.connection
            connection.autoCommit = false
            try {
                val jdbc = JdbcClient.create(dataSource)
                jdbc.sql("SET LOCAL session_replication_role = replica").update()
                jdbc.sql("DELETE FROM fnrh.integration_call").update()
                val history = JdbcFnrhCallHistory(jdbc)

                for (environment in FnrhEnvironment.entries) {
                    history.lastSuccessAt(environment) shouldBe null
                    history.lastFailure(environment) shouldBe null
                }
            } finally {
                connection.rollback()
            }
        }
    }
}
