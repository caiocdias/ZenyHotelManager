package com.zeny.hotel.iam

import com.zeny.hotel.shared.ErrorCode

/**
 * Códigos de erro estáveis do módulo `iam` (ARCHITECTURE §7). Todos os de autenticação saem
 * com `401`; o cliente decide pelo código (só o `UNAUTHENTICATED` do `shared` pede um refresh).
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
}
