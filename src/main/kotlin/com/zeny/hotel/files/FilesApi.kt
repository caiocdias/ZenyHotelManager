package com.zeny.hotel.files

import com.zeny.hotel.shared.UserId
import java.time.Instant

/**
 * API pública do `files` para os outros módulos (ARCHITECTURE §3.1): conferir que um anexo existe e
 * é de uma finalidade antes de guardar o id dele, vinculá-lo ao registro dono, desvinculá-lo e
 * apagá-lo. Não checa permissão: quem chama é um caso de uso que já se autorizou. O upload e o
 * download são da API HTTP (`/api/v1/files`), não daqui.
 *
 * Um anexo sem vínculo (órfão: nunca vinculado, ou desvinculado) é apagado pelo expurgo depois de
 * `zeny.files.orphan-retention` (padrão 24 h) sem vínculo. Um anexo apagado (por [delete] ou pelo
 * expurgo) some de [find], dos metadados e da URL de download (`404 FILE_NOT_FOUND`); a linha fica,
 * marcada com a data da exclusão, porque a trilha de auditoria a referencia.
 */
interface FilesApi {
    /** O anexo com o [id], ou `null` se não existe ou foi apagado. */
    fun find(id: FileId): FileSummary?

    /**
     * Vincula o anexo ao registro dono ([ownerEntityId], o id do registro no módulo da finalidade),
     * na transação de quem chama, e o audita (`files.file.attach`). Vincular de novo ao mesmo
     * registro não muda nada. Falha com `404 FILE_NOT_FOUND` se o anexo não existe, `422
     * FILE_PURPOSE_MISMATCH` se ele é de outra finalidade e `409 FILE_ALREADY_ATTACHED` se já é de
     * outro registro.
     */
    fun attach(
        id: FileId,
        purpose: FilePurposeCode,
        ownerEntityId: String,
    ): FileSummary

    /**
     * Desfaz o vínculo do anexo com o registro dono [ownerEntityId], na transação de quem chama, e o
     * audita (`files.file.detach`): o anexo volta a órfão e o expurgo o apaga depois de
     * `zeny.files.orphan-retention` sem vínculo (contados daqui). Desvincular um órfão não muda nada.
     * Falha com `404 FILE_NOT_FOUND` se o anexo não existe ou foi apagado, `422
     * FILE_PURPOSE_MISMATCH` se ele é de outra finalidade e `409 FILE_ALREADY_ATTACHED` se ele é de
     * outro registro.
     */
    fun detach(
        id: FileId,
        purpose: FilePurposeCode,
        ownerEntityId: String,
    ): FileSummary

    /**
     * Apaga o anexo do registro dono [ownerEntityId] (ou um órfão), na transação de quem chama, e o
     * audita (`files.file.delete`): a linha é marcada com a data da exclusão e o objeto sai do
     * armazenamento **depois do commit** (a transação desfeita não apaga nada). Falha com `404
     * FILE_NOT_FOUND` se o anexo não existe ou já foi apagado, `422 FILE_PURPOSE_MISMATCH` se ele é
     * de outra finalidade e `409 FILE_ALREADY_ATTACHED` se ele é de outro registro.
     */
    fun delete(
        id: FileId,
        purpose: FilePurposeCode,
        ownerEntityId: String,
    )
}

/** Um anexo como os outros módulos o veem: sem o nome original, a chave no armazenamento nem o conteúdo. */
data class FileSummary(
    val id: FileId,
    val purpose: FilePurposeCode,
    val contentType: String,
    val sizeBytes: Long,
    val sensitive: Boolean,
    /** O registro dono (no módulo da finalidade), depois de [FilesApi.attach]; antes, `null`. */
    val ownerEntityId: String?,
    val createdBy: UserId?,
    val createdAt: Instant,
)
