package com.zeny.hotel.endofday

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.testsupport.MutableClockConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/**
 * O contexto dos testes das etapas reais do fechamento (M6 E11), com o `@EndOfDayIntegrationTest`: as etapas de
 * produção, sem etapa falsa, mais o relógio mutável, o apoio do `iam`, as sondas do inventário, a [BillingPostProbe] e a [RoomsMarkProbe].
 *
 * As etapas reais agem sobre todo o banco isolado, inclusive o que os testes anteriores deixaram: cada teste fecha a
 * data corrente (`EndOfDayTestRuns.settle`) e olha só os próprios dados.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(MutableClockConfiguration::class, IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("UtilityClassWithPublicConstructor") // configuração do Spring, com o @Bean estático no companion
class EndOfDayRealStepsTestConfiguration {
    companion object {
        /** Estático: um pós-processador de beans existe antes dos beans que ele processa. */
        @JvmStatic
        @Bean
        fun billingPostProbe(): BillingPostProbe = BillingPostProbe()

        /** Estático, como a [billingPostProbe]: embrulha o `RoomsApi`. */
        @JvmStatic
        @Bean
        fun roomsMarkProbe(): RoomsMarkProbe = RoomsMarkProbe()
    }
}
