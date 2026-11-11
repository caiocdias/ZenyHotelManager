package com.zeny.hotel.printing.domain

import com.zeny.hotel.shared.Cnpj
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Formatação pt-BR dos documentos (D-E11-3); exposta aos templates como `fmt`. Nulo vira texto vazio. */
class DocumentFormatter(
    private val zone: ZoneId,
) {
    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val instantFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    private val symbols = DecimalFormatSymbols(Locale.of("pt", "BR"))

    fun date(value: LocalDate?): String = value?.format(dateFormat).orEmpty()

    fun time(value: LocalTime?): String = value?.format(timeFormat).orEmpty()

    fun instant(value: Instant?): String = value?.atZone(zone)?.format(instantFormat).orEmpty()

    fun money(value: Money?): String {
        if (value == null) return ""
        val digits = DecimalFormat("#,##0.00", symbols).format(value.amount.abs())
        return (if (value.isNegative()) "-R$ " else "R$ ") + digits
    }

    fun percent(value: Percent?): String {
        if (value == null) return ""
        return value.value
            .stripTrailingZeros()
            .toPlainString()
            .replace('.', symbols.decimalSeparator) + "%"
    }

    fun cnpj(value: Cnpj?): String = value?.formatted().orEmpty()
}
