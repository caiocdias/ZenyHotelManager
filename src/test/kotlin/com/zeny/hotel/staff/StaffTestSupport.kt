package com.zeny.hotel.staff

import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Apoio dos testes do `staff`: cria setores, colaboradores, turnos e padrões pela API, como o administrador. */
class StaffTestSupport(
    private val iam: IamTestSupport,
) {
    private val admin: RequestPostProcessor get() = iam.asAdmin()

    fun unique(prefix: String) = "$prefix ${UuidV7.generate().toString().takeLast(SUFFIX_LENGTH)}"

    fun createDepartment(name: String = unique("Setor")): UUID = id(post("/api/v1/departments", mapOf("name" to name)))

    fun createEmployee(
        departmentId: UUID,
        name: String = unique("Colaboradora"),
        userId: UUID? = null,
    ): UUID = id(post("/api/v1/employees", employeeBody(departmentId, name, userId)))

    fun employeeBody(
        departmentId: UUID,
        name: String,
        userId: UUID? = null,
    ): Map<String, Any?> =
        mapOf(
            "name" to name,
            "position" to "Camareira",
            "phone" to "+55 11 98765-4321",
            "email" to "colaboradora@example.com",
            "departmentId" to departmentId,
            "userId" to userId,
        )

    fun createShift(
        departmentId: UUID,
        start: LocalTime,
        end: LocalTime,
        name: String = unique("Turno"),
    ): UUID =
        id(
            post(
                "/api/v1/shifts",
                mapOf(
                    "departmentId" to departmentId,
                    "name" to name,
                    "start" to start.toString(),
                    "end" to end.toString(),
                ),
            ),
        )

    @Suppress("LongParameterList") // os campos do padrão, com padrões
    fun createPattern(
        employeeId: UUID,
        shiftId: UUID,
        kind: SchedulePatternKind,
        startsOn: LocalDate,
        endsOn: LocalDate? = null,
        weekdays: List<String> = emptyList(),
    ): UUID =
        id(
            post(
                "/api/v1/schedule-patterns",
                mapOf(
                    "employeeId" to employeeId,
                    "shiftId" to shiftId,
                    "kind" to kind.name,
                    "startsOn" to startsOn.toString(),
                    "endsOn" to endsOn?.toString(),
                    "weekdays" to weekdays,
                ),
            ),
        )

    fun generate(
        from: LocalDate,
        to: LocalDate,
        departmentId: UUID? = null,
        employeeId: UUID? = null,
    ): JsonNode =
        with(iam) {
            call(
                POST,
                "/api/v1/schedule/generate",
                admin,
                mapOf(
                    "from" to from.toString(),
                    "to" to to.toString(),
                    "departmentId" to departmentId,
                    "employeeId" to employeeId,
                ),
            ).ok()
        }

    @Suppress("LongParameterList") // os campos do lançamento, com padrões
    fun record(
        employeeId: UUID,
        kind: ScheduleEntryKind,
        from: LocalDate,
        to: LocalDate = from,
        shiftId: UUID? = null,
        note: String? = null,
    ): JsonNode =
        with(iam) {
            call(
                POST,
                "/api/v1/schedule/entries",
                admin,
                mapOf(
                    "employeeId" to employeeId,
                    "kind" to kind.name,
                    "from" to from.toString(),
                    "to" to to.toString(),
                    "shiftId" to shiftId,
                    "note" to note,
                ),
            ).ok()
        }

    /** Todas as entradas da consulta (seguindo as páginas). */
    fun entries(
        query: String,
        auth: RequestPostProcessor = admin,
    ): List<JsonNode> {
        val items = mutableListOf<JsonNode>()
        var cursor: String? = null
        do {
            val page =
                with(iam) {
                    call(GET, "/api/v1/schedule?$query&limit=200" + cursor?.let { "&cursor=$it" }.orEmpty(), auth).ok()
                }
            items += page["items"].values()
            cursor = page["nextCursor"].takeUnless { it.isNull }?.asString()
        } while (cursor != null)
        return items
    }

    fun etagOf(path: String): String {
        val response: MockHttpServletResponse = iam.call(GET, path, admin)
        withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 200 }
        return response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
    }

    private fun post(
        path: String,
        body: Any,
    ): MockHttpServletResponse = iam.call(POST, path, admin, body)

    private fun id(response: MockHttpServletResponse): UUID =
        with(iam) { UUID.fromString(response.expectStatus(201)!!["id"].asString()) }

    companion object {
        private const val SUFFIX_LENGTH = 12

        fun definition(code: PermissionCode) = StaffPermissions.permissions.single { it.code == code }
    }
}
