package com.zeny.hotel.reservations

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.billing.FolioOwner
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.reservations.infrastructure.ProvisionalExpiryJob
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.test.EnableScenarios
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Grupos e bloqueios (M4 E11; RF-RES-09, RN-16): o bloqueio toma o contador `blocked` (sem disponibilidade, `409` e
 * nada gravado) e abre uma só conta master do grupo; o pick-up passa cada noite de `blocked` para `sold` (a
 * disponibilidade não muda) com a tarifa do grupo congelada; a rooming list repetida com a mesma `Idempotency-Key` não
 * duplica; `releaseUnused` antes da data de corte é `409` e depois devolve só o não utilizado; o cancelamento de uma
 * reserva do pick-up devolve a unidade ao bloqueio ativo; `GuestsMerged` reponta o titular. Cada teste cria os próprios
 * tipos de UH (banco compartilhado). O mesmo contexto dos outros testes das reservas.
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LargeClass", "LongParameterList") // os cenários dos bloqueios e as portas que eles usam
class RoomBlocksApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val billingApi: BillingApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    // --- Criação ------------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // o bloqueio inteiro: inventário, evento, conta master, idempotência e trilha
    fun `a block takes the blocked counter, opens one master folio and a repetition creates nothing more`() {
        val (type, _) = inventory.roomTypeWithRooms(3, today)
        val plan = support.plan(listOf(type))
        val holder = support.guest()
        val first = today.plusDays(5)
        val body =
            support.blockBody(
                plan.id,
                today.plusDays(3),
                support.allocation(type, first, first.plusDays(1), 2),
                holderGuest = holder,
            )
        val key = UuidV7.generate().toString()

        val (created, published) = inventory.publishedBy(type) { support.createBlock(body, idempotencyKey = key) }
        val again = support.createBlock(body, idempotencyKey = key)

        created.status shouldBe 201
        again.getHeader("Idempotent-Replayed") shouldBe "true"
        val block = iam.json(created)
        val blockId = UUID.fromString(block["id"].asString())
        block["code"].asString().matches(Regex("G-\\d{4}-\\d{6,}")) shouldBe true
        block["status"].asString() shouldBe "ACTIVE"
        block["holderGuestId"].asString() shouldBe holder.toString()
        block["totalQuantity"].asInt() shouldBe 4
        block["allocations"].values().map {
            Triple(it["date"].asString(), it["quantity"].asInt(), it["remaining"].asInt())
        } shouldContainExactly
            listOf(Triple(first.toString(), 2, 2), Triple(first.plusDays(1).toString(), 2, 2))
        // A disponibilidade cai: duas unidades bloqueadas em cada noite, nenhuma na saída.
        inventory.row(type, first)!!.let {
            it.blocked shouldBe 2
            it.available shouldBe 1
        }
        inventory.row(type, first.plusDays(1))!!.blocked shouldBe 2
        inventory.row(type, first.plusDays(2))!!.blocked shouldBe 0
        published shouldContainExactly listOf(first to first.plusDays(1))
        // Uma só conta master, do grupo, mesmo com a repetição.
        support.blocksNamed(body["name"] as String) shouldBe 1
        val folios = billingApi.foliosOf(FolioOwner(FolioOwnerType.GROUP, blockId))
        folios.map { it.id.value.toString() } shouldContainExactly listOf(block["folioId"].asString())
        // A trilha, sem o nome do grupo.
        val trail = support.auditEntries(blockId)
        trail.map { it["action"] } shouldContainExactly listOf("reservations.room-block.create")
        trail.single()["after"].toString() shouldNotContain "Casamento"
        support.block(blockId)["version"].asLong() shouldBe 0
    }

    @Test
    fun `without availability the block is refused with 409 and nothing is written`() {
        val (type, _) = inventory.roomTypeWithRooms(2, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(20))
        val first = today.plusDays(6)
        val folios = groupFolioCount()
        val body =
            support.blockBody(
                plan.id,
                today.plusDays(2),
                support.allocation(type, first, first.plusDays(1), 3),
                holderGuest = support.guest(),
            )

        val problem = with(support.billing) { support.createBlock(body).problem(409, "ROOM_TYPE_UNAVAILABLE") }

        problem["dates"].values().map { it.asString() } shouldContainExactly
            listOf(first.toString(), first.plusDays(1).toString())
        support.blocksNamed(body["name"] as String) shouldBe 0
        groupFolioCount() shouldBe folios
        inventory.row(type, first)!!.blocked shouldBe 0

        // Um bloqueio com as duas UHs deixa a data sem disponibilidade para uma reserva comum.
        support.createdBlock(
            support.blockBody(
                plan.id,
                today.plusDays(2),
                support.allocation(type, first, first, 2),
                holderGuest = support.guest(),
            ),
        )
        inventory.row(type, first)!!.available shouldBe 0
        with(support.billing) {
            support
                .create(
                    support.booking(support.guest(), support.reservation(type, plan.id, first, first.plusDays(1))),
                ).problem(409, "ROOM_TYPE_UNAVAILABLE")
        }
    }

    @Test
    @Suppress("LongMethod") // cada recusa da criação, com o campo
    fun `the block is refused with the field when its format, holder, plan, room type or dates are wrong`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type))
        val guest = support.guest()
        val ok = support.allocation(type, today.plusDays(3), today.plusDays(3), 1)
        with(support.billing) {
            fun refused(
                body: Map<String, Any?>,
                status: Int,
                code: String,
            ) = support.createBlock(body).problem(status, code)

            refused(support.blockBody(plan.id, today.plusDays(1), ok), 400, "ROOM_BLOCK_INVALID")["errors"][0]["field"]
                .asString() shouldBe "holderGuestId"
            refused(
                support.blockBody(
                    plan.id,
                    today.plusDays(1),
                    ok,
                    holderGuest = guest,
                    holderCompany = UuidV7.generate(),
                ),
                400,
                "ROOM_BLOCK_INVALID",
            )
            refused(
                support.blockBody(plan.id, today, holderGuest = guest),
                400,
                "ROOM_BLOCK_INVALID",
            )["errors"][0]["field"].asString() shouldBe "allocations"
            refused(
                support.blockBody(
                    plan.id,
                    today,
                    support.allocation(type, today.plusDays(3), today.plusDays(4), 1),
                    support.allocation(type, today.plusDays(4), today.plusDays(5), 1),
                    holderGuest = guest,
                ),
                400,
                "ROOM_BLOCK_INVALID",
            )["errors"][0]["field"].asString() shouldBe "allocations[1]"
            refused(
                support.blockBody(
                    plan.id,
                    today,
                    support.allocation(type, today.plusDays(3), today.plusDays(3), 51),
                    holderGuest = guest,
                ),
                400,
                "ROOM_BLOCK_INVALID",
            )["errors"][0]["field"].asString() shouldBe "allocations[0].quantity"
            refused(
                support.blockBody(plan.id, today.minusDays(1), ok, holderGuest = guest),
                422,
                "ROOM_BLOCK_CUTOFF_IN_PAST",
            )
            refused(
                support.blockBody(
                    plan.id,
                    today,
                    support.allocation(type, today.minusDays(1), today, 1),
                    holderGuest = guest,
                ),
                422,
                "ROOM_BLOCK_PERIOD_IN_PAST",
            )
            refused(support.blockBody(UuidV7.generate(), today, ok, holderGuest = guest), 400, "UNKNOWN_RATE_PLAN")
            refused(support.blockBody(plan.id, today, ok, holderGuest = UuidV7.generate()), 400, "UNKNOWN_GUEST")
            refused(support.blockBody(plan.id, today, ok, holderCompany = UuidV7.generate()), 400, "UNKNOWN_COMPANY")
            refused(
                support.blockBody(
                    plan.id,
                    today,
                    support.allocation(UuidV7.generate(), today.plusDays(3), today.plusDays(3), 1),
                    holderGuest = guest,
                ),
                400,
                "UNKNOWN_ROOM_TYPE",
            )["errors"][0]["field"].asString() shouldBe "allocations[0].roomTypeId"
        }
        inventory.row(type, today.plusDays(3))!!.blocked shouldBe 0
    }

    // --- Pick-up --------------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // o pick-up inteiro: inventário, tarifa congelada, alteração da tarifa e esgotamento
    fun `the pick-up moves a unit from blocked to sold at the frozen group rate, keeping the availability`() {
        val (type, _) = inventory.roomTypeWithRooms(3, today)
        val plan = support.plan(listOf(type))
        // O plano tem outro preço: a reserva do pick-up usa a tarifa do grupo.
        support.setPrices(plan.id, listOf(type), today, today.plusDays(20))
        val arrival = today.plusDays(5)
        val holder = support.guest()
        val block =
            support.createdBlock(
                support.blockBody(
                    plan.id,
                    today.plusDays(3),
                    support.allocation(type, arrival, arrival.plusDays(1), 2, "250.00"),
                    holderGuest = holder,
                ),
            )
        val blockId = UUID.fromString(block["id"].asString())
        val before = listOf(inventory.row(type, arrival)!!, inventory.row(type, arrival.plusDays(1))!!)

        val booking = with(support.billing) { support.pickUp(blockId, pickUpStay(type, arrival)).expect(201) }

        val reservation = booking["reservations"].values().single()
        booking["holderGuestId"].asString() shouldBe holder.toString()
        reservation["blockId"].asString() shouldBe blockId.toString()
        reservation["ratePlanId"].asString() shouldBe plan.id.toString()
        reservation["status"].asString() shouldBe "CONFIRMED"
        reservation["nightlyRates"].values().map {
            Triple(it["date"].asString(), it["roomAmount"].asString(), it["overridden"].asBoolean())
        } shouldContainExactly
            listOf(
                Triple(arrival.toString(), "250.00", false),
                Triple(arrival.plusDays(1).toString(), "250.00", false),
            )
        reservation["total"].asString() shouldBe "500.00"
        reservation["cancellationTerms"]["policyId"].asString() shouldBe plan.cancellationPolicy.toString()
        // blocked + sold constante em cada noite; a disponibilidade não muda.
        listOf(arrival, arrival.plusDays(1)).forEachIndexed { index, date ->
            val row = inventory.row(type, date)!!
            withClue(date) {
                row.blocked shouldBe before[index].blocked - 1
                row.sold shouldBe before[index].sold + 1
                row.blocked + row.sold shouldBe before[index].blocked + before[index].sold
                row.available shouldBe before[index].available
            }
        }
        support.allocationRow(blockId, type, arrival) shouldBe Triple(2, 1, 0)
        support.block(blockId).let {
            it["totalPickedUp"].asInt() shouldBe 2
            it["version"].asLong() shouldBe 1
        }

        // A tarifa nova do bloqueio vale só para o pick-up seguinte; a reserva já feita fica com a congelada.
        with(support.billing) {
            support
                .changeBlock(
                    blockId,
                    changeBody(block, support.allocation(type, arrival, arrival.plusDays(1), 2, "199.00")),
                ).expect(200)
        }
        val reservationId = UUID.fromString(reservation["id"].asString())
        support.reservation(reservationId)["reservation"]["total"].asString() shouldBe "500.00"
        val second = with(support.billing) { support.pickUp(blockId, pickUpStay(type, arrival)).expect(201) }
        second["reservations"][0]["total"].asString() shouldBe "398.00"

        // Esgotado: nada mais vira reserva (409) e o inventário não muda.
        val exhausted = inventory.row(type, arrival)!!
        with(support.billing) {
            support
                .pickUp(blockId, pickUpStay(type, arrival))
                .problem(409, "ROOM_BLOCK_UNAVAILABLE")["dates"]
                .values()
                .map { it.asString() } shouldContainExactly
                listOf(arrival.toString(), arrival.plusDays(1).toString())
            // Uma noite fora do bloqueio.
            support
                .pickUp(blockId, pickUpStay(type, arrival.plusDays(1), arrival.plusDays(3)))
                .problem(422, "ROOM_BLOCK_DATES_NOT_COVERED")["dates"]
                .values()
                .map { it.asString() } shouldContainExactly
                listOf(arrival.plusDays(2).toString())
        }
        inventory.row(type, arrival) shouldBe exhausted
        support.blockReservations(blockId) shouldHaveSize 2
    }

    @Test
    fun `N simultaneous pick-ups of the last unit convert exactly one, the others get ROOM_BLOCK_UNAVAILABLE`() {
        val (type, _) = inventory.roomTypeWithRooms(2, today)
        val plan = support.plan(listOf(type))
        val arrival = today.plusDays(4)
        val blockId = block(plan.id, type, arrival, arrival.plusDays(1), 1)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(CONCURRENT)
        try {
            val results =
                (1..CONCURRENT)
                    .map {
                        executor.submit(
                            Callable {
                                start.await()
                                support.pickUp(blockId, pickUpStay(type, arrival)).let {
                                    it.status to it.getContentAsString(Charsets.UTF_8)
                                }
                            },
                        )
                    }.also { start.countDown() }
                    .map { it.get(TIMEOUT, TimeUnit.SECONDS) }
            withClue(results) {
                results.count { it.first == 201 } shouldBe 1
                results.filter { it.first != 201 }.forEach {
                    it.first shouldBe 409
                    it.second.contains("ROOM_BLOCK_UNAVAILABLE") shouldBe true
                }
            }
        } finally {
            executor.shutdownNow()
        }
        support.blockReservations(blockId) shouldHaveSize 1
        support.allocationRow(blockId, type, arrival) shouldBe Triple(1, 1, 0)
        inventory.row(type, arrival)!!.let {
            it.sold shouldBe 1
            it.blocked shouldBe 0
        }
    }

    @Test
    fun `the rooming list creates the reservations in batch and repeating it with the same key duplicates nothing`() {
        val (type, _) = inventory.roomTypeWithRooms(3, today)
        val plan = support.plan(listOf(type))
        val arrival = today.plusDays(6)
        val blockId = block(plan.id, type, arrival, arrival.plusDays(1), 2)
        val guests = (1..3).map { support.guest() }
        val list =
            listOf(
                support.blockReservation(type, arrival, arrival.plusDays(2), guests = listOf(guests[0] to true)),
                support.blockReservation(
                    type,
                    arrival,
                    arrival.plusDays(2),
                    guests = listOf(guests[1] to true, guests[2] to false),
                ),
            )
        val key = UuidV7.generate().toString()

        val first = support.roomingList(blockId, list, holder = guests[0], idempotencyKey = key)
        val second = support.roomingList(blockId, list, holder = guests[0], idempotencyKey = key)

        first.status shouldBe 201
        second.status shouldBe 201
        second.getHeader("Idempotent-Replayed") shouldBe "true"
        second.getContentAsString(Charsets.UTF_8) shouldBe first.getContentAsString(Charsets.UTF_8)
        val booking = iam.json(first)
        booking["reservations"].values().map { r -> r["guests"].values().map { it["guestId"].asString() } } shouldBe
            listOf(listOf(guests[0].toString()), listOf(guests[1].toString(), guests[2].toString()))
        support.blockReservations(blockId) shouldHaveSize 2
        support.allocationRow(blockId, type, arrival) shouldBe Triple(2, 2, 0)
        inventory.row(type, arrival)!!.let {
            it.sold shouldBe 2
            it.blocked shouldBe 0
        }
        // Outra chave é outro pedido: o bloqueio esgotado recusa e nada é criado.
        with(support.billing) {
            support.roomingList(blockId, list, holder = guests[0]).problem(409, "ROOM_BLOCK_UNAVAILABLE")
        }
        support.blockReservations(blockId) shouldHaveSize 2
    }

    @Test
    fun `a company block needs the holder of the booking, pays the master folio and refuses a wrong stay`() {
        val (type, _) = inventory.roomTypeWithRooms(2, today)
        val plan = support.plan(listOf(type))
        val company = support.guests.createCompany()
        val arrival = today.plusDays(5)
        val block =
            support.createdBlock(
                support.blockBody(
                    plan.id,
                    today.plusDays(2),
                    support.allocation(type, arrival, arrival, 1),
                    holderCompany = company,
                ),
            )
        val blockId = UUID.fromString(block["id"].asString())
        billingApi
            .foliosOf(FolioOwner(FolioOwnerType.GROUP, blockId))
            .single()
            .payerCompanyId
            ?.value shouldBe company

        with(support.billing) {
            support
                .pickUp(blockId, pickUpStay(type, arrival))
                .problem(400, "BOOKING_INVALID")["errors"][0]["field"]
                .asString() shouldBe "holderGuestId"
            val holder = support.guest()
            support.pickUp(blockId, pickUpStay(type, arrival, arrival), holder).problem(400, "STAY_INVALID")
            support.pickUp(blockId, pickUpStay(type, arrival, adults = 9), holder).problem(
                422,
                "OCCUPANCY_EXCEEDS_ROOM_TYPE",
            )
            support.pickUp(blockId, pickUpStay(type, arrival, arrival.plusDays(1)), holder).expect(201)
        }
    }

    // --- Ciclo de vida das reservas do pick-up ---------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // cancelamento, reativação e o cancelamento depois da liberação
    fun `a cancelled pick-up gives the unit back to the active block, and after the release to the inventory`() {
        val (type, _) = inventory.roomTypeWithRooms(3, today)
        val plan = support.plan(listOf(type), cancellationBody = support.rates.cancellationBody(deadlineHours = 0))
        val arrival = today.plusDays(5)
        val blockId = block(plan.id, type, arrival, arrival.plusDays(1), 2, cutoff = today)
        val reservationId = pickedUp(blockId, type, arrival)
        val picked = inventory.row(type, arrival)!!

        with(support.billing) { support.cancel(reservationId).expect(200) }

        inventory.row(type, arrival)!!.let {
            it.sold shouldBe picked.sold - 1
            it.blocked shouldBe picked.blocked + 1
            it.available shouldBe picked.available
        }
        support.allocationRow(blockId, type, arrival) shouldBe Triple(2, 0, 0)

        // A reativação toma de novo do bloqueio ativo.
        with(support.billing) { support.reactivate(reservationId).expect(200) }
        inventory.row(type, arrival) shouldBe picked
        support.allocationRow(blockId, type, arrival) shouldBe Triple(2, 1, 0)

        // Liberado o bloqueio, a unidade cancelada vai ao inventário geral.
        reservationsApi.releaseUnused(RoomBlockId(blockId)).status shouldBe RoomBlockStatus.RELEASED
        val released = inventory.row(type, arrival)!!
        released.blocked shouldBe picked.blocked - 1
        with(support.billing) { support.cancel(reservationId).expect(200) }
        inventory.row(type, arrival)!!.let {
            it.sold shouldBe released.sold - 1
            it.blocked shouldBe released.blocked
            it.available shouldBe released.available + 1
        }
        // A unidade cancelada depois da liberação conta como devolvida ao inventário.
        support.allocationRow(blockId, type, arrival) shouldBe Triple(2, 0, 2)
    }

    @Test
    fun `a pick-up reservation keeps its stay - only the occupancy changes, without repricing`() {
        val (type, _) = inventory.roomTypeWithRooms(2, today)
        val plan = support.plan(listOf(type))
        val arrival = today.plusDays(5)
        val blockId = block(plan.id, type, arrival, arrival.plusDays(2), 1)
        val reservationId = pickedUp(blockId, type, arrival)
        val current = support.reservation(reservationId)["reservation"]

        with(support.billing) {
            support
                .change(reservationId, support.changeBody(current, departure = arrival.plusDays(3)))
                .problem(409, "ROOM_BLOCK_RESERVATION_STAY_FIXED")
            val changed = support.change(reservationId, support.changeBody(current, adults = 1)).expect(200)
            changed["reservation"]["adults"].asInt() shouldBe 1
            changed["reservation"]["total"].asString() shouldBe current["total"].asString()
            changed["reservation"]["nightlyRates"] shouldBe current["nightlyRates"]
        }
    }

    // --- Alteração do bloqueio ------------------------------------------------------------------------------------

    @Test
    fun `the change replaces the future units through the inventory, never below the pick-up, with If-Match`() {
        val (type, _) = inventory.roomTypeWithRooms(4, today)
        val plan = support.plan(listOf(type))
        val arrival = today.plusDays(5)
        val block =
            support.createdBlock(
                support.blockBody(
                    plan.id,
                    today.plusDays(3),
                    support.allocation(type, arrival, arrival.plusDays(1), 2),
                    holderGuest = support.guest(),
                ),
            )
        val blockId = UUID.fromString(block["id"].asString())
        pickedUp(blockId, type, arrival, arrival.plusDays(1))

        with(support.billing) {
            support
                .changeBlock(
                    blockId,
                    changeBody(block, support.allocation(type, arrival.plusDays(1), arrival.plusDays(1), 2)),
                ).problem(422, "ROOM_BLOCK_BELOW_PICK_UP")["dates"]
                .values()
                .map { it.asString() } shouldContainExactly listOf(arrival.toString())
            support
                .changeBlock(blockId, changeBody(block, support.allocation(type, today.minusDays(1), arrival, 1)))
                .problem(422, "ROOM_BLOCK_PERIOD_IN_PAST")
            support
                .changeBlock(blockId, changeBody(block, support.allocation(type, arrival, arrival, 9)))
                .problem(409, "ROOM_TYPE_UNAVAILABLE")
            support.changeBlock(blockId, changeBody(block), ifMatch = null).problem(428, "PRECONDITION_REQUIRED")
            support.changeBlock(blockId, changeBody(block), ifMatch = "\"999\"").problem(412, "PRECONDITION_FAILED")
            // Três na primeira noite (uma já convertida) e a segunda fora: a primeira toma uma, a segunda devolve duas.
            val changed =
                support
                    .changeBlock(
                        blockId,
                        changeBody(block, support.allocation(type, arrival, arrival, 3), cutoff = today.plusDays(4)),
                    ).expect(200)
            changed["cutoffDate"].asString() shouldBe today.plusDays(4).toString()
        }
        support.allocationRow(blockId, type, arrival) shouldBe Triple(3, 1, 0)
        support.allocationRow(blockId, type, arrival.plusDays(1)) shouldBe null
        inventory.row(type, arrival)!!.let {
            it.blocked shouldBe 2
            it.sold shouldBe 1
        }
        inventory.row(type, arrival.plusDays(1))!!.blocked shouldBe 0
    }

    @Test
    fun `every room type of the block is in its rate plan, on creation and on change`() {
        val (type, _) = inventory.roomTypeWithRooms(2, today)
        val (other, _) = inventory.roomTypeWithRooms(2, today)
        val plan = support.plan(listOf(type))
        val arrival = today.plusDays(5)
        val body =
            support.blockBody(
                plan.id,
                today.plusDays(2),
                support.allocation(type, arrival, arrival, 1),
                support.allocation(other, arrival, arrival, 1),
                holderGuest = support.guest(),
            )

        with(support.billing) {
            support.createBlock(body).problem(422, "ROOM_TYPE_NOT_IN_PLAN").let {
                it["field"].asString() shouldBe "allocations[1].roomTypeId"
                it["roomTypeIds"].values().map { id -> id.asString() } shouldContainExactly listOf(other.toString())
            }
            support.blocksNamed(body["name"] as String) shouldBe 0
            inventory.row(type, arrival)!!.blocked shouldBe 0
            val block =
                support
                    .createBlock(
                        support.blockBody(
                            plan.id,
                            today.plusDays(2),
                            support.allocation(type, arrival, arrival, 1),
                            holderGuest = support.guest(),
                        ),
                    ).expect(201)
            val blockId = UUID.fromString(block["id"].asString())
            support
                .changeBlock(
                    blockId,
                    changeBody(
                        block,
                        support.allocation(type, arrival, arrival, 1),
                        support.allocation(other, arrival, arrival, 1),
                    ),
                ).problem(422, "ROOM_TYPE_NOT_IN_PLAN")["field"]
                .asString() shouldBe "allocations[1].roomTypeId"
            inventory.row(other, arrival)!!.blocked shouldBe 0
        }
    }

    @Test
    @Suppress("LongMethod") // a expiração com o bloqueio ativo e depois de liberado
    fun `an expired pick-up pre-reservation returns its unit to the active block, then to the inventory`(
        @Autowired expiryJob: ProvisionalExpiryJob,
    ) {
        val (type, _) = inventory.roomTypeWithRooms(3, today)
        val plan = support.plan(listOf(type), guarantee = "CARD")
        val arrival = today.plusDays(5)
        val blockId = block(plan.id, type, arrival, arrival, 2, cutoff = today)
        val before = inventory.row(type, arrival)!!

        val first = pickedUp(blockId, type, arrival)
        support.status(first) shouldBe "PROVISIONAL"
        expire(expiryJob, first)

        support.status(first) shouldBe "CANCELLED"
        cancellationReason(first) shouldBe "EXPIRED"
        inventory.row(type, arrival) shouldBe before
        support.allocationRow(blockId, type, arrival) shouldBe Triple(2, 0, 0)

        // Liberado o bloqueio, a pré-reserva que expira devolve a unidade ao inventário geral.
        val second = pickedUp(blockId, type, arrival)
        reservationsApi.releaseUnused(RoomBlockId(blockId))
        val released = inventory.row(type, arrival)!!
        support.allocationRow(blockId, type, arrival) shouldBe Triple(2, 1, 1)
        expire(expiryJob, second)

        support.status(second) shouldBe "CANCELLED"
        inventory.row(type, arrival)!!.let {
            it.sold shouldBe released.sold - 1
            it.blocked shouldBe released.blocked
            it.available shouldBe released.available + 1
        }
        support.allocationRow(blockId, type, arrival) shouldBe Triple(2, 0, 2)
    }

    @Test
    fun `a change reducing the block and a pick-up of its last free unit never leave fewer units than pick-ups`() {
        val (type, _) = inventory.roomTypeWithRooms(3, today)
        val plan = support.plan(listOf(type))
        repeat(ROUNDS) { round ->
            val arrival = today.plusDays(10L + round)
            val block =
                support.createdBlock(
                    support.blockBody(
                        plan.id,
                        today.plusDays(2),
                        support.allocation(type, arrival, arrival, 2),
                        holderGuest = support.guest(),
                    ),
                )
            val blockId = UUID.fromString(block["id"].asString())
            pickedUp(blockId, type, arrival)
            val etag = support.blockEtag(blockId)
            val reduce = changeBody(block, support.allocation(type, arrival, arrival, 1))
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val change =
                    executor.submit(
                        Callable {
                            start.await()
                            support.changeBlock(blockId, reduce, ifMatch = etag).let {
                                it.status to it.getContentAsString(Charsets.UTF_8)
                            }
                        },
                    )
                val pickUp =
                    executor.submit(
                        Callable {
                            start.await()
                            support.pickUp(blockId, pickUpStay(type, arrival, arrival.plusDays(1))).let {
                                it.status to it.getContentAsString(Charsets.UTF_8)
                            }
                        },
                    )
                start.countDown()
                val results = listOf(change.get(TIMEOUT, TimeUnit.SECONDS), pickUp.get(TIMEOUT, TimeUnit.SECONDS))
                withClue("rodada $round: $results") {
                    results.count { it.first in 200..299 } shouldBe 1
                    results.filter { it.first !in 200..299 }.forEach {
                        (it.first in setOf(409, 412, 422)) shouldBe true
                    }
                    val (quantity, picked, _) = support.allocationRow(blockId, type, arrival)!!
                    (picked <= quantity) shouldBe true
                    inventory.row(type, arrival)!!.let {
                        it.blocked shouldBe quantity - picked
                        it.sold shouldBe picked
                    }
                }
            } finally {
                executor.shutdownNow()
            }
        }
    }

    // --- Liberação ------------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // a liberação: antes do corte, depois dele, o pick-up recusado e a repetição
    fun `releaseUnused before the cutoff is a 409 and from it on gives back only the unused units`() {
        val (type, _) = inventory.roomTypeWithRooms(4, today)
        val plan = support.plan(listOf(type))
        val arrival = today.plusDays(7)
        val early = block(plan.id, type, arrival, arrival, 1, cutoff = today.plusDays(1))
        val blockId = block(plan.id, type, arrival, arrival.plusDays(1), 3, cutoff = today)
        pickedUp(blockId, type, arrival, arrival.plusDays(1))
        val before = listOf(inventory.row(type, arrival)!!, inventory.row(type, arrival.plusDays(1))!!)

        val refused =
            runCatching { reservationsApi.releaseUnused(RoomBlockId(early)) }.exceptionOrNull()
                as com.zeny.hotel.shared.DomainException
        refused.code shouldBe ReservationsErrorCodes.ROOM_BLOCK_CUTOFF_NOT_REACHED
        reservationsApi.blocksDueForRelease() shouldContain RoomBlockId(blockId)
        reservationsApi.blocksDueForRelease() shouldNotContain RoomBlockId(early)

        val (released, published) =
            inventory.publishedBy(type) { reservationsApi.releaseUnused(RoomBlockId(blockId)) }

        released.status shouldBe RoomBlockStatus.RELEASED
        released.releasedOn shouldBe today
        // Só o não utilizado volta: duas na primeira noite (uma virou reserva), três na segunda.
        inventory.row(type, arrival)!!.let {
            it.blocked shouldBe before[0].blocked - 2
            it.sold shouldBe before[0].sold
        }
        inventory.row(type, arrival.plusDays(1))!!.blocked shouldBe before[1].blocked - 3
        support.allocationRow(blockId, type, arrival) shouldBe Triple(3, 1, 2)
        support.allocationRow(blockId, type, arrival.plusDays(1)) shouldBe Triple(3, 0, 3)
        published shouldContainExactly listOf(arrival to arrival.plusDays(1))
        reservationsApi.blocksDueForRelease() shouldNotContain RoomBlockId(blockId)
        // Liberado, não aceita pick-up nem alteração; liberar de novo não muda nada.
        with(support.billing) {
            support.pickUp(blockId, pickUpStay(type, arrival)).problem(409, "ROOM_BLOCK_RELEASED")
        }
        val again = reservationsApi.releaseUnused(RoomBlockId(blockId))
        again.version shouldBe released.version
        support
            .auditEntries(
                blockId,
            ).map { it["action"] }
            .count { it == "reservations.room-block.release-unused" } shouldBe
            1
        // A data de corte já passada recusa o pick-up de um bloqueio ainda ativo.
        jdbc
            .sql("UPDATE reservations.room_block SET cutoff_date = :date WHERE id = :id")
            .param("date", today.minusDays(1))
            .param("id", early)
            .update()
        with(support.billing) {
            support
                .pickUp(
                    early,
                    pickUpStay(type, arrival, arrival.plusDays(1)),
                ).problem(422, "ROOM_BLOCK_CUTOFF_PASSED")
        }
        reservationsApi.releaseUnused(RoomBlockId(early)).status shouldBe RoomBlockStatus.RELEASED
    }

    // --- Recontagem, titular e permissões --------------------------------------------------------------------------

    @Test
    fun `the recount keeps the units of the active blocks as blocked and the pick-ups as sold`() {
        val (type, _) = inventory.roomTypeWithRooms(3, today)
        val plan = support.plan(listOf(type))
        val arrival = today.plusDays(RECOUNT_OFFSET)
        val blockId = block(plan.id, type, arrival, arrival.plusDays(1), 2, cutoff = today.plusDays(1))
        pickedUp(blockId, type, arrival)
        val before = inventory.row(type, arrival)!!

        reservationsApi.recountInventory(arrival, arrival.plusDays(2))

        inventory.row(type, arrival) shouldBe before
        before.blocked shouldBe 1
        before.sold shouldBe 1
        inventory.row(type, arrival.plusDays(1))!!.blocked shouldBe 2
    }

    @Test
    fun `GuestsMerged repoints the holder guest of the block`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type))
        val absorbed = support.guest()
        val survivor = support.guest()
        val blockId =
            UUID.fromString(
                support
                    .createdBlock(
                        support.blockBody(
                            plan.id,
                            today.plusDays(1),
                            support.allocation(type, today.plusDays(4), today.plusDays(4), 1),
                            holderGuest = absorbed,
                        ),
                    )["id"]
                    .asString(),
            )

        with(iam) {
            val path = "/api/v1/guests/$survivor"
            call(
                POST,
                "$path/merge",
                asAdmin(),
                mapOf("mergedGuestId" to absorbed, "takeFromMerged" to emptyList<String>()),
                ifMatch = support.guests.etagOf(path),
            ).ok()
        }

        await()
            .atMost(Duration.ofSeconds(TIMEOUT))
            .until { support.block(blockId)["holderGuestId"].asString() == survivor.toString() }
        support.block(blockId)["version"].asLong() shouldBe 1
        support.auditEntries(blockId).map { it["action"] } shouldContainExactly
            listOf("reservations.room-block.create", "reservations.guest-reference.repoint")
    }

    @Test
    fun `reading, managing and picking up a block need their own permissions`() {
        val (type, _) = inventory.roomTypeWithRooms(2, today)
        val plan = support.plan(listOf(type))
        val arrival = today.plusDays(5)
        val blockId = block(plan.id, type, arrival, arrival.plusDays(1), 1)
        val reader = iam.asTestUser(iam.createUserWith(TestGrant(definition(ReservationsPermissions.BLOCKS_READ), ALL)))
        val picker =
            iam.asTestUser(iam.createUserWith(TestGrant(definition(ReservationsPermissions.BLOCKS_PICK_UP), ALL)))
        val body =
            support.blockBody(
                plan.id,
                today,
                support.allocation(type, arrival, arrival, 1),
                holderGuest = support.guest(),
            )

        with(support.billing) {
            support.createBlock(body, reader).problem(403, "PERMISSION_DENIED")
            support.pickUp(blockId, pickUpStay(type, arrival), auth = reader).problem(403, "PERMISSION_DENIED")
            support.pickUp(blockId, pickUpStay(type, arrival), auth = picker).expect(201)
        }
        with(iam) {
            call(org.springframework.http.HttpMethod.GET, "/api/v1/room-blocks/$blockId", reader).ok()
            call(org.springframework.http.HttpMethod.GET, "/api/v1/room-blocks/$blockId", picker)
                .problem(403, "PERMISSION_DENIED")
            call(org.springframework.http.HttpMethod.GET, "/api/v1/room-blocks/${UuidV7.generate()}", asAdmin())
                .problem(404, "ROOM_BLOCK_NOT_FOUND")
            call(org.springframework.http.HttpMethod.GET, "/api/v1/room-blocks?status=ACTIVE&limit=200", reader)
                .ok()["items"]
                .values()
                .map { it["id"].asString() } shouldContain blockId.toString()
        }
        listOf(
            ReservationsPermissions.BLOCKS_READ,
            ReservationsPermissions.BLOCKS_MANAGE,
            ReservationsPermissions.BLOCKS_PICK_UP,
        ).map { definition(it).sensitive }.distinct() shouldContainExactly listOf(false)
    }

    // --- Apoio ------------------------------------------------------------------------------------------------------

    /** Um bloqueio do tipo de [from] a [to] (inclusive) com [quantity] unidades por noite; devolve o id. */
    @Suppress("LongParameterList") // o bloqueio, com padrões
    private fun block(
        plan: UUID,
        type: UUID,
        from: LocalDate,
        to: LocalDate,
        quantity: Int,
        cutoff: LocalDate = today.plusDays(2),
        rate: String = "250.00",
    ): UUID =
        UUID.fromString(
            support
                .createdBlock(
                    support.blockBody(
                        plan,
                        cutoff,
                        support.allocation(type, from, to, quantity, rate),
                        holderGuest = support.guest(),
                    ),
                )["id"]
                .asString(),
        )

    /** O pick-up de uma reserva de [arrival] a [departure] (padrão: uma noite); devolve o id da reserva. */
    private fun pickedUp(
        block: UUID,
        type: UUID,
        arrival: LocalDate,
        departure: LocalDate = arrival.plusDays(1),
    ): UUID =
        with(support.billing) {
            UUID.fromString(
                support
                    .pickUp(
                        block,
                        pickUpStay(type, arrival, departure),
                    ).expect(201)["reservations"][0]["id"]
                    .asString(),
            )
        }

    private fun pickUpStay(
        type: UUID,
        arrival: LocalDate,
        departure: LocalDate = arrival.plusDays(2),
        adults: Int = 2,
    ) = support.blockReservation(type, arrival, departure, adults)

    /** O corpo da alteração a partir do bloqueio [block] (a resposta da API), com as unidades pedidas. */
    private fun changeBody(
        block: JsonNode,
        vararg allocations: Map<String, Any?>,
        cutoff: LocalDate = LocalDate.parse(block["cutoffDate"].asString()),
    ): Map<String, Any?> =
        mapOf(
            "name" to block["name"].asString(),
            "cutoffDate" to cutoff,
            "allocations" to allocations.toList(),
        )

    private fun groupFolioCount(): Int =
        jdbc
            .sql("SELECT count(*) FROM billing.folio WHERE owner_type = 'GROUP'")
            .query(Int::class.java)
            .single()

    /** Vence a pré-reserva agora (a validade no passado) e roda o job de expiração. */
    private fun expire(
        job: ProvisionalExpiryJob,
        reservation: UUID,
    ) {
        jdbc
            .sql(
                "UPDATE reservations.reservation SET provisional_until = now() - interval '1 minute' " +
                    "WHERE id = :id AND status = 'PROVISIONAL'",
            ).param("id", reservation)
            .update() shouldBe 1
        job.expireProvisionalReservations()
    }

    private fun cancellationReason(reservation: UUID): String? =
        jdbc
            .sql("SELECT cancellation_reason FROM reservations.reservation WHERE id = :id")
            .param("id", reservation)
            .query(String::class.java)
            .single()

    private companion object {
        const val TIMEOUT = 30L
        const val CONCURRENT = 6
        const val RECOUNT_OFFSET = 690L
        const val ROUNDS = 5

        fun definition(code: PermissionCode) = ReservationsPermissions.permissions.single { it.code == code }
    }
}
