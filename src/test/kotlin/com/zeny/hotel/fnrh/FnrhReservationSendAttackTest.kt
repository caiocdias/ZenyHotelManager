package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhConnectionSource
import com.zeny.hotel.fnrh.application.FnrhRemoteIds
import com.zeny.hotel.fnrh.application.FnrhRemoteReservation
import com.zeny.hotel.fnrh.application.FnrhReservationSync
import com.zeny.hotel.fnrh.application.FnrhSendRun
import com.zeny.hotel.fnrh.application.FnrhSubmissionClosedException
import com.zeny.hotel.fnrh.application.FnrhSubmissionRecorder
import com.zeny.hotel.fnrh.application.FnrhSubmissionSender
import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.fnrh.domain.FnrhSubmission
import com.zeny.hotel.fnrh.domain.FnrhSubmissionFailure
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * Ataque ao envio das operações da reserva à FNRH (M5 E08, Task 6): nunca repetir às cegas (D-E08-6, 15), releitura
 * depois da marca, isolamento (D-E08-17), retry e relógio (D-E08-4), troca de ambiente (D-E08-13), estado atual
 * (D-E08-9) e privacidade. Cada teste cria a própria reserva e envia só as submissões dela.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension::class)
@Import(
    IamTestConfiguration::class,
    InventoryTestConfiguration::class,
    MutableClockConfiguration::class,
    FnrhShortReadTimeoutConfiguration::class,
)
@Suppress("LongParameterList", "TooManyFunctions") // as portas e os apoios do fluxo real; um teste por ataque
class FnrhReservationSendAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired private val sender: FnrhSubmissionSender,
    @Autowired private val sync: FnrhReservationSync,
    @Autowired private val recorder: FnrhSubmissionRecorder,
    @Autowired private val connections: FnrhConnectionSource,
    @Autowired private val store: FnrhSubmissionStore,
    @Autowired private val remoteIds: FnrhRemoteIds,
    @Autowired private val clock: MutableClock,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val today: LocalDate get() = hotelApi.currentBusinessDate()
    private var advanced: Duration = Duration.ZERO
    private val createdBodies = mutableMapOf<UUID, JsonNode>()

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

    private data class Row(
        val id: UUID,
        val operation: FnrhOperation,
        val status: FnrhSubmissionStatus,
        val attempts: Int,
        val lastFailure: FnrhSubmissionFailure?,
        val inFlight: Boolean,
    )

    private fun rows(reservation: UUID): List<Row> =
        jdbc
            .sql(
                "SELECT id, operation, status, attempts, last_failure, in_flight " +
                    "FROM fnrh.submission WHERE reservation_id = :id ORDER BY sequence",
            ).param("id", reservation)
            .query { rs, _ ->
                Row(
                    rs.getObject("id", UUID::class.java),
                    FnrhOperation.valueOf(rs.getString("operation")),
                    FnrhSubmissionStatus.valueOf(rs.getString("status")),
                    rs.getInt("attempts"),
                    rs.getString("last_failure")?.let(FnrhSubmissionFailure::valueOf),
                    rs.getBoolean("in_flight"),
                )
            }.list()

    private fun row(
        reservation: UUID,
        id: UUID,
    ): Row = rows(reservation).single { it.id == id }

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

    private fun reservation(
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
                    support.reservation(
                        type,
                        plan.id,
                        arrival,
                        arrival.plusDays(2),
                        maxOf(adults, guests.size),
                        guests = guests,
                    ),
                ),
            )["reservations"][0]
        val id = UUID.fromString(created["id"].asString())
        createdBodies[id] = created
        awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        return id
    }

    private fun guestBornOn(birthDate: LocalDate): UUID =
        UUID.fromString(
            support.guests
                .createGuest(
                    support.guests.createBody(
                        profile = support.guests.profileBody(extra = mapOf("birthDate" to birthDate)),
                        documents = emptyList(),
                    ),
                )["id"]
                .asString(),
        )

    private fun changeDeparture(
        reservation: UUID,
        departure: LocalDate,
    ) {
        support
            .change(reservation, support.changeBody(createdBodies.getValue(reservation), departure = departure))
            .also { with(support.billing) { it.expect(200) } }
    }

    private fun cancel(reservation: UUID) {
        support.cancel(reservation).also { with(support.billing) { it.expect(200) } }
        awaitSubmission(reservation, FnrhOperation.CANCEL_RESERVATION)
    }

    private fun remoteId(): String = "r-${UuidV7.generate()}"

    private fun requests(
        method: String,
        path: String,
    ) = FnrhTestServer.requests().filter { it.method.value() == method && it.url == FnrhTestServer.BASE_PATH + path }

    private fun findPath(reservation: UUID) = "/reservas?page_number=1&codigo_reserva=${codeOf(reservation)}"

    private fun pendingRegister(): Pair<UUID, Row> {
        val id = reservation()
        return id to awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
    }

    // --- Nunca repetir às cegas -----------------------------------------------------------------------------------

    @Test
    fun `an ambiguous reconciliation is UNKNOWN_OUTCOME and never a POST, and keeps the write in flight`() {
        val (id, register) = pendingRegister()
        store.markInFlight(register.id)
        iam.enableFnrh()
        FnrhTestServer.stubFindReservationByCode_v2_4(codeOf(id), remoteId(), remoteId())
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())

        sender.sendDueFor(id) shouldBe FnrhSendRun(sent = 0, postponed = 1, failed = 0, invalid = 0)

        requests("POST", "/reservas").shouldBeEmpty()
        row(id, register.id).also {
            it.status shouldBe FnrhSubmissionStatus.PENDING
            it.lastFailure shouldBe FnrhSubmissionFailure.UNKNOWN_OUTCOME
            it.inFlight shouldBe true
            it.attempts shouldBe 1
        }
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldBeNull()
    }

    @Test
    fun `another failure and a manual retry keep the write in flight, and the next attempt still reconciles`() {
        val (id, register) = pendingRegister()
        store.markInFlight(register.id)
        iam.enableFnrh()
        // A conciliação mesma falha de forma permanente: a submissão vira FAILED com a marca.
        FnrhTestServer.stub(findPath(id), 403)

        sender.sendDueFor(id) shouldBe FnrhSendRun(sent = 0, postponed = 0, failed = 1, invalid = 0)
        row(id, register.id).also {
            it.status shouldBe FnrhSubmissionStatus.FAILED
            it.inFlight shouldBe true
        }

        transactions.executeWithoutResult {
            val submission = checkNotNull(store.find(register.id))
            submission.retry(clock.instant())
            store.save(submission)
        }
        row(id, register.id).also {
            it.status shouldBe FnrhSubmissionStatus.PENDING
            it.attempts shouldBe 0
            it.inFlight shouldBe true
        }

        FnrhTestServer.reset()
        val remote = remoteId()
        FnrhTestServer.stubFindReservationByCode_v2_4(codeOf(id), remote)
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())
        sender.sendDueFor(id).sent shouldBe 1

        requests("POST", "/reservas").shouldBeEmpty()
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().remoteId shouldBe
            FnrhRemoteId(remote)
        row(id, register.id).inFlight shouldBe false
    }

    // --- Releitura depois da marca --------------------------------------------------------------------------------

    @Test
    fun `a submission cancelled after it was read does not call Serpro and the recorder does nothing`() {
        val (id, register) = pendingRegister()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())
        val stale = checkNotNull(store.find(register.id))
        val cancelled = checkNotNull(store.find(register.id))
        cancelled.cancel(clock.instant())
        store.save(cancelled)
        val reservation = checkNotNull(reservationsApi.reservation(ReservationId(id)))
        val connection = checkNotNull(connections.current())

        shouldThrow<FnrhSubmissionClosedException> { sync.register(stale, reservation, connection) }

        FnrhTestServer.requests().shouldBeEmpty()
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldBeNull()
        val auditBefore = support.auditEntries(register.id).size
        recorder.sent(register.id) shouldBe FnrhSubmissionStatus.CANCELLED
        recorder.failed(register.id, FnrhSubmissionFailure.SERVER_ERROR) shouldBe FnrhSubmissionStatus.CANCELLED
        recorder.failedLocally(register.id, FnrhSubmissionFailure.REMOTE_CANCELLED) shouldBe
            FnrhSubmissionStatus.CANCELLED
        recorder.invalid(register.id, emptyList()) shouldBe FnrhSubmissionStatus.CANCELLED
        row(id, register.id).also {
            it.status shouldBe FnrhSubmissionStatus.CANCELLED
            it.attempts shouldBe 0
            it.lastFailure.shouldBeNull()
        }
        support.auditEntries(register.id) shouldHaveSize auditBefore
        // E o job, com a submissão já cancelada, não a escolhe.
        sender.sendDueFor(id) shouldBe FnrhSendRun(0, 0, 0, 0)
        FnrhTestServer.requests().shouldBeEmpty()
    }

    // --- Isolamento -----------------------------------------------------------------------------------------------

    @Test
    fun `a poisoned submission is retried on the curve and fails on the 8th attempt without leaking the code`(
        output: CapturedOutput,
    ) {
        val poisonedReservation = UuidV7.generate()
        val poisoned =
            FnrhSubmission.create(
                reservationId = poisonedReservation,
                reservationCode = "R-POISON-CODE",
                operation = FnrhOperation.REGISTER_RESERVATION,
                eventKey = "created",
                causalVersion = 0,
                performedBy = null,
                occurredAt = clock.instant(),
                now = clock.instant(),
            )
        store.recordIfAbsent(poisoned) shouldBe true
        iam.enableFnrh()

        var delay = Duration.ofMinutes(1)
        (1..MAX_ATTEMPTS - 1).forEach { attempt ->
            sender.sendDueFor(poisonedReservation).postponed shouldBe 1
            row(poisonedReservation, poisoned.id).attempts shouldBe attempt
            advance(delay)
            delay = minOf(delay.multipliedBy(2), Duration.ofHours(1))
        }
        sender.sendDueFor(poisonedReservation) shouldBe FnrhSendRun(sent = 0, postponed = 0, failed = 1, invalid = 0)

        row(poisonedReservation, poisoned.id).also {
            it.status shouldBe FnrhSubmissionStatus.FAILED
            it.attempts shouldBe MAX_ATTEMPTS
            it.lastFailure shouldBe FnrhSubmissionFailure.INTERNAL_ERROR
        }
        FnrhTestServer.requests().shouldBeEmpty()
        output.all shouldNotContain "R-POISON-CODE"
    }

    // --- Falhas permanentes ---------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource("400,VALIDATION", "401,UNAUTHORIZED", "403,FORBIDDEN", "404,NOT_FOUND")
    fun `a permanent failure from Serpro fails the submission at once, with one attempt`(
        status: Int,
        failure: FnrhSubmissionFailure,
    ) {
        val (id, register) = pendingRegister()
        iam.enableFnrh()
        FnrhTestServer.stubAny(status)

        sender.sendDueFor(id) shouldBe FnrhSendRun(sent = 0, postponed = 0, failed = 1, invalid = 0)

        row(id, register.id).also {
            it.status shouldBe FnrhSubmissionStatus.FAILED
            it.attempts shouldBe 1
            it.lastFailure shouldBe failure
        }
        // FAILED segura a fila: nada sai de novo, nem depois de muito tempo.
        advance(Duration.ofHours(2))
        val before = FnrhTestServer.requests().size
        sender.sendDueFor(id) shouldBe FnrhSendRun(0, 0, 0, 0)
        FnrhTestServer.requests().size shouldBe before
    }

    // --- Troca de ambiente ----------------------------------------------------------------------------------------

    @Test
    fun `after switching the environment the reservation is registered again in the new one, without a PUT`() {
        val (id, register) = pendingRegister()
        iam.enableFnrh()
        val remoteHomologation = remoteId()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteHomologation)
        sender.sendDueFor(id).sent shouldBe 1

        changeDeparture(id, today.plusDays(ARRIVAL_IN_DAYS + 3))
        val update = awaitSubmission(id, FnrhOperation.UPDATE_RESERVATION)
        iam.enableFnrh("PRODUCTION")
        val remoteProduction = remoteId()
        FnrhTestServer.reset()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteProduction)
        FnrhTestServer.stubUpdateReservation_v2_4(remoteHomologation)

        sender.sendDueFor(id).sent shouldBe 1

        requests("POST", "/reservas") shouldHaveSize 1
        requests("PUT", "/reservas/$remoteHomologation").shouldBeEmpty()
        row(id, update.id).status shouldBe FnrhSubmissionStatus.SENT
        remoteIds.reservation(id, FnrhEnvironment.PRODUCTION).shouldNotBeNull().remoteId shouldBe
            FnrhRemoteId(remoteProduction)
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().remoteId shouldBe
            FnrhRemoteId(remoteHomologation)
        row(id, register.id).status shouldBe FnrhSubmissionStatus.SENT
    }

    // --- Estado atual ---------------------------------------------------------------------------------------------

    @Test
    fun `a cancellation of a remote reservation already cancelled is sent without a call`() {
        val (id, register) = pendingRegister()
        iam.enableFnrh()
        remoteIds.saveReservation(
            FnrhRemoteReservation(id, FnrhEnvironment.HOMOLOGATION, FnrhRemoteId(remoteId()), null, cancelled = true),
            register.id,
        )
        cancel(id)
        FnrhTestServer.reset()

        sender.sendDueFor(id).sent shouldBe 2

        FnrhTestServer.requests().shouldBeEmpty()
        rows(id).map { it.status }.toSet() shouldBe setOf(FnrhSubmissionStatus.SENT)
    }

    @Test
    fun `one run drains the change and the cancellation of a registered reservation in order`() {
        val (id, _) = pendingRegister()
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        FnrhTestServer.stubUpdateReservation_v2_4(remote)
        FnrhTestServer.stubCancelReservation_v2_4(remote)
        sender.sendDueFor(id).sent shouldBe 1
        changeDeparture(id, today.plusDays(ARRIVAL_IN_DAYS + 3))
        awaitSubmission(id, FnrhOperation.UPDATE_RESERVATION)
        cancel(id)

        sender.sendDueFor(id) shouldBe FnrhSendRun(sent = 2, postponed = 0, failed = 0, invalid = 0)

        FnrhTestServer.requests().map {
            it.method.value() + " " +
                it.url.removePrefix(
                    FnrhTestServer.BASE_PATH,
                )
        } shouldBe
            listOf("POST /reservas", "PUT /reservas/$remote", "POST /reservas/$remote/cancelar")
        rows(id).map { it.status }.toSet() shouldBe setOf(FnrhSubmissionStatus.SENT)
    }

    // --- Menores --------------------------------------------------------------------------------------------------

    private fun minorsSent(
        childAges: List<Int>,
        guests: List<UUID>,
    ): Int {
        val id = reservation(extraGuests = guests)
        if (childAges.isNotEmpty()) {
            jdbc
                .sql("UPDATE reservations.reservation SET child_ages = :ages WHERE id = :id")
                .param("ages", childAges.toTypedArray())
                .param("id", id)
                .update()
        }
        iam.enableFnrh()
        FnrhTestServer.reset()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())
        sender.sendDueFor(id).sent shouldBe 1
        return jsonMapper.readTree(requests("POST", "/reservas").single().bodyAsString)[
            "quantidade_hospede_menor",
        ].asInt()
    }

    @Test
    fun `minors is the greater of the child ages and the listed guests under 18 on arrival`() {
        val arrival = today.plusDays(ARRIVAL_IN_DAYS)
        // Mais crianças informadas que hóspedes listados menores: vale a das idades.
        minorsSent(listOf(3, 5), listOf(guestBornOn(today.minusYears(10)))) shouldBe 2
        // Mais menores listados que idades informadas: vale a lista.
        minorsSent(emptyList(), listOf(guestBornOn(today.minusYears(10)), guestBornOn(today.minusYears(12)))) shouldBe 2
        // Faz 18 anos na chegada: já é adulto; um dia antes dos 18, ainda é menor.
        minorsSent(emptyList(), listOf(guestBornOn(arrival.minusYears(ADULT_AGE)))) shouldBe 0
        minorsSent(emptyList(), listOf(guestBornOn(arrival.minusYears(ADULT_AGE).plusDays(1)))) shouldBe 1
    }

    // --- Privacidade ----------------------------------------------------------------------------------------------

    @Test
    fun `the pre-check-in link is encrypted in the column and absent from the logs, the audit trail and the call log`(
        output: CapturedOutput,
    ) {
        val (id, register) = pendingRegister()
        iam.enableFnrh()
        val secret = "tok${UuidV7.generate().toString().replace("-", "")}"
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId(), "https://precheckin.example/r/$secret")

        sender.sendDueFor(id).sent shouldBe 1

        val column =
            jdbc
                .sql("SELECT precheckin_link FROM fnrh.remote_reservation WHERE reservation_id = :id")
                .param("id", id)
                .query(String::class.java)
                .list()
        column shouldHaveSize 1
        column.single() shouldNotContain secret
        output.all shouldNotContain secret
        support.auditEntries(register.id).forEach {
            (it["before"] as String?).orEmpty() shouldNotContain secret
            (it["after"] as String?).orEmpty() shouldNotContain secret
        }
        support.auditEntries(id).forEach { (it["after"] as String?).orEmpty() shouldNotContain secret }
        jdbc
            .sql("SELECT count(*) FROM fnrh.integration_call c WHERE c::text LIKE :p")
            .param("p", "%$secret%")
            .query(Long::class.java)
            .single() shouldBe 0L
        jdbc
            .sql("SELECT count(*) FROM audit.audit_entry WHERE after_state::text LIKE :p OR before_state::text LIKE :p")
            .param("p", "%$secret%")
            .query(Long::class.java)
            .single() shouldBe 0L
    }

    @Test
    fun `the audit entry of a failed submission has the failure and no reservation code`() {
        val (id, register) = pendingRegister()
        iam.enableFnrh()
        FnrhTestServer.stubAny(503)

        sender.sendDueFor(id).postponed shouldBe 1

        val entry = support.auditEntries(register.id).single()
        entry["action"] shouldBe "fnrh.submission.send"
        (entry["after"] as String) shouldNotContain codeOf(id)
        (entry["before"] as String) shouldNotContain codeOf(id)
        (entry["after"] as String) shouldContain "SERVER_ERROR"
    }

    private companion object {
        val REWIND: Duration = Duration.ofHours(3)
        const val PRICED_DAYS = 30L
        const val ARRIVAL_IN_DAYS = 5L
        const val MAX_ATTEMPTS = 8
        const val ADULT_AGE = 18L
    }
}
