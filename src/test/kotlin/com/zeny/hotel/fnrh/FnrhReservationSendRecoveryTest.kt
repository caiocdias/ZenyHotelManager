package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhRemoteIds
import com.zeny.hotel.fnrh.application.FnrhRemoteReservation
import com.zeny.hotel.fnrh.application.FnrhSendRun
import com.zeny.hotel.fnrh.application.FnrhSubmissionSender
import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.fnrh.domain.FnrhRetryPolicy
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
import io.kotest.assertions.throwables.shouldThrow
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
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A escrita em curso vista pela reserva e os limites do envio (M5 E08, Task 6, revisão; D-E08-15): a marca de outra
 * submissão da mesma reserva obriga a conciliar antes do `POST /reservas`, a marca nunca liga numa submissão resolvida
 * e o envio recusa rodar dentro de uma transação do banco.
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    InventoryTestConfiguration::class,
    MutableClockConfiguration::class,
    FnrhShortReadTimeoutConfiguration::class,
)
@Suppress("LongParameterList") // as portas e os apoios do fluxo real
class FnrhReservationSendRecoveryTest(
    @Autowired iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
    @Autowired hotelApi: HotelApi,
    @Autowired reservationsApi: ReservationsApi,
    @Autowired sender: FnrhSubmissionSender,
    @Autowired store: FnrhSubmissionStore,
    @Autowired remoteIds: FnrhRemoteIds,
    @Autowired clock: MutableClock,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired tx: TransactionTemplate,
) : FnrhReservationSendTestBase(
        iam,
        jdbc,
        hotelApi,
        reservationsApi,
        sender,
        store,
        remoteIds,
        clock,
        mockMvc,
        jsonMapper,
        tx,
    ) {
    // --- Escrita em curso de outra submissão da reserva ------------------------------------------------------------

    @Test
    fun `a registration without marks registers with one POST and no reconciliation`() {
        val id = reservation()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())

        sender.send(id).sent shouldBe 1

        requests("POST", "/reservas") shouldHaveSize 1
        requests("GET", findPath(id)).shouldBeEmpty()
        store.anyInFlight(id) shouldBe false
    }

    @Test
    fun `a late registration after a change whose POST is in flight reconciles and never POSTs again`() {
        val id = reservation()
        val register = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        jdbc.sql("DELETE FROM fnrh.submission WHERE id = :id").param("id", register.id).update()
        val update = recordUpdate(id)
        store.markInFlight(update)
        // O registro chega depois (reenvio do outbox), sem marca, como a cabeça da reserva.
        val late =
            FnrhSubmission.create(
                reservationId = id,
                reservationCode = codeOf(id),
                operation = FnrhOperation.REGISTER_RESERVATION,
                eventKey = "created",
                causalVersion = 0,
                performedBy = null,
                occurredAt = clock.instant(),
                now = clock.instant(),
            )
        store.recordIfAbsent(late) shouldBe true
        store.anyInFlight(id) shouldBe true
        iam.enableFnrh()
        val remote = remoteId()
        FnrhTestServer.stubFindReservationByCode_v2_4(codeOf(id), remote)
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())
        FnrhTestServer.stubUpdateReservation_v2_4(remote)

        sender.send(id).sent shouldBe 2

        requests("POST", "/reservas").shouldBeEmpty()
        requests("GET", findPath(id)) shouldHaveSize 1
        requests("PUT", "/reservas/$remote") shouldHaveSize 1
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().remoteId shouldBe
            FnrhRemoteId(remote)
        rows(id).map { it.status }.toSet() shouldBe setOf(FnrhSubmissionStatus.SENT)
    }

    @Test
    fun `a change after a dismissed registration that was in flight reconciles and never POSTs blindly`() {
        val id = reservation()
        dismissedInFlight(id)
        val update = recordUpdate(id)
        iam.enableFnrh()
        val remote = remoteId()
        FnrhTestServer.stubFindReservationByCode_v2_4(codeOf(id), remote)
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())

        sender.send(id).sent shouldBe 1

        requests("POST", "/reservas").shouldBeEmpty()
        requests("GET", findPath(id)) shouldHaveSize 1
        row(update).status shouldBe FnrhSubmissionStatus.SENT
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().remoteId shouldBe
            FnrhRemoteId(remote)
    }

    @Test
    fun `a cancellation after a dismissed registration that was in flight reconciles and cancels in Serpro`() {
        val id = reservation()
        val register = dismissedInFlight(id)
        cancel(id)
        val cancellation = rows(id).single { it.operation == FnrhOperation.CANCEL_RESERVATION }
        iam.enableFnrh()
        val remote = remoteId()
        FnrhTestServer.stubFindReservationByCode_v2_4(codeOf(id), remote)
        FnrhTestServer.stubCancelReservation_v2_4(remote)

        sender.send(id).sent shouldBe 1

        requests("GET", findPath(id)) shouldHaveSize 1
        requests("POST", "/reservas/$remote/cancelar") shouldHaveSize 1
        requests("POST", "/reservas").shouldBeEmpty()
        row(cancellation.id).status shouldBe FnrhSubmissionStatus.SENT
        row(register).inFlight shouldBe true
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().let {
            it.remoteId shouldBe FnrhRemoteId(remote)
            it.cancelled shouldBe true
        }
    }

    @Test
    fun `a cancellation after a dismissed registration in flight that Serpro never got is sent with no write`() {
        val id = reservation()
        dismissedInFlight(id)
        cancel(id)
        iam.enableFnrh()
        FnrhTestServer.stubFindReservationByCode_v2_4(codeOf(id))

        sender.send(id).sent shouldBe 1

        requests("GET", findPath(id)) shouldHaveSize 1
        FnrhTestServer.requests().filter { it.method.value() != "GET" }.shouldBeEmpty()
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldBeNull()
    }

    @Test
    fun `a cancellation without a remote id and without a write in flight is sent with no call`() {
        val id = reservation()
        val register = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        tx.executeWithoutResult {
            val failed = checkNotNull(store.find(register.id))
            failed.recordFailure(FnrhSubmissionFailure.VALIDATION, clock.instant(), FnrhRetryPolicy())
            failed.dismiss()
            store.save(failed)
        }
        cancel(id)
        iam.enableFnrh()

        sender.send(id).sent shouldBe 1

        FnrhTestServer.requests().shouldBeEmpty()
    }

    // --- Marca e transação ----------------------------------------------------------------------------------------

    @Test
    fun `the write in flight is never marked on a submission already resolved`() {
        val id = reservation()
        val register = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())
        sender.send(id).sent shouldBe 1
        cancel(id)
        val cancelled = rows(id).single { it.operation == FnrhOperation.CANCEL_RESERVATION }
        store.cancelIfIdle(cancelled.id, clock.instant()) shouldBe true

        store.markInFlight(register.id)
        store.markInFlight(cancelled.id)

        row(register.id).also {
            it.status shouldBe FnrhSubmissionStatus.SENT
            it.inFlight shouldBe false
        }
        row(cancelled.id).also {
            it.status shouldBe FnrhSubmissionStatus.CANCELLED
            it.inFlight shouldBe false
        }
    }

    @Test
    fun `the sender refuses to run inside a database transaction`() {
        val id = reservation()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())

        shouldThrow<IllegalStateException> { tx.execute { sender.sendDueFor(id) } }
        shouldThrow<IllegalStateException> { tx.execute { sender.sendDue() } }

        FnrhTestServer.requests().shouldBeEmpty()
        awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION).status shouldBe FnrhSubmissionStatus.PENDING
    }

    /** O registro da reserva com a escrita em curso marcada, recusado e encerrado com `dismiss`; devolve o id dele. */
    private fun dismissedInFlight(reservation: UUID): UUID {
        val register = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION)
        store.markInFlight(register.id)
        tx.executeWithoutResult {
            val failed = checkNotNull(store.find(register.id))
            failed.recordFailure(FnrhSubmissionFailure.VALIDATION, clock.instant(), FnrhRetryPolicy())
            failed.dismiss()
            store.save(failed)
        }
        row(register.id).also {
            it.status shouldBe FnrhSubmissionStatus.DISMISSED
            it.inFlight shouldBe true
        }
        return register.id
    }
}
