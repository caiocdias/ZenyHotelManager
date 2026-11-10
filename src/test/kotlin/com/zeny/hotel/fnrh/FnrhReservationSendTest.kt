package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhRemoteIds
import com.zeny.hotel.fnrh.application.FnrhRemoteReservation
import com.zeny.hotel.fnrh.application.FnrhSendRun
import com.zeny.hotel.fnrh.application.FnrhSubmissionSender
import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.fnrh.domain.FnrhSubmission
import com.zeny.hotel.fnrh.domain.FnrhSubmissionFailure
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.fnrh.infrastructure.FnrhProperties
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.enableFnrh
import com.zeny.hotel.testsupport.resetFnrhSettings
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * As duas URLs da FNRH no [FnrhTestServer] com um `read-timeout` curto, para o timeout de leitura do `POST /reservas`
 * caber no teste (o padrão do [com.zeny.hotel.testsupport.FnrhTestConfiguration] é 20 s).
 */
@TestConfiguration(proxyBeanMethods = false)
class FnrhShortReadTimeoutConfiguration {
    @Bean
    @Primary
    fun shortReadTimeoutFnrhProperties(): FnrhProperties {
        val url = URI.create(FnrhTestServer.baseUrl)
        return FnrhProperties(homologationUrl = url, productionUrl = url, readTimeout = READ_TIMEOUT)
    }

    companion object {
        val READ_TIMEOUT: Duration = Duration.ofSeconds(2)
    }
}

/**
 * O envio das operações da reserva à FNRH (M5 E08, Task 6; D-E08-3, 4, 5, 6, 9, 13, 15 e 17): registro, alteração e
 * cancelamento, a política de retry, a conciliação da escrita em curso, a integração desligada, a chegada fora de
 * ordem e o isolamento de uma submissão envenenada. Cada teste cria a própria reserva e envia só as submissões dela.
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    InventoryTestConfiguration::class,
    MutableClockConfiguration::class,
    FnrhShortReadTimeoutConfiguration::class,
)
@Suppress("LongParameterList") // as portas e os apoios do fluxo real
class FnrhReservationSendTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired private val sender: FnrhSubmissionSender,
    @Autowired private val store: FnrhSubmissionStore,
    @Autowired private val remoteIds: FnrhRemoteIds,
    @Autowired private val clock: MutableClock,
    @Autowired mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val today: LocalDate get() = hotelApi.currentBusinessDate()
    private var advanced: Duration = Duration.ZERO

    /**
     * O relógio volta [REWIND] antes de cada teste: as chamadas gravadas em `fnrh.integration_call` (append-only, no
     * banco compartilhado) ficam no passado mesmo depois de o teste avançar o relógio pelo backoff, e o "último
     * sucesso" e a "última falha" de outro teste (`GET /fnrh/status`) não as veem como as mais recentes.
     */
    @BeforeEach
    fun setUp() {
        FnrhTestServer.reset()
        jdbc.resetFnrhSettings()
        advance(REWIND.negated())
    }

    @AfterEach
    fun tearDown() {
        clock.advance(advanced.negated())
        advanced = Duration.ZERO
        jdbc.resetFnrhSettings()
    }

    private fun advance(duration: Duration) {
        clock.advance(duration)
        advanced = advanced.plus(duration)
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    /** Uma linha da fila, só com o que os testes conferem. */
    private data class Row(
        val id: UUID,
        val operation: FnrhOperation,
        val status: FnrhSubmissionStatus,
        val attempts: Int,
        val nextAttemptAt: Instant,
        val lastFailure: FnrhSubmissionFailure?,
        val inFlight: Boolean,
    )

    private fun rows(reservation: UUID): List<Row> =
        jdbc
            .sql(
                "SELECT id, operation, status, attempts, next_attempt_at, last_failure, in_flight " +
                    "FROM fnrh.submission WHERE reservation_id = :id ORDER BY sequence",
            ).param("id", reservation)
            .query { rs, _ ->
                Row(
                    rs.getObject("id", UUID::class.java),
                    FnrhOperation.valueOf(rs.getString("operation")),
                    FnrhSubmissionStatus.valueOf(rs.getString("status")),
                    rs.getInt("attempts"),
                    rs.getTimestamp("next_attempt_at").toInstant(),
                    rs.getString("last_failure")?.let(FnrhSubmissionFailure::valueOf),
                    rs.getBoolean("in_flight"),
                )
            }.list()

    private fun row(id: UUID): Row =
        jdbc
            .sql("SELECT reservation_id FROM fnrh.submission WHERE id = :id")
            .param("id", id)
            .query(UUID::class.java)
            .single()
            .let { reservation -> rows(reservation).single { it.id == id } }

    /** Espera o listener gravar a submissão da [operation] da reserva e a devolve. */
    private fun awaitSubmission(
        reservation: UUID,
        operation: FnrhOperation,
    ): Row {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50)).until {
            rows(reservation).any { it.operation == operation }
        }
        return rows(reservation).single { it.operation == operation }
    }

    private fun codeOf(reservation: UUID): String =
        checkNotNull(reservationsApi.reservation(ReservationId(reservation))).code.toString()

    /** Uma reserva confirmada daqui a 5 dias, num tipo de UH novo, com o registro já gravado pelo listener. */
    private fun reservation(
        externalId: String? = null,
        origin: String = "PHONE",
        adults: Int = 2,
        extraGuests: List<UUID> = emptyList(),
    ): UUID {
        val (type, _) = support.inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type), channels = listOf("DIRECT", "WEBSITE", "OTA"))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(PRICED_DAYS))
        val holder = support.guest()
        val arrival = today.plusDays(ARRIVAL_IN_DAYS)
        val guests = listOf(holder to true) + extraGuests.map { it to false }
        val created =
            support.created(
                support.booking(
                    holder,
                    support.reservation(type, plan.id, arrival, arrival.plusDays(2), adults, guests = guests),
                    origin = origin,
                    externalId = externalId,
                ),
            )["reservations"][0]
        val id = UUID.fromString(created["id"].asString())
        createdBodies[id] = created
        awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        return id
    }

    /** A reserva como `POST /api/v1/bookings` a devolveu, base do corpo da alteração. */
    private val createdBodies = mutableMapOf<UUID, JsonNode>()

    private fun changeDeparture(
        reservation: UUID,
        departure: LocalDate,
    ) {
        support
            .change(reservation, support.changeBody(createdBodies.getValue(reservation), departure = departure))
            .also { with(support.billing) { it.expect(200) } }
    }

    private fun minorGuest(): UUID =
        UUID.fromString(
            support.guests
                .createGuest(
                    support.guests.createBody(
                        profile =
                            support.guests.profileBody(extra = mapOf("birthDate" to today.minusYears(MINOR_AGE))),
                        documents = emptyList(),
                    ),
                )["id"]
                .asString(),
        )

    private fun cancel(reservation: UUID) {
        support.cancel(reservation).also { with(support.billing) { it.expect(200) } }
        awaitSubmission(reservation, FnrhOperation.CANCEL_RESERVATION)
    }

    private fun remoteId(): String = "r-${UuidV7.generate()}"

    private fun requests(
        method: String,
        path: String,
    ) = FnrhTestServer.requests().filter { it.method.value() == method && it.url == FnrhTestServer.BASE_PATH + path }

    private fun FnrhSubmissionSender.send(reservation: UUID): FnrhSendRun = sendDueFor(reservation)

    /** O banco guarda microssegundos, arredondados: confere a espera com essa tolerância. */
    private fun Instant.shouldBeDueIn(delay: Duration) {
        Duration.between(clock.instant().plus(delay), this).abs() shouldBeLessThan Duration.ofMillis(1)
    }

    // --- Registro -------------------------------------------------------------------------------------------------

    @Test
    fun `registering stores the remote id and the encrypted link and sends the OTA code and the minors`() {
        val external = "OTA-${UuidV7.generate()}"
        val id = reservation(externalId = external, origin = "OTA", extraGuests = listOf(minorGuest()))
        val submission = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote, "https://precheckin.example/r/$remote")

        sender.send(id) shouldBe FnrhSendRun(sent = 1, postponed = 0, failed = 0, invalid = 0)

        row(submission.id).status shouldBe FnrhSubmissionStatus.SENT
        row(submission.id).inFlight shouldBe false
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().also {
            it.remoteId shouldBe FnrhRemoteId(remote)
            it.precheckinLink shouldBe "https://precheckin.example/r/$remote"
            it.cancelled shouldBe false
        }
        val stored =
            jdbc
                .sql("SELECT precheckin_link FROM fnrh.remote_reservation WHERE reservation_id = :id")
                .param("id", id)
                .query(String::class.java)
                .single()
        stored shouldStartWith "v1:"
        stored shouldNotContain "precheckin.example"
        val body = jsonMapper.readTree(requests("POST", "/reservas").single().bodyAsString)
        body["numero_reserva"].asString() shouldBe codeOf(id)
        body["numero_reserva_ota"].asString() shouldBe external
        body["origem_reserva_id"].asString() shouldBe "OTA"
        body["quantidade_hospede_adulto"].asInt() shouldBe 2
        body["quantidade_hospede_menor"].asInt() shouldBe 1
    }

    @Test
    fun `the audit entry of a sent submission has its state and never the reservation code`() {
        val id = reservation()
        val submission = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())

        sender.send(id).sent shouldBe 1

        val entries = support.auditEntries(submission.id)
        entries shouldHaveSize 1
        entries.single()["action"] shouldBe "fnrh.submission.send"
        val after = entries.single()["after"] as String
        after shouldContain "SENT"
        after shouldContain "REGISTER_RESERVATION"
        (entries.single()["before"] as String) shouldNotContain codeOf(id)
        after shouldNotContain codeOf(id)
    }

    @Test
    fun `a temporary failure backs off 1, 2 and 4 minutes, is not sent before it is due and fails on the 8th`() {
        val id = reservation()
        val submission = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        iam.enableFnrh()
        FnrhTestServer.stubAny(503)

        sender.send(id) shouldBe FnrhSendRun(sent = 0, postponed = 1, failed = 0, invalid = 0)
        row(submission.id).attempts shouldBe 1
        row(submission.id).nextAttemptAt.shouldBeDueIn(Duration.ofMinutes(1))
        row(submission.id).lastFailure shouldBe FnrhSubmissionFailure.SERVER_ERROR

        // Antes de vencer, nada sai.
        val before = FnrhTestServer.requests().size
        sender.send(id) shouldBe FnrhSendRun(0, 0, 0, 0)
        FnrhTestServer.requests().size shouldBe before
        row(submission.id).attempts shouldBe 1

        advance(Duration.ofMinutes(1))
        sender.send(id).postponed shouldBe 1
        row(submission.id).attempts shouldBe 2
        row(submission.id).nextAttemptAt.shouldBeDueIn(Duration.ofMinutes(2))

        advance(Duration.ofMinutes(2))
        sender.send(id).postponed shouldBe 1
        row(submission.id).attempts shouldBe 3
        row(submission.id).nextAttemptAt.shouldBeDueIn(Duration.ofMinutes(4))

        listOf(4L, 8L, 16L, 32L).forEachIndexed { index, minutes ->
            advance(Duration.ofMinutes(minutes))
            sender.send(id).postponed shouldBe 1
            row(submission.id).attempts shouldBe 4 + index
        }
        row(submission.id).nextAttemptAt.shouldBeDueIn(Duration.ofHours(1))

        advance(Duration.ofHours(1))
        sender.send(id) shouldBe FnrhSendRun(sent = 0, postponed = 0, failed = 1, invalid = 0)
        row(submission.id).attempts shouldBe 8
        row(submission.id).status shouldBe FnrhSubmissionStatus.FAILED
    }

    @Test
    fun `a read timeout on the registration is reconciled by code on the next attempt, without a second POST`() {
        val id = reservation()
        val submission = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        iam.enableFnrh()
        FnrhTestServer.stub("/reservas", 200, """{"reserva":{"reserva_id":"late"}}""", delayMs = TIMEOUT_DELAY_MS)

        sender.send(id).postponed shouldBe 1
        row(submission.id).lastFailure shouldBe FnrhSubmissionFailure.UNKNOWN_OUTCOME
        row(submission.id).inFlight shouldBe true

        FnrhTestServer.reset()
        val remote = remoteId()
        FnrhTestServer.stubFindReservationByCode_v2_4(codeOf(id), remote)
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())
        advance(Duration.ofMinutes(1))

        sender.send(id).sent shouldBe 1

        requests("POST", "/reservas").shouldBeEmpty()
        row(submission.id).status shouldBe FnrhSubmissionStatus.SENT
        row(submission.id).inFlight shouldBe false
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().also {
            it.remoteId shouldBe FnrhRemoteId(remote)
            it.precheckinLink.shouldBeNull()
        }
    }

    @Test
    fun `a crash after the 2xx leaves the write in flight and the next attempt reconciles without a POST`() {
        val id = reservation()
        val submission = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        store.markInFlight(submission.id)
        iam.enableFnrh()
        val remote = remoteId()
        FnrhTestServer.stubFindReservationByCode_v2_4(codeOf(id), remote)
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())

        sender.send(id).sent shouldBe 1

        requests("POST", "/reservas").shouldBeEmpty()
        requests("GET", "/reservas?page_number=1&codigo_reserva=${codeOf(id)}") shouldHaveSize 1
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().remoteId shouldBe
            FnrhRemoteId(remote)
        row(submission.id).status shouldBe FnrhSubmissionStatus.SENT
    }

    @Test
    fun `a write in flight that Serpro does not have is registered after the reconciliation`() {
        val id = reservation()
        val submission = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        store.markInFlight(submission.id)
        iam.enableFnrh()
        val remote = remoteId()
        FnrhTestServer.stubFindReservationByCode_v2_4(codeOf(id))
        FnrhTestServer.stubRegisterReservation_v2_4(remote)

        sender.send(id).sent shouldBe 1

        requests("POST", "/reservas") shouldHaveSize 1
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().remoteId shouldBe
            FnrhRemoteId(remote)
    }

    // --- Integração desligada -------------------------------------------------------------------------------------

    @Test
    fun `with the integration off nothing is touched, and once on the submission is sent`() {
        val id = reservation()
        val submission = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())

        sender.send(id) shouldBe FnrhSendRun(0, 0, 0, 0)

        FnrhTestServer.requests().shouldBeEmpty()
        row(submission.id).also {
            it.status shouldBe FnrhSubmissionStatus.PENDING
            it.attempts shouldBe 0
        }

        iam.enableFnrh()
        sender.send(id).sent shouldBe 1
        row(submission.id).status shouldBe FnrhSubmissionStatus.SENT
    }

    // --- Alteração e cancelamento ---------------------------------------------------------------------------------

    @Test
    fun `a change is sent by PUT and a cancellation cancels the remote reservation, keeping the link`() {
        val id = reservation()
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote, "https://precheckin.example/r/$remote")
        sender.send(id).sent shouldBe 1

        changeDeparture(id, today.plusDays(ARRIVAL_IN_DAYS + 3))
        val update = awaitSubmission(id, FnrhOperation.UPDATE_RESERVATION)
        FnrhTestServer.stubUpdateReservation_v2_4(remote)
        sender.send(id).sent shouldBe 1
        row(update.id).status shouldBe FnrhSubmissionStatus.SENT
        val put = requests("PUT", "/reservas/$remote").single()
        jsonMapper.readTree(put.bodyAsString)["data_saida"].asString() shouldBe
            today.plusDays(ARRIVAL_IN_DAYS + 3).toString()

        cancel(id)
        FnrhTestServer.stubCancelReservation_v2_4(remote)
        sender.send(id).sent shouldBe 1
        requests("POST", "/reservas/$remote/cancelar") shouldHaveSize 1
        rows(id).single { it.operation == FnrhOperation.CANCEL_RESERVATION }.status shouldBe
            FnrhSubmissionStatus.SENT
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().also {
            it.cancelled shouldBe true
            it.precheckinLink shouldBe "https://precheckin.example/r/$remote"
        }
    }

    @Test
    fun `a change of a reservation without a remote id registers it, without the PUT`() {
        val id = reservation()
        val register = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        // Como uma reserva anterior à E08: sem o registro na fila.
        jdbc.sql("DELETE FROM fnrh.submission WHERE id = :id").param("id", register.id).update()
        changeDeparture(id, today.plusDays(ARRIVAL_IN_DAYS + 3))
        val update = awaitSubmission(id, FnrhOperation.UPDATE_RESERVATION)
        iam.enableFnrh()
        val remote = remoteId()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        FnrhTestServer.stubUpdateReservation_v2_4(remote)

        sender.send(id).sent shouldBe 1

        row(update.id).status shouldBe FnrhSubmissionStatus.SENT
        requests("POST", "/reservas") shouldHaveSize 1
        requests("PUT", "/reservas/$remote").shouldBeEmpty()
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().remoteId shouldBe
            FnrhRemoteId(remote)
    }

    @Test
    fun `a change of a reservation cancelled in Serpro fails with REMOTE_CANCELLED without a call`() {
        val id = reservation()
        val register = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        iam.enableFnrh()
        remoteIds.saveReservation(
            FnrhRemoteReservation(id, FnrhEnvironment.HOMOLOGATION, FnrhRemoteId(remoteId()), null, cancelled = true),
            register.id,
        )
        val update =
            FnrhSubmission.create(
                reservationId = id,
                reservationCode = codeOf(id),
                operation = FnrhOperation.UPDATE_RESERVATION,
                eventKey = "reactivated:v99",
                causalVersion = 99,
                performedBy = null,
                occurredAt = clock.instant(),
                now = clock.instant(),
            )
        store.recordIfAbsent(update) shouldBe true

        sender.send(id) shouldBe FnrhSendRun(sent = 1, postponed = 0, failed = 1, invalid = 0)

        FnrhTestServer.requests().shouldBeEmpty()
        row(register.id).status shouldBe FnrhSubmissionStatus.SENT
        row(update.id).also {
            it.status shouldBe FnrhSubmissionStatus.FAILED
            it.lastFailure shouldBe FnrhSubmissionFailure.REMOTE_CANCELLED
        }
    }

    /** Uma alteração gravada pelo store, antes do cancelamento da reserva (versão 1). */
    private fun recordUpdate(reservation: UUID): UUID {
        val update =
            FnrhSubmission.create(
                reservationId = reservation,
                reservationCode = codeOf(reservation),
                operation = FnrhOperation.UPDATE_RESERVATION,
                eventKey = "changed:v1",
                causalVersion = 1,
                performedBy = null,
                occurredAt = clock.instant(),
                now = clock.instant(),
            )
        store.recordIfAbsent(update) shouldBe true
        return update.id
    }

    @Test
    fun `a change of a reservation now cancelled and without a remote id is sent without a call`() {
        val id = reservation()
        val register = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        jdbc.sql("DELETE FROM fnrh.submission WHERE id = :id").param("id", register.id).update()
        val update = recordUpdate(id)
        cancel(id)
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())

        sender.send(id) shouldBe FnrhSendRun(sent = 2, postponed = 0, failed = 0, invalid = 0)

        FnrhTestServer.requests().shouldBeEmpty()
        row(update).status shouldBe FnrhSubmissionStatus.SENT
        rows(id).map { it.status }.toSet() shouldBe setOf(FnrhSubmissionStatus.SENT)
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldBeNull()
    }

    @Test
    fun `a change in flight of a reservation now cancelled is reconciled, kept and then cancelled`() {
        val id = reservation()
        val register = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        jdbc.sql("DELETE FROM fnrh.submission WHERE id = :id").param("id", register.id).update()
        val update = recordUpdate(id)
        cancel(id)
        store.markInFlight(update)
        iam.enableFnrh()
        val remote = remoteId()
        FnrhTestServer.stubFindReservationByCode_v2_4(codeOf(id), remote)
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())
        FnrhTestServer.stubUpdateReservation_v2_4(remote)
        FnrhTestServer.stubCancelReservation_v2_4(remote)

        sender.send(id).sent shouldBe 2

        requests("POST", "/reservas").shouldBeEmpty()
        requests("PUT", "/reservas/$remote").shouldBeEmpty()
        requests("POST", "/reservas/$remote/cancelar") shouldHaveSize 1
        row(update).also {
            it.status shouldBe FnrhSubmissionStatus.SENT
            it.inFlight shouldBe false
        }
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().also {
            it.remoteId shouldBe FnrhRemoteId(remote)
            it.cancelled shouldBe true
        }
    }

    // --- Fora de ordem --------------------------------------------------------------------------------------------

    @Test
    fun `a registration in flight of a cancelled reservation found in Serpro is kept and then cancelled`() {
        val id = reservation()
        val register = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        cancel(id)
        store.markInFlight(register.id)
        iam.enableFnrh()
        val remote = remoteId()
        FnrhTestServer.stubFindReservationByCode_v2_4(codeOf(id), remote)
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())
        FnrhTestServer.stubCancelReservation_v2_4(remote)

        sender.send(id).sent shouldBe 2

        requests("POST", "/reservas").shouldBeEmpty()
        requests("POST", "/reservas/$remote/cancelar") shouldHaveSize 1
        rows(id).map { it.status }.toSet() shouldBe setOf(FnrhSubmissionStatus.SENT)
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().also {
            it.remoteId shouldBe FnrhRemoteId(remote)
            it.cancelled shouldBe true
        }
    }

    @Test
    fun `a cancellation recorded before the registration is sent without a call, and so is the late registration`() {
        val id = reservation()
        val register = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        jdbc.sql("DELETE FROM fnrh.submission WHERE id = :id").param("id", register.id).update()
        cancel(id)
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())

        sender.send(id).sent shouldBe 1
        rows(id).single().also {
            it.operation shouldBe FnrhOperation.CANCEL_RESERVATION
            it.status shouldBe FnrhSubmissionStatus.SENT
        }

        // O listener do registro chega depois (reenvio do outbox).
        store.recordIfAbsent(
            FnrhSubmission.create(
                reservationId = id,
                reservationCode = codeOf(id),
                operation = FnrhOperation.REGISTER_RESERVATION,
                eventKey = "created",
                causalVersion = 0,
                performedBy = null,
                occurredAt = clock.instant(),
                now = clock.instant(),
            ),
        ) shouldBe true
        sender.send(id).sent shouldBe 1

        rows(id).map { it.status }.toSet() shouldBe setOf(FnrhSubmissionStatus.SENT)
        FnrhTestServer.requests().shouldBeEmpty()
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldBeNull()
    }

    // --- Isolamento -----------------------------------------------------------------------------------------------

    @Test
    fun `a poisoned submission fails temporarily with INTERNAL_ERROR and does not stop the healthy one`() {
        val poisonedReservation = UuidV7.generate()
        val poisoned =
            FnrhSubmission.create(
                reservationId = poisonedReservation,
                reservationCode = "R-POISON",
                operation = FnrhOperation.REGISTER_RESERVATION,
                eventKey = "created",
                causalVersion = 0,
                performedBy = null,
                occurredAt = clock.instant(),
                now = clock.instant(),
            )
        store.recordIfAbsent(poisoned) shouldBe true
        val healthy = reservation()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())

        sender.sendDueFor(poisonedReservation, healthy) shouldBe
            FnrhSendRun(sent = 1, postponed = 1, failed = 0, invalid = 0)

        row(poisoned.id).also {
            it.status shouldBe FnrhSubmissionStatus.PENDING
            it.lastFailure shouldBe FnrhSubmissionFailure.INTERNAL_ERROR
            it.attempts shouldBe 1
        }
        rows(healthy).single().status shouldBe FnrhSubmissionStatus.SENT
    }

    private companion object {
        /** Mais que todo o backoff de um teste (2 h 3 min até a 8ª tentativa). */
        val REWIND: Duration = Duration.ofHours(3)
        const val PRICED_DAYS = 30L
        const val ARRIVAL_IN_DAYS = 5L
        const val MINOR_AGE = 10L

        /** Maior que o `read-timeout` de teste ([FnrhShortReadTimeoutConfiguration.READ_TIMEOUT]). */
        const val TIMEOUT_DELAY_MS = 3_000
    }
}
