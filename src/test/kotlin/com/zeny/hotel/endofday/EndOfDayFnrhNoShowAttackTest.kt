package com.zeny.hotel.endofday

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.application.FnrhRemoteIds
import com.zeny.hotel.fnrh.application.FnrhSendRun
import com.zeny.hotel.fnrh.application.FnrhSubmissionSender
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.fnrh.domain.FnrhSubmissionFailure
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationMarkedNoShow
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import com.zeny.hotel.testsupport.FnrhTestConfiguration
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.enableFnrh
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * O ataque ao no-show do fechamento real na FNRH (M6 E14, Task 5; RN-22, D-E14-2): o fechamento retomado não grava nem
 * publica o no-show duas vezes, e a submissão leva a versão, o autor e o instante do histórico; com o registro da
 * reserva ainda pendente, o no-show não sai antes dele; com a FNRH ligada e credenciais que o Serpro recusa, o
 * fechamento conclui e o no-show espera. Mesmo contexto do [EndOfDayFnrhNoShowTest] (banco isolado): cada teste
 * termina com [EndOfDayTestRuns.settle], devolve o relógio e a configuração da FNRH e olha só os próprios dados e os
 * pedidos da própria reserva no [FnrhTestServer].
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class, FnrhTestConfiguration::class)
@Suppress("LongParameterList") // os apoios do contexto
class EndOfDayFnrhNoShowAttackTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val sender: FnrhSubmissionSender,
    @Autowired private val remoteIds: FnrhRemoteIds,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val probe: BillingPostProbe,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private var advanced: Duration = Duration.ZERO

    /** Uma linha da fila, só com o que os testes conferem. */
    private data class Row(
        val id: UUID,
        val operation: FnrhOperation,
        val eventKey: String,
        val causalVersion: Long,
        val performedBy: UUID?,
        val occurredAt: Instant,
        val status: FnrhSubmissionStatus,
        val attempts: Int,
        val lastFailure: FnrhSubmissionFailure?,
    )

    /** A entrada `NO_SHOW` do histórico da reserva: o que o evento leva (versão, autor e instante). */
    private data class History(
        val version: Long,
        val actor: UUID?,
        val occurredAt: Instant,
    )

    /** As colunas de `fnrh.settings` que os testes mudam, para devolvê-las no `finally`. */
    private data class Settings(
        val environment: String,
        val username: String?,
        val password: String?,
        val defaultCpf: String?,
        val enabled: Boolean,
    )

    @Test
    fun `a resumed end of day records and publishes the no-show once per reservation with the history fields`() =
        isolated {
            val date = hotel.currentBusinessDate()
            val (type, _) = support.inventory.roomTypeWithRooms(2, date)
            val plan = support.plan(listOf(type), cancellationBody = fixedPenalty())
            support.setPrices(plan.id, listOf(type), date, date.plusDays(PRICED_DAYS))
            val marked = created(type, plan.id, date)
            val failing = created(type, plan.id, date)
            awaitRow(marked.id(), FnrhOperation.REGISTER_RESERVATION)
            awaitRow(failing.id(), FnrhOperation.REGISTER_RESERVATION)
            probe.failOn(FolioId(failing.folio()))

            val run = EndOfDayTestRuns.closeCurrentDate(context)

            statusOf(run) shouldBe "FAILED"
            support.status(marked.id()) shouldBe "NO_SHOW"
            support.status(failing.id()) shouldBe "CONFIRMED"
            awaitRow(marked.id(), FnrhOperation.NO_SHOW)

            // A retomada (resume + execute) leva a segunda a no-show e passa pela primeira de novo.
            EndOfDayTestRuns.settle(context)

            support.status(failing.id()) shouldBe "NO_SHOW"
            listOf(marked.id(), failing.id()).forEach { id ->
                awaitRow(id, FnrhOperation.NO_SHOW)
                await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(WAIT_SECONDS)).until {
                    noShows(id).size == 1
                }
                val history = noShowHistory(id)
                noShows(id).single().also {
                    it.eventKey shouldBe "no-show:v${history.version}"
                    it.causalVersion shouldBe history.version
                    it.performedBy shouldBe history.actor
                    it.occurredAt shouldBe history.occurredAt
                    it.status shouldBe FnrhSubmissionStatus.PENDING
                }
                support.lifecyclePublications(
                    ReservationMarkedNoShow::class.java,
                    id,
                    listener = "FnrhSubmissionListeners",
                ) shouldHaveSize 1
            }
        }

    @Test
    fun `with the registration still pending the end of day no-show does not go out before it`() =
        isolated {
            val date = hotel.currentBusinessDate()
            val id = arrivingOn(date)
            val code = codeOf(id)
            val remote = "r-${UuidV7.generate()}"
            iam.enableFnrh()
            FnrhTestServer.stub("/reservas", status = SERVICE_UNAVAILABLE)
            sender.sendDueFor(id) shouldBe FnrhSendRun(sent = 0, postponed = 1, failed = 0, invalid = 0)

            val run = EndOfDayTestRuns.closeCurrentDate(context)

            statusOf(run) shouldBe "COMPLETED"
            support.status(id) shouldBe "NO_SHOW"
            val noShow = awaitRow(id, FnrhOperation.NO_SHOW)
            // O registro ainda não venceu e segura a fila: o no-show não sai, nem é tentado.
            sender.sendDueFor(id) shouldBe FnrhSendRun(0, 0, 0, 0)
            row(noShow.id).attempts shouldBe 0
            ownRequests(code, remote).map { it.first } shouldContainExactly listOf("POST /reservas")

            // Vencido, o registro (escrita em curso) concilia e só então o no-show sai, pela remota achada.
            FnrhTestServer.stubFindReservationByCode_v2_4(code, remote)
            FnrhTestServer.stubMarkNoShow_v2_4(remote)
            advance(Duration.ofMinutes(2))
            sender.sendDueFor(id) shouldBe FnrhSendRun(sent = 2, postponed = 0, failed = 0, invalid = 0)

            ownRequests(code, remote).map { it.first } shouldContainExactly
                listOf(
                    "POST /reservas",
                    "GET /reservas?page_number=1&codigo_reserva=${URLEncoder.encode(code, Charsets.UTF_8)}",
                    "POST /reservas/$remote/noshow",
                )
            row(noShow.id).status shouldBe FnrhSubmissionStatus.SENT
            remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().also {
                it.remoteId shouldBe FnrhRemoteId(remote)
                it.noShow shouldBe true
            }
        }

    @Test
    fun `the end of day completes with the integration on and credentials the Serpro refuses`() =
        isolated {
            val date = hotel.currentBusinessDate()
            val id = arrivingOn(date)
            val code = codeOf(id)
            iam.enableFnrh()
            FnrhTestServer.stub("/reservas", status = UNAUTHORIZED)
            sender.sendDueFor(id) shouldBe FnrhSendRun(sent = 0, postponed = 0, failed = 1, invalid = 0)

            val run = EndOfDayTestRuns.closeCurrentDate(context)

            statusOf(run) shouldBe "COMPLETED"
            support.status(id) shouldBe "NO_SHOW"
            val noShow = awaitRow(id, FnrhOperation.NO_SHOW)
            noShow.status shouldBe FnrhSubmissionStatus.PENDING
            // O registro FAILED segura a fila: o no-show espera retry ou dismiss, sem chamada.
            advance(Duration.ofMinutes(2))
            sender.sendDueFor(id) shouldBe FnrhSendRun(0, 0, 0, 0)
            rows(id).single { it.operation == FnrhOperation.REGISTER_RESERVATION }.also {
                it.status shouldBe FnrhSubmissionStatus.FAILED
                it.lastFailure shouldBe FnrhSubmissionFailure.UNAUTHORIZED
            }
            row(noShow.id).also {
                it.status shouldBe FnrhSubmissionStatus.PENDING
                it.attempts shouldBe 0
            }
            ownRequests(code, null).map { it.first } shouldContainExactly listOf("POST /reservas")
        }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    /**
     * A regra do banco isolado em volta do teste: zera o WireMock e, no `finally`, [EndOfDayTestRuns.settle], o relógio
     * de volta exatamente o que o teste avançou e a configuração original da FNRH.
     */
    private fun isolated(block: () -> Unit) {
        val original = settings()
        FnrhTestServer.reset()
        try {
            block()
        } finally {
            try {
                EndOfDayTestRuns.settle(context)
            } finally {
                clock.advance(advanced.negated())
                advanced = Duration.ZERO
                restore(original)
            }
        }
    }

    private fun advance(duration: Duration) {
        clock.advance(duration)
        advanced = advanced.plus(duration)
    }

    private fun fixedPenalty() =
        support.rates.cancellationBody(
            deadlineHours = 8760,
            penaltyType = "FIXED_AMOUNT",
            penaltyNights = null,
            penaltyAmount = "150.00",
        )

    private fun created(
        type: UUID,
        plan: UUID,
        arrival: LocalDate,
    ): JsonNode =
        support.created(
            support.booking(support.guest(), support.reservation(type, plan, arrival, arrival.plusDays(2))),
        )["reservations"][0]

    private fun codeOf(reservation: UUID): String =
        checkNotNull(reservationsApi.reservation(ReservationId(reservation))).code.toString()

    private fun JsonNode.id(): UUID = UUID.fromString(this["id"].asString())

    private fun JsonNode.folio(): UUID = UUID.fromString(this["folioId"].asString())

    /** Uma reserva confirmada com chegada em [date] (o fechamento dela a torna no-show), com o registro na fila. */
    private fun arrivingOn(date: LocalDate): UUID {
        val (type, _) = support.inventory.roomTypeWithRooms(1, date)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), date, date.plusDays(PRICED_DAYS))
        val id = created(type, plan.id, date).id()
        awaitRow(id, FnrhOperation.REGISTER_RESERVATION)
        return id
    }

    private fun rows(reservation: UUID): List<Row> =
        jdbc
            .sql(
                "SELECT id, operation, event_key, causal_version, performed_by, occurred_at, status, attempts, " +
                    "last_failure FROM fnrh.submission WHERE reservation_id = :id ORDER BY sequence",
            ).param("id", reservation)
            .query { rs, _ ->
                Row(
                    rs.getObject("id", UUID::class.java),
                    FnrhOperation.valueOf(rs.getString("operation")),
                    rs.getString("event_key"),
                    rs.getLong("causal_version"),
                    rs.getObject("performed_by", UUID::class.java),
                    rs.getTimestamp("occurred_at").toInstant(),
                    FnrhSubmissionStatus.valueOf(rs.getString("status")),
                    rs.getInt("attempts"),
                    rs.getString("last_failure")?.let(FnrhSubmissionFailure::valueOf),
                )
            }.list()

    private fun noShows(reservation: UUID) = rows(reservation).filter { it.operation == FnrhOperation.NO_SHOW }

    private fun row(id: UUID): Row =
        jdbc
            .sql("SELECT reservation_id FROM fnrh.submission WHERE id = :id")
            .param("id", id)
            .query(UUID::class.java)
            .single()
            .let { reservation -> rows(reservation).single { it.id == id } }

    /** Espera o listener gravar a submissão da [operation] da reserva e a devolve. */
    private fun awaitRow(
        reservation: UUID,
        operation: FnrhOperation,
    ): Row {
        await().atMost(Duration.ofSeconds(WAIT_SECONDS)).pollInterval(Duration.ofMillis(POLL_MILLIS)).until {
            rows(reservation).any { it.operation == operation }
        }
        return rows(reservation).single { it.operation == operation }
    }

    private fun noShowHistory(reservation: UUID): History =
        jdbc
            .sql(
                "SELECT version, actor_id, occurred_at FROM reservations.reservation_history " +
                    "WHERE reservation_id = :id AND kind = 'NO_SHOW'",
            ).param("id", reservation)
            .query { rs, _ ->
                History(
                    rs.getLong("version"),
                    rs.getObject("actor_id", UUID::class.java),
                    rs.getTimestamp("occurred_at").toInstant(),
                )
            }.single()

    /**
     * Os pedidos ao [FnrhTestServer] que tocam a reserva (o código no caminho ou no corpo, ou o id remoto no caminho),
     * na ordem em que chegaram, como `MÉTODO caminho` (sem o prefixo da API) e o instante.
     */
    private fun ownRequests(
        code: String,
        remote: String?,
    ): List<Pair<String, Instant>> {
        val encoded = URLEncoder.encode(code, Charsets.UTF_8)
        return FnrhTestServer
            .requests()
            .filter {
                code in it.url || encoded in it.url || code in it.bodyAsString || (remote != null && remote in it.url)
            }.map {
                "${it.method.value()} ${it.url.removePrefix(FnrhTestServer.BASE_PATH)}" to it.loggedDate.toInstant()
            }.sortedBy { it.second }
    }

    private fun statusOf(run: EndOfDayRunId): String =
        jdbc
            .sql("SELECT status FROM endofday.run WHERE id = :id")
            .param("id", run.value)
            .query(String::class.java)
            .single()

    private fun settings(): Settings =
        jdbc
            .sql("SELECT environment, username, password, default_cpf, enabled FROM fnrh.settings")
            .query { rs, _ ->
                Settings(
                    rs.getString("environment"),
                    rs.getString("username"),
                    rs.getString("password"),
                    rs.getString("default_cpf"),
                    rs.getBoolean("enabled"),
                )
            }.single()

    private fun restore(original: Settings) {
        jdbc
            .sql(
                "UPDATE fnrh.settings SET environment = :environment, username = :username, password = :password, " +
                    "default_cpf = :cpf, enabled = :enabled",
            ).param("environment", original.environment)
            .param("username", original.username)
            .param("password", original.password)
            .param("cpf", original.defaultCpf)
            .param("enabled", original.enabled)
            .update() shouldBe 1
    }

    private companion object {
        const val PRICED_DAYS = 10L
        const val WAIT_SECONDS = 30L
        const val POLL_MILLIS = 50L
        const val SERVICE_UNAVAILABLE = 503
        const val UNAUTHORIZED = 401
    }
}
