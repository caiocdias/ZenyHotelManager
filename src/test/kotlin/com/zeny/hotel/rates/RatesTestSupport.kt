package com.zeny.hotel.rates

import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.rooms.RoomsTestSupport
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.util.UUID

/** Apoio dos testes do `rates`: cria políticas, pacotes e planos pela API, como o administrador. */
class RatesTestSupport(
    private val iam: IamTestSupport,
) {
    val rooms = RoomsTestSupport(iam)

    private val admin: RequestPostProcessor get() = iam.asAdmin()

    fun uniqueCode(prefix: String): String = (prefix + UuidV7.generate().toString().takeLast(SUFFIX_LENGTH)).uppercase()

    fun uniqueName(prefix: String): String = "$prefix ${UuidV7.generate().toString().takeLast(SUFFIX_LENGTH)}"

    @Suppress("LongParameterList") // os termos da política, com padrões
    fun cancellationBody(
        name: String = uniqueName("Flexível"),
        deadlineHours: Int = 48,
        penaltyType: String = "NIGHTS",
        penaltyPercent: String? = null,
        penaltyNights: Int? = 1,
        penaltyAmount: String? = null,
    ): Map<String, Any?> =
        mapOf(
            "name" to name,
            "deadlineHours" to deadlineHours,
            "penaltyType" to penaltyType,
            "penaltyPercent" to penaltyPercent,
            "penaltyNights" to penaltyNights,
            "penaltyAmount" to penaltyAmount,
        )

    fun createCancellationPolicy(body: Map<String, Any?> = cancellationBody()): UUID =
        id(post("/api/v1/cancellation-policies", body))

    fun guaranteeBody(
        name: String = uniqueName("Depósito"),
        type: String = "DEPOSIT",
        depositPercent: String? = "30.0000",
        dueDaysBefore: Int? = 7,
    ): Map<String, Any?> =
        mapOf("name" to name, "type" to type, "depositPercent" to depositPercent, "dueDaysBefore" to dueDaysBefore)

    fun createGuaranteePolicy(body: Map<String, Any?> = guaranteeBody()): UUID =
        id(post("/api/v1/guarantee-policies", body))

    /** Um código de lançamento novo do `billing` (consumo, por padrão), pela API. */
    fun createTransactionCode(kind: String = "CHARGE"): UUID {
        val code = uniqueCode("PK").take(TRANSACTION_CODE_LENGTH)
        return id(
            post(
                "/api/v1/transaction-codes",
                mapOf(
                    "code" to code,
                    "name" to "Pacote $code",
                    "kind" to kind,
                    "revenueGroup" to if (kind == "CHARGE") "FOOD_BEVERAGE" else "OTHER",
                ),
            ),
        )
    }

    @Suppress("LongParameterList") // os campos do pacote, com padrões
    fun packageBody(
        transactionCodeId: UUID?,
        code: String = uniqueCode("PKG"),
        name: String = uniqueName("Café"),
        price: String = "35.00",
        pricing: String = "PER_PERSON_PER_NIGHT",
        postingRhythm: String = "DAILY",
        includedInRate: Boolean = true,
    ): Map<String, Any?> =
        mapOf(
            "code" to code,
            "name" to name,
            "price" to price,
            "pricing" to pricing,
            "postingRhythm" to postingRhythm,
            "includedInRate" to includedInRate,
            "transactionCodeId" to transactionCodeId,
        )

    fun createPackage(transactionCodeId: UUID = createTransactionCode()): UUID =
        id(post("/api/v1/packages", packageBody(transactionCodeId)))

    @Suppress("LongParameterList") // os campos do plano, com padrões
    fun planBody(
        roomTypeIds: List<UUID>,
        cancellationPolicyId: UUID,
        guaranteePolicyId: UUID,
        packageIds: List<UUID> = emptyList(),
        code: String = uniqueCode("RP"),
        name: String = uniqueName("Tarifa balcão"),
        category: String? = "NORMAL",
        channels: List<String> = listOf("DIRECT", "WEBSITE"),
        mealPlan: String = "BREAKFAST",
    ): Map<String, Any?> =
        buildMap {
            put("code", code)
            put("name", name)
            put("description", "Tarifa de balcão com café")
            put("mealPlan", mealPlan)
            if (category != null) put("category", category)
            put("channels", channels)
            put("roomTypeIds", roomTypeIds)
            put("packageIds", packageIds)
            put("cancellationPolicyId", cancellationPolicyId)
            put("guaranteePolicyId", guaranteePolicyId)
        }

    /** Um plano com um tipo de UH, duas políticas e um pacote novos. */
    fun createPlan(category: String = "NORMAL"): RatePlanFixture {
        val roomType = rooms.createRoomType()
        val cancellation = createCancellationPolicy()
        val guarantee = createGuaranteePolicy()
        val pkg = createPackage()
        val body = planBody(listOf(roomType), cancellation, guarantee, listOf(pkg), category = category)
        val plan = id(post("/api/v1/rate-plans", body))
        return RatePlanFixture(plan, roomType, cancellation, guarantee, pkg)
    }

    /** Um plano novo, sem pacotes, com os tipos de UH [roomTypeIds] (novos, por padrão um) e políticas novas. */
    fun createPlanFor(roomTypeIds: List<UUID> = listOf(rooms.createRoomType())): UUID =
        id(post("/api/v1/rate-plans", planBody(roomTypeIds, createCancellationPolicy(), createGuaranteePolicy())))

    fun etagOf(path: String): String {
        val response: MockHttpServletResponse = iam.call(GET, path, admin)
        withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 200 }
        return response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
    }

    fun listAll(path: String): List<JsonNode> = rooms.listAll(path)

    private fun post(
        path: String,
        body: Any,
    ): MockHttpServletResponse = iam.call(POST, path, admin, body)

    private fun id(response: MockHttpServletResponse): UUID =
        with(iam) { UUID.fromString(response.expectStatus(201)!!["id"].asString()) }

    data class RatePlanFixture(
        val plan: UUID,
        val roomType: UUID,
        val cancellationPolicy: UUID,
        val guaranteePolicy: UUID,
        val ratePackage: UUID,
    )

    companion object {
        private const val SUFFIX_LENGTH = 12
        private const val TRANSACTION_CODE_LENGTH = 20

        fun definition(code: PermissionCode) = RatesPermissions.permissions.single { it.code == code }
    }
}
