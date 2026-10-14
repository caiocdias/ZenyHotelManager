package com.zeny.hotel.shared

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * CPF válido: 11 dígitos com os dois dígitos verificadores corretos e sem a sequência de um
 * só dígito repetido. Aceita a entrada com ou sem pontuação (`529.982.247-25`) e guarda só
 * os dígitos. Em JSON e no banco (`varchar(11)`), só os dígitos.
 *
 * Número de documento é dado sensível (ARCHITECTURE §8.3): se precisar ficar cifrado, grave
 * os dígitos com o [EncryptedStringConverter] em vez do [CpfConverter].
 */
class Cpf private constructor(
    /** Os 11 dígitos, sem pontuação. */
    val digits: String,
) {
    /** `529.982.247-25`. */
    fun formatted(): String = applyMask(digits, "###.###.###-##")

    override fun equals(other: Any?): Boolean = other is Cpf && digits == other.digits

    override fun hashCode(): Int = digits.hashCode()

    @JsonValue
    override fun toString(): String = digits

    companion object {
        private val SHAPE = Regex("""\d{11}|\d{3}\.\d{3}\.\d{3}-\d{2}""")
        private const val BASE_LENGTH = 9

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun of(value: String): Cpf {
            val digits = requireNotNull(normalize(value)) { "CPF inválido" }
            return Cpf(digits)
        }

        fun isValid(value: String): Boolean = normalize(value) != null

        /** Os 11 dígitos, se o CPF for válido; `null` se não for. */
        private fun normalize(value: String): String? {
            val trimmed = value.trim()
            if (!SHAPE.matches(trimmed)) return null
            val digits = trimmed.filter(Char::isDigit)
            val values = digits.map(Char::digitToInt)
            val valid =
                values.toSet().size > 1 &&
                    checkDigit(values.take(BASE_LENGTH)) == values[BASE_LENGTH] &&
                    checkDigit(values.take(BASE_LENGTH + 1)) == values[BASE_LENGTH + 1]
            return digits.takeIf { valid }
        }

        /** Módulo 11 com pesos decrescentes a partir de `tamanho + 1`. */
        private fun checkDigit(values: List<Int>): Int {
            val sum = values.mapIndexed { index, value -> value * (values.size + 1 - index) }.sum()
            return (sum * DECIMAL_BASE % CHECK_MODULUS) % DECIMAL_BASE
        }
    }
}

/**
 * CNPJ válido, numérico ou alfanumérico (IN RFB 2.229/2024, emitido desde julho de 2026):
 * 12 caracteres `[0-9A-Z]` seguidos de 2 dígitos verificadores numéricos, sem a sequência de
 * um só caractere repetido. Aceita a entrada com ou sem pontuação e em minúsculas, e guarda
 * os 14 caracteres em maiúsculas. Em JSON e no banco (`varchar(14)`), sem pontuação.
 */
class Cnpj private constructor(
    /** Os 14 caracteres, sem pontuação, em maiúsculas. */
    val value: String,
) {
    /** `12.ABC.345/01DE-35`. */
    fun formatted(): String = applyMask(value, "##.###.###/####-##")

    override fun equals(other: Any?): Boolean = other is Cnpj && value == other.value

    override fun hashCode(): Int = value.hashCode()

    @JsonValue
    override fun toString(): String = value

    companion object {
        private val SHAPE =
            Regex("""[0-9A-Z]{12}\d{2}|[0-9A-Z]{2}\.[0-9A-Z]{3}\.[0-9A-Z]{3}/[0-9A-Z]{4}-\d{2}""")
        private const val BASE_LENGTH = 12
        private val FIRST_WEIGHTS = listOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
        private val SECOND_WEIGHTS = listOf(6) + FIRST_WEIGHTS

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun of(value: String): Cnpj {
            val normalized = requireNotNull(normalize(value)) { "CNPJ inválido" }
            return Cnpj(normalized)
        }

        fun isValid(value: String): Boolean = normalize(value) != null

        /** Os 14 caracteres, se o CNPJ for válido; `null` se não for. */
        private fun normalize(value: String): String? {
            val trimmed = value.trim().uppercase()
            if (!SHAPE.matches(trimmed)) return null
            val chars = trimmed.filter(Char::isLetterOrDigit)
            // Valor de cada caractere: código ASCII menos 48 ('0'..'9' → 0..9, 'A' → 17…).
            val values = chars.map { it.code - '0'.code }
            val valid =
                chars.toSet().size > 1 &&
                    checkDigit(values.take(BASE_LENGTH), FIRST_WEIGHTS) == values[BASE_LENGTH] &&
                    checkDigit(values.take(BASE_LENGTH + 1), SECOND_WEIGHTS) == values[BASE_LENGTH + 1]
            return chars.takeIf { valid }
        }

        private fun checkDigit(
            values: List<Int>,
            weights: List<Int>,
        ): Int {
            val remainder = values.zip(weights).sumOf { (value, weight) -> value * weight } % CHECK_MODULUS
            return if (remainder < 2) 0 else CHECK_MODULUS - remainder
        }
    }
}

/** `Cpf` ↔ `varchar(11)` (só dígitos), aplicado automaticamente a todo atributo `Cpf` de entidade. */
@Converter(autoApply = true)
class CpfConverter : AttributeConverter<Cpf, String> {
    override fun convertToDatabaseColumn(attribute: Cpf?): String? = attribute?.digits

    override fun convertToEntityAttribute(dbData: String?): Cpf? = dbData?.let(Cpf::of)
}

/** `Cnpj` ↔ `varchar(14)`, aplicado automaticamente a todo atributo `Cnpj` de entidade. */
@Converter(autoApply = true)
class CnpjConverter : AttributeConverter<Cnpj, String> {
    override fun convertToDatabaseColumn(attribute: Cnpj?): String? = attribute?.value

    override fun convertToEntityAttribute(dbData: String?): Cnpj? = dbData?.let(Cnpj::of)
}

/** Módulo dos dígitos verificadores de CPF e CNPJ. */
private const val CHECK_MODULUS = 11
private const val DECIMAL_BASE = 10

/** Encaixa os caracteres de [value], em ordem, nas posições `#` de [mask]. */
internal fun applyMask(
    value: String,
    mask: String,
): String {
    val chars = value.iterator()
    return buildString { mask.forEach { append(if (it == '#') chars.next() else it) } }
}
