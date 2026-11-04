package com.zeny.hotel.testsupport

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.any
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.verification.LoggedRequest
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

    /** `POST /reservas` da v2.4: `200` com a reserva `CRIADA`. */
    @Suppress("ktlint:standard:function-naming") // o sufixo v2_4 nomeia a versão da API
    fun stubRegisterReservation_v2_4(
        reservationId: String,
        precheckinLink: String? = "https://precheckin.example/r/1",
    ) {
        val link = precheckinLink?.let { ""","link_precheckin":"$it"""" }.orEmpty()
        server.stubFor(
            any(urlEqualTo("$BASE_PATH/reservas"))
                .willReturn(
                    aResponse().withStatus(OK).withHeader("Content-Type", JSON).withBody(
                        """{"reserva":{"reserva_id":"$reservationId","situacao_reserva_id":"CRIADA"$link}}""",
                    ),
                ),
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
