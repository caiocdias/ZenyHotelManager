package com.zeny.hotel.printing

import com.zeny.hotel.printing.domain.DocumentFormatter
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

class DocumentFormatterAttackTest {
    private val fmt = DocumentFormatter(ZoneId.of("America/Sao_Paulo"))

    @Test
    fun `dinheiro negativo leva o sinal antes do simbolo`() {
        assertThat(fmt.money(Money.of("-1234.5"))).isEqualTo("-R$ 1.234,50")
        assertThat(fmt.money(Money.of("-0.01"))).isEqualTo("-R$ 0,01")
    }

    @Test
    fun `zero nao leva sinal`() {
        assertThat(fmt.money(Money.of("0.00"))).isEqualTo("R$ 0,00")
        assertThat(fmt.money(Money.of("-0"))).isEqualTo("R$ 0,00")
    }

    @Test
    fun `dinheiro com muitos digitos agrupa por milhar sem perder precisao`() {
        assertThat(fmt.money(Money.of("123456789012.34"))).isEqualTo("R$ 123.456.789.012,34")
        assertThat(fmt.money(Money.of("999999999999.99"))).isEqualTo("R$ 999.999.999.999,99")
        assertThat(fmt.money(Money.of("1000"))).isEqualTo("R$ 1.000,00")
        assertThat(fmt.money(Money.of("999.99"))).isEqualTo("R$ 999,99")
    }

    @Test
    fun `percentual com casas e zeros finais`() {
        assertThat(fmt.percent(Percent.of("0"))).isEqualTo("0%")
        assertThat(fmt.percent(Percent.of("0.00"))).isEqualTo("0%")
        assertThat(fmt.percent(Percent.of("100"))).isEqualTo("100%")
        assertThat(fmt.percent(Percent.of("12.50"))).isEqualTo("12,5%")
        assertThat(fmt.percent(Percent.of("12.3456"))).isEqualTo("12,3456%")
        assertThat(fmt.percent(Percent.of("0.0001"))).isEqualTo("0,0001%")
    }

    @Test
    fun `instante na virada do dia usa o fuso do hotel`() {
        assertThat(fmt.instant(Instant.parse("2026-11-12T02:59:00Z"))).isEqualTo("11/11/2026 23:59")
        assertThat(fmt.instant(Instant.parse("2026-11-12T03:00:00Z"))).isEqualTo("12/11/2026 00:00")
        assertThat(fmt.instant(Instant.parse("2026-01-01T02:59:59Z"))).isEqualTo("31/12/2025 23:59")
    }

    @Test
    fun `instante respeita o fuso informado`() {
        val tokyo = DocumentFormatter(ZoneId.of("Asia/Tokyo"))
        assertThat(tokyo.instant(Instant.parse("2026-11-11T20:00:00Z"))).isEqualTo("12/11/2026 05:00")
    }

    @Test
    fun `instante dentro do horario de verao historico usa o deslocamento da data`() {
        // Sao Paulo teve horario de verao ate 2019: 2018-12-01 12:00Z = 10:00 (UTC-2)
        assertThat(fmt.instant(Instant.parse("2018-12-01T12:00:00Z"))).isEqualTo("01/12/2018 10:00")
    }
}
