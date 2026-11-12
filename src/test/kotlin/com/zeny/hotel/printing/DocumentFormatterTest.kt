package com.zeny.hotel.printing

import com.zeny.hotel.printing.domain.DocumentFormatter
import com.zeny.hotel.shared.Cnpj
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class DocumentFormatterTest {
    private val fmt = DocumentFormatter(ZoneId.of("America/Sao_Paulo"))

    @Test
    fun `formata dinheiro`() {
        assertThat(fmt.money(Money.of("1234.5"))).isEqualTo("R$ 1.234,50")
        assertThat(fmt.money(Money.of("0"))).isEqualTo("R$ 0,00")
    }

    @Test
    fun `formata instante no fuso do hotel`() {
        assertThat(fmt.instant(Instant.parse("2026-11-11T13:05:00Z"))).isEqualTo("11/11/2026 10:05")
    }

    @Test
    fun `formata data e hora`() {
        assertThat(fmt.date(LocalDate.of(2026, 1, 5))).isEqualTo("05/01/2026")
        assertThat(fmt.time(LocalTime.of(7, 5))).isEqualTo("07:05")
    }

    @Test
    fun `formata percentual`() {
        assertThat(fmt.percent(Percent.of("10"))).isEqualTo("10%")
        assertThat(fmt.percent(Percent.of("12.5"))).isEqualTo("12,5%")
    }

    @Test
    fun `formata cnpj`() {
        assertThat(fmt.cnpj(Cnpj.of("11222333000181"))).isEqualTo("11.222.333/0001-81")
    }

    @Test
    fun `nulo vira vazio`() {
        assertThat(fmt.date(null)).isEmpty()
        assertThat(fmt.time(null)).isEmpty()
        assertThat(fmt.instant(null)).isEmpty()
        assertThat(fmt.money(null)).isEmpty()
        assertThat(fmt.percent(null)).isEmpty()
        assertThat(fmt.cnpj(null)).isEmpty()
    }
}
