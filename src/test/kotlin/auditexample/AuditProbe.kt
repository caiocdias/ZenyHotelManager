package auditexample

import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.AuditMasked
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.UserId
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Caso de uso só de teste (fora do pacote base, como o `authzexample`) para exercitar o aspecto de
 * `@Audited` e a trilha: grava uma linha em `test_support.example_task` e descreve a mudança.
 */
@Service
class AuditProbeService(
    private val audit: AuditContext,
    private val jdbc: JdbcClient,
) {
    /** Grava a tarefa, descreve a criação e, se pedido, falha depois de tudo (rollback). */
    @Audited("test.probe.write")
    @Transactional
    fun write(
        id: UUID,
        title: String,
        snapshot: Any? = null,
        failAfterWriting: Boolean = false,
    ) {
        insert(id, title)
        audit.created(PROBE, id) { snapshot ?: mapOf("title" to title) }
        check(!failAfterWriting) { "falha proposital depois da escrita" }
    }

    /** Muda o título de uma tarefa acompanhada (antes e depois pelo retrato). */
    @Audited("test.probe.rename")
    @Transactional
    fun rename(
        id: UUID,
        title: String,
    ) {
        audit.track(PROBE, id) { mapOf("title" to titleOf(id)) }
        jdbc
            .sql("UPDATE test_support.example_task SET title = :title WHERE id = :id")
            .param("title", title)
            .param("id", id)
            .update()
    }

    /** Grava, descreve e recusa com uma [ConflictException] que confirma a transação. */
    @Audited("test.probe.reject", recordRejections = true)
    @Transactional(noRollbackFor = [ConflictException::class])
    fun reject(id: UUID) {
        insert(id, "recusada")
        audit.created(PROBE, id) { mapOf("title" to "recusada") }
        throw ConflictException(REJECTED, "Recusa de teste.")
    }

    /** O mesmo, mas a recusa desfaz a transação: nem a linha nem a entrada ficam. */
    @Audited("test.probe.reject-rollback", recordRejections = true)
    @Transactional
    fun rejectRolledBack(id: UUID) {
        insert(id, "recusada e desfeita")
        audit.created(PROBE, id) { mapOf("title" to "recusada e desfeita") }
        throw ConflictException(REJECTED, "Recusa de teste.")
    }

    /** Recusa sem `recordRejections`: nada é gravado na trilha, mesmo com a transação confirmada. */
    @Audited("test.probe.reject-unrecorded")
    @Transactional(noRollbackFor = [ConflictException::class])
    fun rejectUnrecorded(id: UUID) {
        insert(id, "recusa sem registro")
        audit.created(PROBE, id) { mapOf("title" to "recusa sem registro") }
        throw ConflictException(REJECTED, "Recusa de teste.")
    }

    /** O retrato do fim do método falha: a entrada não sai, e a escrita é desfeita. */
    @Audited("test.probe.failing-snapshot")
    @Transactional
    fun failingSnapshot(id: UUID) {
        insert(id, "retrato que falha")
        audit.created(PROBE, id) { error("retrato indisponível") }
    }

    /** Sem nenhuma descrição: uma entrada só com a ação. */
    @Audited("test.probe.undescribed")
    @Transactional
    fun undescribed() = Unit

    /** Declara que não mudou nada: nenhuma entrada. */
    @Audited("test.probe.no-changes")
    @Transactional
    fun noChanges() = audit.noChanges()

    /** Atribui as entradas a um usuário e sessão (como o login) e registra uma aprovação. */
    @Audited("test.probe.attributed")
    @Transactional
    fun attributed(
        id: UUID,
        userId: UserId,
        approvalId: UUID,
    ) {
        audit.attributeTo(userId, null)
        audit.approval(approvalId)
        audit.record(PROBE, id, null, mapOf("title" to "atribuída"))
    }

    /** `@Audited` sem transação: recusado antes de executar. */
    @Audited("test.probe.no-transaction")
    fun withoutTransaction(id: UUID) = insert(id, "sem transação")

    /** `@Audited` numa transação só de leitura: recusado antes de executar. */
    @Audited("test.probe.read-only")
    @Transactional(readOnly = true)
    fun readOnly() = Unit

    private fun insert(
        id: UUID,
        title: String,
    ) {
        jdbc
            .sql(
                "INSERT INTO test_support.example_task (id, title, created_by, completed) " +
                    "VALUES (:id, :title, :id, false)",
            ).param("id", id)
            .param("title", title)
            .update()
    }

    private fun titleOf(id: UUID): String? =
        jdbc
            .sql("SELECT title FROM test_support.example_task WHERE id = :id")
            .param("id", id)
            .query(String::class.java)
            .optional()
            .orElse(null)

    companion object {
        const val PROBE = "test.probe"
        val REJECTED = ErrorCode("PROBE_REJECTED")
    }
}

/** Um retrato com tudo o que nunca pode ir para a trilha, ao lado do que pode. */
data class SecretSnapshot(
    val name: String,
    val password: String,
    val pin: String,
    val refreshToken: String,
    val pinHash: String,
    val apiSecret: String,
    @AuditMasked val documentNumber: String,
    val note: String,
    val hasPin: Boolean,
    val passwordChangeRequired: Boolean,
    val nested: Map<String, Any?>,
)

@TestConfiguration(proxyBeanMethods = false)
class AuditProbeConfiguration {
    @Bean
    fun auditProbeService(
        audit: AuditContext,
        jdbc: JdbcClient,
    ) = AuditProbeService(audit, jdbc)
}
