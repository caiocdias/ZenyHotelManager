package com.zeny.hotel.checklists

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import java.time.Instant
import java.util.UUID

/**
 * As operações da `ChecklistsApi` para a governança (M6 E04, Task 2; D5): `currentVersion` devolve a versão publicada
 * vigente de um modelo ativo, ou nulo; `completeExecutionIfOpen` conclui a execução aberta (com o `422` de item
 * obrigatório) e, com a execução já concluída por fora, devolve "já concluída" sem erro e sem um segundo
 * `ChecklistCompleted`.
 */
@IntegrationTest
@RecordApplicationEvents
@Import(IamTestConfiguration::class)
class ChecklistsApiHousekeepingOperationsTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val checklistsApi: ChecklistsApi,
    @Autowired private val jdbc: JdbcClient,
) {
    // O registro de eventos do Spring só chega por campo (não pelo construtor).
    @Autowired
    private lateinit var events: ApplicationEvents

    private val templates = ChecklistsTestSupport(iam)

    /** Um modelo de governança com `bed` obrigatório e `towels` opcional. */
    private fun template(): UUID =
        templates.create(
            templates.templateBody(
                sections =
                    listOf(
                        templates.section(
                            "Quarto",
                            templates.item("bed", extra = mapOf("required" to true)),
                            templates.item("towels"),
                        ),
                    ),
            ),
        )

    private fun completions(execution: ChecklistExecutionId) =
        events.stream(ChecklistCompleted::class.java).filter { it.executionId == execution.value }.count()

    @Test
    fun `currentVersion is the current published version of an active template, or null`(): Unit =
        with(iam) {
            val id = template()
            val templateId = ChecklistTemplateId(id)
            checklistsApi.currentVersion(templateId).shouldBeNull()

            templates.publish(id)
            checklistsApi.currentVersion(templateId).shouldNotBeNull().number shouldBe 1
            val body = templates.templateBody(sections = listOf(templates.section("Novo", templates.item("bath"))))
            call(PUT, "/api/v1/checklist-templates/$id", asAdmin(), body, templates.etagOf(id)).ok()
            // O rascunho não vale: segue a versão publicada.
            checklistsApi.currentVersion(templateId).shouldNotBeNull().number shouldBe 1
            templates.publish(id)
            checklistsApi.currentVersion(templateId).shouldNotBeNull().let {
                it.number shouldBe 2
                it.templateId shouldBe templateId
                it.content.items.map { item -> item.key } shouldBe listOf("bath")
            }

            call(POST, "/api/v1/checklist-templates/$id/deactivate", asAdmin()).ok()
            checklistsApi.currentVersion(templateId).shouldBeNull()
            checklistsApi.currentVersion(ChecklistTemplateId.new()).shouldBeNull()
        }

    @Test
    fun `completeExecutionIfOpen completes an open execution and keeps the 422 for a missing required item`() {
        val id = template()
        templates.publish(id)
        val version = checklistsApi.currentVersion(ChecklistTemplateId(id)).shouldNotBeNull()
        val executor = iam.createUser().id
        val subject = ChecklistSubject("housekeeping.task", UuidV7.generate())
        val execution = checklistsApi.startExecution(version.id, subject, executor, Instant.now()).id

        val missing =
            shouldThrow<BusinessRuleException> {
                checklistsApi.completeExecutionIfOpen(
                    execution,
                    listOf(ChecklistAnswerInput("towels", check = CheckAnswer.OK)),
                    Instant.now(),
                )
            }
        missing.code.value shouldBe "CHECKLIST_REQUIRED_ITEMS_MISSING"
        checklistsApi.execution(execution).shouldNotBeNull().status shouldBe ChecklistExecutionStatus.IN_PROGRESS
        completions(execution) shouldBe 0

        val completion =
            checklistsApi
                .completeExecutionIfOpen(
                    execution,
                    listOf(ChecklistAnswerInput("bed", check = CheckAnswer.OK)),
                    Instant.now(),
                ).shouldNotBeNull()
        completion.execution.status shouldBe ChecklistExecutionStatus.COMPLETED
        completion.execution.answers.map { it.itemKey } shouldBe listOf("bed")
        completions(execution) shouldBe 1
    }

    @Test
    fun `completeExecutionIfOpen on an execution completed elsewhere says so, without error nor a second event`() {
        val id = template()
        templates.publish(id)
        val version = checklistsApi.currentVersion(ChecklistTemplateId(id)).shouldNotBeNull()
        val executor = iam.createUser().id
        val execution = checklistsApi.startExecution(version.id, null, executor, Instant.now()).id
        checklistsApi.completeExecution(
            execution,
            listOf(ChecklistAnswerInput("bed", check = CheckAnswer.OK)),
            Instant.now(),
        )
        completions(execution) shouldBe 1
        val before = checklistsApi.execution(execution).shouldNotBeNull()

        // As respostas do corpo são ignoradas, inclusive as que faltariam.
        checklistsApi
            .completeExecutionIfOpen(
                execution,
                listOf(ChecklistAnswerInput("towels", check = CheckAnswer.NOT_OK)),
                Instant.now(),
            ).shouldBeNull()

        completions(execution) shouldBe 1
        checklistsApi.execution(execution) shouldBe before
        jdbc
            .sql(
                "SELECT action FROM audit.audit_entry WHERE entity_type = 'checklists.execution' " +
                    "AND entity_id = :id ORDER BY occurred_at, id",
            ).param("id", execution.value.toString())
            .query(String::class.java)
            .list() shouldContainExactly listOf("checklists.execution.start", "checklists.execution.complete")
    }
}
