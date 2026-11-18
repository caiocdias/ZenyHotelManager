package com.zeny.hotel.housekeeping

import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.DepartmentKey
import com.zeny.hotel.staff.ScheduleEntryKind
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.staff.StaffTestSupport
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.PATCH
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Apoio dos testes do plano do dia (M6 E03): escala camareiras no setor de governança da carga, grava tarefas direto no
 * banco (origem e status que a API não cria) e chama a sugestão e o ajuste **tolerando o plano alheio** (Global
 * Constraints): quando o plano da data já existe, lê o `ETag` e o manda no `If-Match`.
 */
class AssignmentPlanTestSupport(
    private val iam: IamTestSupport,
    private val jdbc: JdbcClient,
    private val staffApi: StaffApi,
) {
    val staff = StaffTestSupport(iam)

    val housekeepingDepartment: UUID
        get() =
            staffApi
                .departments()
                .single { it.key == DepartmentKey.HOUSEKEEPING }
                .id.value

    /**
     * Uma camareira nova no setor [department] (padrão: governança), escalada num turno do setor em [dates] e vinculada
     * ao usuário [userId], se houver.
     */
    fun attendant(
        vararg dates: LocalDate,
        department: UUID = housekeepingDepartment,
        name: String = staff.unique("Camareira"),
        userId: UUID? = null,
    ): UUID {
        val employee = staff.createEmployee(department, name, userId)
        val shift = staff.createShift(department, LocalTime.of(7, 0), LocalTime.of(15, 0))
        dates.forEach { staff.record(employee, ScheduleEntryKind.SHIFT, it, shiftId = shift) }
        return employee
    }

    fun plan(
        date: LocalDate,
        auth: RequestPostProcessor = iam.asAdmin(),
    ): MockHttpServletResponse = iam.call(GET, "$PLANS/$date", auth)

    /** O `ETag` do plano da data, ou `null` se ele ainda não existe. */
    fun etagOrNull(date: LocalDate): String? {
        val response = plan(date)
        return if (response.status == 200) response.getHeader(HttpHeaders.ETAG) else null
    }

    /** A sugestão com o `If-Match` do plano existente (se houver), restrita a [employeeIds]. */
    fun suggest(
        date: LocalDate,
        employeeIds: List<UUID>?,
        auth: RequestPostProcessor = iam.asAdmin(),
    ): MockHttpServletResponse =
        iam.call(POST, "$PLANS/$date/suggest", auth, mapOf("employeeIds" to employeeIds), etagOrNull(date))

    /** A sugestão que precisa passar: `200`, com o corpo e o `ETag`. */
    fun suggested(
        date: LocalDate,
        employeeIds: List<UUID>?,
    ): Pair<JsonNode, String> {
        val response = suggest(date, employeeIds)
        val body = with(iam) { response.ok() }
        return body to response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
    }

    fun adjust(
        date: LocalDate,
        body: Map<String, Any?>,
        ifMatch: String?,
        auth: RequestPostProcessor = iam.asAdmin(),
    ): MockHttpServletResponse = iam.call(PATCH, "$PLANS/$date", auth, body, ifMatch)

    /** A publicação com o `If-Match` dado (padrão: o do plano existente). */
    fun publish(
        date: LocalDate,
        ifMatch: String? = etagOrNull(date),
        auth: RequestPostProcessor = iam.asAdmin(),
    ): MockHttpServletResponse = iam.call(POST, "$PLANS/$date/publish", auth, null, ifMatch)

    /** A publicação que precisa passar: `200`, com o corpo e o `ETag`. */
    fun published(date: LocalDate): Pair<JsonNode, String> {
        val response = publish(date)
        val body = with(iam) { response.ok() }
        return body to response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
    }

    /**
     * O rascunho exato do teste, tolerando o plano alheio: a sugestão restrita a [employees] e um ajuste que deixa no
     * plano só as tarefas de [assignment], cada uma com a sua camareira (`null`: sem camareira), na ordem dada.
     * Devolve o `ETag`.
     */
    fun draft(
        date: LocalDate,
        employees: List<UUID>,
        assignment: List<Pair<UUID, UUID?>>,
    ): String {
        val (plan, etag) = suggested(date, employees)
        val mine = assignment.map { it.first }.toSet()
        val foreign = (itemsOf(plan).keys + unassignedOf(plan)).filterNot { it in mine }
        val body =
            mapOf(
                "items" to assignment.map { (task, employee) -> mapOf("taskId" to task, "employeeId" to employee) },
                "remove" to foreign,
            )
        val response = adjust(date, body, etag)
        with(iam) { response.ok() }
        return response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
    }

    /** A tarefa pela API (`GET /housekeeping-tasks/{id}`). */
    fun task(id: UUID): JsonNode = with(iam) { call(GET, "$TASKS/$id", asAdmin()).ok() }

    /** Muda o status da tarefa direto no banco (a tarefa que "andou", como na E04). */
    fun setStatus(
        task: UUID,
        status: String,
    ) {
        jdbc
            .sql("UPDATE housekeeping.housekeeping_task SET status = :status WHERE id = :id")
            .param("status", status)
            .param("id", task)
            .update() shouldBe 1
    }

    /** Uma tarefa gravada direto no banco (o papel da aplicação), com a origem e o status que a API não cria. */
    @Suppress("LongParameterList") // os campos da tarefa, com padrões
    fun insertTask(
        date: LocalDate,
        room: UUID? = null,
        area: UUID? = null,
        source: String = "AD_HOC",
        status: String = "PENDING",
        priority: Int = 3,
        credits: Int = 1,
        arrivalExpected: Boolean = false,
        type: UUID = TOUCH_UP_TYPE,
        employee: UUID? = null,
        position: Int? = null,
    ): UUID {
        val id = UuidV7.generate()
        jdbc
            .sql(
                """
                INSERT INTO housekeeping.housekeeping_task (id, room_id, area_id, business_date, cleaning_type_id,
                    source, status, priority, credits, arrival_expected, scheduled_cleaning_id, notes, created_by,
                    created_at, updated_at, version, assigned_employee_id, assignment_position)
                VALUES (:id, :room, :area, :date, :type, :source, :status, :priority, :credits, :arrival, NULL, NULL,
                    NULL, now(), now(), 0, :employee, :position)
                """.trimIndent(),
            ).param("id", id)
            .param("room", room)
            .param("area", area)
            .param("date", date)
            .param("type", type)
            .param("source", source)
            .param("status", status)
            .param("priority", priority)
            .param("credits", credits)
            .param("arrival", arrivalExpected)
            .param("employee", employee)
            .param("position", position)
            .update()
        return id
    }

    companion object {
        const val PLANS = "/api/v1/assignment-plans"
        const val TASKS = "/api/v1/housekeeping-tasks"

        /** O tipo `TOUCH_UP` da carga (D5), só referenciado. */
        val TOUCH_UP_TYPE: UUID = UUID.fromString("01a1e8f2-f902-7002-8000-000000000003")

        /** Os itens do plano, por tarefa: (camareira, posição). */
        fun itemsOf(plan: JsonNode): Map<UUID, Pair<UUID, Int>> =
            plan["items"].values().associate {
                UUID.fromString(it["taskId"].asString()) to
                    (UUID.fromString(it["employeeId"].asString()) to it["position"].asInt())
            }

        fun unassignedOf(plan: JsonNode): List<UUID> =
            plan["unassigned"].values().map { UUID.fromString(it.asString()) }

        /** As tarefas da camareira, na ordem de execução. */
        fun orderOf(
            plan: JsonNode,
            employee: UUID,
        ): List<UUID> =
            itemsOf(plan)
                .filterValues { it.first == employee }
                .entries
                .sortedBy { it.value.second }
                .map { it.key }

        fun expectInPlan(
            plan: JsonNode,
            task: UUID,
        ) = withClue("tarefa $task no plano") {
            (task in itemsOf(plan) || task in unassignedOf(plan)) shouldBe true
        }
    }
}
