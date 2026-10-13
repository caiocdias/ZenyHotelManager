package com.zeny.hotel.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.junit.jupiter.api.Test

/** Aplica as regras de camadas ao código de produção sob o pacote base. */
class LayerArchitectureTest {
    private val rules = LayerRules(BASE_PACKAGE)
    private val classes =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages(BASE_PACKAGE)

    @Test
    fun `domain does not depend on Spring`() {
        rules.domainMustNotDependOnSpring.check(classes)
    }

    @Test
    fun `domain uses only persistence annotations`() {
        rules.domainMayUseOnlyPersistenceAnnotations.check(classes)
    }

    @Test
    fun `domain does not depend on outer layers`() {
        rules.domainMustNotDependOnOuterLayers.check(classes)
    }

    @Test
    fun `web talks only to application`() {
        rules.webTalksOnlyToApplication.check(classes)
    }

    @Test
    fun `modules use only the public API of other modules`() {
        rules.modulesUseOnlyPublicApiOfOtherModules.check(classes)
    }

    private companion object {
        const val BASE_PACKAGE = "com.zeny.hotel"
    }
}
