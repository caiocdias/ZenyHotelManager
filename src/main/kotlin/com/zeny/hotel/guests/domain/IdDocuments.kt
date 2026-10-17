package com.zeny.hotel.guests.domain

import com.zeny.hotel.guests.DocumentType
import com.zeny.hotel.guests.GuestsErrorCodes
import com.zeny.hotel.shared.Cpf
import java.util.Locale

/**
 * Um documento já validado e normalizado: o tipo, o país emissor e o número na forma canônica (CPF só com os
 * dígitos; os demais em maiúsculas, sem espaços nem pontuação). [toString] nunca mostra o número.
 */
class NormalizedDocument internal constructor(
    val type: DocumentType,
    val issuingCountry: String,
    val number: String,
) {
    /** O texto de que se calcula o índice cego (H8): tipo, país e número normalizado. */
    val indexInput: String get() = IdDocuments.indexInput(type, issuingCountry, number)

    /** Os últimos caracteres do número, guardados em claro para a máscara (H9). */
    val suffix: String get() = IdDocuments.suffix(number)

    /** O número mascarado (H9): só os últimos caracteres. */
    val masked: String get() = IdDocuments.mask(number)

    override fun toString(): String = "NormalizedDocument(type=$type, issuingCountry=$issuingCountry)"
}

/**
 * Regras dos documentos de identidade (SPEC §8.2.4, H8, H9): validação por tipo, país emissor, normalização e
 * máscara. As recusas citam o campo e nunca repetem o número.
 */
object IdDocuments {
    /** Finalidade do índice cego do número de documento (`info` da derivação da chave). */
    const val INDEX_PURPOSE = "guests.document-number"

    private val CPF_SHAPE = Regex("""\d{11}|\d{3}\.\d{3}\.\d{3}-\d{2}""")
    private val ACCEPTED_CHARS = Regex("""[A-Za-z0-9 .\-/]+""")
    private val NOT_ALPHANUMERIC = Regex("""[^A-Z0-9]""")
    private val CNH = Regex("""\d{11}""")
    private const val MIN_LENGTH = 4
    private const val MAX_LENGTH = 20
    private const val VISIBLE_LONG = 4
    private const val VISIBLE_SHORT = 2
    private const val SHORT_NUMBER = 8

    /**
     * Valida e normaliza. [numberField] e [countryField] são os caminhos dos campos nas recusas
     * (`documents[0].number`). País: documento brasileiro é sempre `BR` (outro país: `400
     * DOCUMENT_COUNTRY_INVALID`; ausente: `BR`); passaporte e identidade estrangeira exigem o país, e a
     * identidade estrangeira não aceita `BR`.
     */
    fun normalize(
        type: DocumentType,
        issuingCountry: String?,
        number: String,
        numberField: String = "number",
        countryField: String = "issuingCountry",
    ): NormalizedDocument {
        val country = country(type, issuingCountry, countryField)
        return NormalizedDocument(type, country, number(type, number, numberField))
    }

    /** O que precede o sufixo na máscara. */
    const val MASK_PREFIX = "***"

    /** Os caracteres visíveis na máscara: os 4 últimos (2, se o número tiver menos de 8). */
    fun suffix(number: String): String =
        number.takeLast(
            if (number.length <
                SHORT_NUMBER
            ) {
                VISIBLE_SHORT
            } else {
                VISIBLE_LONG
            },
        )

    /** O número mascarado: `***` e o [suffix]. */
    fun mask(number: String): String = MASK_PREFIX + suffix(number)

    /** O texto de que se calcula o índice cego (H8): tipo, país e número normalizado, unidos por `:`. */
    fun indexInput(
        type: DocumentType,
        issuingCountry: String,
        number: String,
    ): String = "${type.name}:$issuingCountry:$number"

    private fun country(
        type: DocumentType,
        issuingCountry: String?,
        field: String,
    ): String {
        val country = GuestsRules.country(field, issuingCountry)
        val refusal =
            when {
                type.brazilian && country != null && country != BRAZIL -> "Documento brasileiro: o país emissor é BR."
                !type.brazilian && country == null -> "Informe o país emissor do documento."
                type == DocumentType.FOREIGN_ID && country == BRAZIL -> "Identidade estrangeira não é do Brasil."
                else -> null
            }
        if (refusal != null) throw GuestsRules.invalid(GuestsErrorCodes.DOCUMENT_COUNTRY_INVALID, field, refusal)
        return country ?: BRAZIL
    }

    private fun number(
        type: DocumentType,
        value: String,
        field: String,
    ): String {
        val trimmed = value.trim()
        if (type == DocumentType.CPF) {
            if (!CPF_SHAPE.matches(trimmed) || !Cpf.isValid(trimmed)) {
                rejectNumber(field, "CPF inválido: confira os 11 dígitos.")
            }
            return Cpf.of(trimmed).digits
        }
        if (!ACCEPTED_CHARS.matches(trimmed)) {
            rejectNumber(field, "Use só letras, dígitos, espaços, ponto, hífen e barra no número.")
        }
        val normalized = trimmed.uppercase(Locale.ROOT).replace(NOT_ALPHANUMERIC, "")
        if (type == DocumentType.CNH && !CNH.matches(normalized)) {
            rejectNumber(field, "CNH inválida: informe os 11 dígitos do registro.")
        }
        if (normalized.length !in MIN_LENGTH..MAX_LENGTH || normalized.none(Char::isDigit)) {
            rejectNumber(field, "Número de documento inválido: de 4 a 20 letras e dígitos, com algum dígito.")
        }
        return normalized
    }

    /** `400 DOCUMENT_NUMBER_INVALID` no campo; a mensagem nunca repete o número. */
    private fun rejectNumber(
        field: String,
        message: String,
    ): Nothing = throw GuestsRules.invalid(GuestsErrorCodes.DOCUMENT_NUMBER_INVALID, field, message)

    private const val BRAZIL = "BR"
}
