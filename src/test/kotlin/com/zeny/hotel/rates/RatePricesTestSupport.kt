package com.zeny.hotel.rates

import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.shared.UuidV7
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import com.zeny.hotel.testsupport.asUser as authenticatedAs

/** Os eventos `RatesChanged` recebidos pelo listener de teste, como o `distribution` (M8) os receberia. */
object RatesChangedEvents {
    val received: MutableList<RatesChanged> = CopyOnWriteArrayList()
}

/** Listener assíncrono e transacional de teste: com ele, a publicação passa pelo outbox. */
open class RatesChangedProbeListener {
    @ApplicationModuleListener
    open fun on(event: RatesChanged) {
        RatesChangedEvents.received += event
    }
}

/**
 * Os listeners de teste dos eventos do `rates` (`RatesChanged` e, desde a E03, `RestrictionsChanged`): com eles, as
 * publicações passam pelo outbox. Um só contexto para os testes de preços e de restrições.
 */
@TestConfiguration(proxyBeanMethods = false)
class RatesChangedListenerConfiguration {
    @Bean
    fun ratesChangedProbeListener() = RatesChangedProbeListener()

    @Bean
    fun restrictionsChangedProbeListener() = RestrictionsChangedProbeListener()
}

/** Apoio dos testes de preços do `rates` (M4 E02): edição em massa, leitura, derivação e as faixas do hotel. */
class RatePricesTestSupport(
    private val iam: IamTestSupport,
    private val jsonMapper: JsonMapper,
) {
    val admin: RequestPostProcessor get() = iam.asAdmin()

    /** Um usuário com sessão fixa (a aprovação de superior fica vinculada a ela). */
    inner class Operator(
        val user: TestUser,
        val sessionId: UUID = UuidV7.generate(),
    ) {
        val auth: RequestPostProcessor get() = authenticatedAs(user.id.value, sessionId)
    }

    data class Approver(
        val user: TestUser,
        val pin: String,
    )

    fun approver(user: TestUser): Approver {
        val pin = (PIN_MIN..PIN_MAX).random().toString()
        iam.setPin(user, pin)
        return Approver(user, pin)
    }

    /** Pede a aprovação do problema `APPROVAL_REQUIRED` [required] e devolve o token. */
    fun approve(
        requester: Operator,
        required: JsonNode,
        approver: Approver,
    ): Pair<String, UUID> {
        val body =
            with(iam) {
                call(
                    POST,
                    "/api/v1/auth/approvals",
                    requester.auth,
                    mapOf(
                        "action" to required["action"].asString(),
                        "permission" to required["permission"].asString(),
                        "resourceRef" to required["resourceRef"].asString(),
                        "approverLogin" to approver.user.login,
                        "approverPin" to approver.pin,
                        "reason" to "Reajuste conferido",
                    ),
                ).expectStatus(201)!!
            }
        return body["approvalToken"].asString() to UUID.fromString(body["approvalId"].asString())
    }

    fun prices(
        oneAdult: String,
        twoAdults: String? = null,
        extraAdult: String? = null,
        children: List<Triple<Int, Int, String>> = emptyList(),
    ): Map<String, Any?> =
        mapOf(
            "oneAdult" to oneAdult,
            "twoAdults" to twoAdults,
            "extraAdult" to extraAdult,
            "children" to
                children.map { (min, max, amount) -> mapOf("minAge" to min, "maxAge" to max, "amount" to amount) },
        )

    @Suppress("LongParameterList") // a seleção inteira, com padrões
    fun selection(
        roomTypeIds: List<UUID>,
        from: LocalDate? = null,
        to: LocalDate? = null,
        weekdays: List<String> = emptyList(),
        seasonId: UUID? = null,
    ): Map<String, Any?> =
        mapOf(
            "roomTypeIds" to roomTypeIds,
            "from" to from,
            "to" to to,
            "seasonId" to seasonId,
            "weekdays" to weekdays,
        )

    fun update(
        plan: UUID,
        selection: Map<String, Any?>,
        prices: Map<String, Any?>,
        auth: RequestPostProcessor = admin,
        approvalToken: String? = null,
    ): MockHttpServletResponse =
        iam.call(
            POST,
            "/api/v1/rate-plans/$plan/prices/bulk-update",
            auth,
            selection + ("prices" to prices),
            approvalToken = approvalToken,
        )

    fun remove(
        plan: UUID,
        selection: Map<String, Any?>,
        auth: RequestPostProcessor = admin,
        approvalToken: String? = null,
    ): MockHttpServletResponse =
        iam.call(POST, "/api/v1/rate-plans/$plan/prices/bulk-remove", auth, selection, approvalToken = approvalToken)

    fun read(
        plan: UUID,
        from: LocalDate,
        to: LocalDate,
        auth: RequestPostProcessor = admin,
    ): MockHttpServletResponse = iam.call(GET, "/api/v1/rate-plans/$plan/prices?from=$from&to=$to", auth)

    /** Os itens da leitura por data (de um só tipo de UH). */
    fun byDate(
        plan: UUID,
        from: LocalDate,
        to: LocalDate,
    ): Map<LocalDate, JsonNode> =
        with(iam) { read(plan, from, to).ok() }["items"]
            .values()
            .associateBy { LocalDate.parse(it["date"].asString()) }

    fun derivation(
        type: String,
        base: UUID,
        percent: String? = null,
        amount: String? = null,
        perPerson: Boolean? = null,
    ): Map<String, Any?> =
        mapOf("basePlanId" to base, "type" to type, "percent" to percent, "amount" to amount, "perPerson" to perPerson)

    fun derive(
        plan: UUID,
        body: Map<String, Any?>,
        auth: RequestPostProcessor = admin,
        approvalToken: String? = null,
        ifMatch: String? = etag(plan),
    ): MockHttpServletResponse =
        iam.call(PUT, "/api/v1/rate-plans/$plan/derivation", auth, body, ifMatch, approvalToken)

    fun removeDerivation(
        plan: UUID,
        auth: RequestPostProcessor = admin,
        approvalToken: String? = null,
    ): MockHttpServletResponse =
        iam.call(POST, "/api/v1/rate-plans/$plan/derivation/remove", auth, null, etag(plan), approvalToken)

    fun etag(plan: UUID): String = iam.call(GET, "/api/v1/rate-plans/$plan", admin).getHeader("ETag")!!

    fun deactivatePlan(plan: UUID) {
        with(iam) { call(POST, "/api/v1/rate-plans/$plan/deactivate", admin).ok() }
    }

    /**
     * Troca a idade limite e as faixas de criança do hotel durante [block] e devolve as anteriores no fim (o banco é
     * compartilhado).
     */
    fun withChildBands(
        childMaxAge: Int,
        bands: List<Triple<Int, Int, String>>,
        block: () -> Unit,
    ) = with(iam) {
        val path = "/api/v1/hotel/policies"
        val before = call(GET, path, admin).ok()

        fun put(body: Map<*, *>) {
            val etag = call(GET, path, admin).getHeader("ETag")
            call(PUT, path, admin, body, ifMatch = etag).ok()
        }
        val original = jsonMapper.convertValue(before, Map::class.java)
        put(
            original +
                mapOf(
                    "childMaxAge" to childMaxAge,
                    "childAgeBands" to
                        bands.map { (min, max, label) -> mapOf("minAge" to min, "maxAge" to max, "label" to label) },
                ),
        )
        try {
            block()
        } finally {
            put(original)
        }
    }

    private companion object {
        const val PIN_MIN = 1000
        const val PIN_MAX = 9999
    }
}
