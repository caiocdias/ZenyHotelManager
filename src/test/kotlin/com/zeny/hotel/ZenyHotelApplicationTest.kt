package com.zeny.hotel

import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor

/** Sobe o contexto completo com o profile `local`, ligado ao PostgreSQL do Testcontainers. */
@IntegrationTest
class ZenyHotelApplicationTest(
    @Autowired private val environment: Environment,
    @Autowired private val context: ApplicationContext,
) {
    @Test
    fun `context starts with the local profile`() {
        environment.activeProfiles.toList() shouldContain "local"
    }

    @Test
    fun `open session in view is disabled`() {
        environment.getProperty("spring.jpa.open-in-view", Boolean::class.java) shouldBe false
        // Com o Spring MVC no classpath, o Boot registraria este interceptor se a propriedade
        // estivesse ligada: é ele que deixaria a sessão do Hibernate aberta durante a view.
        context.getBeanNamesForType(OpenEntityManagerInViewInterceptor::class.java).toList().shouldBeEmpty()
    }
}
