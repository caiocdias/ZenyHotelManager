package com.zeny.hotel.notifications.infrastructure

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.zeny.hotel.notifications.application.PushGateway
import com.zeny.hotel.notifications.application.PushMessage
import com.zeny.hotel.notifications.application.PushOutcome
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * Configuração do push pelo FCM HTTP v1 (H7, decidida pelo usuário em 2026-10-16), em `zeny.notifications.push.fcm`:
 *
 * - [credentials] (`ZENY_FCM_CREDENTIALS`): o JSON da conta de serviço do projeto Firebase, em base64; vazio, o
 *   push fica desligado e só o painel é entregue. O `project_id` e o `token_uri` saem do próprio JSON.
 * - [endpoint]: a raiz da API do FCM (`https://fcm.googleapis.com`; outra só nos testes, com o WireMock).
 * - [connectTimeout] e [readTimeout]: limites de cada chamada (o envio roda no listener, fora da requisição).
 *
 * O [toString] nunca mostra a credencial.
 */
@ConfigurationProperties("zeny.notifications.push.fcm")
data class FcmProperties(
    val credentials: String = "",
    val endpoint: URI = URI.create("https://fcm.googleapis.com"),
    val connectTimeout: Duration = Duration.ofSeconds(DEFAULT_CONNECT_SECONDS),
    val readTimeout: Duration = Duration.ofSeconds(DEFAULT_READ_SECONDS),
) {
    val enabled: Boolean get() = credentials.isNotBlank()

    override fun toString(): String =
        "FcmProperties(credentials=${if (enabled) "***" else "<vazia>"}, endpoint=$endpoint, " +
            "connectTimeout=$connectTimeout, readTimeout=$readTimeout)"

    private companion object {
        const val DEFAULT_CONNECT_SECONDS = 5L
        const val DEFAULT_READ_SECONDS = 10L
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FcmProperties::class)
class FcmConfiguration

/**
 * A conta de serviço do Google lida de `ZENY_FCM_CREDENTIALS` (o JSON baixado do console do Firebase, em base64):
 * `type` `service_account`, `project_id`, `client_email`, `private_key` (PEM PKCS#8), `private_key_id` e
 * `token_uri`. O [toString] e as mensagens de erro nunca mostram a chave.
 */
class FcmServiceAccount private constructor(
    val projectId: String,
    val clientEmail: String,
    val privateKeyId: String?,
    val privateKey: RSAPrivateKey,
    val tokenUri: URI,
) {
    override fun toString(): String = "FcmServiceAccount(projectId=$projectId, clientEmail=$clientEmail)"

    companion object {
        private val mapper: JsonMapper = JsonMapper.builder().build()

        /**
         * Lê a conta de serviço; fora do formato, `IllegalArgumentException` sem o conteúdo: a causa original não
         * vai junto, porque a mensagem do decodificador ou do leitor de JSON pode citar trechos da chave privada.
         */
        @Suppress("SwallowedException", "ThrowsCount") // a causa pode conter a chave; um motivo por campo
        fun parse(base64: String): FcmServiceAccount {
            val bytes =
                try {
                    Base64.getMimeDecoder().decode(base64.trim())
                } catch (e: IllegalArgumentException) {
                    throw invalid("não é base64 (${e.javaClass.simpleName})")
                }
            val json =
                try {
                    mapper.readTree(bytes)
                } catch (e: JacksonException) {
                    throw invalid("não é um JSON (${e.javaClass.simpleName})")
                }?.takeIf { it.isObject } ?: throw invalid("não é um objeto JSON")
            require(json.text("type") == "service_account") { message("o tipo não é service_account") }
            val key =
                try {
                    val pem = json.text("private_key") ?: throw invalid("sem private_key")
                    require(pem.contains(PEM_BEGIN)) { message("a private_key não é PEM PKCS#8") }
                    val der = Base64.getMimeDecoder().decode(pem.substringAfter(PEM_BEGIN).substringBefore(PEM_END))
                    KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateKey
                } catch (e: java.security.GeneralSecurityException) {
                    throw invalid("a private_key não é uma chave RSA (${e.javaClass.simpleName})")
                }
            return FcmServiceAccount(
                projectId = json.text("project_id") ?: throw invalid("sem project_id"),
                clientEmail = json.text("client_email") ?: throw invalid("sem client_email"),
                privateKeyId = json.text("private_key_id"),
                privateKey = key,
                tokenUri = URI.create(json.text("token_uri") ?: DEFAULT_TOKEN_URI),
            )
        }

        private fun JsonNode.text(field: String): String? =
            get(field)
                ?.takeIf {
                    it.isString
                }?.asString()
                ?.takeIf { it.isNotBlank() }

        private fun message(reason: String) = "ZENY_FCM_CREDENTIALS inválida: $reason"

        private fun invalid(reason: String) = IllegalArgumentException(message(reason))

        private const val PEM_BEGIN = "-----BEGIN PRIVATE KEY-----"
        private const val PEM_END = "-----END PRIVATE KEY-----"
        private const val DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token"
    }
}

/**
 * Classificação dos erros do FCM HTTP v1 (`error.details[].errorCode` do tipo `FcmError`, ou `error.status`):
 * `UNREGISTERED`, `SENDER_ID_MISMATCH` e `INVALID_ARGUMENT` (o conteúdo é montado pelo servidor dentro dos
 * limites; o argumento inválido é o token) querem dizer que o token não vale mais; o resto (`UNAVAILABLE`,
 * `INTERNAL`, `QUOTA_EXCEEDED`, `THIRD_PARTY_AUTH_ERROR`, `PERMISSION_DENIED`, `UNAUTHENTICATED`, resposta sem
 * JSON) é temporário: o outbox reprocessa, e o que não passar em 24 h fica abandonado com alerta (E12).
 */
object FcmErrors {
    private val mapper: JsonMapper = JsonMapper.builder().build()
    private val INVALID_TOKEN = setOf("UNREGISTERED", "SENDER_ID_MISMATCH", "INVALID_ARGUMENT")

    fun classify(
        status: Int,
        body: String,
    ): PushOutcome {
        val error =
            try {
                mapper.readTree(body)?.get("error")
            } catch (_: JacksonException) {
                null
            }
        val code =
            error
                ?.get("details")
                ?.firstOrNull { it.get("errorCode") != null }
                ?.get("errorCode")
                ?.asString()
                ?: error?.get("status")?.takeIf { it.isString }?.asString()
        return if (code in
            INVALID_TOKEN
        ) {
            PushOutcome.InvalidToken
        } else {
            PushOutcome.TemporaryFailure("HTTP $status ${code.orEmpty()}".trim())
        }
    }
}

/**
 * O adaptador do push pelo FCM HTTP v1 (ARCHITECTURE §12, H7): o access token OAuth 2.0 sai de um JWT assinado com
 * a chave da conta de serviço (grant `jwt-bearer`, escopo `firebase.messaging`, guardado até 1 min antes de vencer)
 * e cada mensagem vai em `POST /v1/projects/{project_id}/messages:send`. Sem `ZENY_FCM_CREDENTIALS`, fica desligado
 * ([enabled] falso); com uma credencial ilegível, a aplicação não sobe. O log leva só o resultado, nunca o token
 * do aparelho, o access token, o título nem o texto.
 */
@Component
class FcmPushGateway(
    properties: FcmProperties,
    private val clock: Clock,
    private val jsonMapper: JsonMapper,
) : PushGateway {
    private val account: FcmServiceAccount? =
        properties.credentials
            .takeIf {
                it.isNotBlank()
            }?.let(FcmServiceAccount::parse)
    private val sendUri: URI? =
        account?.let {
            URI.create("${properties.endpoint.toString().trimEnd('/')}/v1/projects/${it.projectId}/messages:send")
        }
    private val http: RestClient =
        RestClient
            .builder()
            .requestFactory(
                JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build())
                    .apply { setReadTimeout(properties.readTimeout) },
            ).build()

    @Volatile
    private var accessToken: AccessToken? = null

    override val enabled: Boolean get() = account != null

    init {
        if (account == null) {
            log.info("Push desligado: ZENY_FCM_CREDENTIALS vazia (só o painel é entregue)")
        } else {
            log.info("Push pelo FCM HTTP v1 ligado para o projeto {}", account.projectId)
        }
    }

    override fun send(message: PushMessage): PushOutcome {
        val uri = checkNotNull(sendUri) { "Push desligado: sem ZENY_FCM_CREDENTIALS" }
        val bearer = currentAccessToken() ?: return PushOutcome.TemporaryFailure("token OAuth indisponível")
        return try {
            http
                .post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $bearer")
                .body(jsonMapper.writeValueAsString(payload(message)))
                .exchange { _, response ->
                    val status = response.statusCode.value()
                    if (response.statusCode.is2xxSuccessful) return@exchange PushOutcome.Delivered
                    if (status == UNAUTHORIZED) accessToken = null
                    FcmErrors.classify(status, response.body.readAllBytes().toString(Charsets.UTF_8))
                }
        } catch (e: RestClientException) {
            PushOutcome.TemporaryFailure("falha de rede (${e.javaClass.simpleName})")
        }
    }

    private fun payload(message: PushMessage) =
        mapOf(
            "message" to
                mapOf(
                    "token" to message.token,
                    "notification" to mapOf("title" to message.title, "body" to message.body),
                    "data" to message.data,
                    "android" to mapOf("priority" to "HIGH"),
                ),
        )

    /** O access token em cache, ou um novo; `null` se o endpoint de token falhar (falha temporária). */
    @Synchronized
    private fun currentAccessToken(): String? {
        val now = clock.instant()
        val token =
            accessToken?.takeIf { it.expiresAt.isAfter(now.plus(TOKEN_MARGIN)) }
                ?: requestAccessToken(checkNotNull(account), now)?.also { accessToken = it }
        return token?.value
    }

    /** Troca um JWT assinado pela conta de serviço por um access token (grant `jwt-bearer`, RFC 7523). */
    private fun requestAccessToken(
        account: FcmServiceAccount,
        now: Instant,
    ): AccessToken? =
        try {
            val form = "grant_type=${encode(JWT_BEARER_GRANT)}&assertion=${encode(assertion(account, now))}"
            val response =
                http
                    .post()
                    .uri(account.tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange { _, response ->
                        if (!response.statusCode.is2xxSuccessful) {
                            log.warn("FCM: o endpoint de token OAuth respondeu {}", response.statusCode.value())
                            return@exchange null
                        }
                        jsonMapper.readTree(response.body.readAllBytes())
                    }
            response?.get("access_token")?.takeIf { it.isString }?.asString()?.let { value ->
                val lifetime = response.get("expires_in")?.takeIf { it.isNumber }?.asLong() ?: DEFAULT_TOKEN_SECONDS
                AccessToken(value, now.plusSeconds(lifetime))
            }
        } catch (e: RestClientException) {
            log.warn("FCM: falha ao pedir o token OAuth ({})", e.javaClass.simpleName)
            null
        } catch (e: JacksonException) {
            log.warn("FCM: resposta ilegível do endpoint de token OAuth ({})", e.javaClass.simpleName)
            null
        }

    private fun assertion(
        account: FcmServiceAccount,
        now: Instant,
    ): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(account.clientEmail)
                .audience(account.tokenUri.toString())
                .claim("scope", FCM_SCOPE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ASSERTION_LIFETIME)))
                .jwtID(UUID.randomUUID().toString())
                .build()
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .apply { account.privateKeyId?.let(::keyID) }
                .build()
        return SignedJWT(header, claims).apply { sign(RSASSASigner(account.privateKey)) }.serialize()
    }

    private class AccessToken(
        val value: String,
        val expiresAt: Instant,
    ) {
        override fun toString(): String = "AccessToken(expiresAt=$expiresAt)"
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(FcmPushGateway::class.java)
        const val FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
        const val JWT_BEARER_GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer"
        const val UNAUTHORIZED = 401
        const val DEFAULT_TOKEN_SECONDS = 3600L
        val TOKEN_MARGIN: Duration = Duration.ofMinutes(1)
        val ASSERTION_LIFETIME: Duration = Duration.ofHours(1)

        fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
    }
}
