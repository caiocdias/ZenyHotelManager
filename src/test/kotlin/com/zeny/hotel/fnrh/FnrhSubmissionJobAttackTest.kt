package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhRemoteIds
import com.zeny.hotel.fnrh.application.FnrhSubmissionSender
import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.fnrh.infrastructure.FnrhProperties
import com.zeny.hotel.fnrh.infrastructure.FnrhSubmissionJob
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.testsupport.FNRH_SEND_DEFERRED
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.enableFnrh
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ataques do testador ao job do envio à FNRH (M5 E08, Task 10): a trava do ShedLock por nome e entre threads, a regra
 * de não rodar em transação, o adiamento no contexto de teste e o log só com contagens.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension::class)
@Import(
    IamTestConfiguration::class,
    InventoryTestConfiguration::class,
    MutableClockConfiguration::class,
    FnrhShortReadTimeoutConfiguration::class,
)
@Suppress("LongParameterList") // as portas e os apoios do fluxo real
class FnrhSubmissionJobAttackTest(
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
    @Autowired private val job: FnrhSubmissionJob,
    @Autowired private val scheduling: ScheduledAnnotationBeanPostProcessor,
    @Autowired private val properties: FnrhProperties,
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
    @AfterEach
    fun releaseLock() {
        // Nunca apaga a linha: o ShedLock lembra que ela existe e só a atualiza, então apagá-la desarma a trava.
        jdbc
            .sql("UPDATE shared.shedlock SET lock_until = now() - interval '1 hour' WHERE name = :n")
            .param("n", LOCK)
            .update()
    }

    private fun holdLock(duration: Duration) {
        jdbc
            .sql(
                """
                INSERT INTO shared.shedlock (name, lock_until, locked_at, locked_by)
                VALUES (:name, now() + CAST(:seconds || ' seconds' AS interval), now(), 'other-instance')
                ON CONFLICT (name) DO UPDATE SET lock_until = EXCLUDED.lock_until, locked_by = EXCLUDED.locked_by
                """.trimIndent(),
            ).param("name", LOCK)
            .param("seconds", duration.seconds.toString())
            .update()
    }

    private fun registerPostsFor(reservation: java.util.UUID) =
        FnrhTestServer
            .requests()
            .filter { it.method.value() == "POST" && it.url == FnrhTestServer.BASE_PATH + "/reservas" }
            .filter { it.bodyAsString.contains(codeOf(reservation)) }

    @Test
    fun `the lock is named fnrh-submissions-send and the job returns nothing and is not transactional`() {
        LOCK shouldBe "fnrh-submissions-send"
        FnrhSubmissionJob.LOCK_NAME shouldBe LOCK
        val method = FnrhSubmissionJob::class.java.getMethod("send")
        method.returnType shouldBe Void.TYPE
        AnnotatedElementUtils.findMergedAnnotation(method, Transactional::class.java).shouldBeNull()
        AnnotatedElementUtils
            .findMergedAnnotation(
                FnrhSubmissionJob::class.java,
                Transactional::class.java,
            ).shouldBeNull()
    }

    @Test
    fun `while another instance holds the lock the job sends nothing, and after it is released the job sends`() {
        val id = reservation()
        val submission = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())
        holdLock(Duration.ofMinutes(10))

        job.send()

        row(submission.id).status shouldBe FnrhSubmissionStatus.PENDING
        row(submission.id).attempts shouldBe 0
        registerPostsFor(id) shouldHaveSize 0

        releaseLock()
        job.send()

        row(submission.id).status shouldBe FnrhSubmissionStatus.SENT
        registerPostsFor(id) shouldHaveSize 1
    }

    @Test
    fun `two concurrent calls of the job send the submission only once`() {
        val id = reservation()
        val submission = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        iam.enableFnrh()
        FnrhTestServer.stub(
            "/reservas",
            body = """{"reserva":{"reserva_id":"${remoteId()}","situacao_reserva_id":"CRIADA"}}""",
            delayMs = SLOW_MS,
        )
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val calls =
                List(2) {
                    CompletableFuture.runAsync({
                        start.await()
                        job.send()
                    }, pool)
                }
            start.countDown()
            calls.forEach { it.get(TIMEOUT, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        row(submission.id).status shouldBe FnrhSubmissionStatus.SENT
        // O WireMock só registra o pedido ao fim do atraso do stub.
        Awaitility.await().atMost(Duration.ofSeconds(5)).until { registerPostsFor(id).isNotEmpty() }
        Thread.sleep(SLOW_MS.toLong())
        registerPostsFor(id) shouldHaveSize 1
    }

    @Test
    fun `a call inside a transaction is refused and releases the lock for the next call`() {
        val id = reservation()
        val submission = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())

        shouldThrow<IllegalStateException> { tx.execute { job.send() } }
        row(submission.id).status shouldBe FnrhSubmissionStatus.PENDING

        job.send()

        row(submission.id).status shouldBe FnrhSubmissionStatus.SENT
    }

    @Test
    fun `the scheduling is deferred in the test context and runs every minute in production`() {
        FNRH_SEND_DEFERRED shouldBe "zeny.fnrh.submissions.initial-delay=PT24H"
        val task =
            scheduling.scheduledTasks
                .map { it.task }
                .filterIsInstance<FixedDelayTask>()
                .single { it.runnable.toString().contains("FnrhSubmissionJob.send") }
        task.initialDelayDuration shouldBe Duration.ofHours(24)
        task.intervalDuration shouldBe Duration.ofMinutes(1)
        properties.submissions.initialDelay shouldBe Duration.ofHours(24)
    }

    @Test
    fun `no submission of the test leaves by itself while the scheduling is deferred`() {
        val id = reservation()
        val submission = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())

        Thread.sleep(IDLE_MS)

        row(submission.id).status shouldBe FnrhSubmissionStatus.PENDING
        row(submission.id).attempts shouldBe 0
        registerPostsFor(id) shouldHaveSize 0
    }

    @Test
    fun `the job logs only counts when something was handled and logs nothing when there is nothing to do`(
        output: CapturedOutput,
    ) {
        val id = reservation()
        val submission = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        val code = codeOf(id)

        job.send() // sem configuração: nada a fazer
        output.all shouldNotContain "Envio à FNRH:"

        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())
        job.send()

        row(submission.id).status shouldBe FnrhSubmissionStatus.SENT
        val line = output.all.lines().single { it.contains("Envio à FNRH:") }
        line shouldContain " enviadas, "
        Regex(
            """Envio à FNRH: \d+ enviadas, \d+ adiadas, \d+ com falha, \d+ inválidas$""",
        ).containsMatchIn(line) shouldBe
            true
        line shouldNotContain code
        line shouldNotContain id.toString()
        // Sem nada mais vencido da reserva, outra chamada não grava outra linha com a reserva.
        output.all.lines().none { it.contains("Envio à FNRH:") && it.contains(code) } shouldBe true
    }

    private companion object {
        const val LOCK = "fnrh-submissions-send"
        const val SLOW_MS = 1_200
        const val IDLE_MS = 3_000L
        const val TIMEOUT = 30L
    }
}
