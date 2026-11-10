package com.zeny.hotel.fnrh.infrastructure

import com.zeny.hotel.fnrh.application.FnrhCall
import com.zeny.hotel.fnrh.application.FnrhConnection
import com.zeny.hotel.fnrh.application.FnrhConnectionSource
import com.zeny.hotel.fnrh.application.FnrhFailure
import com.zeny.hotel.fnrh.application.FnrhGateway
import com.zeny.hotel.fnrh.application.FnrhGatewayException
import com.zeny.hotel.fnrh.application.FnrhIntegrationLog
import com.zeny.hotel.fnrh.application.FnrhRemoteGuestRef
import com.zeny.hotel.fnrh.application.FnrhReservationRegistered
import com.zeny.hotel.fnrh.domain.FnrhDocument
import com.zeny.hotel.fnrh.domain.FnrhDomain
import com.zeny.hotel.fnrh.domain.FnrhDomainValue
import com.zeny.hotel.fnrh.domain.FnrhGuest
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
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpClient
import java.time.Instant

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
        ) { tree -> tree?.let { reservationIdsFor(it, reservationCode) } }
            .firstOrNull()
            ?.let(::FnrhRemoteId)

    override fun listReservationGuests(
        reservationId: FnrhRemoteId,
        correlationRef: String,
    ): List<FnrhRemoteGuestRef> =
        call(
            operation = "listReservationGuests",
            correlationRef = correlationRef,
            method = HttpMethod.GET,
            segments = listOf("reservas", reservationId.value, "hospedes"),
            knownRemoteId = reservationId.value,
        ) { tree -> tree?.let(::guestRefsOf) }

    override fun findPersonByDocument(
        document: FnrhDocument,
        requesterCpf: Cpf,
        correlationRef: String,
    ): FnrhRemoteId? =
        try {
            call(
                operation = "findPersonByDocument",
                correlationRef = correlationRef,
                method = HttpMethod.GET,
                segments = listOf("pessoas", "documento", documentTypeId(document.type), document.number),
                headers = mapOf(REQUESTER_HEADER to requesterCpf.digits),
                sentValues = listOf(document.number, requesterCpf.digits),
                remoteIdOf = { it.firstOrNull() },
            ) { tree -> tree?.let(::personIdsOf) }.firstOrNull()?.let(::FnrhRemoteId)
        } catch (e: FnrhGatewayException) {
            // 404: a pessoa não existe no MTur, o mesmo que "sem id". A exceção não leva causa nem a URL.
            if (e.failure == FnrhFailure.NOT_FOUND) null else throw e
        }

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
                ) + personSensitiveValues(person),
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

    /**
     * Faz uma chamada: [parse] extrai o resultado do JSON da resposta (`null` se ele não traz o que devia), e
     * [remoteIdOf] ou [knownRemoteId] dão a referência remota que vai ao registro.
     */
    @Suppress("LongParameterList", "ThrowsCount")
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
        val recorder = CallRecorder(log, integrationLog, connection, operation, correlationRef)

        fun fail(
            failure: FnrhFailure,
            status: Int?,
            message: String?,
        ): Nothing {
            recorder.record(failure.name, status, knownRemoteId, message)
            throw FnrhGatewayException(failure, status, message)
        }

        // RFC 7617: o user-id do Basic não tem ':'; a configuração já o barra, isto cobre um valor antigo.
        if (':' in connection.username) fail(FnrhFailure.UNAUTHORIZED, null, null)
        // Montar a URL é antes do envio: se falhar, nada saiu (UNAVAILABLE), nunca UNKNOWN_OUTCOME.
        val uri =
            uriOrNull(log, operation, connection.baseUrl, segments, query) ?: fail(FnrhFailure.UNAVAILABLE, null, null)
        val reply =
            when (val sent = trySend(log, operation) { http.sendRequest(connection, method, uri, body, headers) }) {
                is Sent.Done -> sent.reply
                is Sent.Failed -> fail(sendFailure(sent.error, write), null, null)
            }
        val tree = readTree(reply.body)
        if (reply.status !in HTTP_OK_RANGE) {
            fail(failureOf(reply.status), reply.status, errorMessage(tree, sentValues))
        }
        val result =
            try {
                parse(tree)
            } catch (_: AmbiguousMatchException) {
                fail(FnrhFailure.UNKNOWN_OUTCOME, reply.status, null)
            } ?: fail(if (write) FnrhFailure.UNKNOWN_OUTCOME else FnrhFailure.SERVER_ERROR, reply.status, null)
        recorder.record(SUCCESS, reply.status, remoteIdOf(result) ?: knownRemoteId, null)
        return result
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(SerproFnrhGateway::class.java)
        const val SUCCESS = "SUCCESS"
        const val REQUESTER_HEADER = "cpf_solicitante"
        val HTTP_OK_RANGE = 200..299
    }
}
