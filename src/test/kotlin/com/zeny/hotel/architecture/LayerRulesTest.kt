package com.zeny.hotel.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchRule
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Teste negativo das regras de camadas: prova que cada regra falha diante de uma violação
 * proposital (classes em `src/test/kotlin/archfixtures`) e que o código de exemplo correto
 * passa em todas. Sem isto, uma regra mal escrita passaria com qualquer código.
 */
class LayerRulesTest {
    @Test
    fun `compliant module layout passes every rule`() {
        val base = "archfixtures.compliant"
        val classes = ClassFileImporter().importPackages(base)
        val failures = LayerRules(base).all.filter { it.evaluate(classes).hasViolation() }

        failures.map { it.description }.shouldBeEmpty()
    }

    @Test
    fun `domain depending on Spring fails`() {
        assertViolates("archfixtures.domainspring", "SpringAwareBooking") { it.domainMustNotDependOnSpring }
    }

    @Test
    fun `domain depending on web fails`() {
        assertViolates("archfixtures.domainweb", "WebAwareBooking") { it.domainMustNotDependOnOuterLayers }
    }

    @Test
    fun `domain depending on application fails`() {
        assertViolates("archfixtures.domainapp", "CommandAwareBooking") { it.domainMustNotDependOnOuterLayers }
    }

    @Test
    fun `domain depending on infrastructure fails`() {
        assertViolates("archfixtures.domaininfra", "RecordAwareBooking") { it.domainMustNotDependOnOuterLayers }
    }

    @Test
    fun `domain using the persistence API beyond annotations fails`() {
        assertViolates("archfixtures.domainjpa", "PersistingBooking") { it.domainMayUseOnlyPersistenceAnnotations }
    }

    @Test
    fun `web depending on domain fails`() {
        assertViolates("archfixtures.webdomain", "LeakyController") { it.webTalksOnlyToApplication }
    }

    @Test
    fun `web depending on infrastructure fails`() {
        assertViolates("archfixtures.webinfra", "RepositoryController") { it.webTalksOnlyToApplication }
    }

    @Test
    fun `module using internal layer of another module fails`() {
        assertViolates("archfixtures.crossmodule", "ChargeService") { it.modulesUseOnlyPublicApiOfOtherModules }
    }

    private fun assertViolates(
        base: String,
        offendingClass: String,
        rule: (LayerRules) -> ArchRule,
    ) {
        val classes = ClassFileImporter().importPackages(base)
        val result = rule(LayerRules(base)).evaluate(classes)

        result.hasViolation() shouldBe true
        result.failureReport.details.joinToString("\n") shouldContain offendingClass
    }
}
