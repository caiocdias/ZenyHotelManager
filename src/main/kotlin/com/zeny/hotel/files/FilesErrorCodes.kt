package com.zeny.hotel.files

import com.zeny.hotel.shared.ErrorCode

/**
 * Códigos de erro estáveis do módulo `files` (ARCHITECTURE §7). As recusas do upload saem com `400`
 * e `errors[]` apontando o campo (`purpose` ou `file`).
 */
object FilesErrorCodes {
    /** 400: finalidade que não está no catálogo. */
    val UNKNOWN_FILE_PURPOSE = ErrorCode("UNKNOWN_FILE_PURPOSE")

    /** 400: arquivo vazio. */
    val FILE_EMPTY = ErrorCode("FILE_EMPTY")

    /** 400: arquivo acima do tamanho máximo da finalidade. */
    val FILE_TOO_LARGE = ErrorCode("FILE_TOO_LARGE")

    /** 400: o conteúdo não é de um tipo aceito pela finalidade (detectado pelo conteúdo, não pela extensão). */
    val FILE_TYPE_NOT_ACCEPTED = ErrorCode("FILE_TYPE_NOT_ACCEPTED")

    /** 400: a extensão do nome do arquivo não é a do tipo detectado no conteúdo. */
    val FILE_EXTENSION_MISMATCH = ErrorCode("FILE_EXTENSION_MISMATCH")

    /** 400: o conteúdo de uma finalidade com reencode não pôde ser lido como imagem. */
    val FILE_IMAGE_UNREADABLE = ErrorCode("FILE_IMAGE_UNREADABLE")

    /** 400: imagem de uma finalidade com reencode com dimensões acima do teto (antes de decodificar). */
    val FILE_IMAGE_TOO_LARGE = ErrorCode("FILE_IMAGE_TOO_LARGE")

    /** 400: `clientFileId` que não é um UUIDv7 na forma canônica. */
    val CLIENT_FILE_ID_INVALID = ErrorCode("CLIENT_FILE_ID_INVALID")

    /**
     * 409: `clientFileId` já usado por outro anexo: de outro usuário, de outra finalidade, com outro
     * conteúdo ou já apagado. O cliente gera outro id.
     */
    val CLIENT_FILE_ID_CONFLICT = ErrorCode("CLIENT_FILE_ID_CONFLICT")

    /** 404: anexo inexistente, apagado (ou de uma finalidade retirada do catálogo). */
    val FILE_NOT_FOUND = ErrorCode("FILE_NOT_FOUND")

    /** 422: o anexo é de outra finalidade. */
    val FILE_PURPOSE_MISMATCH = ErrorCode("FILE_PURPOSE_MISMATCH")

    /** 409: o anexo já está vinculado a outro registro (ao vincular, desvincular ou apagar). */
    val FILE_ALREADY_ATTACHED = ErrorCode("FILE_ALREADY_ATTACHED")

    /** 403: URL de download adulterada, de outro anexo ou ilegível. */
    val DOWNLOAD_URL_INVALID = ErrorCode("DOWNLOAD_URL_INVALID")

    /** 403: URL de download vencida; peça outra. */
    val DOWNLOAD_URL_EXPIRED = ErrorCode("DOWNLOAD_URL_EXPIRED")
}
