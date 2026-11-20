package com.zeny.hotel.reporting

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.rooms.RoomsTestSupport
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.HousekeepingReportSeed
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * `GET /api/v1/reports/housekeeping` (M6 E09, D5): UHs e créditos por camareira, tempo médio por `worked_seconds`
 * sem as pausas e taxa de reprovação por tipo de limpeza e tipo de UH, sobre camareiras, tipos e UHs criados aqui.
 * O banco é compartilhado: as asserções filtram por eles e o período cai num ano que nenhum outro teste usa.
 * Também: período inválido, permissão e a ausência de texto livre e de dado de hóspede.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class HousekeepingReportApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
) {
    private val rooms = RoomsTestSupport(iam)
    private val seed = HousekeepingReportSeed(jdbc)

    // Um dia por teste, 30 dias à parte, num ano só desta classe: o banco é compartilhado e o relatório soma o período.
    private val day = LocalDate.of(2033, 1, 1).plusDays(DAY_STEP * sequence.getAndIncrement())

    private val admin = iam.asAdmin()

    private fun report(
        from: LocalDate,
        to: LocalDate,
        auth: org.springframework.test.web.servlet.request.RequestPostProcessor = admin,
    ) = iam.call(GET, "/api/v1/reports/housekeeping?from=$from&to=$to", auth)

    @Suppress("LongParameterList") // os ids do conjunto de dados do teste
    private class Dataset(
        val attendant1: UUID = UuidV7.generate(),
        val attendant2: UUID = UuidV7.generate(),
        val attendant3: UUID = UuidV7.generate(),
        val typeX: UUID,
        val typeY: UUID,
        val roomTypeA: UUID,
        val roomTypeB: UUID,
    )

    private fun dataset(): Dataset {
        val data =
            Dataset(
                typeX = seed.cleaningType(),
                typeY = seed.cleaningType(),
                roomTypeA = rooms.createRoomType(),
                roomTypeB = rooms.createRoomType(),
            )
        val a1 = rooms.createRoom(data.roomTypeA)
        val a2 = rooms.createRoom(data.roomTypeA)
        val b1 = rooms.createRoom(data.roomTypeB)
        val x = data.typeX
        val y = data.typeY
        // Camareira 1: três UHs concluídas (uma já aprovada, uma à espera de inspeção) e uma área; 3+3+2+1 créditos.
        val x1 = seed.task(x, day, room = a1, status = "DONE", employee = data.attendant1, worked = 1800)
        val x2 = seed.task(x, day, room = a2, status = "AWAITING_INSPECTION", employee = data.attendant1, worked = 3600)
        seed.task(y, day, room = b1, status = "DONE", employee = data.attendant1, worked = 600, credits = 2)
        seed.task(y, day, area = UuidV7.generate(), status = "DONE", employee = data.attendant1, credits = 1)
        // Camareira 2: uma UH concluída e uma em andamento (não conta).
        seed.task(x, day, room = b1, status = "DONE", employee = data.attendant2, worked = 900)
        seed.task(y, day, room = a2, status = "IN_PROGRESS", employee = data.attendant2, credits = 2)
        // Camareira 3: só uma tarefa atribuída, sem concluir.
        seed.task(y, day, room = a1, status = "ASSIGNED", employee = data.attendant3, credits = 2)
        // Fora do período: não conta em nada.
        seed.task(x, day.plusDays(10), room = a1, status = "DONE", employee = data.attendant1, worked = 99_999)
        // Inspeções das tarefas: x1 reprovada, x2 aprovada.
        seed.inspection(x1, a1, "REJECTED")
        seed.inspection(x2, a2, "APPROVED")
        // Voluntárias: duas no período (uma reprovada) e uma fora.
        seed.inspection(null, a1, "REJECTED", day.atTime(15, 0).toInstant(ZoneOffset.UTC))
        seed.inspection(null, b1, "APPROVED", day.atTime(16, 0).toInstant(ZoneOffset.UTC))
        seed.inspection(null, b1, "APPROVED", day.plusDays(10).atTime(16, 0).toInstant(ZoneOffset.UTC))
        return data
    }

    private fun JsonNode.attendant(id: UUID) =
        this["attendants"].values().single { it["employeeId"].asString() == "$id" }

    private fun JsonNode.cleaning(
        type: UUID,
        roomType: UUID,
    ) = this["cleaningTypes"].values().single {
        it["cleaningTypeId"].asString() == "$type" && it["roomTypeId"].asString() == "$roomType"
    }

    @Test
    fun `rooms and credits per attendant count only finished tasks, and an attendant with none has zeros`() {
        val data = dataset()

        val body = with(iam) { report(day, day.plusDays(1)).ok() }

        body.attendant(data.attendant1).let {
            it["rooms"].asLong() shouldBe 3
            it["credits"].asLong() shouldBe 9
        }
        body.attendant(data.attendant2).let {
            it["rooms"].asLong() shouldBe 1
            it["credits"].asLong() shouldBe 3
        }
        body.attendant(data.attendant3).let {
            it["rooms"].asLong() shouldBe 0
            it["credits"].asLong() shouldBe 0
        }
        // Do que mais fez ao que menos fez, entre as camareiras criadas aqui.
        val ours = setOf(data.attendant1, data.attendant2, data.attendant3).map { "$it" }
        body["attendants"].values().map { it["employeeId"].asString() }.filter { it in ours } shouldContainExactly
            listOf(data.attendant1, data.attendant2, data.attendant3).map { "$it" }
    }

    @Test
    fun `the average time uses the worked seconds and the rejection rate the inspections of the period tasks`() {
        val data = dataset()

        val body = with(iam) { report(day, day.plusDays(1)).ok() }

        // X em A: x1 (1800 s) e x2 (3600 s) → 45,0 min; 2 inspeções, 1 reprovada → 50%.
        body.cleaning(data.typeX, data.roomTypeA).let {
            it["tasks"].asLong() shouldBe 2
            it["averageMinutes"].asDouble() shouldBe 45.0
            it["inspections"].asLong() shouldBe 2
            it["rejections"].asLong() shouldBe 1
            it["rejectionRate"].asString() shouldBe "50.0000"
        }
        // X em B: 900 s → 15,0 min; sem inspeção: taxa nula (não zero).
        body.cleaning(data.typeX, data.roomTypeB).let {
            it["averageMinutes"].asDouble() shouldBe 15.0
            it["inspections"].asLong() shouldBe 0
            it["rejectionRate"].isNull shouldBe true
        }
        // Y em B: 600 s → 10,0 min. Y em A: só uma tarefa em andamento, sem tempo médio.
        body.cleaning(data.typeY, data.roomTypeB)["averageMinutes"].asDouble() shouldBe 10.0
        body.cleaning(data.typeY, data.roomTypeA).let {
            it["tasks"].asLong() shouldBe 0
            it["averageMinutes"].isNull shouldBe true
        }
        // A tarefa de área não entra por tipo de UH.
        body["cleaningTypes"].values().count { it["cleaningTypeId"].asString() == "${data.typeY}" } shouldBe 2
    }

    @Test
    fun `the voluntary inspections of the period are apart, without a type`() {
        // Total do hotel: lido antes e depois de semear, e afirmado por diferença (a taxa exata é do teste de unidade).
        val before = with(iam) { report(day, day.plusDays(1)).ok() }["voluntaryInspections"]
        dataset()
        val body = with(iam) { report(day, day.plusDays(1)).ok() }

        body["voluntaryInspections"]["inspections"].asLong() - before["inspections"].asLong() shouldBe 2
        body["voluntaryInspections"]["rejections"].asLong() - before["rejections"].asLong() shouldBe 1
        body["from"].asString() shouldBe "$day"
        body["to"].asString() shouldBe "${day.plusDays(1)}"
    }

    @Test
    fun `an invalid period is refused with 400 and the limit of 366 days is inclusive`() {
        with(iam) {
            report(day, day.minusDays(1))
                .problem(400, "REPORT_PERIOD_INVALID")["errors"][0]["field"]
                .asString() shouldBe "to"
            report(day, day.plusDays(366)).problem(400, "REPORT_PERIOD_INVALID")
            report(day, day.plusDays(365)).ok()
            call(GET, "/api/v1/reports/housekeeping?to=$day", admin).problem(400, "VALIDATION_FAILED")
            call(GET, "/api/v1/reports/housekeeping?from=&to=$day", admin).problem(400, "VALIDATION_FAILED")
        }
    }

    @Test
    fun `without the permission the report is refused with 403, and with it the report is served`() {
        val none = iam.asTestUser(iam.createUser())
        val reader =
            iam.asTestUser(
                iam.createUserWith(
                    TestGrant(
                        ReportingPermissions.permissions.single {
                            it.code ==
                                ReportingPermissions.HOUSEKEEPING_READ
                        },
                        ALL,
                    ),
                ),
            )
        val onlyDashboard =
            iam.asTestUser(
                iam.createUserWith(
                    TestGrant(
                        ReportingPermissions.permissions.single {
                            it.code ==
                                ReportingPermissions.DASHBOARD_READ
                        },
                        ALL,
                    ),
                ),
            )

        with(iam) {
            report(day, day, none).problem(403, "PERMISSION_DENIED")
            report(day, day, onlyDashboard).problem(403, "PERMISSION_DENIED")
            // A permissão vem antes da validação do período.
            report(day, day.minusDays(1), none).problem(403, "PERMISSION_DENIED")
            report(day, day, reader).ok()
        }
    }

    @Test
    fun `the response is a typed object with no free text and no guest data`() {
        dataset()

        val body = with(iam) { report(day, day.plusDays(1)).ok() }

        body.propertyNames().toSet() shouldBe
            setOf("from", "to", "attendants", "cleaningTypes", "voluntaryInspections")
        body["attendants"].values().forEach {
            it.propertyNames().toSet() shouldBe
                setOf("employeeId", "rooms", "credits")
        }
        body["cleaningTypes"].values().forEach {
            it.propertyNames().toSet() shouldBe
                setOf(
                    "cleaningTypeId",
                    "roomTypeId",
                    "tasks",
                    "averageMinutes",
                    "inspections",
                    "rejections",
                    "rejectionRate",
                )
        }
        body["voluntaryInspections"].propertyNames().toSet() shouldBe
            setOf("inspections", "rejections", "rejectionRate")
        // Nenhum array de array: as listas são de objetos.
        (body["attendants"].values() + body["cleaningTypes"].values()).all { it.isObject } shouldBe true
    }

    private companion object {
        // Um dia por instância (uma por teste), 30 dias à parte: sem colisão entre métodos nem entre classes.
        const val DAY_STEP = 30L
        val sequence = AtomicInteger()
    }
}
