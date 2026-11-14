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
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.enableFnrh
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID

/**
 * O envio do no-show à FNRH (M6 E14, Task 4; D-E14-3 e D-E14-4): o passo `NO_SHOW` faz o `POST /reservas/{id}/noshow`
 * pela remota guardada ou conciliada, espera o registro, não chama sem remota nem numa remota já cancelada ou no-show
 * e, recusado, falha com alerta; a remota no-show não reativa (`UPDATE_RESERVATION` falha com `REMOTE_NO_SHOW`) nem é
 * cancelada. Cada teste cria a própria reserva (chegada hoje, para o no-show), grava o `NO_SHOW` pelo store com a
 * chave do evento e conta só os pedidos da própria reserva.
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    InventoryTestConfiguration::class,
    MutableClockConfiguration::class,
    FnrhShortReadTimeoutConfiguration::class,
)
@Suppress("LongParameterList") // as portas e os apoios do fluxo real
class FnrhNoShowSendTest(
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
    @Test
    fun `a registered reservation is marked no-show once`() {
        val id = arrivingToday()
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote, "https://precheckin.example/r/$remote")
        sender.send(id).sent shouldBe 1
        val noShow = noShow(id)
        FnrhTestServer.stubMarkNoShow_v2_4(remote)

        sender.send(id) shouldBe FnrhSendRun(sent = 1, postponed = 0, failed = 0, invalid = 0)

        requests("POST", "/reservas/$remote/noshow") shouldHaveSize 1
        row(noShow).also {
            it.status shouldBe FnrhSubmissionStatus.SENT
            it.inFlight shouldBe false
        }
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().also {
            it.remoteId shouldBe FnrhRemoteId(remote)
            it.noShow shouldBe true
            it.cancelled shouldBe false
            it.precheckinLink shouldBe "https://precheckin.example/r/$remote"
        }

        // Enviado, não sai de novo.
        sender.send(id) shouldBe FnrhSendRun(0, 0, 0, 0)
        requests("POST", "/reservas/$remote/noshow") shouldHaveSize 1
    }

    @Test
    fun `no-show waits for the reservation registration`() {
        val id = arrivingToday()
        val register = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        iam.enableFnrh()
        FnrhTestServer.stubAny(503)
        sender.send(id).postponed shouldBe 1
        row(register.id).lastFailure shouldBe FnrhSubmissionFailure.SERVER_ERROR
        val noShow = noShow(id)

        // O registro ainda não venceu: nada sai.
        sender.send(id) shouldBe FnrhSendRun(0, 0, 0, 0)
        // Vencido, o registro (com a escrita em curso) falha de novo e segura o no-show.
        advance(Duration.ofMinutes(1))
        sender.send(id) shouldBe FnrhSendRun(sent = 0, postponed = 1, failed = 0, invalid = 0)

        FnrhTestServer.requests().filter { it.url.endsWith("/noshow") }.shouldBeEmpty()
        row(register.id).attempts shouldBe 2
        row(noShow).also {
            it.status shouldBe FnrhSubmissionStatus.PENDING
            it.attempts shouldBe 0
            it.lastFailure.shouldBeNull()
        }
    }

    @Test
    fun `no-show without remote reservation is sent without a call`() {
        val id = arrivingToday()
        val noShow = noShow(id)
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())

        sender.send(id) shouldBe FnrhSendRun(sent = 2, postponed = 0, failed = 0, invalid = 0)

        ownRequests(id).shouldBeEmpty()
        row(noShow).status shouldBe FnrhSubmissionStatus.SENT
        rows(id).map { it.status }.toSet() shouldBe setOf(FnrhSubmissionStatus.SENT)
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldBeNull()
    }

    @Test
    fun `no-show with a write in flight reconciles first`() {
        val id = arrivingToday()
        val register = dismissedInFlight(id)
        val noShow = noShow(id)
        iam.enableFnrh()
        val remote = remoteId()
        FnrhTestServer.stubFindReservationByCode_v2_4(codeOf(id), remote)
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())
        FnrhTestServer.stubMarkNoShow_v2_4(remote)

        sender.send(id) shouldBe FnrhSendRun(sent = 1, postponed = 0, failed = 0, invalid = 0)

        requests("GET", findPath(id)) shouldHaveSize 1
        requests("POST", "/reservas").shouldBeEmpty()
        requests("POST", "/reservas/$remote/noshow") shouldHaveSize 1
        row(noShow).also {
            it.status shouldBe FnrhSubmissionStatus.SENT
            it.inFlight shouldBe false
        }
        row(register).inFlight shouldBe true
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().also {
            it.remoteId shouldBe FnrhRemoteId(remote)
            it.noShow shouldBe true
            it.cancelled shouldBe false
            it.precheckinLink.shouldBeNull()
        }
    }

    @Test
    fun `no-show of a remote already cancelled makes no call`() {
        noShowOfAClosedRemote(cancelled = true, noShow = false)
    }

    @Test
    fun `no-show of a remote already no-show makes no call`() {
        noShowOfAClosedRemote(cancelled = false, noShow = true)
    }

    @Test
    fun `a refused no-show fails with an alert`() {
        val manager =
            iam.createUserWith(
                TestGrant(FnrhPermissions.permissions.single { it.code == FnrhPermissions.SUBMISSIONS_MANAGE }, ALL),
            )
        val id = arrivingToday()
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        sender.send(id).sent shouldBe 1
        val noShow = noShow(id)
        FnrhTestServer.stub("/reservas/$remote/noshow", 422, """{"mensagem":"recusado"}""")

        sender.send(id) shouldBe FnrhSendRun(sent = 0, postponed = 0, failed = 1, invalid = 0)

        requests("POST", "/reservas/$remote/noshow") shouldHaveSize 1
        row(noShow).also {
            it.status shouldBe FnrhSubmissionStatus.FAILED
            it.lastFailure shouldBe FnrhSubmissionFailure.VALIDATION
        }
        // Só o código da reserva e rótulos fixos: nenhum dado pessoal.
        alertBodies(id, manager.id.value) shouldBe listOf("Reserva ${codeOf(id)}: no-show — falhou")
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().noShow shouldBe false
    }

    @Test
    fun `update after a remote no-show fails locally`() {
        val id = arrivingToday()
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        FnrhTestServer.stubMarkNoShow_v2_4(remote)
        FnrhTestServer.stubUpdateReservation_v2_4(remote)
        sender.send(id).sent shouldBe 1
        noShow(id)
        sender.send(id).sent shouldBe 1

        support.reactivate(id).also { with(support.billing) { it.expect(200) } }
        val update = awaitSubmission(id, FnrhOperation.UPDATE_RESERVATION)

        sender.send(id) shouldBe FnrhSendRun(sent = 0, postponed = 0, failed = 1, invalid = 0)

        requests("PUT", "/reservas/$remote").shouldBeEmpty()
        row(update.id).also {
            it.status shouldBe FnrhSubmissionStatus.FAILED
            it.lastFailure shouldBe FnrhSubmissionFailure.REMOTE_NO_SHOW
        }
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().noShow shouldBe true
    }

    @Test
    fun `cancel after a remote no-show makes no call`() {
        val id = reservation()
        val register = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        val remote = remoteId()
        remoteIds.saveReservation(remoteReservation(id, remote, cancelled = false, noShow = true), register.id)
        cancel(id)
        val cancellation = rows(id).single { it.operation == FnrhOperation.CANCEL_RESERVATION }
        iam.enableFnrh()
        FnrhTestServer.stubCancelReservation_v2_4(remote)

        sender.send(id) shouldBe FnrhSendRun(sent = 2, postponed = 0, failed = 0, invalid = 0)

        ownRequests(id, remote).shouldBeEmpty()
        row(cancellation.id).status shouldBe FnrhSubmissionStatus.SENT
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().also {
            it.noShow shouldBe true
            it.cancelled shouldBe false
        }
    }

    @Test
    fun `remote reservation round-trips the no-show flag`() {
        val id = reservation()
        val register = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        val remote = remoteId()

        remoteIds.saveReservation(remoteReservation(id, remote, cancelled = false, noShow = false), register.id)
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().noShow shouldBe false

        // Pelo `ON CONFLICT … DO UPDATE`.
        remoteIds.saveReservation(remoteReservation(id, remote, cancelled = false, noShow = true), register.id)
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().noShow shouldBe true

        val relinked = remoteReservation(id, remote, cancelled = false, noShow = true, link = "https://p.example/2")
        remoteIds.saveReservation(relinked, register.id)
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().also {
            it shouldBe relinked
            it.noShow shouldBe true
            it.precheckinLink shouldBe "https://p.example/2"
        }
        jdbc
            .sql("SELECT no_show FROM fnrh.remote_reservation WHERE reservation_id = :id")
            .param("id", id)
            .query(Boolean::class.java)
            .single() shouldBe true
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private fun noShowOfAClosedRemote(
        cancelled: Boolean,
        noShow: Boolean,
    ) {
        val id = arrivingToday()
        val register = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        val remote = remoteId()
        remoteIds.saveReservation(remoteReservation(id, remote, cancelled, noShow), register.id)
        val submission = noShow(id)
        iam.enableFnrh()
        FnrhTestServer.stubMarkNoShow_v2_4(remote)

        sender.send(id) shouldBe FnrhSendRun(sent = 2, postponed = 0, failed = 0, invalid = 0)

        ownRequests(id, remote).shouldBeEmpty()
        row(submission).status shouldBe FnrhSubmissionStatus.SENT
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().also {
            it.cancelled shouldBe cancelled
            it.noShow shouldBe noShow
        }
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
     * Marca a reserva como no-show (como o fechamento) e grava o `NO_SHOW` com a chave do evento
     * `ReservationMarkedNoShow`; devolve o id da submissão. Não exige a gravação: o listener do evento pode ter gravado
     * antes a mesma chave.
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
        return rows(reservation).single { it.operation == FnrhOperation.NO_SHOW }.id
    }

    /** O registro com a escrita em curso marcada, recusado e encerrado com `dismiss`; devolve o id dele. */
    private fun dismissedInFlight(reservation: UUID): UUID {
        val register = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION)
        store.markInFlight(register.id)
        tx.executeWithoutResult {
            val failed = checkNotNull(store.find(register.id))
            failed.recordFailure(FnrhSubmissionFailure.VALIDATION, clock.instant(), FnrhRetryPolicy())
            failed.dismiss()
            store.save(failed)
        }
        return register.id
    }

    private fun remoteReservation(
        reservation: UUID,
        remote: String,
        cancelled: Boolean,
        noShow: Boolean,
        link: String? = null,
    ) = FnrhRemoteReservation(
        reservation,
        FnrhEnvironment.HOMOLOGATION,
        FnrhRemoteId(remote),
        link,
        cancelled = cancelled,
        noShow = noShow,
    )

    /** Os pedidos ao [FnrhTestServer] que tocam a reserva: o código no caminho ou no corpo, ou o id remoto. */
    private fun ownRequests(
        reservation: UUID,
        remote: String? = null,
    ) = codeOf(reservation).let { code ->
        FnrhTestServer.requests().filter {
            code in it.url || code in it.bodyAsString || (remote != null && remote in it.url)
        }
    }

    private fun alertBodies(
        reservation: UUID,
        recipient: UUID,
    ): List<String> =
        jdbc
            .sql(
                "SELECT body FROM notifications.notification WHERE type = 'notifications.integration-failure' " +
                    "AND recipient_id = :recipient AND body LIKE :code ORDER BY id",
            ).param("recipient", recipient)
            .param("code", "%${codeOf(reservation)}%")
            .query { rs, _ -> rs.getString("body") }
            .list()
}
