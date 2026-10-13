package com.zeny.hotel.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.base.DescribedPredicate.not
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * Regras das camadas internas de um módulo (ARCHITECTURE §4), genéricas por convenção de
 * nome de subpacote: `<base>.<módulo>.{domain,application,infrastructure,web}`.
 *
 * - `domain` não depende de Spring nem de `application`, `infrastructure` ou `web`;
 *   de JPA/Hibernate só usa anotações (e os enums que elas recebem).
 * - `web` só fala com `application`: não usa `domain` nem `infrastructure`.
 * - Um módulo não usa as camadas internas de outro; só a raiz do pacote (API pública).
 *   Outros subpacotes (named interfaces) ficam a cargo do `ApplicationModules.verify()`.
 *
 * As regras recebem o pacote base para poderem ser exercitadas contra classes de exemplo
 * (ver `LayerRulesTest`). Enquanto não há módulos, as regras aceitam não encontrar classes.
 */
class LayerRules(
    private val basePackage: String,
) {
    private val domain = "$basePackage.*.domain.."
    private val application = "$basePackage.*.application.."
    private val infrastructure = "$basePackage.*.infrastructure.."
    private val web = "$basePackage.*.web.."

    val domainMustNotDependOnSpring: ArchRule =
        noClasses()
            .that()
            .resideInAPackage(domain)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework..")
            .allowEmptyShould(true)
            .because("domain não depende de Spring (ARCHITECTURE §4)")

    val domainMayUseOnlyPersistenceAnnotations: ArchRule =
        noClasses()
            .that()
            .resideInAPackage(domain)
            .should()
            .dependOnClassesThat(
                resideInAnyPackage("jakarta.persistence..", "org.hibernate..")
                    .and(not(ANNOTATION_OR_ENUM)),
            ).allowEmptyShould(true)
            .because("domain só tolera anotações JPA, não a API de persistência (ARCHITECTURE §4)")

    val domainMustNotDependOnOuterLayers: ArchRule =
        noClasses()
            .that()
            .resideInAPackage(domain)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(application, infrastructure, web)
            .allowEmptyShould(true)
            .because("domain é a camada mais interna e não conhece application, infrastructure nem web")

    val webTalksOnlyToApplication: ArchRule =
        noClasses()
            .that()
            .resideInAPackage(web)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(domain, infrastructure)
            .allowEmptyShould(true)
            .because("web só fala com application (ARCHITECTURE §4)")

    val modulesUseOnlyPublicApiOfOtherModules: ArchRule =
        classes()
            .that()
            .resideInAPackage("$basePackage..")
            .should(notAccessInternalLayersOfOtherModules())
            .allowEmptyShould(true)
            .because("outros módulos só usam o que está na raiz do pacote do módulo (ARCHITECTURE §4)")

    val all: List<ArchRule> =
        listOf(
            domainMustNotDependOnSpring,
            domainMayUseOnlyPersistenceAnnotations,
            domainMustNotDependOnOuterLayers,
            webTalksOnlyToApplication,
            modulesUseOnlyPublicApiOfOtherModules,
        )

    private fun moduleOf(javaClass: JavaClass): String? =
        javaClass.packageName
            .removePrefix("$basePackage.")
            .takeIf { javaClass.packageName.startsWith("$basePackage.") }
            ?.substringBefore('.')

    private fun internalLayerOf(javaClass: JavaClass): String? {
        val module = moduleOf(javaClass) ?: return null
        val rest = javaClass.packageName.removePrefix("$basePackage.$module")
        return INTERNAL_LAYERS.firstOrNull { rest == ".$it" || rest.startsWith(".$it.") }
    }

    private fun notAccessInternalLayersOfOtherModules(): ArchCondition<JavaClass> =
        object : ArchCondition<JavaClass>("not depend on internal layers of other modules") {
            override fun check(
                item: JavaClass,
                events: ConditionEvents,
            ) {
                val origin = moduleOf(item) ?: return
                item.directDependenciesFromSelf
                    .filter { dependency ->
                        val target = dependency.targetClass.baseComponentType
                        val targetModule = moduleOf(target)
                        targetModule != null && targetModule != origin && internalLayerOf(target) != null
                    }.forEach { dependency ->
                        events.add(SimpleConditionEvent.violated(dependency, dependency.description))
                    }
            }
        }

    private companion object {
        val INTERNAL_LAYERS = listOf("domain", "application", "infrastructure", "web")

        val ANNOTATION_OR_ENUM: DescribedPredicate<JavaClass> =
            DescribedPredicate.describe("annotation or enum") { it.isAnnotation || it.isEnum }
    }
}
