package com.zeny.hotel.billing

import com.zeny.hotel.guests.GuestsTestSupport
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.IDEMPOTENT_REPLAYED_HEADER
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.util.UUID

/**
 * Contas (M3 E04, RF-CTA-01): abertura para cada tipo de dono, a empresa (dona ou pagadora) conferida pela
 * `GuestsApi`, a idempotência da abertura, o pagador e o limite com `If-Match`, o saldo, a listagem por dono e a
 * [BillingApi] (abrir e lançar sem conferir permissão, auditadas).
 */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class)
class FoliosApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val billingApi: BillingApi,
) {
    private val billing = BillingTestSupport(iam, mockMvc, jsonMapper)
    private val guests = GuestsTestSupport(iam)

    @Test
    fun `a folio is opened for any owner, numbered by the business year and starts open with a zero balance`(): Unit =
        with(billing) {
            val today = hotelApi.currentBusinessDate()
            for (type in listOf("RESERVATION", "GROUP", "EVENT")) {
                val owner = UuidV7.generate()
                val response = call(POST, "/api/v1/folios", admin, folioBody(type, owner))
                val folio = response.expect(201)
                response.getHeader(HttpHeaders.ETAG) shouldBe "\"0\""
                response.getHeader(HttpHeaders.LOCATION) shouldBe "/api/v1/folios/${folio["id"].asString()}"
                folio["number"].asString() shouldMatch Regex("C-${today.year}-\\d{6,}")
                folio["owner"]["type"].asString() shouldBe type
                folio["owner"]["id"].asString() shouldBe owner.toString()
                folio["status"].asString() shouldBe "OPEN"
                folio["balance"].asString() shouldBe "0.00"
                folio["openedOn"].asString() shouldBe today.toString()
            }
            // A avulsa sem id: o dono é a própria conta; os outros tipos exigem o id.
            val walkIn = call(POST, "/api/v1/folios", admin, folioBody("WALK_IN", null)).expect(201)
            walkIn["owner"]["id"].asString() shouldBe walkIn["id"].asString()
            call(POST, "/api/v1/folios", admin, folioBody("RESERVATION", null))
                .problem(400, "FOLIO_OWNER_INVALID")["errors"][0]["field"]
                .asString() shouldBe "owner.id"
            call(POST, "/api/v1/folios", admin, folioBody("RESERVATION", UuidV7.generate(), creditLimit = "-1.00"))
                .problem(400, "BILLING_DATA_INVALID")
            call(
                POST,
                "/api/v1/folios",
                admin,
                mapOf("owner" to mapOf("type" to "HOTEL")),
            ).problem(400, "VALIDATION_FAILED")
        }

    @Test
    fun `a company owner or payer is checked through the guests API`(): Unit =
        with(billing) {
            val company = guests.createCompany()
            val inactive = guests.createCompany()
            iam.call(POST, "/api/v1/companies/$inactive/deactivate", admin).status shouldBe 200

            call(POST, "/api/v1/folios", admin, folioBody("COMPANY", UuidV7.generate()))
                .problem(400, "UNKNOWN_COMPANY")["errors"][0]["field"]
                .asString() shouldBe "owner.id"
            call(POST, "/api/v1/folios", admin, folioBody("COMPANY", inactive)).problem(422, "COMPANY_INACTIVE")
            call(
                POST,
                "/api/v1/folios",
                admin,
                folioBody("EVENT", UuidV7.generate(), payerCompanyId = UuidV7.generate()),
            ).problem(400, "UNKNOWN_COMPANY")["errors"][0]["field"]
                .asString() shouldBe "payerCompanyId"
            call(POST, "/api/v1/folios", admin, folioBody("EVENT", UuidV7.generate(), payerCompanyId = inactive))
                .problem(422, "COMPANY_INACTIVE")

            val companyFolio = call(POST, "/api/v1/folios", admin, folioBody("COMPANY", company)).expect(201)
            companyFolio["owner"]["id"].asString() shouldBe company.toString()
            val paid =
                call(POST, "/api/v1/folios", admin, folioBody("RESERVATION", UuidV7.generate(), company, "300.00"))
                    .expect(201)
            paid["payerCompanyId"].asString() shouldBe company.toString()
            paid["creditLimit"].asString() shouldBe "300.00"
        }

    @Test
    fun `opening a folio needs an Idempotency-Key and the same key does not open it twice`(): Unit =
        with(billing) {
            val owner = UuidV7.generate()
            call(POST, "/api/v1/folios", admin, folioBody("RESERVATION", owner), idempotencyKey = null)
                .problem(400, "IDEMPOTENCY_KEY_REQUIRED")
            val key = UuidV7.generate().toString()
            val first = call(POST, "/api/v1/folios", admin, folioBody("RESERVATION", owner), idempotencyKey = key)
            val again = call(POST, "/api/v1/folios", admin, folioBody("RESERVATION", owner), idempotencyKey = key)
            first.expect(201)["id"] shouldBe again.expect(201)["id"]
            again.getHeader(IDEMPOTENT_REPLAYED_HEADER) shouldBe "true"
            listAll("/api/v1/folios?ownerType=RESERVATION&ownerId=$owner").size shouldBe 1
        }

    @Test
    fun `payer and credit limit change with If-Match, and balance and ETag follow the postings`(): Unit =
        with(billing) {
            val folio = openFolio()
            val path = "/api/v1/folios/$folio"
            val company = guests.createCompany()
            call(PUT, path, admin, mapOf("creditLimit" to "100.00")).problem(428, "PRECONDITION_REQUIRED")
            val updated =
                call(PUT, path, admin, mapOf("payerCompanyId" to company, "creditLimit" to "100.00"), ifMatch = "\"0\"")
                    .expect(200)
            updated["payerCompanyId"].asString() shouldBe company.toString()
            updated["creditLimitExceeded"].asBoolean() shouldBe false
            val etag = etagOf(path)

            post(folio, codeId("ROOM_CHARGE"), "80.00", quantity = "2")
            val after = folio(folio)
            after["balance"].asString() shouldBe "160.00"
            after["creditLimitExceeded"].asBoolean() shouldBe true
            (etagOf(path) == etag) shouldBe false
            call(PUT, path, admin, mapOf("creditLimit" to "200.00"), ifMatch = etag).problem(412, "PRECONDITION_FAILED")
        }

    @Test
    fun `folios are listed by owner and status, newest first, and reading needs billing folios read`(): Unit =
        with(billing) {
            val owner = UuidV7.generate()
            val first = openFolio(ownerId = owner)
            val second = openFolio(ownerId = owner)
            listAll("/api/v1/folios?ownerId=$owner&status=OPEN").map { it["id"].asString() } shouldContainExactly
                listOf(second.toString(), first.toString())
            val stranger = iam.asTestUser(iam.createUser())
            call(GET, "/api/v1/folios", stranger).problem(403, "PERMISSION_DENIED")
            call(POST, "/api/v1/folios", stranger, folioBody("GROUP", owner)).problem(403, "PERMISSION_DENIED")
            call(GET, "/api/v1/folios/${UuidV7.generate()}", admin).problem(404, "FOLIO_NOT_FOUND")
        }

    @Test
    fun `the public API opens folios and posts without permission, on the business date, audited as the system`() {
        val owner = FolioOwner(FolioOwnerType.EVENT, UuidV7.generate())
        val opened = billingApi.openFolio(NewFolio(owner.type, owner.id, creditLimit = Money.of("1000.00")))
        opened.owner shouldBe owner
        opened.balance shouldBe Money.ZERO
        opened.openedOn shouldBe hotelApi.currentBusinessDate()

        val food = billingApi.transactionCode(TransactionCodeKey.FOOD_BEVERAGE)
        food.code shouldBe "AB"
        food.serviceChargeApplies shouldBe true
        billingApi.transactionCode(food.id) shouldBe food

        val posting =
            billingApi.post(
                opened.id,
                NewPosting(food.id, Money.of("42.50"), BigDecimal("2"), "Jantar do evento", PostingSource.EVENT),
            )
        posting.amount shouldBe Money.of("85.00")
        posting.source shouldBe PostingSource.EVENT
        posting.businessDate shouldBe hotelApi.currentBusinessDate()
        posting.postedBy.shouldBeNull()
        posting.type shouldBe PostingType.REGULAR

        billingApi.folio(opened.id)!!.balance shouldBe Money.of("85.00")
        billingApi.foliosOf(owner).map { it.id } shouldContainExactly listOf(opened.id)
        billingApi.folio(FolioId(UuidV7.generate())).shouldBeNull()

        // As duas escritas na trilha, em nome do sistema.
        audit(opened.id.value) shouldContainExactly listOf("billing.folio.open" to null)
        audit(posting.id.value) shouldContainExactly listOf("billing.posting.create" to null)
    }

    private fun audit(entityId: UUID): List<Pair<String, UUID?>> =
        jdbc
            .sql("SELECT action, user_id FROM audit.audit_entry WHERE entity_id = :id ORDER BY occurred_at, id")
            .param("id", entityId.toString())
            .query { rs, _ -> rs.getString("action") to rs.getObject("user_id", UUID::class.java) }
            .list()
}
