package com.zeny.hotel.testsupport

import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import java.io.IOException
import java.util.UUID

/** Exceção *checked* dos cenários de rollback (o Kotlin deixa lançá-la sem declarar). */
class ProbeCheckedException : Exception("checked de teste")

/**
 * Casos de uso só de teste para o rollback em toda exceção (M6 E18): gravam uma linha marcada em
 * `test_support.example_task`, descrevem a entidade na trilha e lançam uma *checked*. Sem `@Service`: o bean vem só de
 * [TransactionAndLockProbesConfiguration], para não entrar na varredura de todos os contextos.
 */
open class TransactionProbe(
    private val audit: AuditContext,
    private val jdbc: JdbcClient,
) {
    /** Grava, descreve e lança uma *checked*: a escrita e a entrada têm de ser desfeitas. */
    @Audited(WRITE_THEN_CHECKED)
    @Transactional
    open fun writeThenThrowChecked(marker: String) {
        val id = insertMarker(jdbc, marker)
        audit.created(PROBE_ENTITY, id) { mapOf("title" to marker) }
        throw IOException("checked depois da escrita")
    }

    /** Grava e lança uma *checked* listada no `noRollbackFor`: a escrita fica. */
    @Transactional(noRollbackFor = [ProbeCheckedException::class])
    open fun writeThenThrowListed(marker: String) {
        insertMarker(jdbc, marker)
        throw ProbeCheckedException()
    }

    /** Grava e lança uma *checked*, para ser chamado de dentro de uma transação que já existe (REQUIRED). */
    @Transactional
    open fun joinedWriteThenThrowChecked(marker: String) {
        insertMarker(jdbc, marker)
        throw IOException("checked na transação unida")
    }

    companion object {
        const val WRITE_THEN_CHECKED = "test.rollback.write-then-checked"
        const val PROBE_ENTITY = "test.rollback"
    }
}

/**
 * Chamador que engole a *checked* de [TransactionProbe.joinedWriteThenThrowChecked] dentro da própria transação.
 */
open class SwallowingCaller(
    private val probe: TransactionProbe,
    private val jdbc: JdbcClient,
) {
    @Transactional
    open fun writeAndSwallow(marker: String) {
        insertMarker(jdbc, marker)
        try {
            probe.joinedWriteThenThrowChecked(marker)
        } catch (_: IOException) {
            // engolida de propósito: o cenário do Review Focus 5
        }
    }
}

/** Combinação de contexto dos testes do rollback e da trava do agendador (`@IntegrationTest` + esta configuração). */
@TestConfiguration(proxyBeanMethods = false)
class TransactionAndLockProbesConfiguration {
    @Bean
    fun transactionProbe(
        audit: AuditContext,
        jdbc: JdbcClient,
    ) = TransactionProbe(audit, jdbc)

    @Bean
    fun swallowingCaller(
        probe: TransactionProbe,
        jdbc: JdbcClient,
    ) = SwallowingCaller(probe, jdbc)
}

/** Grava uma linha em `test_support.example_task` com o [marker] como título e devolve o id. */
internal fun insertMarker(
    jdbc: JdbcClient,
    marker: String,
): UUID {
    val id = UUID.randomUUID()
    jdbc
        .sql(
            "INSERT INTO test_support.example_task (id, title, created_by, completed) " +
                "VALUES (:id, :title, :id, false)",
        ).param("id", id)
        .param("title", marker)
        .update()
    return id
}

/** Quantas linhas de `test_support.example_task` têm o [marker] como título. */
internal fun rowsWithMarker(
    jdbc: JdbcClient,
    marker: String,
): Int =
    jdbc
        .sql("SELECT count(*) FROM test_support.example_task WHERE title = :title")
        .param("title", marker)
        .query(Int::class.java)
        .single()
