package com.zeny.hotel.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.base.DescribedPredicate.not
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.domain.properties.HasAnnotations
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.zeny.hotel.shared.Audited
import org.springframework.transaction.annotation.Transactional
import jakarta.transaction.Transactional as JakartaTransactional

/**
 * Regras das camadas internas de um módulo (ARCHITECTURE §4), genéricas por convenção de
 * nome de subpacote: `<base>.<módulo>.{domain,application,infrastructure,web}`.
 *
 * - `domain` não depende de Spring nem de `application`, `infrastructure` ou `web`;
 *   de JPA/Hibernate só usa anotações (e os enums que elas recebem).
 * - `web` só fala com `application`: não usa `domain` nem `infrastructure`.
 * - Um módulo não usa as camadas internas de outro; só a raiz do pacote (API pública).
 *   Outros subpacotes (named interfaces) ficam a cargo do `ApplicationModules.verify()`.
 * - Todo caso de uso de escrita de `application` (método público `@Transactional` do Spring sem
 *   `readOnly` ou `@jakarta.transaction.Transactional`, no método ou na classe) leva `@Audited`
 *   (RNF-11, ARCHITECTURE §5).
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

    val writeUseCasesAreAudited: ArchRule =
        methods()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage(application)
            .and(TRANSACTIONAL_WRITE)
            .should()
            .beAnnotatedWith(Audited::class.java)
            .allowEmptyShould(true)
            .because("toda escrita é auditada na mesma transação (RNF-11, ARCHITECTURE §5)")

    val all: List<ArchRule> =
        listOf(
            domainMustNotDependOnSpring,
            domainMayUseOnlyPersistenceAnnotations,
            domainMustNotDependOnOuterLayers,
            webTalksOnlyToApplication,
            modulesUseOnlyPublicApiOfOtherModules,
            writeUseCasesAreAudited,
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

        /**
         * Método público que abre uma transação de escrita: `@Transactional` do Spring sem `readOnly`
         * ou `@jakarta.transaction.Transactional` (que não tem modo só de leitura e o Spring também
         * honra), no método ou, sem anotação no método, na classe. Métodos sintéticos (do compilador)
         * ficam fora.
         */
        val TRANSACTIONAL_WRITE: DescribedPredicate<JavaMethod> =
            DescribedPredicate.describe("are public write transactions (@Transactional without readOnly)") { method ->
                JavaModifier.PUBLIC in method.modifiers &&
                    JavaModifier.SYNTHETIC !in method.modifiers &&
                    (opensWriteTransaction(method) ?: opensWriteTransaction(method.owner) ?: false)
            }

        /** Se o elemento abre uma transação de escrita; `null` sem anotação de transação nele. */
        private fun opensWriteTransaction(element: HasAnnotations<*>): Boolean? {
            val spring = element.tryGetAnnotationOfType(Transactional::class.java).orElse(null)
            return when {
                spring != null -> !spring.readOnly
                element.isAnnotatedWith(JakartaTransactional::class.java) -> true
                else -> null
            }
        }
    }
}
