package com.zeny.hotel

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.zeny.hotel.billing.BillingTestSupport
import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistsTestSupport
import com.zeny.hotel.checklists.SyncCommand
import com.zeny.hotel.endofday.EndOfDayRealStepsTestConfiguration
import com.zeny.hotel.endofday.EndOfDayTestRuns
import com.zeny.hotel.fnrh.application.FnrhSendRun
import com.zeny.hotel.fnrh.application.FnrhSubmissionSender
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.guests.GuestsTestSupport.Companion.newCpf
import com.zeny.hotel.hotel.BusinessDateAdvanced
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.AssignmentPlanTestSupport.Companion.itemsOf
import com.zeny.hotel.housekeeping.AssignmentPlanTestSupport.Companion.unassignedOf
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.reservations.GuestCheckedOut
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.reservations.ReservationsTestSupport.Operator
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.DepartmentKey
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import com.zeny.hotel.testsupport.FnrhTestConfiguration
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.enableFnrh
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.PATCH
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * Cenário ponta a ponta da Fase 1 (gate do M6, E12, D1 do plano), pela API HTTP, com os módulos reais (só a FNRH no
 * WireMock), no banco isolado do fechamento (`@EndOfDayIntegrationTest`, H10) e com o fechamento real de 11 etapas.
 *
 * Operadores pelos modelos de role do boot: o Gerente cadastra e aprova, a Recepcionista reserva, faz o check-in, o
 * fechamento de D0 (com a aprovação do Gerente), o pagamento e o check-out, a Governanta distribui e inspeciona e a
 * Camareira executa as tarefas pelos comandos offline. O Administrador só liga a FNRH (sem modelo de role).
 *
 * A estadia é de duas noites (D0 a D2), porque a permanência só nasce para quem chegou antes da data e não sai nela:
 * D0 fecha com a diária, a UH Suja, a data em D1, o snapshot e o `DAY_REPORT` de D0 e as rotinas de checklist de D1; a
 * permanência de D1 é distribuída, executada e inspecionada; D1 fecha com a segunda diária; em D2 o saldo é pago, o
 * check-out encerra a conta sem diária a mais e a tarefa de saída é gerada e concluída. O banco acumula execuções,
 * pendências e tarefas de outros testes: as conferências olham só a reserva, a UH e as execuções deste teste, e o teste
 * termina com [EndOfDayTestRuns.settle], a configuração da FNRH, a rotina e o [MutableClock] devolvidos.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class, FnrhTestConfiguration::class)
@Suppress("LongParameterList", "LargeClass") // os beans que o cenário usa; o cenário do marco inteiro
class MilestoneM6EndToEndTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val staffApi: StaffApi,
    @Autowired private val sender: FnrhSubmissionSender,
    @Autowired private val clock: MutableClock,
    @Autowired private val context: ApplicationContext,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val billing: BillingTestSupport = support.billing
    private val templates = ChecklistsTestSupport(iam)

    /** O PIN de aprovação do Gerente, sorteado. */
    private val managerPin: String = (PIN_MIN..PIN_MAX).random().toString()

    private val manager: Operator by lazy {
        Operator(userWithTemplate("MANAGER").also { iam.setPin(it, managerPin) })
    }
    private val receptionist: Operator by lazy { Operator(userWithTemplate("RECEPTIONIST")) }
    private val housekeeper: Operator by lazy { Operator(userWithTemplate("HOUSEKEEPER")) }
    private val attendant: Operator by lazy { Operator(userWithTemplate("ROOM_ATTENDANT")) }

    /** As colunas de `fnrh.settings` que o teste muda, para devolvê-las no `finally`. */
    private data class FnrhSettings(
        val environment: String,
        val username: String?,
        val password: String?,
        val defaultCpf: String?,
        val enabled: Boolean,
    )

    @Test
    @Suppress("LongMethod") // o cenário do marco, passo a passo
    fun `a two-night stay goes through two real closes, the housekeeping tasks and a zero balance check-out`() {
        val clockStart = clock.instant()
        val originalFnrh = fnrhSettings()
        FnrhTestServer.reset()
        var routine: UUID? = null
        try {
            // --- Preparo: a FNRH ligada, os cadastros, a camareira escalada e os modelos de checklist ------------
            iam.enableFnrh()
            val d0 = hotelApi.currentBusinessDate()
            val d1 = d0.plusDays(1)
            val d2 = d0.plusDays(2)
            val stay = newStay(d0)
            stubFnrhFor(stay)
            val employee = scheduledAttendant(d1, d2)
            val taskTemplate = housekeepingTemplate(stay.type)
            inspectionTemplate(stay.type)
            routine = dailyRoutine(taskTemplate, stay.room)

            // --- A Recepcionista reserva D0 a D2 com a hora prevista de chegada --------------------------------
            val booked = book(stay, d0, d2)
            support.reservation(booked.id)["reservation"]["expectedArrivalTime"].asString() shouldBe
                EXPECTED_ARRIVAL_TIME
            val nights = nightlyAmounts(booked.id)
            nights.map { it.first } shouldContainExactly listOf(d0, d1)

            // --- Check-in em D0 e a submissão FNRH do check-in entregue ----------------------------------------
            api(POST, "/api/v1/reservations/${booked.id}/assign-room", receptionist, mapOf("roomId" to stay.room)).ok()
            val checkIn =
                api(
                    POST,
                    "/api/v1/reservations/${booked.id}/check-in",
                    receptionist,
                    mapOf("guests" to listOf(stayGuest(stay.holder))),
                ).expect(200)
            checkIn["reservation"]["status"].asString() shouldBe "IN_HOUSE"
            checkIn["room"]["occupancy"].asString() shouldBe "OCCUPIED"
            sendOwnFnrhSubmissions(booked.id, FnrhOperation.REGISTER_RESERVATION, FnrhOperation.CHECK_IN)
            fnrhStatuses(booked.id) shouldContainExactly
                listOf(
                    FnrhOperation.REGISTER_RESERVATION to FnrhSubmissionStatus.SENT,
                    FnrhOperation.CHECK_IN to FnrhSubmissionStatus.SENT,
                )
            fnrhCallsOf(stay, booked.code) shouldContainExactly
                listOf(
                    "POST /reservas",
                    "GET /pessoas/documento/CPF/${stay.cpf}",
                    "POST /pessoas",
                    "POST /reservas/${stay.remote}/hospedes",
                )

            // --- Fechamento de D0 pela Recepcionista, com a aprovação do Gerente --------------------------------
            val required =
                closeRequest(receptionist, d0).problem(403, "APPROVAL_REQUIRED").also {
                    it["action"].asString() shouldBe "endofday.run.start"
                    it["permission"].asString() shouldBe "endofday.runs.execute"
                    it["resourceRef"].asString() shouldBe "endofday.business-date:$d0"
                }
            withClue("a recusa por falta de aprovação não cria execução") { runsOn(d0) shouldBe 0 }
            val approval = approvedByManager(required)
            val firstRun = completedRun(closeRequest(receptionist, d0, approval["approvalToken"].asString()))
            firstRun["trigger"].asString() shouldBe "MANUAL"
            firstRun["triggeredBy"].asString() shouldBe
                receptionist.user.id.value
                    .toString()
            val firstRunId = UUID.fromString(firstRun["id"].asString())

            // A diária da noite D0, uma vez; a UH Suja; a data em D1; o snapshot, o arquivo e as rotinas de D1.
            roomCharges(booked.folio) shouldContainExactly listOf(d0 to nights[0].second)
            roomsApi.status(RoomId(stay.room))!!.housekeepingStatus shouldBe HousekeepingStatus.DIRTY
            hotelApi.currentBusinessDate() shouldBe d1
            countOf("SELECT count(*) FROM reporting.daily_statistics WHERE business_date = :date", d0) shouldBe 1
            countOf(
                "SELECT count(*) FROM reporting.archived_report WHERE business_date = :date AND kind = 'DAY_REPORT'",
                d0,
            ) shouldBe 1
            await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until { routineExecutions(routine, d1) == 1 }
            issuesAbout(firstRunId, booked).shouldBeEmpty()

            // --- A permanência de D1: gerada pelo avanço, distribuída e publicada ------------------------------
            awaitHousekeepingListener(BusinessDateAdvanced::class.java, "\"current\":\"$d1\"")
            tasksOf(stay.room).map { it.date to it.source } shouldContainExactly listOf(d1 to "STAYOVER")
            val stayover = tasksOf(stay.room).single().id
            publishPlan(d1, employee, stayover)
            task(stayover).let {
                it["status"].asString() shouldBe "ASSIGNED"
                it["assignedEmployeeId"].asString() shouldBe employee.toString()
            }
            panel(attendant.user).map { it["type"].asString() } shouldContainExactly
                listOf("notifications.task-assigned")

            // --- A Camareira executa pelo comando offline (início e conclusão com o checklist) -----------------
            executedOffline(stayover)
            task(stayover)["status"].asString() shouldBe "DONE"
            checklistOf(stayover) shouldBe (taskTemplate to "COMPLETED")
            roomsApi.status(RoomId(stay.room))!!.let {
                it.housekeepingStatus shouldBe HousekeepingStatus.CLEAN
                it.occupancy.name shouldBe "OCCUPIED"
            }

            // --- A Governanta inspeciona e aprova a UH: Inspecionada -------------------------------------------
            // A permanência da carga não pede inspeção (STAYOVER, D5 da E01): a UH sai dela Limpa e sem
            // pendência, e a aprovação é a inspeção voluntária da E05.
            api(POST, "/api/v1/room-inspections", housekeeper, mapOf("roomId" to stay.room)).ok()["inspection"].let {
                it["status"].asString() shouldBe "COMPLETED"
                it["result"].asString() shouldBe "APPROVED"
            }
            roomsApi.status(RoomId(stay.room))!!.housekeepingStatus shouldBe HousekeepingStatus.INSPECTED

            // --- Fechamento de D1 pelo Gerente (com a permissão): a diária de D1, uma vez, e a data em D2 -------
            val secondRun = completedRun(closeRequest(manager, d1))
            val secondRunId = UUID.fromString(secondRun["id"].asString())
            roomCharges(booked.folio) shouldContainExactly listOf(d0 to nights[0].second, d1 to nights[1].second)
            hotelApi.currentBusinessDate() shouldBe d2
            issuesAbout(secondRunId, booked).shouldBeEmpty()
            awaitHousekeepingListener(BusinessDateAdvanced::class.java, "\"current\":\"$d2\"")
            withClue("quem sai em D2 não tem permanência em D2") {
                tasksOf(stay.room).map { it.date to it.source } shouldContainExactly listOf(d1 to "STAYOVER")
            }

            // --- Em D2: o saldo pago e o check-out com saldo zero, sem diária a mais -----------------------------
            openShift(receptionist)
            val due = billing.balance(booked.folio)
            due shouldBe nights.fold(BigDecimal.ZERO) { sum, night -> sum + BigDecimal(night.second) }.toPlainString()
            api(
                POST,
                "/api/v1/folios/${booked.folio}/payments",
                receptionist,
                mapOf("method" to "CASH", "amount" to due),
            ).expect(201)
            val checkOut = api(POST, "/api/v1/reservations/${booked.id}/check-out", receptionist).expect(200)
            checkOut["reservation"]["status"].asString() shouldBe "CHECKED_OUT"
            checkOut["stayCharges"].size() shouldBe 0
            checkOut["closedFolios"].values().map { it["folioId"].asString() to it["status"].asString() } shouldBe
                listOf(booked.folio.toString() to "CLOSED")
            checkOut["room"]["occupancy"].asString() shouldBe "VACANT"
            checkOut["room"]["housekeepingStatus"].asString() shouldBe "DIRTY"
            support.status(booked.id) shouldBe "CHECKED_OUT"
            billing.folio(booked.folio)["status"].asString() shouldBe "CLOSED"
            billing.balance(booked.folio) shouldBe "0.00"
            roomCharges(booked.folio) shouldContainExactly listOf(d0 to nights[0].second, d1 to nights[1].second)

            // --- A tarefa de saída de D2: gerada pelo check-out e concluída (execução e inspeção) ---------------
            awaitHousekeepingListener(GuestCheckedOut::class.java, "\"reservationId\":\"${booked.id}\"")
            tasksOf(stay.room).map { it.date to it.source } shouldContainExactly
                listOf(d1 to "STAYOVER", d2 to "CHECKOUT")
            val departure = tasksOf(stay.room).single { it.source == "CHECKOUT" }.id
            publishPlan(d2, employee, departure)
            executedOffline(departure)
            task(departure)["status"].asString() shouldBe "AWAITING_INSPECTION"
            roomsApi.status(RoomId(stay.room))!!.housekeepingStatus shouldBe HousekeepingStatus.CLEAN
            inspectedAndApproved(departure)
            task(departure)["status"].asString() shouldBe "DONE"
            checklistOf(departure) shouldBe (taskTemplate to "COMPLETED")
            roomsApi.status(RoomId(stay.room))!!.let {
                it.housekeepingStatus shouldBe HousekeepingStatus.INSPECTED
                it.occupancy.name shouldBe "VACANT"
            }

            // --- A submissão FNRH do check-out entregue, como a última chamada da reserva -----------------------
            sendOwnFnrhSubmissions(booked.id, FnrhOperation.CHECK_OUT)
            fnrhStatuses(booked.id) shouldContainExactly
                listOf(
                    FnrhOperation.REGISTER_RESERVATION to FnrhSubmissionStatus.SENT,
                    FnrhOperation.CHECK_IN to FnrhSubmissionStatus.SENT,
                    FnrhOperation.CHECK_OUT to FnrhSubmissionStatus.SENT,
                )
            fnrhCallsOf(stay, booked.code).last() shouldBe "PATCH /hospedes/${stay.guestRemote}/checkout"
        } finally {
            try {
                EndOfDayTestRuns.settle(context)
            } finally {
                // A configuração global primeiro; a rotina (que só gera execuções da própria UH) por último.
                restoreFnrh(originalFnrh)
                FnrhTestServer.reset()
                clock.advance(Duration.between(clock.instant(), clockStart))
                routine?.let { api(POST, "/api/v1/checklist-routines/$it/deactivate", housekeeper).ok() }
            }
        }
    }

    // --- Apoio: cadastros ---------------------------------------------------------------------------------------

    /** O tipo de UH, a UH (inspecionada), o plano com preços, o titular e os ids que o Serpro devolve. */
    private class Stay(
        val type: UUID,
        val room: UUID,
        val plan: UUID,
        val holder: UUID,
        val cpf: String,
        val remote: String,
    ) {
        val person: String get() = "p-$remote"
        val guestRemote: String get() = "h-$remote"
    }

    private data class Booked(
        val id: UUID,
        val folio: UUID,
        val code: String,
    )

    /** O Gerente cadastra o tipo de UH, a UH (deixada inspecionada), as políticas, o plano e os preços. */
    private fun newStay(today: LocalDate): Stay {
        val rooms = support.inventory.rooms
        val type = api(POST, "/api/v1/room-types", manager, rooms.roomTypeBody()).expect(201).id()
        val room = api(POST, "/api/v1/rooms", manager, rooms.roomBody(type)).expect(201).id()
        support.inventory.awaitRow(type, today) { it.total == 1 }
        markRoom(room, "MARK_CLEAN")
        markRoom(room, "MARK_INSPECTED")
        val cancellation =
            api(POST, "/api/v1/cancellation-policies", manager, support.rates.cancellationBody()).expect(201).id()
        val guarantee =
            api(
                POST,
                "/api/v1/guarantee-policies",
                manager,
                support.rates.guaranteeBody(type = "NONE", depositPercent = null, dueDaysBefore = null),
            ).expect(201).id()
        val plan =
            api(POST, "/api/v1/rate-plans", manager, support.rates.planBody(listOf(type), cancellation, guarantee))
                .expect(201)
                .id()
        support.prices
            .update(
                plan,
                support.prices.selection(listOf(type), today, today.plusDays(PRICED_DAYS)),
                support.prices.prices("300.00", "400.00", "120.00"),
                auth = manager.auth,
            ).ok()
        val cpf = newCpf()
        val holder =
            api(
                POST,
                "/api/v1/guests",
                receptionist,
                support.guests.createBody(
                    profile =
                        support.guests.profileBody(
                            fullName = support.guests.uniqueName("Titular"),
                            extra = mapOf("birthDate" to LocalDate.of(1988, 3, 14), "nationality" to "BR"),
                        ),
                    documents = listOf(support.guests.document("CPF", cpf)),
                    sensitiveData = mapOf("raceColor" to "BROWN"),
                ),
            ).expect(201).id()
        return Stay(type, room, plan, holder, cpf, "r-${UuidV7.generate()}")
    }

    /** A Recepcionista reserva de [arrival] a [departure] para o titular, com a hora prevista de chegada. */
    private fun book(
        stay: Stay,
        arrival: LocalDate,
        departure: LocalDate,
    ): Booked {
        val reservation =
            support.reservation(
                stay.type,
                stay.plan,
                arrival,
                departure,
                adults = 1,
                guests = listOf(stay.holder to true),
            ) + ("expectedArrivalTime" to EXPECTED_ARRIVAL_TIME)
        val created = support.create(support.booking(stay.holder, reservation), receptionist.auth).expect(201)
        val booked = created["reservations"][0]
        return Booked(booked.id(), UUID.fromString(booked["folioId"].asString()), booked["code"].asString())
    }

    private fun stayGuest(guest: UUID): Map<String, Any?> =
        mapOf(
            "guestId" to guest,
            "primary" to true,
            "travelReason" to "LAZER_FERIAS",
            "transportMode" to "AUTOMOVEL",
        )

    /** Uma mudança manual do status de governança da UH pelo Gerente. */
    private fun markRoom(
        room: UUID,
        transition: String,
    ) {
        billing
            .call(
                POST,
                "/api/v1/rooms/$room/housekeeping-status",
                manager.auth,
                mapOf("transition" to transition),
                ifMatch = support.inventory.rooms.etagOf("/api/v1/rooms/$room/status"),
            ).ok()
    }

    /**
     * Uma camareira (usuário pelo modelo Camareira) cadastrada pelo Gerente como colaboradora do setor de governança,
     * vinculada ao usuário e escalada num turno das 7h às 15h em [dates]. Devolve o colaborador.
     */
    private fun scheduledAttendant(vararg dates: LocalDate): UUID {
        val department =
            staffApi
                .departments()
                .single { it.key == DepartmentKey.HOUSEKEEPING }
                .id.value
        val employee =
            api(
                POST,
                "/api/v1/employees",
                manager,
                mapOf(
                    "name" to "Camareira ${support.guests.suffix()}",
                    "position" to "Camareira",
                    "departmentId" to department,
                    "userId" to attendant.user.id.value,
                ),
            ).expect(201).id()
        val shift =
            api(
                POST,
                "/api/v1/shifts",
                manager,
                mapOf(
                    "departmentId" to department,
                    "name" to "Turno ${support.guests.suffix()}",
                    "start" to "07:00",
                    "end" to "15:00",
                ),
            ).expect(201).id()
        dates.forEach { date ->
            api(
                POST,
                "/api/v1/schedule/entries",
                manager,
                mapOf(
                    "employeeId" to employee,
                    "kind" to "SHIFT",
                    "from" to date.toString(),
                    "to" to date.toString(),
                    "shiftId" to shift,
                ),
            ).ok()
        }
        return employee
    }

    /**
     * O modelo de governança da Governanta (`bed` obrigatório e `towels`), que vale só para a permanência e a saída no
     * tipo de UH do teste: mais específico que qualquer modelo geral do banco isolado, e sem efeito nas UHs alheias.
     */
    private fun housekeepingTemplate(type: UUID): UUID =
        publishedTemplate(
            ChecklistContext.HOUSEKEEPING,
            mapOf(
                "roomTypeIds" to listOf(type),
                "cleaningTypeIds" to listOf(cleaningType("STAYOVER"), cleaningType("CHECKOUT")),
            ),
        )

    /** O modelo de inspeção da Governanta (`bed` obrigatório), que vale só para o tipo de UH do teste. */
    private fun inspectionTemplate(type: UUID): UUID =
        publishedTemplate(ChecklistContext.INSPECTION, mapOf("roomTypeIds" to listOf(type)))

    private fun publishedTemplate(
        context: ChecklistContext,
        applicability: Map<String, Any?>,
    ): UUID {
        val body =
            templates.templateBody(
                context = context,
                applicability = listOf(applicability),
                sections =
                    listOf(
                        templates.section(
                            "Quarto",
                            templates.item("bed", extra = mapOf("required" to true)),
                            templates.item("towels"),
                        ),
                    ),
            )
        val id = api(POST, "/api/v1/checklist-templates", housekeeper, body).expect(201).id()
        val etag = etagOf("/api/v1/checklist-templates/$id", housekeeper)
        billing.call(POST, "/api/v1/checklist-templates/$id/publish", housekeeper.auth, ifMatch = etag).expect(201)
        return id
    }

    /** A rotina diária da Governanta, no setor de governança, com o modelo [template] e a UH do teste como local. */
    private fun dailyRoutine(
        template: UUID,
        room: UUID,
    ): UUID {
        val department =
            staffApi
                .departments()
                .single { it.key == DepartmentKey.HOUSEKEEPING }
                .id.value
        val body =
            mapOf(
                "name" to "Rotina ${support.guests.suffix()}",
                "templateId" to template,
                "departmentId" to department,
                "frequency" to "DAILY",
                "weekdays" to emptyList<String>(),
                "dueAt" to "08:00",
                "location" to mapOf("type" to "rooms.room", "id" to room),
            )
        return api(POST, "/api/v1/checklist-routines", housekeeper, body).expect(201).id()
    }

    private fun cleaningType(key: String): UUID =
        jdbc
            .sql("SELECT id FROM housekeeping.cleaning_type WHERE system_key = :key")
            .param("key", key)
            .query(UUID::class.java)
            .single()

    // --- Apoio: o fechamento ------------------------------------------------------------------------------------

    /** O `POST /api/v1/end-of-day/runs` da data [date] por [operator], com a própria `Idempotency-Key`. */
    private fun closeRequest(
        operator: Operator,
        date: LocalDate,
        approvalToken: String? = null,
    ): MockHttpServletResponse =
        billing.call(
            POST,
            "/api/v1/end-of-day/runs",
            operator.auth,
            mapOf("businessDate" to date.toString()),
            approvalToken = approvalToken,
        )

    /**
     * A execução criada (`201`), lida de novo depois de o motor terminar (a resposta pode vir `RUNNING` se passar da
     * espera): `COMPLETED`, com as 11 etapas em ordem, todas `COMPLETED`.
     */
    private fun completedRun(response: MockHttpServletResponse): JsonNode {
        val created = response.expect(201)
        EndOfDayTestRuns.awaitEngine(context)
        val run = api(GET, "/api/v1/end-of-day/runs/${created["id"].asString()}", manager).ok()
        run["status"].asString() shouldBe "COMPLETED"
        run["steps"].values().map { it["name"].asString() to it["status"].asString() } shouldContainExactly
            STEPS.map { it to "COMPLETED" }
        return run
    }

    /** A aprovação dada pelo Gerente com o login e o PIN, pedida na sessão da Recepcionista. */
    private fun approvedByManager(problem: JsonNode): JsonNode =
        api(
            POST,
            "/api/v1/auth/approvals",
            receptionist,
            mapOf(
                "action" to problem["action"].asString(),
                "permission" to problem["permission"].asString(),
                "resourceRef" to problem["resourceRef"].asString(),
                "approverLogin" to manager.user.login,
                "approverPin" to managerPin,
                "reason" to "Fechamento do dia pela recepção",
            ),
        ).expect(201)

    private fun runsOn(date: LocalDate): Int =
        countOf("SELECT count(*) FROM endofday.run WHERE business_date = :date", date)

    /** As pendências da execução que citam a reserva, a conta ou o código dela. */
    private fun issuesAbout(
        run: UUID,
        booked: Booked,
    ): List<String> =
        jdbc
            .sql("SELECT kind FROM endofday.run_issue WHERE run_id = :run AND reference IN (:refs)")
            .param("run", run)
            .param("refs", listOf(booked.id.toString(), booked.folio.toString(), booked.code))
            .query(String::class.java)
            .list()
            .filterNotNull()

    /** As diárias lançadas pelo fechamento na conta: a data e o valor, em ordem. */
    private fun roomCharges(folio: UUID): List<Pair<LocalDate, String>> =
        support
            .postings(folio)
            .filter { it["key"] == "ROOM_CHARGE" }
            .map {
                withClue("a diária é do fechamento") { it["source"] shouldBe "END_OF_DAY" }
                it["businessDate"] as LocalDate to it["amount"] as String
            }

    /** As noites da reserva: a data e o valor. */
    private fun nightlyAmounts(reservation: UUID): List<Pair<LocalDate, String>> =
        support.reservation(reservation)["reservation"]["nightlyRates"].values().map {
            LocalDate.parse(it["date"].asString()) to it["amount"].asString()
        }

    private fun routineExecutions(
        routine: UUID,
        date: LocalDate,
    ): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM checklists.checklist_execution " +
                    "WHERE routine_id = :routine AND business_date = :date",
            ).param("routine", routine)
            .param("date", date)
            .query(Int::class.java)
            .single()

    private fun countOf(
        sql: String,
        date: LocalDate,
    ): Int =
        jdbc
            .sql(sql)
            .param("date", date)
            .query(Int::class.java)
            .single()

    // --- Apoio: a governança ------------------------------------------------------------------------------------

    private data class Task(
        val id: UUID,
        val date: LocalDate,
        val source: String,
    )

    /** As tarefas da UH, em ordem de data. */
    private fun tasksOf(room: UUID): List<Task> =
        jdbc
            .sql(
                "SELECT id, business_date, source FROM housekeeping.housekeeping_task WHERE room_id = :room " +
                    "ORDER BY business_date, id",
            ).param("room", room)
            .query { rs, _ ->
                Task(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("business_date", LocalDate::class.java),
                    rs.getString("source"),
                )
            }.list()

    /** Espera o listener da governança concluir a publicação do evento [type] com o [fragment]. */
    private fun awaitHousekeepingListener(
        type: Class<*>,
        fragment: String,
    ) {
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until {
            jdbc
                .sql(
                    "SELECT count(*) FROM shared.event_publication WHERE event_type = :type " +
                        "AND serialized_event LIKE :fragment AND listener_id LIKE :listener " +
                        "AND completion_date IS NOT NULL",
                ).param("type", type.name)
                .param("fragment", "%$fragment%")
                .param("listener", "%housekeeping.application.HousekeepingTaskListeners%")
                .query(Int::class.java)
                .single() == 1
        }
    }

    /**
     * A Governanta monta o plano de [date] só com a [task] para a camareira [employee] (a sugestão restrita a ela e o
     * ajuste que tira as tarefas alheias do plano) e o publica.
     */
    private fun publishPlan(
        date: LocalDate,
        employee: UUID,
        task: UUID,
    ) {
        val path = "/api/v1/assignment-plans/$date"
        val existing = api(GET, path, housekeeper).takeIf { it.status == 200 }?.getHeader(HttpHeaders.ETAG)
        val suggestion =
            billing.call(
                POST,
                "$path/suggest",
                housekeeper.auth,
                mapOf("employeeIds" to listOf(employee)),
                ifMatch = existing,
            )
        val plan = suggestion.ok()
        val foreign = (itemsOf(plan).keys + unassignedOf(plan)).filterNot { it == task }
        val adjusted =
            billing.call(
                PATCH,
                path,
                housekeeper.auth,
                mapOf("items" to listOf(mapOf("taskId" to task, "employeeId" to employee)), "remove" to foreign),
                ifMatch = suggestion.getHeader(HttpHeaders.ETAG),
            )
        itemsOf(adjusted.ok()) shouldBe mapOf(task to (employee to 1))
        billing
            .call(POST, "$path/publish", housekeeper.auth, ifMatch = adjusted.getHeader(HttpHeaders.ETAG))
            .ok()["status"]
            .asString() shouldBe "PUBLISHED"
    }

    /** A Camareira inicia e conclui a [task] pelos comandos offline, num lote, com as respostas do checklist. */
    private fun executedOffline(task: UUID) {
        val now = clock.instant()
        val start = SyncCommand(UuidV7.generate(), "housekeeping.start-task", mapOf("taskId" to task), now, now)
        val complete =
            SyncCommand(
                UuidV7.generate(),
                "housekeeping.complete-task",
                mapOf("taskId" to task, "answers" to listOf(check("bed"), check("towels"))),
                now,
                now,
            )
        val results =
            api(POST, "/api/v1/sync/commands", attendant, mapOf("commands" to listOf(start.body(), complete.body())))
                .ok()["results"]
                .values()
                .toList()
        withClue(results.toString()) {
            results.map { it["status"].asString() } shouldContainExactly listOf("APPLIED", "APPLIED")
        }
    }

    /** A Governanta inicia e conclui a inspeção da [task], aprovada, com o checklist de inspeção. */
    private fun inspectedAndApproved(task: UUID) {
        val tasks = "/api/v1/housekeeping-tasks/$task"
        val started =
            billing.call(POST, "$tasks/inspection/start", housekeeper.auth, ifMatch = etagOf(tasks, housekeeper))
        started.ok()
        val completed =
            billing.call(
                POST,
                "$tasks/inspection/complete",
                housekeeper.auth,
                mapOf("result" to "APPROVED", "answers" to listOf(check("bed"))),
                ifMatch = started.getHeader(HttpHeaders.ETAG),
            )
        completed.ok()["inspection"]["result"].asString() shouldBe "APPROVED"
    }

    private fun check(key: String): Map<String, Any?> = mapOf("itemKey" to key, "check" to "OK")

    private fun task(id: UUID): JsonNode = api(GET, "/api/v1/housekeeping-tasks/$id", housekeeper).ok()

    /** O modelo e a situação da execução do checklist da tarefa. */
    private fun checklistOf(task: UUID): Pair<UUID, String> =
        jdbc
            .sql(
                "SELECT e.template_id, e.status FROM checklists.checklist_execution e " +
                    "JOIN housekeeping.housekeeping_task t ON t.checklist_execution_id = e.id WHERE t.id = :id",
            ).param("id", task)
            .query { rs, _ -> rs.getObject("template_id", UUID::class.java) to rs.getString("status") }
            .single()

    /** As notificações do usuário no painel dele, da mais nova para a mais velha. */
    private fun panel(user: TestUser): List<JsonNode> =
        with(iam) { call(GET, "/api/v1/notifications?limit=100", asTestUser(user)).ok() }["items"].values().toList()

    // --- Apoio: o caixa -----------------------------------------------------------------------------------------

    /** Abre, como [operator], um turno de caixa num ponto de caixa novo cadastrado pelo Gerente. */
    private fun openShift(operator: Operator) {
        val cashPoint =
            api(
                POST,
                "/api/v1/cash-points",
                manager,
                mapOf("name" to "Caixa ${support.guests.suffix()}"),
            ).expect(201).id()
        api(POST, "/api/v1/cashier-shifts", operator, mapOf("cashPointId" to cashPoint, "openingFloat" to "0.00"))
            .expect(201)
    }

    // --- Apoio: a FNRH ------------------------------------------------------------------------------------------

    /** Os stubs v2.4 do Serpro para a estadia: o registro, a pessoa, o hóspede na reserva e o check-out dele. */
    private fun stubFnrhFor(stay: Stay) {
        FnrhTestServer.stubRegisterReservation_v2_4(stay.remote)
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", stay.cpf, REQUESTER_CPF)
        FnrhTestServer.server.stubFor(
            post(urlEqualTo("${FnrhTestServer.BASE_PATH}/pessoas"))
                .withHeader("Authorization", equalTo(FnrhTestServer.basicAuthorization))
                .withHeader("cpf_solicitante", equalTo(REQUESTER_CPF))
                .withRequestBody(matchingJsonPath("$.pessoa.documento_id.numero_documento", equalTo(stay.cpf)))
                .willReturn(okJson("""{"pessoa_id":"${stay.person}"}""")),
        )
        FnrhTestServer.server.stubFor(
            post(urlEqualTo("${FnrhTestServer.BASE_PATH}/reservas/${stay.remote}/hospedes"))
                .withHeader("Authorization", equalTo(FnrhTestServer.basicAuthorization))
                .withRequestBody(matchingJsonPath("$.pessoa_id", equalTo(stay.person)))
                .willReturn(okJson("""{"hospede_id":"${stay.guestRemote}"}""")),
        )
        FnrhTestServer.stubCheckOut_v2_4(stay.guestRemote)
    }

    /**
     * Espera o listener do `fnrh` gravar as submissões [expected] da reserva e envia só as dela (o job fica adiado nos
     * testes), fora de transação.
     */
    private fun sendOwnFnrhSubmissions(
        reservationId: UUID,
        vararg expected: FnrhOperation,
    ): FnrhSendRun {
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).pollInterval(Duration.ofMillis(POLL_MILLIS)).until {
            fnrhStatuses(reservationId).map { it.first }.containsAll(expected.toList())
        }
        return sender.sendDueFor(reservationId)
    }

    private fun fnrhStatuses(reservationId: UUID): List<Pair<FnrhOperation, FnrhSubmissionStatus>> =
        jdbc
            .sql("SELECT operation, status FROM fnrh.submission WHERE reservation_id = :id ORDER BY sequence")
            .param("id", reservationId)
            .query { rs, _ ->
                FnrhOperation.valueOf(rs.getString("operation")) to
                    FnrhSubmissionStatus.valueOf(rs.getString("status"))
            }.list()

    /**
     * As chamadas ao Serpro (WireMock, um por JVM) desta estadia, na ordem em que chegaram, como o método e o caminho
     * sem o prefixo da API: as que levam o id remoto da reserva ou do hóspede, ou o CPF, no caminho, o cadastro da
     * pessoa com o CPF e o registro com o código da reserva.
     */
    private fun fnrhCallsOf(
        stay: Stay,
        code: String,
    ): List<String> =
        FnrhTestServer
            .requests()
            .filter { request ->
                val path = request.url.removePrefix(FnrhTestServer.BASE_PATH)
                val body = request.bodyAsString
                stay.remote in path ||
                    stay.cpf in path ||
                    (path == "/pessoas" && stay.cpf in body) ||
                    (path == "/reservas" && "\"numero_reserva\":\"$code\"" in body)
            }.map { "${it.method.value()} ${it.url.removePrefix(FnrhTestServer.BASE_PATH)}" }

    private fun fnrhSettings(): FnrhSettings =
        jdbc
            .sql("SELECT environment, username, password, default_cpf, enabled FROM fnrh.settings")
            .query { rs, _ ->
                FnrhSettings(
                    rs.getString("environment"),
                    rs.getString("username"),
                    rs.getString("password"),
                    rs.getString("default_cpf"),
                    rs.getBoolean("enabled"),
                )
            }.single()

    private fun restoreFnrh(original: FnrhSettings) {
        jdbc
            .sql(
                "UPDATE fnrh.settings SET environment = :environment, username = :username, password = :password, " +
                    "default_cpf = :cpf, enabled = :enabled",
            ).param("environment", original.environment)
            .param("username", original.username)
            .param("password", original.password)
            .param("cpf", original.defaultCpf)
            .param("enabled", original.enabled)
            .update() shouldBe 1
    }

    // --- Apoio: operadores e chamadas ---------------------------------------------------------------------------

    /** Usuário novo com a role do modelo da SPEC §3.1 que o sistema cria no boot. */
    private fun userWithTemplate(template: String): TestUser {
        val role =
            jdbc
                .sql("SELECT id FROM iam.role WHERE template_key = :template")
                .param("template", template)
                .query(UUID::class.java)
                .single()
        return iam.createUser().also { iam.assignRole(it, RoleId(role)) }
    }

    private fun etagOf(
        path: String,
        operator: Operator,
    ): String {
        val response = api(GET, path, operator)
        withClue(response.contentAsString) { response.status shouldBe 200 }
        return checkNotNull(response.getHeader(HttpHeaders.ETAG))
    }

    private fun api(
        method: HttpMethod,
        path: String,
        operator: Operator,
        body: Any? = null,
    ): MockHttpServletResponse = billing.call(method, path, operator.auth, body)

    private fun MockHttpServletResponse.expect(status: Int): JsonNode = with(billing) { expect(status) }

    private fun MockHttpServletResponse.problem(
        status: Int,
        code: String,
    ): JsonNode = with(billing) { problem(status, code) }

    private fun MockHttpServletResponse.ok(): JsonNode = with(iam) { ok() }

    private fun JsonNode.id(): UUID = UUID.fromString(this["id"].asString())

    private companion object {
        const val PIN_MIN = 1000
        const val PIN_MAX = 9999
        const val PRICED_DAYS = 30L
        const val EXPECTED_ARRIVAL_TIME = "14:30:00"

        /** O CPF solicitante padrão que o `enableFnrh` configura, só dígitos. */
        const val REQUESTER_CPF = "52998224725"
        const val TIMEOUT_SECONDS = 30L
        const val POLL_MILLIS = 50L

        /** As 11 etapas do fechamento real, em ordem de posição. */
        val STEPS =
            listOf(
                "no-show",
                "pending-departures",
                "room-charges",
                "block-release",
                "provisional-expiry",
                "card-pre-authorizations",
                "occupied-rooms-dirty",
                "daily-statistics",
                "end-of-day-reports",
                "end-of-day-pending",
                "advance-business-date",
            )
    }
}
