package com.zeny.hotel.files.application

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Remoção dos objetos do armazenamento amarrada ao desfecho da transação de escrita corrente: o
 * objeto de um upload sai se a transação desfizer; o de um anexo apagado (exclusão ou expurgo),
 * só **depois do commit**, para a transação desfeita não deixar uma linha viva sem objeto. Uma
 * falha do armazenamento depois do commit só vai para o log (o objeto fica no bucket, sem linha
 * viva que o sirva).
 */
@Component
class StoredObjectRemovals(
    private val storage: FileStorage,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Apaga o objeto [key] se a transação corrente desfizer. */
    fun removeOnRollback(key: String) =
        register(key) { status ->
            if (status == TransactionSynchronization.STATUS_ROLLED_BACK) remove(key, "de um upload desfeito")
        }

    /** Apaga o objeto [key] depois que a transação corrente confirmar; se ela desfizer, nada. */
    fun removeAfterCommit(key: String) =
        register(key) { status ->
            if (status == TransactionSynchronization.STATUS_COMMITTED) remove(key, "de um anexo apagado")
        }

    private fun register(
        key: String,
        onCompletion: (Int) -> Unit,
    ) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "A remoção do objeto $key precisa de uma transação de escrita"
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCompletion(status: Int) = onCompletion(status)
            },
        )
    }

    private fun remove(
        key: String,
        what: String,
    ) {
        runCatching { storage.delete(key) }
            .onFailure { log.warn("Objeto {} {} não foi apagado do armazenamento", key, what, it) }
    }
}
