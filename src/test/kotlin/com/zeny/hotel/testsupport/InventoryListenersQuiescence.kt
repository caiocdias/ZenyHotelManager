package com.zeny.hotel.testsupport

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.awaitility.core.ConditionTimeoutException
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration

/**
 * Espera os listeners do inventário do `reservations` (M4 E05) terminarem: a criação, a troca de tipo, a desativação
 * e a reativação de UH e cada OOO disparam um listener assíncrono que abre uma transação de escrita para refazer os
 * contadores. Enquanto ela está aberta, o download da sincronização offline (H6 do M2) não serve nenhuma mudança
 * confirmada depois de ela começar (o cursor só anda até as transações terminadas), então um teste que baixa logo
 * depois de mexer numa UH, ou logo depois de outro teste que mexeu, veria o download incompleto. Chame antes de todo
 * download e no `@BeforeEach` dos testes da sincronização.
 *
 * Conta as publicações do outbox dos listeners do inventário ainda não concluídas (sem as `FAILED`, que já saíram de
 * execução); a conclusão é gravada na transação do listener, então zero = nenhuma transação deles aberta.
 */
object InventoryListenersQuiescence {
    private val timeout: Duration = Duration.ofSeconds(30)

    fun await(jdbc: JdbcClient) {
        try {
            await().atMost(timeout).pollInterval(Duration.ofMillis(POLL_MILLIS)).until { pending(jdbc) == 0 }
        } catch (expired: ConditionTimeoutException) {
            withClue("listeners do inventário ainda em execução depois de $timeout: ${expired.message}") {
                pending(jdbc) shouldBe 0
            }
        }
    }

    private fun pending(jdbc: JdbcClient): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM shared.event_publication WHERE completion_date IS NULL " +
                    "AND status IS DISTINCT FROM 'FAILED' AND listener_id LIKE :listener",
            ).param("listener", "%reservations.application.InventoryListeners%")
            .query(Int::class.java)
            .single()

    private const val POLL_MILLIS = 50L
}
