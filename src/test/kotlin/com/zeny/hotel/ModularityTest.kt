package com.zeny.hotel

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifica os limites entre módulos (ARCHITECTURE §3, §4 e §17): grafo acíclico e
 * acesso só à API pública (raiz do pacote) de outro módulo. Também gera a documentação
 * dos módulos em `build/spring-modulith-docs`.
 */
class ModularityTest {
    private val modules = ApplicationModules.of(ZenyHotelApplication::class.java)

    @Test
    fun `module structure is valid`() {
        modules.verify()
    }

    @Test
    fun `shared is declared as a shared module`() {
        val shared = modules.getModuleByName("shared")

        shared.isPresent shouldBe true
        shared.get().displayName shouldBe "Shared kernel"
        modules.sharedModules.map { it.identifier.toString() } shouldContain "shared"
    }

    @Test
    fun `writes module documentation`() {
        Documenter(modules).writeDocumentation()

        Files.exists(Path.of("build", "spring-modulith-docs", "components.puml")) shouldBe true
    }
}
