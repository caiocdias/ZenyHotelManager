package com.zeny.hotel

import io.kotest.matchers.collections.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles

/** Sobe o contexto completo com o profile `local` (ainda sem banco: E02 o acrescenta). */
@SpringBootTest
@ActiveProfiles("local")
class ZenyHotelApplicationTest(
    @Autowired private val environment: Environment,
) {
    @Test
    fun `context starts with the local profile`() {
        environment.activeProfiles.toList() shouldContain "local"
    }
}
