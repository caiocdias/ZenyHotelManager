package com.zeny.hotel.fnrh.infrastructure

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.zeny.hotel.fnrh.application.FnrhFailure
import com.zeny.hotel.fnrh.domain.FnrhGuest
import com.zeny.hotel.fnrh.domain.FnrhGuestStatus
import com.zeny.hotel.fnrh.domain.FnrhPersonData
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.fnrh.domain.FnrhReservationData
import org.springframework.http.MediaType
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.util.UriUtils
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.ConnectException
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// O que o adaptador do Serpro usa para falar o idioma da API v2.4: o corpo de cada requisição (com os nomes do
// Serpro), a URL, o instante, a leitura da resposta e a classificação dos erros. Nada daqui é a API da porta.

/** O corpo de uma requisição e o seu `Content-Type`. */
internal class Payload(
    val contentType: String,
    val bytes: ByteArray,
)

private val wireMapper: JsonMapper = JsonMapper.builder().build()

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CLIENT_ERROR = 400
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_CLIENT_ERROR_END = 499
private const val CAUSE_DEPTH = 10

private val INSTANT_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** O instante em UTC com milissegundos e `Z` (`2026-02-10T15:00:00.000Z`), como a API v2.4 o pede. */
internal fun formatInstant(instant: Instant): String = INSTANT_FORMAT.format(instant.truncatedTo(ChronoUnit.MILLIS))

internal fun jsonPayload(body: Any): Payload =
    Payload(MediaType.APPLICATION_JSON_VALUE, wireMapper.writeValueAsBytes(body))

internal fun textPayload(text: String): Payload = Payload(MediaType.TEXT_PLAIN_VALUE, text.toByteArray(Charsets.UTF_8))

/** O JSON da resposta, ou `null` se o corpo está vazio ou não é JSON. */
internal fun readTree(bytes: ByteArray): JsonNode? =
    if (bytes.isEmpty()) {
        null
    } else {
        try {
            wireMapper.readTree(bytes)
        } catch (_: JacksonException) {
            null
        }
    }

internal fun JsonNode.text(field: String): String? =
    get(field)?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }

/** A `mensagem` do erro do Serpro, saneada; o corpo bruto nunca é usado. */
internal fun errorMessage(
    tree: JsonNode?,
    sentValues: Collection<String>,
): String? = tree?.text("mensagem")?.let { FnrhMessageSanitizer.sanitize(it, sentValues) }

/**
 * A URL da chamada: [base] mais os [segments] (codificados) e a [query]; só `http` e `https` (outro esquema é
 * requisição malformada, que nunca sai). Nunca vai a log: pode ter documento.
 */
internal fun requestUri(
    base: URI,
    segments: List<String>,
    query: List<Pair<String, String>>,
): URI {
    val path = segments.joinToString("") { "/" + encodeSegment(it) }
    val queryString =
        query
            .takeIf { it.isNotEmpty() }
            ?.joinToString("&", prefix = "?") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
            .orEmpty()
    return URI.create(base.toString().trimEnd('/') + path + queryString).also {
        require(it.scheme.equals("http", true) || it.scheme.equals("https", true)) { "esquema não suportado" }
    }
}

/** Codifica um segmento do caminho; `.` e `..` viram `%2E`, para um id remoto nunca navegar no caminho. */
private fun encodeSegment(segment: String): String =
    if (segment == "." ||
        segment == ".."
    ) {
        segment.replace(".", "%2E")
    } else {
        UriUtils.encodePathSegment(segment, Charsets.UTF_8)
    }

/**
 * A falha de um envio que não completou. `UNKNOWN_OUTCOME` só quando foi um erro de I/O que não é de conexão numa
 * escrita (a requisição pode ter chegado, como o timeout de leitura); erro de programação ou requisição malformada
 * (que não é de I/O) nunca saiu e é `UNAVAILABLE`.
 */
internal fun sendFailure(
    e: RuntimeException,
    write: Boolean,
): FnrhFailure =
    if (write && e is ResourceAccessException && !neverSent(e)) FnrhFailure.UNKNOWN_OUTCOME else FnrhFailure.UNAVAILABLE

/** O mapeamento do status HTTP de erro para a falha da porta. */
internal fun failureOf(status: Int): FnrhFailure =
    when (status) {
        HTTP_UNAUTHORIZED -> FnrhFailure.UNAUTHORIZED
        HTTP_FORBIDDEN -> FnrhFailure.FORBIDDEN
        HTTP_NOT_FOUND -> FnrhFailure.NOT_FOUND
        HTTP_TOO_MANY_REQUESTS -> FnrhFailure.SERVER_ERROR
        in HTTP_CLIENT_ERROR..HTTP_CLIENT_ERROR_END -> FnrhFailure.VALIDATION
        else -> FnrhFailure.SERVER_ERROR
    }

/** A falha foi ao conectar (recusada, esgotada ou sem resolver o host): a requisição não saiu. */
internal fun neverSent(e: Throwable): Boolean =
    generateSequence(e) { it.cause?.takeIf { cause -> cause !== it } }.take(CAUSE_DEPTH).any {
        it is HttpConnectTimeoutException || it is ConnectException || it is UnknownHostException
    }

// ---- os corpos, com os nomes do Serpro

@JsonInclude(JsonInclude.Include.NON_NULL)
@Suppress("LongParameterList") // espelha o corpo da API
internal class ReservationBody(
    @get:JsonProperty("numero_reserva") val code: String,
    @get:JsonProperty("numero_reserva_ota") val otaCode: String?,
    @get:JsonProperty("data_entrada") val arrival: String,
    @get:JsonProperty("data_saida") val departure: String,
    @get:JsonProperty("quantidade_hospede_adulto") val adults: Int,
    @get:JsonProperty("quantidade_hospede_menor") val minors: Int,
    @get:JsonProperty("origem_reserva_id") val origin: String?,
) {
    companion object {
        fun of(
            data: FnrhReservationData,
            withOrigin: Boolean,
        ) = ReservationBody(
            code = data.reservationCode,
            otaCode = data.otaCode,
            arrival = data.arrival.toString(),
            departure = data.departure.toString(),
            adults = data.adults,
            minors = data.minors,
            origin = if (withOrigin) (if (data.otaCode != null) "OTA" else "MEIOHOSPEDAGEM") else null,
        )
    }
}

internal class PersonEnvelope(
    @get:JsonProperty("pessoa") val person: PersonBody,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@Suppress("LongParameterList") // espelha o corpo da API
internal class PersonBody(
    @get:JsonProperty("nome") val name: String,
    @get:JsonProperty("nome_social") val socialName: String?,
    @get:JsonProperty("PaisNacionalidade_id") val nationality: String?,
    @get:JsonProperty("genero_id") val gender: String?,
    @get:JsonProperty("GeneroDescricao") val genderDescription: String?,
    @get:JsonProperty("data_nascimento") val birthDate: String?,
    @get:JsonProperty("raca_id") val race: String?,
    @get:JsonProperty("deficiencia_id") val disabilityOption: String?,
    @get:JsonProperty("tipo_deficiencia_id") val disabilityType: String?,
    @get:JsonProperty("documento_id") val document: DocumentBody?,
    @get:JsonProperty("contato") val contact: ContactBody?,
) {
    companion object {
        fun of(p: FnrhPersonData) =
            PersonBody(
                name = p.name,
                socialName = p.socialName,
                nationality = p.nationality,
                gender = p.gender,
                genderDescription = p.genderDescription,
                birthDate = p.birthDate?.toString(),
                race = p.race,
                disabilityOption = p.disabilityOption,
                disabilityType = p.disabilityType,
                document = p.document?.let { DocumentBody(it.number, documentTypeId(it.type)) },
                contact = ContactBody.of(p),
            )
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
internal class DocumentBody(
    @get:JsonProperty("numero_documento") val number: String,
    @get:JsonProperty("tipo_documento_id") val type: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@Suppress("LongParameterList") // espelha o corpo da API
internal class ContactBody(
    @get:JsonProperty("email") val email: String?,
    @get:JsonProperty("telefone") val phone: String?,
    @get:JsonProperty("PaisResidencia_id") val residenceCountry: String?,
    @get:JsonProperty("cep") val postalCode: String?,
    @get:JsonProperty("logradouro") val street: String?,
    @get:JsonProperty("numero") val number: String?,
    @get:JsonProperty("complemento") val complement: String?,
    @get:JsonProperty("bairro") val neighborhood: String?,
    @get:JsonProperty("cidade_id") val cityIbge: Int?,
    @get:JsonProperty("estado_id") val state: String?,
) {
    companion object {
        fun of(p: FnrhPersonData): ContactBody? {
            val all =
                listOf(
                    p.email,
                    p.phone,
                    p.residenceCountry,
                    p.postalCode,
                    p.street,
                    p.number,
                    p.complement,
                    p.neighborhood,
                    p.cityIbge,
                    p.state,
                )
            if (all.all { it == null }) return null
            return ContactBody(
                p.email,
                p.phone,
                p.residenceCountry,
                p.postalCode,
                p.street,
                p.number,
                p.complement,
                p.neighborhood,
                p.cityIbge,
                p.state,
            )
        }
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
internal class GuestBody(
    @get:JsonProperty("pessoa_id") val personId: String,
    @get:JsonProperty("is_principal") val principal: Boolean,
    @get:JsonProperty("situacao_hospede_id") val status: String,
    @get:JsonProperty("checkin_em") val checkedInAt: String,
    @get:JsonProperty("fnrh") val form: FormBody?,
    @get:JsonProperty("responsavel_id") val responsibleId: String?,
) {
    companion object {
        fun of(
            personId: FnrhRemoteId,
            guest: FnrhGuest,
        ) = GuestBody(
            personId = personId.value,
            principal = guest.principal,
            status = FnrhGuestStatus.CHECKIN_REALIZADO.name,
            checkedInAt = formatInstant(guest.checkedInAt),
            form =
                if (guest.travelReason == null && guest.transportMode == null) {
                    null
                } else {
                    FormBody(guest.travelReason, guest.transportMode)
                },
            responsibleId = guest.responsibleGuestId?.value,
        )
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
internal class FormBody(
    @get:JsonProperty("motivo_viagem_id") val travelReason: String?,
    @get:JsonProperty("meio_transporte_id") val transportMode: String?,
)
