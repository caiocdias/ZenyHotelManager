package com.zeny.hotel.billing.domain

import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * O texto da notificação de diferença no fechamento de um turno de caixa (H14 do M3, M3 E07): o número do turno, o
 * nome do ponto de caixa (texto de configuração do gestor), a data operacional do fechamento e os valores; **nenhum
 * dado pessoal** (nem de hóspede, nem o nome do operador, que o `billing` não conhece: o turno o identifica). O
 * texto vai ao painel e ao push.
 */
object CashierDifferenceNotice {
    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")

    fun title(number: DocumentNumber): String = "Diferença no fechamento do caixa $number"

    fun body(
        number: DocumentNumber,
        cashPointName: String,
        closedOn: LocalDate,
        closing: CashierClosing,
    ): String {
        val lines = closing.differences.joinToString(" ") { "${it.method.label}: ${describe(it.difference)}." }
        return "O turno $number do ponto de caixa $cashPointName, na data operacional ${DATE.format(closedOn)}, " +
            "fechou com diferença. $lines Total: ${describe(closing.differenceTotal)}."
    }

    /** "sobra de R$ 5,00", "falta de R$ 10,00" ou "sem diferença". */
    fun describe(difference: Money): String =
        when {
            difference.isPositive() -> "sobra de ${format(difference)}"
            difference.isNegative() -> "falta de ${format(difference.abs())}"
            else -> "sem diferença"
        }

    private fun format(value: Money): String = "R$ " + String.format(PT_BR, "%,.2f", value.amount)
}
