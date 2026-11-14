package com.zeny.hotel.endofday

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
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import com.zeny.hotel.testsupport.FnrhTestConfiguration
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.enableFnrh
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
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * O fechamento real com o Serpro fora do ar (M6 E14, Task 5; RN-22): a etapa `no-show` marca a reserva, o listener
 * grava o `NO_SHOW` e o fechamento conclui; o envio recebe `503` e a submissão segue pendente, para nova tentativa. No
 * banco isolado: a FNRH é ligada só aqui e volta à configuração original no `finally`, com [EndOfDayTestRuns.settle]
 * e o relógio devolvido; as conferências olham só a reserva do teste e os pedidos dela no [FnrhTestServer].
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class, FnrhTestConfiguration::class)
@Suppress("LongParameterList") // os apoios do contexto
class EndOfDayFnrhNoShowTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val sender: FnrhSubmissionSender,
    @Autowired private val remoteIds: FnrhRemoteIds,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)

    /** Uma linha da fila, só com o que o teste confere. */
    private data class Row(
        val operation: FnrhOperation,
        val status: FnrhSubmissionStatus,
        val attempts: Int,
        val lastFailure: FnrhSubmissionFailure?,
    )

    /** As colunas de `fnrh.settings` que o teste muda, para devolvê-las no `finally`. */
    private data class Settings(
        val environment: String,
        val username: String?,
        val password: String?,
        val defaultCpf: String?,
        val enabled: Boolean,
    )

    @Test
    fun `the end of day completes with the Serpro down and the no-show stays pending`() {
        val clockStart = clock.instant()
        val original = settings()
        FnrhTestServer.reset()
        val date = hotel.currentBusinessDate()
        val id = arrivingOn(date)
        val remote = "r-${UuidV7.generate()}"
        try {
            iam.enableFnrh()
            FnrhTestServer.stubRegisterReservation_v2_4(remote)
            sender.sendDueFor(id) shouldBe FnrhSendRun(sent = 1, postponed = 0, failed = 0, invalid = 0)
            remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().remoteId shouldBe
                FnrhRemoteId(remote)
            FnrhTestServer.stub("/reservas/$remote/noshow", status = SERVICE_UNAVAILABLE)

            val run = EndOfDayTestRuns.closeCurrentDate(context)

            statusOf(run) shouldBe "COMPLETED"
            support.status(id) shouldBe "NO_SHOW"
            awaitRow(id, FnrhOperation.NO_SHOW).status shouldBe FnrhSubmissionStatus.PENDING
            rows(id).count { it.operation == FnrhOperation.NO_SHOW } shouldBe 1

            sender.sendDueFor(id) shouldBe FnrhSendRun(sent = 0, postponed = 1, failed = 0, invalid = 0)

            noShowRequests(remote) shouldHaveSize 1
            rows(id).single { it.operation == FnrhOperation.NO_SHOW }.also {
                it.status shouldBe FnrhSubmissionStatus.PENDING
                it.attempts shouldBe 1
                it.lastFailure shouldBe FnrhSubmissionFailure.SERVER_ERROR
            }
            remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull().noShow shouldBe false
        } finally {
            try {
                EndOfDayTestRuns.settle(context)
            } finally {
                clock.advance(Duration.between(clock.instant(), clockStart))
                restore(original)
            }
        }
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    /** Uma reserva confirmada com chegada em [date] (o fechamento dela a torna no-show), com o registro na fila. */
    private fun arrivingOn(date: LocalDate): UUID {
        val (type, _) = support.inventory.roomTypeWithRooms(1, date)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), date, date.plusDays(PRICED_DAYS))
        val created =
            support.created(
                support.booking(support.guest(), support.reservation(type, plan.id, date, date.plusDays(2))),
            )["reservations"][0]
        val id = UUID.fromString(created["id"].asString())
        awaitRow(id, FnrhOperation.REGISTER_RESERVATION)
        return id
    }

    private fun rows(reservation: UUID): List<Row> =
        jdbc
            .sql(
                "SELECT operation, status, attempts, last_failure FROM fnrh.submission " +
                    "WHERE reservation_id = :id ORDER BY sequence",
            ).param("id", reservation)
            .query { rs, _ ->
                Row(
                    FnrhOperation.valueOf(rs.getString("operation")),
                    FnrhSubmissionStatus.valueOf(rs.getString("status")),
                    rs.getInt("attempts"),
                    rs.getString("last_failure")?.let(FnrhSubmissionFailure::valueOf),
                )
            }.list()

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

    /** Os `POST …/noshow` da remota deste teste (o WireMock é compartilhado). */
    private fun noShowRequests(remote: String) =
        FnrhTestServer.requests().filter {
            it.method.value() == "POST" && it.url == "${FnrhTestServer.BASE_PATH}/reservas/$remote/noshow"
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
    }
}
