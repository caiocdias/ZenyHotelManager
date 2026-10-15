package com.zeny.hotel.files.application

import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.domain.StoredFile
import java.net.URI
import java.time.Duration

/** Os metadados dos anexos, na transação corrente (implementada por JPA em `infrastructure`). */
interface StoredFiles {
    fun find(id: FileId): StoredFile?

    /** Trava a linha (`SELECT … FOR UPDATE`) para mudar o vínculo. */
    fun findForUpdate(id: FileId): StoredFile?

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
