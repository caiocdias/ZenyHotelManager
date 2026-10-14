package com.zeny.hotel.iam

import com.zeny.hotel.shared.ErrorCode

/**
 * Códigos de erro estáveis do módulo `iam` (ARCHITECTURE §7). Todos os de autenticação saem
 * com `401`; o cliente decide pelo código (só o `UNAUTHENTICATED` do `shared` pede um refresh).
 * Os de autorização (`403`) saem da porta `Authorization` do `shared`, que o `iam` implementa,
 * para qualquer módulo.
 */
object IamErrorCodes {
    /** 401: login inexistente ou senha errada (não se diz qual dos dois). */
    val INVALID_CREDENTIALS = ErrorCode("INVALID_CREDENTIALS")

    /** 401: login bloqueado por falhas seguidas; recusado até vencer o bloqueio, mesmo com a senha certa. */
    val ACCOUNT_LOCKED = ErrorCode("ACCOUNT_LOCKED")

    /** 401: usuário desativado (só depois de a senha conferir). */
    val ACCOUNT_DISABLED = ErrorCode("ACCOUNT_DISABLED")

    /** 401: refresh token desconhecido, vencido, de sessão revogada ou de usuário desativado. */
    val REFRESH_TOKEN_INVALID = ErrorCode("REFRESH_TOKEN_INVALID")

    /** 401: refresh token já trocado por outro foi reapresentado; a sessão inteira foi revogada. */
    val REFRESH_TOKEN_REUSED = ErrorCode("REFRESH_TOKEN_REUSED")

    /** 422: a nova senha é igual à atual. */
    val PASSWORD_UNCHANGED = ErrorCode("PASSWORD_UNCHANGED")

    /** 404: sessão inexistente ou de outro usuário. */
    val SESSION_NOT_FOUND = ErrorCode("SESSION_NOT_FOUND")

    /** 403: o usuário não tem a permissão do catálogo exigida pela ação (ou ela está obsoleta). */
    val PERMISSION_DENIED = ErrorCode("PERMISSION_DENIED")

    /** 403: a permissão do usuário tem escopo `OWN` e o registro é de outro usuário. */
    val RESOURCE_OUT_OF_SCOPE = ErrorCode("RESOURCE_OUT_OF_SCOPE")
}
