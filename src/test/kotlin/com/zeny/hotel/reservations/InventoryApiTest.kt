package com.zeny.hotel.reservations

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.reservations.application.InventoryListeners
import com.zeny.hotel.reservations.application.RoomTypeInventory
import com.zeny.hotel.reservations.domain.InventoryChange
import com.zeny.hotel.reservations.domain.InventoryOutOfHorizonException
import com.zeny.hotel.reservations.domain.InventoryUse
import com.zeny.hotel.reservations.domain.RoomTypeUnavailableException
import com.zeny.hotel.reservations.infrastructure.InventoryHorizonJob
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OutOfOrderAction
import com.zeny.hotel.rooms.OutOfOrderChanged
import com.zeny.hotel.rooms.OutOfOrderKind
import com.zeny.hotel.rooms.OutOfOrderRequest
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomRegistryChange
import com.zeny.hotel.rooms.RoomRegistryChanged
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.INVENTORY_HORIZON_DEFERRED
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.InventoryListenersQuiescence
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.test.EnableScenarios
import org.springframework.modulith.test.Scenario
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.test.util.AopTestUtils
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.function.Supplier
import kotlin.concurrent.thread

/**
 * O inventário por tipo de UH e data (M4 E05; RF-TAR-08 e 09, RN-07, Q-09, H3, H4 e H10): o total pelo cadastro de
 * UH (evento novo do `rooms`), o OOO pelo listener de `OutOfOrderChanged` (o OOS não conta) e idempotente na
 * reentrega, a tomada da última unidade sob concorrência, a ordem das travas sem deadlock, o limite de overbooking, o
 * horizonte, a recontagem e `AvailabilityChanged`. Cada teste cria os próprios tipos de UH (banco compartilhado).
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LargeClass", "LongParameterList") // os cenários do inventário de ponta a ponta e o que eles usam
class InventoryApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired private val inventory: RoomTypeInventory,
    @Autowired private val listeners: InventoryListeners,
    @Autowired private val horizonJob: InventoryHorizonJob,
    @Autowired private val transactions: TransactionTemplate,
) {
    private val support = InventoryTestSupport(iam, jdbc)
    private val rooms = support.rooms
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    // --- Total pelo cadastro de UH (H4) ----------------------------------------------------------------

    @Test
    fun `creating, deactivating, reactivating and moving rooms updates the totals of the affected types`() {
        val day = today.plusDays(3)
        val (single, singleRooms) = support.roomTypeWithRooms(2, day)
        val (double, _) = support.roomTypeWithRooms(1, day)
        support.shouldHaveHorizon(single, today)
        support.row(single, today.plusDays(InventoryTestSupport.HORIZON_DAYS - 1))!!.total shouldBe 2

        val moved = singleRooms.first()
        val path = "/api/v1/rooms/$moved"
        with(iam) {
            call(POST, "$path/deactivate", asAdmin()).ok()
            support.awaitRow(single, day) { it.total == 1 }
            call(POST, "$path/reactivate", asAdmin()).ok()
            support.awaitRow(single, day) { it.total == 2 }
            call(PUT, path, asAdmin(), rooms.roomBody(double), ifMatch = rooms.etagOf(path)).ok()
        }
        support.awaitRow(single, day) { it.total == 1 }
        support.awaitRow(double, day) { it.total == 2 }
        support.settledRow(single, today).total shouldBe 1
        support.settledRow(double, today.plusDays(InventoryTestSupport.HORIZON_DAYS - 1)).total shouldBe 2
    }

    @Test
    fun `the room registry listener is idempotent and recomputes from the RoomsApi instead of adding deltas`() {
        val day = today.plusDays(2)
        val (type, created) = support.roomTypeWithRooms(2, day)
        val direct = AopTestUtils.getTargetObject<InventoryListeners>(listeners)
        val event =
            RoomRegistryChanged(created.first(), type, null, true, RoomRegistryChange.CREATED, 0, Instant.now())

        repeat(3) { direct.on(event) }

        support.settledRow(type, day).total shouldBe 2
        support.rowCount(type) shouldBe InventoryTestSupport.HORIZON_DAYS.toInt()
    }

    // --- OOO pelo listener (H10) -----------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // abertura, alteração e liberação, com o evento
    fun `an OOO reduces the availability on its dates through the listener, on open, update and release`() {
        val (type, created) = support.roomTypeWithRooms(2, today)
        val room = created.first()
        val start = today.plusDays(10)
        val (period, opened) =
            support.publishedBy(type) {
                roomsApi.openOutOfOrder(RoomId(room), OutOfOrderKind.OUT_OF_ORDER, request(start, start.plusDays(2)))
            }

        // Um AvailabilityChanged pelo outbox por ação, com as datas que mudaram.
        opened shouldContainExactly listOf(start to start.plusDays(2))
        support.row(type, start)!!.outOfOrder shouldBe 1
        support.row(type, start)!!.available shouldBe 1
        support.row(type, start.plusDays(2))!!.outOfOrder shouldBe 1
        support.row(type, start.plusDays(3))!!.outOfOrder shouldBe 0
        support.row(type, start.minusDays(1))!!.outOfOrder shouldBe 0

        // A alteração refaz as datas do período novo e as do anterior.
        val path = "/api/v1/rooms/$room/out-of-order/${period.id}"
        val (_, updated) =
            support.publishedBy(type) {
                with(iam) {
                    call(
                        PUT,
                        path,
                        asAdmin(),
                        periodBody(start.plusDays(5), start.plusDays(6)),
                        ifMatch = rooms.etagOf(path),
                    ).ok()
                }
            }
        updated shouldContainExactly listOf(start to start.plusDays(6))
        support.row(type, start)!!.outOfOrder shouldBe 0
        support.row(type, start.plusDays(2))!!.outOfOrder shouldBe 0
        support.row(type, start.plusDays(5))!!.outOfOrder shouldBe 1
        support.row(type, start.plusDays(6))!!.outOfOrder shouldBe 1

        // A liberação antes de começar devolve todas as datas.
        val (_, released) =
            support.publishedBy(type) { roomsApi.releaseOutOfOrder(period.id, RoomStatusSource.MANUAL) }
        released shouldContainExactly listOf(start.plusDays(5) to start.plusDays(6))
        support.row(type, start.plusDays(5))!!.outOfOrder shouldBe 0
        support.row(type, start.plusDays(6))!!.outOfOrder shouldBe 0
        await().atMost(Duration.ofSeconds(TIMEOUT)).until { AvailabilityChangedEvents.of(type).size >= 3 }
    }

    @Test
    fun `taking and releasing units publish AvailabilityChanged with the nights, a net zero change publishes none`(
        scenario: Scenario,
    ) {
        val day = today.plusDays(90)
        val (type, _) = support.roomTypeWithRooms(2, day)
        val id = RoomTypeId(type)
        // Os eventos dos listeners da criação das UHs não podem passar pelo da tomada.
        InventoryListenersQuiescence.await(jdbc)

        scenario
            .stimulate(Runnable { transactions.executeWithoutResult { inventory.take(id, day, day.plusDays(3)) } })
            .andWaitForEventOfType(AvailabilityChanged::class.java)
            .matching { it.roomTypeId == type && it.from == day }
            .toArriveAndVerify { event ->
                event.from shouldBe day
                event.to shouldBe day.plusDays(2)
            }

        val (_, released) =
            support.publishedBy(type) {
                transactions.executeWithoutResult { inventory.release(id, day.plusDays(1), day.plusDays(3)) }
            }
        released shouldContainExactly listOf(day.plusDays(1) to day.plusDays(2))
        val (_, netZero) =
            support.publishedBy(type) {
                transactions.executeWithoutResult {
                    inventory.apply(
                        listOf(
                            InventoryChange.take(id, day, day.plusDays(1), use = InventoryUse.BLOCKED),
                            InventoryChange.release(id, day, day.plusDays(1)),
                        ),
                    )
                }
            }
        netZero shouldContainExactly listOf(day to day)
        val (_, nothing) =
            support.publishedBy(type) {
                transactions.executeWithoutResult {
                    inventory.apply(
                        listOf(
                            InventoryChange.take(id, day, day.plusDays(1)),
                            InventoryChange.release(id, day, day.plusDays(1)),
                        ),
                    )
                }
            }
        nothing.shouldBeEmpty()
        support.row(type, day) shouldBe InventoryRow(2, 0, 1, 0, 0)
    }

    @Test
    fun `redelivering the same OutOfOrderChanged does not change the counters, and an OOS never reduces them`() {
        val (type, created) = support.roomTypeWithRooms(2, today)
        val start = today.plusDays(20)
        val ooo =
            roomsApi.openOutOfOrder(RoomId(created[0]), OutOfOrderKind.OUT_OF_ORDER, request(start, start.plusDays(1)))
        roomsApi.openOutOfOrder(RoomId(created[1]), OutOfOrderKind.OUT_OF_SERVICE, request(start, start.plusDays(4)))
        support.awaitRow(type, start) { it.outOfOrder == 1 }
        val before = support.settledRow(type, start)
        val events = AvailabilityChangedEvents.of(type).size

        val event =
            OutOfOrderChanged(
                ooo.id.value,
                created[0],
                OutOfOrderKind.OUT_OF_ORDER,
                OutOfOrderAction.OPENED,
                start,
                start.plusDays(1),
                null,
                start.plusDays(1),
                null,
                null,
                0,
                Instant.now(),
            )
        val direct = AopTestUtils.getTargetObject<InventoryListeners>(listeners)
        repeat(3) { direct.on(event) }

        support.settledRow(type, start) shouldBe before
        before.outOfOrder shouldBe 1
        before.available shouldBe 1
        support.row(type, start.plusDays(3))!!.outOfOrder shouldBe 0
        AvailabilityChangedEvents.of(type).size shouldBe events
    }

    @Test
    fun `an OOO of a deactivated room is not counted, since the room is already out of the total`() {
        val (type, created) = support.roomTypeWithRooms(2, today)
        val start = today.plusDays(30)
        roomsApi.openOutOfOrder(RoomId(created[0]), OutOfOrderKind.OUT_OF_ORDER, request(start, start))
        support.awaitRow(type, start) { it.outOfOrder == 1 }

        with(iam) { call(POST, "/api/v1/rooms/${created[0]}/deactivate", asAdmin()).ok() }

        support.awaitRow(type, start) { it.total == 1 && it.outOfOrder == 0 }.available shouldBe 1
    }

    // --- Tomada concorrente e ordem das travas -----------------------------------------------------------

    @Test
    fun `N simultaneous transactions for the last unit of a type on a date, and exactly one gets it`() {
        val day = today.plusDays(40)
        val (type, _) = support.roomTypeWithRooms(1, day)
        val start = CountDownLatch(1)
        val outcomes = arrayOfNulls<Result<Unit>>(CONCURRENT)
        val threads =
            (0 until CONCURRENT).map { index ->
                thread {
                    start.await()
                    outcomes[index] =
                        runCatching {
                            transactions.executeWithoutResult {
                                inventory.take(RoomTypeId(type), day, day.plusDays(1))
                            }
                        }
                }
            }
        start.countDown()
        threads.forEach { it.join() }

        outcomes.count { it!!.isSuccess } shouldBe 1
        val refused = outcomes.mapNotNull { it!!.exceptionOrNull() }
        refused shouldHaveSize CONCURRENT - 1
        refused.all { it is RoomTypeUnavailableException } shouldBe true
        refused.map { (it as RoomTypeUnavailableException).code.value }.distinct() shouldContainExactly
            listOf("ROOM_TYPE_UNAVAILABLE")
        support.row(type, day)!!.sold shouldBe 1
        support.row(type, day)!!.available shouldBe 0
    }

    @Test
    fun `overlapping ranges of two types requested in opposite orders never deadlock`() {
        val base = today.plusDays(60)
        val (first, _) = support.roomTypeWithRooms(2, base)
        val (second, _) = support.roomTypeWithRooms(2, base)
        val a = RoomTypeId(first)
        val b = RoomTypeId(second)
        repeat(ROUNDS) { round ->
            val from = base.plusDays(round * WINDOW)
            val barrier = CyclicBarrier(2)
            val requests =
                listOf(
                    // As duas pedem as linhas em comum em ordens opostas de tipo.
                    listOf(
                        InventoryChange.take(a, from, from.plusDays(10)),
                        InventoryChange.take(b, from, from.plusDays(10)),
                    ),
                    listOf(
                        InventoryChange.take(b, from.plusDays(5), from.plusDays(15)),
                        InventoryChange.take(a, from.plusDays(5), from.plusDays(15)),
                    ),
                )
            val errors = arrayOfNulls<Throwable>(2)
            val threads =
                requests.mapIndexed { index, changes ->
                    thread {
                        barrier.await()
                        errors[index] =
                            runCatching { transactions.executeWithoutResult { inventory.apply(changes) } }
                                .exceptionOrNull()
                    }
                }
            threads.forEach { it.join() }
            errors.filterNotNull().shouldBeEmpty()
        }
        val overlap = base.plusDays(7)
        support.row(first, overlap)!!.sold shouldBe 2
        support.row(second, overlap)!!.sold shouldBe 2
        support.row(first, base)!!.sold shouldBe 1
    }

    @Test
    fun `the inventory only changes inside the caller's write transaction`() {
        val day = today.plusDays(45)
        val (type, _) = support.roomTypeWithRooms(1, day)

        shouldThrow<IllegalStateException> { inventory.take(RoomTypeId(type), day, day.plusDays(1)) }
        val readOnly = TransactionTemplate(transactions.transactionManager!!).apply { isReadOnly = true }
        shouldThrow<IllegalStateException> {
            readOnly.executeWithoutResult { inventory.take(RoomTypeId(type), day, day.plusDays(1)) }
        }
        support.row(type, day)!!.sold shouldBe 0
    }

    @Test
    fun `a release gives the units back, and the transaction rolled back changes nothing`() {
        val day = today.plusDays(50)
        val (type, _) = support.roomTypeWithRooms(2, day)
        val id = RoomTypeId(type)
        transactions.executeWithoutResult { inventory.take(id, day, day.plusDays(3), quantity = 2) }
        support.row(type, day.plusDays(2))!!.available shouldBe 0

        runCatching {
            transactions.executeWithoutResult {
                inventory.release(id, day, day.plusDays(3), quantity = 2)
                error("desfaz")
            }
        }
        support.row(type, day)!!.sold shouldBe 2

        transactions.executeWithoutResult { inventory.release(id, day, day.plusDays(3)) }
        support.row(type, day)!!.sold shouldBe 1
        shouldThrow<IllegalStateException> {
            transactions.executeWithoutResult { inventory.release(id, day, day.plusDays(1), quantity = 2) }
        }
        support.row(type, day)!!.sold shouldBe 1
    }

    @Test
    fun `taking a date beyond the horizon is refused with INVENTORY_DATE_OUT_OF_HORIZON`() {
        val (type, _) = support.roomTypeWithRooms(1, today)
        val last = today.plusDays(InventoryTestSupport.HORIZON_DAYS - 1)

        val refused =
            shouldThrow<InventoryOutOfHorizonException> {
                transactions.executeWithoutResult { inventory.take(RoomTypeId(type), last, last.plusDays(2)) }
            }
        refused.code.value shouldBe "INVENTORY_DATE_OUT_OF_HORIZON"
        refused.dates shouldContainExactly listOf(last.plusDays(1))
        support.row(type, last)!!.sold shouldBe 0
    }

    // --- Limite de overbooking (RF-TAR-09, Q-09) -----------------------------------------------------------

    @Test
    @Suppress("LongMethod") // limite zero, configurado, respeitado, auditado e com o evento
    fun `the overbooking limit starts at zero and, when configured, is respected`(scenario: Scenario) {
        val day = today.plusDays(70)
        val (type, _) = support.roomTypeWithRooms(1, day)
        val id = RoomTypeId(type)
        support.row(type, day)!!.overbookingLimit shouldBe 0
        transactions.executeWithoutResult { inventory.take(id, day, day.plusDays(1)) }
        shouldThrow<RoomTypeUnavailableException> {
            transactions.executeWithoutResult { inventory.take(id, day, day.plusDays(1)) }
        }

        scenario
            .stimulate(
                Supplier {
                    with(iam) { call(POST, LIMIT, asAdmin(), limitBody(listOf(type), day, day, 1)).ok() }
                },
            ).andWaitForEventOfType(AvailabilityChanged::class.java)
            .matching { it.roomTypeId == type && it.from == day && it.to == day }
            .toArriveAndVerify { _, response -> response["changedRows"].asInt() shouldBe 1 }

        transactions.executeWithoutResult { inventory.take(id, day, day.plusDays(1)) }
        shouldThrow<RoomTypeUnavailableException> {
            transactions.executeWithoutResult { inventory.take(id, day, day.plusDays(1)) }
        }
        support.row(type, day) shouldBe InventoryRow(1, 2, 0, 0, 1)
        support.row(type, day.plusDays(1))!!.overbookingLimit shouldBe 0

        // Repetir não muda nada; a trilha guarda o antes e o depois.
        val repeated = with(iam) { call(POST, LIMIT, asAdmin(), limitBody(listOf(type), day, day, 1)).ok() }
        repeated["changedRows"].asInt() shouldBe 0
        val audited =
            jdbc
                .sql(
                    "SELECT after_state ->> 'overbookingLimit' FROM audit.audit_entry " +
                        "WHERE action = :action AND entity_id = :type",
                ).param("action", "reservations.inventory.set-overbooking-limit")
                .param("type", type.toString())
                .query(String::class.java)
                .list()
        audited shouldHaveSize 1
        audited.single() shouldBe "[1]"
    }

    @Test
    fun `the overbooking limit endpoint checks permission, format, past dates, weekdays and the horizon`() {
        val (type, _) = support.roomTypeWithRooms(1, today)
        val reader = iam.createUserWith(TestGrant(definition(ReservationsPermissions.AVAILABILITY_READ), ALL))
        with(iam) {
            call(POST, LIMIT, asTestUser(reader), limitBody(listOf(type), today, today, 1))
                .problem(403, "PERMISSION_DENIED")
            call(POST, LIMIT, asAdmin(), limitBody(listOf(type), today, today, 51))
                .problem(400, "OVERBOOKING_LIMIT_INVALID")
            call(POST, LIMIT, asAdmin(), limitBody(emptyList(), today, today, 1))
                .problem(400, "OVERBOOKING_LIMIT_INVALID")
            call(POST, LIMIT, asAdmin(), limitBody(listOf(type), today.plusDays(2), today, 1))
                .problem(400, "INVENTORY_PERIOD_INVALID")
            call(POST, LIMIT, asAdmin(), limitBody(listOf(type), today, today.plusDays(366), 1))
                .problem(400, "INVENTORY_PERIOD_TOO_LONG")
            call(POST, LIMIT, asAdmin(), limitBody(listOf(type), today.minusDays(1), today, 1))
                .problem(422, "INVENTORY_PERIOD_IN_PAST")
            call(POST, LIMIT, asAdmin(), limitBody(listOf(UUID.randomUUID()), today, today, 1))
                .problem(400, "UNKNOWN_ROOM_TYPE")
            val last = today.plusDays(InventoryTestSupport.HORIZON_DAYS - 1)
            call(POST, LIMIT, asAdmin(), limitBody(listOf(type), last, last.plusDays(1), 1))
                .problem(422, "INVENTORY_DATE_OUT_OF_HORIZON")["dates"]
                .values()
                .map { it.asString() } shouldContainExactly listOf(last.plusDays(1).toString())
            val sunday = generateSequence(today.plusDays(1)) { it.plusDays(1) }.first { it.dayOfWeek.value == 7 }
            call(POST, LIMIT, asAdmin(), limitBody(listOf(type), sunday, sunday, 1, listOf("MONDAY")))
                .problem(400, "INVENTORY_PERIOD_INVALID")
        }
        support.row(type, today)!!.overbookingLimit shouldBe 0
    }

    // --- Leitura, horizonte e recontagem ----------------------------------------------------------------

    @Test
    fun `the inventory read needs the permission and shows the counters`() {
        val (type, _) = support.roomTypeWithRooms(2, today)
        val reader = iam.createUserWith(TestGrant(definition(ReservationsPermissions.AVAILABILITY_READ), ALL))
        val nobody = iam.createUser()
        val path = "/api/v1/room-type-inventory?roomTypeId=$type&from=$today&to=${today.plusDays(2)}"
        with(iam) {
            val items = call(GET, path, asTestUser(reader)).ok()["items"].values()
            items shouldHaveSize 3
            items.first()["total"].asInt() shouldBe 2
            items.first()["available"].asInt() shouldBe 2
            items.first()["date"].asString() shouldBe today.toString()
            call(GET, path, asTestUser(nobody)).problem(403, "PERMISSION_DENIED")
            call(GET, "/api/v1/room-type-inventory?roomTypeId=${UUID.randomUUID()}&from=$today&to=$today", asAdmin())
                .problem(400, "UNKNOWN_ROOM_TYPE")
        }
        reservationsApi.inventory(RoomTypeId(type), today, today.plusDays(1)).map { it.available } shouldContainExactly
            listOf(2, 2)
    }

    @Test
    fun `the horizon job is locked by ShedLock and creates the missing dates with the right counters`() {
        val (type, created) = support.roomTypeWithRooms(2, today)
        roomsApi.openOutOfOrder(
            RoomId(created[0]),
            OutOfOrderKind.OUT_OF_ORDER,
            request(today.plusDays(300), today.plusDays(301)),
        )
        support.awaitRow(type, today.plusDays(300)) { it.outOfOrder == 1 }
        // Como se a data operacional tivesse avançado: o fim do horizonte some.
        jdbc
            .sql("DELETE FROM reservations.room_type_inventory WHERE room_type_id = :type AND stay_date >= :from")
            .param("type", type)
            .param("from", today.plusDays(200))
            .update()

        horizonJob.extendHorizon()

        support.shouldHaveHorizon(type, today)
        support.row(type, today.plusDays(300))!!.outOfOrder shouldBe 1
        support.row(type, today.plusDays(600)) shouldBe InventoryRow(2, 0, 0, 0, 0)
        val method = InventoryHorizonJob::class.java.getMethod("extendHorizon")
        method.getAnnotation(SchedulerLock::class.java).name shouldBe "reservations-inventory-horizon"
        method.getAnnotation(Scheduled::class.java).initialDelayString shouldContain "inventory-horizon.initial-delay"
        INVENTORY_HORIZON_DEFERRED shouldContain "PT24H"
    }

    @Test
    fun `the recount repairs counters tampered with by SQL, through the public API and the endpoint`() {
        // Longe das datas dos outros testes desta classe: a recontagem refaz todos os tipos no período.
        val day = today.plusDays(700)
        val (type, created) = support.roomTypeWithRooms(3, day)
        roomsApi.openOutOfOrder(RoomId(created[0]), OutOfOrderKind.OUT_OF_ORDER, request(day, day))
        support.awaitRow(type, day) { it.outOfOrder == 1 }
        tamper(type, day)

        val (recount, recounted) = support.publishedBy(type) { reservationsApi.recountInventory(day, day.plusDays(1)) }
        recount.from shouldBe day
        recount.to shouldBe day.plusDays(1)
        (recount.changedRows >= 2) shouldBe true
        recounted shouldContainExactly listOf(day to day.plusDays(1))
        support.row(type, day) shouldBe InventoryRow(3, 0, 0, 1, 0)
        support.row(type, day.plusDays(1)) shouldBe InventoryRow(3, 0, 0, 0, 0)
        // Recontar de novo não muda nada nem publica.
        support.publishedBy(type) { reservationsApi.recountInventory(day, day.plusDays(1)) }.second.shouldBeEmpty()

        tamper(type, day)
        val nobody = iam.createUser()
        with(iam) {
            call(POST, "/api/v1/room-type-inventory/recount", asTestUser(nobody), mapOf("from" to day.toString()))
                .problem(403, "PERMISSION_DENIED")
            call(
                POST,
                "/api/v1/room-type-inventory/recount",
                asAdmin(),
                mapOf("from" to day.toString(), "to" to day.minusDays(1).toString()),
            ).problem(400, "INVENTORY_PERIOD_INVALID")
        }
        val (response, byEndpoint) =
            support.publishedBy(type) {
                with(iam) {
                    call(
                        POST,
                        "/api/v1/room-type-inventory/recount",
                        asAdmin(),
                        mapOf("from" to day.toString(), "to" to day.plusDays(1).toString()),
                    ).ok()
                }
            }
        (response["changedRows"].asInt() >= 2) shouldBe true
        byEndpoint shouldContainExactly listOf(day to day.plusDays(1))
        support.row(type, day) shouldBe InventoryRow(3, 0, 0, 1, 0)
    }

    @Test
    fun `the database refuses negative counters, OOO above the total and an out of range overbooking limit`() {
        val (type, _) = support.roomTypeWithRooms(1, today)
        val day = today.plusDays(5)

        fun set(assignment: String) =
            jdbc
                .sql(
                    "UPDATE reservations.room_type_inventory SET $assignment " +
                        "WHERE room_type_id = :type AND stay_date = :day",
                ).param("type", type)
                .param("day", day)
                .update()

        listOf("sold = -1", "blocked = -1", "out_of_order = 2", "overbooking_limit = 51", "total = -1").forEach {
            shouldThrow<org.springframework.dao.DataIntegrityViolationException> { set(it) }
        }
        support.row(type, day) shouldBe InventoryRow(1, 0, 0, 0, 0)
    }

    // --- Apoio ------------------------------------------------------------------------------------------

    private fun tamper(
        type: UUID,
        day: LocalDate,
    ) {
        jdbc
            .sql(
                "UPDATE reservations.room_type_inventory SET total = 9, sold = 4, out_of_order = 0 " +
                    "WHERE room_type_id = :type AND stay_date BETWEEN :day AND :next",
            ).param("type", type)
            .param("day", day)
            .param("next", day.plusDays(1))
            .update()
    }

    private fun request(
        startsOn: LocalDate,
        endsOn: LocalDate,
    ) = OutOfOrderRequest(startsOn, endsOn, "Infiltração", "Manutenção", HousekeepingStatus.DIRTY)

    private fun periodBody(
        startsOn: LocalDate,
        endsOn: LocalDate,
    ): Map<String, Any?> =
        mapOf(
            "startsOn" to startsOn.toString(),
            "endsOn" to endsOn.toString(),
            "reason" to "Infiltração",
            "responsible" to "Manutenção",
            "returnStatus" to "DIRTY",
        )

    @Suppress("LongParameterList") // o corpo inteiro do limite
    private fun limitBody(
        types: List<UUID>,
        from: LocalDate,
        to: LocalDate,
        limit: Int,
        weekdays: List<String> = emptyList(),
    ): Map<String, Any?> =
        mapOf(
            "roomTypeIds" to types,
            "from" to from.toString(),
            "to" to to.toString(),
            "weekdays" to weekdays,
            "limit" to limit,
        )

    private companion object {
        const val LIMIT = "/api/v1/room-type-inventory/overbooking-limit"
        const val CONCURRENT = 8
        const val ROUNDS = 10
        const val WINDOW = 20L
        const val TIMEOUT = 15L

        fun definition(code: com.zeny.hotel.shared.PermissionCode) =
            ReservationsPermissions.permissions.single { it.code == code }
    }
}
