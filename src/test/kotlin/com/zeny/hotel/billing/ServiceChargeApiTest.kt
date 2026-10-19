package com.zeny.hotel.billing

import com.zeny.hotel.billing.BillingTestSupport.Companion.definition
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Taxa de serviço (M3 E06, RF-CTA-14, RN-02, RN-10, H4, H5): o lançamento separado e vinculado ao item com o
 * percentual informado, senão o do código, senão o padrão do hotel, pela regra de `Money`; nada num código sem a taxa;
 * a taxa na conta final do roteamento; a remoção a pedido registrada e auditada; o estorno, o ajuste, a transferência
 * e a divisão do item que levam a taxa junto (sem taxa nova e sem perder centavos); o limite das correções sobre o
 * item com a taxa e as recusas de corrigir a taxa à parte. O banco e a concorrência ficam em
 * [ServiceChargeIntegrityTest].
 */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class)
@Suppress("LongParameterList") // os beans que os cenários usam
class ServiceChargeApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val billingApi: BillingApi,
) {
    private val billing = BillingTestSupport(iam, mockMvc, jsonMapper)

    @Test
    fun `an item whose code accepts the charge gets a separate linked posting with the rate of the code`(): Unit =
        with(billing) {
            val folio = openFolio()
            val code = createCode(serviceCharge = true, rate = "12.5")
            val item = post(folio, code, "33.33", quantity = "3")
            item["amount"].asString() shouldBe "99.99"
            val chargeId = item["serviceChargePostingId"].asString()

            val charge = posting(folio, chargeId)
            charge["type"].asString() shouldBe "SERVICE_CHARGE"
            charge["transactionCodeId"].asString() shouldBe codeId("SERVICE_CHARGE").toString()
            charge["serviceChargeForPostingId"].asString() shouldBe item["id"].asString()
            charge["serviceChargeRate"].asString() shouldBe "12.5000"
            // 12,5% de 99,99 = 12,49875 → 12,50 (regra de Money); o item fica com o valor dele.
            charge["amount"].asString() shouldBe "12.50"
            charge["businessDate"] shouldBe item["businessDate"]
            charge["remainingAmount"].asString() shouldBe "12.50"
            posting(folio, item["id"].asString())["amount"].asString() shouldBe "99.99"
            postings(folio).map { it["type"].asString() } shouldContainExactly listOf("REGULAR", "SERVICE_CHARGE")
            balance(folio) shouldBe "112.49"

            // A trilha descreve os dois lançamentos (sem a descrição, H10), com o vínculo e o percentual.
            val audited = auditOf("billing.posting.create").associateBy({ it.first }, { it.second })
            audited.getValue(item["id"].asString())["serviceChargePostingId"].asString() shouldBe chargeId
            val chargeAudit = audited.getValue(chargeId)
            chargeAudit["type"].asString() shouldBe "SERVICE_CHARGE"
            chargeAudit["serviceChargeForId"].asString() shouldBe item["id"].asString()
            chargeAudit["serviceChargeRate"].asString() shouldBe "12.5000"

            // O código da taxa é de repasse (não receita); o do item, não.
            val codes = listAll("/api/v1/transaction-codes").associateBy { it["id"].asString() }
            codes.getValue(codeId("SERVICE_CHARGE").toString())["passThrough"].asBoolean() shouldBe true
            codes.getValue(codeId("SERVICE_CHARGE").toString())["revenueGroup"].asString() shouldBe "SERVICE_CHARGE"
            codes.getValue(code.toString())["passThrough"].asBoolean() shouldBe false
        }

    @Test
    fun `without a rate of its own the code falls back to the hotel default, rounded by Money`(): Unit =
        with(billing) {
            withHotelRate("7.5") {
                val folio = openFolio()
                // AB pré-carregado: com a taxa, sem percentual próprio. 7,5% de 10,05 = 0,75375 → 0,75.
                val item = post(folio, codeId("FOOD_BEVERAGE"), "10.05")
                posting(folio, item["serviceChargePostingId"].asString()).let {
                    it["amount"].asString() shouldBe "0.75"
                    it["serviceChargeRate"].asString() shouldBe "7.5000"
                }
            }
            withHotelRate("10") {
                val folio = openFolio()
                // HALF_EVEN: 10% de 0,25 = 0,025 → 0,02 (HALF_UP daria 0,03).
                val item = post(folio, codeId("FOOD_BEVERAGE"), "0.25")
                posting(folio, item["serviceChargePostingId"].asString())["amount"].asString() shouldBe "0.02"
                // 10% de 0,04 arredonda a zero: nenhuma taxa.
                post(folio, codeId("FOOD_BEVERAGE"), "0.04")["serviceChargePostingId"].isNull shouldBe true
                // O percentual guardado na taxa não muda quando o hotel muda o padrão depois.
                withHotelRate("20") {
                    posting(folio, item["serviceChargePostingId"].asString())["serviceChargeRate"].asString() shouldBe
                        "10.0000"
                }
            }
        }

    @Test
    fun `the rate informed by the module of origin wins over the code and the hotel`(): Unit =
        with(billing) {
            val folio = openFolio()
            val own = createCode(serviceCharge = true, rate = "12.5")
            val posted =
                billingApi.post(
                    FolioId(folio),
                    NewPosting(
                        TransactionCodeId(own),
                        Money.of("20.10"),
                        source = PostingSource.POS,
                        serviceChargeRate = Percent.of("15"),
                    ),
                )
            // 15% de 20,10 = 3,015 → 3,02.
            val charge = posted.serviceCharge.shouldNotBeNull()
            charge.type shouldBe PostingType.SERVICE_CHARGE
            charge.amount shouldBe Money.of("3.02")
            charge.serviceChargeRate shouldBe Percent.of("15")
            charge.serviceChargeForId shouldBe posted.id
            charge.source shouldBe PostingSource.POS
            charge.folioId shouldBe posted.folioId
            billingApi.folio(FolioId(folio))!!.balance shouldBe Money.of("23.12")

            // Sem percentual informado, o do código.
            billingApi
                .post(FolioId(folio), NewPosting(TransactionCodeId(own), Money.of("10.00"), source = PostingSource.POS))
                .serviceCharge
                ?.amount shouldBe Money.of("1.25")

            // Fora de 0 a 100: recusado antes de lançar.
            shouldThrow<InvalidInputException> {
                billingApi.post(
                    FolioId(folio),
                    NewPosting(
                        TransactionCodeId(own),
                        Money.of("10.00"),
                        source = PostingSource.POS,
                        serviceChargeRate = Percent.of("101"),
                    ),
                )
            }.code.value shouldBe "POSTING_INVALID"
            billingApi.folio(FolioId(folio))!!.balance shouldBe Money.of("34.37")
        }

    @Test
    fun `a code without the service charge generates none, even with a rate informed`(): Unit =
        with(billing) {
            val folio = openFolio()
            val room = post(folio, codeId("ROOM_CHARGE"), "250.00")
            room["serviceChargePostingId"].isNull shouldBe true
            val posted =
                billingApi.post(
                    FolioId(folio),
                    NewPosting(
                        TransactionCodeId(codeId("LAUNDRY")),
                        Money.of("40.00"),
                        source = PostingSource.LAUNDRY,
                        serviceChargeRate = Percent.of("10"),
                    ),
                )
            posted.serviceCharge.shouldBeNull()
            postings(folio).map { it["type"].asString() } shouldContainExactly listOf("REGULAR", "REGULAR")
            balance(folio) shouldBe "290.00"
        }

    @Test
    fun `a routed item gets the charge on the final folio, without routing the charge again`(): Unit =
        with(billing) {
            withHotelRate("10") {
                val requested = openFolio()
                val company = openFolio("GROUP")
                val other = openFolio("EVENT")
                val today = businessDate()
                createRule(requested, company, today, codes = listOf(codeId("FOOD_BEVERAGE"))).expect(201)
                // Uma regra do código da taxa na conta pedida não desvia a taxa: ela fica com o item.
                createRule(requested, other, today, codes = listOf(codeId("SERVICE_CHARGE"))).expect(201)
                createRule(company, other, today, codes = listOf(codeId("SERVICE_CHARGE"))).expect(201)

                val item = post(requested, codeId("FOOD_BEVERAGE"), "80.00")
                item["folioId"].asString() shouldBe company.toString()
                item["routedFromFolioId"].asString() shouldBe requested.toString()
                val charge = posting(company, item["serviceChargePostingId"].asString())
                charge["folioId"].asString() shouldBe company.toString()
                charge["amount"].asString() shouldBe "8.00"
                charge["routedFromFolioId"].isNull shouldBe true
                balance(requested) shouldBe "0.00"
                balance(company) shouldBe "88.00"
                balance(other) shouldBe "0.00"
            }
        }

    @Test
    fun `the removal at the guest request reverses the charge with the reason, audited, without approval`(): Unit =
        with(billing) {
            val folio = openFolio()
            val item = post(folio, createCode(serviceCharge = true, rate = "10"), "50.00")
            val chargeId = item["serviceChargePostingId"].asString()
            val remover =
                iam.asTestUser(
                    iam.createUserWith(TestGrant(definition(BillingPermissions.SERVICE_CHARGE_REMOVE), ALL)),
                )
            val reader = iam.asTestUser(iam.createUserWith(TestGrant(definition(BillingPermissions.FOLIOS_READ), ALL)))

            removeCharge(folio, chargeId, reader).problem(403, "PERMISSION_DENIED")
            removeCharge(folio, chargeId, remover, idempotencyKey = null).problem(400, "IDEMPOTENCY_KEY_REQUIRED")
            // O item não é taxa: recusado sem efeito.
            removeCharge(folio, item["id"].asString(), remover).problem(422, "POSTING_NOT_SERVICE_CHARGE")

            val removal = removeCharge(folio, chargeId, remover).expect(201)
            removal["type"].asString() shouldBe "REVERSAL"
            removal["correctsId"].asString() shouldBe chargeId
            removal["amount"].asString() shouldBe "-5.00"
            removal["reason"].asString() shouldBe "Taxa de serviço removida a pedido do cliente."
            removal["triggeredByPostingId"].isNull shouldBe true
            removal["approvalId"].isNull shouldBe true
            posting(folio, chargeId)["reversedBy"].asString() shouldBe removal["id"].asString()
            posting(folio, item["id"].asString())["remainingAmount"].asString() shouldBe "50.00"
            balance(folio) shouldBe "50.00"

            val audited =
                auditOf(
                    "billing.posting.remove-service-charge",
                ).single { it.first == removal["id"].asString() }
            audited.second["serviceChargeRemoval"].asBoolean() shouldBe true
            audited.second["correctsId"].asString() shouldBe chargeId
            auditEntries("billing.posting.remove-service-charge", removal["id"].asString()) shouldBe 1

            // De novo: já estornada.
            removeCharge(folio, chargeId, remover).problem(409, "POSTING_ALREADY_REVERSED")
            // Estornar o item depois da remoção não estorna a taxa de novo nem cria outra.
            val reversal = reverseWithReason(folio, item["id"].asString()).expect(201)
            reversal["amount"].asString() shouldBe "-50.00"
            postings(folio).count { it["type"].asString() == "SERVICE_CHARGE" } shouldBe 1
            postings(folio).count { it["correctsId"]?.takeUnless(JsonNode::isNull)?.asString() == chargeId } shouldBe 1
            balance(folio) shouldBe "0.00"
        }

    @Test
    fun `the charge is never corrected nor moved apart from the item`(): Unit =
        with(billing) {
            val folio = openFolio()
            val target = openFolio("GROUP")
            val item = post(folio, createCode(serviceCharge = true, rate = "10"), "50.00")
            val chargeId = item["serviceChargePostingId"].asString()
            reverseWithReason(folio, chargeId).problem(422, "SERVICE_CHARGE_FOLLOWS_ITEM")
            call(
                POST,
                "/api/v1/folios/$folio/postings/$chargeId/adjust",
                admin,
                mapOf("amount" to "1.00", "reason" to "Ajuste"),
            ).problem(422, "SERVICE_CHARGE_FOLLOWS_ITEM")
            transfer(folio, target, listOf(chargeId)).problem(422, "SERVICE_CHARGE_FOLLOWS_ITEM")
            split(folio, chargeId, "PERCENT", listOf(folio to "50", target to "50"))
                .problem(422, "SERVICE_CHARGE_FOLLOWS_ITEM")
            postings(folio).size shouldBe 2
            balance(folio) shouldBe "55.00"
        }

    @Test
    fun `reversing the item reverses the linked charge too and posts no new charge`(): Unit =
        with(billing) {
            val folio = openFolio()
            val item = post(folio, createCode(serviceCharge = true, rate = "10"), "100.00")
            val chargeId = item["serviceChargePostingId"].asString()
            val reversal = reverseWithReason(folio, item["id"].asString()).expect(201)
            reversal["amount"].asString() shouldBe "-100.00"

            val all = postings(folio)
            all.map { it["type"].asString() } shouldContainExactly
                listOf("REGULAR", "SERVICE_CHARGE", "REVERSAL", "REVERSAL")
            val chargeReversal = all.single { it["correctsId"]?.takeUnless(JsonNode::isNull)?.asString() == chargeId }
            chargeReversal["amount"].asString() shouldBe "-10.00"
            chargeReversal["triggeredByPostingId"].asString() shouldBe reversal["id"].asString()
            chargeReversal["reason"] shouldBe reversal["reason"]
            chargeReversal["serviceChargePostingId"].isNull shouldBe true
            all.count { it["type"].asString() == "SERVICE_CHARGE" } shouldBe 1
            balance(folio) shouldBe "0.00"
            removeCharge(folio, chargeId, admin).problem(409, "POSTING_ALREADY_REVERSED")
        }

    @Test
    fun `adjusting the item adjusts the charge with the stored rate, ending both at zero`(): Unit =
        with(billing) {
            val folio = openFolio()
            val item = post(folio, createCode(serviceCharge = true, rate = "10"), "100.00")
            val id = item["id"].asString()
            val chargeId = item["serviceChargePostingId"].asString()
            val following = mutableListOf<String>()
            for (step in listOf("33.33", "33.33")) {
                val adjustment = adjust(folio, id, step).expect(201)
                following +=
                    postings(folio)
                        .single {
                            it["triggeredByPostingId"]?.takeUnless(JsonNode::isNull)?.asString() ==
                                adjustment["id"].asString()
                        }.also { it["correctsId"].asString() shouldBe chargeId }["amount"]
                        .asString()
            }
            following shouldBe listOf("-3.33", "-3.34")
            posting(folio, chargeId)["remainingAmount"].asString() shouldBe "3.33"
            // O estorno do resto leva o resto da taxa.
            reverseWithReason(folio, id).expect(201)
            posting(folio, chargeId)["remainingAmount"].asString() shouldBe "0.00"
            balance(folio) shouldBe "0.00"
            postings(folio).count { it["type"].asString() == "SERVICE_CHARGE" } shouldBe 1
        }

    @Test
    fun `the correction ceiling counts the item with its charge, removals included`(): Unit =
        with(billing) {
            val read = definition(BillingPermissions.FOLIOS_READ)
            val reverse = definition(BillingPermissions.POSTINGS_REVERSE)
            val ceiling = mapOf(BillingPermissions.MAX_AMOUNT to LimitValue.Max(BigDecimal("105")))
            val operator = iam.asTestUser(iam.createUserWith(TestGrant(read, ALL), TestGrant(reverse, ALL, ceiling)))
            val code = createCode(serviceCharge = true, rate = "10")

            // Item 100 + taxa 10: o estorno tira 110 da conta, acima do teto de 105.
            val folio = openFolio()
            val item = post(folio, code, "100.00")
            val required = reverseWithReason(folio, item["id"].asString(), operator).problem(403, "APPROVAL_REQUIRED")
            BigDecimal(required["limit"]["amount"].asString()).compareTo(BigDecimal("110")) shouldBe 0
            postings(folio).size shouldBe 2

            // Item 90 + taxa 9 = 99 cabe no teto.
            val small = post(folio, code, "90.00")
            reverseWithReason(folio, small["id"].asString(), operator).expect(201)
            balance(folio) shouldBe "110.00"

            // A remoção a pedido conta no total depois: removida (10) e estornado o item (100), o total é 110.
            val removedFolio = openFolio()
            val removed = post(removedFolio, code, "100.00")
            removeCharge(removedFolio, removed["serviceChargePostingId"].asString(), admin).expect(201)
            val again =
                reverseWithReason(
                    removedFolio,
                    removed["id"].asString(),
                    operator,
                ).problem(403, "APPROVAL_REQUIRED")
            BigDecimal(again["limit"]["amount"].asString()).compareTo(BigDecimal("110")) shouldBe 0
        }

    @Test
    fun `transferring and splitting the item take the charge along, proportionally and without losing cents`(): Unit =
        with(billing) {
            val source = openFolio()
            val target = openFolio("GROUP")
            val code = createCode(serviceCharge = true, rate = "10")
            val item = post(source, code, "100.00")
            val chargeId = item["serviceChargePostingId"].asString()

            val moved = transfer(source, target, listOf(item["id"].asString())).expect(201)["transfers"][0]
            val incoming = moved["incoming"]
            val chargeIncoming = moved["serviceChargeIncoming"]
            chargeIncoming["type"].asString() shouldBe "TRANSFER_IN"
            chargeIncoming["folioId"].asString() shouldBe target.toString()
            chargeIncoming["amount"].asString() shouldBe "10.00"
            chargeIncoming["serviceChargeForPostingId"].asString() shouldBe incoming["id"].asString()
            chargeIncoming["serviceChargeRate"].asString() shouldBe "10.0000"
            moved["serviceChargeOutgoing"]["correctsId"].asString() shouldBe chargeId
            moved["serviceChargeOutgoing"]["triggeredByPostingId"].asString() shouldBe
                moved["outgoing"]["id"].asString()
            posting(target, incoming["id"].asString())["serviceChargePostingId"].asString() shouldBe
                chargeIncoming["id"].asString()
            balance(source) shouldBe "0.00"
            balance(target) shouldBe "110.00"

            // A entrada vale como um item: dividida, a taxa vai junto, na mesma proporção.
            val third = openFolio("EVENT")
            val parts =
                split(
                    target,
                    incoming["id"].asString(),
                    "PERCENT",
                    listOf(target to "33.3333", source to "33.3333", third to "33.3334"),
                ).expect(201)["transfers"].values().toList()
            parts.map { it["incoming"]["amount"].asString() } shouldContainExactly listOf("33.33", "33.34")
            parts.map { it["serviceChargeIncoming"]["amount"].asString() } shouldContainExactly listOf("3.33", "3.34")
            balance(target) shouldBe "36.66"
            balance(source) shouldBe "36.66"
            balance(third) shouldBe "36.68"
            listOf(target, source, third).sumOf { BigDecimal(balance(it)) }.compareTo(BigDecimal("110.00")) shouldBe 0

            // O estorno de uma parte estorna a taxa dela.
            val inThird = parts[1]["incoming"]["id"].asString()
            reverseWithReason(third, inThird).expect(201)
            balance(third) shouldBe "0.00"
            postings(third).count { it["type"].asString() == "REVERSAL" } shouldBe 2
            postings(source).count { it["type"].asString() == "SERVICE_CHARGE" } shouldBe 1
        }

    @Test
    fun `an inactive service charge code refuses the item instead of dropping the charge, unless it is zero`(): Unit =
        with(billing) {
            val folio = openFolio()
            val food = createCode(serviceCharge = true, rate = "10")
            val chargeCode = codeId("SERVICE_CHARGE")
            call(POST, "/api/v1/transaction-codes/$chargeCode/deactivate", admin).expect(200)
            try {
                call(
                    POST,
                    "/api/v1/folios/$folio/postings",
                    admin,
                    mapOf("transactionCodeId" to food, "unitPrice" to "10.00"),
                ).problem(422, "TRANSACTION_CODE_INACTIVE")
                postings(folio).size shouldBe 0
                // Com a taxa zero, o item passa sem taxa: o valor que arredonda a zero (10% de 0,04) e o percentual 0.
                post(folio, food, "0.04")["serviceChargePostingId"].isNull shouldBe true
                billingApi
                    .post(
                        FolioId(folio),
                        NewPosting(
                            TransactionCodeId(food),
                            Money.of("50.00"),
                            source = PostingSource.POS,
                            serviceChargeRate = Percent.ZERO,
                        ),
                    ).serviceCharge
                    .shouldBeNull()
                // Um código sem a taxa continua lançando.
                post(folio, codeId("ROOM_CHARGE"), "10.00")
                postings(folio).map { it["type"].asString() } shouldContainExactly
                    listOf("REGULAR", "REGULAR", "REGULAR")
                balance(folio) shouldBe "60.04"
            } finally {
                call(POST, "/api/v1/transaction-codes/$chargeCode/reactivate", admin).expect(200)
            }
        }

    private fun posting(
        folio: UUID,
        id: String,
    ): JsonNode = billing.run { call(GET, "/api/v1/folios/$folio/postings/$id", admin).expect(200) }

    private fun adjust(
        folio: UUID,
        id: String,
        amount: String,
    ): MockHttpServletResponse =
        billing.call(
            POST,
            "/api/v1/folios/$folio/postings/$id/adjust",
            billing.admin,
            mapOf("amount" to amount, "reason" to "Cobrado a mais"),
        )

    private fun businessDate() =
        LocalDate.parse(
            billing.run { call(GET, "/api/v1/hotel/business-date", admin).expect(200)["businessDate"].asString() },
        )

    /** Troca a taxa de serviço padrão do hotel durante [block] e devolve a anterior no fim (banco compartilhado). */
    private fun withHotelRate(
        rate: String,
        block: () -> Unit,
    ) = with(billing) {
        val path = "/api/v1/hotel/tax-settings"
        val before = call(GET, path, admin).expect(200)

        fun put(value: String) {
            val body = jsonMapper.convertValue(before, Map::class.java) + ("serviceChargeRate" to value)
            call(PUT, path, admin, body, ifMatch = etagOf(path)).expect(200)
        }
        put(rate)
        try {
            block()
        } finally {
            put(before["serviceChargeRate"].asString())
        }
    }

    private fun auditOf(action: String): List<Pair<String, JsonNode>> =
        jdbc
            .sql("SELECT entity_id, after_state::text FROM audit.audit_entry WHERE action = :action")
            .param("action", action)
            .query { rs, _ -> rs.getString(1) to jsonMapper.readTree(rs.getString(2)) }
            .list()

    private fun auditEntries(
        action: String,
        entityId: String,
    ): Int =
        jdbc
            .sql("SELECT count(*) FROM audit.audit_entry WHERE action = :action AND entity_id = :id")
            .param("action", action)
            .param("id", entityId)
            .query(Int::class.java)
            .single()
}
