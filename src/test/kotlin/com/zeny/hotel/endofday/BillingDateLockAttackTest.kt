package com.zeny.hotel.endofday

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.billing.BillingPermissions
import com.zeny.hotel.billing.BillingTestSupport
import com.zeny.hotel.billing.CardPreAuthorizationId
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.billing.NewFolio
import com.zeny.hotel.billing.NewPosting
import com.zeny.hotel.billing.PostingId
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.PostingSummary
import com.zeny.hotel.billing.TransactionCodeKey
import com.zeny.hotel.billing.application.CardPreAuthorizationService
import com.zeny.hotel.billing.application.FolioService
import com.zeny.hotel.billing.application.PaymentInput
import com.zeny.hotel.billing.application.PaymentService
import com.zeny.hotel.billing.application.PostingService
import com.zeny.hotel.billing.application.PostingTransferService
import com.zeny.hotel.billing.application.PreAuthorizationInput
import com.zeny.hotel.billing.application.SplitBy
import com.zeny.hotel.billing.application.SplitPartCommand
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Ataque ao carimbo do `billing` com a data operacional travada (RF-FEC-05, M6 E11, Task 8), nos caminhos de escrita
 * que não são o lançamento (este está no `BillingDateLockTest`): pagamento, reembolso, transferência, divisão, estorno,
 * ajuste, remoção da taxa de serviço, registro, captura e liberação de caução, reabertura e encerramento de conta.
 *
 * Para cada caminho: a transação que leu a data antes do avanço e escreveu depois dele grava a data nova; o caminho em
 * curso segura o avanço até o commit e fica na data velha. E ainda: a chave de origem já lançada devolve o lançamento
 * existente (sem duplicar, mesmo esperando o avanço); duas contas travadas antes da data por uma transferência, com um
 * lançamento (data antes da conta) e o avanço na fila, não fazem deadlock; e o lançamento numa transação só de leitura
 * falha antes de gravar.
 *
 * Banco isolado porque avança a data, direto pela `HotelApi` (sem execução de fechamento). Toda transação das threads
 * do teste tem `lock_timeout`, todo latch tem prazo e o `finally` solta os latches, encerra as threads e chama
 * [EndOfDayTestRuns.settle], que não acha execução da data corrente.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList", "LargeClass", "TooManyFunctions") // os serviços de cada caminho; um cenário por caminho
class BillingDateLockAttackTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val billingApi: BillingApi,
    @Autowired private val folios: FolioService,
    @Autowired private val postings: PostingService,
    @Autowired private val transfers: PostingTransferService,
    @Autowired private val payments: PaymentService,
    @Autowired private val preAuthorizations: CardPreAuthorizationService,
    @Autowired transactionManager: PlatformTransactionManager,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired private val clock: Clock,
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val write = TransactionTemplate(transactionManager)
    private val readOnly = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val billing = BillingTestSupport(iam, mockMvc, jsonMapper)

    /** Os caminhos de escrita do `billing` que carimbam a data, fora o lançamento. */
    enum class WritePath {
        PAYMENT,
        REFUND,
        TRANSFER,
        SPLIT,
        REVERSAL,
        ADJUSTMENT,
        SERVICE_CHARGE_REMOVAL,
        PRE_AUTHORIZATION_REGISTRATION,
        PRE_AUTHORIZATION_CAPTURE,
        PRE_AUTHORIZATION_RELEASE,
        REOPENING,
        CLOSING,
    }

    @ParameterizedTest
    @EnumSource(WritePath::class)
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a write whose transaction read the date before the advance is stamped with the new date`(path: WritePath) {
        val advanced = CountDownLatch(1)
        withThreads(advanced) { pool ->
            val current = hotel.currentBusinessDate()
            val operator = operator()
            val scenario = prepare(path, operator)
            val read = CountDownLatch(1)

            val writer =
                pool.submit<LocalDate> {
                    asOperator(operator) {
                        inWrite {
                            val before = hotel.currentBusinessDate()
                            read.countDown()
                            advanced.await(LATCH_SECONDS, TimeUnit.SECONDS)
                            scenario.act()
                            before
                        }
                    }
                }
            read.await(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe true
            advance(current) shouldBe current.plusDays(1)
            advanced.countDown()

            writer.get(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe current
            scenario.dates().toSet() shouldBe setOf(current.plusDays(1))
        }
    }

    @ParameterizedTest
    @EnumSource(WritePath::class)
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a write in flight holds the advance until its commit and keeps the date it stamped`(path: WritePath) {
        val release = CountDownLatch(1)
        withThreads(release) { pool ->
            val current = hotel.currentBusinessDate()
            val operator = operator()
            val scenario = prepare(path, operator)
            val wrote = CountDownLatch(1)

            val writer =
                pool.submit<Unit> {
                    asOperator(operator) {
                        inWrite {
                            scenario.act()
                            wrote.countDown()
                            release.await(LATCH_SECONDS, TimeUnit.SECONDS)
                        }
                    }
                }
            wrote.await(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe true
            val advance = pool.submit<LocalDate> { advance(current) }
            awaitLockWaiter(ADVANCE_QUERY)
            shouldThrow<TimeoutException> { advance.get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS) }

            release.countDown()
            writer.get(LATCH_SECONDS, TimeUnit.SECONDS)
            advance.get(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe current.plusDays(1)
            scenario.dates().toSet() shouldBe setOf(current)
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a source reference already posted on a closed date returns that posting and never posts it again`() {
        withThreads { _ ->
            val current = hotel.currentBusinessDate()
            val folio = openFolio()
            val key = sourceReference(current)
            val first = inWrite { billingApi.post(folio, roomCharge(key)) }

            advance(current) shouldBe current.plusDays(1)
            val again = inWrite { billingApi.post(folio, roomCharge(key)) }

            again.id shouldBe first.id
            again.businessDate shouldBe current
            keyedDates(key) shouldBe listOf(current)
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a repeated source reference asked while the advance holds the date waits and returns the existing one`() {
        val commit = CountDownLatch(1)
        withThreads(commit) { pool ->
            val current = hotel.currentBusinessDate()
            val folio = openFolio()
            val key = sourceReference(current)
            val first = inWrite { billingApi.post(folio, roomCharge(key)) }
            val advanced = CountDownLatch(1)

            val advance =
                pool.submit<LocalDate> {
                    inWrite {
                        hotel.advanceBusinessDate(current).current.also {
                            advanced.countDown()
                            commit.await(LATCH_SECONDS, TimeUnit.SECONDS)
                        }
                    }
                }
            advanced.await(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe true
            val repeat = pool.submit<PostingSummary> { inWrite { billingApi.post(folio, roomCharge(key)) } }
            awaitLockWaiter(SHARE_QUERY)

            commit.countDown()
            advance.get(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe current.plusDays(1)
            val again = repeat.get(LATCH_SECONDS, TimeUnit.SECONDS)
            again.id shouldBe first.id
            again.businessDate shouldBe current
            keyedDates(key) shouldBe listOf(current)
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `two first posts of one source reference around a waiting advance leave one posting on the old date`() {
        val release = CountDownLatch(1)
        withThreads(release) { pool ->
            val current = hotel.currentBusinessDate()
            val folio = openFolio()
            val key = sourceReference(current)
            val posted = CountDownLatch(1)

            val first =
                pool.submit<PostingSummary> {
                    inWrite {
                        billingApi.post(folio, roomCharge(key)).also {
                            posted.countDown()
                            release.await(LATCH_SECONDS, TimeUnit.SECONDS)
                        }
                    }
                }
            posted.await(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe true
            // O segundo trava a data (FOR SHARE, atendido) e espera a conta, que o primeiro segura.
            val second = pool.submit<PostingSummary> { inWrite { billingApi.post(folio, roomCharge(key)) } }
            awaitLockWaiter(FOLIO_QUERY)
            val advance = pool.submit<LocalDate> { advance(current) }
            awaitLockWaiter(ADVANCE_QUERY)

            release.countDown()
            val original = first.get(LATCH_SECONDS, TimeUnit.SECONDS)
            val repeated = second.get(LATCH_SECONDS, TimeUnit.SECONDS)
            advance.get(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe current.plusDays(1)
            repeated.id shouldBe original.id
            original.businessDate shouldBe current
            repeated.businessDate shouldBe current
            keyedDates(key) shouldBe listOf(current)
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a transfer holding two folios before the date does not deadlock with a posting and the advance in line`() {
        val proceed = CountDownLatch(1)
        withThreads(proceed) { pool ->
            val current = hotel.currentBusinessDate()
            val operator = operator()
            val source = openFolio()
            val target = openFolio()
            val original = inWrite { billingApi.post(source, roomCharge()) }.id
            val foliosLocked = CountDownLatch(1)

            // A: a transferência trava as duas contas (aqui antes, por JDBC, para segurar) e só depois a data.
            val transfer =
                pool.submit<List<LocalDate>> {
                    asOperator(operator) {
                        inWrite {
                            jdbc
                                .sql("SELECT id FROM billing.folio WHERE id IN (:ids) ORDER BY id FOR UPDATE")
                                .param("ids", listOf(source.value, target.value))
                                .query(UUID::class.java)
                                .list()
                            foliosLocked.countDown()
                            proceed.await(LATCH_SECONDS, TimeUnit.SECONDS)
                            transfers
                                .transfer(source, target, listOf(original), null)
                                .flatMap { listOf(it.outgoing.businessDate, it.incoming.businessDate) }
                        }
                    }
                }
            foliosLocked.await(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe true

            // B: o lançamento trava a data (FOR SHARE) e espera a conta de destino.
            val posting = pool.submit<PostingSummary> { inWrite { billingApi.post(target, roomCharge()) } }
            awaitLockWaiter(FOLIO_QUERY)

            // C: o avanço espera a data atrás de B.
            val advance = pool.submit<LocalDate> { advance(current) }
            awaitLockWaiter(ADVANCE_QUERY)

            // A pede a data com C na fila: atendido, transfere, confirma e solta as contas.
            proceed.countDown()
            transfer.get(GRANTED_SECONDS, TimeUnit.SECONDS) shouldBe listOf(current, current)
            posting.get(LATCH_SECONDS, TimeUnit.SECONDS).businessDate shouldBe current
            advance.get(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe current.plusDays(1)
            postingDates(listOf(source, target), except = original).toSet() shouldBe setOf(current)
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a posting joined to a read-only transaction fails before writing anything`() {
        withThreads { _ ->
            val folio = openFolio()

            val failure =
                shouldThrow<IllegalStateException> { readOnly.execute { billingApi.post(folio, roomCharge()) } }

            failure.message shouldContain "transação de escrita"
            postingDates(listOf(folio), except = null) shouldBe emptyList()
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a folio closing joined to a read-only transaction fails and leaves the folio open`() {
        withThreads { _ ->
            val folio = openFolio()

            shouldThrowAny { readOnly.execute { billingApi.closeFolio(folio) } }

            folioStatus(folio) shouldBe "OPEN"
        }
    }

    /** Um caminho preparado na data corrente: [act] escreve na transação de quem chama; [dates], as datas gravadas. */
    private class Scenario(
        val act: () -> Unit,
        val dates: () -> List<LocalDate>,
    )

    @Suppress("LongMethod") // um cenário por caminho
    private fun prepare(
        path: WritePath,
        operator: UserId,
    ): Scenario {
        val folio = openFolio()
        return when (path) {
            WritePath.PAYMENT -> {
                Scenario({ payments.receive(folio, pix()) }) { paymentDates(folio, refunds = false) }
            }

            WritePath.REFUND -> {
                val paid = asOperator(operator) { inWrite { payments.receive(folio, pix()) } }.id
                Scenario({ payments.refund(folio, paid, money("10.00"), "Desistiu", null, null, null) }) {
                    paymentDates(folio, refunds = true)
                }
            }

            WritePath.TRANSFER -> {
                val original = charge(folio)
                val target = openFolio()
                Scenario({ transfers.transfer(folio, target, listOf(original), null) }) {
                    postingDates(listOf(folio, target), except = original)
                }
            }

            WritePath.SPLIT -> {
                val original = charge(folio)
                val target = openFolio()
                val parts =
                    listOf(
                        SplitPartCommand(folio, amount = money("60.00")),
                        SplitPartCommand(target, amount = money("40.00")),
                    )
                Scenario({ transfers.split(folio, original, SplitBy.AMOUNT, parts, null) }) {
                    postingDates(listOf(folio, target), except = original)
                }
            }

            WritePath.REVERSAL -> {
                val original = charge(folio)
                Scenario({ postings.reverse(folio, original, "Não consumiu", null) }) { correctionDates(original) }
            }

            WritePath.ADJUSTMENT -> {
                val original = charge(folio)
                Scenario({ postings.adjust(folio, original, money("10.00"), "Desconto", null) }) {
                    correctionDates(original)
                }
            }

            WritePath.SERVICE_CHARGE_REMOVAL -> {
                val item = inWrite { billingApi.post(folio, foodWithServiceCharge()) }
                val serviceCharge = checkNotNull(item.serviceCharge).id
                Scenario({ postings.removeServiceCharge(folio, serviceCharge) }) { correctionDates(serviceCharge) }
            }

            WritePath.PRE_AUTHORIZATION_REGISTRATION -> {
                Scenario({ preAuthorizations.register(folio, preAuthorization()) }) { preAuthorizationDates(folio) }
            }

            WritePath.PRE_AUTHORIZATION_CAPTURE -> {
                val id = register(operator, folio)
                Scenario({ preAuthorizations.capture(folio, id, money("80.00"), null, null) }) {
                    paymentDates(folio, refunds = false)
                }
            }

            WritePath.PRE_AUTHORIZATION_RELEASE -> {
                val id = register(operator, folio)
                Scenario({ preAuthorizations.release(folio, id) }) { releaseDates(folio) }
            }

            WritePath.REOPENING -> {
                asOperator(operator) { inWrite { folios.close(folio) } }
                Scenario({ folios.reopen(folio, "Cobrança esquecida", null) }) { reopeningDates(folio) }
            }

            WritePath.CLOSING -> {
                Scenario({ folios.close(folio) }) { listOfNotNull(folioDate(folio, "closed_on")) }
            }
        }
    }

    /** Um operador com todas as permissões do `billing` (sem teto) e um turno de caixa aberto. */
    private fun operator(): UserId {
        val grants =
            BillingPermissions.permissions.map { definition ->
                TestGrant(
                    definition,
                    if (PermissionScope.ALL in definition.scopes) PermissionScope.ALL else definition.scopes.first(),
                    definition.limits.associate { it.key to LimitValue.Unlimited },
                )
            }
        val user = iam.createUserWith(*grants.toTypedArray())
        billing.openShift(iam.asTestUser(user))
        return user.id
    }

    private fun <T> asOperator(
        operator: UserId,
        block: () -> T,
    ): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(operator.toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        val previous = SecurityContextHolder.getContext().authentication
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.getContext().authentication = previous
        }
    }

    private fun <T : Any> inWrite(block: () -> T): T =
        checkNotNull(
            write.execute {
                setLockTimeout()
                block()
            },
        )

    private fun openFolio(): FolioId = billingApi.openFolio(NewFolio(FolioOwnerType.RESERVATION, UuidV7.generate())).id

    private fun charge(folio: FolioId): PostingId = inWrite { billingApi.post(folio, roomCharge()) }.id

    private fun register(
        operator: UserId,
        folio: FolioId,
    ): CardPreAuthorizationId =
        asOperator(operator) { inWrite { preAuthorizations.register(folio, preAuthorization()) } }.id

    private fun roomCharge(sourceReference: String? = null): NewPosting =
        NewPosting(
            transactionCodeId = billingApi.transactionCode(TransactionCodeKey.ROOM_CHARGE).id,
            unitPrice = money("100.00"),
            source = PostingSource.MANUAL,
            sourceReference = sourceReference,
        )

    private fun foodWithServiceCharge(): NewPosting =
        NewPosting(
            transactionCodeId = billingApi.transactionCode(TransactionCodeKey.FOOD_BEVERAGE).id,
            unitPrice = money("100.00"),
            source = PostingSource.MANUAL,
            serviceChargeRate = Percent.of("10"),
        )

    private fun sourceReference(date: LocalDate): String = "reservations.reservation:${UuidV7.generate()}:$date:room"

    private fun pix(): PaymentInput = PaymentInput("PIX", money("50.00"))

    private fun preAuthorization(): PreAuthorizationInput =
        PreAuthorizationInput(money("200.00"), clock.instant().plus(Duration.ofDays(1)), authorizationCode = "AUT1")

    private fun money(value: String): Money = Money.of(BigDecimal(value))

    private fun advance(current: LocalDate): LocalDate = inWrite { hotel.advanceBusinessDate(current).current }

    private fun dates(
        sql: String,
        vararg params: Pair<String, Any>,
    ): List<LocalDate> =
        params
            .fold(jdbc.sql(sql)) { spec, (name, value) -> spec.param(name, value) }
            .query(LocalDate::class.java)
            .list()
            .filterNotNull()

    private fun paymentDates(
        folio: FolioId,
        refunds: Boolean,
    ): List<LocalDate> =
        dates(
            "SELECT business_date FROM billing.payment WHERE folio_id = :folio AND (refund_of_id IS NULL) <> :refunds",
            "folio" to folio.value,
            "refunds" to refunds,
        )

    /** As datas dos lançamentos das [folios], menos o [except] (o original de uma transferência ou divisão). */
    private fun postingDates(
        folios: List<FolioId>,
        except: PostingId?,
    ): List<LocalDate> =
        dates(
            "SELECT business_date FROM billing.posting WHERE folio_id IN (:folios) AND id <> :except",
            "folios" to folios.map { it.value },
            "except" to (except?.value ?: UuidV7.generate()),
        )

    private fun correctionDates(original: PostingId): List<LocalDate> =
        dates("SELECT business_date FROM billing.posting WHERE corrects_id = :original", "original" to original.value)

    private fun keyedDates(key: String): List<LocalDate> =
        dates("SELECT business_date FROM billing.posting WHERE source_reference = :key", "key" to key)

    private fun preAuthorizationDates(folio: FolioId): List<LocalDate> =
        dates(
            "SELECT business_date FROM billing.card_pre_authorization WHERE folio_id = :folio",
            "folio" to folio.value,
        )

    private fun releaseDates(folio: FolioId): List<LocalDate> =
        dates(
            "SELECT business_date FROM billing.card_pre_authorization_release WHERE folio_id = :folio",
            "folio" to folio.value,
        )

    /** A data da reabertura e a da conta reaberta (`reopened_on`). */
    private fun reopeningDates(folio: FolioId): List<LocalDate> =
        dates("SELECT business_date FROM billing.folio_reopening WHERE folio_id = :folio", "folio" to folio.value) +
            listOfNotNull(folioDate(folio, "reopened_on"))

    private fun folioDate(
        folio: FolioId,
        column: String,
    ): LocalDate? =
        jdbc
            .sql("SELECT $column FROM billing.folio WHERE id = :id")
            .param("id", folio.value)
            .query(LocalDate::class.java)
            .optional()
            .orElse(null)

    private fun folioStatus(folio: FolioId): String =
        jdbc
            .sql("SELECT status FROM billing.folio WHERE id = :id")
            .param("id", folio.value)
            .query(String::class.java)
            .single()

    private fun setLockTimeout() {
        jdbc.sql("SET LOCAL lock_timeout = '$LOCK_TIMEOUT'").update()
    }

    /** Espera, com prazo, alguma sessão deste banco esperar trava numa consulta que case com [pattern]. */
    private fun awaitLockWaiter(pattern: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LATCH_SECONDS)
        while (lockWaiters(pattern) == 0) {
            check(System.nanoTime() < deadline) { "Nenhuma sessão ficou esperando trava em '$pattern'" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun lockWaiters(pattern: String): Int =
        jdbc
            .sql(
                """
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database() AND pid <> pg_backend_pid()
                  AND wait_event_type = 'Lock' AND query ILIKE :pattern
                """.trimIndent(),
            ).param("pattern", pattern)
            .query(Int::class.java)
            .single()

    /**
     * Roda [block] com um pool de threads e, mesmo se ele falhar, solta [latches], encerra as threads e cumpre a regra
     * do banco isolado ([EndOfDayTestRuns.settle]).
     */
    private fun withThreads(
        vararg latches: CountDownLatch,
        block: (ExecutorService) -> Unit,
    ) {
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            block(pool)
        } finally {
            latches.forEach { it.countDown() }
            pool.shutdown()
            pool.awaitTermination(LATCH_SECONDS * 2, TimeUnit.SECONDS)
            pool.shutdownNow()
            EndOfDayTestRuns.settle(context)
        }
    }

    private companion object {
        const val THREADS = 4
        const val LATCH_SECONDS = 10L
        const val GRANTED_SECONDS = 3L
        const val BLOCKED_MILLIS = 500L
        const val POLL_MILLIS = 20L
        const val LOCK_TIMEOUT = "15s"
        const val FOLIO_QUERY = "%billing.folio%update%"
        const val SHARE_QUERY = "%business_day%for share%"
        const val ADVANCE_QUERY = "%business_day%update%"
    }
}
