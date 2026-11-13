package com.zeny.hotel.endofday

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.ONE
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.STEPS
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.THREE
import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.endofday.application.EndOfDayRunCommands
import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import com.zeny.hotel.endofday.application.EndOfDayTrigger
import com.zeny.hotel.endofday.application.TickOutcome
import com.zeny.hotel.endofday.domain.EndOfDayRun
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.endofday.domain.RunTrigger
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.notifications.NotificationsApi
import com.zeny.hotel.shared.IDEMPOTENCY_KEY_HEADER
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
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
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * O gatilho automático do fechamento (M6 E10, Task 12): vence no horário do hotel, no fuso dele, e só age até 24 h
 * depois; fora disso, só o fechamento manual. No banco isolado, com as etapas falsas. Cada teste troca o fuso do hotel
 * para `America/Manaus` pelo `PUT /api/v1/hotel` (UTC-4, uma hora atrás do padrão `America/Sao_Paulo`), põe o relógio
 * no instante que quer e, no fim, fecha a data ([EndOfDayTestRuns.settle]), devolve o relógio e o fuso originais.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList", "TooManyFunctions") // o gatilho, os apoios do contexto e os passos de cada cenário
class EndOfDayTriggerTest(
    @Autowired private val trigger: EndOfDayTrigger,
    @Autowired private val runs: EndOfDayRunRepository,
    @Autowired private val registry: EndOfDayStepRegistry,
    @Autowired private val commands: EndOfDayRunCommands,
    @Autowired private val engine: EndOfDayEngine,
    @Autowired private val notifications: NotificationsApi,
    @Autowired private val probe: FakeStepsProbe,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val json: JsonMapper,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val clock: MutableClock,
    @Autowired private val context: ApplicationContext,
) {
    private data class Notice(
        val title: String,
        val body: String,
    )

    /**
     * O cenário de um teste: fuso do hotel em `America/Manaus`, a data corrente e o instante em que ela vence (03:00 de
     * Manaus da madrugada seguinte, 07:00Z). No fim, fecha a data e devolve relógio e fuso, nessa ordem.
     */
    private fun scenario(block: (LocalDate, Instant) -> Unit) {
        val originalClock = clock.instant()
        val originalHotel = hotelData()
        try {
            probe.reset()
            putHotel(withTimezone(originalHotel, MANAUS))
            val date = hotelApi.currentBusinessDate()
            hotelApi.operationalSettings().endOfDayTime shouldBe LocalTime.of(3, 0)
            val dueAt = ZonedDateTime.of(date.plusDays(1), LocalTime.of(3, 0), ZoneId.of(MANAUS)).toInstant()
            block(date, dueAt)
        } finally {
            try {
                EndOfDayTestRuns.settle(context)
            } finally {
                setClock(originalClock)
                restoreHotel(originalHotel)
            }
        }
    }

    private fun setClock(instant: Instant) = clock.advance(Duration.between(clock.instant(), instant))

    private fun hotelData(): JsonNode {
        synchronizer.synchronize()
        val response = iam.call(GET, "/api/v1/hotel", iam.asAdmin())
        withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 200 }
        return iam.json(response)
    }

    /** O corpo do `PUT` com o [timezone]: os dados do hotel, ou os de teste se ele ainda não foi configurado. */
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
        val response = iam.call(PUT, "/api/v1/hotel", iam.asAdmin(), body, ifMatch = etag)
        withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 200 }
    }

    /**
     * Devolve o fuso original pelo `PUT`; um hotel que não estava configurado volta sem dados cadastrais (a API não
     * apaga dados cadastrais, então pela tabela).
     */
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

    private fun auditUsers(
        id: UUID,
        action: String,
    ): List<Any?> =
        jdbc
            .sql("SELECT user_id FROM audit.audit_entry WHERE entity_id = :id AND action = :action")
            .param("id", id.toString())
            .param("action", action)
            .query()
            .listOfRows()
            .map { it["user_id"] }

    /** A execução da data corrente interrompida por uma queda: `fake-one` concluída, `fake-two` em curso. */
    private fun interruptedRun(): EndOfDayRunId {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return tx
            .execute {
                val run =
                    EndOfDayRun.start(hotelApi.currentBusinessDate(), RunTrigger.AUTOMATIC, null, registry.slots, now)
                run.beginNext(registry.slots, now)
                run.completeStep(ONE, "DONE", now)
                run.beginNext(registry.slots, now)
                runs.save(run)
            }.shouldNotBeNull()
            .id
    }

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

    @Test
    fun `before the due time nothing runs`() =
        scenario { date, dueAt ->
            setClock(dueAt.minus(Duration.ofMinutes(1)))

            trigger.tick() shouldBe TickOutcome.NOT_DUE

            runCount(date) shouldBe 0
            invocations(date) shouldContainExactly listOf(0, 0, 0, 0)
        }

    @Test
    fun `at the due time in the hotel zone the run starts automatically`() =
        scenario { date, dueAt ->
            setClock(dueAt)

            trigger.tick() shouldBe TickOutcome.STARTED

            val run = runRow(date)
            run["status"] shouldBe "COMPLETED"
            run["trigger"] shouldBe "AUTOMATIC"
            run["triggered_by"] shouldBe null
            auditUsers(run["id"] as UUID, "endofday.run.start") shouldContainExactly listOf(null)
            invocations(date) shouldContainExactly listOf(1, 1, 1, 1)
        }

    @Test
    fun `an interrupted run is resumed by the trigger`() =
        scenario { date, dueAt ->
            val id = interruptedRun()
            setClock(dueAt)

            trigger.tick() shouldBe TickOutcome.RESUMED

            val run = runRow(date)
            run["id"] shouldBe id.value
            run["status"] shouldBe "COMPLETED"
            invocations(date) shouldContainExactly listOf(0, 1, 1, 1)
        }

    @Test
    fun `a failed run waits for the manual resume`() =
        scenario { date, dueAt ->
            setClock(dueAt)
            probe.failNext(THREE, 1)
            trigger.tick() shouldBe TickOutcome.STARTED
            val failed = runRow(date)
            failed["status"] shouldBe "FAILED"
            val calls = invocations(date)

            trigger.tick() shouldBe TickOutcome.WAITING_MANUAL_RESUME

            runRow(date) shouldBe failed
            invocations(date) shouldBe calls
        }

    @Test
    fun `after the catch-up window nothing runs automatically`() =
        scenario { date, dueAt ->
            val reader = follower()
            setClock(dueAt.plus(Duration.ofHours(25)))

            trigger.tick() shouldBe TickOutcome.OUTSIDE_WINDOW
            trigger.tick() shouldBe TickOutcome.OUTSIDE_WINDOW

            runCount(date) shouldBe 0
            invocations(date) shouldContainExactly listOf(0, 0, 0, 0)
            noticesOf(reader) shouldContainExactly
                listOf(
                    Notice(
                        "Pendência no fechamento de ${date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}",
                        "OUTSIDE_WINDOW: fechamento automático fora da janela; execute manualmente",
                    ),
                )
            val run = startManually(manager(), date).body(201)
            run["status"].asString() shouldBe "COMPLETED"
            run["trigger"].asString() shouldBe "MANUAL"
        }

    @Test
    fun `the trigger and a manual run at the same time produce one run`() =
        scenario { date, dueAt ->
            setClock(dueAt)
            val user = manager()
            val pool = Executors.newFixedThreadPool(2)
            try {
                val ready = CountDownLatch(2)
                val go = CountDownLatch(1)
                val tick =
                    pool.submit<TickOutcome> {
                        ready.countDown()
                        go.await()
                        trigger.tick()
                    }
                val manual =
                    pool.submit<MockHttpServletResponse> {
                        ready.countDown()
                        go.await()
                        startManually(user, date)
                    }
                ready.await(5, TimeUnit.SECONDS) shouldBe true
                go.countDown()
                // As duas terminam antes de qualquer asserção: o settle não pode correr com uma delas em curso.
                val outcome = runCatching { tick.get(60, TimeUnit.SECONDS) }
                val response = runCatching { manual.get(60, TimeUnit.SECONDS) }

                oneRunWithOneCreator(date, outcome.getOrThrow(), response.getOrThrow().status)
            } finally {
                pool.shutdownNow()
            }
        }

    @Test
    fun `a manual run that closes the date before the trigger reads it leaves the trigger not due`() =
        scenario { date, dueAt ->
            setClock(dueAt)
            // A intercalação extrema da corrida acima: o POST conclui (a etapa de avanço leva a data a D+1) antes de
            // o tick ler a data corrente; D+1 vence 24 h depois, então o tick não tem o que fazer.
            val status = startManually(manager(), date).status

            val outcome = trigger.tick()

            outcome shouldBe TickOutcome.NOT_DUE
            oneRunWithOneCreator(date, outcome, status)
        }

    /**
     * O desfecho da corrida entre o `tick` e o `POST` da mesma [date]: uma execução, concluída, com as etapas uma vez,
     * e um criador só (o `tick` criou, `STARTED`, se e somente se o `POST` recebeu `409`). O `tick` pode também não
     * ter o que fazer (`NOT_DUE`) quando o `POST` concluiu e avançou a data antes de ele ler a data corrente.
     */
    private fun oneRunWithOneCreator(
        date: LocalDate,
        outcome: TickOutcome,
        status: Int,
    ) = withClue("tick $outcome, HTTP $status") {
        outcome shouldBeIn
            listOf(TickOutcome.STARTED, TickOutcome.RESUMED, TickOutcome.ALREADY_CLOSED, TickOutcome.NOT_DUE)
        status shouldBeIn listOf(201, 409)
        (outcome == TickOutcome.STARTED) shouldBe (status == 409)
        runCount(date) shouldBe 1
        runRow(date)["status"] shouldBe "COMPLETED"
        invocations(date) shouldContainExactly listOf(1, 1, 1, 1)
    }

    @Test
    fun `an interrupted run outside the window waits for the manual resume`() =
        scenario { date, dueAt ->
            val id = interruptedRun()
            val before = runRow(date)
            setClock(dueAt.plus(Duration.ofHours(25)))

            trigger.tick() shouldBe TickOutcome.OUTSIDE_WINDOW

            runRow(date) shouldBe before
            invocations(date) shouldContainExactly listOf(0, 0, 0, 0)
            val resumed =
                mockMvc
                    .post("/api/v1/end-of-day/runs/${id.value}/resume") {
                        with(asUser(manager().id.value))
                        header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                    }.andReturn()
                    .response
                    .body(200)
            resumed["status"].asString() shouldBe "COMPLETED"
            invocations(date) shouldContainExactly listOf(0, 1, 1, 1)
        }

    @Test
    fun `with an empty registry nothing runs and the warning is logged once per date`() =
        scenario { date, dueAt ->
            val empty =
                EndOfDayTrigger(
                    hotelApi,
                    runs,
                    EndOfDayStepRegistry(emptyList()),
                    commands,
                    engine,
                    notifications,
                    clock,
                    Duration.ofHours(24),
                    Duration.ofMinutes(5),
                )
            setClock(dueAt)
            val logger = LoggerFactory.getLogger(EndOfDayTrigger::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                empty.tick() shouldBe TickOutcome.NO_STEPS
                empty.tick() shouldBe TickOutcome.NO_STEPS
            } finally {
                logger.detachAppender(appender)
            }

            runCount(date) shouldBe 0
            appender.list.count { it.level == Level.WARN } shouldBe 1
        }

    private companion object {
        const val MANAUS = "America/Manaus"

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
