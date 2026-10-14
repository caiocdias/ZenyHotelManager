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
    fun `iam depends only on shared`() {
        val iam = modules.getModuleByName("iam").orElseThrow()

        iam
            .getDirectDependencies(modules)
            .uniqueModules()
            .map { it.identifier.toString() }
            .toList() shouldBe
            listOf("shared")
    }

    @Test
    fun `no module other than staff depends on iam, only on the ports of shared`() {
        val dependents =
            modules
                .filter { it.identifier.toString() != "iam" }
                .filter { module ->
                    module.getDirectDependencies(modules).uniqueModules().anyMatch { it.identifier.toString() == "iam" }
                }.map { it.identifier.toString() }

        (dependents - "staff") shouldBe emptyList()
    }

    @Test
    fun `writes module documentation`() {
        Documenter(modules).writeDocumentation()

        Files.exists(Path.of("build", "spring-modulith-docs", "components.puml")) shouldBe true
    }
}
