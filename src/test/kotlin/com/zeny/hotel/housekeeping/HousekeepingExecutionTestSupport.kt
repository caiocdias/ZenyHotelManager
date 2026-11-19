package com.zeny.hotel.housekeeping

import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.staff.StaffApi
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.util.UUID

/**
 * Apoio dos testes da execução das tarefas (M6 E04): usuários com os modelos de role da carga (Camareira, Governanta),
 * camareiras vinculadas a eles e tarefas avulsas da data operacional corrente, cada uma numa UH nova e com um tipo de
 * limpeza novo (isoladas no banco compartilhado), atribuídas pelo próprio teste por escrita direta (o que a publicação
 * do plano grava: status `ASSIGNED`, a camareira e a posição), sem passar pelo plano do dia, que é compartilhado.
 */
class HousekeepingExecutionTestSupport(
    private val iam: IamTestSupport,
    private val jdbc: JdbcClient,
    staffApi: StaffApi,
) {
    val housekeeping = HousekeepingTestSupport(iam, jdbc)
    val plans = AssignmentPlanTestSupport(iam, jdbc, staffApi)

    /**
     * Desativa os modelos de checklist **gerais** de governança ativos e publicados (contexto `HOUSEKEEPING`, versão
     * publicada sem regra de aplicabilidade). O `start` de uma tarefa sem modelo vinculado ao tipo pega qualquer modelo
     * geral de governança ativo pelo `resolveTemplate` (D5 da E04), e outros testes do banco compartilhado criam modelos
     * gerais (com itens obrigatórios): eles não são duráveis aqui, e um teste que inicia tarefas não pode depender da
     * ordem das classes. Os modelos de um teste da execução entram pelo vínculo do tipo de limpeza.
     */
    fun neutralizeGeneralHousekeepingTemplates() {
        jdbc
            .sql(
                """
                UPDATE checklists.checklist_template t SET active = false
                WHERE t.context = 'HOUSEKEEPING' AND t.active AND t.published_version IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM checklists.checklist_version v
                                  WHERE v.template_id = t.id AND v.number = t.published_version
                                    AND jsonb_array_length(COALESCE(v.content -> 'applicability', '[]'::jsonb)) > 0)
                """.trimIndent(),
            ).update()
    }

    /** Um usuário novo só com o modelo de role [template] da carga. */
    fun userWithTemplate(template: RoleTemplate): TestUser =
        iam.createUser().also {
            val roleId =
                jdbc
                    .sql("SELECT id FROM iam.role WHERE template_key = :key")
                    .param("key", template.name)
                    .query(UUID::class.java)
                    .single()
            iam.assignRole(it, RoleId(roleId))
        }

    /** Uma camareira (usuário com o modelo Camareira e o colaborador da governança vinculado a ele). */
    fun attendant(): Attendant {
        val user = userWithTemplate(RoleTemplate.ROOM_ATTENDANT)
        return Attendant(user, plans.attendant(userId = user.id.value))
    }

    /** Uma tarefa avulsa nova na data operacional, numa UH nova, com as [notes]; `PENDING`. */
    fun pendingTask(notes: String? = null): UUID {
        val body =
            housekeeping.taskBody(housekeeping.createCleaningType(), roomId = housekeeping.createRoom(), notes = notes)
        return UUID.fromString(housekeeping.requestTask(body)["id"].asString())
    }

    /** Uma tarefa avulsa nova, já atribuída a [employee] (o que a publicação do plano grava). */
    fun assignedTask(
        employee: UUID,
        notes: String? = null,
    ): UUID = pendingTask(notes).also { assign(it, employee) }

    fun assign(
        task: UUID,
        employee: UUID,
    ) {
        jdbc
            .sql(
                "UPDATE housekeeping.housekeeping_task SET status = 'ASSIGNED', assigned_employee_id = :employee, " +
                    "assignment_position = 1, version = version + 1 WHERE id = :id",
            ).param("employee", employee)
            .param("id", task)
            .update() shouldBe 1
    }

    /** A ação [action] sobre a tarefa, com o `If-Match` dado (padrão: o `ETag` atual). */
    fun act(
        task: UUID,
        action: String,
        auth: RequestPostProcessor,
        ifMatch: String? = housekeeping.etagOf("$TASKS/$task"),
        body: Any? = null,
    ): MockHttpServletResponse = iam.call(POST, "$TASKS/$task/$action", auth, body, ifMatch)

    /** A ação que precisa passar: o corpo `200` e o `ETag` novo. */
    fun acted(
        task: UUID,
        action: String,
        auth: RequestPostProcessor,
        body: Any? = null,
    ): Pair<String, MockHttpServletResponse> {
        val response = act(task, action, auth, body = body)
        withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 200 }
        return response.getHeader(HttpHeaders.ETAG).shouldNotBeNull() to response
    }

    data class Attendant(
        val user: TestUser,
        val employee: UUID,
    )

    companion object {
        const val TASKS = HousekeepingTestSupport.TASKS
    }
}
