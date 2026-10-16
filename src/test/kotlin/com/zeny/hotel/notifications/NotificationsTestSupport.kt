package com.zeny.hotel.notifications

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import com.zeny.hotel.notifications.application.PushGateway
import com.zeny.hotel.notifications.infrastructure.FcmProperties
import com.zeny.hotel.notifications.infrastructure.FcmPushGateway
import com.zeny.hotel.shared.ModulePermissions
import notificationsexample.NotificationsExamplePermissions
import notificationsexample.NotificationsExampleTypes
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Clock
import java.util.Base64

/**
 * Um FCM de mentira (WireMock) para os testes do push (ARCHITECTURE §12): o endpoint de token OAuth da conta de
 * serviço (`/token`) e o de envio do FCM HTTP v1 (`/v1/projects/zeny-test/messages:send`). A conta de serviço é
 * gerada na JVM (chave RSA sorteada): nenhuma credencial fica no repositório. Um servidor por JVM, zerado por
 * teste ([reset]).
 */
object FcmTestServer {
    const val PROJECT_ID = "zeny-test"
    const val CLIENT_EMAIL = "push@zeny-test.iam.gserviceaccount.com"
    const val ACCESS_TOKEN = "ya29.test-access-token"
    const val SEND_PATH = "/v1/projects/$PROJECT_ID/messages:send"
    private const val KEY_BITS = 2048

    val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()

    val server: WireMockServer = WireMockServer(options().dynamicPort()).also { it.start() }

    val baseUrl: String get() = "http://localhost:${server.port()}"

    /** O JSON da conta de serviço, como o Google o entrega, em base64 (o formato de `ZENY_FCM_CREDENTIALS`). */
    val credentials: String by lazy {
        val pem =
            "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded) +
                "\n-----END PRIVATE KEY-----\n"
        val json =
            """
            {
              "type": "service_account",
              "project_id": "$PROJECT_ID",
              "private_key_id": "test-key-1",
              "private_key": "${pem.replace("\n", "\\n")}",
              "client_email": "$CLIENT_EMAIL",
              "client_id": "1234567890",
              "token_uri": "$baseUrl/token"
            }
            """.trimIndent()
        Base64.getEncoder().encodeToString(json.toByteArray())
    }

    /** Zera os pedidos e os stubs, com o token OAuth e o envio respondendo com sucesso. */
    fun reset() {
        server.resetAll()
        server.stubFor(
            post(urlEqualTo("/token")).willReturn(
                okJson("""{"access_token":"$ACCESS_TOKEN","expires_in":3599,"token_type":"Bearer"}"""),
            ),
        )
        server.stubFor(
            post(urlEqualTo(SEND_PATH)).willReturn(okJson("""{"name":"projects/$PROJECT_ID/messages/0:1"}""")),
        )
    }

    /** O envio para o [token] responde com o erro do FCM HTTP v1 [status]/[errorCode]. */
    fun failFor(
        token: String,
        status: Int,
        errorCode: String,
        scenario: String? = null,
        times: Int = Int.MAX_VALUE,
    ) {
        val body =
            """
            {"error":{"code":$status,"message":"falha simulada","status":"$errorCode",
             "details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"$errorCode"}]}}
            """.trimIndent()
        if (scenario == null || times == Int.MAX_VALUE) {
            server.stubFor(
                post(urlEqualTo(SEND_PATH))
                    .withRequestBody(matchingJsonPath("$.message.token", equalTo(token)))
                    .willReturn(
                        aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body),
                    ),
            )
            return
        }
        // Falha [times] vezes e depois responde com sucesso.
        (0 until times).forEach { attempt ->
            server.stubFor(
                post(urlEqualTo(SEND_PATH))
                    .inScenario(scenario)
                    .whenScenarioStateIs(if (attempt == 0) "Started" else "failed-$attempt")
                    .withRequestBody(matchingJsonPath("$.message.token", equalTo(token)))
                    .willReturn(
                        aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body),
                    ).willSetStateTo("failed-${attempt + 1}"),
            )
        }
        server.stubFor(
            post(urlEqualTo(SEND_PATH))
                .inScenario(scenario)
                .whenScenarioStateIs("failed-$times")
                .withRequestBody(matchingJsonPath("$.message.token", equalTo(token)))
                .willReturn(okJson("""{"name":"projects/$PROJECT_ID/messages/0:2"}""")),
        )
    }

    /** O envio para o [token] derruba a conexão (falha de rede). */
    fun dropConnectionFor(token: String) {
        server.stubFor(
            post(urlEqualTo(SEND_PATH))
                .withRequestBody(matchingJsonPath("$.message.token", equalTo(token)))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
        )
    }

    /** Os envios recebidos para o [token]. */
    fun sendsTo(token: String): List<LoggedRequest> =
        server.findAll(
            postRequestedFor(
                urlEqualTo(SEND_PATH),
            ).withRequestBody(matchingJsonPath("$.message.token", equalTo(token))),
        )

    fun tokenRequests(): List<LoggedRequest> = server.findAll(postRequestedFor(urlEqualTo("/token")))
}

/**
 * Contexto dos testes de notificações: o push ligado contra o [FcmTestServer] (o adaptador do FCM com a conta de
 * serviço de teste, num bean `@Primary`, para todas as classes compartilharem o mesmo contexto), a permissão e o
 * tipo de exemplo.
 */
@TestConfiguration(proxyBeanMethods = false)
class NotificationsTestConfiguration {
    @Bean
    @Primary
    fun testFcmPushGateway(
        clock: Clock,
        jsonMapper: JsonMapper,
    ): PushGateway =
        FcmPushGateway(
            FcmProperties(credentials = FcmTestServer.credentials, endpoint = URI.create(FcmTestServer.baseUrl)),
            clock,
            jsonMapper,
        )

    @Bean
    fun notificationsExamplePermissions(): ModulePermissions = NotificationsExamplePermissions

    @Bean
    fun notificationsExampleTypes(): ModuleNotificationTypes = NotificationsExampleTypes
}

/** Autentica a requisição do `MockMvc` com um access token de verdade (de uma sessão existente). */
fun bearer(accessToken: String): RequestPostProcessor =
    RequestPostProcessor { request ->
        request.addHeader("Authorization", "Bearer $accessToken")
        request
    }
