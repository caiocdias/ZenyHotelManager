package com.zeny.hotel.staff

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.staff.StaffTestSupport.Companion.definition
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpMethod
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Colaboradores, turnos e escala pela API (M1 E07, RF-PES-01 a 04, RF-ACS-12, H6, H7): os setores
 * pré-carregados; o cadastro com permissão, `If-Match` e auditoria; o vínculo com usuário validado
 * pela API pública do `iam`; padrões, geração e exceções que prevalecem; o escopo `OWN`; e a API
 * pública "escala de uma data por setor".
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LargeClass") // cenários de ponta a ponta do cadastro, da escala e de cada recusa
class StaffApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val hotel: HotelApi,
    @Autowired private val staffApi: StaffApi,
    @Autowired @Qualifier("requestMappingHandlerMapping") private val handlerMapping: RequestMappingHandlerMapping,
) {
    private val staff = StaffTestSupport(iam)
    private val day = LocalTime.of(7, 0) to LocalTime.of(19, 0)
    private val night = LocalTime.of(19, 0) to LocalTime.of(7, 0)

    private fun today(): LocalDate = hotel.currentBusinessDate()

    // --- Setores -------------------------------------------------------------------------------------

    @Test
    fun `the six departments of SPEC 5_3 exist after the migration`() {
        val seeded =
            jdbc
                .sql("SELECT system_key, name FROM staff.department WHERE system_key IS NOT NULL")
                .query { rs, _ -> rs.getString(1) to rs.getString(2) }
                .list()

        seeded.map { it.first } shouldContainExactlyInAnyOrder DepartmentKey.entries.map { it.name }
        seeded.toMap()["HOUSEKEEPING"] shouldNotBe null
        staffApi.departments().mapNotNull { it.key } shouldContainExactlyInAnyOrder DepartmentKey.entries
        staffApi.departments().take(6).map { it.key } shouldContainExactly DepartmentKey.entries
    }

    @Test
    fun `departments are created and renamed with permission and If-Match, never deleted`(): Unit =
        with(iam) {
            val reader = createUserWith(TestGrant(definition(StaffPermissions.EMPLOYEES_READ), ALL))
            val name = staff.unique("Lavanderia")
            call(
                POST,
                "/api/v1/departments",
                asTestUser(reader),
                mapOf("name" to name),
            ).problem(403, "PERMISSION_DENIED")
            val id = staff.createDepartment(name)
            call(POST, "/api/v1/departments", asAdmin(), mapOf("name" to name.uppercase()))
                .problem(409, "DEPARTMENT_NAME_ALREADY_IN_USE")

            val path = "/api/v1/departments/$id"
            val etag = staff.etagOf(path)
            val renamed = mapOf("name" to "$name II")
            call(PUT, path, asTestUser(reader), renamed, ifMatch = etag).problem(403, "PERMISSION_DENIED")
            call(PUT, path, asAdmin(), renamed).problem(428, "PRECONDITION_REQUIRED")
            call(PUT, path, asAdmin(), renamed, ifMatch = "\"999\"").problem(412, "PRECONDITION_FAILED")
            call(PUT, path, asAdmin(), mapOf("name" to "Governança"), ifMatch = etag)
                .problem(409, "DEPARTMENT_NAME_ALREADY_IN_USE")
            call(PUT, path, asAdmin(), renamed, ifMatch = etag).ok()["name"].asString() shouldBe "$name II"
            call(GET, path, asTestUser(reader)).ok()["key"].isNull shouldBe true
            call(
                GET,
                "/api/v1/departments/${UUID.randomUUID()}",
                asTestUser(reader),
            ).problem(404, "DEPARTMENT_NOT_FOUND")
            call(GET, "/api/v1/departments", asTestUser(createUser())).problem(403, "PERMISSION_DENIED")

            auditActions("staff.department", id) shouldContainExactly
                listOf("staff.department.create", "staff.department.update")
            shouldThrow<DataAccessException> {
                transactions.executeWithoutResult {
                    jdbc.sql("DELETE FROM staff.department WHERE id = :id").param("id", id).update()
                }
            }
        }

    // --- Colaboradores e vínculo ---------------------------------------------------------------------

    @Test
    fun `an employee is registered, edited, deactivated and reactivated, and is never deleted`(): Unit =
        with(iam) {
            val department = staff.createDepartment()
            val other = staff.createDepartment()
            val id = staff.createEmployee(department, "Ana Souza")
            val path = "/api/v1/employees/$id"

            val created = call(GET, path, asAdmin()).ok()
            created["active"].asBoolean() shouldBe true
            created["position"].asString() shouldBe "Camareira"
            created["userId"].isNull shouldBe true

            val edited = staff.employeeBody(other, "Ana Souza Lima") + ("phone" to null)
            call(PUT, path, asAdmin(), edited, ifMatch = staff.etagOf(path)).ok().let {
                it["name"].asString() shouldBe "Ana Souza Lima"
                it["departmentId"].asString() shouldBe other.toString()
                it["phone"].isNull shouldBe true
            }
            call(PUT, path, asAdmin(), staff.employeeBody(UUID.randomUUID(), "X"), ifMatch = staff.etagOf(path))
                .problem(400, "UNKNOWN_DEPARTMENT")["errors"][0]["field"]
                .asString() shouldBe "departmentId"
            call(POST, "/api/v1/employees", asAdmin(), staff.employeeBody(department, "  "))
                .problem(400, "VALIDATION_FAILED")

            call(POST, "$path/deactivate", asAdmin()).ok()["active"].asBoolean() shouldBe false
            call(POST, "$path/deactivate", asAdmin()).ok()["active"].asBoolean() shouldBe false
            call(GET, "/api/v1/employees?departmentId=$other&active=false", asAdmin())
                .ok()["items"]
                .values()
                .map { it["id"].asString() } shouldContainExactly listOf(id.toString())
            call(
                GET,
                "/api/v1/employees?departmentId=$other&active=true",
                asAdmin(),
            ).ok()["items"].values().shouldBeEmpty()
            call(POST, "$path/reactivate", asAdmin()).ok()["active"].asBoolean() shouldBe true

            auditActions("staff.employee", id) shouldContainExactly
                listOf(
                    "staff.employee.create",
                    "staff.employee.update",
                    "staff.employee.deactivate",
                    "staff.employee.reactivate",
                )
            // Nem a aplicação consegue apagar um colaborador: o banco recusa.
            shouldThrow<DataAccessException> {
                transactions.executeWithoutResult {
                    jdbc.sql("DELETE FROM staff.employee WHERE id = :id").param("id", id).update()
                }
            }
            jdbc
                .sql(
                    "SELECT count(*) FROM staff.employee WHERE id = :id",
                ).param("id", id)
                .query(Int::class.java)
                .single() shouldBe
                1
        }

    @Test
    fun `no staff endpoint deletes anything`() {
        val staffMappings =
            handlerMapping.handlerMethods.filter { (_, method) ->
                method.beanType.packageName.startsWith("com.zeny.hotel.staff")
            }
        staffMappings.keys
            .flatMap { it.methodsCondition.methods }
            .map { it.name }
            .toSet() shouldBe
            setOf("GET", "POST", "PUT")
        staffMappings.keys.flatMap { it.patternValues }.toSet() shouldContainExactlyInAnyOrder
            listOf(
                "/api/v1/departments",
                "/api/v1/departments/{id}",
                "/api/v1/employees",
                "/api/v1/employees/{id}",
                "/api/v1/employees/{id}/deactivate",
                "/api/v1/employees/{id}/reactivate",
                "/api/v1/shifts",
                "/api/v1/shifts/{id}",
                "/api/v1/schedule-patterns",
                "/api/v1/schedule-patterns/{id}",
                "/api/v1/schedule-patterns/{id}/deactivate",
                "/api/v1/schedule",
                "/api/v1/schedule/generate",
                "/api/v1/schedule/entries",
            )
    }

    @Test
    @Suppress("LongMethod") // um cenário de ponta a ponta, com cada recusa no seu passo
    fun `a user who does not exist, is inactive or is already linked cannot be linked`(): Unit =
        with(iam) {
            val department = staff.createDepartment()
            val user = createUser()
            val inactive = createUser().also { deactivate(it) }

            // Inexistente: 400 UNKNOWN_USER no campo userId.
            call(POST, "/api/v1/employees", asAdmin(), staff.employeeBody(department, "Bia", UUID.randomUUID()))
                .problem(400, "UNKNOWN_USER")["errors"][0]["field"]
                .asString() shouldBe "userId"
            // Inativo: 422 USER_INACTIVE.
            call(POST, "/api/v1/employees", asAdmin(), staff.employeeBody(department, "Bia", inactive.id.value))
                .problem(422, "USER_INACTIVE")

            val bia = staff.createEmployee(department, "Bia", user.id.value)
            staffApi.employeeOfUser(user.id).shouldNotBeNull().id shouldBe EmployeeId(bia)
            // Já vinculado a outro colaborador: 409 USER_ALREADY_LINKED, na criação e na edição.
            call(POST, "/api/v1/employees", asAdmin(), staff.employeeBody(department, "Carla", user.id.value))
                .problem(409, "USER_ALREADY_LINKED")
            val carla = staff.createEmployee(department, "Carla")
            val carlaPath = "/api/v1/employees/$carla"
            call(
                PUT,
                carlaPath,
                asAdmin(),
                staff.employeeBody(department, "Carla", user.id.value),
                ifMatch = staff.etagOf(carlaPath),
            ).problem(409, "USER_ALREADY_LINKED")
            call(
                PUT,
                carlaPath,
                asAdmin(),
                staff.employeeBody(department, "Carla", inactive.id.value),
                ifMatch = staff.etagOf(carlaPath),
            ).problem(422, "USER_INACTIVE")
            call(
                PUT,
                carlaPath,
                asAdmin(),
                staff.employeeBody(department, "Carla", UUID.randomUUID()),
                ifMatch = staff.etagOf(carlaPath),
            ).problem(400, "UNKNOWN_USER")
            call(GET, carlaPath, asAdmin()).ok()["userId"].isNull shouldBe true

            // Editar a própria Bia mantendo o vínculo não é "já vinculado", mesmo com o usuário desativado depois.
            val biaPath = "/api/v1/employees/$bia"
            deactivate(user)
            call(
                PUT,
                biaPath,
                asAdmin(),
                staff.employeeBody(department, "Bia Lima", user.id.value),
                ifMatch = staff.etagOf(biaPath),
            ).ok()["userId"]
                .asString() shouldBe user.id.value.toString()
            // Desvinculada a Bia, o usuário (ativo de novo) pode ir para a Carla.
            jdbc.sql("UPDATE iam.user_account SET active = true WHERE id = :id").param("id", user.id.value).update()
            call(
                PUT,
                biaPath,
                asAdmin(),
                staff.employeeBody(department, "Bia Lima", null),
                ifMatch = staff.etagOf(biaPath),
            ).ok()
            call(
                PUT,
                carlaPath,
                asAdmin(),
                staff.employeeBody(department, "Carla", user.id.value),
                ifMatch = staff.etagOf(carlaPath),
            ).ok()["userId"]
                .asString() shouldBe user.id.value.toString()
            staffApi.employeeOfUser(user.id).shouldNotBeNull().id shouldBe EmployeeId(carla)
            staffApi.employeeOfUser(UserId.new()).shouldBeNull()

            // O banco também garante: um usuário, no máximo um colaborador.
            shouldThrow<DataIntegrityViolationException> {
                transactions.executeWithoutResult {
                    jdbc
                        .sql("UPDATE staff.employee SET user_id = :user WHERE id = :id")
                        .param("user", user.id.value)
                        .param("id", bia)
                        .update()
                }
            }
        }

    // --- Turnos --------------------------------------------------------------------------------------

    @Test
    fun `shifts belong to a department, may cross midnight and have coherent times`(): Unit =
        with(iam) {
            val department = staff.createDepartment()
            val nightId = staff.createShift(department, night.first, night.second, "Noturno")
            val path = "/api/v1/shifts/$nightId"
            call(GET, path, asAdmin()).ok().let {
                it["crossesMidnight"].asBoolean() shouldBe true
                it["start"].asString() shouldBe "19:00:00"
            }
            val body = mapOf("departmentId" to department, "name" to "Zero", "start" to "07:00", "end" to "07:00")
            call(POST, "/api/v1/shifts", asAdmin(), body).problem(400, "SHIFT_TIMES_INVALID")
            call(POST, "/api/v1/shifts", asAdmin(), body + ("end" to "07:00:30")).problem(400, "SHIFT_TIMES_INVALID")
            call(POST, "/api/v1/shifts", asAdmin(), body + ("name" to "noturno") + ("end" to "15:00"))
                .problem(409, "SHIFT_NAME_ALREADY_IN_USE")
            call(POST, "/api/v1/shifts", asAdmin(), body + ("departmentId" to UUID.randomUUID()) + ("end" to "15:00"))
                .problem(400, "UNKNOWN_DEPARTMENT")
            // Outro setor pode ter um turno com o mesmo nome.
            staff.createShift(staff.createDepartment(), night.first, night.second, "Noturno")

            val update = mapOf("name" to "Noturno", "start" to "22:00", "end" to "06:00")
            call(PUT, path, asAdmin(), update, ifMatch = staff.etagOf(path)).ok()["start"].asString() shouldBe
                "22:00:00"
            jdbc
                .sql("SELECT CAST(start_time AS text) FROM staff.shift WHERE id = :id")
                .param("id", nightId)
                .query(String::class.java)
                .single() shouldBe "22:00:00"
            auditActions("staff.shift", nightId) shouldContainExactly listOf("staff.shift.create", "staff.shift.update")
        }

    // --- Padrões, geração e exceções -------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // um cenário de ponta a ponta, com cada recusa no seu passo
    fun `patterns are coherent, do not overlap and need an active employee`(): Unit =
        with(iam) {
            val department = staff.createDepartment()
            val shift = staff.createShift(department, day.first, day.second)
            val employee = staff.createEmployee(department)
            val start = today()
            val pattern =
                staff.createPattern(
                    employee,
                    shift,
                    SchedulePatternKind.SIX_ON_ONE_OFF,
                    start,
                    start.plusDays(30),
                )
            val body =
                mapOf(
                    "employeeId" to employee,
                    "shiftId" to shift,
                    "kind" to "FIXED",
                    "startsOn" to start.plusDays(30).toString(),
                    "weekdays" to listOf("MONDAY"),
                )

            call(POST, "/api/v1/schedule-patterns", asAdmin(), body).problem(409, "SCHEDULE_PATTERN_OVERLAP")
            call(POST, "/api/v1/schedule-patterns", asAdmin(), body + ("weekdays" to emptyList<String>()))
                .problem(400, "SCHEDULE_PATTERN_INVALID")
            call(POST, "/api/v1/schedule-patterns", asAdmin(), body + ("shiftId" to UUID.randomUUID()))
                .problem(400, "UNKNOWN_SHIFT")
            call(POST, "/api/v1/schedule-patterns", asAdmin(), body + ("employeeId" to UUID.randomUUID()))
                .problem(400, "UNKNOWN_EMPLOYEE")
            // Depois do fim do primeiro, não se sobrepõe.
            val next =
                staff.createPattern(
                    employee,
                    shift,
                    SchedulePatternKind.FIXED,
                    start.plusDays(31),
                    weekdays = listOf("MONDAY", "FRIDAY"),
                )
            call(GET, "/api/v1/schedule-patterns/$next", asAdmin()).ok()["weekdays"].values().map {
                it.asString()
            } shouldContainExactly
                listOf("MONDAY", "FRIDAY")

            // Estender o primeiro sobre o segundo: 409; desativar o segundo libera.
            val path = "/api/v1/schedule-patterns/$pattern"
            val extended =
                mapOf(
                    "kind" to "SIX_ON_ONE_OFF",
                    "shiftId" to shift,
                    "startsOn" to start.toString(),
                    "endsOn" to null,
                )
            call(PUT, path, asAdmin(), extended, ifMatch = staff.etagOf(path)).problem(409, "SCHEDULE_PATTERN_OVERLAP")
            call(POST, "/api/v1/schedule-patterns/$next/deactivate", asAdmin()).ok()["active"].asBoolean() shouldBe
                false
            call(PUT, path, asAdmin(), extended, ifMatch = staff.etagOf(path)).ok()["endsOn"].isNull shouldBe true
            call(
                PUT,
                "/api/v1/schedule-patterns/$next",
                asAdmin(),
                extended,
                ifMatch = staff.etagOf("/api/v1/schedule-patterns/$next"),
            ).problem(422, "SCHEDULE_PATTERN_INACTIVE")

            // Colaborador desativado não recebe padrão.
            call(POST, "/api/v1/employees/$employee/deactivate", asAdmin()).ok()
            call(POST, "/api/v1/schedule-patterns", asAdmin(), body + ("startsOn" to start.plusDays(400).toString()))
                .problem(422, "EMPLOYEE_INACTIVE")
            auditActions("staff.schedule-pattern", pattern) shouldContainExactly
                listOf("staff.schedule-pattern.create", "staff.schedule-pattern.update")
        }

    @Test
    @Suppress("LongMethod") // um cenário de ponta a ponta, com cada recusa no seu passo
    fun `generation follows the pattern and never overwrites recorded exceptions`(): Unit =
        with(iam) {
            val department = staff.createDepartment()
            val dayShift = staff.createShift(department, day.first, day.second)
            val nightShift = staff.createShift(department, night.first, night.second)
            val employee = staff.createEmployee(department)
            val start = today()
            val end = start.plusDays(29)
            val pattern = staff.createPattern(employee, dayShift, SchedulePatternKind.TWELVE_THIRTY_SIX, start)

            // 12x36 em 30 dias: 15 datas, um dia sim, um não.
            val first = staff.generate(start, end, departmentId = department)
            first["created"].asInt() shouldBe 15

            fun schedule() =
                staff.entries("from=$start&to=$end&employeeId=$employee").associateBy {
                    LocalDate.parse(it["date"].asString())
                }
            schedule().keys.sorted() shouldContainExactly (0L..29L step 2).map { start.plusDays(it) }
            schedule().values.all {
                it["source"].asString() == "PATTERN" &&
                    it["shift"]["id"].asString() == dayShift.toString()
            } shouldBe
                true

            // Exceções: folga num dia de trabalho, férias em três dias, troca num dia de descanso.
            staff.record(employee, ScheduleEntryKind.DAY_OFF, start.plusDays(2), note = "Folga pedida")
            staff.record(employee, ScheduleEntryKind.VACATION, start.plusDays(10), start.plusDays(12))
            staff.record(employee, ScheduleEntryKind.SWAP, start.plusDays(5), shiftId = nightShift)
            call(
                POST,
                "/api/v1/schedule/entries",
                asAdmin(),
                mapOf(
                    "employeeId" to employee,
                    "kind" to "DAY_OFF",
                    "from" to start.toString(),
                    "shiftId" to dayShift,
                ),
            ).problem(400, "SCHEDULE_ENTRY_INVALID")

            // Gerar de novo não muda nada e conta as 5 lançadas.
            val again = staff.generate(start, end, employeeId = employee)
            listOf(again["created"], again["updated"], again["removed"]).map { it.asInt() } shouldBe listOf(0, 0, 0)
            again["keptManual"].asInt() shouldBe 5

            // Nem trocando o padrão para 6x1 noturno: as lançadas ficam.
            val path = "/api/v1/schedule-patterns/$pattern"
            call(
                PUT,
                path,
                asAdmin(),
                mapOf(
                    "kind" to "SIX_ON_ONE_OFF",
                    "shiftId" to nightShift,
                    "startsOn" to start.toString(),
                    "endsOn" to null,
                ),
                ifMatch = staff.etagOf(path),
            ).ok()
            staff.generate(start, end, departmentId = department)["keptManual"].asInt() shouldBe 5
            val after = schedule()
            after.getValue(start.plusDays(2)).let {
                it["kind"].asString() shouldBe "DAY_OFF"
                it["source"].asString() shouldBe "MANUAL"
                it["note"].asString() shouldBe "Folga pedida"
                it["shift"].isNull shouldBe true
            }
            (10L..12L).forEach { after.getValue(start.plusDays(it))["kind"].asString() shouldBe "VACATION" }
            after.getValue(start.plusDays(5)).let {
                it["kind"].asString() shouldBe "SWAP"
                it["shift"]["id"].asString() shouldBe nightShift.toString()
            }
            // As geradas seguem o 6x1 (folga a cada sétimo dia) no turno da noite.
            val generated = after.values.filter { it["source"].asString() == "PATTERN" }
            generated.map { LocalDate.parse(it["date"].asString()) }.sorted() shouldContainExactly
                (0L..29L).filter { it % 7 != 6L && it !in setOf(2L, 5L, 10L, 11L, 12L) }.map { start.plusDays(it) }
            generated.all { it["shift"]["id"].asString() == nightShift.toString() } shouldBe true

            // O passado não é reescrito pela geração.
            call(
                POST,
                "/api/v1/schedule/generate",
                asAdmin(),
                mapOf(
                    "from" to start.minusDays(1).toString(),
                    "to" to end.toString(),
                ),
            ).problem(422, "SCHEDULE_PERIOD_IN_PAST")
            call(
                POST,
                "/api/v1/schedule/generate",
                asAdmin(),
                mapOf(
                    "from" to start.toString(),
                    "to" to start.plusDays(92).toString(),
                ),
            ).problem(400, "SCHEDULE_PERIOD_INVALID")
            jdbc
                .sql(
                    "SELECT count(*) FROM audit.audit_entry " +
                        "WHERE action = 'staff.schedule.generate' AND after_state ->> 'departmentId' = :d",
                ).param("d", department.toString())
                .query(Int::class.java)
                .single() shouldBe 2
            jdbc
                .sql(
                    "SELECT count(*) FROM audit.audit_entry " +
                        "WHERE action = 'staff.schedule-entry.record' AND after_state ->> 'employeeId' = :e",
                ).param("e", employee.toString())
                .query(Int::class.java)
                .single() shouldBe 5
        }

    @Test
    fun `deactivating an employee takes them out of the schedule from the business date on, keeping absences`(): Unit =
        with(iam) {
            val department = staff.createDepartment()
            val shift = staff.createShift(department, day.first, day.second)
            val employee = staff.createEmployee(department)
            val start = today()
            staff.createPattern(employee, shift, SchedulePatternKind.SIX_ON_ONE_OFF, start)
            staff.generate(start, start.plusDays(13), departmentId = department)
            staff.record(employee, ScheduleEntryKind.VACATION, start.plusDays(3))

            call(POST, "/api/v1/employees/$employee/deactivate", asAdmin()).ok()

            staff.entries("from=$start&to=${start.plusDays(13)}&employeeId=$employee").map {
                it["kind"].asString()
            } shouldContainExactly
                listOf("VACATION")
            staff.generate(start, start.plusDays(13), departmentId = department)["created"].asInt() shouldBe 0
            call(
                POST,
                "/api/v1/schedule/entries",
                asAdmin(),
                mapOf(
                    "employeeId" to employee,
                    "kind" to "SHIFT",
                    "from" to start.toString(),
                    "shiftId" to shift,
                ),
            ).problem(422, "EMPLOYEE_INACTIVE")
            call(POST, "/api/v1/employees/$employee/reactivate", asAdmin()).ok()
            staff.generate(start, start.plusDays(13), departmentId = department)["created"].asInt() shouldBe 11
        }

    // --- Escala do dia (API pública) e escopo OWN --------------------------------------------------------

    @Test
    fun `the public API returns who works on a date in a department, and their shifts`() {
        val department = staff.createDepartment()
        val otherDepartment = staff.createDepartment()
        val dayShift = staff.createShift(department, day.first, day.second, "Diurno")
        val nightShift = staff.createShift(department, night.first, night.second, "Noturno")
        val otherShift = staff.createShift(otherDepartment, day.first, day.second)
        val ana = staff.createEmployee(department, "Ana")
        val bia = staff.createEmployee(department, "Bia")
        val carla = staff.createEmployee(department, "Carla")
        val duda = staff.createEmployee(otherDepartment, "Duda")
        val date = today().plusDays(1)

        staff.record(ana, ScheduleEntryKind.SHIFT, date, shiftId = nightShift)
        staff.record(bia, ScheduleEntryKind.SHIFT, date, shiftId = dayShift)
        staff.record(carla, ScheduleEntryKind.DAY_OFF, date)
        staff.record(duda, ScheduleEntryKind.SHIFT, date, shiftId = otherShift)
        // Duda cobre um turno do setor numa troca: conta no setor do turno.
        staff.record(duda, ScheduleEntryKind.SWAP, date.plusDays(1), shiftId = dayShift)

        val schedule = staffApi.daySchedule(date, DepartmentId(department))

        schedule.date shouldBe date
        schedule.assignments.map { it.employee.name to it.shift.name } shouldContainExactly
            listOf("Bia" to "Diurno", "Ana" to "Noturno")
        schedule.assignments.last().let {
            it.shift.crossesMidnight shouldBe true
            it.shift.start shouldBe night.first
            it.kind shouldBe ScheduleEntryKind.SHIFT
            it.employee.id shouldBe EmployeeId(ana)
        }
        // O turno da noite pertence à data em que começa: no dia seguinte, só a troca da Duda.
        staffApi.daySchedule(date.plusDays(1), DepartmentId(department)).assignments.map {
            it.employee.name to it.kind
        } shouldContainExactly
            listOf("Duda" to ScheduleEntryKind.SWAP)
        staffApi
            .daySchedule(
                date,
                DepartmentId(otherDepartment),
            ).assignments
            .map { it.employee.name } shouldContainExactly
            listOf("Duda")
    }

    @Test
    fun `the schedule of today uses the business date`(): Unit =
        with(iam) {
            val department = staff.createDepartment()
            val shift = staff.createShift(department, day.first, day.second)
            val employee = staff.createEmployee(department)
            staff.record(employee, ScheduleEntryKind.SHIFT, today().minusDays(1), today().plusDays(1), shiftId = shift)

            staff.entries("departmentId=$department").map { it["date"].asString() } shouldContainExactly
                listOf(today().toString())
        }

    @Test
    fun `a user with OWN scope sees only their own schedule and patterns`(): Unit =
        with(iam) {
            val department = staff.createDepartment()
            val shift = staff.createShift(department, day.first, day.second)
            val ownGrant = TestGrant(definition(StaffPermissions.SCHEDULE_READ), OWN)
            val me = createUserWith(ownGrant)
            val colleague = createUserWith(ownGrant)
            val unlinked = createUserWith(ownGrant)
            val allReader = createUserWith(TestGrant(definition(StaffPermissions.SCHEDULE_READ), ALL))
            val mine = staff.createEmployee(department, "Eu", me.id.value)
            val theirs = staff.createEmployee(department, "Colega", colleague.id.value)
            val start = today()
            val myPattern = staff.createPattern(mine, shift, SchedulePatternKind.TWELVE_THIRTY_SIX, start)
            val theirPattern =
                staff.createPattern(
                    theirs,
                    shift,
                    SchedulePatternKind.TWELVE_THIRTY_SIX,
                    start.plusDays(1),
                )
            staff.generate(start, start.plusDays(9), departmentId = department)
            val query = "from=$start&to=${start.plusDays(9)}&departmentId=$department"

            staff.entries(query, asTestUser(me)).map { it["employeeId"].asString() }.toSet() shouldBe
                setOf(mine.toString())
            staff.entries(query, asTestUser(me)).size shouldBe 5
            staff.entries("$query&employeeId=$theirs", asTestUser(me)).shouldBeEmpty()
            staff.entries(query, asTestUser(unlinked)).shouldBeEmpty()
            staff.entries(query, asTestUser(allReader)).map { it["employeeId"].asString() }.toSet() shouldBe
                setOf(mine.toString(), theirs.toString())

            call(GET, "/api/v1/schedule-patterns", asTestUser(me)).ok()["items"].values().map {
                it["id"].asString()
            } shouldContainExactly
                listOf(myPattern.toString())
            call(GET, "/api/v1/schedule-patterns/$myPattern", asTestUser(me)).ok()
            call(
                GET,
                "/api/v1/schedule-patterns/$theirPattern",
                asTestUser(me),
            ).problem(404, "SCHEDULE_PATTERN_NOT_FOUND")
            call(GET, "/api/v1/schedule-patterns/$theirPattern", asTestUser(allReader)).ok()
            // Sem a permissão, a consulta é recusada (não esvaziada); ler a escala não dá o cadastro.
            call(GET, "/api/v1/schedule?$query", asTestUser(createUser())).problem(403, "PERMISSION_DENIED")
            call(GET, "/api/v1/employees", asTestUser(me)).problem(403, "PERMISSION_DENIED")
        }

    @Test
    fun `a tampered cursor of any staff listing is 400 INVALID_CURSOR`(): Unit =
        with(iam) {
            val id = UUID.randomUUID().toString()
            val cases =
                listOf(
                    // Base64 válido, mas com uma data impossível na chave de data.
                    "/api/v1/schedule" to PageCursor.of("2026-02-30", id),
                    "/api/v1/schedule" to PageCursor.of("ontem", id),
                    "/api/v1/schedule" to PageCursor.of(today().toString(), "not-a-uuid"),
                    "/api/v1/employees" to PageCursor.of("ana", "not-a-uuid"),
                    "/api/v1/departments" to PageCursor.of("not-a-uuid"),
                    "/api/v1/shifts" to PageCursor.of("not-a-uuid"),
                    "/api/v1/schedule-patterns" to PageCursor.of("not-a-uuid"),
                )
            assertSoftly {
                cases.forEach { (path, cursor) ->
                    withClue("$path ${cursor.keys}") {
                        call(GET, "$path?cursor=${cursor.encode()}", asAdmin()).problem(400, "INVALID_CURSOR")
                    }
                }
            }
            // O cursor que a própria consulta devolve continua valendo.
            call(GET, "/api/v1/schedule?cursor=${PageCursor.of(today().toString(), id).encode()}", asAdmin()).ok()
        }

    @Test
    fun `deactivating a pattern waits for the lock of the employee, like the other schedule writes`(): Unit =
        with(iam) {
            val department = staff.createDepartment()
            val shift = staff.createShift(department, day.first, day.second)
            val employee = staff.createEmployee(department)
            val pattern = staff.createPattern(employee, shift, SchedulePatternKind.TWELVE_THIRTY_SIX, today())
            val locked = CountDownLatch(1)
            val holder =
                thread {
                    transactions.executeWithoutResult {
                        jdbc
                            .sql(
                                "SELECT id FROM staff.employee WHERE id = :id FOR UPDATE",
                            ).param("id", employee)
                            .query()
                            .listOfRows()
                        locked.countDown()
                        Thread.sleep(LOCK_HOLD_MILLIS)
                    }
                }
            locked.await()

            val started = System.nanoTime()
            call(POST, "/api/v1/schedule-patterns/$pattern/deactivate", asAdmin()).ok()["active"].asBoolean() shouldBe
                false
            val waitedMillis = (System.nanoTime() - started) / NANOS_PER_MILLI
            holder.join()

            // Só concluiu depois que a outra transação (como uma geração) soltou o colaborador.
            waitedMillis shouldBeGreaterThanOrEqual LOCK_HOLD_MILLIS / 2
        }

    // --- Permissões e If-Match de cada escrita -----------------------------------------------------------

    @Test
    @Suppress("LongMethod") // um cenário de ponta a ponta, com cada recusa no seu passo
    fun `every write endpoint refuses without its permission, and every edit without If-Match`(): Unit =
        with(iam) {
            val department = staff.createDepartment()
            val shift = staff.createShift(department, day.first, day.second)
            val employee = staff.createEmployee(department)
            val start = today()
            val pattern = staff.createPattern(employee, shift, SchedulePatternKind.TWELVE_THIRTY_SIX, start)
            val patternBody = mapOf("kind" to "TWELVE_THIRTY_SIX", "shiftId" to shift, "startsOn" to start.toString())
            // Quem tem tudo de leitura e a outra permissão de escrita, mas não a do endpoint.
            val read =
                listOf(StaffPermissions.EMPLOYEES_READ, StaffPermissions.SCHEDULE_READ).map {
                    TestGrant(definition(it), ALL)
                }
            val onlySchedule =
                createUserWith(*(read + TestGrant(definition(StaffPermissions.SCHEDULE_MANAGE), ALL)).toTypedArray())
            val onlyRegistry =
                createUserWith(*(read + TestGrant(definition(StaffPermissions.EMPLOYEES_MANAGE), ALL)).toTypedArray())

            data class Write(
                val method: HttpMethod,
                val path: String,
                val body: Any?,
                val etagPath: String? = null,
            )
            val registryWrites =
                listOf(
                    Write(POST, "/api/v1/departments", mapOf("name" to staff.unique("Setor"))),
                    Write(
                        PUT,
                        "/api/v1/departments/$department",
                        mapOf("name" to staff.unique("Setor")),
                        "/api/v1/departments/$department",
                    ),
                    Write(POST, "/api/v1/employees", staff.employeeBody(department, "Nova")),
                    Write(
                        PUT,
                        "/api/v1/employees/$employee",
                        staff.employeeBody(department, "Editada"),
                        "/api/v1/employees/$employee",
                    ),
                    Write(POST, "/api/v1/employees/$employee/deactivate", null),
                    Write(POST, "/api/v1/employees/$employee/reactivate", null),
                    Write(
                        POST,
                        "/api/v1/shifts",
                        mapOf(
                            "departmentId" to department,
                            "name" to "Novo",
                            "start" to "08:00",
                            "end" to "16:00",
                        ),
                    ),
                    Write(
                        PUT,
                        "/api/v1/shifts/$shift",
                        mapOf("name" to "Editado", "start" to "08:00", "end" to "16:00"),
                        "/api/v1/shifts/$shift",
                    ),
                )
            val scheduleWrites =
                listOf(
                    Write(
                        POST,
                        "/api/v1/schedule-patterns",
                        patternBody + ("employeeId" to employee) + ("startsOn" to start.plusDays(500).toString()),
                    ),
                    Write(PUT, "/api/v1/schedule-patterns/$pattern", patternBody, "/api/v1/schedule-patterns/$pattern"),
                    Write(POST, "/api/v1/schedule-patterns/$pattern/deactivate", null),
                    Write(
                        POST,
                        "/api/v1/schedule/generate",
                        mapOf(
                            "from" to start.toString(),
                            "to" to start.toString(),
                            "employeeId" to employee,
                        ),
                    ),
                    Write(
                        POST,
                        "/api/v1/schedule/entries",
                        mapOf(
                            "employeeId" to employee,
                            "kind" to "DAY_OFF",
                            "from" to start.toString(),
                        ),
                    ),
                )

            assertSoftly {
                val cases = registryWrites.map { it to onlySchedule } + scheduleWrites.map { it to onlyRegistry }
                cases.forEach { (write, user) ->
                    withClue("${write.method} ${write.path}") {
                        val etag = write.etagPath?.let(staff::etagOf)
                        call(
                            write.method,
                            write.path,
                            asTestUser(user),
                            write.body,
                            ifMatch = etag,
                        ).problem(403, "PERMISSION_DENIED")
                        if (write.etagPath != null) {
                            call(write.method, write.path, asAdmin(), write.body).problem(428, "PRECONDITION_REQUIRED")
                            call(
                                write.method,
                                write.path,
                                asAdmin(),
                                write.body,
                                ifMatch = "\"999\"",
                            ).problem(412, "PRECONDITION_FAILED")
                            staff.etagOf(write.etagPath) shouldBe etag
                        }
                    }
                }
            }
            // Nada mudou com as recusas.
            call(GET, "/api/v1/employees/$employee", asAdmin()).ok()["name"].asString() shouldNotBe "Editada"
            call(GET, "/api/v1/schedule-patterns/$pattern", asAdmin()).ok()["active"].asBoolean() shouldBe true
            staff.entries("from=$start&employeeId=$employee").shouldBeEmpty()
        }

    @Test
    fun `the permissions are in the catalog with the suggested templates`() {
        val holders =
            jdbc
                .sql(
                    """
                    SELECT r.template_key, g.permission_code, g.scope FROM iam.role_grant g JOIN iam.role r ON r.id = g.role_id
                    WHERE r.template_key IS NOT NULL AND r.template_key <> 'ADMINISTRATOR' AND g.permission_code LIKE 'staff.%'
                    """.trimIndent(),
                ).query { rs, _ -> "${rs.getString(1)}:${rs.getString(2)}:${rs.getString(3)}" }
                .list()

        holders shouldContainAll
            listOf(
                "MANAGER:staff.employees.read:ALL",
                "MANAGER:staff.employees.manage:ALL",
                "MANAGER:staff.schedule.read:ALL",
                "MANAGER:staff.schedule.manage:ALL",
                "HOUSEKEEPER:staff.employees.read:ALL",
                "HOUSEKEEPER:staff.schedule.read:ALL",
                "ROOM_ATTENDANT:staff.schedule.read:OWN",
                "MAINTENANCE:staff.schedule.read:OWN",
                "WAITER:staff.schedule.read:OWN",
            )
        StaffPermissions.permissions.none { it.sensitive } shouldBe true
    }

    private fun auditActions(
        entityType: String,
        entityId: UUID,
    ): List<String> =
        jdbc
            .sql(
                "SELECT action FROM audit.audit_entry WHERE entity_type = :type AND entity_id = :id " +
                    "ORDER BY occurred_at, id",
            ).param("type", entityType)
            .param("id", entityId.toString())
            .query(String::class.java)
            .list()
            .filterNotNull()

    private companion object {
        const val LOCK_HOLD_MILLIS = 1500L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
