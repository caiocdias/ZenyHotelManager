package com.zeny.hotel.reservations

import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestStatus
import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.guests.GuestsMerged
import com.zeny.hotel.guests.GuestsPermissions
import com.zeny.hotel.guests.GuestsTestSupport
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.reservations.application.BookingStore
import com.zeny.hotel.reservations.application.GuestReferenceRepointing
import com.zeny.hotel.reservations.application.GuestsMergedListener
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.test.EnableScenarios
import org.springframework.test.util.AopTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * As reservas e o `guests` (M4 E07; H8, H12, RF-HOS-04 e 07): o alerta da lista de restrição na resposta; o hóspede
 * mesclado vira o sobrevivente e o anonimizado é recusado na criação; o listener de produção de `GuestsMerged`
 * reponta o titular e os hóspedes, e a reentrega não muda nada; a reserva ativa recusa a anonimização; a exportação do
 * titular traz a seção `reservations`. O mesmo contexto dos outros testes das reservas.
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList") // as portas que os cenários usam
class BookingGuestsApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val listener: GuestsMergedListener,
    @Autowired private val repointing: GuestReferenceRepointing,
    @Autowired private val store: BookingStore,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val guestsApi: GuestsApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    @Test
    fun `a guest on the restriction list gives an alert in the response, without blocking the reservation`() {
        val stay = stay()
        val restricted = support.guest()
        with(iam) {
            call(POST, "/api/v1/guests/$restricted/restrict", asAdmin(), mapOf("reason" to "Danos ao quarto")).ok()
        }

        val booking =
            support.created(
                support.booking(
                    support.guest(),
                    support.reservation(
                        stay.type,
                        stay.plan,
                        stay.arrival,
                        stay.departure,
                        guests =
                            listOf(restricted to true),
                    ),
                ),
            )

        booking["restrictionAlerts"].values().map {
            it["guestId"].asString() to it["reason"].asString()
        } shouldContainExactly
            listOf(restricted.toString() to "Danos ao quarto")
        support
            .reservation(UUID.fromString(booking["reservations"][0]["id"].asString()))["restrictionAlerts"]
            .size() shouldBe 1
    }

    @Test
    fun `a merged guest becomes the survivor, an anonymized one is refused and an unknown one is a 400`() {
        val stay = stay()
        val survivor = support.guest()
        val merged = support.guest()
        merge(survivor, merged)
        val anonymized = support.guest()
        with(iam) { call(POST, "/api/v1/guests/$anonymized/anonymize", asAdmin()).ok() }

        val booking =
            support.created(
                support.booking(
                    merged,
                    support.reservation(
                        stay.type,
                        stay.plan,
                        stay.arrival,
                        stay.departure,
                        guests =
                            listOf(merged to true),
                    ),
                ),
            )
        booking["holderGuestId"].asString() shouldBe survivor.toString()
        booking["reservations"][0]["guests"][0]["guestId"].asString() shouldBe survivor.toString()

        with(support.billing) {
            support
                .create(
                    support.booking(
                        anonymized,
                        support.reservation(stay.type, stay.plan, stay.arrival, stay.departure),
                    ),
                ).problem(422, "GUEST_ANONYMIZED")
            support
                .create(
                    support.booking(
                        UuidV7.generate(),
                        support.reservation(stay.type, stay.plan, stay.arrival, stay.departure),
                    ),
                ).problem(400, "UNKNOWN_GUEST")["errors"][0]["field"]
                .asString() shouldBe "holderGuestId"
            val reservation =
                support.reservation(
                    stay.type,
                    stay.plan,
                    stay.arrival,
                    stay.departure,
                    guests = listOf(survivor to true, UuidV7.generate() to false),
                )
            support
                .create(support.booking(survivor, reservation))
                .problem(400, "UNKNOWN_GUEST")["errors"][0]["field"]
                .asString() shouldBe "reservations[0].guests[1].guestId"
        }
    }

    @Test
    @Suppress("LongMethod") // as duas reservas, a mesclagem pelo listener e a reentrega
    fun `GuestsMerged repoints the holder and the stay guests, and a redelivery changes nothing`() {
        val stay = stay(rooms = 2)
        val absorbed = support.guest()
        val companion = support.guest()
        val survivor = support.guest()
        val first =
            support.created(
                support.booking(
                    absorbed,
                    support.reservation(
                        stay.type,
                        stay.plan,
                        stay.arrival,
                        stay.departure,
                        guests = listOf(absorbed to true, companion to false),
                    ),
                ),
            )
        // Uma reserva com os dois perfis: fica um só, o sobrevivente, como principal.
        val both =
            support.created(
                support.booking(
                    survivor,
                    support.reservation(
                        stay.type,
                        stay.plan,
                        stay.arrival,
                        stay.departure,
                        guests = listOf(survivor to false, absorbed to true),
                    ),
                ),
            )

        merge(survivor, absorbed)

        val firstReservation = UUID.fromString(first["reservations"][0]["id"].asString())
        val bothReservation = UUID.fromString(both["reservations"][0]["id"].asString())
        await().atMost(Duration.ofSeconds(TIMEOUT)).until { holderOf(first) == survivor }
        guests(firstReservation) shouldContainExactly listOf(survivor to true, companion to false)
        guests(bothReservation) shouldContainExactly listOf(survivor to true)
        val versions = versions(first, firstReservation, bothReservation)
        versions shouldBe listOf(1L, 1L, 1L)
        support.auditEntries(firstReservation).map { it["action"] } shouldContainExactly
            listOf("reservations.booking.create", "reservations.guest-reference.repoint")

        // A reentrega (o listener sem o proxy, como o outbox o chamaria de novo): nada muda, nada vai à trilha.
        AopTestUtils
            .getTargetObject<GuestsMergedListener>(listener)
            .on(GuestsMerged(survivor, absorbed, Instant.now()))
        versions(first, firstReservation, bothReservation) shouldBe versions
        guests(firstReservation) shouldContainExactly listOf(survivor to true, companion to false)
        support.auditEntries(firstReservation).size shouldBe 2
    }

    @Test
    fun `the repointing waits for a reservation being created with the absorbed profile`() {
        val absorbed = GuestId(support.guest())
        val survivor = GuestId(support.guest())
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val creation =
            CompletableFuture.runAsync {
                transactions.executeWithoutResult {
                    store.lockGuests(listOf(absorbed), exclusive = false)
                    locked.countDown()
                    release.await(TIMEOUT, TimeUnit.SECONDS)
                }
            }
        locked.await(TIMEOUT, TimeUnit.SECONDS) shouldBe true

        val repoint = CompletableFuture.supplyAsync { repointing.repoint(absorbed, survivor) }
        Thread.sleep(BLOCKED_MILLIS)
        repoint.isDone shouldBe false

        release.countDown()
        creation.get(TIMEOUT, TimeUnit.SECONDS)
        repoint.get(TIMEOUT, TimeUnit.SECONDS).empty shouldBe true
    }

    // --- Corridas com a mesclagem e a anonimização (a releitura depois da trava vê o que foi confirmado) -------------

    @Test
    fun `a guest anonymized after the creation read it is refused, never booked`() {
        val stay = stay()
        val guest = support.guest()

        val error =
            shouldThrow<BusinessRuleException> {
                transactions.executeWithoutResult {
                    // A transação da criação já leu o perfil (ele fica no contexto de persistência).
                    guestsApi.guest(GuestId(guest))!!.status shouldBe GuestStatus.ACTIVE
                    CompletableFuture
                        .runAsync { with(iam) { call(POST, "/api/v1/guests/$guest/anonymize", asAdmin()).ok() } }
                        .get(TIMEOUT, TimeUnit.SECONDS)
                    reservationsApi.createBooking(newBooking(stay, guest))
                }
            }

        error.code shouldBe ReservationsErrorCodes.GUEST_ANONYMIZED
        support.bookingCount(stay.type) shouldBe 0
    }

    @Test
    fun `a guest merged after the creation read it is booked as the survivor, never as the absorbed`() {
        val stay = stay()
        val absorbed = support.guest()
        val survivor = support.guest()

        val booking =
            transactions.execute {
                guestsApi.guest(GuestId(absorbed))!!.status shouldBe GuestStatus.ACTIVE
                CompletableFuture.runAsync { merge(survivor, absorbed) }.get(TIMEOUT, TimeUnit.SECONDS)
                reservationsApi.createBooking(newBooking(stay, absorbed))
            }

        booking.holderGuestId shouldBe GuestId(survivor)
        booking.reservations
            .single()
            .guests
            .map { it.guestId } shouldContainExactly listOf(GuestId(survivor))
        holderOf(booking.id.value) shouldBe survivor
    }

    @Test
    fun `a merge confirmed between the discovery and the lock refuses the creation with GUEST_PROFILE_CHANGED`() {
        val stay = stay()
        val absorbed = support.guest()
        val survivor = support.guest()
        // Outra transação segura a trava exclusiva do perfil (como o listener de GuestsMerged): a criação descobre o
        // perfil ainda ativo e espera a trava; a mesclagem confirma enquanto ela espera.
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder =
            CompletableFuture.runAsync {
                transactions.executeWithoutResult {
                    store.lockGuests(listOf(GuestId(absorbed)), exclusive = true)
                    locked.countDown()
                    release.await(TIMEOUT, TimeUnit.SECONDS)
                }
            }
        locked.await(TIMEOUT, TimeUnit.SECONDS) shouldBe true
        val creation =
            CompletableFuture.supplyAsync {
                runCatching {
                    transactions.execute { reservationsApi.createBooking(newBooking(stay, absorbed)) }
                }
            }
        await().atMost(Duration.ofSeconds(TIMEOUT)).until { guestLockWaits(absorbed) > 0 }

        merge(survivor, absorbed)
        release.countDown()
        holder.get(TIMEOUT, TimeUnit.SECONDS)

        val error = creation.get(TIMEOUT, TimeUnit.SECONDS).exceptionOrNull()
        (error as ConflictException).code shouldBe ReservationsErrorCodes.GUEST_PROFILE_CHANGED
        support.bookingCount(stay.type) shouldBe 0
    }

    @Test
    fun `an active stay refuses the anonymization, and the export of the guest has the reservations section`() {
        val stay = stay()
        val holder = support.guest()
        val booking =
            support.created(
                support.booking(
                    holder,
                    support.reservation(
                        stay.type,
                        stay.plan,
                        stay.arrival,
                        stay.departure,
                        specialRequests = "Andar alto",
                    ),
                ),
            )
        with(iam) {
            call(POST, "/api/v1/guests/$holder/anonymize", asAdmin()).problem(409, "GUEST_HAS_ACTIVE_STAY")
        }

        // A exportação é sensível: o solicitante sem a permissão pede a aprovação de quem a tem.
        val requester = ReservationsTestSupport.Operator(iam.createUser())
        val approver =
            iam.createUserWith(TestGrant(GuestsTestSupport.definition(GuestsPermissions.DATA_EXPORT), ALL))
        iam.setPin(approver, APPROVER_PIN)
        val exported =
            with(iam) {
                val path = "/api/v1/guests/$holder/export"
                val required = call(POST, path, requester.auth).problem(403, "APPROVAL_REQUIRED")
                val token =
                    call(
                        POST,
                        "/api/v1/auth/approvals",
                        requester.auth,
                        mapOf(
                            "action" to required["action"].asString(),
                            "permission" to required["permission"].asString(),
                            "resourceRef" to required["resourceRef"].asString(),
                            "approverLogin" to approver.login,
                            "approverPin" to APPROVER_PIN,
                            "reason" to "Pedido do titular",
                        ),
                    ).expectStatus(201)!!["approvalToken"].asString()
                call(POST, path, requester.auth, approvalToken = token).ok()
            }

        val section = exported["sections"].values().single { it["name"].asString() == "reservations" }["data"]
        val reservation = section["reservations"].values().single()
        reservation["code"].asString() shouldBe booking["reservations"][0]["code"].asString()
        reservation["bookingCode"].asString() shouldBe booking["code"].asString()
        reservation["roles"].values().map { it.asString() } shouldContainExactly listOf("HOLDER", "PRIMARY_GUEST")
        reservation["specialRequests"].asString() shouldBe "Andar alto"
        reservation["nights"].size() shouldBe 2
        reservation["roomType"]["name"].asString() shouldContain "Standard"
    }

    // --- Apoio --------------------------------------------------------------------------------------------------

    private data class Stay(
        val type: UUID,
        val plan: UUID,
        val arrival: LocalDate,
        val departure: LocalDate,
    )

    private fun stay(rooms: Int = 3): Stay {
        val (type, _) = support.inventory.roomTypeWithRooms(rooms, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        return Stay(type, plan.id, today.plusDays(2), today.plusDays(4))
    }

    private fun merge(
        survivor: UUID,
        merged: UUID,
    ) = with(iam) {
        val path = "/api/v1/guests/$survivor"
        call(
            POST,
            "$path/merge",
            asAdmin(),
            mapOf("mergedGuestId" to merged, "takeFromMerged" to emptyList<String>()),
            ifMatch = support.guests.etagOf(path),
        ).ok()
    }

    private fun holderOf(booking: JsonNode): UUID = holderOf(UUID.fromString(booking["id"].asString()))

    private fun holderOf(booking: UUID): UUID =
        jdbc
            .sql("SELECT holder_guest_id FROM reservations.booking WHERE id = :id")
            .param("id", booking)
            .query(UUID::class.java)
            .single()

    private fun newBooking(
        stay: Stay,
        holder: UUID,
    ) = NewBooking(
        GuestId(holder),
        ReservationOrigin.PHONE,
        reservations =
            listOf(
                NewReservation(
                    RoomTypeId(stay.type),
                    RatePlanId(stay.plan),
                    stay.arrival,
                    stay.departure,
                    adults = 2,
                    segment = MarketSegment.LEISURE,
                    guests = listOf(NewStayGuest(GuestId(holder), primary = true)),
                ),
            ),
    )

    /** Quantas transações esperam a trava consultiva do hóspede (a criação parada em `BookingStore.lockGuests`). */
    private fun guestLockWaits(guest: UUID): Int =
        jdbc
            .sql(
                """
                SELECT count(*) FROM pg_locks
                WHERE locktype = 'advisory' AND objsubid = 2 AND NOT granted
                  AND classid::bigint = (hashtext('reservations.guest')::bigint & 4294967295)
                  AND objid::bigint = (hashtext(:id)::bigint & 4294967295)
                """.trimIndent(),
            ).param("id", guest.toString())
            .query(Int::class.java)
            .single()

    private fun guests(reservation: UUID): List<Pair<UUID, Boolean>> =
        jdbc
            .sql(
                "SELECT guest_id, is_primary FROM reservations.reservation_guest WHERE reservation_id = :id " +
                    "ORDER BY is_primary DESC, position",
            ).param("id", reservation)
            .query { rs, _ -> rs.getObject("guest_id", UUID::class.java) to rs.getBoolean("is_primary") }
            .list()

    private fun versions(
        booking: JsonNode,
        vararg reservations: UUID,
    ): List<Long> =
        listOf(
            jdbc
                .sql("SELECT version FROM reservations.booking WHERE id = :id")
                .param("id", UUID.fromString(booking["id"].asString()))
                .query(Long::class.java)
                .single(),
        ) +
            reservations.map {
                jdbc
                    .sql("SELECT version FROM reservations.reservation WHERE id = :id")
                    .param("id", it)
                    .query(Long::class.java)
                    .single()
            }

    private companion object {
        const val TIMEOUT = 30L
        const val BLOCKED_MILLIS = 700L
        const val APPROVER_PIN = "7391"
    }
}
