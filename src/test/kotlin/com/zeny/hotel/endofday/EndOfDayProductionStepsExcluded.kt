package com.zeny.hotel.endofday

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * Tira do contexto as etapas reais do fechamento (M6 E11): as definições de bean das classes do pacote
 * `com.zeny.hotel.endofday.application.steps`. Importada pelo contexto das etapas falsas ([EndOfDayTestConfiguration]),
 * pelo das transições e da persistência da E10 e pelo [EndOfDayNoStepsProductionTest], que não podem ter etapa real
 * registrada: ela agiria sobre todo o banco isolado.
 */
@TestConfiguration(proxyBeanMethods = false)
@Suppress("UtilityClassWithPublicConstructor") // configuração do Spring, com o @Bean estático no companion
class EndOfDayProductionStepsExcluded {
    companion object {
        /** Estático: o pós-processador de definições roda antes de qualquer bean da configuração existir. */
        @JvmStatic
        @Bean
        fun endOfDayProductionStepsRemover(): BeanDefinitionRegistryPostProcessor = ProductionStepsRemover()

        /** O pacote das etapas reais. */
        const val STEPS_PACKAGE = "com.zeny.hotel.endofday.application.steps."
    }
}

/** Remove as definições cuja classe está no [EndOfDayProductionStepsExcluded.STEPS_PACKAGE]. */
private class ProductionStepsRemover : BeanDefinitionRegistryPostProcessor {
    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        registry.beanDefinitionNames
            .filter {
                registry
                    .getBeanDefinition(it)
                    .beanClassName
                    ?.startsWith(EndOfDayProductionStepsExcluded.STEPS_PACKAGE) == true
            }.forEach(registry::removeBeanDefinition)
    }

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) = Unit
}
