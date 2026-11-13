package com.zeny.hotel.frontdesk

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.NewPosting
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.TransactionCodeKey
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * Ataque ao check-out pela regra única do `reservations` (M6 E11, Task 6): a taxa de turismo do hotel configurada
 * nunca entra no check-out (day use e pernoite que sai no dia da chegada), as chaves e os valores seguem os da M5 E04,
 * a taxa de serviço só nos adicionais, nada depois da data da chegada, a chave já lançada não duplica e a resposta
 * mantém a forma. Banco compartilhado, sem avançar a data; a taxa de turismo volta ao valor original no `finally`.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class, MutableClockConfiguration::class)
@Suppress("LargeClass", "LongParameterList")
class CheckOutChargesAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val billingApi: BillingApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val frontdesk = FrontdeskTestSupport(iam, support, hotelApi, roomsApi, jsonMapper)
    private val billing = support.billing
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    @Test
    @Suppress("LongMethod")
    fun `a day use and a same day check-out never charge the tourism fee even when the hotel configures it`() {
        withTourismFee("7.00") {
            val tourismCode = asAdmin { billingApi.transactionCode(TransactionCodeKey.TOURISM_FEE).id.value }
            val stay = frontdesk.stay()
            val cashier = frontdesk.cashier()
            for (departure in listOf(today, today.plusDays(3))) {
                withClue("saída $departure") {
                    val booked = stay.checkedIn(departure = departure)
                    // Só a diária de 400,00: com a taxa de turismo o saldo seria outro e o pagamento não fecharia.
                    frontdesk.pay(booked.folio, cashier, "400.00")

                    val response = checkOut(booked.id).expect(200)

                    response["stayCharges"].values().map { it["amount"].asString() } shouldBe listOf("400.00")
                    response["stayCharges"].values().map { it["transactionCodeId"].asString() } shouldBe
                        listOf(billing.codeId("ROOM_CHARGE").toString())
                    postings(booked.folio) shouldBe
                        listOf(Triple("reservations.reservation:${booked.id}:$today:room", "400.00", null))
                    tourismPostings(booked.folio, tourismCode) shouldBe 0
                    billing.balance(booked.folio) shouldBe "0.00"
                }
            }
        }
    }

    @Test
    @Suppress("LongMethod")
    fun `packages keep their keys, the included without service charge and the additional by its code`() {
        withTourismFee("7.00") {
            val included = createPackage("ON_ARRIVAL", "PER_STAY", "60.00", included = true, serviceCharge = true)
            val additional = createPackage("ON_ARRIVAL", "PER_STAY", "50.00", included = false, serviceCharge = true)
            val stay = frontdesk.stay(packages = listOf(included.id, additional.id))
            val booked = stay.checkedIn(departure = today.plusDays(2))
            // 340 de hospedagem + 60 incluso + 50 adicional + 5,00 de taxa de serviço (10%) só do adicional.
            frontdesk.pay(booked.folio, frontdesk.cashier(), "455.00")

            val response = checkOut(booked.id).expect(200)

            val base = "reservations.reservation:${booked.id}:$today"
            postings(booked.folio) shouldContainExactlyInAnyOrder
                listOf(
                    Triple("$base:room", "340.00", null),
                    Triple("$base:package:${included.id}", "60.00", null),
                    Triple("$base:package:${additional.id}", "50.00", null),
                    // A taxa de serviço do adicional: lançamento próprio, sem chave de origem.
                    Triple(null, "5.00", "SERVICE_CHARGE"),
                )
            response["stayCharges"].values().associateBy { it["transactionCodeId"].asString() }.let { items ->
                items.getValue(included.code.toString())["serviceCharge"].isNull shouldBe true
                items.getValue(billing.codeId("ROOM_CHARGE").toString())["serviceCharge"].isNull shouldBe true
                items.getValue(additional.code.toString())["serviceCharge"]["amount"].asString() shouldBe "5.00"
            }
        }
    }

    @Test
    fun `a key already posted is not duplicated by the check-out and its posting is the one returned`() {
        withTourismFee("7.00") {
            val stay = frontdesk.stay()
            val booked = stay.checkedIn(departure = today.plusDays(2))
            val key = "reservations.reservation:${booked.id}:$today:room"
            val existing =
                asAdmin {
                    billingApi.post(
                        FolioId(booked.folio),
                        NewPosting(
                            billingApi.transactionCode(TransactionCodeKey.ROOM_CHARGE).id,
                            Money.of("400.00"),
                            source = PostingSource.END_OF_DAY,
                            sourceReference = key,
                        ),
                    )
                }
            frontdesk.pay(booked.folio, frontdesk.cashier(), "400.00")

            val response = checkOut(booked.id).expect(200)

            response["stayCharges"].values().map { it["postingId"].asString() } shouldBe
                listOf(existing.id.toString())
            postings(booked.folio) shouldBe listOf(Triple(key, "400.00", null))
        }
    }

    @Test
    fun `a check-out after the arrival date posts nothing even with the tourism fee configured`() {
        withTourismFee("7.00") {
            val stay = frontdesk.stay()
            val booked = stay.book(frontdesk.guest(), departure = today.plusDays(2))
            unassign(booked.id)
            jdbc
                .sql("UPDATE reservations.reservation SET arrival = :date WHERE id = :id")
                .param("date", today.minusDays(1))
                .param("id", booked.id)
                .update() shouldBe 1
            support.forceInHouse(booked.id, stay.room)
            jdbc
                .sql("UPDATE reservations.reservation_guest SET checked_in_at = now() WHERE reservation_id = :id")
                .param("id", booked.id)
                .update()
            roomsApi.markOccupied(RoomId(stay.room), 1, RoomStatusSource.MANUAL)

            val response = checkOut(booked.id).expect(200)

            response["stayCharges"].values().toList().shouldBeEmpty()
            postings(booked.folio).shouldBeEmpty()
        }
    }

    @Test
    fun `the check-out response keeps its shape, with the posting fields and no tourism line`() {
        withTourismFee("7.00") {
            val stay = frontdesk.stay()
            val booked = stay.checkedIn(departure = today)
            frontdesk.pay(booked.folio, frontdesk.cashier(), "400.00")

            val response = checkOut(booked.id).expect(200)

            response.propertyNames().toSet() shouldBe
                setOf(
                    "reservation",
                    "room",
                    "stayCharges",
                    "lateCheckOut",
                    "releasedPreAuthorizations",
                    "closedFolios",
                )
            val item = response["stayCharges"].values().single()
            item.propertyNames().toSet() shouldBe
                setOf("postingId", "folioId", "transactionCodeId", "amount", "businessDate", "serviceCharge")
            item["folioId"].asString() shouldBe booked.folio.toString()
            item["businessDate"].asString() shouldBe today.toString()
            response["stayCharges"] shouldHaveSize 1
        }
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private fun checkOut(id: UUID) =
        frontdesk.Response(
            billing.call(POST, "/api/v1/reservations/$id/check-out", iam.asAdmin(), null, UuidV7.generate().toString()),
        )

    private fun unassign(id: UUID) {
        with(iam) { call(POST, "/api/v1/reservations/$id/unassign-room", asAdmin()).ok() }
    }

    /** Os lançamentos da conta: a chave de origem, o valor e, se houver, a chave do código de serviço. */
    private fun postings(folio: UUID): List<Triple<String?, String, String?>> =
        jdbc
            .sql(
                "SELECT source_reference, amount, service_charge_for_id FROM billing.posting " +
                    "WHERE folio_id = :folio ORDER BY id",
            ).param("folio", folio)
            .query { rs, _ ->
                Triple(
                    rs.getString("source_reference"),
                    rs.getBigDecimal("amount").toPlainString(),
                    if (rs.getObject("service_charge_for_id") != null) "SERVICE_CHARGE" else null,
                )
            }.list()

    private fun tourismPostings(
        folio: UUID,
        code: UUID,
    ): Int =
        jdbc
            .sql("SELECT count(*) FROM billing.posting WHERE folio_id = :folio AND transaction_code_id = :code")
            .param("folio", folio)
            .param("code", code)
            .query(Int::class.java)
            .single()

    private data class Package(
        val id: UUID,
        val code: UUID,
    )

    private fun createPackage(
        rhythm: String,
        pricing: String,
        price: String,
        included: Boolean,
        serviceCharge: Boolean = false,
    ): Package {
        val code = support.rates.createTransactionCode(serviceChargeApplies = serviceCharge)
        val body =
            support.rates.packageBody(
                code,
                price = price,
                pricing = pricing,
                postingRhythm = rhythm,
                includedInRate = included,
            )
        val id = with(iam) { call(POST, "/api/v1/packages", asAdmin(), body).expectStatus(201)!! }["id"].asString()
        return Package(UUID.fromString(id), code)
    }

    private fun <T> asAdmin(block: () -> T): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(iam.adminId().toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    /** Roda [block] com a taxa de turismo do hotel em [fee] e devolve a original no `finally`. */
    private fun withTourismFee(
        fee: String?,
        block: () -> Unit,
    ) {
        val path = "/api/v1/hotel/tax-settings"
        val before = with(iam) { json(call(GET, path, asAdmin())) }

        fun put(value: String?) =
            with(iam) {
                val etag = checkNotNull(call(GET, path, asAdmin()).getHeader("ETag"))
                call(
                    PUT,
                    path,
                    asAdmin(),
                    mapOf(
                        "serviceChargeRate" to before["serviceChargeRate"].asString(),
                        "issRate" to before["issRate"].asString(),
                        "tourismFeePerPersonNight" to value,
                    ),
                    ifMatch = etag,
                ).ok()
            }
        try {
            put(fee)
            block()
        } finally {
            put(before["tourismFeePerPersonNight"].takeUnless { it.isNull }?.asString())
        }
    }
}
