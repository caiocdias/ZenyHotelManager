package com.zeny.hotel.rooms

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.rooms.RoomsTestSupport.Companion.definition
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.modulith.test.EnableScenarios
import org.springframework.modulith.test.Scenario
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/** Os eventos `OutOfOrderChanged` recebidos pelo listener de teste, como o inventário (M4) os receberia. */
object OutOfOrderEvents {
    val received: MutableList<OutOfOrderChanged> = CopyOnWriteArrayList()
}

/** Listener assíncrono e transacional de teste: com ele, a publicação passa pelo outbox. */
open class OutOfOrderProbeListener {
    @ApplicationModuleListener
    open fun on(event: OutOfOrderChanged) {
        OutOfOrderEvents.received += event
    }
}

@TestConfiguration(proxyBeanMethods = false)
class OutOfOrderListenerConfiguration {
    @Bean
    fun outOfOrderProbeListener() = OutOfOrderProbeListener()
}

/**
 * OOO e OOS (M2 E03, RF-UH-05, RN-05, RN-07, RF-ACS-08): abrir OOO é ação sensível (aprovação de
 * superior sem a permissão, auditada com o aprovador), OOO é recusado em UH ocupada no período atual e
 * OOS não, períodos da mesma UH não se sobrepõem (nem por SQL direto), a edição usa `If-Match`, a
 * liberação devolve a UH ao status de retorno com histórico, a UH em OOO não fica pronta para check-in,
 * o painel mostra a sobreposição e `OutOfOrderChanged` sai pelo outbox ao abrir, alterar e liberar.
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, OutOfOrderListenerConfiguration::class)
@Suppress("LargeClass") // cenários de ponta a ponta do OOO/OOS, do step-up e do evento
class OutOfOrderApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val jsonMapper: JsonMapper,
) {
    private val rooms = RoomsTestSupport(iam)
    private val oooManage = definition(RoomsPermissions.OUT_OF_ORDER_MANAGE)
    private val oosManage = definition(RoomsPermissions.OUT_OF_SERVICE_MANAGE)
    private val oooRead = definition(RoomsPermissions.OUT_OF_ORDER_READ)

    // --- Step-up (RF-ACS-08) -------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // o fluxo inteiro: recusa, aprovação, conclusão e trilha
    fun `an OOO without the permission asks for approval, and the approved request is audited with the approver`() {
        val room = newRoom()
        val today = today()
        // A Manutenção abre OOS, mas só pede OOO (SPEC §3.1): precisa da aprovação de quem tem a permissão.
        val requester = Requester(iam.createUserWith(TestGrant(oosManage, ALL), TestGrant(oooRead, ALL)))
        val approver = approverWith(TestGrant(oooManage, ALL))
        val body = periodBody("OUT_OF_ORDER", today, today.plusDays(2), returnStatus = "INSPECTED")

        val required = open(room, requester.auth, body).problem(403, "APPROVAL_REQUIRED")
        required["action"].asString() shouldBe "rooms.out-of-order.open"
        required["permission"].asString() shouldBe "rooms.outoforder.manage"
        required["resourceRef"].asString() shouldBe "rooms.room:$room"
        required.has("limit") shouldBe false
        periodsOf(room).shouldBeEmpty()

        val approval = approve(requester, required, approver).expect(201)
        val approvalId = UUID.fromString(approval["approvalId"].asString())
        val token = approval["approvalToken"].asString()

        val created = open(room, requester.auth, body, approvalToken = token)
        val period = created.expect(201)
        created.getHeader(HttpHeaders.LOCATION) shouldBe "/api/v1/rooms/$room/out-of-order/${period["id"].asString()}"
        created.getHeader(HttpHeaders.ETAG) shouldBe "\"0\""
        period["kind"].asString() shouldBe "OUT_OF_ORDER"
        period["openedBy"].asString() shouldBe requester.user.id.toString()
        // O token é de uso único.
        open(room, requester.auth, periodBody("OUT_OF_ORDER", today.plusDays(5), today.plusDays(6)), token)
            .problem(403, "APPROVAL_TOKEN_USED")

        // A trilha: a abertura é do solicitante, referencia a aprovação, e a aprovação traz o aprovador.
        val periodId = UUID.fromString(period["id"].asString())
        val entries =
            jdbc
                .sql(
                    "SELECT entity_type, user_id, device_session_id, after_state::text AS after " +
                        "FROM audit.audit_entry WHERE action = 'rooms.out-of-order.open' AND approval_id = :id",
                ).param("id", approvalId)
                .query { rs, _ ->
                    listOf(
                        rs.getString("entity_type"),
                        rs.getObject("user_id", UUID::class.java),
                        rs.getObject("device_session_id", UUID::class.java),
                        rs.getString("after"),
                    )
                }.list()
        entries.map { it[0] } shouldContainExactlyInAnyOrder listOf("rooms.out-of-order", "iam.approval")
        entries.forEach {
            it[1] shouldBe requester.user.id.value
            it[2] shouldBe requester.sessionId
        }
        val periodEntry = jsonMapper.readTree(entries.single { it[0] == "rooms.out-of-order" }[3] as String)
        periodEntry["kind"].asString() shouldBe "OUT_OF_ORDER"
        periodEntry["roomId"].asString() shouldBe room.toString()
        val approvalEntry = jsonMapper.readTree(entries.single { it[0] == "iam.approval" }[3] as String)
        approvalEntry["approverId"].asString() shouldBe approver.user.id.toString()
        approvalEntry["resourceRef"].asString() shouldBe "rooms.room:$room"
        approvalEntry["usedAt"].isNull shouldBe false
        auditActions(periodId) shouldContainExactly listOf("rooms.out-of-order.open")
    }

    @Test
    fun `whoever holds the permission opens an OOO without approval, and OOS has its own permission`(): Unit =
        with(iam) {
            val room = newRoom()
            val today = today()
            val manager = createUserWith(TestGrant(oooManage, ALL))
            val nobody = createUser()

            // OOS não é sensível: sem a permissão, recusado sem pedir aprovação.
            open(room, asTestUser(manager), periodBody("OUT_OF_SERVICE", today, today))
                .problem(403, "PERMISSION_DENIED")
            open(room, asTestUser(nobody), periodBody("OUT_OF_SERVICE", today, today)).problem(403, "PERMISSION_DENIED")
            open(room, asTestUser(manager), periodBody("OUT_OF_ORDER", today, today.plusDays(1))).expect(201)
            open(UUID.randomUUID(), asAdmin(), periodBody("OUT_OF_ORDER", today, today))
                .problem(404, "ROOM_NOT_FOUND")
            // Formato e período inválidos respondem 400 antes da autorização.
            open(room, asTestUser(nobody), periodBody("OUT_OF_ORDER", today.plusDays(2), today))
                .problem(400, "OUT_OF_ORDER_PERIOD_INVALID")
            open(room, asAdmin(), periodBody("OUT_OF_ORDER", today, today) - "reason").problem(400, "VALIDATION_FAILED")
            open(room, asAdmin(), periodBody("OUT_OF_ORDER", today.minusDays(1), today))
                .problem(422, "OUT_OF_ORDER_PERIOD_IN_PAST")

            // Consultar exige rooms.outoforder.read.
            call(GET, "/api/v1/out-of-order", asTestUser(nobody)).problem(403, "PERMISSION_DENIED")
        }

    // --- RN-07 e sobreposição ----------------------------------------------------------------------

    @Test
    fun `an OOO is refused in an occupied room in the current period, an OOS is accepted`(): Unit =
        with(iam) {
            val room = newRoom()
            val today = today()
            roomsApi.markOccupied(RoomId(room), 2, RoomStatusSource.CHECK_IN)

            open(room, asAdmin(), periodBody("OUT_OF_ORDER", today, today.plusDays(3)))
                .problem(422, "OUT_OF_ORDER_ROOM_OCCUPIED")
            // Pela API pública também (a manutenção do M6).
            shouldThrow<BusinessRuleException> {
                roomsApi.openOutOfOrder(RoomId(room), OutOfOrderKind.OUT_OF_ORDER, request(today, today))
            }.code shouldBe ErrorCode("OUT_OF_ORDER_ROOM_OCCUPIED")
            periodsOf(room).shouldBeEmpty()
            open(room, asAdmin(), periodBody("OUT_OF_SERVICE", today, today.plusDays(1))).expect(201)
            // Um OOO que só começa depois do período atual não esbarra na ocupação de hoje.
            open(room, asAdmin(), periodBody("OUT_OF_ORDER", today.plusDays(2), today.plusDays(3))).expect(201)
            periodsOf(room).size shouldBe 2
        }

    @Test
    @Suppress("LongMethod") // a API, a edição, a liberação e o banco
    fun `overlapping periods of the same room are refused, also by the database`(): Unit =
        with(iam) {
            val room = newRoom()
            val other = newRoom()
            val today = today()
            val first =
                open(room, asAdmin(), periodBody("OUT_OF_SERVICE", today.plusDays(2), today.plusDays(4)))
                    .expect(201)["id"]
                    .asString()

            open(room, asAdmin(), periodBody("OUT_OF_ORDER", today.plusDays(4), today.plusDays(6)))
                .problem(409, "OUT_OF_ORDER_OVERLAP")
            open(room, asAdmin(), periodBody("OUT_OF_SERVICE", today, today.plusDays(9)))
                .problem(409, "OUT_OF_ORDER_OVERLAP")
            // Outra UH, ou encostado, vale.
            open(other, asAdmin(), periodBody("OUT_OF_ORDER", today.plusDays(2), today.plusDays(4))).expect(201)
            val second =
                open(room, asAdmin(), periodBody("OUT_OF_ORDER", today.plusDays(5), today.plusDays(6)))
                    .expect(201)["id"]
                    .asString()
            // A edição também não sobrepõe.
            val path = "/api/v1/rooms/$room/out-of-order/$first"
            call(PUT, path, asAdmin(), periodBody(null, today.plusDays(2), today.plusDays(5)), ifMatch = etag(path))
                .problem(409, "OUT_OF_ORDER_OVERLAP")
            shouldThrow<ConflictException> {
                roomsApi.openOutOfOrder(
                    RoomId(room),
                    OutOfOrderKind.OUT_OF_SERVICE,
                    request(today.plusDays(6), today.plusDays(7)),
                )
            }.code shouldBe ErrorCode("OUT_OF_ORDER_OVERLAP")

            // O banco recusa a sobreposição mesmo por SQL direto (EXCLUDE USING gist).
            val error =
                shouldThrow<DataAccessException> {
                    transactions.executeWithoutResult {
                        jdbc
                            .sql(
                                """
                                INSERT INTO rooms.out_of_order (id, room_id, kind, starts_on, ends_on, reason,
                                    responsible, return_status, opened_at, updated_at, version)
                                VALUES (:id, :room, 'OUT_OF_ORDER', :from, :to, 'SQL', 'SQL', 'DIRTY', now(), now(), 0)
                                """.trimIndent(),
                            ).param("id", UuidV7.generate())
                            .param("room", room)
                            .param("from", today.plusDays(3))
                            .param("to", today.plusDays(3))
                            .update()
                    }
                }
            generateSequence<Throwable>(error) { it.cause }.joinToString { it.message.orEmpty() } shouldContain
                "out_of_order_overlap_ex"

            // Liberado, o período deixa de bloquear os dias a partir da liberação.
            call(
                POST,
                "/api/v1/rooms/$room/out-of-order/$second/release",
                asAdmin(),
            ).ok()["releasedOn"].asString() shouldBe
                today.toString()
            open(room, asAdmin(), periodBody("OUT_OF_ORDER", today.plusDays(5), today.plusDays(6))).expect(201)
        }

    @Test
    fun `two overlapping periods opened at the same time - one is opened, the other gets 409`() {
        val room = RoomId(newRoom())
        val today = today()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results =
                listOf(today to today.plusDays(2), today.plusDays(1) to today.plusDays(3))
                    .map { (from, to) ->
                        pool.submit(
                            Callable {
                                start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
                                runCatching {
                                    roomsApi.openOutOfOrder(
                                        room,
                                        OutOfOrderKind.OUT_OF_SERVICE,
                                        request(from, to),
                                    )
                                }
                            },
                        )
                    }.also { start.countDown() }
                    .map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }

            results.count { it.isSuccess } shouldBe 1
            (results.single { it.isFailure }.exceptionOrNull() as ConflictException).code shouldBe
                ErrorCode("OUT_OF_ORDER_OVERLAP")
            periodsOf(room.value).size shouldBe 1
        } finally {
            pool.shutdownNow()
        }
    }

    // --- Edição ------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // cada recusa da edição no seu passo
    fun `a period is edited with If-Match, an OOO edit is sensitive and a released period is not edited`(): Unit =
        with(iam) {
            val room = newRoom()
            val today = today()
            val period =
                open(room, asAdmin(), periodBody("OUT_OF_ORDER", today.plusDays(1), today.plusDays(2))).expect(201)
            val path = "/api/v1/rooms/$room/out-of-order/${period["id"].asString()}"
            val edit = periodBody(null, today.plusDays(1), today.plusDays(4), reason = "Troca do piso")

            call(PUT, path, asAdmin(), edit).problem(428, "PRECONDITION_REQUIRED")
            call(PUT, path, asAdmin(), edit, ifMatch = "\"9\"").problem(412, "PRECONDITION_FAILED")
            val requester = Requester(createUserWith(TestGrant(oooRead, ALL)))
            val required = call(PUT, path, requester.auth, edit, ifMatch = etag(path)).problem(403, "APPROVAL_REQUIRED")
            required["action"].asString() shouldBe "rooms.out-of-order.update"
            required["resourceRef"].asString() shouldBe "rooms.out-of-order:${period["id"].asString()}"

            val edited = call(PUT, path, asAdmin(), edit, ifMatch = etag(path))
            edited.ok().let {
                it["endsOn"].asString() shouldBe today.plusDays(4).toString()
                it["reason"].asString() shouldBe "Troca do piso"
                it["kind"].asString() shouldBe "OUT_OF_ORDER"
            }
            edited.getHeader(HttpHeaders.ETAG) shouldBe "\"1\""
            call(GET, path, asAdmin()).ok()["version"].asLong() shouldBe 1
            call(GET, "/api/v1/rooms/${newRoom()}/out-of-order/${period["id"].asString()}", asAdmin())
                .problem(404, "OUT_OF_ORDER_NOT_FOUND")
            call(PUT, path, asAdmin(), periodBody(null, today.minusDays(1), today), ifMatch = etag(path))
                .problem(422, "OUT_OF_ORDER_PERIOD_IN_PAST")

            call(POST, "$path/release", asAdmin()).ok()
            call(PUT, path, asAdmin(), edit, ifMatch = etag(path)).problem(422, "OUT_OF_ORDER_RELEASED")
            auditActions(UUID.fromString(period["id"].asString())) shouldContainExactly
                listOf("rooms.out-of-order.open", "rooms.out-of-order.update", "rooms.out-of-order.release")
        }

    // --- Liberação, RN-05 e painel --------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // o período em vigor do começo ao fim, com o painel e o histórico
    fun `releasing a period in effect applies the return status, records it and makes the room ready again`(): Unit =
        with(iam) {
            val room = newRoom()
            val today = today()
            roomsApi.isReadyForCheckIn(RoomId(room)) shouldBe false // nasce suja
            roomsApi.markClean(RoomId(room), requiresInspection = false, source = RoomStatusSource.TASK)
            roomsApi.isReadyForCheckIn(RoomId(room)) shouldBe true

            val period =
                open(room, asAdmin(), periodBody("OUT_OF_ORDER", today, today.plusDays(3), returnStatus = "INSPECTED"))
                    .expect(201)
            val periodId = period["id"].asString()
            // RN-05: em OOO, não está pronta; o painel mostra a sobreposição.
            roomsApi.isReadyForCheckIn(RoomId(room)) shouldBe false
            roomsApi.status(RoomId(room)).shouldNotBeNull().let {
                it.readyForCheckIn shouldBe false
                it.outOfOrder shouldBe OutOfOrderKind.OUT_OF_ORDER
            }
            boardItem(room).let {
                it["readyForCheckIn"].asBoolean() shouldBe false
                it["outOfOrder"]["id"].asString() shouldBe periodId
                it["outOfOrder"]["kind"].asString() shouldBe "OUT_OF_ORDER"
                it["outOfOrder"]["startsOn"].asString() shouldBe today.toString()
                it["outOfOrder"]["endsOn"].asString() shouldBe today.plusDays(3).toString()
            }
            call(GET, "/api/v1/rooms/$room/status", asAdmin()).ok()["outOfOrder"]["kind"].asString() shouldBe
                "OUT_OF_ORDER"

            val releaser = createUserWith(TestGrant(oooManage, ALL))
            val released = call(POST, "/api/v1/rooms/$room/out-of-order/$periodId/release", asTestUser(releaser)).ok()
            released["releasedOn"].asString() shouldBe today.toString()
            released["releasedBy"].asString() shouldBe releaser.id.toString()
            released["blockedUntil"].isNull shouldBe true

            roomsApi.status(RoomId(room)).shouldNotBeNull().let {
                it.housekeepingStatus shouldBe HousekeepingStatus.INSPECTED
                it.outOfOrder.shouldBeNull()
                it.readyForCheckIn shouldBe true
            }
            boardItem(room)["outOfOrder"].isNull shouldBe true
            history(room).first().let {
                it["field"].asString() shouldBe "HOUSEKEEPING_STATUS"
                it["from"].asString() shouldBe "CLEAN"
                it["to"].asString() shouldBe "INSPECTED"
                it["source"].asString() shouldBe "MANUAL"
                it["changedBy"].asString() shouldBe releaser.id.toString()
            }
            // Liberar de novo não muda nada.
            val historySize = history(room).size
            call(POST, "/api/v1/rooms/$room/out-of-order/$periodId/release", asTestUser(releaser)).ok()
            history(room).size shouldBe historySize
            auditActions(UUID.fromString(periodId)) shouldContainExactly
                listOf("rooms.out-of-order.open", "rooms.out-of-order.release")
            auditActions(room) shouldContainExactly
                listOf("rooms.room.create", "rooms.room-status.mark-clean", "rooms.out-of-order.release")
        }

    @Test
    fun `an OOS keeps the room ready, and releasing a period not yet started leaves the status alone`(): Unit =
        with(iam) {
            val room = newRoom()
            val today = today()
            roomsApi.markClean(RoomId(room), requiresInspection = false, source = RoomStatusSource.TASK)
            open(room, asAdmin(), periodBody("OUT_OF_SERVICE", today, today)).expect(201)
            roomsApi.isReadyForCheckIn(RoomId(room)) shouldBe true
            boardItem(room)["outOfOrder"]["kind"].asString() shouldBe "OUT_OF_SERVICE"

            val future =
                open(room, asAdmin(), periodBody("OUT_OF_ORDER", today.plusDays(3), today.plusDays(4))).expect(201)
            roomsApi.isReadyForCheckIn(RoomId(room)) shouldBe true
            val before = history(room).size
            call(POST, "/api/v1/rooms/$room/out-of-order/${future["id"].asString()}/release", asAdmin()).ok()
            history(room).size shouldBe before
            roomsApi.status(RoomId(room)).shouldNotBeNull().housekeepingStatus shouldBe HousekeepingStatus.CLEAN
        }

    // --- Consulta e API pública ----------------------------------------------------------------------

    @Test
    fun `periods are listed by the dates they block, and the public API reads, opens and releases them`(): Unit =
        with(iam) {
            val room = newRoom()
            val today = today()
            val reader = createUserWith(TestGrant(oooRead, ALL))
            val near = open(room, asAdmin(), periodBody("OUT_OF_SERVICE", today, today.plusDays(1))).expect(201)
            // Pela API pública, sem permissão nenhuma (quem chama já se autorizou) e com o sistema como autor.
            val far =
                roomsApi.openOutOfOrder(
                    RoomId(room),
                    OutOfOrderKind.OUT_OF_ORDER,
                    request(today.plusDays(10), today.plusDays(12)),
                )
            far.openedBy.shouldBeNull()
            far.version shouldBe 0

            fun listed(query: String) =
                rooms
                    .listAll(
                        "/api/v1/out-of-order?roomId=$room&$query",
                        asTestUser(reader),
                    ).map { it["id"].asString() }

            listed("from=$today&to=${today.plusDays(30)}") shouldContainExactly
                listOf(near["id"].asString(), far.id.toString())
            listed("from=${today.plusDays(2)}&to=${today.plusDays(9)}").shouldBeEmpty()
            listed("from=${today.plusDays(12)}") shouldContainExactly listOf(far.id.toString())
            listed("from=$today&kind=OUT_OF_ORDER") shouldContainExactly listOf(far.id.toString())
            rooms
                .listAll("/api/v1/out-of-order?from=$today", asTestUser(reader))
                .map { it["id"].asString() }
                .containsAll(listOf(near["id"].asString(), far.id.toString())) shouldBe true
            call(GET, "/api/v1/out-of-order?from=${today.plusDays(3)}&to=$today", asTestUser(reader))
                .problem(400, "OUT_OF_ORDER_PERIOD_INVALID")

            roomsApi.outOfOrderPeriods(today, today.plusDays(30), RoomId(room)).map { it.id } shouldContainExactly
                listOf(OutOfOrderId(UUID.fromString(near["id"].asString())), far.id)
            roomsApi.outOfOrderPeriods(today.plusDays(11), today.plusDays(11)).map { it.id }.contains(far.id) shouldBe
                true
            roomsApi.outOfOrder(far.id).shouldNotBeNull().kind shouldBe OutOfOrderKind.OUT_OF_ORDER
            roomsApi.outOfOrder(OutOfOrderId(UUID.randomUUID())).shouldBeNull()

            roomsApi.releaseOutOfOrder(far.id, RoomStatusSource.TASK).let {
                it.releasedOn shouldBe today
                it.releasedBy.shouldBeNull()
                it.blockedUntil.shouldBeNull()
            }
            roomsApi.outOfOrderPeriods(today, today.plusDays(30), RoomId(room)).map { it.id } shouldContainExactly
                listOf(OutOfOrderId(UUID.fromString(near["id"].asString())))
        }

    // --- Evento ------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // as três ações e o que vai para o outbox
    fun `OutOfOrderChanged is published on open, update and release with the whole period`(scenario: Scenario) {
        val room = newRoom()
        val today = today()

        scenario
            .stimulate(
                Supplier {
                    roomsApi.openOutOfOrder(
                        RoomId(room),
                        OutOfOrderKind.OUT_OF_ORDER,
                        request(today.plusDays(1), today.plusDays(3)),
                    )
                },
            ).andWaitForEventOfType(OutOfOrderChanged::class.java)
            .matching { it.roomId == room && it.action == OutOfOrderAction.OPENED }
            .toArriveAndVerify { event, period ->
                event.outOfOrderId shouldBe period.id.value
                event.kind shouldBe OutOfOrderKind.OUT_OF_ORDER
                event.startsOn shouldBe today.plusDays(1)
                event.endsOn shouldBe today.plusDays(3)
                event.blockedUntil shouldBe today.plusDays(3)
                event.releasedOn.shouldBeNull()
                event.previousStartsOn.shouldBeNull()
                event.version shouldBe 0
            }
        val id = periodsOf(room).single()
        val path = "/api/v1/rooms/$room/out-of-order/$id"
        with(iam) {
            call(PUT, path, asAdmin(), periodBody(null, today, today.plusDays(5)), ifMatch = etag(path)).ok()
            call(POST, "$path/release", asAdmin()).ok()
            // Liberar de novo não publica nada.
            call(POST, "$path/release", asAdmin()).ok()
        }
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until { events(room).size == 3 }
        Thread.sleep(SETTLE_MILLIS)
        val (_, updated, released) = events(room).sortedBy { it.version }
        updated.action shouldBe OutOfOrderAction.UPDATED
        updated.startsOn shouldBe today
        updated.endsOn shouldBe today.plusDays(5)
        updated.previousStartsOn shouldBe today.plusDays(1)
        updated.previousEndsOn shouldBe today.plusDays(3)
        released.action shouldBe OutOfOrderAction.RELEASED
        released.releasedOn shouldBe today
        released.blockedUntil.shouldBeNull()
        released.endsOn shouldBe today.plusDays(5)
        events(room).size shouldBe 3

        // O que vai para o outbox: só ids, tipo, datas e versão; nada de motivo, responsável ou autor.
        val serialized =
            jdbc
                .sql(
                    "SELECT serialized_event FROM shared.event_publication " +
                        "WHERE event_type = :type AND serialized_event LIKE :room ORDER BY publication_date",
                ).param("type", OutOfOrderChanged::class.java.name)
                .param("room", "%$room%")
                .query(String::class.java)
                .list()
                .filterNotNull()
        serialized.size shouldBe 3
        jsonMapper.readTree(serialized.first()).propertyNames().toSet() shouldBe
            setOf(
                "outOfOrderId",
                "roomId",
                "kind",
                "action",
                "startsOn",
                "endsOn",
                "releasedOn",
                "blockedUntil",
                "previousStartsOn",
                "previousEndsOn",
                "version",
                "changedAt",
            )
        serialized.none { "Vazamento" in it || "Equipe" in it } shouldBe true
    }

    // --- Apoio -------------------------------------------------------------------------------------

    /** O solicitante numa sessão de dispositivo fixa (o token de aprovação fica vinculado a ela). */
    private inner class Requester(
        val user: TestUser,
        val sessionId: UUID = UuidV7.generate(),
    ) {
        val auth: RequestPostProcessor get() = asUser(user.id.value, sessionId)
    }

    private data class Approver(
        val user: TestUser,
        val pin: String,
    )

    private fun approverWith(vararg grants: TestGrant): Approver {
        val user = iam.createUserWith(*grants)
        val pin = (PIN_MIN..PIN_MAX).random().toString()
        iam.setPin(user, pin)
        return Approver(user, pin)
    }

    private fun approve(
        requester: Requester,
        required: JsonNode,
        approver: Approver,
    ): MockHttpServletResponse =
        iam.call(
            POST,
            "/api/v1/auth/approvals",
            requester.auth,
            mapOf(
                "action" to required["action"].asString(),
                "permission" to required["permission"].asString(),
                "resourceRef" to required["resourceRef"].asString(),
                "approverLogin" to approver.user.login,
                "approverPin" to approver.pin,
                "reason" to "Obra no banheiro",
            ),
        )

    private fun today(): LocalDate = hotelApi.currentBusinessDate()

    private fun newRoom(): UUID = rooms.createRoom(rooms.createRoomType())

    private fun periodBody(
        kind: String?,
        startsOn: LocalDate,
        endsOn: LocalDate,
        reason: String = "Vazamento no banheiro",
        returnStatus: String = "DIRTY",
    ): Map<String, Any?> =
        buildMap {
            if (kind != null) put("kind", kind)
            put("startsOn", startsOn.toString())
            put("endsOn", endsOn.toString())
            put("reason", reason)
            put("responsible", "Equipe de manutenção")
            put("returnStatus", returnStatus)
        }

    private fun request(
        startsOn: LocalDate,
        endsOn: LocalDate,
    ) = OutOfOrderRequest(startsOn, endsOn, "Vazamento no banheiro", "Equipe de manutenção", HousekeepingStatus.DIRTY)

    private fun open(
        room: UUID,
        auth: RequestPostProcessor,
        body: Map<String, Any?>,
        approvalToken: String? = null,
    ): MockHttpServletResponse =
        iam.call(POST, "/api/v1/rooms/$room/out-of-order", auth, body, approvalToken = approvalToken)

    private fun MockHttpServletResponse.expect(status: Int): JsonNode =
        with(iam) { expectStatus(status).shouldNotBeNull() }

    private fun MockHttpServletResponse.problem(
        status: Int,
        code: String,
    ): JsonNode = with(iam) { problem(status, code) }

    private fun etag(path: String): String = rooms.etagOf(path)

    private fun periodsOf(room: UUID): List<String> =
        jdbc
            .sql("SELECT id::text FROM rooms.out_of_order WHERE room_id = :room")
            .param("room", room)
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun boardItem(room: UUID): JsonNode =
        rooms.listAll("/api/v1/rooms/status").single { it["roomId"].asString() == room.toString() }

    private fun history(room: UUID): List<JsonNode> = rooms.listAll("/api/v1/rooms/$room/status-history")

    private fun events(room: UUID): List<OutOfOrderChanged> = OutOfOrderEvents.received.filter { it.roomId == room }

    private fun auditActions(entityId: UUID): List<String> =
        jdbc
            .sql("SELECT action FROM audit.audit_entry WHERE entity_id = :id ORDER BY occurred_at, id")
            .param("id", entityId.toString())
            .query(String::class.java)
            .list()
            .filterNotNull()

    private companion object {
        const val TIMEOUT_SECONDS = 15L
        const val SETTLE_MILLIS = 500L
        const val PIN_MIN = 1000
        const val PIN_MAX = 9999
    }
}
