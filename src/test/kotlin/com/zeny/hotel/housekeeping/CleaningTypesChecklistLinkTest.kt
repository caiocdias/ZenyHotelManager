package com.zeny.hotel.housekeeping

import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistsTestSupport
import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.CLEANING_TYPES
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * O vínculo do tipo de limpeza ao modelo de checklist (M6 E01, Task 2; D1, H16): inexistente é `400`; contexto
 * incompatível, modelo inativo e não publicado são `422`; só o momento do vínculo confere (Review Focus 3).
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class CleaningTypesChecklistLinkTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
) {
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)
    private val checklists = ChecklistsTestSupport(iam)

    private fun publishedTemplate(context: ChecklistContext = ChecklistContext.HOUSEKEEPING): UUID =
        checklists.create(checklists.templateBody(context = context)).also { checklists.publish(it) }

    @Test
    fun `a valid template is linked on create and on update, and null removes the link`() {
        val template = publishedTemplate()
        val id = housekeeping.createCleaningType(housekeeping.cleaningTypeBody(checklistTemplateId = template))
        read(id)["checklistTemplateId"].asString() shouldBe template.toString()

        val other = publishedTemplate()
        val etag = housekeeping.etagOf("$CLEANING_TYPES/$id")
        val updated = admin(PUT, "$CLEANING_TYPES/$id", body(other), etag)
        with(iam) { updated.ok()["checklistTemplateId"].asString() } shouldBe other.toString()

        val cleared = admin(PUT, "$CLEANING_TYPES/$id", body(null), housekeeping.etagOf("$CLEANING_TYPES/$id"))
        with(iam) { cleared.ok()["checklistTemplateId"].isNull } shouldBe true
        read(id)["checklistTemplateId"].isNull shouldBe true
    }

    @Test
    fun `an unknown template is 400 with the code, on create and on update, and nothing is stored`() {
        val code = housekeeping.uniqueCode("ul")
        with(iam) {
            admin(POST, CLEANING_TYPES, body(UUID.randomUUID(), code))
                .problem(400, "CLEANING_TYPE_UNKNOWN_CHECKLIST_TEMPLATE")
        }
        housekeeping.countByCode(code.uppercase()) shouldBe 0

        val id = housekeeping.createCleaningType()
        with(iam) {
            admin(PUT, "$CLEANING_TYPES/$id", body(UUID.randomUUID()), housekeeping.etagOf("$CLEANING_TYPES/$id"))
                .problem(400, "CLEANING_TYPE_UNKNOWN_CHECKLIST_TEMPLATE")
        }
    }

    @Test
    fun `a template of another context is 422`() {
        val template = publishedTemplate(ChecklistContext.EVENT)
        with(iam) {
            admin(POST, CLEANING_TYPES, body(template)).problem(422, "CLEANING_TYPE_CHECKLIST_CONTEXT_INCOMPATIBLE")
        }
        val id = housekeeping.createCleaningType()
        with(iam) {
            admin(PUT, "$CLEANING_TYPES/$id", body(template), housekeeping.etagOf("$CLEANING_TYPES/$id"))
                .problem(422, "CLEANING_TYPE_CHECKLIST_CONTEXT_INCOMPATIBLE")
        }
    }

    @Test
    fun `an inactive template is 422`() {
        val template = publishedTemplate()
        deactivate(template)
        with(iam) {
            admin(POST, CLEANING_TYPES, body(template)).problem(422, "CLEANING_TYPE_CHECKLIST_TEMPLATE_INACTIVE")
        }
        val id = housekeeping.createCleaningType()
        with(iam) {
            admin(PUT, "$CLEANING_TYPES/$id", body(template), housekeeping.etagOf("$CLEANING_TYPES/$id"))
                .problem(422, "CLEANING_TYPE_CHECKLIST_TEMPLATE_INACTIVE")
        }
    }

    @Test
    fun `a template never published is 422`() {
        val template = checklists.create()
        with(iam) {
            admin(POST, CLEANING_TYPES, body(template)).problem(422, "CLEANING_TYPE_CHECKLIST_TEMPLATE_NOT_PUBLISHED")
        }
        val id = housekeeping.createCleaningType()
        with(iam) {
            admin(PUT, "$CLEANING_TYPES/$id", body(template), housekeeping.etagOf("$CLEANING_TYPES/$id"))
                .problem(422, "CLEANING_TYPE_CHECKLIST_TEMPLATE_NOT_PUBLISHED")
        }
    }

    @Test
    fun `a linked template deactivated later stays linked, the read does not fail and an unrelated edit is accepted`() {
        val template = publishedTemplate()
        val id = housekeeping.createCleaningType(housekeeping.cleaningTypeBody(checklistTemplateId = template))
        deactivate(template)

        read(id)["checklistTemplateId"].asString() shouldBe template.toString()
        val edited =
            admin(PUT, "$CLEANING_TYPES/$id", body(template, name = "Outro nome ${UUID.randomUUID()}"), etag(id))
        with(iam) { edited.ok()["checklistTemplateId"].asString() } shouldBe template.toString()
    }

    private fun body(
        template: UUID?,
        code: String = housekeeping.uniqueCode(),
        name: String = housekeeping.rooms.uniqueName("Limpeza"),
    ) = housekeeping.cleaningTypeBody(code = code, name = name, checklistTemplateId = template)

    private fun etag(id: UUID) = housekeeping.etagOf("$CLEANING_TYPES/$id")

    private fun read(id: UUID) = with(iam) { call(GET, "$CLEANING_TYPES/$id", asAdmin()).ok() }

    private fun deactivate(template: UUID) {
        with(iam) { call(POST, "/api/v1/checklist-templates/$template/deactivate", asAdmin()).ok() }
    }

    private fun admin(
        method: HttpMethod,
        path: String,
        body: Any?,
        ifMatch: String? = null,
    ) = iam.call(method, path, iam.asAdmin(), body, ifMatch = ifMatch)
}
