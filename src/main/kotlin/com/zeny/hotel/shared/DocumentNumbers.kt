package com.zeny.hotel.shared

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * Número legível de um documento (D15): prefixo, ano e sequência com ao menos 6 dígitos,
 * como `R-2026-000123`. É só para pessoas lerem e buscarem; a identidade continua sendo o
 * UUIDv7. Em JSON e no banco (`varchar`), o texto.
 */
data class DocumentNumber(
    val prefix: String,
    val year: Int,
    val sequence: Long,
) {
    init {
        require(PREFIX.matches(prefix)) { "Prefixo de número legível inválido: '$prefix'" }
        require(year in YEARS) { "Ano de número legível fora de $YEARS: $year" }
        require(sequence > 0) { "Sequência de número legível precisa ser positiva: $sequence" }
    }

    @JsonValue
    override fun toString(): String = "$prefix-$year-${sequence.toString().padStart(SEQUENCE_DIGITS, '0')}"

    companion object {
        internal val PREFIX = Regex("[A-Z]{1,5}")
        private val YEARS = 2000..9999
        private const val SEQUENCE_DIGITS = 6
        private val TEXT = Regex("""([A-Z]{1,5})-(\d{4})-(\d{6,})""")

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(text: String): DocumentNumber {
            val match = requireNotNull(TEXT.matchEntire(text)) { "Número legível inválido: '$text'" }
            val (prefix, year, sequence) = match.destructured
            return DocumentNumber(prefix, year.toInt(), sequence.toLong())
        }
    }
}

/**
 * Tipo de documento com número legível: o [prefix] e a *sequence* do banco que o numera,
 * qualificada com o schema do módulo dono (`reservations.reservation_number_seq`). O módulo
 * cria a sequence na sua migração (`CREATE SEQUENCE reservations.reservation_number_seq;`).
 */
data class DocumentNumberType(
    val prefix: String,
    val sequence: String,
) {
    init {
        require(DocumentNumber.PREFIX.matches(prefix)) { "Prefixo de número legível inválido: '$prefix'" }
        require(QUALIFIED_NAME.matches(sequence)) {
            "A sequence precisa ser um nome qualificado com o schema (schema.nome): '$sequence'"
        }
    }

    private companion object {
        val QUALIFIED_NAME = Regex("[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*")
    }
}

/**
 * Gera o próximo número legível de um tipo de documento a partir da sequence dele.
 *
 * - O ano vem de quem chama (normalmente o ano da data operacional, RN-01); a sequência não
 *   reinicia por ano.
 * - Sequences não voltam atrás: uma transação desfeita deixa um buraco na numeração. Número
 *   sem buracos (fiscal) não sai daqui.
 */
interface DocumentNumberGenerator {
    fun next(
        type: DocumentNumberType,
        year: Int,
    ): DocumentNumber
}

/** `DocumentNumber` ↔ `varchar` (`R-2026-000123`), aplicado automaticamente a todo atributo do tipo. */
@Converter(autoApply = true)
class DocumentNumberConverter : AttributeConverter<DocumentNumber, String> {
    override fun convertToDatabaseColumn(attribute: DocumentNumber?): String? = attribute?.toString()

    override fun convertToEntityAttribute(dbData: String?): DocumentNumber? = dbData?.let(DocumentNumber::parse)
}
