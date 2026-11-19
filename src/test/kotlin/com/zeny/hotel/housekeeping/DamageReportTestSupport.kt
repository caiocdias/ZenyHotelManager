package com.zeny.hotel.housekeeping

import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.UuidV7
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Apoio dos testes de danos (M6 E06, Task 2): registra danos pela API, chama as ações (`review`, `charge`, `waive`)
 * com o `If-Match` atual, cria códigos de lançamento e lê os lançamentos do dano direto no banco (pela chave de origem
 * `housekeeping.damage:<id>`).
 */
class DamageReportTestSupport(
    private val iam: IamTestSupport,
    private val jdbc: JdbcClient,
) {
    val housekeeping = HousekeepingTestSupport(iam, jdbc)

    fun body(
        roomId: UUID? = null,
        areaId: UUID? = null,
        description: String? = "Abajur quebrado",
        estimatedCost: String? = "120.00",
    ): Map<String, Any?> =
        mapOf("roomId" to roomId, "areaId" to areaId, "description" to description, "estimatedCost" to estimatedCost)

    fun register(
        auth: RequestPostProcessor,
        body: Map<String, Any?> = body(roomId = housekeeping.createRoom()),
    ): MockHttpServletResponse = iam.call(POST, DAMAGES, auth, body)

    /** Registra um dano como [auth] e devolve o id (`201`). */
    fun registered(
        auth: RequestPostProcessor = iam.asAdmin(),
        body: Map<String, Any?> = body(roomId = housekeeping.createRoom()),
    ): UUID = with(iam) { UUID.fromString(register(auth, body).expectStatus(201)!!["id"].asString()) }

    /** A ação [action] (`review`, `charge`, `waive`) com o [ifMatch] informado ou o atual. */
    fun act(
        id: UUID,
        action: String,
        auth: RequestPostProcessor,
        body: Map<String, Any?>,
        ifMatch: String? = etag(id),
    ): MockHttpServletResponse = iam.call(POST, "$DAMAGES/$id/$action", auth, body, ifMatch)

    fun reviewBody(
        transactionCodeId: UUID,
        finalAmount: String? = "150.00",
        reservationId: UUID? = null,
    ): Map<String, Any?> =
        mapOf("finalAmount" to finalAmount, "transactionCodeId" to transactionCodeId, "reservationId" to reservationId)

    fun etag(id: UUID): String = housekeeping.etagOf("$DAMAGES/$id")

    fun damage(id: UUID): JsonNode = with(iam) { call(GET, "$DAMAGES/$id", iam.asAdmin()).ok() }

    /** Cria um código de lançamento do [kind] (`CHARGE` com a taxa de serviço de 10%, ou outro sem ela). */
    fun createCode(kind: String): UUID {
        val code =
            "DM" +
                UuidV7
                    .generate()
                    .toString()
                    .takeLast(8)
                    .uppercase()
        val charge = kind == "CHARGE"
        return with(iam) {
            UUID.fromString(
                call(
                    POST,
                    "/api/v1/transaction-codes",
                    asAdmin(),
                    mapOf(
                        "code" to code,
                        "name" to "Dano $code",
                        "kind" to kind,
                        "revenueGroup" to if (charge) "FOOD_BEVERAGE" else "OTHER",
                        "serviceChargeApplies" to charge,
                        "serviceChargeRate" to if (charge) "10" else null,
                    ),
                ).expectStatus(201)!!["id"]
                    .asString(),
            )
        }
    }

    /** Os lançamentos com a chave de origem do dano [id] (o item, sem a taxa). */
    fun postingsOf(id: UUID): List<Map<String, Any?>> =
        jdbc
            .sql("SELECT * FROM billing.posting WHERE source_reference = :ref")
            .param("ref", "housekeeping.damage:$id")
            .query()
            .listOfRows()

    /** As taxas de serviço lançadas junto do lançamento [posting]. */
    fun serviceChargesOf(posting: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM billing.posting WHERE service_charge_for_id = :id")
            .param("id", posting)
            .query(Int::class.java)
            .single()

    companion object {
        const val DAMAGES = "/api/v1/damage-reports"

        /** Roda [block] autenticado como [userId] (os casos de uso chamados direto, fora de uma requisição). */
        fun <T> actingAs(
            userId: UUID,
            block: () -> T,
        ): T {
            val jwt =
                Jwt
                    .withTokenValue("token")
                    .header("alg", "none")
                    .subject(userId.toString())
                    .claim("sid", UUID.randomUUID().toString())
                    .build()
            SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
            try {
                return block()
            } finally {
                SecurityContextHolder.clearContext()
            }
        }
    }
}
