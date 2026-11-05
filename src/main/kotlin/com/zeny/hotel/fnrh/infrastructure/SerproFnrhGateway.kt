package com.zeny.hotel.fnrh.infrastructure

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.zeny.hotel.fnrh.application.FnrhCall
import com.zeny.hotel.fnrh.application.FnrhConnection
import com.zeny.hotel.fnrh.application.FnrhConnectionSource
import com.zeny.hotel.fnrh.application.FnrhFailure
import com.zeny.hotel.fnrh.application.FnrhGateway
import com.zeny.hotel.fnrh.application.FnrhGatewayException
import com.zeny.hotel.fnrh.application.FnrhIntegrationLog
import com.zeny.hotel.fnrh.application.FnrhReservationRegistered
import com.zeny.hotel.fnrh.domain.FnrhDocumentType
import com.zeny.hotel.fnrh.domain.FnrhDomain
import com.zeny.hotel.fnrh.domain.FnrhDomainValue
import com.zeny.hotel.fnrh.domain.FnrhGuest
import com.zeny.hotel.fnrh.domain.FnrhGuestStatus
import com.zeny.hotel.fnrh.domain.FnrhPersonData
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.fnrh.domain.FnrhReservationData
import com.zeny.hotel.shared.Cpf
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriUtils
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.ConnectException
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * O adaptador da FNRH do Serpro (API v2.4, `docs/fnrh/api-fnrh-v2.4.md`), no modelo do `FcmPushGateway`: um
 * `RestClient` sobre o `JdkClientHttpRequestFactory`, com `exchange` para nunca receber a exceção de status do
 * cliente. Cada chamada lê a conexão vigente ([FnrhConnectionSource]), registra uma [FnrhCall] no
 * [FnrhIntegrationLog] e, em falha, lança [FnrhGatewayException] sem causa encadeada (a do cliente HTTP pode trazer o
 * corpo da resposta). A URL (que pode ter o número do documento) e o corpo nunca vão a log.
 *
 * Um timeout de leitura num `POST`/`PUT`/`PATCH` é [FnrhFailure.UNKNOWN_OUTCOME] (o Serpro pode ter gravado); num
 * `GET`, e em toda falha de conexão (nada foi enviado), é [FnrhFailure.UNAVAILABLE].
 */
@Component
class SerproFnrhGateway(
    properties: FnrhProperties,
    private val connectionSource: FnrhConnectionSource,
    private val integrationLog: FnrhIntegrationLog,
) : FnrhGateway {
    private val http: RestClient =
        RestClient
            .builder()
            .requestFactory(
                JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build())
                    .apply { setReadTimeout(properties.readTimeout) },
            ).build()

    override fun registerReservation(
        reservation: FnrhReservationData,
        correlationRef: String,
    ): FnrhReservationRegistered =
        call(
            operation = "registerReservation",
            correlationRef = correlationRef,
            method = HttpMethod.POST,
            segments = listOf("reservas"),
            body = jsonPayload(ReservationBody.of(reservation, withOrigin = true)),
            sentValues = listOfNotNull(reservation.reservationCode, reservation.otaCode),
            remoteIdOf = { it.reservationId.value },
        ) { tree ->
            val reserva = tree?.get("reserva")
            reserva
                ?.text(
                    "reserva_id",
                )?.let { FnrhReservationRegistered(FnrhRemoteId(it), reserva.text("link_precheckin")) }
        }

    override fun updateReservation(
        reservationId: FnrhRemoteId,
        reservation: FnrhReservationData,
        correlationRef: String,
    ) = call(
        operation = "updateReservation",
        correlationRef = correlationRef,
        method = HttpMethod.PUT,
        segments = listOf("reservas", reservationId.value),
        body = jsonPayload(ReservationBody.of(reservation, withOrigin = false)),
        sentValues = listOfNotNull(reservation.reservationCode, reservation.otaCode),
        knownRemoteId = reservationId.value,
    ) { Unit }

    override fun cancelReservation(
        reservationId: FnrhRemoteId,
        correlationRef: String,
    ) = call(
        operation = "cancelReservation",
        correlationRef = correlationRef,
        method = HttpMethod.POST,
        segments = listOf("reservas", reservationId.value, "cancelar"),
        knownRemoteId = reservationId.value,
    ) { Unit }

    override fun findReservationByCode(
        reservationCode: String,
        correlationRef: String,
    ): FnrhRemoteId? =
        call(
            operation = "findReservationByCode",
            correlationRef = correlationRef,
            method = HttpMethod.GET,
            segments = listOf("reservas"),
            query = listOf("page_number" to "1", "codigo_reserva" to reservationCode),
            sentValues = listOf(reservationCode),
            remoteIdOf = { it.firstOrNull() },
        ) { tree -> tree?.get("dados")?.takeIf { it.isArray }?.mapNotNull { it.text("reserva_id") } }
            .firstOrNull()
            ?.let(::FnrhRemoteId)

    override fun registerPerson(
        person: FnrhPersonData,
        requesterCpf: Cpf,
        correlationRef: String,
    ): FnrhRemoteId =
        call(
            operation = "registerPerson",
            correlationRef = correlationRef,
            method = HttpMethod.POST,
            segments = listOf("pessoas"),
            body = jsonPayload(PersonEnvelope(PersonBody.of(person))),
            headers = mapOf(REQUESTER_HEADER to requesterCpf.digits),
            sentValues =
                listOfNotNull(
                    person.name,
                    person.socialName,
                    person.document?.number,
                    person.email,
                    person.phone,
                    requesterCpf.digits,
                ),
            remoteIdOf = { it.value },
        ) { tree -> tree?.text("pessoa_id")?.let(::FnrhRemoteId) }

    override fun addGuestToReservation(
        reservationId: FnrhRemoteId,
        personId: FnrhRemoteId,
        guest: FnrhGuest,
        correlationRef: String,
    ): FnrhRemoteId =
        call(
            operation = "addGuestToReservation",
            correlationRef = correlationRef,
            method = HttpMethod.POST,
            segments = listOf("reservas", reservationId.value, "hospedes"),
            body = jsonPayload(GuestBody.of(personId, guest)),
            remoteIdOf = { it.value },
        ) { tree -> tree?.text("hospede_id")?.let(::FnrhRemoteId) }

    override fun checkOut(
        guestId: FnrhRemoteId,
        at: Instant,
        correlationRef: String,
    ) = call(
        operation = "checkOut",
        correlationRef = correlationRef,
        method = HttpMethod.PATCH,
        segments = listOf("hospedes", guestId.value, "checkout"),
        body = textPayload(formatInstant(at)),
        knownRemoteId = guestId.value,
    ) { Unit }

    override fun fetchDomain(domain: FnrhDomain): List<FnrhDomainValue> =
        call(
            operation = "fetchDomain",
            correlationRef = null,
            method = HttpMethod.GET,
            segments = domain.path.trim('/').split('/'),
        ) { tree ->
            tree?.get("dados")?.takeIf { it.isArray }?.mapNotNull { item ->
                item.text("id")?.let { FnrhDomainValue(it, item.text("label").orEmpty()) }
            }
        }

    // ---- a chamada HTTP

    private class Reply(
        val status: Int,
        val body: ByteArray,
    )

    /**
     * Faz uma chamada: [parse] extrai o resultado do JSON da resposta (`null` se ele não traz o que devia), e
     * [remoteIdOf] ou [knownRemoteId] dão a referência remota que vai ao registro.
     */
    @Suppress("LongParameterList", "ThrowsCount", "SwallowedException", "TooGenericExceptionCaught")
    private fun <T : Any> call(
        operation: String,
        correlationRef: String?,
        method: HttpMethod,
        segments: List<String>,
        query: List<Pair<String, String>> = emptyList(),
        body: Payload? = null,
        headers: Map<String, String> = emptyMap(),
        sentValues: Collection<String> = emptyList(),
        knownRemoteId: String? = null,
        remoteIdOf: (T) -> String? = { null },
        parse: (JsonNode?) -> T?,
    ): T {
        val connection =
            connectionSource.current() ?: throw FnrhGatewayException(FnrhFailure.NOT_CONFIGURED, null, null)
        val write = method != HttpMethod.GET
        val started = System.nanoTime()

        fun fail(
            failure: FnrhFailure,
            status: Int?,
            message: String?,
        ): Nothing {
            record(connection, operation, failure.name, status, started, correlationRef, knownRemoteId)
            log.warn(
                "FNRH {} falhou: {} (HTTP {}), ref {}{}",
                operation,
                failure,
                status ?: "-",
                correlationRef ?: "-",
                message?.let { ": $it" }.orEmpty(),
            )
            throw FnrhGatewayException(failure, status, message)
        }

        // RFC 7617: o user-id do Basic não tem ':'; a configuração já o barra, isto cobre um valor antigo.
        if (':' in connection.username) fail(FnrhFailure.UNAUTHORIZED, null, null)
        // Montar a URL é antes do envio: se falhar, nada saiu (UNAVAILABLE), nunca UNKNOWN_OUTCOME.
        val uri =
            try {
                requestUri(connection.baseUrl, segments, query)
            } catch (e: RuntimeException) {
                log.warn("FNRH {}: montagem da requisição falhou ({})", operation, e.javaClass.simpleName)
                fail(FnrhFailure.UNAVAILABLE, null, null)
            }
        val reply =
            try {
                send(connection, method, uri, body, headers)
            } catch (e: RuntimeException) {
                // Só a classe vai ao log, nunca a mensagem nem a causa (podem trazer o corpo da resposta).
                log.warn("FNRH {}: falha no envio ({})", operation, e.javaClass.simpleName)
                fail(sendFailure(e, write), null, null)
            }
        val tree = readTree(reply.body)
        if (reply.status !in HTTP_OK_RANGE) {
            fail(failureOf(reply.status), reply.status, errorMessage(tree, sentValues))
        }
        val result =
            parse(tree)
                ?: fail(if (write) FnrhFailure.UNKNOWN_OUTCOME else FnrhFailure.SERVER_ERROR, reply.status, null)
        record(
            connection,
            operation,
            SUCCESS,
            reply.status,
            started,
            correlationRef,
            remoteIdOf(result) ?: knownRemoteId,
        )
        return result
    }

    private fun send(
        connection: FnrhConnection,
        method: HttpMethod,
        uri: URI,
        body: Payload?,
        headers: Map<String, String>,
    ): Reply =
        http
            .method(method)
            .uri(uri)
            .headers { h ->
                h.setBasicAuth(connection.username, connection.password, Charsets.UTF_8)
                h.set("Content-Type", body?.contentType ?: MediaType.APPLICATION_JSON_VALUE)
                headers.forEach { (name, value) -> h.set(name, value) }
            }.apply { body?.let { body(it.bytes) } }
            .exchange { _, response ->
                Reply(response.statusCode.value(), response.body.readNBytes(MAX_BODY_BYTES))
            }

    @Suppress("LongParameterList") // os campos da FnrhCall, um por argumento
    private fun record(
        connection: FnrhConnection,
        operation: String,
        outcome: String,
        status: Int?,
        startedNanos: Long,
        correlationRef: String?,
        remoteId: String?,
    ) = integrationLog.record(
        FnrhCall(
            operation = operation,
            environment = connection.environment,
            outcome = outcome,
            httpStatus = status,
            durationMs = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI,
            correlationRef = correlationRef,
            remoteId = remoteId,
        ),
    )

    private companion object {
        val log: Logger = LoggerFactory.getLogger(SerproFnrhGateway::class.java)
        const val SUCCESS = "SUCCESS"
        const val REQUESTER_HEADER = "cpf_solicitante"
        const val MAX_BODY_BYTES = 1 shl 20
        const val NANOS_PER_MILLI = 1_000_000L
        val HTTP_OK_RANGE = 200..299
    }
}
