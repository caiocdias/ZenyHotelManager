package com.zeny.hotel.reservations

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.billing.FolioOwner
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.rates.RatesPermissions
import com.zeny.hotel.rates.RatesTestSupport
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
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
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * A criação de reservas (M4 E07; RF-RES-01 a 03 e 14, RN-14, RN-15, RF-CTA-01, H5, H7): a reserva simples e a
 * agrupadora tomam o inventário, abrem a conta de cada reserva e publicam `ReservationCreated`, tudo na mesma
 * transação; o preço por noite e os termos das políticas ficam congelados; o desconto acima do limite pede aprovação;
 * sem unidade, nada é gravado; a repetição com a mesma `Idempotency-Key` não cria nada; o day use não toma inventário.
 * Cada teste cria os próprios tipos de UH (banco compartilhado). O mesmo contexto do `InventoryApiTest`.
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList", "LargeClass") // as portas e os cenários da criação
class BookingsApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val billingApi: BillingApi,
    @Autowired mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    // --- Reserva simples e agrupadora ------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // a reserva simples inteira: inventário, conta, preço, políticas, trilha e evento
    fun `a simple reservation takes the inventory, opens its folio and is confirmed without a required guarantee`() {
        val (type, _) = inventory.roomTypeWithRooms(2, today)
        val plan = support.plan(listOf(type))
        val arrival = today.plusDays(2)
        val departure = today.plusDays(4)
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val holder = support.guest()

        val booking =
            support.created(
                support.booking(
                    holder,
                    support.reservation(type, plan.id, arrival, departure, specialRequests = "Berço no quarto"),
                ),
            )

        booking["code"].asString() shouldMatchCode "B"
        booking["holderGuestId"].asString() shouldBe holder.toString()
        booking["origin"].asString() shouldBe "PHONE"
        booking["channel"].asString() shouldBe "DIRECT"
        val reservation = booking["reservations"].values().single()
        reservation["code"].asString() shouldMatchCode "R"
        reservation["status"].asString() shouldBe "CONFIRMED"
        reservation["provisionalUntil"].isNull shouldBe true
        reservation["dayUse"].asBoolean() shouldBe false
        reservation["guests"].values().map {
            it["guestId"].asString() to it["primary"].asBoolean()
        } shouldContainExactly
            listOf(holder.toString() to true)
        reservation["nightlyRates"].values().map {
            it["date"].asString() to it["amount"].asString()
        } shouldContainExactly
            listOf(arrival.toString() to "400.00", arrival.plusDays(1).toString() to "400.00")
        reservation["total"].asString() shouldBe "800.00"
        reservation["specialRequests"].asString() shouldBe "Berço no quarto"
        reservation["cancellationTerms"]["policyId"].asString() shouldBe plan.cancellationPolicy.toString()
        reservation["cancellationTerms"]["penaltyType"].asString() shouldBe "NIGHTS"
        reservation["guaranteeTerms"]["type"].asString() shouldBe "NONE"

        // Uma unidade em cada noite (não na saída).
        inventory.row(type, arrival)!!.sold shouldBe 1
        inventory.row(type, arrival.plusDays(1))!!.sold shouldBe 1
        inventory.row(type, departure)!!.sold shouldBe 0

        // A conta da reserva, aberta no billing com o dono RESERVATION.
        val reservationId = UUID.fromString(reservation["id"].asString())
        val folios = billingApi.foliosOf(FolioOwner(FolioOwnerType.RESERVATION, reservationId))
        folios.map { it.id.value.toString() } shouldContainExactly listOf(reservation["folioId"].asString())

        // A trilha, sem pedidos especiais nem dado pessoal; a consulta.
        val trail = support.auditEntries(reservationId)
        trail.map { it["action"] } shouldContainExactly listOf("reservations.booking.create")
        trail.joinToString { "${it["after"]}" }.let {
            it shouldNotContain "Berço"
            it shouldNotContain "Conceição"
        }
        val detail = support.reservation(reservationId)
        detail["reservation"]["total"].asString() shouldBe "800.00"
        detail["bookingCode"].asString() shouldBe booking["code"].asString()
        detail["restrictionAlerts"].values().shouldBeEmpty()
        with(iam) {
            call(
                GET,
                "/api/v1/bookings/${booking["id"].asString()}",
                support.admin,
            ).ok()["reservations"].size() shouldBe
                1
        }
    }

    @Test
    fun `a booking with two rooms creates one reservation and one folio per room, and ReservationCreated for each`(
        scenario: Scenario,
    ) {
        val (type, _) = inventory.roomTypeWithRooms(2, today)
        val (other, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type, other))
        support.setPrices(plan.id, listOf(type, other), today, today.plusDays(10))
        val holder = support.guest()
        val arrival = today.plusDays(3)
        val departure = today.plusDays(5)
        val body =
            support.booking(
                holder,
                support.reservation(type, plan.id, arrival, departure),
                support.reservation(other, plan.id, arrival, departure, adults = 1),
            )

        var booking: JsonNode? = null
        scenario
            .stimulate(Supplier { support.created(body).also { booking = it } })
            .andWaitForEventOfType(ReservationCreated::class.java)
            .matching { it.roomTypeId == other }
            .toArriveAndVerify { event ->
                event.status shouldBe ReservationStatus.CONFIRMED
                event.arrival shouldBe arrival
                event.adults shouldBe 1
                event.channel.name shouldBe "DIRECT"
            }

        val created = booking!!
        val reservations = created["reservations"].values()
        reservations shouldHaveSize 2
        reservations.map { it["folioId"].asString() }.distinct() shouldHaveSize 2
        reservations.map { it["bookingId"].asString() }.distinct() shouldContainExactly listOf(created["id"].asString())
        inventory.row(type, arrival)!!.sold shouldBe 1
        inventory.row(other, arrival)!!.sold shouldBe 1
        val bookingId = UUID.fromString(created["id"].asString())
        await().atMost(Duration.ofSeconds(TIMEOUT)).until { ReservationCreatedEvents.of(bookingId).size == 2 }
        val publications = support.createdPublications(bookingId)
        publications shouldHaveSize 2
        publications.forEach { serialized ->
            serialized shouldNotContain holder.toString()
            jsonMapper.readTree(serialized).propertyNames().toSet() shouldBe EVENT_FIELDS
        }
    }

    // --- Preço e políticas congelados -----------------------------------------------------------------------------

    @Test
    fun `changing the rate and the policy afterwards changes neither the nightly rates nor the copied terms`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val arrival = today.plusDays(2)
        val reservation =
            support
                .created(
                    support.booking(support.guest(), support.reservation(type, plan.id, arrival, arrival.plusDays(2))),
                )["reservations"][0]
        val id = UUID.fromString(reservation["id"].asString())

        support.setPrices(plan.id, listOf(type), today, today.plusDays(10), oneAdult = "450.00", twoAdults = "550.00")
        with(iam) {
            val path = "/api/v1/cancellation-policies/${plan.cancellationPolicy}"
            call(
                PUT,
                path,
                support.admin,
                support.rates.cancellationBody(deadlineHours = 12, penaltyNights = 2),
                ifMatch = support.rates.etagOf(path),
            ).ok()
        }

        val after = support.reservation(id)["reservation"]
        after["nightlyRates"].values().map { it["roomAmount"].asString() } shouldContainExactly
            listOf("400.00", "400.00")
        after["nightlyRates"].values().map { it["calculatedRoomAmount"].asString() } shouldContainExactly
            listOf("400.00", "400.00")
        after["total"].asString() shouldBe "800.00"
        after["cancellationTerms"]["deadlineHours"].asInt() shouldBe 48
        after["cancellationTerms"]["penaltyNights"].asInt() shouldBe 1
        after["cancellationTerms"]["version"].asLong() shouldBe reservation["cancellationTerms"]["version"].asLong()
    }

    @Test
    fun `a reservation under a deposit guarantee is a provisional one, valid for the hotel pre-reservation expiry`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type), guarantee = "DEPOSIT")
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val expiry = hotelApi.operationalSettings().preReservationExpiry

        val reservation =
            support
                .created(
                    support.booking(
                        support.guest(),
                        support.reservation(type, plan.id, today.plusDays(5), today.plusDays(6)),
                    ),
                )["reservations"][0]

        reservation["status"].asString() shouldBe "PROVISIONAL"
        Instant.parse(reservation["provisionalUntil"].asString()) shouldBe
            Instant.parse(reservation["createdAt"].asString()).plus(expiry)
        reservation["guaranteeTerms"]["type"].asString() shouldBe "DEPOSIT"
        reservation["guaranteeTerms"]["depositPercent"].asString() shouldBe "30.0000"
    }

    // --- Desconto -----------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // o desconto dentro do teto, acima dele, sem a permissão e com a aprovação
    fun `a discount above the limit asks for approval, and the approved one is audited with requester and approver`() {
        val (type, _) = inventory.roomTypeWithRooms(3, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val arrival = today.plusDays(2)
        val manage = TestGrant(definition(ReservationsPermissions.RESERVATIONS_MANAGE), ALL)
        val discount =
            TestGrant(
                RatesTestSupport.definition(RatesPermissions.DISCOUNTS_APPLY),
                ALL,
                mapOf(RatesPermissions.MAX_PERCENT to LimitValue.Max(BigDecimal("10"))),
            )
        val receptionist = ReservationsTestSupport.Operator(iam.createUserWith(manage, discount))
        val withoutDiscount = ReservationsTestSupport.Operator(iam.createUserWith(manage))
        val approverUser =
            iam.createUserWith(
                TestGrant(
                    RatesTestSupport.definition(RatesPermissions.DISCOUNTS_APPLY),
                    ALL,
                    mapOf(RatesPermissions.MAX_PERCENT to LimitValue.Unlimited),
                ),
            )
        iam.setPin(approverUser, APPROVER_PIN)
        val holder = support.guest()

        fun body(amount: String) =
            support.booking(
                holder,
                support.reservation(
                    type,
                    plan.id,
                    arrival,
                    arrival.plusDays(2),
                    nightlyRates = listOf(arrival to amount),
                ),
            )

        // 5% de desconto: dentro do teto de 10%.
        val within = with(support.billing) { support.create(body("380.00"), receptionist.auth).expect(201) }
        within["reservations"][0]["nightlyRates"][0].let {
            it["overridden"].asBoolean() shouldBe true
            it["discountPercent"].asString() shouldBe "5.0000"
            it["calculatedRoomAmount"].asString() shouldBe "400.00"
        }
        within["reservations"][0]["total"].asString() shouldBe "780.00"
        // Acima da calculada: não é desconto.
        val above = with(support.billing) { support.create(body("450.00"), withoutDiscount.auth).expect(201) }
        above["reservations"][0]["nightlyRates"][0]["discountPercent"].isNull shouldBe true

        // 25%: acima do teto; sem a permissão do desconto, qualquer desconto pede aprovação.
        val soldBefore = inventory.row(type, arrival)!!.sold
        val required =
            with(
                support.billing,
            ) { support.create(body("300.00"), receptionist.auth).problem(403, "APPROVAL_REQUIRED") }
        required["action"].asString() shouldBe "reservations.booking.create"
        required["permission"].asString() shouldBe "rates.discounts.apply"
        required["resourceRef"].asString() shouldBe "reservations.booking:new:$holder"
        required["limit"]["key"].asString() shouldBe "maxPercent"
        BigDecimal(required["limit"]["amount"].asString()).compareTo(BigDecimal("25")) shouldBe 0
        with(support.billing) { support.create(body("399.99"), withoutDiscount.auth).problem(403, "APPROVAL_REQUIRED") }
        inventory.row(type, arrival)!!.sold shouldBe soldBefore

        // Com a aprovação de um superior (outra Idempotency-Key): criada, com o aprovador na reserva e na trilha.
        val approval =
            with(iam) {
                call(
                    POST,
                    "/api/v1/auth/approvals",
                    receptionist.auth,
                    mapOf(
                        "action" to required["action"].asString(),
                        "permission" to required["permission"].asString(),
                        "resourceRef" to required["resourceRef"].asString(),
                        "limit" to mapOf("key" to "maxPercent", "amount" to required["limit"]["amount"].asString()),
                        "approverLogin" to approverUser.login,
                        "approverPin" to APPROVER_PIN,
                        "reason" to "Cliente frequente",
                    ),
                ).expectStatus(201)!!
            }
        val approved =
            with(support.billing) {
                support
                    .create(body("300.00"), receptionist.auth, approvalToken = approval["approvalToken"].asString())
                    .expect(201)
            }
        val reservation = approved["reservations"][0]
        reservation["approvalId"].asString() shouldBe approval["approvalId"].asString()
        reservation["approvedBy"].asString() shouldBe approverUser.id.toString()
        reservation["nightlyRates"][0]["discountPercent"].asString() shouldBe "25.0000"
        val trail = support.auditEntries(UUID.fromString(reservation["id"].asString()))
        trail.single()["userId"] shouldBe receptionist.user.id.value
        trail.single()["approvalId"].toString() shouldBe approval["approvalId"].asString()
    }

    @Test
    fun `a nightly rate below the included packages, or out of the stay, is refused`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val pkg = support.rates.createPackage()
        val plan = support.plan(listOf(type), packages = listOf(pkg))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val arrival = today.plusDays(2)
        val holder = support.guest()

        fun body(vararg rates: Pair<LocalDate, String>) =
            support.booking(
                holder,
                support.reservation(type, plan.id, arrival, arrival.plusDays(1), nightlyRates = rates.toList()),
            )

        with(support.billing) {
            // O café incluso é 35,00 por pessoa: 70,00 na noite de dois adultos.
            support
                .create(body(arrival to "69.99"))
                .problem(422, "NIGHTLY_RATE_BELOW_INCLUDED_PACKAGES")["dates"]
                .values()
                .map { it.asString() } shouldContainExactly listOf(arrival.toString())
            support
                .create(
                    body(arrival.plusDays(1) to "100.00"),
                ).problem(400, "NIGHTLY_RATE_INVALID")["errors"][0]["field"]
                .asString() shouldBe "reservations[0].nightlyRates[0].date"
            support.create(body(arrival to "100.00", arrival to "120.00")).problem(400, "NIGHTLY_RATE_INVALID")
        }
        support.bookingCount(type) shouldBe 0
    }

    // --- Disponibilidade -----------------------------------------------------------------------------------------

    @Test
    fun `without a unit the booking is refused with 409 and nothing is written, not even the folios`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val (other, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type, other))
        support.setPrices(plan.id, listOf(type, other), today, today.plusDays(10))
        val arrival = today.plusDays(2)
        val holder = support.guest()
        support.created(support.booking(holder, support.reservation(type, plan.id, arrival, arrival.plusDays(2))))
        val folios = support.reservationFolioCount()

        val problem =
            with(support.billing) {
                support
                    .create(
                        support.booking(
                            holder,
                            support.reservation(other, plan.id, arrival, arrival.plusDays(2)),
                            support.reservation(type, plan.id, arrival.plusDays(1), arrival.plusDays(3)),
                        ),
                    ).problem(409, "ROOM_TYPE_UNAVAILABLE")
            }

        problem["roomTypeIds"].values().map { it.asString() } shouldContainExactly listOf(type.toString())
        problem["dates"].values().map { it.asString() } shouldContainExactly listOf(arrival.plusDays(1).toString())
        support.bookingCount(other) shouldBe 0
        support.bookingCount(type) shouldBe 1
        inventory.row(other, arrival)!!.sold shouldBe 0
        inventory.row(type, arrival.plusDays(2))!!.sold shouldBe 0
        support.reservationFolioCount() shouldBe folios
    }

    @Test
    fun `N simultaneous bookings for the last room create exactly one, the others get ROOM_TYPE_UNAVAILABLE`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val arrival = today.plusDays(4)
        val holder = support.guest()
        val body = support.booking(holder, support.reservation(type, plan.id, arrival, arrival.plusDays(2)))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(CONCURRENT)
        try {
            val results =
                (1..CONCURRENT)
                    .map {
                        executor.submit(
                            Callable {
                                start.await()
                                support.create(body).let { it.status to it.getContentAsString(Charsets.UTF_8) }
                            },
                        )
                    }.also { start.countDown() }
                    .map { it.get(TIMEOUT, TimeUnit.SECONDS) }
            withClue(results) {
                results.count { it.first == 201 } shouldBe 1
                results.filter { it.first != 201 }.forEach {
                    it.first shouldBe 409
                    it.second.contains("ROOM_TYPE_UNAVAILABLE") shouldBe true
                }
            }
        } finally {
            executor.shutdownNow()
        }
        support.bookingCount(type) shouldBe 1
        inventory.row(type, arrival)!!.sold shouldBe 1
    }

    // --- Idempotência -------------------------------------------------------------------------------------------

    @Test
    fun `repeating with the same Idempotency-Key creates no second reservation nor second folio`() {
        val (type, _) = inventory.roomTypeWithRooms(2, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val body =
            support.booking(support.guest(), support.reservation(type, plan.id, today.plusDays(2), today.plusDays(3)))
        val key = UuidV7.generate().toString()

        val first = support.create(body, idempotencyKey = key)
        val second = support.create(body, idempotencyKey = key)

        first.status shouldBe 201
        second.status shouldBe 201
        second.getHeader("Idempotent-Replayed") shouldBe "true"
        second.getContentAsString(Charsets.UTF_8) shouldBe first.getContentAsString(Charsets.UTF_8)
        support.bookingCount(type) shouldBe 1
        inventory.row(type, today.plusDays(2))!!.sold shouldBe 1
        val reservation = iam.json(first)["reservations"][0]
        billingApi.foliosOf(
            FolioOwner(FolioOwnerType.RESERVATION, UUID.fromString(reservation["id"].asString())),
        ) shouldHaveSize
            1
    }

    // --- Day use ------------------------------------------------------------------------------------------------

    @Test
    fun `a day use takes no inventory, is priced by the rate of its date and opens its folio`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type))
        val date = today.plusDays(3)
        support.setPrices(plan.id, listOf(type), date, date, oneAdult = "150.00", twoAdults = "180.00")

        val booking = support.created(support.booking(support.guest(), support.reservation(type, plan.id, date, date)))
        // A noite da data continua à venda: outra reserva de pernoite a toma.
        support.setPrices(plan.id, listOf(type), date, date.plusDays(1))
        support.created(support.booking(support.guest(), support.reservation(type, plan.id, date, date.plusDays(1))))

        val reservation = booking["reservations"][0]
        reservation["dayUse"].asBoolean() shouldBe true
        reservation["nightlyRates"].values().map {
            it["date"].asString() to it["amount"].asString()
        } shouldContainExactly
            listOf(date.toString() to "180.00")
        reservation["total"].asString() shouldBe "180.00"
        reservation["folioId"].isNull shouldBe false
        inventory.row(type, date)!!.sold shouldBe 1
        val bookingId = UUID.fromString(booking["id"].asString())
        await().atMost(Duration.ofSeconds(TIMEOUT)).until { ReservationCreatedEvents.of(bookingId).isNotEmpty() }
        ReservationCreatedEvents.of(bookingId).single().dayUse shouldBe true
        // A cotação continua só de pernoite.
        with(iam) {
            call(
                GET,
                "/api/v1/quotes?arrival=$date&departure=$date&adults=2&channel=DIRECT&roomTypeIds=$type",
                support.admin,
            ).problem(400, "STAY_INVALID")
        }
    }

    // --- Recusas ----------------------------------------------------------------------------------------------

    @Test
    fun `the stay is refused when it is not sellable, with the code of the rates and every reason`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val (outside, _) = inventory.roomTypeWithRooms(1, today)
        val websiteOnly = support.plan(listOf(type), channels = listOf("WEBSITE"))
        support.setPrices(websiteOnly.id, listOf(type), today, today.plusDays(10))
        val holder = support.guest()
        val arrival = today.plusDays(2)

        with(support.billing) {
            val channel =
                support
                    .create(
                        support.booking(
                            holder,
                            support.reservation(type, websiteOnly.id, arrival, arrival.plusDays(1)),
                        ),
                    ).problem(422, "SALES_CHANNEL_NOT_ALLOWED")
            channel["reservationIndex"].asInt() shouldBe 0
            channel["refusals"].values().map { it["code"].asString() } shouldContainExactly
                listOf("SALES_CHANNEL_NOT_ALLOWED")
            // Pelo site, o mesmo plano vende.
            support
                .create(
                    support.booking(
                        holder,
                        support.reservation(type, websiteOnly.id, arrival, arrival.plusDays(1)),
                        origin = "WEBSITE",
                    ),
                ).expect(201)["channel"]
                .asString() shouldBe "WEBSITE"
            support
                .create(
                    support.booking(
                        holder,
                        support.reservation(outside, websiteOnly.id, arrival, arrival.plusDays(1)),
                        origin = "WEBSITE",
                    ),
                ).problem(422, "ROOM_TYPE_NOT_IN_PLAN")
            support
                .create(
                    support.booking(holder, support.reservation(type, UuidV7.generate(), arrival, arrival.plusDays(1))),
                ).problem(400, "UNKNOWN_RATE_PLAN")["errors"][0]["field"]
                .asString() shouldBe "reservations[0].ratePlanId"
            support
                .create(
                    support.booking(holder, support.reservation(type, websiteOnly.id, arrival, arrival.minusDays(1))),
                ).problem(400, "STAY_INVALID")["errors"][0]["field"]
                .asString() shouldBe "reservations[0].departure"
            support
                .create(support.booking(holder))
                .problem(400, "BOOKING_INVALID")["errors"][0]["field"]
                .asString() shouldBe "reservations"
        }
    }

    @Test
    fun `creating needs reservations manage and reading needs reservations read`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val reader = iam.createUserWith(TestGrant(definition(ReservationsPermissions.RESERVATIONS_READ), ALL))
        val nobody = iam.createUser()
        val body =
            support.booking(
                support.guest(),
                support.reservation(type, plan.id, today.plusDays(2), today.plusDays(3)),
            )

        with(support.billing) {
            support.create(body, iam.asTestUser(reader)).problem(403, "PERMISSION_DENIED")
            val booking = support.create(body).expect(201)
            with(iam) {
                call(GET, "/api/v1/bookings/${booking["id"].asString()}", asTestUser(reader)).ok()
                call(
                    GET,
                    "/api/v1/bookings/${booking["id"].asString()}",
                    asTestUser(nobody),
                ).problem(403, "PERMISSION_DENIED")
                call(GET, "/api/v1/bookings/${UuidV7.generate()}", asAdmin()).problem(404, "BOOKING_NOT_FOUND")
                call(GET, "/api/v1/reservations/${UuidV7.generate()}", asAdmin()).problem(404, "RESERVATION_NOT_FOUND")
            }
        }
        definition(ReservationsPermissions.RESERVATIONS_MANAGE).sensitive shouldBe false
        definition(ReservationsPermissions.RESERVATIONS_READ).sensitive shouldNotBe true
    }

    // --- Recontagem -----------------------------------------------------------------------------------------------

    @Test
    fun `the recount counts the units sold to reservations, and not the day use`(
        @Autowired reservationsApi: ReservationsApi,
    ) {
        val (type, _) = inventory.roomTypeWithRooms(2, today)
        val plan = support.plan(listOf(type))
        val arrival = today.plusDays(RECOUNT_OFFSET)
        support.setPrices(plan.id, listOf(type), arrival, arrival.plusDays(2))
        support.created(
            support.booking(support.guest(), support.reservation(type, plan.id, arrival, arrival.plusDays(2))),
        )
        support.created(support.booking(support.guest(), support.reservation(type, plan.id, arrival, arrival)))

        reservationsApi.recountInventory(arrival, arrival.plusDays(2))

        inventory.row(type, arrival)!!.sold shouldBe 1
        inventory.row(type, arrival.plusDays(1))!!.sold shouldBe 1
        inventory.row(type, arrival.plusDays(2))!!.sold shouldBe 0
    }

    private infix fun String.shouldMatchCode(prefix: String) {
        withClue(this) { Regex("$prefix-\\d{4}-\\d{6,}").matches(this) shouldBe true }
    }

    private companion object {
        const val TIMEOUT = 30L
        const val CONCURRENT = 6
        const val APPROVER_PIN = "4826"
        const val RECOUNT_OFFSET = 640L

        val EVENT_FIELDS =
            setOf(
                "reservationId",
                "bookingId",
                "status",
                "roomTypeId",
                "ratePlanId",
                "arrival",
                "departure",
                "dayUse",
                "adults",
                "children",
                "origin",
                "channel",
                "createdAt",
            )

        fun definition(code: PermissionCode) = ReservationsPermissions.permissions.single { it.code == code }
    }
}
