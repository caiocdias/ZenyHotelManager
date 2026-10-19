package com.zeny.hotel.billing

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Taxa de serviço no banco e na concorrência (M3 E06, RF-CTA-14, RN-03, RN-10): o trigger e os `CHECK` que barram
 * uma taxa incoerente (sobre um estorno, noutro código, ajustada à parte, em dobro, ou a entrada de uma taxa
 * transferida citando a entrada de outro item) e a remoção simultânea ao estorno do item, que estorna a taxa uma vez.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class)
class ServiceChargeIntegrityTest(
    @Autowired iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired private val jdbc: JdbcClient,
    @Autowired flyway: Flyway,
) {
    private val billing = BillingTestSupport(iam, mockMvc, jsonMapper)
    private val owner = JdbcClient.create(flyway.configuration.dataSource)
    private val ownerTransactions =
        TransactionTemplate(DataSourceTransactionManager(flyway.configuration.dataSource))

    @Test
    fun `the database refuses an incoherent charge and a correction of the charge apart from the item`(): Unit =
        with(billing) {
            val folio = openFolio()
            val item = post(folio, createCode(serviceCharge = true, rate = "10"), "40.00")
            val reversal = billing.reverseWithReason(folio, item["id"].asString()).expect(201)
            val chargeId = UUID.fromString(item["serviceChargePostingId"].asString())
            val txServico = codeId("SERVICE_CHARGE")

            // Taxa sobre um estorno: o item precisa ser um lançamento normal.
            refusedInsert(
                "cita um item",
                "SERVICE_CHARGE",
                folio,
                txServico,
                "1.00",
                serviceChargeFor = reversal.uuid("id"),
            )
            // Taxa num código que não é de taxa de serviço (sobre um item ainda sem taxa).
            val room = post(folio, codeId("ROOM_CHARGE"), "10.00")
            refusedInsert("cita um item", "SERVICE_CHARGE", folio, codeId("ROOM_CHARGE"), "1.00", room.uuid("id"))
            // Ajuste da taxa sem o lançamento do item que o provocou.
            refusedInsert(
                "junto do item",
                "ADJUSTMENT",
                folio,
                txServico,
                "-1.00",
                correctsId = chargeId,
                reason = "Ajuste à parte",
            )
            // Uma segunda taxa para o mesmo item.
            refusedInsert("posting_service_charge_uk", "SERVICE_CHARGE", folio, txServico, "1.00", item.uuid("id"))
            postings(folio).size shouldBe 5
        }

    @Test
    fun `the removal and the reversal of the item at the same time reverse the charge once, without deadlock`() {
        val code = billing.createCode(serviceCharge = true, rate = "10")
        repeat(ROUNDS) { _ ->
            val folio = billing.openFolio()
            val item = billing.post(folio, code, "100.00")
            val chargeId = item["serviceChargePostingId"].asString()
            val results =
                concurrently(
                    listOf(
                        { billing.removeCharge(folio, chargeId, billing.admin) },
                        { billing.reverseWithReason(folio, item["id"].asString()) },
                    ),
                )
            // O estorno do item passa sempre; a remoção passa se veio antes, senão a taxa já está estornada.
            results[1].status shouldBe 201
            with(billing) {
                if (results[0].status != 201) results[0].problem(409, "POSTING_ALREADY_REVERSED")
            }
            jdbc
                .sql("SELECT count(*) FROM billing.posting WHERE posting_type = 'REVERSAL' AND corrects_id = :id")
                .param("id", UUID.fromString(chargeId))
                .query(Long::class.java)
                .single() shouldBe 1L
            billing.balance(folio) shouldBe "0.00"
        }
    }

    @Test
    fun `the database refuses the entry of a transferred charge citing the entry of another item`(): Unit =
        with(billing) {
            val source = openFolio()
            val target = openFolio("GROUP")
            val code = createCode(serviceCharge = true, rate = "10")
            val first = post(source, code, "40.00")
            val second = post(source, code, "60.00")
            val txServico = codeId("SERVICE_CHARGE")

            // Os pares dos dois itens e a saída da taxa do primeiro; a entrada da taxa cita a de um dos dois.
            fun forge(linkToSecond: Boolean) {
                val firstOut =
                    rawInsert("TRANSFER_OUT", source, code, "-40.00", corrects = first.uuid("id"))
                val firstIn = rawInsert("TRANSFER_IN", target, code, "40.00", transferredFrom = firstOut)
                val secondOut =
                    rawInsert("TRANSFER_OUT", source, code, "-60.00", corrects = second.uuid("id"))
                val secondIn = rawInsert("TRANSFER_IN", target, code, "60.00", transferredFrom = secondOut)
                val chargeOut =
                    rawInsert(
                        "TRANSFER_OUT",
                        source,
                        txServico,
                        "-4.00",
                        corrects = first.uuid("serviceChargePostingId"),
                        triggeredBy = firstOut,
                    )
                rawInsert(
                    "TRANSFER_IN",
                    target,
                    txServico,
                    "4.00",
                    transferredFrom = chargeOut,
                    serviceChargeFor = if (linkToSecond) secondIn else firstIn,
                )
            }

            // Com a entrada do mesmo par, o banco aceita (desfeito no fim, só para provar o cenário).
            ownerTransactions.executeWithoutResult {
                forge(linkToSecond = false)
                it.setRollbackOnly()
            }
            // Com a entrada do outro item, recusa.
            val refusal =
                shouldThrow<DataAccessException> {
                    ownerTransactions.executeWithoutResult { forge(linkToSecond = true) }
                }
            generateSequence<Throwable>(
                refusal,
            ) { it.cause }.mapNotNull { it.message }.joinToString(" | ") shouldContain
                "do mesmo par"
            postings(source).size shouldBe 4
            postings(target).size shouldBe 0
        }

    @Suppress("LongParameterList") // as colunas do lançamento inserido à mão
    private fun rawInsert(
        type: String,
        folio: UUID,
        code: UUID,
        amount: String,
        corrects: UUID? = null,
        transferredFrom: UUID? = null,
        serviceChargeFor: UUID? = null,
        triggeredBy: UUID? = null,
    ): UUID {
        val id = UuidV7.generate()
        owner
            .sql(
                """
                INSERT INTO billing.posting (id, folio_id, transaction_code_id, posting_type, quantity, unit_price,
                    amount, description, source, business_date, posted_at, corrects_id, transferred_from_id,
                    service_charge_for_id, service_charge_rate, triggered_by_id)
                VALUES (:id, :folio, :code, :type, 1, :amount, :amount, 'Manual', 'MANUAL', current_date, now(),
                    :corrects, :transferredFrom, :for, CASE WHEN CAST(:for AS uuid) IS NULL THEN NULL ELSE 10 END,
                    :triggeredBy)
                """.trimIndent(),
            ).param("id", id)
            .param("folio", folio)
            .param("code", code)
            .param("type", type)
            .param("amount", BigDecimal(amount))
            .param("corrects", corrects)
            .param("transferredFrom", transferredFrom)
            .param("for", serviceChargeFor)
            .param("triggeredBy", triggeredBy)
            .update()
        return id
    }

    private fun concurrently(calls: List<() -> MockHttpServletResponse>): List<MockHttpServletResponse> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(calls.size)
        try {
            return calls
                .map { call ->
                    pool.submit(
                        Callable {
                            start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
                            call()
                        },
                    )
                }.also { start.countDown() }
                .map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Suppress("LongParameterList") // as colunas do lançamento inserido à mão
    private fun refusedInsert(
        message: String,
        type: String,
        folio: UUID,
        code: UUID,
        amount: String,
        serviceChargeFor: UUID? = null,
        correctsId: UUID? = null,
        reason: String? = null,
    ) {
        val refusal =
            shouldThrow<DataAccessException> {
                owner
                    .sql(
                        """
                        INSERT INTO billing.posting (id, folio_id, transaction_code_id, posting_type, quantity, unit_price,
                            amount, description, source, business_date, posted_at, corrects_id, reason,
                            service_charge_for_id, service_charge_rate)
                        VALUES (:id, :folio, :code, :type, 1, :amount, :amount, 'Taxa', 'MANUAL', current_date, now(),
                            :corrects, :reason, :for, CASE WHEN CAST(:for AS uuid) IS NULL THEN NULL ELSE 10 END)
                        """.trimIndent(),
                    ).param("id", UuidV7.generate())
                    .param("folio", folio)
                    .param("code", code)
                    .param("type", type)
                    .param("amount", BigDecimal(amount))
                    .param("corrects", correctsId)
                    .param("reason", reason)
                    .param("for", serviceChargeFor)
                    .update()
            }
        generateSequence<Throwable>(refusal) { it.cause }.mapNotNull { it.message }.joinToString(" | ") shouldContain
            message
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L
        const val ROUNDS = 5
    }
}
