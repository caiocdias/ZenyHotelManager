package com.zeny.hotel.fnrh

import com.zeny.hotel.shared.ErrorCode

/**
 * Códigos de erro (de problema) do `fnrh` (ARCHITECTURE §7). Um código publicado não muda de nome nem de sentido.
 */
object FnrhErrorCodes {
    /** 422: ligar a integração sem usuário, senha ou CPF solicitante padrão. */
    val FNRH_SETTINGS_INCOMPLETE = ErrorCode("FNRH_SETTINGS_INCOMPLETE")

    /** 422: trocar de ambiente exige informar a senha de novo (a credencial de um ambiente não vai para o outro). */
    val FNRH_PASSWORD_REQUIRED_FOR_ENVIRONMENT_CHANGE = ErrorCode("FNRH_PASSWORD_REQUIRED_FOR_ENVIRONMENT_CHANGE")

    /** 422: o usuário da FNRH não pode ter dois-pontos (RFC 7617, Basic Auth). */
    val FNRH_USERNAME_INVALID = ErrorCode("FNRH_USERNAME_INVALID")

    /** 404: o CPF solicitante pedido não existe para o usuário. */
    val FNRH_REQUESTER_NOT_FOUND = ErrorCode("FNRH_REQUESTER_NOT_FOUND")

    /** 404: o domínio oficial pedido não existe. */
    val FNRH_DOMAIN_NOT_FOUND = ErrorCode("FNRH_DOMAIN_NOT_FOUND")

    /** 409: a integração está desligada ou sem configuração completa. */
    val FNRH_NOT_CONFIGURED = ErrorCode("FNRH_NOT_CONFIGURED")

    /** 502: o Serpro recusou as credenciais configuradas. */
    val FNRH_CREDENTIALS_REJECTED = ErrorCode("FNRH_CREDENTIALS_REJECTED")

    /** 503: o Serpro está indisponível (conexão recusada ou erro do servidor). */
    val FNRH_UNAVAILABLE = ErrorCode("FNRH_UNAVAILABLE")

    /** 502: o Serpro recusou a operação (a mensagem dele, saneada, vai só ao log). */
    val FNRH_REJECTED = ErrorCode("FNRH_REJECTED")
}

/** Códigos de campo (`errors[].code`) do `fnrh`, além de `REQUIRED`, `INVALID_VALUE` e `INVALID_FORMAT`. */
object FnrhFieldCodes {
    /** O valor não consta do domínio oficial da FNRH. */
    const val NOT_IN_DOMAIN = "NOT_IN_DOMAIN"

    /** O valor não é coerente com outro campo da mesma ficha. */
    const val INCONSISTENT = "INCONSISTENT"
}
