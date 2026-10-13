package com.zeny.hotel

import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment

/** Sobe o contexto completo com o profile `local`, ligado ao PostgreSQL do Testcontainers. */
@IntegrationTest
class ZenyHotelApplicationTest(
    @Autowired private val environment: Environment,
) {
    @Test
    fun `context starts with the local profile`() {
        environment.activeProfiles.toList() shouldContain "local"
    }

    @Test
    fun `open session in view is disabled`() {
        environment.getProperty("spring.jpa.open-in-view", Boolean::class.java) shouldBe false
    }
}
