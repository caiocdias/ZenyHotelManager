package com.zeny.hotel.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * M6 E23: os métodos `loadWithoutAuthorization` do painel do dia e das pendências existem para o arquivo do servidor,
 * que já se autorizou. Nada em `reporting.web` pode chamá-los: o controller passa sempre pelos métodos que conferem a
 * permissão.
 */
class ReportingWebAuthorizationTest {
    private val classes =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages(BASE)

    private val withoutAuthorization =
        object : DescribedPredicate<JavaCall<*>>("call a loadWithoutAuthorization method") {
            override fun test(call: JavaCall<*>) = call.target.name == "loadWithoutAuthorization"
        }

    @Test
    fun `nothing in reporting web calls loadWithoutAuthorization`() {
        noClasses()
            .that()
            .resideInAPackage("$BASE.reporting.web..")
            .should()
            .callCodeUnitWhere(withoutAuthorization)
            .check(classes)
    }

    @Test
    fun `the rule can fail, because the server archive in reporting application does call the methods`() {
        val callers =
            classes
                .filter { javaClass -> javaClass.packageName.startsWith("$BASE.reporting.application") }
                .filter { javaClass -> javaClass.methodCallsFromSelf.any { call -> withoutAuthorization.test(call) } }
        (callers.isNotEmpty()) shouldBe true
    }

    private companion object {
        const val BASE = "com.zeny.hotel"
    }
}
