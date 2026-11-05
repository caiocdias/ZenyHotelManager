package com.zeny.hotel.testsupport

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.any
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.patch
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.put
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import java.net.URLEncoder
import java.util.Base64

/**
 * Uma FNRH de mentira (WireMock) para os testes do adaptador do Serpro (API v2.4). Serve a testes com e sem contexto
 * do Spring: um servidor por JVM, zerado por teste ([reset]). Os caminhos ficam sob [BASE_PATH], como na URL real
 * (`…/FNRH_API/rest/v2`); aponte a conexão para [baseUrl].
 *
 * Os stubs ficam nomeados pela versão da API (`v2_4`).
 */
object FnrhTestServer {
    const val BASE_PATH = "/FNRH_API/rest/v2"
    const val USERNAME = "hotel-user"
    const val PASSWORD = "s3cr3t-pass"

    val server: WireMockServer = WireMockServer(options().dynamicPort()).also { it.start() }

    val baseUrl: String get() = "http://localhost:${server.port()}$BASE_PATH"

    /** O header `Authorization` que a API v2.4 espera para [USERNAME] e [PASSWORD]. */
    val basicAuthorization: String
        get() = "Basic " + Base64.getEncoder().encodeToString("$USERNAME:$PASSWORD".toByteArray())

    /** O maior atraso dos stubs desde o último [reset]: o WireMock só registra o pedido ao fim do atraso. */
    @Volatile
    private var longestDelayMs = 0

    /**
     * Zera os pedidos e os stubs. Antes, espera o fim dos atrasos pendentes: o WireMock grava o pedido no diário só
     * quando termina de responder, e um pedido de um teste que estourou o timeout cairia no teste seguinte.
     */
    fun reset() {
        if (longestDelayMs > 0) {
            Thread.sleep(longestDelayMs + SETTLE_MS)
            longestDelayMs = 0
        }
        server.resetAll()
    }

    /**
     * Os stubs por operação da API v2.4, para a E08 reaproveitar. Cada um só casa se o método, o caminho, o
     * `Authorization` (Basic com [USERNAME] e [PASSWORD]), o `Content-Type` e o `cpf_solicitante` (presente só em
     * `POST /pessoas`) forem os da API: um pedido fora disso cai no `404` do WireMock e o teste vê a diferença.
     */
    private fun MappingBuilder.strictV24(
        contentType: String?,
        requesterCpf: String? = null,
    ): MappingBuilder {
        withHeader("Authorization", equalTo(basicAuthorization))
        if (contentType != null) withHeader("Content-Type", containing(contentType))
        withHeader("cpf_solicitante", requesterCpf?.let { equalTo(it) } ?: absent())
        return this
    }

    private fun json(body: String) = aResponse().withStatus(OK).withHeader("Content-Type", JSON).withBody(body)

    /** `POST /reservas` da v2.4: `200` com a reserva `CRIADA`. */
    @Suppress("ktlint:standard:function-naming") // o sufixo v2_4 nomeia a versão da API
    fun stubRegisterReservation_v2_4(
        reservationId: String,
        precheckinLink: String? = "https://precheckin.example/r/1",
    ) {
        val link = precheckinLink?.let { ""","link_precheckin":"$it"""" }.orEmpty()
        server.stubFor(
            post(urlEqualTo("$BASE_PATH/reservas"))
                .strictV24(JSON)
                .willReturn(
                    json("""{"reserva":{"reserva_id":"$reservationId","situacao_reserva_id":"CRIADA"$link}}"""),
                ),
        )
    }

    /** `PUT /reservas/{id}`: `200` de corpo vazio. */
    @Suppress("ktlint:standard:function-naming")
    fun stubUpdateReservation_v2_4(reservationId: String) {
        server.stubFor(
            put(urlEqualTo("$BASE_PATH/reservas/$reservationId")).strictV24(JSON).willReturn(json("")),
        )
    }

    /** `POST /reservas/{id}/cancelar`: `200` de corpo vazio. */
    @Suppress("ktlint:standard:function-naming")
    fun stubCancelReservation_v2_4(reservationId: String) {
        server.stubFor(
            post(urlEqualTo("$BASE_PATH/reservas/$reservationId/cancelar")).strictV24(JSON).willReturn(json("")),
        )
    }

    /** `GET /reservas?page_number=1&codigo_reserva=…`: a lista com os ids dados (cada um com o `numero_reserva`). */
    @Suppress("ktlint:standard:function-naming")
    fun stubFindReservationByCode_v2_4(
        reservationCode: String,
        vararg reservationIds: String,
    ) {
        val items = reservationIds.joinToString(",") { """{"reserva_id":"$it","numero_reserva":"$reservationCode"}""" }
        val code = URLEncoder.encode(reservationCode, Charsets.UTF_8)
        server.stubFor(
            get(urlEqualTo("$BASE_PATH/reservas?page_number=1&codigo_reserva=$code"))
                .strictV24(JSON)
                .willReturn(json("""{"dados":[$items],"pagination":{"TotalRegistros":${reservationIds.size}}}""")),
        )
    }

    /** `POST /pessoas`: exige o `cpf_solicitante` dado (só dígitos) e devolve `pessoa_id`. */
    @Suppress("ktlint:standard:function-naming")
    fun stubRegisterPerson_v2_4(
        personId: String,
        requesterCpf: String,
    ) {
        server.stubFor(
            post(urlEqualTo("$BASE_PATH/pessoas"))
                .strictV24(JSON, requesterCpf)
                .willReturn(json("""{"pessoa_id":"$personId"}""")),
        )
    }

    /** `POST /reservas/{id}/hospedes`: devolve `hospede_id`. */
    @Suppress("ktlint:standard:function-naming")
    fun stubAddGuest_v2_4(
        reservationId: String,
        guestId: String,
    ) {
        server.stubFor(
            post(urlEqualTo("$BASE_PATH/reservas/$reservationId/hospedes"))
                .strictV24(JSON)
                .willReturn(json("""{"hospede_id":"$guestId"}""")),
        )
    }

    /** `PATCH /hospedes/{id}/checkout`: exige corpo `text/plain` com o instante `…T…:…:….mmmZ`, sem aspas. */
    @Suppress("ktlint:standard:function-naming")
    fun stubCheckOut_v2_4(guestId: String) {
        server.stubFor(
            patch(urlEqualTo("$BASE_PATH/hospedes/$guestId/checkout"))
                .strictV24("text/plain")
                .withRequestBody(matching("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z"""))
                .willReturn(json("""{"dados":{"hospede_id":"$guestId","situacao_id":"CHECKOUT_REALIZADO"}}""")),
        )
    }

    /** `GET` de um domínio: a lista `{"dados":[{"id","label"}]}`. */
    @Suppress("ktlint:standard:function-naming")
    fun stubDomain_v2_4(
        path: String,
        vararg values: Pair<String, String>,
    ) {
        val items = values.joinToString(",") { (id, label) -> """{"id":"$id","label":"$label"}""" }
        server.stubFor(
            get(urlEqualTo("$BASE_PATH$path")).strictV24(JSON).willReturn(json("""{"dados":[$items]}""")),
        )
    }

    /** Responde [status] e [body] (JSON) a qualquer método em [path] (com a query, se houver). */
    fun stub(
        path: String,
        status: Int = OK,
        body: String = "",
        delayMs: Int = 0,
    ) {
        server.stubFor(
            any(urlEqualTo("$BASE_PATH$path")).respond(status, body, delayMs),
        )
    }

    /** Responde [status] e [body] a qualquer requisição (útil para os erros). */
    fun stubAny(
        status: Int,
        body: String = "",
        delayMs: Int = 0,
    ) {
        server.stubFor(any(anyUrl()).respond(status, body, delayMs))
    }

    /** Todas as requisições recebidas, na ordem. */
    fun requests(): List<LoggedRequest> = server.findAll(anyRequestedFor(anyUrl()))

    /** A única requisição recebida (falha se houver zero ou mais de uma). */
    fun onlyRequest(): LoggedRequest {
        val all = requests()
        check(all.size == 1) { "esperava 1 requisição, houve ${all.size}" }
        return all.single()
    }

    private fun MappingBuilder.respond(
        status: Int,
        body: String,
        delayMs: Int,
    ): MappingBuilder {
        longestDelayMs = maxOf(longestDelayMs, delayMs)
        return willReturn(
            aResponse()
                .withStatus(status)
                .withHeader("Content-Type", JSON)
                .withBody(body)
                .withFixedDelay(delayMs),
        )
    }

    private const val SETTLE_MS = 100L
    private const val OK = 200
    private const val JSON = "application/json"
}
