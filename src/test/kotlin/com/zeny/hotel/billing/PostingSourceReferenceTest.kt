package com.zeny.hotel.billing

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.ErrorKind
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Lançamento idempotente por chave de origem (M5 E04, H14): a [BillingApi.post] com `sourceReference` lança uma vez só,
 * mesmo repetida com a conta já encerrada (o check-out e o fechamento do dia usam a mesma chave); sem a chave, nunca
 * deduplica; chave fora do formato é recusada.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList") // os beans que os cenários usam
class PostingSourceReferenceTest(
    @Autowired iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired private val billingApi: BillingApi,
    @Autowired private val jdbc: JdbcClient,
) {
    private val billing = BillingTestSupport(iam, mockMvc, jsonMapper)

    @Test
    fun `a posting with a source reference is recorded once, and the repetition returns the same posting`(): Unit =
        with(billing) {
            val folio = openApiFolio()
            val key = key()
            val posting = roomCharge(key)
            val first = billingApi.post(folio, posting)
            val again = billingApi.post(folio, posting)

            again.id shouldBe first.id
            again.sourceReference shouldBe key
            jdbc
                .sql("SELECT count(*) FROM billing.posting WHERE source_reference = :key")
                .param("key", key)
                .query(Long::class.java)
                .single() shouldBe 1L
            folio(folio.value)["balance"].asString() shouldBe "310.00"
            val auditedBefore = auditEntries("billing.posting.create")
            billingApi.post(folio, posting)
            auditEntries("billing.posting.create") shouldBe auditedBefore
            jdbc
                .sql(
                    "SELECT count(*) FROM audit.audit_entry " +
                        "WHERE entity_id = :id AND action = 'billing.posting.create'",
                ).param("id", first.id.value.toString())
                .query(Long::class.java)
                .single() shouldBe 1L
        }

    @Test
    fun `the repetition returns the existing posting even with the folio closed`(): Unit =
        with(billing) {
            val folio = openApiFolio()
            val posting = roomCharge(key())
            val first = billingApi.post(folio, posting)
            call(
                POST,
                "/api/v1/folios/${folio.value}/postings/${first.id.value}/reverse",
                admin,
                mapOf("reason" to "Zera a conta para encerrar"),
            ).expect(201)
            billingApi.closeFolio(folio).status shouldBe FolioStatus.CLOSED

            billingApi.post(folio, posting).id shouldBe first.id
            val other = shouldThrow<DomainException> { billingApi.post(folio, roomCharge(key())) }
            other.kind shouldBe ErrorKind.CONFLICT
            other.code.value shouldBe "FOLIO_CLOSED"
        }

    @Test
    fun `a service charge item repeated by key returns the item with its service charge`(): Unit =
        with(billing) {
            val folio = openApiFolio()
            val posting =
                NewPosting(
                    TransactionCodeId(codeId("FOOD_BEVERAGE")),
                    Money.of("100.00"),
                    source = PostingSource.MANUAL,
                    serviceChargeRate = Percent.of("10"),
                    sourceReference = key(),
                )
            val first = billingApi.post(folio, posting)
            val again = billingApi.post(folio, posting)

            val charge = first.serviceCharge.shouldNotBeNull()
            again.id shouldBe first.id
            again.serviceCharge.shouldNotBeNull().id shouldBe charge.id
        }

    @Test
    fun `an invalid source reference is refused`(): Unit =
        with(billing) {
            val folio = openApiFolio()
            listOf("com espaço", "a".repeat(201)).forEach { invalid ->
                val refused = shouldThrow<DomainException> { billingApi.post(folio, roomCharge(invalid)) }
                refused.kind shouldBe ErrorKind.INVALID_INPUT
                refused.code.value shouldBe "POSTING_INVALID"
                refused.violations.single().field shouldBe "sourceReference"
            }
        }

    @Test
    fun `a posting without a source reference is never deduplicated`(): Unit =
        with(billing) {
            val folio = openApiFolio()
            val posting = roomCharge(null)
            billingApi.post(folio, posting).id shouldNotBe billingApi.post(folio, posting).id
            folio(folio.value)["balance"].asString() shouldBe "620.00"
        }

    private fun auditEntries(action: String): Long =
        jdbc
            .sql("SELECT count(*) FROM audit.audit_entry WHERE action = :action")
            .param("action", action)
            .query(Long::class.java)
            .single()

    private fun openApiFolio(): FolioId =
        billingApi.openFolio(NewFolio(FolioOwnerType.RESERVATION, UuidV7.generate())).id

    private fun BillingTestSupport.roomCharge(reference: String?) =
        NewPosting(
            TransactionCodeId(codeId("ROOM_CHARGE")),
            Money.of("310.00"),
            source = PostingSource.MANUAL,
            sourceReference = reference,
        )

    private fun key() = "reservations.reservation:${UUID.randomUUID()}:2026-11-02:room"
}
