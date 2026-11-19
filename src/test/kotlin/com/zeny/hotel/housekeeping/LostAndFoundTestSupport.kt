package com.zeny.hotel.housekeeping

import com.zeny.hotel.iam.IamTestSupport
import io.kotest.matchers.nulls.shouldNotBeNull
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Apoio dos testes de achados e perdidos (M6 E06, Task 1): registra itens pela API, muda o `found_at` direto no banco
 * (o vencimento depende da data em que o item foi achado, e os testes não avançam a data operacional) e chama as ações
 * de destino com o `If-Match` atual.
 */
class LostAndFoundTestSupport(
    private val iam: IamTestSupport,
    private val jdbc: JdbcClient,
) {
    val housekeeping = HousekeepingTestSupport(iam, jdbc)

    fun body(
        roomId: UUID? = null,
        areaId: UUID? = null,
        description: String? = "Carteira marrom",
        storagePlace: String? = "Armário da governança",
    ): Map<String, Any?> =
        mapOf("roomId" to roomId, "areaId" to areaId, "description" to description, "storagePlace" to storagePlace)

    fun register(
        auth: RequestPostProcessor,
        body: Map<String, Any?> = body(roomId = housekeeping.createRoom()),
    ): MockHttpServletResponse = iam.call(POST, ITEMS, auth, body)

    /** Registra um item numa UH nova como [auth] e devolve o id (`201`). */
    fun registered(auth: RequestPostProcessor = iam.asAdmin()): UUID =
        with(iam) { UUID.fromString(register(auth).expectStatus(201)!!["id"].asString()) }

    /** Muda o instante em que o item foi achado, direto no banco. */
    fun foundAt(
        id: UUID,
        at: Instant,
    ) {
        jdbc
            .sql("UPDATE housekeeping.lost_and_found_item SET found_at = :at WHERE id = :id")
            .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
            .param("id", id)
            .update()
    }

    /** A ação de destino [action] (`return`, `discard`, `donate`) com o [ifMatch] informado ou o atual. */
    fun act(
        id: UUID,
        action: String,
        auth: RequestPostProcessor,
        body: Map<String, Any?>,
        ifMatch: String? = etag(id),
    ): MockHttpServletResponse = iam.call(POST, "$ITEMS/$id/$action", auth, body, ifMatch)

    fun etag(id: UUID): String = housekeeping.etagOf("$ITEMS/$id")

    fun item(id: UUID): JsonNode = with(iam) { call(GET, "$ITEMS/$id", asAdmin()).ok() }

    fun etagHeader(response: MockHttpServletResponse): String = response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()

    companion object {
        const val ITEMS = "/api/v1/lost-and-found-items"
    }
}
