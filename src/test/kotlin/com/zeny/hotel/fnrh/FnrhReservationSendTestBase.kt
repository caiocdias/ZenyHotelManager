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
 * O apoio comum dos testes do envio das operações da reserva à FNRH (M5 E08, Task 6): cria a reserva pela API (o
 * listener grava o registro), lê a fila do banco e controla o relógio e o WireMock. Cada teste cria a própria reserva
 * e envia só as submissões dela.
 */
@Suppress("LongParameterList") // as portas e os apoios do fluxo real
open class FnrhReservationSendTestBase(
    protected val iam: IamTestSupport,
    protected val jdbc: JdbcClient,
    hotelApi: HotelApi,
    private val reservationsApi: ReservationsApi,
    protected val sender: FnrhSubmissionSender,
    protected val store: FnrhSubmissionStore,
    protected val remoteIds: FnrhRemoteIds,
    protected val clock: MutableClock,
    mockMvc: MockMvc,
    protected val jsonMapper: JsonMapper,
    protected val tx: TransactionTemplate,
) {
    protected val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val hotel = hotelApi
    protected val today: LocalDate get() = hotel.currentBusinessDate()
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

    protected fun advance(duration: Duration) {
        clock.advance(duration)
        advanced = advanced.plus(duration)
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    /** Uma linha da fila, só com o que os testes conferem. */
    protected data class Row(
        val id: UUID,
        val operation: FnrhOperation,
        val status: FnrhSubmissionStatus,
        val attempts: Int,
        val nextAttemptAt: Instant,
        val lastFailure: FnrhSubmissionFailure?,
        val inFlight: Boolean,
    )

    protected fun rows(reservation: UUID): List<Row> =
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

    protected fun row(id: UUID): Row =
        jdbc
            .sql("SELECT reservation_id FROM fnrh.submission WHERE id = :id")
            .param("id", id)
            .query(UUID::class.java)
            .single()
            .let { reservation -> rows(reservation).single { it.id == id } }

    /** Espera o listener gravar a submissão da [operation] da reserva e a devolve. */
    protected fun awaitSubmission(
        reservation: UUID,
        operation: FnrhOperation,
    ): Row {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50)).until {
            rows(reservation).any { it.operation == operation }
        }
        return rows(reservation).single { it.operation == operation }
    }

    protected fun codeOf(reservation: UUID): String =
        checkNotNull(reservationsApi.reservation(ReservationId(reservation))).code.toString()

    /** Uma reserva confirmada daqui a 5 dias, num tipo de UH novo, com o registro já gravado pelo listener. */
    protected fun reservation(
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
    protected val createdBodies = mutableMapOf<UUID, JsonNode>()

    protected fun changeDeparture(
        reservation: UUID,
        departure: LocalDate,
    ) {
        support
            .change(reservation, support.changeBody(createdBodies.getValue(reservation), departure = departure))
            .also { with(support.billing) { it.expect(200) } }
    }

    protected fun minorGuest(): UUID =
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

    protected fun cancel(reservation: UUID) {
        support.cancel(reservation).also { with(support.billing) { it.expect(200) } }
        awaitSubmission(reservation, FnrhOperation.CANCEL_RESERVATION)
    }

    protected fun remoteId(): String = "r-${UuidV7.generate()}"

    protected fun requests(
        method: String,
        path: String,
    ) = FnrhTestServer.requests().filter { it.method.value() == method && it.url == FnrhTestServer.BASE_PATH + path }

    protected fun FnrhSubmissionSender.send(reservation: UUID): FnrhSendRun = sendDueFor(reservation)

    /** O banco guarda microssegundos, arredondados: confere a espera com essa tolerância. */
    protected fun Instant.shouldBeDueIn(delay: Duration) {
        Duration.between(clock.instant().plus(delay), this).abs() shouldBeLessThan Duration.ofMillis(1)
    }

    /** Uma alteração gravada pelo store, antes do cancelamento da reserva (versão 1). */
    protected fun recordUpdate(reservation: UUID): UUID {
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

    protected fun findPath(reservation: UUID) = "/reservas?page_number=1&codigo_reserva=${codeOf(reservation)}"

    protected companion object {
        /** Mais que todo o backoff de um teste (2 h 3 min até a 8ª tentativa). */
        val REWIND: Duration = Duration.ofHours(3)
        const val PRICED_DAYS = 30L
        const val ARRIVAL_IN_DAYS = 5L
        const val MINOR_AGE = 10L

        /** Maior que o `read-timeout` de teste ([FnrhShortReadTimeoutConfiguration.READ_TIMEOUT]). */
        const val TIMEOUT_DELAY_MS = 3_000
    }
}
