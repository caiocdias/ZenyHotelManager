package com.zeny.hotel.guests.infrastructure

import com.zeny.hotel.guests.DocumentType
import com.zeny.hotel.guests.domain.IdDocuments
import com.zeny.hotel.shared.BlindIndex
import com.zeny.hotel.shared.FieldCipher
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** O resultado de uma passada da [GuestDocumentRekeyJob]. */
data class RekeyResult(
    val rekeyed: Int,
    val failed: Int,
    val pending: Long,
)

/**
 * Rotação das chaves nos documentos de hóspede (H8 do M3, ARCHITECTURE §8.3): a cada
 * `zeny.guests.document-rekey.interval` (padrão 1 h) e sob trava do ShedLock (`guests-documents-rekey`), procura
 * os documentos com o número cifrado ou o índice cego calculado com uma chave que não é a ativa e, um por
 * transação (com a linha travada), re-cifra o número e recalcula o índice com a chave ativa. Quando não sobra
 * nenhum, a chave antiga pode sair do anel (procedimento no `CLAUDE.md`).
 *
 * É uma **escrita técnica**, fora da trilha de auditoria, como os expurgos de dados transitórios: o número e o
 * documento não mudam (só a chave que os protege), e nenhuma pessoa age. Por execução, no máximo [MAX_BATCHES]
 * lotes de [BATCH_SIZE]; um documento que não decifra (chave já retirada, valor corrompido) vai para o log só
 * com o id e não interrompe os outros.
 *
 * Métrica `zeny.guests.documents.rekey.pending` (`zeny_guests_documents_rekey_pending` no Prometheus): quantos
 * documentos ainda usam outra chave, medido no fim de cada execução (`NaN` antes da primeira). Zero: a rotação
 * terminou para os documentos.
 */
@Component
class GuestDocumentRekeyJob(
    private val jdbc: JdbcClient,
    private val cipher: FieldCipher,
    private val blindIndex: BlindIndex,
    transactionManager: PlatformTransactionManager,
) : MeterBinder {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transactions = TransactionTemplate(transactionManager)

    @Volatile
    private var pending: Long? = null

    override fun bindTo(registry: MeterRegistry) {
        Gauge
            .builder(PENDING_METRIC, this) { it.pending?.toDouble() ?: Double.NaN }
            .description("Documentos de hóspede com o número ou o índice cego ainda numa chave que não é a ativa")
            .register(registry)
    }

    @Scheduled(
        fixedDelayString = "\${zeny.guests.document-rekey.interval}",
        initialDelayString = "\${zeny.guests.document-rekey.initial-delay}",
    )
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT30M")
    fun run() {
        val result = rekeyStale()
        if (result.rekeyed > 0 || result.failed > 0) {
            log.info(
                "Rotação de chaves dos documentos: {} atualizados, {} com falha, {} pendentes",
                result.rekeyed,
                result.failed,
                result.pending,
            )
        }
    }

    /** Uma passada: até [MAX_BATCHES] lotes, cada documento na sua transação. */
    fun rekeyStale(): RekeyResult {
        var rekeyed = 0
        var failed = 0
        var after: UUID? = null
        var batches = 0
        do {
            val ids = staleIds(after, BATCH_SIZE)
            ids.forEach { id ->
                runCatching { transactions.execute { rekey(id) } == true }
                    .onSuccess { if (it) rekeyed++ }
                    .onFailure {
                        failed++
                        // Só o id e o tipo do erro: a mensagem de uma falha de decifragem não leva o valor, mas
                        // nada do documento vai ao log.
                        log.warn("Rotação de chave do documento {} falhou ({})", id, it.javaClass.simpleName)
                    }
            }
            after = ids.lastOrNull()
            batches++
        } while (ids.size == BATCH_SIZE && batches < MAX_BATCHES)
        return RekeyResult(rekeyed, failed, countStale().also { pending = it })
    }

    private fun rekey(id: UUID): Boolean {
        val row =
            jdbc
                .sql(
                    "SELECT type, issuing_country, number, number_index_key, number_index " +
                        "FROM guests.guest_document WHERE id = :id FOR UPDATE",
                ).param("id", id)
                .query { rs, _ ->
                    StoredDocument(
                        DocumentType.valueOf(rs.getString("type")),
                        rs.getString("issuing_country"),
                        rs.getString("number"),
                        rs.getString("number_index_key"),
                        rs.getString("number_index"),
                    )
                }.optional()
                .orElse(null) ?: return false
        val plain = cipher.decrypt(row.number)
        val number = if (cipher.isEncryptedWithActiveKey(row.number)) row.number else cipher.encrypt(plain)
        val index =
            blindIndex.compute(IdDocuments.INDEX_PURPOSE, IdDocuments.indexInput(row.type, row.issuingCountry, plain))
        val changed = number != row.number || index.keyId != row.indexKey || index.hash != row.index
        if (changed) {
            jdbc
                .sql(
                    "UPDATE guests.guest_document SET number = :number, number_index_key = :key, " +
                        "number_index = :hash WHERE id = :id",
                ).param("number", number)
                .param("key", index.keyId)
                .param("hash", index.hash)
                .param("id", id)
                .update()
        }
        return changed
    }

    private fun staleIds(
        after: UUID?,
        limit: Int,
    ): List<UUID> =
        jdbc
            .sql(
                """
                SELECT id FROM guests.guest_document
                WHERE $STALE
                  AND (CAST(:after AS uuid) IS NULL OR id > CAST(:after AS uuid))
                ORDER BY id LIMIT :limit
                """.trimIndent(),
            ).param("key", blindIndex.activeKeyId)
            .param("prefix", cipherPrefix())
            .param("after", after)
            .param("limit", limit)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) }
            .list()

    private fun countStale(): Long =
        jdbc
            .sql("SELECT count(*) FROM guests.guest_document WHERE $STALE")
            .param("key", blindIndex.activeKeyId)
            .param("prefix", cipherPrefix())
            .query(Long::class.java)
            .single()

    /** O começo de um valor cifrado com a chave ativa (formato do `FieldCipher`: `v1:<chave>:…`). */
    private fun cipherPrefix() = "v1:${blindIndex.activeKeyId}:"

    private class StoredDocument(
        val type: DocumentType,
        val issuingCountry: String,
        val number: String,
        val indexKey: String,
        val index: String,
    )

    companion object {
        const val LOCK_NAME = "guests-documents-rekey"
        const val PENDING_METRIC = "zeny.guests.documents.rekey.pending"
        const val BATCH_SIZE = 100
        const val MAX_BATCHES = 10

        /** O índice ou o número numa chave que não é a ativa (sem `LIKE`: o `_` do id da chave seria curinga). */
        private const val STALE =
            "(number_index_key <> :key OR left(number, length(CAST(:prefix AS text))) <> CAST(:prefix AS text))"
    }
}
