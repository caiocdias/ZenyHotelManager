package com.zeny.hotel.endofday

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.STEPS
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.THREE
import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.endofday.application.EndOfDayRunCommands
import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import com.zeny.hotel.endofday.application.EndOfDayTrigger
import com.zeny.hotel.endofday.application.TickOutcome
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.endofday.infrastructure.EndOfDayTriggerJob
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.notifications.NotificationId
import com.zeny.hotel.notifications.NotificationRecipients
import com.zeny.hotel.notifications.NotificationTypeCode
import com.zeny.hotel.notifications.NotificationsApi
import com.zeny.hotel.shared.IDEMPOTENCY_KEY_HEADER
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.javacrumbs.shedlock.core.ClockProvider
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.PUT
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ataques ao gatilho automático do fechamento (M6 E10, Task 12), no banco isolado com as etapas falsas: a borda exata
 * da janela de 24 h, a data atrasada (uma data por rodada, cada uma na própria janela), o horário do meio-dia em diante
 * num fuso de meia hora, a execução `FAILED` durante toda a janela, as corridas perdidas na criação, a trava do motor
 * em outro executor, rodadas e fechamentos manuais simultâneos em várias datas, o aviso que falhou e a trava do job.
 *
 * Cada teste troca o fuso (e, se precisar, o horário do fechamento) pela API, põe o relógio onde quer e, no `finally`,
 * fecha a data ([EndOfDayTestRuns.settle]) e devolve relógio, horário e fuso. Toda data avisada fora da janela pelo
 * gatilho do contexto é fechada no próprio teste: o aviso é uma vez por data e processo, e uma data avisada e deixada
 * aberta calaria o aviso do teste seguinte.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass") // o gatilho, os apoios do contexto e os cenários
class EndOfDayTriggerAttackTest(
    @Autowired private val trigger: EndOfDayTrigger,
    @Autowired private val job: EndOfDayTriggerJob,
    @Autowired private val runs: EndOfDayRunRepository,
    @Autowired private val registry: EndOfDayStepRegistry,
    @Autowired private val commands: EndOfDayRunCommands,
    @Autowired private val engine: EndOfDayEngine,
    @Autowired private val notifications: NotificationsApi,
    @Autowired private val lockProvider: LockProvider,
    @Autowired private val probe: FakeStepsProbe,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val json: JsonMapper,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val clock: MutableClock,
    @Autowired private val context: ApplicationContext,
    @Value("\${zeny.endofday.lock-at-most-for}") private val lockAtMostFor: Duration,
) {
    private data class Notice(
        val title: String,
        val body: String,
    )

    /**
     * Um cenário: o hotel no fuso [zone] e com o fechamento às [endOfDayTime]; o [block] recebe a data corrente. No
     * fim, fecha a data e devolve relógio, horário e fuso, nessa ordem.
     */
    private fun scenario(
        zone: String = MANAUS,
        endOfDayTime: LocalTime = DEFAULT_END_OF_DAY,
        block: (LocalDate) -> Unit,
    ) {
        val originalClock = clock.instant()
        val originalHotel = hotelData()
        val originalOperations = operationsData()
        try {
            probe.reset()
            putHotel(withTimezone(originalHotel, zone))
            if (endOfDayTime != DEFAULT_END_OF_DAY) {
                putOperations((originalOperations.deepCopy() as ObjectNode).put("endOfDayTime", "$endOfDayTime"))
            }
            hotelApi.operationalSettings().endOfDayTime shouldBe endOfDayTime
            block(hotelApi.currentBusinessDate())
        } finally {
            try {
                EndOfDayTestRuns.settle(context)
            } finally {
                setClock(originalClock)
                if (endOfDayTime != DEFAULT_END_OF_DAY) putOperations(originalOperations)
                restoreHotel(originalHotel)
            }
        }
    }

    /** O vencimento da [date] às 03:00 de Manaus (UTC-4, sem horário de verão): 07:00Z do dia seguinte. */
    private fun manausDueAt(date: LocalDate): Instant =
        ZonedDateTime.of(date.plusDays(1), DEFAULT_END_OF_DAY, ZoneId.of(MANAUS)).toInstant()

    private fun setClock(instant: Instant) = clock.advance(Duration.between(clock.instant(), instant))

    private fun hotelData(): JsonNode {
        synchronizer.synchronize()
        return iam.call(GET, "/api/v1/hotel", iam.asAdmin()).body(200)
    }

    private fun operationsData(): JsonNode = iam.call(GET, OPERATIONS, iam.asAdmin()).body(200)

    private fun withTimezone(
        hotel: JsonNode,
        timezone: String,
    ): ObjectNode {
        val body: ObjectNode =
            if (hotel["configured"].asBoolean()) hotel.deepCopy() as ObjectNode else json.valueToTree(TEST_HOTEL)
        body.remove(listOf("id", "configured", "updatedAt"))
        body.put("timezone", timezone)
        return body
    }

    private fun putHotel(body: JsonNode) {
        val etag = iam.call(GET, "/api/v1/hotel", iam.asAdmin()).getHeader(HttpHeaders.ETAG).shouldNotBeNull()
        iam.call(PUT, "/api/v1/hotel", iam.asAdmin(), body, ifMatch = etag).body(200)
    }

    private fun putOperations(body: JsonNode) {
        val etag = iam.call(GET, OPERATIONS, iam.asAdmin()).getHeader(HttpHeaders.ETAG).shouldNotBeNull()
        iam.call(PUT, OPERATIONS, iam.asAdmin(), body, ifMatch = etag).body(200)
    }

    /** Como no `EndOfDayTriggerTest`: o fuso pelo `PUT`; um hotel não configurado volta sem dados cadastrais. */
    private fun restoreHotel(original: JsonNode) {
        putHotel(withTimezone(original, original["timezone"].asString()))
        if (!original["configured"].asBoolean()) {
            jdbc
                .sql(
                    "UPDATE hotel.hotel SET legal_name = NULL, trade_name = NULL, cnpj = NULL, " +
                        "municipal_registration = NULL, cadastur = NULL, street = NULL, number = NULL, " +
                        "complement = NULL, district = NULL, city = NULL, state = NULL, postal_code = NULL, " +
                        "ibge_city_code = NULL, tax_regime = NULL, logo_file_id = NULL",
                ).update()
        }
        hotelApi.timezone().id shouldBe original["timezone"].asString()
    }

    private fun follower(): TestUser =
        iam.createUserWith(
            TestGrant(EndOfDayPermissions.permissions.single { it.code == EndOfDayPermissions.RUNS_READ }, ALL),
        )

    private fun manager(): TestUser =
        iam.createUserWith(
            TestGrant(EndOfDayPermissions.permissions.single { it.code == EndOfDayPermissions.RUNS_EXECUTE }, ALL),
        )

    private fun noticesOf(user: TestUser): List<Notice> =
        jdbc
            .sql(
                "SELECT title, body FROM notifications.notification " +
                    "WHERE type = 'notifications.end-of-day-pending' AND recipient_id = :id ORDER BY id",
            ).param("id", user.id.value)
            .query { rs, _ -> Notice(rs.getString("title"), rs.getString("body")) }
            .list()

    private fun outsideWindowNotice(date: LocalDate) =
        Notice(
            "Pendência no fechamento de ${date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}",
            "OUTSIDE_WINDOW: fechamento automático fora da janela; execute manualmente",
        )

    private fun runCount(date: LocalDate): Int =
        jdbc
            .sql("SELECT count(*) FROM endofday.run WHERE business_date = :d")
            .param("d", date)
            .query(Int::class.java)
            .single()

    private fun runRow(date: LocalDate): Map<String, Any?> =
        jdbc
            .sql("SELECT id, status, trigger, triggered_by, version FROM endofday.run WHERE business_date = :d")
            .param("d", date)
            .query()
            .singleRow()

    private fun invocations(date: LocalDate): List<Int> = STEPS.map { probe.invocations(it, date) }

    private fun closeManually(
        user: TestUser,
        date: LocalDate,
    ): JsonNode = startManually(user, date).body(201).also { it["status"].asString() shouldBe "COMPLETED" }

    private fun startManually(
        user: TestUser,
        date: LocalDate,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/end-of-day/runs") {
                with(asUser(user.id.value))
                header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"businessDate":"$date"}"""
            }.andReturn()
            .response

    private fun MockHttpServletResponse.body(status: Int): JsonNode {
        withClue(getContentAsString(Charsets.UTF_8)) { this.status shouldBe status }
        return json.readTree(getContentAsString(Charsets.UTF_8))
    }

    /** Um gatilho com as portas do contexto, salvo [commands] e [notifications] trocados pelo teste. */
    private fun triggerWith(
        commands: EndOfDayRunCommands = this.commands,
        notifications: NotificationsApi = this.notifications,
    ) = EndOfDayTrigger(
        hotelApi,
        runs,
        registry,
        commands,
        engine,
        notifications,
        clock,
        Duration.ofHours(24),
        lockAtMostFor,
    )

    private fun warnings(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(EndOfDayTrigger::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.WARN }
    }

    /** Solta [first] e [second] juntas e espera as duas terminarem (o `settle` não corre com elas em curso). */
    private fun <A, B> together(
        first: () -> A,
        second: () -> B,
    ): Pair<Result<A>, Result<B>> {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val ready = CountDownLatch(2)
            val go = CountDownLatch(1)
            val a: Future<A> =
                pool.submit<A> {
                    ready.countDown()
                    go.await()
                    first()
                }
            val b: Future<B> =
                pool.submit<B> {
                    ready.countDown()
                    go.await()
                    second()
                }
            ready.await(5, TimeUnit.SECONDS) shouldBe true
            go.countDown()
            val resultA = runCatching { a.get(RACE_WAIT, TimeUnit.SECONDS) }
            val resultB = runCatching { b.get(RACE_WAIT, TimeUnit.SECONDS) }
            return resultA to resultB
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `at exactly 24 h after the due time the window is closed`() =
        scenario { date ->
            val reader = follower()
            setClock(manausDueAt(date).plus(Duration.ofHours(24)))

            val warned =
                warnings {
                    trigger.tick() shouldBe TickOutcome.OUTSIDE_WINDOW
                    trigger.tick() shouldBe TickOutcome.OUTSIDE_WINDOW
                }

            runCount(date) shouldBe 0
            invocations(date) shouldContainExactly listOf(0, 0, 0, 0)
            warned.size shouldBe 1
            noticesOf(reader) shouldContainExactly listOf(outsideWindowNotice(date))
            closeManually(manager(), date)
        }

    @Test
    fun `one microsecond before the end of the window the run still starts`() =
        scenario { date ->
            setClock(manausDueAt(date).plus(Duration.ofHours(24)).minusNanos(1_000))

            trigger.tick() shouldBe TickOutcome.STARTED

            runRow(date)["status"] shouldBe "COMPLETED"
            runRow(date)["trigger"] shouldBe "AUTOMATIC"
            invocations(date) shouldContainExactly listOf(1, 1, 1, 1)
        }

    @Test
    fun `a late date is closed one date per tick`() =
        scenario { date ->
            setClock(manausDueAt(date).plus(Duration.ofHours(23)))

            trigger.tick() shouldBe TickOutcome.STARTED
            trigger.tick() shouldBe TickOutcome.NOT_DUE

            runRow(date)["status"] shouldBe "COMPLETED"
            hotelApi.currentBusinessDate() shouldBe date.plusDays(1)
            runCount(date.plusDays(1)) shouldBe 0
            invocations(date.plusDays(1)) shouldContainExactly listOf(0, 0, 0, 0)
        }

    @Test
    fun `each late date is warned once outside its window and only a date inside its own window closes by itself`() =
        scenario { date ->
            val reader = follower()
            val user = manager()
            setClock(manausDueAt(date).plus(Duration.ofHours(49)))

            trigger.tick() shouldBe TickOutcome.OUTSIDE_WINDOW
            closeManually(user, date)
            trigger.tick() shouldBe TickOutcome.OUTSIDE_WINDOW
            trigger.tick() shouldBe TickOutcome.OUTSIDE_WINDOW
            closeManually(user, date.plusDays(1))
            trigger.tick() shouldBe TickOutcome.STARTED
            trigger.tick() shouldBe TickOutcome.NOT_DUE

            noticesOf(reader) shouldContainExactly
                listOf(outsideWindowNotice(date), outsideWindowNotice(date.plusDays(1)))
            runRow(date.plusDays(2))["trigger"] shouldBe "AUTOMATIC"
            runRow(date.plusDays(2))["status"] shouldBe "COMPLETED"
            runCount(date.plusDays(3)) shouldBe 0
        }

    @Test
    fun `a closing time from noon on is due on the business date itself in a half-hour offset zone`() =
        scenario(zone = "Asia/Kolkata", endOfDayTime = LocalTime.of(23, 30)) { date ->
            // 23:30 de Kolkata (UTC+5:30) na própria data operacional: 18:00Z.
            val dueAt = date.atTime(18, 0).toInstant(ZoneOffset.UTC)
            setClock(dueAt.minusSeconds(1))

            trigger.tick() shouldBe TickOutcome.NOT_DUE
            runCount(date) shouldBe 0

            setClock(dueAt)
            trigger.tick() shouldBe TickOutcome.STARTED
            runRow(date)["status"] shouldBe "COMPLETED"
        }

    @Test
    fun `a failed run is never retried by the trigger during the whole window`() =
        scenario { date ->
            val dueAt = manausDueAt(date)
            setClock(dueAt)
            probe.failNext(THREE, 1)
            trigger.tick() shouldBe TickOutcome.STARTED
            val failed = runRow(date)
            failed["status"] shouldBe "FAILED"
            val calls = invocations(date)

            listOf(Duration.ofHours(1), Duration.ofHours(12), Duration.ofHours(24).minusSeconds(1)).forEach {
                setClock(dueAt.plus(it))
                withClue("$it após o vencimento") { trigger.tick() shouldBe TickOutcome.WAITING_MANUAL_RESUME }
            }

            runRow(date) shouldBe failed
            invocations(date) shouldBe calls
        }

    @Test
    fun `a run another executor created first is followed to the end`() =
        scenario { date ->
            val racing = mockk<EndOfDayRunCommands>()
            every { racing.createAutomatic(date) } answers {
                commands.createAutomatic(date)
                throw EndOfDayRunExistsException(date, null)
            }
            setClock(manausDueAt(date))

            triggerWith(commands = racing).tick() shouldBe TickOutcome.RESUMED

            runCount(date) shouldBe 1
            runRow(date)["status"] shouldBe "COMPLETED"
            invocations(date) shouldContainExactly listOf(1, 1, 1, 1)
        }

    @Test
    fun `a run another executor already closed is already closed`() =
        scenario { date ->
            val racing = mockk<EndOfDayRunCommands>()
            every { racing.createAutomatic(date) } answers {
                engine.execute(commands.createAutomatic(date).id, Duration.ofSeconds(RACE_WAIT))
                throw EndOfDayAlreadyClosedException(date)
            }
            setClock(manausDueAt(date))

            triggerWith(commands = racing).tick() shouldBe TickOutcome.ALREADY_CLOSED

            runCount(date) shouldBe 1
            runRow(date)["status"] shouldBe "COMPLETED"
            invocations(date) shouldContainExactly listOf(1, 1, 1, 1)
        }

    @Test
    fun `a date that moved under the trigger is busy and creates nothing`() =
        scenario { date ->
            val racing = mockk<EndOfDayRunCommands>()
            every { racing.createAutomatic(date) } throws EndOfDayDateMismatchException(date.plusDays(1))
            setClock(manausDueAt(date))

            triggerWith(commands = racing).tick() shouldBe TickOutcome.BUSY

            runCount(date) shouldBe 0
            invocations(date) shouldContainExactly listOf(0, 0, 0, 0)
        }

    @Test
    fun `a conflict that is not a lost race escapes the tick`() =
        scenario { date ->
            val failing = mockk<EndOfDayRunCommands>()
            every { failing.createAutomatic(date) } throws EndOfDayRunInProgressException()
            setClock(manausDueAt(date))

            shouldThrow<EndOfDayRunInProgressException> { triggerWith(commands = failing).tick() }

            runCount(date) shouldBe 0
        }

    @Test
    fun `a run created while another executor holds the engine lock is busy and the next tick resumes it`() =
        scenario { date ->
            setClock(manausDueAt(date))
            val held =
                lockProvider
                    .lock(LockConfiguration(ClockProvider.now(), EndOfDayEngine.LOCK_NAME, HELD_FOR, Duration.ZERO))
                    .orElseThrow()
            try {
                trigger.tick() shouldBe TickOutcome.BUSY

                runRow(date)["status"] shouldBe "RUNNING"
                runRow(date)["trigger"] shouldBe "AUTOMATIC"
                invocations(date) shouldContainExactly listOf(0, 0, 0, 0)
            } finally {
                held.unlock()
            }

            trigger.tick() shouldBe TickOutcome.RESUMED

            runCount(date) shouldBe 1
            runRow(date)["status"] shouldBe "COMPLETED"
            invocations(date) shouldContainExactly listOf(1, 1, 1, 1)
        }

    @Test
    fun `two ticks at the same time produce one run per date`() =
        scenario { first ->
            repeat(ROUNDS) { round ->
                val date = first.plusDays(round.toLong())
                hotelApi.currentBusinessDate() shouldBe date
                setClock(manausDueAt(date))

                val (a, b) = together({ trigger.tick() }, { trigger.tick() })

                val outcomes = listOf(a.getOrThrow(), b.getOrThrow())
                withClue("rodada $round: $outcomes") {
                    outcomes.count { it == TickOutcome.STARTED } shouldBe 1
                    outcomes.forEach {
                        it shouldBeIn
                            listOf(
                                TickOutcome.STARTED,
                                TickOutcome.RESUMED,
                                TickOutcome.ALREADY_CLOSED,
                                TickOutcome.NOT_DUE,
                            )
                    }
                    runCount(date) shouldBe 1
                    runRow(date)["status"] shouldBe "COMPLETED"
                    invocations(date) shouldContainExactly listOf(1, 1, 1, 1)
                }
            }
        }

    @Test
    fun `the trigger and a manual run racing on several dates produce one run per date with one creator`() =
        scenario { first ->
            val user = manager()
            repeat(ROUNDS) { round ->
                val date = first.plusDays(round.toLong())
                hotelApi.currentBusinessDate() shouldBe date
                setClock(manausDueAt(date))

                val (tick, manual) = together({ trigger.tick() }, { startManually(user, date) })

                val outcome = tick.getOrThrow()
                val status = manual.getOrThrow().status
                withClue("rodada $round: $outcome, HTTP $status") {
                    outcome shouldBeIn
                        listOf(
                            TickOutcome.STARTED,
                            TickOutcome.RESUMED,
                            TickOutcome.ALREADY_CLOSED,
                            TickOutcome.NOT_DUE,
                        )
                    status shouldBeIn listOf(201, 409)
                    (outcome == TickOutcome.STARTED) shouldBe (status == 409)
                    runCount(date) shouldBe 1
                    runRow(date)["status"] shouldBe "COMPLETED"
                    invocations(date) shouldContainExactly listOf(1, 1, 1, 1)
                }
            }
        }

    @Test
    fun `a notice outside the window that failed is sent by the next tick and then only once`() =
        scenario { date ->
            val reader = follower()
            val calls = AtomicInteger()
            val flaky =
                object : NotificationsApi {
                    override fun notify(
                        type: NotificationTypeCode,
                        title: String,
                        body: String,
                        recipients: NotificationRecipients,
                    ): List<NotificationId> {
                        check(calls.incrementAndGet() > 1) { "painel fora do ar" }
                        return notifications.notify(type, title, body, recipients)
                    }
                }
            val outside = triggerWith(notifications = flaky)
            setClock(manausDueAt(date).plus(Duration.ofHours(25)))

            shouldThrow<IllegalStateException> { outside.tick() }
            outside.tick() shouldBe TickOutcome.OUTSIDE_WINDOW
            outside.tick() shouldBe TickOutcome.OUTSIDE_WINDOW

            calls.get() shouldBe 2
            noticesOf(reader) shouldContainExactly listOf(outsideWindowNotice(date))
            runCount(date) shouldBe 0
        }

    @Test
    fun `the job does not tick while another process holds the end-of-day-trigger lock`() =
        scenario { date ->
            setClock(manausDueAt(date))
            val held =
                lockProvider
                    .lock(LockConfiguration(ClockProvider.now(), EndOfDayTriggerJob.LOCK_NAME, HELD_FOR, Duration.ZERO))
                    .orElseThrow()
            try {
                job.triggerEndOfDay()

                runCount(date) shouldBe 0
                invocations(date) shouldContainExactly listOf(0, 0, 0, 0)
            } finally {
                held.unlock()
            }

            job.triggerEndOfDay()

            runRow(date)["status"] shouldBe "COMPLETED"
            runRow(date)["trigger"] shouldBe "AUTOMATIC"
        }

    private companion object {
        const val MANAUS = "America/Manaus"
        const val OPERATIONS = "/api/v1/hotel/operational-settings"
        const val ROUNDS = 3
        const val RACE_WAIT = 60L
        val DEFAULT_END_OF_DAY: LocalTime = LocalTime.of(3, 0)
        val HELD_FOR: Duration = Duration.ofMinutes(5)

        val TEST_HOTEL: Map<String, Any?> =
            mapOf(
                "legalName" to "Pousada Gatilho Ltda",
                "tradeName" to "Pousada Gatilho",
                "cnpj" to "11.222.333/0001-81",
                "municipalRegistration" to null,
                "cadastur" to null,
                "address" to
                    mapOf(
                        "street" to "Rua das Flores",
                        "number" to "100",
                        "complement" to null,
                        "district" to "Centro",
                        "city" to "São Paulo",
                        "state" to "SP",
                        "postalCode" to "01310-100",
                        "ibgeCityCode" to "3550308",
                    ),
                "timezone" to "America/Sao_Paulo",
                "taxRegime" to "SIMPLES_NACIONAL",
                "logoFileId" to null,
            )
    }
}
