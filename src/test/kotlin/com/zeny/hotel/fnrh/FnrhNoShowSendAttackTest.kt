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
import com.zeny.hotel.frontdesk.FrontdeskPermissions
import com.zeny.hotel.frontdesk.FrontdeskTestSupport
import com.zeny.hotel.guests.GuestsTestSupport.Companion.newCpf
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.enableFnrh
import com.zeny.hotel.testsupport.resetFnrhSettings
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Ataques do testador ao envio do no-show à FNRH (M6 E14, Task 4; D-E14-3, D-E14-4 e D-E14-6): o `UNKNOWN_OUTCOME` do
 * `POST …/noshow` e a repetição (aceita, recusada e manual), a integração desligada no meio da rodada, a submissão
 * encerrada com o `POST` em curso, a conciliação que não acha ou acha duas, o registro conciliado depois do no-show,
 * a troca de ambiente, os fluxos reais que levariam a remota a cancelada e no-show ao mesmo tempo (a CHECK
 * `remote_reservation_closed_ck`), o check-in sobre uma remota no-show (não coberto por D-E14-4: o comportamento atual
 * fica documentado) e a privacidade. Cada teste cria as próprias reservas, conta só os pedidos delas no
 * [FnrhTestServer] e devolve a FNRH desligada (a base).
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
class FnrhNoShowSendAttackTest(
    @Autowired iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
    @Autowired hotelApi: HotelApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired sender: FnrhSubmissionSender,
    @Autowired store: FnrhSubmissionStore,
    @Autowired remoteIds: FnrhRemoteIds,
    @Autowired clock: MutableClock,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired tx: TransactionTemplate,
    @Autowired roomsApi: RoomsApi,
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
    private val frontdesk = FrontdeskTestSupport(iam, support, hotelApi, roomsApi, jsonMapper)
    private var running: CompletableFuture<FnrhSendRun>? = null

    /** Nenhuma rodada fica solta no servidor compartilhado, nem se o teste falhar antes do `get`. */
    @AfterEach
    fun settle() {
        gate = null
        runCatching { running?.get(RUN_SECONDS, TimeUnit.SECONDS) }
        running = null
        jdbc.resetFnrhSettings()
    }

    // --- Resultado incerto e repetição ----------------------------------------------------------------------------

    @Test
    fun `an UNKNOWN_OUTCOME no-show is repeated after the backoff and then marks the remote`() {
        val (id, remote) = registeredArrivingToday()
        val noShow = noShow(id)
        FnrhTestServer.stub("/reservas/$remote/noshow", 200, delayMs = TIMEOUT_DELAY_MS)

        sender.send(id) shouldBe FnrhSendRun(sent = 0, postponed = 1, failed = 0, invalid = 0)

        row(noShow).also {
            it.status shouldBe FnrhSubmissionStatus.PENDING
            it.attempts shouldBe 1
            it.lastFailure shouldBe FnrhSubmissionFailure.UNKNOWN_OUTCOME
            // O no-show não cria recurso: não marca escrita em curso (uma marca faria a reserva conciliar para sempre).
            it.inFlight shouldBe false
        }
        stored(id).noShow shouldBe false

        FnrhTestServer.stubMarkNoShow_v2_4(remote)
        sender.send(id) shouldBe FnrhSendRun(0, 0, 0, 0)
        advance(Duration.ofMinutes(1))
        sender.send(id) shouldBe FnrhSendRun(sent = 1, postponed = 0, failed = 0, invalid = 0)

        requests("POST", "/reservas/$remote/noshow") shouldHaveSize 2
        row(noShow).status shouldBe FnrhSubmissionStatus.SENT
        stored(id).also {
            it.noShow shouldBe true
            it.cancelled shouldBe false
        }
    }

    @Test
    fun `an UNKNOWN_OUTCOME no-show whose repetition is refused fails and the local remote stays not no-show`() {
        val (id, remote) = registeredArrivingToday()
        val noShow = noShow(id)
        FnrhTestServer.stub("/reservas/$remote/noshow", 200, delayMs = TIMEOUT_DELAY_MS)
        sender.send(id).postponed shouldBe 1
        // O Serpro gravou o no-show do primeiro pedido e recusa o repetido.
        FnrhTestServer.stub("/reservas/$remote/noshow", 422, """{"mensagem":"reserva em no-show"}""")
        advance(Duration.ofMinutes(1))

        sender.send(id) shouldBe FnrhSendRun(sent = 0, postponed = 0, failed = 1, invalid = 0)

        requests("POST", "/reservas/$remote/noshow") shouldHaveSize 2
        row(noShow).also {
            it.status shouldBe FnrhSubmissionStatus.FAILED
            it.attempts shouldBe 2
            it.lastFailure shouldBe FnrhSubmissionFailure.VALIDATION
            it.inFlight shouldBe false
        }
        stored(id).also {
            it.noShow shouldBe false
            it.cancelled shouldBe false
        }
        // FAILED segura a fila: nada sai de novo.
        advance(Duration.ofHours(2))
        sender.send(id) shouldBe FnrhSendRun(0, 0, 0, 0)
        requests("POST", "/reservas/$remote/noshow") shouldHaveSize 2
    }

    @Test
    fun `a manual retry of a refused no-show repeats the POST and then marks the remote`() {
        val (id, remote) = registeredArrivingToday()
        val noShow = noShow(id)
        FnrhTestServer.stub("/reservas/$remote/noshow", 422, """{"mensagem":"recusado"}""")
        sender.send(id).failed shouldBe 1

        tx.executeWithoutResult {
            val submission = checkNotNull(store.find(noShow))
            submission.retry(clock.instant())
            store.save(submission)
        }
        FnrhTestServer.stubMarkNoShow_v2_4(remote)
        sender.send(id) shouldBe FnrhSendRun(sent = 1, postponed = 0, failed = 0, invalid = 0)

        requests("POST", "/reservas/$remote/noshow") shouldHaveSize 2
        row(noShow).status shouldBe FnrhSubmissionStatus.SENT
        stored(id).noShow shouldBe true
    }

    // --- Integração desligada e submissão encerrada no meio -------------------------------------------------------

    @Test
    fun `turning the integration off during a run stops before the next no-show with nothing recorded`() {
        val (first, firstRemote) = registeredArrivingToday()
        val (second, secondRemote) = registeredArrivingToday()
        noShow(first)
        val secondNoShow = noShow(second)
        FnrhTestServer.stub("/reservas/$firstRemote/noshow", 200, delayMs = HOLD_MS)
        FnrhTestServer.stubMarkNoShow_v2_4(secondRemote)

        val run = held("/reservas/$firstRemote/noshow", { jdbc.resetFnrhSettings() }) { sender.send(first, second) }

        run shouldBe FnrhSendRun(sent = 1, postponed = 0, failed = 0, invalid = 0)
        stored(first).noShow shouldBe true
        requests("POST", "/reservas/$secondRemote/noshow").shouldBeEmpty()
        row(secondNoShow).also {
            it.status shouldBe FnrhSubmissionStatus.PENDING
            it.attempts shouldBe 0
            it.lastFailure.shouldBeNull()
        }
        stored(second).noShow shouldBe false

        // Religada, o no-show pendente sai.
        iam.enableFnrh()
        sender.send(second) shouldBe FnrhSendRun(sent = 1, postponed = 0, failed = 0, invalid = 0)
        requests("POST", "/reservas/$secondRemote/noshow") shouldHaveSize 1
        stored(second).noShow shouldBe true
    }

    @Test
    fun `a no-show closed while its POST is in flight stays closed and the remote records what Serpro got`() {
        val (id, remote) = registeredArrivingToday()
        val noShow = noShow(id)
        FnrhTestServer.stub("/reservas/$remote/noshow", 200, delayMs = HOLD_MS)

        val run = held("/reservas/$remote/noshow", { failAndDismiss(noShow) }) { sender.send(id) }

        // O recorder só aplica resultado a PENDING: nada conta, nada muda na submissão encerrada.
        run shouldBe FnrhSendRun(0, 0, 0, 0)
        requests("POST", "/reservas/$remote/noshow") shouldHaveSize 1
        row(noShow).also {
            it.status shouldBe FnrhSubmissionStatus.DISMISSED
            it.attempts shouldBe 1
            it.lastFailure shouldBe FnrhSubmissionFailure.VALIDATION
        }
        // O Serpro aceitou o no-show: a remota local diz isso, e a reativação não faria PUT.
        stored(id).also {
            it.noShow shouldBe true
            it.cancelled shouldBe false
        }
    }

    // --- Conciliação ----------------------------------------------------------------------------------------------

    @Test
    fun `a reconciliation that finds nothing sends the no-show without a write`() {
        val id = arrivingToday()
        dismissedInFlight(id)
        val noShow = noShow(id)
        iam.enableFnrh()
        FnrhTestServer.stubFindReservationByCode_v2_4(codeOf(id))
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())

        sender.send(id) shouldBe FnrhSendRun(sent = 1, postponed = 0, failed = 0, invalid = 0)

        requests("GET", findPath(id)) shouldHaveSize 1
        ownRequests(id).filter { it.method.value() != "GET" }.shouldBeEmpty()
        FnrhTestServer.requests().filter { it.url.endsWith("/noshow") }.shouldBeEmpty()
        row(noShow).status shouldBe FnrhSubmissionStatus.SENT
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldBeNull()
    }

    @Test
    fun `an ambiguous reconciliation holds the no-show with UNKNOWN_OUTCOME and makes no POST`() {
        val id = arrivingToday()
        dismissedInFlight(id)
        val noShow = noShow(id)
        iam.enableFnrh()
        val (one, other) = remoteId() to remoteId()
        FnrhTestServer.stubFindReservationByCode_v2_4(codeOf(id), one, other)
        FnrhTestServer.stubMarkNoShow_v2_4(one)
        FnrhTestServer.stubMarkNoShow_v2_4(other)

        sender.send(id) shouldBe FnrhSendRun(sent = 0, postponed = 1, failed = 0, invalid = 0)

        FnrhTestServer.requests().filter { it.method.value() == "POST" }.shouldBeEmpty()
        row(noShow).also {
            it.status shouldBe FnrhSubmissionStatus.PENDING
            it.attempts shouldBe 1
            it.lastFailure shouldBe FnrhSubmissionFailure.UNKNOWN_OUTCOME
        }
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldBeNull()
    }

    @Test
    fun `a registration that failed before the no-show is reconciled and then the remote is marked no-show`() {
        val id = arrivingToday()
        val register = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        iam.enableFnrh()
        FnrhTestServer.stubAny(503)
        sender.send(id).postponed shouldBe 1
        row(register.id).inFlight shouldBe true
        val noShow = noShow(id)
        val remote = remoteId()
        FnrhTestServer.stubFindReservationByCode_v2_4(codeOf(id), remote)
        FnrhTestServer.stubMarkNoShow_v2_4(remote)
        advance(Duration.ofMinutes(1))

        sender.send(id) shouldBe FnrhSendRun(sent = 2, postponed = 0, failed = 0, invalid = 0)

        // Só o POST /reservas da primeira tentativa (o 503); o registro concilia, e o no-show vai à remota achada.
        requests("POST", "/reservas") shouldHaveSize 1
        requests("GET", findPath(id)) shouldHaveSize 1
        requests("POST", "/reservas/$remote/noshow") shouldHaveSize 1
        row(register.id).also {
            it.status shouldBe FnrhSubmissionStatus.SENT
            it.inFlight shouldBe false
        }
        row(noShow).status shouldBe FnrhSubmissionStatus.SENT
        stored(id).also {
            it.remoteId shouldBe FnrhRemoteId(remote)
            it.noShow shouldBe true
            it.cancelled shouldBe false
        }
    }

    // --- Troca de ambiente ----------------------------------------------------------------------------------------

    @Test
    fun `a no-show pending when switching to production is sent there without a call`() {
        val (id, homologation) = registeredArrivingToday()
        val noShow = noShow(id)
        iam.enableFnrh("PRODUCTION")
        FnrhTestServer.stubMarkNoShow_v2_4(homologation)
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())

        sender.send(id) shouldBe FnrhSendRun(sent = 1, postponed = 0, failed = 0, invalid = 0)

        requests("POST", "/reservas/$homologation/noshow").shouldBeEmpty()
        ownRequests(id, homologation).filter { it.method.value() != "GET" } shouldHaveSize 1 // só o registro em H
        row(noShow).status shouldBe FnrhSubmissionStatus.SENT
        remoteIds.reservation(id, FnrhEnvironment.PRODUCTION).shouldBeNull()
        stored(id).noShow shouldBe false
    }

    @Test
    fun `a no-show sent in homologation does not hold in production, where the reactivation registers again`() {
        val (id, homologation) = registeredArrivingToday()
        noShow(id)
        FnrhTestServer.stubMarkNoShow_v2_4(homologation)
        sender.send(id).sent shouldBe 1
        stored(id).noShow shouldBe true
        val update = reactivate(id)
        iam.enableFnrh("PRODUCTION")
        val production = remoteId()
        FnrhTestServer.stubRegisterReservation_v2_4(production)
        FnrhTestServer.stubUpdateReservation_v2_4(homologation)

        sender.send(id) shouldBe FnrhSendRun(sent = 1, postponed = 0, failed = 0, invalid = 0)

        row(update).also {
            it.status shouldBe FnrhSubmissionStatus.SENT
            it.lastFailure.shouldBeNull()
        }
        requests("PUT", "/reservas/$homologation").shouldBeEmpty()
        requests("POST", "/reservas").filter { codeOf(id) in it.bodyAsString } shouldHaveSize 2
        remoteIds.reservation(id, FnrhEnvironment.PRODUCTION).shouldNotBeNull().also {
            it.remoteId shouldBe FnrhRemoteId(production)
            it.noShow shouldBe false
            it.cancelled shouldBe false
        }
        stored(id).noShow shouldBe true
    }

    // --- A remota nunca cancelada e no-show (CHECK remote_reservation_closed_ck) ----------------------------------

    @Test
    fun `a reactivated then cancelled reservation over a remote no-show sends the update and cancel with no call`() {
        val (id, remote) = registeredArrivingToday()
        noShow(id)
        FnrhTestServer.stubMarkNoShow_v2_4(remote)
        sender.send(id).sent shouldBe 1
        val update = reactivate(id)
        cancel(id)
        val cancellation = rows(id).single { it.operation == FnrhOperation.CANCEL_RESERVATION }.id
        FnrhTestServer.stubUpdateReservation_v2_4(remote)
        FnrhTestServer.stubCancelReservation_v2_4(remote)

        sender.send(id) shouldBe FnrhSendRun(sent = 2, postponed = 0, failed = 0, invalid = 0)

        requests("PUT", "/reservas/$remote").shouldBeEmpty()
        requests("POST", "/reservas/$remote/cancelar").shouldBeEmpty()
        row(update).status shouldBe FnrhSubmissionStatus.SENT
        row(cancellation).status shouldBe FnrhSubmissionStatus.SENT
        closedColumns(id) shouldBe (false to true)
    }

    @Test
    fun `a reservation marked no-show again after a reactivation sends neither the update nor the second no-show`() {
        val (id, remote) = registeredArrivingToday()
        noShow(id)
        FnrhTestServer.stubMarkNoShow_v2_4(remote)
        FnrhTestServer.stubUpdateReservation_v2_4(remote)
        sender.send(id).sent shouldBe 1
        val update = reactivate(id)
        val again = noShow(id)

        sender.send(id) shouldBe FnrhSendRun(sent = 2, postponed = 0, failed = 0, invalid = 0)

        requests("POST", "/reservas/$remote/noshow") shouldHaveSize 1
        requests("PUT", "/reservas/$remote").shouldBeEmpty()
        row(update).status shouldBe FnrhSubmissionStatus.SENT
        row(again).status shouldBe FnrhSubmissionStatus.SENT
        closedColumns(id) shouldBe (false to true)
    }

    @Test
    fun `a no-show after a sent cancel and a dismissed reactivation makes no call and keeps the remote cancelled`() {
        val (id, remote) = registeredArrivingToday()
        cancel(id)
        FnrhTestServer.stubCancelReservation_v2_4(remote)
        sender.send(id).sent shouldBe 1
        val update = reactivate(id)
        sender.send(id).failed shouldBe 1
        row(update).lastFailure shouldBe FnrhSubmissionFailure.REMOTE_CANCELLED
        dismiss(update)
        val noShow = noShow(id)
        FnrhTestServer.stubMarkNoShow_v2_4(remote)

        sender.send(id) shouldBe FnrhSendRun(sent = 1, postponed = 0, failed = 0, invalid = 0)

        requests("POST", "/reservas/$remote/noshow").shouldBeEmpty()
        row(noShow).status shouldBe FnrhSubmissionStatus.SENT
        closedColumns(id) shouldBe (true to false)
    }

    // --- Estado atual da reserva no passo do no-show ---------------------------------------------------------------

    /**
     * Pelo estado atual (D-E08-9; guarda de D-E14-3, ruling da Task 4): o no-show que ainda não saiu (Serpro fora)
     * quando a reserva é reativada não sai mais (o no-show remoto é irreversível), e a alteração da reativação segue
     * normal, com o `PUT` na remota não no-show.
     */
    @Test
    fun `a no-show still pending when the reservation is reactivated is not sent and the update goes through`() {
        val (id, remote) = registeredArrivingToday()
        val noShow = noShow(id)
        FnrhTestServer.stub("/reservas/$remote/noshow", 503)
        sender.send(id).postponed shouldBe 1
        val update = reactivate(id)
        checkNotNull(reservationsApi.reservation(ReservationId(id))).status shouldBe ReservationStatus.CONFIRMED
        // Esquece o pedido recusado antes da reativação: daqui em diante, nenhum `POST …/noshow`.
        FnrhTestServer.reset()
        FnrhTestServer.stubMarkNoShow_v2_4(remote)
        FnrhTestServer.stubUpdateReservation_v2_4(remote)
        advance(Duration.ofMinutes(1))

        sender.send(id) shouldBe FnrhSendRun(sent = 2, postponed = 0, failed = 0, invalid = 0)

        requests("POST", "/reservas/$remote/noshow").shouldBeEmpty()
        requests("PUT", "/reservas/$remote") shouldHaveSize 1
        row(noShow).status shouldBe FnrhSubmissionStatus.SENT
        row(update).also {
            it.status shouldBe FnrhSubmissionStatus.SENT
            it.lastFailure.shouldBeNull()
        }
        stored(id).noShow shouldBe false
        closedColumns(id) shouldBe (false to false)
    }

    // --- Fora de alcance (D-E14-4): o check-in sobre uma remota no-show -------------------------------------------

    /**
     * Documenta o comportamento atual, que D-E14-4 deixa sem guarda (como o `REMOTE_CANCELLED` da E08): o `CHECK_IN`
     * sobre uma remota no-show (depois de reativação e `dismiss`) envia a pessoa e o hóspede, e a recusa do Serpro vira
     * `FAILED`. Se a guarda for estendida ao check-in ("Decisões para o usuário"), este teste muda junto.
     */
    @Test
    fun `a check-in over a remote no-show is not guarded locally and fails on the refusal of Serpro`() {
        val cpf = newCpf()
        val id = checkedIn(fnrhGuest(cpf))
        val register = rows(id).single { it.operation == FnrhOperation.REGISTER_RESERVATION }.id
        val remote = remoteId()
        remoteIds.saveReservation(
            FnrhRemoteReservation(id, FnrhEnvironment.HOMOLOGATION, FnrhRemoteId(remote), null, false, noShow = true),
            register,
        )
        iam.enableFnrh()
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", cpf, DEFAULT_CPF)
        FnrhTestServer.stubRegisterPerson_v2_4("p-$remote", DEFAULT_CPF)
        FnrhTestServer.stub("/reservas/$remote/hospedes", 422, """{"mensagem":"reserva em no-show"}""")

        sender.send(id) shouldBe FnrhSendRun(sent = 1, postponed = 0, failed = 1, invalid = 0)

        requests("POST", "/reservas").filter { codeOf(id) in it.bodyAsString }.shouldBeEmpty()
        requests("POST", "/reservas/$remote/hospedes") shouldHaveSize 1
        rows(id).single { it.operation == FnrhOperation.CHECK_IN }.also {
            it.status shouldBe FnrhSubmissionStatus.FAILED
            it.lastFailure shouldBe FnrhSubmissionFailure.VALIDATION
        }
        closedColumns(id) shouldBe (false to true)
    }

    // --- Privacidade ----------------------------------------------------------------------------------------------

    @Test
    fun `the no-show keeps the link encrypted and out of the log, the call log and the trail, with no code`(
        output: CapturedOutput,
    ) {
        val secret = "tok${UuidV7.generate().toString().replace("-", "")}"
        val link = "https://precheckin.example/r/$secret"
        val (id, remote) = registeredArrivingToday(link)
        val noShow = noShow(id)
        val code = codeOf(id)
        FnrhTestServer.stubMarkNoShow_v2_4(remote)
        val already = output.all.length

        sender.send(id).sent shouldBe 1

        stored(id).also {
            it.noShow shouldBe true
            it.precheckinLink shouldBe link
        }
        jdbc
            .sql("SELECT precheckin_link FROM fnrh.remote_reservation WHERE reservation_id = :id")
            .param("id", id)
            .query(String::class.java)
            .single() shouldNotContain secret
        output.all.substring(already).also {
            it shouldNotContain secret
            it shouldNotContain code
        }
        val calls =
            jdbc
                .sql(
                    "SELECT operation, remote_id, c::text AS line FROM fnrh.integration_call c " +
                        "WHERE correlation_ref = :ref",
                ).param("ref", noShow.toString())
                .query { rs, _ -> Triple(rs.getString("operation"), rs.getString("remote_id"), rs.getString("line")) }
                .list()
        calls.map { it.first to it.second } shouldBe listOf("markNoShow" to remote)
        calls.single().third.also {
            it shouldNotContain secret
            it shouldNotContain code
        }
        support.auditEntries(noShow).shouldNotBeEmptyAndClean(secret, code)
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private fun List<Map<String, Any?>>.shouldNotBeEmptyAndClean(vararg secrets: String) {
        isEmpty() shouldBe false
        forEach { entry ->
            secrets.forEach {
                (entry["before"] as String?).orEmpty() shouldNotContain it
                (entry["after"] as String?).orEmpty() shouldNotContain it
            }
        }
    }

    private fun stored(reservation: UUID): FnrhRemoteReservation =
        remoteIds.reservation(reservation, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull()

    /** As colunas `cancelled` e `no_show` da remota em homologação, lidas do banco. */
    private fun closedColumns(reservation: UUID): Pair<Boolean, Boolean> =
        jdbc
            .sql(
                "SELECT cancelled, no_show FROM fnrh.remote_reservation " +
                    "WHERE reservation_id = :id AND environment = 'HOMOLOGATION'",
            ).param("id", reservation)
            .query { rs, _ -> rs.getBoolean("cancelled") to rs.getBoolean("no_show") }
            .single()

    /** Uma reserva chegando hoje, registrada no Serpro (homologação) com a FNRH ligada; devolve o id e o remoto. */
    private fun registeredArrivingToday(link: String? = null): Pair<UUID, String> {
        val id = arrivingToday()
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote, link)
        sender.send(id).sent shouldBe 1
        return id to remote
    }

    /** Uma reserva confirmada chegando hoje (o no-show só vale até a data operacional), com o registro na fila. */
    private fun arrivingToday(): UUID {
        val (type, _) = support.inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(PRICED_DAYS))
        val created =
            support.created(
                support.booking(support.guest(), support.reservation(type, plan.id, today, today.plusDays(2))),
            )["reservations"][0]
        val id = UUID.fromString(created["id"].asString())
        awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        return id
    }

    /**
     * Marca a reserva como no-show (como o fechamento) e grava o `NO_SHOW` com a chave do evento; devolve o id da
     * submissão desta marcação (a mais nova). Não exige a gravação: o listener da Task 5 pode ter gravado a mesma
     * chave.
     */
    private fun noShow(reservation: UUID): UUID {
        val marked = checkNotNull(tx.execute { reservationsApi.markNoShow(ReservationId(reservation)) })
        marked.status shouldBe ReservationStatus.NO_SHOW
        store.recordIfAbsent(
            FnrhSubmission.create(
                reservationId = reservation,
                reservationCode = codeOf(reservation),
                operation = FnrhOperation.NO_SHOW,
                eventKey = "no-show:v${marked.version}",
                causalVersion = marked.version,
                performedBy = null,
                occurredAt = clock.instant(),
                now = clock.instant(),
            ),
        )
        return rows(reservation).last { it.operation == FnrhOperation.NO_SHOW }.id
    }

    /** Reativa pela API (o listener grava o `UPDATE_RESERVATION`); devolve o id do `UPDATE` mais novo. */
    private fun reactivate(reservation: UUID): UUID {
        val before = rows(reservation).count { it.operation == FnrhOperation.UPDATE_RESERVATION }
        support.reactivate(reservation).also { with(support.billing) { it.expect(200) } }
        await()
            .atMost(Duration.ofSeconds(AWAIT_SECONDS))
            .until { rows(reservation).count { it.operation == FnrhOperation.UPDATE_RESERVATION } > before }
        return rows(reservation).last { it.operation == FnrhOperation.UPDATE_RESERVATION }.id
    }

    private fun dismiss(submission: UUID) {
        tx.executeWithoutResult {
            val failed = checkNotNull(store.find(submission))
            failed.dismiss()
            store.save(failed)
        }
    }

    /** A submissão `PENDING` levada a `FAILED` e encerrada com `dismiss`, como o operador faria. */
    private fun failAndDismiss(submission: UUID) {
        tx.executeWithoutResult {
            val open = checkNotNull(store.find(submission))
            open.recordFailure(FnrhSubmissionFailure.VALIDATION, clock.instant(), FnrhRetryPolicy())
            open.dismiss()
            store.save(open)
        }
    }

    /** O registro com a escrita em curso marcada, recusado e encerrado com `dismiss`; devolve o id dele. */
    private fun dismissedInFlight(reservation: UUID): UUID {
        val register = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION)
        store.markInFlight(register.id)
        failAndDismiss(register.id)
        return register.id
    }

    /** Os pedidos ao [FnrhTestServer] que tocam a reserva: o código no caminho ou no corpo, ou o id remoto. */
    private fun ownRequests(
        reservation: UUID,
        remote: String? = null,
    ) = codeOf(reservation).let { code ->
        FnrhTestServer.requests().filter {
            code in it.url || code in it.bodyAsString || (remote != null && remote in it.url)
        }
    }

    /**
     * Roda [send] em outra thread, espera o `POST` em [path] chegar ao WireMock (preso por [HOLD_MS], abaixo do
     * `read-timeout` de teste), faz [meanwhile] e devolve o resultado da rodada.
     */
    private fun held(
        path: String,
        meanwhile: () -> Unit,
        send: () -> FnrhSendRun,
    ): FnrhSendRun {
        val received = CountDownLatch(1)
        listen()
        gate = Gate(FnrhTestServer.BASE_PATH + path, received)
        val future = CompletableFuture.supplyAsync { send() }
        running = future
        received.await(AWAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
        meanwhile()
        return future.get(RUN_SECONDS, TimeUnit.SECONDS)
    }

    private fun FnrhSubmissionSender.send(vararg reservations: UUID): FnrhSendRun = sendDueFor(*reservations)

    private fun fnrhGuest(cpf: String): UUID =
        UUID.fromString(
            support.guests
                .createGuest(
                    support.guests.createBody(
                        profile =
                            support.guests.profileBody(
                                extra = mapOf("birthDate" to LocalDate.of(1988, 3, 14), "nationality" to "BR"),
                            ),
                        documents = listOf(support.guests.document("CPF", cpf)),
                        sensitiveData = mapOf("raceColor" to "BROWN"),
                    ),
                )["id"]
                .asString(),
        )

    /** Uma reserva de hoje com o check-in do [guest] feito, com o registro e o check-in já gravados pelo listener. */
    private fun checkedIn(guest: UUID): UUID {
        val auth =
            ReservationsTestSupport
                .Operator(iam.createUserWith(frontdesk.grant(FrontdeskPermissions.STAYS_CHECK_IN)))
                .auth
        val stay = frontdesk.stay()
        val booked = stay.book(guest)
        stay.ready()
        val guests =
            listOf(
                mapOf(
                    "guestId" to guest,
                    "primary" to true,
                    "travelReason" to "LAZER_FERIAS",
                    "transportMode" to "AUTOMOVEL",
                    "minorAuthorization" to null,
                ),
            )
        with(support.billing) {
            call(POST, "/api/v1/reservations/${booked.id}/check-in", auth, mapOf("guests" to guests)).expect(200)
        }
        awaitSubmission(booked.id, FnrhOperation.REGISTER_RESERVATION)
        awaitSubmission(booked.id, FnrhOperation.CHECK_IN)
        return booked.id
    }

    /** O pedido que a rodada espera ver chegar ao WireMock antes de [held] agir. */
    private class Gate(
        val url: String,
        val received: CountDownLatch,
    )

    private companion object {
        const val DEFAULT_CPF = "52998224725"

        /** Abaixo do `read-timeout` de teste (2 s): a chamada presa termina com sucesso. */
        const val HOLD_MS = 1_000
        const val AWAIT_SECONDS = 10L
        const val RUN_SECONDS = 30L

        /** O pedido que o ouvinte espera; só um ouvinte desta classe é registrado por JVM e ele consulta este campo. */
        @Volatile
        var gate: Gate? = null

        private val listener: Unit by lazy {
            FnrhTestServer.server.addMockServiceRequestListener { request, _ ->
                gate
                    ?.takeIf { request.method.value() == "POST" && request.url == it.url }
                    ?.received
                    ?.countDown()
            }
        }

        /** Registra o ouvinte uma única vez no servidor compartilhado (o WireMock não o remove). */
        fun listen() = listener
    }
}
