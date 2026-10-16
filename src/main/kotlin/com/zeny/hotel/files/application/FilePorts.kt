package com.zeny.hotel.files.application

import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.domain.StoredFile
import java.net.URI
import java.time.Duration
import java.time.Instant

/**
 * Os metadados dos anexos, na transação corrente (implementada por JPA em `infrastructure`). [find] e
 * [findForUpdate] só enxergam os anexos não apagados.
 */
interface StoredFiles {
    /** O anexo não apagado com o [id], ou `null`. */
    fun find(id: FileId): StoredFile?

    /** Trava a linha (`SELECT … FOR UPDATE`) do anexo não apagado, para mudar o vínculo ou apagá-lo. */
    fun findForUpdate(id: FileId): StoredFile?

    /** O anexo com o [id], apagado ou não (a repetição de um upload com `clientFileId`). */
    fun findIncludingDeleted(id: FileId): StoredFile?

    /**
     * Trava consultiva da transação (`pg_advisory_xact_lock`) sobre o id escolhido pelo cliente:
     * dois uploads simultâneos com o mesmo `clientFileId` se enfileiram, e o segundo vê o anexo
     * gravado pelo primeiro.
     */
    fun lockClientFileId(id: FileId)

    /**
     * Até [limit] órfãos (sem vínculo e não apagados) sem vínculo desde antes de [cutoff] (o
     * desvínculo ou, se nunca houve, a criação), dos mais antigos aos mais novos.
     */
    fun orphansBefore(
        cutoff: Instant,
        limit: Int,
    ): List<FileId>

    /** Grava e descarrega na hora, para a falha de banco acontecer antes de gravar o objeto. */
    fun add(file: StoredFile)

    fun flush()
}

/**
 * Porta do armazenamento de objetos (D14): uma API S3 (MinIO no compose, S3/R2/B2 na nuvem). As
 * chaves vêm do [StoredFile.objectKey]. Os objetos não são públicos: o download é por URL assinada.
 */
interface FileStorage {
    fun put(
        key: String,
        content: ByteArray,
        contentType: String,
    )

    /** O conteúdo gravado; falha se o objeto não existe. */
    fun get(key: String): ByteArray

    /** Apaga o objeto (sem erro se não existe). */
    fun delete(key: String)

    /**
     * URL assinada que baixa o objeto direto do armazenamento por [ttl], com o tipo e o nome de
     * download informados (cabeçalhos da resposta assinados junto).
     */
    fun presignedDownload(
        key: String,
        ttl: Duration,
        contentType: String,
        downloadName: String,
    ): URI
}
