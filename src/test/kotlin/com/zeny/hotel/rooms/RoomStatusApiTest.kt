package com.zeny.hotel.rooms

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.rooms.RoomsTestSupport.Companion.definition
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import kotlin.concurrent.thread

/** Os eventos `RoomStatusChanged` recebidos pelo listener de teste, como um módulo os receberia. */
object RoomStatusEvents {
    val received: MutableList<RoomStatusChanged> = CopyOnWriteArrayList()
}

/** Listener assíncrono e transacional de teste: com ele, a publicação passa pelo outbox. */
open class RoomStatusProbeListener {
    @ApplicationModuleListener
    open fun on(event: RoomStatusChanged) {
        RoomStatusEvents.received += event
    }
}

@TestConfiguration(proxyBeanMethods = false)
class RoomStatusListenerConfiguration {
    @Bean
    fun roomStatusProbeListener() = RoomStatusProbeListener()
}

/**
 * Status da UH (M2 E02, RF-UH-04 a 08, RN-05, RN-08, RN-09): o painel, a mudança manual com permissão e
 * `If-Match`, a sinalização do hóspede, a API pública usada pelos módulos futuros, o histórico de toda
 * mudança (quem, quando e origem), as discrepâncias entre recepção e governança, a corrida entre duas
 * mudanças e o evento `RoomStatusChanged` pelo outbox, sem dados de hóspede.
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, RoomStatusListenerConfiguration::class)
@Suppress("LargeClass") // cenários de ponta a ponta do status, do histórico e das discrepâncias
class RoomStatusApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val jsonMapper: JsonMapper,
) {
    private val rooms = RoomsTestSupport(iam)

    // --- Painel e mudança manual -------------------------------------------------------------------

    @Test
    fun `a new room shows up on the board vacant and dirty, and the board needs rooms_status_read`(): Unit =
        with(iam) {
            val type = rooms.createRoomType()
            val room = rooms.createRoom(type)
            val reader = createUserWith(TestGrant(definition(RoomsPermissions.STATUS_READ), ALL))

            val item = board(asTestUser(reader)).single { it["roomId"].asString() == room.toString() }
            item["occupancy"].asString() shouldBe "VACANT"
            item["guestCount"].isNull shouldBe true
            item["housekeepingStatus"].asString() shouldBe "DIRTY"
            item["inspectionPending"].asBoolean() shouldBe false
            item["serviceFlag"].asString() shouldBe "NONE"
            item["readyForCheckIn"].asBoolean() shouldBe false
            item["roomTypeId"].asString() shouldBe type.toString()

            val status = call(GET, "/api/v1/rooms/$room/status", asTestUser(reader))
            status.ok()["number"].asString() shouldBe item["number"].asString()
            status.getHeader(HttpHeaders.ETAG) shouldBe "\"${item["version"].asLong()}\""

            call(GET, "/api/v1/rooms/status", asTestUser(createUser())).problem(403, "PERMISSION_DENIED")
            call(GET, "/api/v1/rooms/$room/status-history", asTestUser(createUser())).problem(403, "PERMISSION_DENIED")
            call(GET, "/api/v1/rooms/discrepancies", asTestUser(createUser())).problem(403, "PERMISSION_DENIED")
            call(GET, "/api/v1/rooms/${UUID.randomUUID()}/status", asAdmin()).problem(404, "ROOM_NOT_FOUND")

            // Filtros e UH desativada (fora do painel).
            board(asAdmin(), "?housekeepingStatus=DIRTY").map { it["roomId"].asString() } shouldContain room.toString()
            board(asAdmin(), "?housekeepingStatus=CLEAN").map { it["roomId"].asString() } shouldNotContain
                room.toString()
            board(asAdmin(), "?occupancy=OCCUPIED").map { it["roomId"].asString() } shouldNotContain room.toString()
            call(POST, "/api/v1/rooms/$room/deactivate", asAdmin()).ok()
            board(asAdmin()).map { it["roomId"].asString() } shouldNotContain room.toString()
        }

    @Test
    @Suppress("LongMethod") // um cenário de ponta a ponta, com cada recusa no seu passo
    fun `a manual housekeeping change needs the permission and If-Match, follows SPEC 8_4 and is recorded`(): Unit =
        with(iam) {
            val room = rooms.createRoom(rooms.createRoomType())
            val path = "/api/v1/rooms/$room/housekeeping-status"
            val reader = createUserWith(TestGrant(definition(RoomsPermissions.STATUS_READ), ALL))
            val housekeeper =
                createUserWith(
                    TestGrant(definition(RoomsPermissions.STATUS_READ), ALL),
                    TestGrant(definition(RoomsPermissions.STATUS_MANAGE), ALL),
                )
            val clean = mapOf("transition" to "MARK_CLEAN", "requiresInspection" to true)

            call(POST, path, asTestUser(reader), clean, ifMatch = etag(room)).problem(403, "PERMISSION_DENIED")
            call(POST, path, asTestUser(housekeeper), clean).problem(428, "PRECONDITION_REQUIRED")
            call(POST, path, asTestUser(housekeeper), clean, ifMatch = "\"999\"").problem(412, "PRECONDITION_FAILED")
            // Sujo → Inspecionado direto: recusado, sem histórico.
            call(POST, path, asTestUser(housekeeper), mapOf("transition" to "MARK_INSPECTED"), ifMatch = etag(room))
                .problem(422, "HOUSEKEEPING_TRANSITION_NOT_ALLOWED")
            history(room).shouldBeEmpty()
            call(POST, path, asTestUser(housekeeper), mapOf("transition" to "JUMP"), ifMatch = etag(room))
                .expectStatus(400)

            val cleaned = call(POST, path, asTestUser(housekeeper), clean, ifMatch = etag(room))
            cleaned.ok().let {
                it["housekeepingStatus"].asString() shouldBe "CLEAN"
                it["inspectionPending"].asBoolean() shouldBe true
                it["readyForCheckIn"].asBoolean() shouldBe false
            }
            cleaned.getHeader(HttpHeaders.ETAG) shouldBe etag(room)
            // Limpo com inspeção pendente → Limpo sem inspeção: recusado (pularia a inspeção).
            call(POST, path, asTestUser(housekeeper), mapOf("transition" to "MARK_CLEAN"), ifMatch = etag(room))
                .problem(422, "HOUSEKEEPING_TRANSITION_NOT_ALLOWED")
            call(POST, path, asTestUser(housekeeper), mapOf("transition" to "REJECT_INSPECTION"), ifMatch = etag(room))
                .ok()["housekeepingStatus"]
                .asString() shouldBe "DIRTY"
            call(POST, path, asAdmin(), clean, ifMatch = etag(room)).ok()
            call(POST, path, asAdmin(), mapOf("transition" to "MARK_INSPECTED"), ifMatch = etag(room)).ok().let {
                it["housekeepingStatus"].asString() shouldBe "INSPECTED"
                it["inspectionPending"].asBoolean() shouldBe false
                it["readyForCheckIn"].asBoolean() shouldBe true
            }
            roomsApi.isReadyForCheckIn(RoomId(room)) shouldBe true
            call(POST, path, asAdmin(), mapOf("transition" to "MARK_DIRTY"), ifMatch = etag(room))
                .ok()["housekeepingStatus"]
                .asString() shouldBe "DIRTY"

            // O histórico tem cada campo que mudou, com quem, quando e a origem, das mais recentes às mais antigas.
            val entries = history(room, asTestUser(reader))
            entries.map { listOf(it["field"].asString(), it["from"].asString(), it["to"].asString()) } shouldBe
                listOf(
                    listOf("HOUSEKEEPING_STATUS", "INSPECTED", "DIRTY"),
                    listOf("INSPECTION_PENDING", "true", "false"),
                    listOf("HOUSEKEEPING_STATUS", "CLEAN", "INSPECTED"),
                    listOf("INSPECTION_PENDING", "false", "true"),
                    listOf("HOUSEKEEPING_STATUS", "DIRTY", "CLEAN"),
                    listOf("INSPECTION_PENDING", "true", "false"),
                    listOf("HOUSEKEEPING_STATUS", "CLEAN", "DIRTY"),
                    listOf("INSPECTION_PENDING", "false", "true"),
                    listOf("HOUSEKEEPING_STATUS", "DIRTY", "CLEAN"),
                )
            entries.map { it["source"].asString() }.toSet() shouldBe setOf("MANUAL")
            entries.takeLast(4).map { it["changedBy"].asString() }.toSet() shouldBe setOf(housekeeper.id.toString())
            entries.take(5).map { it["changedBy"].asString() }.toSet() shouldBe setOf(adminId().toString())
            entries.forEach { it["changedAt"].isNull shouldBe false }
            auditActions(room).count { it == "rooms.room-status.change-housekeeping" } shouldBe 5
            call(GET, "/api/v1/rooms/${UUID.randomUUID()}/status-history", asAdmin()).problem(404, "ROOM_NOT_FOUND")
        }

    @Test
    fun `the guest signal needs rooms_status_flag and an occupied room`(): Unit =
        with(iam) {
            val room = rooms.createRoom(rooms.createRoomType())
            val path = "/api/v1/rooms/$room/service-flag"
            val reception = createUserWith(TestGrant(definition(RoomsPermissions.STATUS_FLAG), ALL))
            val dnd = mapOf("flag" to "DO_NOT_DISTURB")

            call(POST, path, asTestUser(createUser()), dnd).problem(403, "PERMISSION_DENIED")
            call(POST, path, asTestUser(reception), dnd).problem(422, "ROOM_NOT_OCCUPIED")
            roomsApi.markOccupied(RoomId(room), 2, RoomStatusSource.CHECK_IN)
            val flagged = call(POST, path, asTestUser(reception), dnd)
            flagged.ok()["serviceFlag"].asString() shouldBe "DO_NOT_DISTURB"
            flagged.getHeader(HttpHeaders.ETAG) shouldBe etag(room)
            call(POST, path, asTestUser(reception), mapOf("flag" to "MAKE_UP_ROOM"))
                .ok()["serviceFlag"]
                .asString() shouldBe "MAKE_UP_ROOM"

            history(room).first().let {
                it["field"].asString() shouldBe "SERVICE_FLAG"
                it["from"].asString() shouldBe "DO_NOT_DISTURB"
                it["to"].asString() shouldBe "MAKE_UP_ROOM"
                it["changedBy"].asString() shouldBe reception.id.toString()
                it["source"].asString() shouldBe "MANUAL"
            }
            auditActions(room).count { it == "rooms.room-status.set-service-flag" } shouldBe 2
        }

    @Test
    fun `an occupied room is not deactivated`(): Unit =
        with(iam) {
            val room = rooms.createRoom(rooms.createRoomType())
            roomsApi.markOccupied(RoomId(room), 1, RoomStatusSource.CHECK_IN)
            call(POST, "/api/v1/rooms/$room/deactivate", asAdmin()).problem(422, "ROOM_OCCUPIED")
            roomsApi.markVacant(RoomId(room), RoomStatusSource.CHECK_OUT)
            call(POST, "/api/v1/rooms/$room/deactivate", asAdmin()).ok()["active"].asBoolean() shouldBe false
            shouldThrow<BusinessRuleException> { roomsApi.markOccupied(RoomId(room), 1, RoomStatusSource.CHECK_IN) }
                .code shouldBe ErrorCode("ROOM_INACTIVE")
            roomsApi.isReadyForCheckIn(RoomId(room)) shouldBe false
        }

    // --- API pública -------------------------------------------------------------------------------

    @Test
    fun `every change through the public API is recorded with its source and the system as author`(): Unit =
        with(iam) {
            val id = RoomId(rooms.createRoom(rooms.createRoomType()))
            roomsApi.status(id).shouldNotBeNull().housekeepingStatus shouldBe HousekeepingStatus.DIRTY
            roomsApi.status(RoomId(UUID.randomUUID())).shouldBeNull()
            shouldThrow<NotFoundException> { roomsApi.markDirty(RoomId(UUID.randomUUID()), RoomStatusSource.TASK) }
                .code shouldBe ErrorCode("ROOM_NOT_FOUND")

            roomsApi.markClean(id, requiresInspection = true, source = RoomStatusSource.TASK).let {
                it.housekeepingStatus shouldBe HousekeepingStatus.CLEAN
                it.inspectionPending shouldBe true
                it.readyForCheckIn shouldBe false
            }
            roomsApi.isReadyForCheckIn(id) shouldBe false
            roomsApi.rejectInspection(id, RoomStatusSource.TASK).housekeepingStatus shouldBe HousekeepingStatus.DIRTY
            roomsApi.markClean(id, requiresInspection = false, source = RoomStatusSource.TASK)
            roomsApi.isReadyForCheckIn(id) shouldBe true
            roomsApi.markInspected(id, RoomStatusSource.TASK).housekeepingStatus shouldBe HousekeepingStatus.INSPECTED
            roomsApi.markOccupied(id, 2, RoomStatusSource.CHECK_IN).let {
                it.occupancy shouldBe OccupancyStatus.OCCUPIED
                it.guestCount shouldBe 2
                it.readyForCheckIn shouldBe false
            }
            roomsApi.setServiceFlag(id, GuestServiceFlag.DO_NOT_DISTURB, RoomStatusSource.MANUAL)
            roomsApi.markDirty(id, RoomStatusSource.END_OF_DAY)
            roomsApi.markClean(id, requiresInspection = false, source = RoomStatusSource.TASK)
            roomsApi.markVacant(id, RoomStatusSource.CHECK_OUT).let {
                it.occupancy shouldBe OccupancyStatus.VACANT
                it.guestCount shouldBe null
                it.serviceFlag shouldBe GuestServiceFlag.NONE
                it.housekeepingStatus shouldBe HousekeepingStatus.DIRTY // RN-09
            }
            // Transição proibida pela API pública: recusada, sem histórico.
            val before = history(id.value).size
            shouldThrow<BusinessRuleException> { roomsApi.markInspected(id, RoomStatusSource.TASK) }.code shouldBe
                ErrorCode("HOUSEKEEPING_TRANSITION_NOT_ALLOWED")
            // Repetir o estado atual não muda nada nem grava histórico.
            roomsApi.markDirty(id, RoomStatusSource.MANUAL)
            history(id.value).size shouldBe before

            val entries = history(id.value).reversed()
            entries.map { "${it["field"].asString()}:${it["source"].asString()}" } shouldContainExactly
                listOf(
                    "HOUSEKEEPING_STATUS:TASK",
                    "INSPECTION_PENDING:TASK",
                    "HOUSEKEEPING_STATUS:TASK",
                    "INSPECTION_PENDING:TASK",
                    "HOUSEKEEPING_STATUS:TASK",
                    "HOUSEKEEPING_STATUS:TASK",
                    "OCCUPANCY:CHECK_IN",
                    "GUEST_COUNT:CHECK_IN",
                    "SERVICE_FLAG:MANUAL",
                    "HOUSEKEEPING_STATUS:END_OF_DAY",
                    "HOUSEKEEPING_STATUS:TASK",
                    "OCCUPANCY:CHECK_OUT",
                    "GUEST_COUNT:CHECK_OUT",
                    "SERVICE_FLAG:CHECK_OUT",
                    "HOUSEKEEPING_STATUS:CHECK_OUT",
                )
            // Sem requisição autenticada, o autor é o sistema (nulo).
            entries.all { it["changedBy"].isNull } shouldBe true
            entries.map { it["version"].asLong() }.zipWithNext().all { (a, b) -> a <= b } shouldBe true
            auditActions(id.value) shouldContain "rooms.room-status.mark-vacant"
        }

    // --- Discrepâncias (RF-UH-08) ------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // as três discrepâncias da RF-UH-08, os casos sem discrepância e a resolução
    fun `housekeeping observations that differ from the front desk show up as discrepancies`(): Unit =
        with(iam) {
            val type = rooms.createRoomType()
            val occupiedFoundVacant = RoomId(rooms.createRoom(type))
            val vacantFoundOccupied = RoomId(rooms.createRoom(type))
            val countMismatch = RoomId(rooms.createRoom(type))
            val matching = RoomId(rooms.createRoom(type))
            val uncounted = RoomId(rooms.createRoom(type))
            listOf(occupiedFoundVacant, countMismatch, matching, uncounted).forEach {
                roomsApi.markOccupied(it, 2, RoomStatusSource.CHECK_IN)
            }
            val housekeeper = createUserWith(TestGrant(definition(RoomsPermissions.STATUS_OBSERVE), ALL))
            val reader = createUserWith(TestGrant(definition(RoomsPermissions.STATUS_READ), ALL))

            fun observe(
                room: RoomId,
                body: Map<String, Any?>,
            ): MockHttpServletResponse =
                call(POST, "/api/v1/rooms/$room/housekeeping-observations", asTestUser(housekeeper), body)

            observe(occupiedFoundVacant, mapOf("occupancy" to "VACANT")).expectStatus(201).shouldNotBeNull().let {
                it["roomId"].asString() shouldBe occupiedFoundVacant.toString()
                it["occupancy"].asString() shouldBe "VACANT"
                it["observedBy"].asString() shouldBe housekeeper.id.toString()
            }
            observe(vacantFoundOccupied, mapOf("occupancy" to "OCCUPIED", "guestCount" to 1)).expectStatus(201)
            observe(countMismatch, mapOf("occupancy" to "OCCUPIED", "guestCount" to 3)).expectStatus(201)
            observe(matching, mapOf("occupancy" to "OCCUPIED", "guestCount" to 2)).expectStatus(201)
            observe(uncounted, mapOf("occupancy" to "OCCUPIED")).expectStatus(201)

            // Validação e permissão.
            observe(matching, mapOf("occupancy" to "VACANT", "guestCount" to 1)).problem(400, "GUEST_COUNT_INVALID")
            observe(matching, mapOf("occupancy" to "OCCUPIED", "guestCount" to 0)).problem(400, "VALIDATION_FAILED")
            call(
                POST,
                "/api/v1/rooms/$matching/housekeeping-observations",
                asTestUser(reader),
                mapOf("occupancy" to "VACANT"),
            ).problem(403, "PERMISSION_DENIED")
            call(
                POST,
                "/api/v1/rooms/${UUID.randomUUID()}/housekeeping-observations",
                asTestUser(housekeeper),
                mapOf("occupancy" to "VACANT"),
            ).problem(404, "ROOM_NOT_FOUND")

            val ours = setOf(occupiedFoundVacant, vacantFoundOccupied, countMismatch, matching, uncounted)
            val found =
                discrepancies(asTestUser(reader)).filter {
                    RoomId(UUID.fromString(it["roomId"].asString())) in
                        ours
                }
            found.associate { it["roomId"].asString() to it["kind"].asString() } shouldBe
                mapOf(
                    occupiedFoundVacant.toString() to "OCCUPIED_BUT_FOUND_VACANT",
                    vacantFoundOccupied.toString() to "VACANT_BUT_FOUND_OCCUPIED",
                    countMismatch.toString() to "GUEST_COUNT_MISMATCH",
                )
            found.single { it["kind"].asString() == "GUEST_COUNT_MISMATCH" }.let {
                it["frontDeskOccupancy"].asString() shouldBe "OCCUPIED"
                it["frontDeskGuestCount"].asInt() shouldBe 2
                it["observedOccupancy"].asString() shouldBe "OCCUPIED"
                it["observedGuestCount"].asInt() shouldBe 3
                it["observedBy"].asString() shouldBe housekeeper.id.toString()
                it["observedAt"].isNull shouldBe false
            }

            // A recepção corrige: a observação anterior à correção deixa de valer.
            roomsApi.markVacant(occupiedFoundVacant, RoomStatusSource.MANUAL)
            roomsApi.markOccupied(vacantFoundOccupied, 1, RoomStatusSource.MANUAL)
            roomsApi.markOccupied(countMismatch, 3, RoomStatusSource.MANUAL)
            // Uma observação nova que diverge volta a aparecer.
            val observation =
                observe(matching, mapOf("occupancy" to "VACANT")).expectStatus(201).shouldNotBeNull()["id"].asString()
            discrepancies(asAdmin())
                .filter { RoomId(UUID.fromString(it["roomId"].asString())) in ours }
                .associate { it["roomId"].asString() to it["kind"].asString() } shouldBe
                mapOf(matching.toString() to "OCCUPIED_BUT_FOUND_VACANT")
            auditActions(UUID.fromString(observation)) shouldBe listOf("rooms.housekeeping-observation.record")
        }

    // --- Concorrência ------------------------------------------------------------------------------

    @Test
    fun `two manual changes with the same If-Match at the same time - one applies, the other gets 412`(): Unit =
        with(iam) {
            val room = rooms.createRoom(rooms.createRoomType())
            val path = "/api/v1/rooms/$room/housekeeping-status"
            val etag = etag(room)
            val start = CountDownLatch(1)
            val bodies =
                listOf(
                    mapOf("transition" to "MARK_CLEAN", "requiresInspection" to true),
                    mapOf("transition" to "MARK_CLEAN", "requiresInspection" to false),
                )
            val statuses = CopyOnWriteArrayList<Int>()
            val threads =
                bodies.map { body ->
                    thread {
                        start.await()
                        statuses += call(POST, path, asAdmin(), body, ifMatch = etag).status
                    }
                }
            start.countDown()
            threads.forEach { it.join() }

            statuses shouldContainExactlyInAnyOrder listOf(200, 412)
            history(room).count { it["field"].asString() == "HOUSEKEEPING_STATUS" } shouldBe 1
        }

    @Test
    fun `a change through the public API waits for a concurrent one and sees its result, never overwriting it`() {
        val room = RoomId(rooms.createRoom(rooms.createRoomType()))
        val changed = CountDownLatch(1)
        val holder =
            thread {
                transactions.executeWithoutResult {
                    roomsApi.markClean(room, requiresInspection = true, source = RoomStatusSource.TASK)
                    changed.countDown()
                    Thread.sleep(LOCK_HOLD_MILLIS)
                }
            }
        changed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
        // Lida antes do commit, a UH ainda estaria suja; com a trava, a segunda mudança espera e é recusada.
        shouldThrow<BusinessRuleException> {
            roomsApi.markClean(room, requiresInspection = false, source = RoomStatusSource.TASK)
        }.code shouldBe ErrorCode("HOUSEKEEPING_TRANSITION_NOT_ALLOWED")
        holder.join()

        roomsApi.status(room).shouldNotBeNull().let {
            it.housekeepingStatus shouldBe HousekeepingStatus.CLEAN
            it.inspectionPending shouldBe true
        }
        history(room.value).count { it["field"].asString() == "HOUSEKEEPING_STATUS" } shouldBe 1
    }

    // --- Evento ------------------------------------------------------------------------------------

    @Test
    fun `RoomStatusChanged carries the room, its dimensions and version, and nothing about guests`(scenario: Scenario) {
        val room = RoomId(rooms.createRoom(rooms.createRoomType()))
        roomsApi.markOccupied(room, 3, RoomStatusSource.CHECK_IN)

        scenario
            .stimulate(
                Supplier { roomsApi.setServiceFlag(room, GuestServiceFlag.MAKE_UP_ROOM, RoomStatusSource.MANUAL) },
            ).andWaitForEventOfType(RoomStatusChanged::class.java)
            .matching { it.roomId == room.value && it.serviceFlag == GuestServiceFlag.MAKE_UP_ROOM }
            .toArriveAndVerify { event, result ->
                event.occupancy shouldBe OccupancyStatus.OCCUPIED
                event.housekeepingStatus shouldBe HousekeepingStatus.DIRTY
                event.inspectionPending shouldBe false
                event.version shouldBe result.version
            }

        val serialized = serializedEvents(room).last()
        val fields = jsonMapper.readTree(serialized).propertyNames().toSet()
        fields shouldBe
            setOf(
                "roomId",
                "occupancy",
                "housekeepingStatus",
                "inspectionPending",
                "serviceFlag",
                "version",
                "changedAt",
            )
    }

    @Test
    fun `the event and the history are written in the changing transaction, and a rollback leaves neither`() {
        val room = RoomId(rooms.createRoom(rooms.createRoomType()))

        transactions.executeWithoutResult { status ->
            roomsApi.markClean(room, requiresInspection = false, source = RoomStatusSource.TASK)
            status.setRollbackOnly()
        }
        serializedEvents(room).shouldBeEmpty()
        history(room.value).shouldBeEmpty()
        roomsApi.status(room).shouldNotBeNull().housekeepingStatus shouldBe HousekeepingStatus.DIRTY

        transactions.executeWithoutResult { _ ->
            roomsApi.markClean(room, requiresInspection = false, source = RoomStatusSource.TASK)
            // Ainda na transação: a publicação já está gravada e o listener não a recebeu.
            publicationStatuses(room) shouldBe listOf("PUBLISHED")
            RoomStatusEvents.received.none { it.roomId == room.value } shouldBe true
        }
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until { publicationStatuses(room) == listOf("COMPLETED") }
        RoomStatusEvents.received.single { it.roomId == room.value }.housekeepingStatus shouldBe
            HousekeepingStatus.CLEAN
        // Nada mudou: nem histórico nem evento.
        roomsApi.markClean(room, requiresInspection = false, source = RoomStatusSource.TASK)
        publicationStatuses(room).size shouldBe 1
    }

    @Test
    fun `the history and the observations are append-only for the application role`() {
        val room = RoomId(rooms.createRoom(rooms.createRoomType()))
        roomsApi.markClean(room, requiresInspection = false, source = RoomStatusSource.TASK)
        for (sql in listOf(
            "UPDATE rooms.room_status_change SET source = 'MANUAL' WHERE room_id = :id",
            "DELETE FROM rooms.room_status_change WHERE room_id = :id",
            "UPDATE rooms.housekeeping_observation SET guest_count = 1 WHERE room_id = :id",
            "DELETE FROM rooms.housekeeping_observation WHERE room_id = :id",
        )) {
            withClue(sql) {
                shouldThrow<DataAccessException> {
                    transactions.executeWithoutResult { jdbc.sql(sql).param("id", room.value).update() }
                }
            }
        }
        history(room.value).size shouldBe 1
    }

    // --- Apoio -------------------------------------------------------------------------------------

    private fun board(
        auth: RequestPostProcessor,
        query: String = "",
    ): List<JsonNode> = rooms.listAll("/api/v1/rooms/status$query", auth)

    private fun discrepancies(auth: RequestPostProcessor): List<JsonNode> =
        rooms.listAll("/api/v1/rooms/discrepancies", auth)

    private fun history(
        room: UUID,
        auth: RequestPostProcessor = iam.asAdmin(),
    ): List<JsonNode> = rooms.listAll("/api/v1/rooms/$room/status-history", auth, limit = 3)

    private fun etag(room: UUID): String = rooms.etagOf("/api/v1/rooms/$room/status")

    private fun auditActions(entityId: UUID): List<String> =
        jdbc
            .sql("SELECT action FROM audit.audit_entry WHERE entity_id = :id ORDER BY occurred_at, id")
            .param("id", entityId.toString())
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun serializedEvents(room: RoomId): List<String> =
        jdbc
            .sql(
                "SELECT serialized_event FROM shared.event_publication " +
                    "WHERE event_type = :type AND serialized_event LIKE :room ORDER BY publication_date",
            ).param("type", RoomStatusChanged::class.java.name)
            .param("room", "%${room.value}%")
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun publicationStatuses(room: RoomId): List<String> =
        jdbc
            .sql(
                "SELECT status FROM shared.event_publication " +
                    "WHERE event_type = :type AND serialized_event LIKE :room ORDER BY publication_date",
            ).param("type", RoomStatusChanged::class.java.name)
            .param("room", "%${room.value}%")
            .query(String::class.java)
            .list()
            .filterNotNull()

    private companion object {
        const val LOCK_HOLD_MILLIS = 1500L
        const val TIMEOUT_SECONDS = 15L
    }
}
