package com.zeny.hotel.housekeeping

import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistsTestSupport
import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.CLEANING_TYPES
import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.definition
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * O vínculo ao modelo de checklist pelo lado das recusas (M6 E01, Task 2; D1, H16): um `PUT` recusado (modelo
 * inexistente, de outro contexto, inativo ou nunca publicado) não toca no tipo, que mantém o vínculo, o resto da
 * configuração, o `ETag` e a trilha; e quem não mantém recebe `403` antes de qualquer `400`/`422` do modelo, que
 * revelaria se ele existe ou em que estado está.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class CleaningTypesChecklistLinkRefusalTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
) {
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)
    private val checklists = ChecklistsTestSupport(iam)

    @Test
    fun `a refused PUT on the link keeps the current link, the configuration, the ETag and the trail`(): Unit =
        with(iam) {
            val linked = publishedTemplate()
            val id = housekeeping.createCleaningType(housekeeping.cleaningTypeBody(checklistTemplateId = linked))
            val path = "$CLEANING_TYPES/$id"
            val before = call(GET, path, asAdmin()).ok()

            val refusals =
                listOf(
                    UUID.randomUUID() to (400 to "CLEANING_TYPE_UNKNOWN_CHECKLIST_TEMPLATE"),
                    publishedTemplate(
                        ChecklistContext.EVENT,
                    ) to (422 to "CLEANING_TYPE_CHECKLIST_CONTEXT_INCOMPATIBLE"),
                    publishedTemplate().also(::deactivate) to (422 to "CLEANING_TYPE_CHECKLIST_TEMPLATE_INACTIVE"),
                    checklists.create() to (422 to "CLEANING_TYPE_CHECKLIST_TEMPLATE_NOT_PUBLISHED"),
                )
            refusals.forEach { (template, expected) ->
                withClue(expected.second) {
                    // O corpo muda também o nome e a prioridade: nada disso pode ficar gravado.
                    val body =
                        housekeeping.cleaningTypeBody(
                            code = before["code"].asString(),
                            name = "Outro nome ${UUID.randomUUID()}",
                            defaultPriority = 5,
                            checklistTemplateId = template,
                        )
                    call(PUT, path, asAdmin(), body, ifMatch = "\"0\"").problem(expected.first, expected.second)

                    val after = call(GET, path, asAdmin())
                    after.getHeader("ETag") shouldBe "\"0\""
                    val read = json(after)
                    read["checklistTemplateId"].asString() shouldBe linked.toString()
                    read["name"].asString() shouldBe before["name"].asString()
                    read["defaultPriority"].asInt() shouldBe before["defaultPriority"].asInt()
                    read["updatedAt"].asString() shouldBe before["updatedAt"].asString()
                }
            }
            housekeeping.auditActions("housekeeping.cleaning-type", id) shouldBe
                listOf("housekeeping.cleaning-type.create")
        }

    @Test
    fun `without manage, an unknown or unlinkable template is 403 and never 400 or 422, on POST and on PUT`(): Unit =
        with(iam) {
            val reader = asTestUser(createUserWith(TestGrant(definition(HousekeepingPermissions.CONFIG_READ), ALL)))
            val nobody = asTestUser(createUser())
            val existing = housekeeping.createCleaningType()
            val existingCode = call(GET, "$CLEANING_TYPES/$existing", asAdmin()).ok()["code"].asString()
            val templates =
                listOf(
                    UUID.randomUUID(),
                    publishedTemplate(ChecklistContext.EVENT),
                    publishedTemplate().also(::deactivate),
                    checklists.create(),
                )
            val code = housekeeping.uniqueCode("NT")

            listOf(reader, nobody).forEachIndexed { index, user ->
                templates.forEach { template ->
                    withClue("usuário $index, modelo $template") {
                        call(
                            POST,
                            CLEANING_TYPES,
                            user,
                            housekeeping.cleaningTypeBody(code = code, checklistTemplateId = template),
                        ).problem(403, "PERMISSION_DENIED")
                        call(
                            PUT,
                            "$CLEANING_TYPES/$existing",
                            user,
                            housekeeping.cleaningTypeBody(code = existingCode, checklistTemplateId = template),
                            ifMatch = "\"0\"",
                        ).problem(403, "PERMISSION_DENIED")
                    }
                }
            }
            housekeeping.countByCode(code) shouldBe 0
            housekeeping.etagOf("$CLEANING_TYPES/$existing") shouldBe "\"0\""
        }

    private fun publishedTemplate(context: ChecklistContext = ChecklistContext.HOUSEKEEPING): UUID =
        checklists.create(checklists.templateBody(context = context)).also { checklists.publish(it) }

    private fun deactivate(template: UUID) {
        with(iam) { call(POST, "/api/v1/checklist-templates/$template/deactivate", asAdmin()).ok() }
    }
}
