package com.zeny.hotel.shared

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.Currency

/**
 * Valor em reais (RN-02, D16): `BigDecimal` com exatamente 2 casas, nunca ponto flutuante.
 *
 * - Só existe BRL: [of] com código de moeda recusa qualquer outra.
 * - A construção é exata: um valor com mais de 2 casas significativas é recusado, não
 *   arredondado em silêncio. Quem precisa arredondar usa [round], a **única** regra de
 *   arredondamento do sistema ([ROUNDING_MODE]); multiplicação e percentual passam por ela.
 * - Divisão não existe: repartir um valor é [allocate] ou [split], que distribuem os
 *   centavos sem perder nem criar nenhum.
 * - Em JSON é uma string decimal (`"123.45"`, ARCHITECTURE §7); no banco, `numeric(14,2)`
 *   pelo [MoneyConverter], aplicado automaticamente a todo atributo `Money` de entidade.
 */
@Suppress("TooManyFunctions") // é o tipo de valor central do domínio; a API cobre RN-02 inteira
class Money private constructor(
    amount: BigDecimal,
) : Comparable<Money> {
    /** O valor com escala 2. */
    val amount: BigDecimal = amount.setScale(SCALE, RoundingMode.UNNECESSARY)

    val currency: Currency get() = BRL

    operator fun plus(other: Money): Money = Money(amount.add(other.amount))

    operator fun minus(other: Money): Money = Money(amount.subtract(other.amount))

    operator fun unaryMinus(): Money = Money(amount.negate())

    /** Multiplicação por quantidade inteira: exata. */
    operator fun times(quantity: Long): Money = Money(amount.multiply(BigDecimal.valueOf(quantity)))

    operator fun times(quantity: Int): Money = times(quantity.toLong())

    /** Multiplicação por fator decimal (quantidade fracionária, preço por kg…): arredonda por [round]. */
    operator fun times(factor: BigDecimal): Money = round(amount.multiply(factor))

    /** O percentual deste valor, arredondado por [round]. */
    operator fun times(percent: Percent): Money = round(amount.multiply(percent.fraction))

    fun abs(): Money = if (isNegative()) -this else this

    fun isZero(): Boolean = amount.signum() == 0

    fun isPositive(): Boolean = amount.signum() > 0

    fun isNegative(): Boolean = amount.signum() < 0

    /** O valor em centavos. */
    fun toCents(): Long = amount.movePointRight(SCALE).longValueExact()

    /** Divide em [parts] partes iguais; as primeiras recebem os centavos que sobram. */
    fun split(parts: Int): List<Money> {
        require(parts > 0) { "O número de partes precisa ser positivo: $parts" }
        return allocate(List(parts) { BigDecimal.ONE })
    }

    fun allocate(vararg ratios: Int): List<Money> = allocate(ratios.map { BigDecimal.valueOf(it.toLong()) })

    fun allocate(vararg ratios: BigDecimal): List<Money> = allocate(ratios.toList())

    /**
     * Reparte o valor na proporção de [ratios] (pesos não negativos, ao menos um positivo).
     * A soma das partes é sempre exatamente o total e cada parte fica a menos de um centavo da
     * parte exata. Método do maior resto: cada parte recebe o piso da sua cota em centavos, e
     * os centavos que sobram vão, um a um, para as maiores frações (empate: a que vem antes).
     * Não há arredondamento aqui: só centavos inteiros sendo distribuídos.
     */
    fun allocate(ratios: List<BigDecimal>): List<Money> {
        require(ratios.isNotEmpty()) { "A alocação precisa de ao menos uma proporção" }
        require(ratios.none { it.signum() < 0 }) { "As proporções não podem ser negativas: $ratios" }
        val ratioSum = ratios.fold(BigDecimal.ZERO, BigDecimal::add)
        require(ratioSum.signum() > 0) { "Ao menos uma proporção precisa ser positiva: $ratios" }

        val totalCents = amount.movePointRight(SCALE).toBigIntegerExact()
        val absoluteCents = totalCents.abs()
        // Cotas exatas como frações inteiras: (centavos × peso) / soma, com pesos escalados a inteiros.
        val commonScale = ratios.maxOf { it.scale() }.coerceAtLeast(0)
        val weights = ratios.map { it.setScale(commonScale).unscaledValue() }
        val weightSum = ratioSum.setScale(commonScale).unscaledValue()
        val shares = weights.map { absoluteCents.multiply(it).divideAndRemainder(weightSum) }

        val leftover = absoluteCents - shares.fold(BigInteger.ZERO) { sum, share -> sum + share[0] }
        val receivers =
            shares.indices
                .sortedWith(compareByDescending<Int> { shares[it][1] }.thenBy { it })
                .take(leftover.intValueExact())
                .toSet()

        return shares.mapIndexed { index, share ->
            val cents = if (index in receivers) share[0] + BigInteger.ONE else share[0]
            ofCents(if (totalCents.signum() < 0) cents.negate() else cents)
        }
    }

    override fun compareTo(other: Money): Int = amount.compareTo(other.amount)

    override fun equals(other: Any?): Boolean = other is Money && amount == other.amount

    override fun hashCode(): Int = amount.hashCode()

    /** O valor em texto decimal simples, com 2 casas: `"1234.50"`. */
    @JsonValue
    override fun toString(): String = amount.toPlainString()

    companion object {
        const val SCALE = 2

        /** A única regra de arredondamento de dinheiro (RN-02); ajustá-la aqui ajusta o sistema todo. */
        val ROUNDING_MODE: RoundingMode = RoundingMode.HALF_EVEN

        val BRL: Currency = Currency.getInstance("BRL")

        val ZERO: Money = Money(BigDecimal.ZERO)

        /**
         * A regra única de arredondamento (RN-02): leva qualquer valor calculado a centavos
         * com [ROUNDING_MODE]. Nenhum outro código arredonda dinheiro.
         */
        fun round(value: BigDecimal): Money = Money(value.setScale(SCALE, ROUNDING_MODE))

        /** Valor exato: recusa mais de 2 casas significativas em vez de arredondar. */
        fun of(amount: BigDecimal): Money {
            val normalized = if (amount.scale() > SCALE) amount.stripTrailingZeros() else amount
            require(normalized.scale() <= SCALE) {
                "Valor monetário com mais de $SCALE casas decimais: ${amount.toPlainString()}"
            }
            return Money(normalized)
        }

        /** Lê um decimal simples (`"123.45"`, `"-7"`); notação científica e texto inválido são recusados. */
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun of(amount: String): Money {
            require(DECIMAL.matches(amount)) { "Valor monetário inválido: '$amount'" }
            return of(BigDecimal(amount))
        }

        /** Como [of], exigindo que a moeda seja BRL, a única aceita (RN-02). */
        fun of(
            amount: String,
            currencyCode: String,
        ): Money {
            require(currencyCode == BRL.currencyCode) { "Moeda não suportada: '$currencyCode'; só BRL" }
            return of(amount)
        }

        fun ofCents(cents: Long): Money = Money(BigDecimal.valueOf(cents, SCALE))

        fun ofCents(cents: BigInteger): Money = Money(BigDecimal(cents, SCALE))

        private val DECIMAL = Regex("""-?\d+(\.\d+)?""")
    }
}

/** Soma de valores; lista vazia soma zero. */
fun Iterable<Money>.sum(): Money = fold(Money.ZERO, Money::plus)

/** `Money` ↔ `numeric(14,2)`, aplicado automaticamente a todo atributo `Money` de entidade. */
@Converter(autoApply = true)
class MoneyConverter : AttributeConverter<Money, BigDecimal> {
    override fun convertToDatabaseColumn(attribute: Money?): BigDecimal? = attribute?.amount

    override fun convertToEntityAttribute(dbData: BigDecimal?): Money? = dbData?.let(Money::of)
}
