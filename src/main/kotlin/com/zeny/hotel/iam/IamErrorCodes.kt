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

    /** 422: a ação pede um valor acima do limite do usuário numa permissão que não aceita aprovação. */
    val LIMIT_EXCEEDED = ErrorCode("LIMIT_EXCEEDED")

    // --- Aprovação de superior (M1 E05, RF-ACS-07) ---------------------------------------------
    // A falta da aprovação é o `APPROVAL_REQUIRED` do `shared`.

    /** 403: login do aprovador inexistente, aprovador sem PIN ou PIN errado (não se diz qual). */
    val APPROVAL_CREDENTIALS_INVALID = ErrorCode("APPROVAL_CREDENTIALS_INVALID")

    /** 403: aprovações do aprovador bloqueadas por PIN errado seguido; recusado mesmo com o PIN certo. */
    val APPROVER_LOCKED = ErrorCode("APPROVER_LOCKED")

    /** 403: o aprovador não pode ser o próprio solicitante. */
    val APPROVER_IS_REQUESTER = ErrorCode("APPROVER_IS_REQUESTER")

    /**
     * 403: o aprovador não tem (ou não tem mais, no uso do token) a permissão, o escopo ou o limite
     * que a ação exige, ou está desativado.
     */
    val APPROVER_NOT_ALLOWED = ErrorCode("APPROVER_NOT_ALLOWED")

    /** 422: a permissão não existe no catálogo vigente, não é sensível ou não declara o limite pedido. */
    val APPROVAL_NOT_APPLICABLE = ErrorCode("APPROVAL_NOT_APPLICABLE")

    /** 403: token de aprovação desconhecido ou malformado. */
    val APPROVAL_TOKEN_INVALID = ErrorCode("APPROVAL_TOKEN_INVALID")

    /** 403: o token de aprovação venceu (2 min). */
    val APPROVAL_TOKEN_EXPIRED = ErrorCode("APPROVAL_TOKEN_EXPIRED")

    /** 403: o token de aprovação já foi usado (vale uma vez). */
    val APPROVAL_TOKEN_USED = ErrorCode("APPROVAL_TOKEN_USED")

    /**
     * 403: o token de aprovação é de outra operação: outra ação, permissão, recurso ou valor de
     * limite, outro solicitante ou outra sessão dele.
     */
    val APPROVAL_TOKEN_MISMATCH = ErrorCode("APPROVAL_TOKEN_MISMATCH")

    // --- Gestão de usuários e roles (M1 E03) ---------------------------------------------------

    /** 404: usuário inexistente. */
    val USER_NOT_FOUND = ErrorCode("USER_NOT_FOUND")

    /** 404: role inexistente. */
    val ROLE_NOT_FOUND = ErrorCode("ROLE_NOT_FOUND")

    /** 409: o login (sem diferenciar maiúsculas) já é de outro usuário. */
    val LOGIN_ALREADY_IN_USE = ErrorCode("LOGIN_ALREADY_IN_USE")

    /** 409: o nome (sem diferenciar maiúsculas) já é de outra role. */
    val ROLE_NAME_ALREADY_IN_USE = ErrorCode("ROLE_NAME_ALREADY_IN_USE")

    /** 409: a ação deixaria o sistema sem nenhum administrador ativo. */
    val LAST_ADMINISTRATOR = ErrorCode("LAST_ADMINISTRATOR")

    /** 400: uma das roles informadas não existe. */
    val UNKNOWN_ROLE = ErrorCode("UNKNOWN_ROLE")

    /** 400: a permissão não existe no catálogo vigente. */
    val UNKNOWN_PERMISSION = ErrorCode("UNKNOWN_PERMISSION")

    /**
     * 400: concessão inválida para a permissão: escopo que ela não suporta, limite que ela não
     * declara, valor fora da unidade, teto e "sem teto" juntos (ou nenhum) ou permissão repetida.
     */
    val INVALID_GRANT = ErrorCode("INVALID_GRANT")

    /** 422: a role de sistema Administrador é mantida pelo catálogo e não aceita edição. */
    val SYSTEM_ROLE_IMMUTABLE = ErrorCode("SYSTEM_ROLE_IMMUTABLE")

    /**
     * 403: a ação concederia permissão, escopo ou limite acima dos do próprio usuário (RF-ACS-09),
     * ou daria a ele acesso a um usuário com permissões acima das suas (senha, PIN, reativação).
     */
    val GRANT_EXCEEDS_OWN_PERMISSIONS = ErrorCode("GRANT_EXCEEDS_OWN_PERMISSIONS")

    /** 403: só um administrador gerencia administradores e dá ou tira a role Administrador (RF-ACS-09). */
    val ADMINISTRATOR_REQUIRED = ErrorCode("ADMINISTRATOR_REQUIRED")
}
