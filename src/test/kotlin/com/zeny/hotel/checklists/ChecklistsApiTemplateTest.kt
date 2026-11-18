package com.zeny.hotel.checklists

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST

/**
 * O resumo do modelo pela API pública (M6 E01, Task 2): contexto, se está ativo e se tem versão publicada, ou `null`
 * se o modelo não existe.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class ChecklistsApiTemplateTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val checklistsApi: ChecklistsApi,
) {
    private val checklists = ChecklistsTestSupport(iam)

    @Test
    fun `template returns null for an unknown id`() {
        checklistsApi.template(ChecklistTemplateId.new()).shouldBeNull()
    }

    @Test
    fun `template summarizes context, active flag and publication`() {
        val id = checklists.create(checklists.templateBody(context = ChecklistContext.EVENT))
        val id2 = ChecklistTemplateId(id)

        checklistsApi.template(id2).shouldNotBeNull().let {
            it.id shouldBe id2
            it.context shouldBe ChecklistContext.EVENT
            it.active shouldBe true
            it.published shouldBe false
        }

        checklists.publish(id)
        checklistsApi.template(id2).shouldNotBeNull().published shouldBe true

        with(iam) { call(POST, "/api/v1/checklist-templates/$id/deactivate", asAdmin()).ok() }
        checklistsApi.template(id2).shouldNotBeNull().let {
            it.active shouldBe false
            it.published shouldBe true
        }
    }
}
