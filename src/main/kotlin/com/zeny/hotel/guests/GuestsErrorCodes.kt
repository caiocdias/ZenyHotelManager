package com.zeny.hotel.guests

import com.zeny.hotel.shared.ErrorCode

/**
 * Códigos de erro estáveis do módulo `guests` (ARCHITECTURE §7). As mensagens nunca repetem o valor recebido
 * (nome, documento, endereço): podem ir à trilha de recusas e aos logs de quem chama.
 */
object GuestsErrorCodes {
    /** 404: perfil de hóspede inexistente. */
    val GUEST_NOT_FOUND = ErrorCode("GUEST_NOT_FOUND")

    /** 404: documento inexistente (ou de outro perfil). */
    val GUEST_DOCUMENT_NOT_FOUND = ErrorCode("GUEST_DOCUMENT_NOT_FOUND")

    /** 404: empresa inexistente. */
    val COMPANY_NOT_FOUND = ErrorCode("COMPANY_NOT_FOUND")

    /** 400: texto em branco, grande demais ou fora do formato (nome, telefone, preferências, observações…). */
    val GUESTS_DATA_INVALID = ErrorCode("GUESTS_DATA_INVALID")

    /** 400: código de país fora da ISO 3166-1 alfa-2 (nacionalidade, residência, país emissor). */
    val INVALID_COUNTRY = ErrorCode("INVALID_COUNTRY")

    /** 400: endereço no Brasil inválido (CEP, código IBGE de outra UF…). */
    val INVALID_ADDRESS = ErrorCode("INVALID_ADDRESS")

    /** 400: endereço completo informado para quem não reside no Brasil (o estrangeiro informa só o país). */
    val ADDRESS_REQUIRES_BRAZIL_RESIDENCE = ErrorCode("ADDRESS_REQUIRES_BRAZIL_RESIDENCE")

    /** 400: data de nascimento no futuro ou anterior a 1900. */
    val BIRTH_DATE_INVALID = ErrorCode("BIRTH_DATE_INVALID")

    /** 400: número de documento inválido para o tipo (CPF com dígito verificador errado, formato…). */
    val DOCUMENT_NUMBER_INVALID = ErrorCode("DOCUMENT_NUMBER_INVALID")

    /** 400: país emissor incompatível com o tipo (CPF, RG, CNH e RNM só BR; identidade estrangeira nunca BR). */
    val DOCUMENT_COUNTRY_INVALID = ErrorCode("DOCUMENT_COUNTRY_INVALID")

    /** 409: o perfil já tem um documento deste tipo e país emissor (retire o anterior para corrigir). */
    val DOCUMENT_ALREADY_RECORDED = ErrorCode("DOCUMENT_ALREADY_RECORDED")

    /** 400: código de deficiência fora do formato (letras maiúsculas, dígitos e `_`, até 60). */
    val SENSITIVE_DATA_INVALID = ErrorCode("SENSITIVE_DATA_INVALID")

    /**
     * 422: restrições alimentares são dado sensível e só se gravam com o consentimento do titular (RF-HOS-02),
     * que chega na E03 do M3; até lá o campo é recusado.
     */
    val DIETARY_RESTRICTIONS_REQUIRE_CONSENT = ErrorCode("DIETARY_RESTRICTIONS_REQUIRE_CONSENT")

    /** 400: a empresa informada no perfil não existe. */
    val UNKNOWN_COMPANY = ErrorCode("UNKNOWN_COMPANY")

    /** 422: a empresa informada no perfil está desativada. */
    val COMPANY_INACTIVE = ErrorCode("COMPANY_INACTIVE")

    /** 409: já existe uma empresa com este CNPJ (ativa ou desativada: reative-a). */
    val CNPJ_ALREADY_IN_USE = ErrorCode("CNPJ_ALREADY_IN_USE")
}
