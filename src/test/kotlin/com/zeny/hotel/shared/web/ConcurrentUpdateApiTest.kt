package com.zeny.hotel.shared.web

import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.IDEMPOTENCY_KEY_HEADER
import com.zeny.hotel.shared.IDEMPOTENT_REPLAYED_HEADER
import com.zeny.hotel.shared.Idempotent
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.orm.jpa.JpaSystemException
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * `409 CONCURRENT_UPDATE` (M6 E21, Task 1; ARCHITECTURE §7, linha "Concorrência"): um deadlock (`40P01`) ou uma espera
 * de trava além do `lock_timeout` (`55P03`) que escapa de um caso de uso responde `409` com código estável, sem a
 * mensagem do banco na resposta nem no log, e conta a ocorrência por SQLSTATE no `zeny.api.concurrent.updates` (D3).
 * Num endpoint `@Idempotent`, esse `409` libera a chave (D4), e outro `409` continua guardado. Toda conexão do
 * datasource da aplicação abre com `lock_timeout` de 10 s (D1).
 *
 * Os casos de uso e o controller são só de teste, aninhados aqui e registrados por [ProbeConfiguration] (fora do
 * contrato). Travas determinísticas: quem segura uma linha o faz antes da requisição que espera, e o deadlock espera,
 * por `pg_blocking_pids`, a segunda requisição ficar bloqueada pela primeira antes de soltar a primeira.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension::class)
@Import(ConcurrentUpdateApiTest.ProbeConfiguration::class)
@Suppress("LongParameterList") // os beans de que os cenários precisam
class ConcurrentUpdateApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val meters: MeterRegistry,
    @Autowired private val probe: ConcurrencyProbe,
    @Autowired private val flyway: Flyway,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)
    private val user = asUser()

    @BeforeEach
    fun reset() = probe.reset()

    // --- Deadlock e lock_timeout reais ---------------------------------------------------------------------------

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a deadlock between two requests answers 409 CONCURRENT_UPDATE to one and success to the other`() {
        val first = newRow()
        val second = newRow()
        val before = concurrentUpdates(DEADLOCK)
        val gateA = probe.gate("a")
        probe.gate("b").proceed.countDown()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<MockHttpServletResponse> { crossLock("a", first, second) }
            check(gateA.locked.await(LATCH_SECONDS, TimeUnit.SECONDS)) { "A primeira requisição não travou a linha" }
            val b = pool.submit<MockHttpServletResponse> { crossLock("b", second, first) }
            awaitBlockedBy(gateA.pid.get(), b)
            gateA.proceed.countDown()

            val responses = listOf(a.get(LATCH_SECONDS, TimeUnit.SECONDS), b.get(LATCH_SECONDS, TimeUnit.SECONDS))

            responses.map { it.status } shouldContainExactlyInAnyOrder listOf(200, 409)
            val conflict = responses.single { it.status == 409 }
            val body = conflict.getContentAsString(Charsets.UTF_8)
            jsonMapper.readTree(body)["code"].asString() shouldBe "CONCURRENT_UPDATE"
            listOf("deadlock", "40P01", "time_probe", "test_support", "Process", "ShareLock", "transaction")
                .forEach { leak -> withClue("o corpo do 409 vazou '$leak': $body") { body shouldNotContain leak } }
            concurrentUpdates(DEADLOCK) shouldBe before + 1
        } finally {
            gateA.proceed.countDown()
            pool.shutdown()
            pool.awaitTermination(LATCH_SECONDS, TimeUnit.SECONDS)
            pool.shutdownNow()
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a request that waits for a row beyond the lock_timeout answers 409 CONCURRENT_UPDATE`() {
        val row = newRow()
        val before = concurrentUpdates(LOCK_NOT_AVAILABLE)

        val response = holdingRow(row) { post("/api/v1/test-concurrency/locked?row=$row") }

        response.status shouldBe 409
        val body = response.getContentAsString(Charsets.UTF_8)
        jsonMapper.readTree(body)["code"].asString() shouldBe "CONCURRENT_UPDATE"
        listOf("55P03", "lock_timeout", "canceling", "time_probe", "test_support")
            .forEach { leak -> withClue("o corpo do 409 vazou '$leak': $body") { body shouldNotContain leak } }
        concurrentUpdates(LOCK_NOT_AVAILABLE) shouldBe before + 1
    }

    @Test
    fun `every connection of the application datasource opens with a lock_timeout of 10s`() {
        tx.execute { jdbc.sql("SHOW lock_timeout").query(String::class.java).single() } shouldBe "10s"
    }

    @Test
    fun `the Flyway connection does not inherit the lock_timeout of the application pool`() {
        // Registro da D1: o Boot 4.1 monta para o Flyway um SimpleDriverDataSource próprio (usuário de migração),
        // sem a inicialização do pool da aplicação.
        flyway.configuration.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SHOW lock_timeout").use { rows ->
                    rows.next()
                    rows.getString(1)
                }
            }
        } shouldBe "0"
    }

    // --- Exceções embrulhadas (Review Focus 1) e log (Review Focus 2) --------------------------------------------

    @Test
    fun `a lock or deadlock SQLSTATE anywhere in the chain answers 409 and is counted by SQLSTATE`(
        output: CapturedOutput,
    ) {
        val sentinel = "SENTINEL-${UUID.randomUUID()}"
        val cases =
            listOf(
                LOCK_NOT_AVAILABLE to
                    CannotAcquireLockException("lock $sentinel", SQLException("cause $sentinel", LOCK_NOT_AVAILABLE)),
                DEADLOCK to
                    IllegalStateException(
                        "wrapper $sentinel",
                        JpaSystemException(RuntimeException("jpa $sentinel", SQLException(sentinel, DEADLOCK))),
                    ),
                DEADLOCK to
                    UncategorizedSQLException(
                        "batch $sentinel",
                        "UPDATE $sentinel",
                        SQLException("batch $sentinel").apply { nextException = SQLException(sentinel, DEADLOCK) },
                    ),
            )

        cases.forEach { (state, failure) ->
            failure.addSuppressed(IllegalArgumentException("suppressed $sentinel"))
            val before = concurrentUpdates(state)
            val start = output.all.length

            val response = failWith(failure)

            withClue(failure.javaClass.simpleName) {
                response.status shouldBe 409
                val body = response.getContentAsString(Charsets.UTF_8)
                jsonMapper.readTree(body)["code"].asString() shouldBe "CONCURRENT_UPDATE"
                body shouldNotContain sentinel
                concurrentUpdates(state) shouldBe before + 1
                val logged = output.all.substring(start)
                logged shouldContain failure.javaClass.name
                logged shouldContain state
                logged shouldNotContain sentinel
            }
        }
    }

    @Test
    fun `another SQLSTATE and a rollback without cause are still a 500 and are not counted`() {
        val failures =
            listOf(
                DataIntegrityViolationException("unique", SQLException("duplicate key", "23505")),
                UnexpectedRollbackException("Transaction silently rolled back: marked as rollback-only"),
            )

        failures.forEach { failure ->
            val before = concurrentUpdates(LOCK_NOT_AVAILABLE) + concurrentUpdates(DEADLOCK)

            val response = failWith(failure)

            withClue(failure.javaClass.simpleName) {
                response.status shouldBe 500
                jsonMapper.readTree(response.getContentAsString(Charsets.UTF_8))["code"].asString() shouldBe
                    "INTERNAL_ERROR"
                concurrentUpdates(LOCK_NOT_AVAILABLE) + concurrentUpdates(DEADLOCK) shouldBe before
            }
        }
    }

    // --- Idempotência (Review Focus 3) ---------------------------------------------------------------------------

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a 409 CONCURRENT_UPDATE releases the idempotency key, and the retry with the same key runs again`() {
        val row = newRow()
        val key = UuidV7.generate().toString()
        val path = "/api/v1/test-concurrency/idempotent-locked?row=$row"

        val first = holdingRow(row) { post(path, key) }

        first.status shouldBe 409
        jsonMapper.readTree(first.getContentAsString(Charsets.UTF_8))["code"].asString() shouldBe "CONCURRENT_UPDATE"
        probe.idempotentLockedRuns.get() shouldBe 1

        val retry = post(path, key)

        retry.status shouldBe 200
        retry.getHeader(IDEMPOTENT_REPLAYED_HEADER) shouldBe null
        probe.idempotentLockedRuns.get() shouldBe 2
        // A resposta de sucesso ficou guardada: o terceiro envio a repete sem executar.
        val replay = post(path, key)
        replay.status shouldBe 200
        replay.getHeader(IDEMPOTENT_REPLAYED_HEADER) shouldBe "true"
        probe.idempotentLockedRuns.get() shouldBe 2
    }

    @Test
    fun `another 409 stays stored, and the retry with the same key replays it without running`() {
        val key = UuidV7.generate().toString()
        val path = "/api/v1/test-concurrency/idempotent-conflict"

        val first = post(path, key)

        first.status shouldBe 409
        jsonMapper.readTree(first.getContentAsString(Charsets.UTF_8))["code"].asString() shouldBe DOMAIN_CONFLICT
        probe.idempotentConflictRuns.get() shouldBe 1

        val retry = post(path, key)

        retry.status shouldBe 409
        retry.getHeader(IDEMPOTENT_REPLAYED_HEADER) shouldBe "true"
        jsonMapper.readTree(retry.getContentAsString(Charsets.UTF_8))["code"].asString() shouldBe DOMAIN_CONFLICT
        probe.idempotentConflictRuns.get() shouldBe 1
    }

    // --- Apoio ---------------------------------------------------------------------------------------------------

    private fun post(
        path: String,
        idempotencyKey: String? = null,
        auth: RequestPostProcessor = user,
    ): MockHttpServletResponse =
        mockMvc
            .post(path) {
                with(auth)
                idempotencyKey?.let { header(IDEMPOTENCY_KEY_HEADER, it) }
                contentType = MediaType.APPLICATION_JSON
                content = "{}"
            }.andReturn()
            .response

    private fun crossLock(
        name: String,
        first: UUID,
        second: UUID,
    ) = post("/api/v1/test-concurrency/cross-lock?name=$name&first=$first&second=$second")

    private fun failWith(failure: Exception): MockHttpServletResponse {
        probe.failure.set(failure)
        return post("/api/v1/test-concurrency/fail")
    }

    private fun newRow(): UUID =
        UuidV7.generate().also { id ->
            jdbc
                .sql("INSERT INTO test_support.time_probe (id, recorded_at) VALUES (:id, :at)")
                .param("id", id)
                .param("at", java.sql.Timestamp.from(Instant.now()))
                .update()
        }

    /** Segura [row] (`FOR UPDATE`) numa transação de outra thread enquanto [block] roda; a transação é desfeita. */
    private fun <T> holdingRow(
        row: UUID,
        block: () -> T,
    ): T {
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Executors.newSingleThreadExecutor()
        try {
            val holding =
                holder.submit {
                    tx.executeWithoutResult { status ->
                        jdbc
                            .sql("SELECT id FROM test_support.time_probe WHERE id = :id FOR UPDATE")
                            .param("id", row)
                            .query(UUID::class.java)
                            .single()
                        locked.countDown()
                        release.await(LATCH_SECONDS, TimeUnit.SECONDS)
                        status.setRollbackOnly()
                    }
                }
            if (!locked.await(LATCH_SECONDS, TimeUnit.SECONDS)) {
                if (holding.isDone) holding.get()
                error("A linha não foi travada em $LATCH_SECONDS s")
            }
            return block()
        } finally {
            release.countDown()
            holder.shutdown()
            holder.awaitTermination(LATCH_SECONDS, TimeUnit.SECONDS)
            holder.shutdownNow()
        }
    }

    /** Espera, com prazo, uma sessão bloqueada por [holder] (`pg_blocking_pids`); [contender] não termina antes. */
    private fun awaitBlockedBy(
        holder: Int,
        contender: Future<*>,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LATCH_SECONDS)
        while (blockedBy(holder) == 0) {
            withClue("a segunda requisição terminou sem esperar a primeira") { contender.isDone shouldBe false }
            check(System.nanoTime() < deadline) { "A segunda requisição não chegou a esperar a sessão $holder" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun blockedBy(holder: Int): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM pg_stat_activity a " +
                    "WHERE a.datname = current_database() AND :holder = ANY (pg_blocking_pids(a.pid))",
            ).param("holder", holder)
            .query(Int::class.java)
            .single()

    private fun concurrentUpdates(state: String): Double =
        meters
            .find(CONCURRENT_UPDATES)
            .tag("sqlstate", state)
            .counter()
            ?.count() ?: 0.0

    // --- Caso de uso e controller só de teste --------------------------------------------------------------------

    /** As portas de uma requisição do deadlock: o pid da sessão, "travou a primeira linha" e "pode seguir". */
    class CrossLockGate {
        val pid = AtomicInteger()
        val locked = CountDownLatch(1)
        val proceed = CountDownLatch(1)
    }

    /** Casos de uso de teste: travam linhas de `test_support.time_probe` ou lançam a falha pedida pelo teste. */
    class ConcurrencyProbe(
        private val jdbc: JdbcClient,
        transactionManager: PlatformTransactionManager,
    ) {
        private val tx = TransactionTemplate(transactionManager)
        private val gates = ConcurrentHashMap<String, CrossLockGate>()
        val failure = AtomicReference<Exception?>()
        val idempotentLockedRuns = AtomicInteger()
        val idempotentConflictRuns = AtomicInteger()

        fun reset() {
            gates.clear()
            failure.set(null)
            idempotentLockedRuns.set(0)
            idempotentConflictRuns.set(0)
        }

        fun gate(name: String): CrossLockGate = gates.computeIfAbsent(name) { CrossLockGate() }

        /** Trava [first], avisa e espera a porta do teste, depois trava [second]: a ordem cruzada dá o deadlock. */
        fun crossLock(
            name: String,
            first: UUID,
            second: UUID,
        ) {
            val gate = gate(name)
            tx.executeWithoutResult {
                lock(first)
                gate.pid.set(jdbc.sql("SELECT pg_backend_pid()").query(Int::class.java).single())
                gate.locked.countDown()
                check(gate.proceed.await(LATCH_SECONDS, TimeUnit.SECONDS)) { "A porta do teste não abriu" }
                lock(second)
            }
        }

        /** Trava [row] com um `lock_timeout` curto, para não esperar os 10 s da aplicação. */
        fun lockBriefly(row: UUID) {
            tx.executeWithoutResult {
                jdbc.sql("SET LOCAL lock_timeout = '$SHORT_LOCK_TIMEOUT'").update()
                lock(row)
            }
        }

        private fun lock(row: UUID) {
            jdbc
                .sql("SELECT id FROM test_support.time_probe WHERE id = :id FOR UPDATE")
                .param("id", row)
                .query(UUID::class.java)
                .single()
        }
    }

    @RestController
    class ConcurrencyProbeController(
        private val probe: ConcurrencyProbe,
    ) {
        @PostMapping("/api/v1/test-concurrency/cross-lock")
        fun crossLock(
            @RequestParam name: String,
            @RequestParam first: UUID,
            @RequestParam second: UUID,
        ): Map<String, Boolean> {
            probe.crossLock(name, first, second)
            return OK
        }

        @PostMapping("/api/v1/test-concurrency/locked")
        fun locked(
            @RequestParam row: UUID,
        ): Map<String, Boolean> {
            probe.lockBriefly(row)
            return OK
        }

        @Idempotent
        @PostMapping("/api/v1/test-concurrency/idempotent-locked")
        fun idempotentLocked(
            @RequestParam row: UUID,
        ): Map<String, Boolean> {
            probe.idempotentLockedRuns.incrementAndGet()
            probe.lockBriefly(row)
            return OK
        }

        @Idempotent
        @PostMapping("/api/v1/test-concurrency/idempotent-conflict")
        fun idempotentConflict(): Map<String, Boolean> {
            probe.idempotentConflictRuns.incrementAndGet()
            throw ConflictException(ErrorCode(DOMAIN_CONFLICT), "Conflito de domínio do teste.")
        }

        @PostMapping("/api/v1/test-concurrency/fail")
        fun fail(): Map<String, Boolean> = throw checkNotNull(probe.failure.get())
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ProbeConfiguration {
        @Bean
        fun concurrencyProbe(
            jdbc: JdbcClient,
            transactionManager: PlatformTransactionManager,
        ) = ConcurrencyProbe(jdbc, transactionManager)

        @Bean
        fun concurrencyProbeController(probe: ConcurrencyProbe) = ConcurrencyProbeController(probe)
    }

    private companion object {
        const val CONCURRENT_UPDATES = "zeny.api.concurrent.updates"
        const val DEADLOCK = "40P01"
        const val LOCK_NOT_AVAILABLE = "55P03"
        const val DOMAIN_CONFLICT = "TEST_DOMAIN_CONFLICT"
        const val SHORT_LOCK_TIMEOUT = "200ms"
        const val LATCH_SECONDS = 20L
        const val POLL_MILLIS = 20L
        val OK = mapOf("ok" to true)
    }
}
