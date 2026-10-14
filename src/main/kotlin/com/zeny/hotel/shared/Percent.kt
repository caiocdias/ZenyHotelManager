package com.zeny.hotel.shared

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Percentual em pontos percentuais (`12.5` = 12,5%) com exatamente 4 casas, no intervalo de
 * `numeric(7,4)` (ARCHITECTURE §11): de -999.9999 a 999.9999. A construção é exata: valor
 * com mais casas ou fora do intervalo é recusado. Aplicar a um valor ([of]) arredonda pela
 * regra única de [Money.round]. Em JSON é uma string decimal (`"12.5000"`).
 */
class Percent private constructor(
    value: BigDecimal,
) : Comparable<Percent> {
    /** O percentual com escala 4. */
    val value: BigDecimal = value.setScale(SCALE, RoundingMode.UNNECESSARY)

    /** A fração exata (`12.5` → `0.125000`). */
    val fraction: BigDecimal get() = value.movePointLeft(2)

    /** Este percentual de [money], arredondado pela regra única de [Money.round]. */
    fun of(money: Money): Money = money * this

    override fun compareTo(other: Percent): Int = value.compareTo(other.value)

    override fun equals(other: Any?): Boolean = other is Percent && value == other.value

    override fun hashCode(): Int = value.hashCode()

    @JsonValue
    override fun toString(): String = value.toPlainString()

    companion object {
        const val SCALE = 4

        private val LIMIT = BigDecimal("999.9999")

        val ZERO: Percent = Percent(BigDecimal.ZERO)

        val HUNDRED: Percent = Percent(BigDecimal(100))

        fun of(value: BigDecimal): Percent {
            val normalized = if (value.scale() > SCALE) value.stripTrailingZeros() else value
            require(normalized.scale() <= SCALE) {
                "Percentual com mais de $SCALE casas decimais: ${value.toPlainString()}"
            }
            require(normalized.abs() <= LIMIT) { "Percentual fora de numeric(7,4): ${value.toPlainString()}" }
            return Percent(normalized)
        }

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun of(value: String): Percent {
            require(DECIMAL.matches(value)) { "Percentual inválido: '$value'" }
            return of(BigDecimal(value))
        }

        private val DECIMAL = Regex("""-?\d+(\.\d+)?""")
    }
}

/** `Percent` ↔ `numeric(7,4)`, aplicado automaticamente a todo atributo `Percent` de entidade. */
@Converter(autoApply = true)
class PercentConverter : AttributeConverter<Percent, BigDecimal> {
    override fun convertToDatabaseColumn(attribute: Percent?): BigDecimal? = attribute?.value

    override fun convertToEntityAttribute(dbData: BigDecimal?): Percent? = dbData?.let(Percent::of)
}
