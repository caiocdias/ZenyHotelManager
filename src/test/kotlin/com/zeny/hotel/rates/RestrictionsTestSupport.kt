package com.zeny.hotel.rates

import com.zeny.hotel.iam.IamTestSupport
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Os eventos `RestrictionsChanged` recebidos pelo listener de teste, como o `distribution` (M8) os receberia. */
object RestrictionsChangedEvents {
    val received: MutableList<RestrictionsChanged> = CopyOnWriteArrayList()
}

/** Listener assíncrono e transacional de teste: com ele, a publicação passa pelo outbox. */
open class RestrictionsChangedProbeListener {
    @ApplicationModuleListener
    open fun on(event: RestrictionsChanged) {
        RestrictionsChangedEvents.received += event
    }
}

/** Apoio dos testes de restrições do `rates` (M4 E03): edição em massa e leitura pela API. */
class RestrictionsTestSupport(
    private val iam: IamTestSupport,
) {
    val admin: RequestPostProcessor get() = iam.asAdmin()

    /** O corpo de uma edição: a seleção, os campos a gravar ([set]) e a retirar ([clear]). */
    @Suppress("LongParameterList") // a seleção inteira e a edição, com padrões
    fun body(
        roomTypeIds: List<UUID> = emptyList(),
        from: LocalDate? = null,
        to: LocalDate? = from,
        set: Map<String, Any?> = emptyMap(),
        clear: List<String> = emptyList(),
        weekdays: List<String> = emptyList(),
        seasonId: UUID? = null,
    ): Map<String, Any?> =
        mapOf(
            "roomTypeIds" to roomTypeIds,
            "from" to from,
            "to" to to,
            "seasonId" to seasonId,
            "weekdays" to weekdays,
            "set" to set,
            "clear" to clear,
        )

    fun update(
        plan: UUID,
        body: Map<String, Any?>,
        auth: RequestPostProcessor = admin,
    ): MockHttpServletResponse = iam.call(POST, "/api/v1/rate-plans/$plan/restrictions/bulk-update", auth, body)

    fun read(
        plan: UUID,
        from: LocalDate,
        to: LocalDate,
        auth: RequestPostProcessor = admin,
        roomTypeId: UUID? = null,
    ): MockHttpServletResponse =
        iam.call(
            GET,
            "/api/v1/rate-plans/$plan/restrictions?from=$from&to=$to" + roomTypeId?.let { "&roomTypeId=$it" }.orEmpty(),
            auth,
        )

    /** Os itens da leitura por data. */
    fun byDate(
        plan: UUID,
        from: LocalDate,
        to: LocalDate,
    ): Map<LocalDate, JsonNode> =
        with(iam) { read(plan, from, to).ok() }["items"]
            .values()
            .associateBy { LocalDate.parse(it["date"].asString()) }

    /** A restrição efetiva do tipo [roomType] na data [day] (sem nenhuma gravada, nula). */
    fun effective(
        plan: UUID,
        roomType: UUID,
        day: LocalDate,
    ): JsonNode? =
        byDate(plan, day, day)[day]
            ?.get("roomTypes")
            ?.values()
            ?.single { it["roomTypeId"].asString() == roomType.toString() }
            ?.get("effective")
}
