package com.zeny.hotel.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.zeny.hotel.guests.web.CreateGuestRequest
import com.zeny.hotel.guests.web.GuestDocumentRequest
import com.zeny.hotel.guests.web.GuestProfileRequest
import com.zeny.hotel.iam.web.LimitRequest
import com.zeny.hotel.iam.web.RoleGrantRequest
import com.zeny.hotel.iam.web.RoleRequest
import com.zeny.hotel.notifications.web.NotificationPreferenceRequest
import com.zeny.hotel.notifications.web.NotificationPreferencesRequest
import com.zeny.hotel.shared.PermissionScope
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import jakarta.validation.Valid
import jakarta.validation.Validation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.lang.reflect.AnnotatedParameterizedType

/**
 * Validação em cascata dos itens de lista (H15 do M3): os DTOs usam `List<@Valid T>` (o `@Valid` no `List` é
 * obsoleto e o Hibernate Validator avisa `HV000271`), o Kotlin grava a anotação de uso de tipo no bytecode
 * (`-Xemit-jvm-type-annotations`) e um item inválido dentro da lista continua recusado, com o caminho do item.
 */
@ExtendWith(OutputCaptureExtension::class)
class ListCascadeValidationTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    private val productionClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.zeny.hotel")
            .filter {
                it.simpleName.endsWith("Request") || it.simpleName.endsWith("Dto") ||
                    it.simpleName.endsWith("Payload")
            }.map { it.reflect() }

    @Test
    fun `no production DTO uses the deprecated @Valid on a List, and the type argument carries it`(
        output: CapturedOutput,
    ) {
        productionClasses.forEach { validator.getConstraintsForClass(it) }

        output.all shouldNotContain "HV000271"
        val cascaded =
            productionClasses.flatMap { type ->
                type.declaredFields
                    .filter { field ->
                        val annotated = field.annotatedType as? AnnotatedParameterizedType
                        annotated?.annotatedActualTypeArguments?.any { it.isAnnotationPresent(Valid::class.java) } ==
                            true
                    }.map { field -> field.name }
            }
        // Os onze elementos que o boot listava no HV000271, mais os documentos do guests.
        cascaded shouldContainAll
            listOf(
                "grants",
                "limits",
                "applicability",
                "sections",
                "items",
                "options",
                "actions",
                "commands",
                "answers",
                "preferences",
                "childAgeBands",
                "documents",
            )
        productionClasses.flatMap { type ->
            type.declaredFields.filter {
                List::class.java.isAssignableFrom(it.type) &&
                    it.isAnnotationPresent(Valid::class.java)
            }
        } shouldContainExactlyInAnyOrder emptyList()
    }

    @Test
    fun `the deprecated form is still detected, so the check above sees the warning`(output: CapturedOutput) {
        validator.getConstraintsForClass(DeprecatedForm::class.java)

        output.all shouldContain "HV000271"
    }

    @Test
    fun `an invalid item inside a list is still refused, with the path of the item`() {
        val guest =
            CreateGuestRequest(
                profile = GuestProfileRequest(fullName = "Maria"),
                documents =
                    listOf(
                        GuestDocumentRequest(null, null, "52998224725"),
                        GuestDocumentRequest(null, null, " "),
                    ),
            )
        validator.validate(guest).map { it.propertyPath.toString() } shouldContainExactlyInAnyOrder
            listOf("documents[0].type", "documents[1].type", "documents[1].number")

        val role =
            RoleRequest(
                name = "Recepção",
                grants =
                    listOf(
                        RoleGrantRequest(" ", PermissionScope.ALL),
                        RoleGrantRequest("iam.users.read", PermissionScope.ALL, listOf(LimitRequest(key = " "))),
                    ),
            )
        validator.validate(role).map { it.propertyPath.toString() } shouldContainExactlyInAnyOrder
            listOf("grants[0].permission", "grants[1].limits[0].key")

        val preferences = NotificationPreferencesRequest(listOf(NotificationPreferenceRequest(null, true, true)))
        validator.validate(preferences).map { it.propertyPath.toString() } shouldContainExactlyInAnyOrder
            listOf("preferences[0].type")
    }

    /** A forma obsoleta, só para provar que o aviso é capturado. */
    data class DeprecatedForm(
        @field:Valid
        val items: List<DeprecatedItem> = emptyList(),
    )

    data class DeprecatedItem(
        @field:jakarta.validation.constraints.NotBlank
        val name: String = "",
    )
}
