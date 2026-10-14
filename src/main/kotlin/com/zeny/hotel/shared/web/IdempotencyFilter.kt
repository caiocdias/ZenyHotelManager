package com.zeny.hotel.shared.web

import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.IDEMPOTENCY_KEY_HEADER
import com.zeny.hotel.shared.IDEMPOTENT_REPLAYED_HEADER
import com.zeny.hotel.shared.Idempotent
import com.zeny.hotel.shared.application.idempotency.IdempotencyDecision
import com.zeny.hotel.shared.application.idempotency.IdempotencyReservation
import com.zeny.hotel.shared.application.idempotency.IdempotencyService
import com.zeny.hotel.shared.application.idempotency.IdempotentRequest
import com.zeny.hotel.shared.application.idempotency.StoredResponse
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.method.HandlerMethod
import org.springframework.web.util.ContentCachingResponseWrapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Collections
import java.util.Enumeration
import java.util.HexFormat

/**
 * `Idempotency-Key` nos endpoints marcados com [Idempotent] (ARCHITECTURE §7). Roda depois do
 * Spring Security (a recusa por autenticação vem antes) e antes do Spring MVC, para guardar a
 * resposta inteira, inclusive os problemas montados pelo `ApiExceptionHandler`.
 *
 * Fluxo de uma requisição a um endpoint marcado:
 * 1. confere a chave (`400 IDEMPOTENCY_KEY_REQUIRED`/`IDEMPOTENCY_KEY_INVALID`) e o usuário
 *    autenticado ([CurrentActor]), cujo id estável é o escopo da chave (`user:<id>`): usuários
 *    diferentes nunca compartilham resposta, e trocar o login não muda o escopo;
 * 2. lê o corpo (até `zeny.idempotency.max-request-body-size`; acima, `413`) e calcula o hash
 *    de método, caminho, query, `If-Match` e corpo. Corpo de formulário
 *    (`application/x-www-form-urlencoded`) ou multipart é recusado com `415`: lido aqui, ele
 *    não chegaria ao MVC como parâmetros (o endpoint marcado recebe JSON);
 * 3. reserva a chave no banco ([IdempotencyService.begin]): chave em uso por outra requisição
 *    → `409 IDEMPOTENCY_KEY_IN_USE`; usada com outra requisição → `422 IDEMPOTENCY_KEY_REUSED`;
 *    já concluída → devolve a resposta guardada com `Idempotent-Replayed: true`;
 * 4. com a reserva, executa o handler com a resposta em buffer e guarda status, cabeçalhos
 *    relevantes e corpo, **qualquer que seja o status**: erro se repete como erro.
 *
 * Se uma exceção escapa do Spring MVC: recusa de segurança (o `ApiExceptionHandler` relança
 * `AccessDeniedException`/`AuthenticationException`) libera a chave, porque nada executou;
 * qualquer outra guarda um `500 INTERNAL_ERROR`, porque o efeito pode ter acontecido.
 */
internal class IdempotencyFilter(
    private val idempotency: IdempotencyService,
    private val currentActor: CurrentActor,
    /** O handler que atenderia a requisição (no Spring MVC, o do `RequestMappingHandlerMapping`). */
    private val handlerOf: (HttpServletRequest) -> Any?,
    private val problems: ProblemWriter,
    private val maxRequestBodySize: Int,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        if (isIdempotent(request)) filterIdempotent(request, response, chain) else chain.doFilter(request, response)
    }

    private fun filterIdempotent(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val key = request.getHeader(IDEMPOTENCY_KEY_HEADER)
        val scope = scopeOfCurrentUser()
        when {
            key.isNullOrBlank() -> {
                refuse(request, response, HttpStatus.BAD_REQUEST, ErrorCodes.IDEMPOTENCY_KEY_REQUIRED)
            }

            !VALID_KEY.matches(key) -> {
                refuse(request, response, HttpStatus.BAD_REQUEST, ErrorCodes.IDEMPOTENCY_KEY_INVALID)
            }

            // A segurança já barra anônimos em /api/**; um endpoint público marcado não tem escopo.
            scope == null -> {
                refuse(request, response, HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHENTICATED)
            }

            // O corpo é lido aqui antes do MVC; parâmetros de formulário ou multipart se perderiam.
            hasFormBody(request) -> {
                refuse(request, response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCodes.UNSUPPORTED_MEDIA_TYPE)
            }

            else -> {
                val body = readBody(request)
                if (body == null) {
                    refuse(request, response, HttpStatus.CONTENT_TOO_LARGE, ErrorCodes.PAYLOAD_TOO_LARGE)
                } else {
                    val idempotentRequest = IdempotentRequest(scope, key, RequestHashes.of(request, body))
                    decide(request, response, chain, body, idempotency.begin(idempotentRequest))
                }
            }
        }
    }

    private fun decide(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
        body: ByteArray,
        decision: IdempotencyDecision,
    ) {
        when (decision) {
            is IdempotencyDecision.Proceed -> {
                execute(request, response, chain, body, decision.reservation)
            }

            is IdempotencyDecision.Replay -> {
                replay(response, decision.response)
            }

            IdempotencyDecision.InProgress -> {
                refuse(request, response, HttpStatus.CONFLICT, ErrorCodes.IDEMPOTENCY_KEY_IN_USE)
            }

            IdempotencyDecision.KeyReused -> {
                refuse(request, response, HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.IDEMPOTENCY_KEY_REUSED)
            }
        }
    }

    private fun refuse(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: HttpStatus,
        code: ErrorCode,
    ) = problems.write(request, response, Problems.of(status, code))

    @Suppress("TooGenericExceptionCaught") // registra o desfecho de qualquer falha e a relança intacta
    private fun execute(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
        body: ByteArray,
        reservation: IdempotencyReservation,
    ) {
        val buffered = ContentCachingResponseWrapper(response)
        try {
            chain.doFilter(CachedBodyRequest(request, body), buffered)
        } catch (failure: Throwable) {
            settleAfterFailure(request, reservation, failure)
            throw failure
        }
        check(!request.isAsyncStarted) { "Endpoint @Idempotent precisa ser síncrono: ${request.requestURI}" }
        store(reservation, StoredResponse(buffered.status, storedHeadersOf(buffered), buffered.contentAsByteArray))
        buffered.copyBodyToResponse()
    }

    @Suppress("TooGenericExceptionCaught") // a falha original é a que importa; esta só vai para o log
    private fun settleAfterFailure(
        request: HttpServletRequest,
        reservation: IdempotencyReservation,
        failure: Throwable,
    ) {
        try {
            if (isSecurityRefusal(failure)) {
                idempotency.release(reservation)
            } else {
                val problem = Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR)
                val headers = mapOf(HttpHeaders.CONTENT_TYPE to listOf(MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                store(reservation, StoredResponse(problem.status, headers, problems.toBytes(request, problem)))
            }
        } catch (ex: Exception) {
            log.error("Falha ao registrar o desfecho da chave de idempotência {}", reservation.id, ex)
        }
    }

    @Suppress("TooGenericExceptionCaught") // a resposta já existe; perder o registro não pode derrubá-la
    private fun store(
        reservation: IdempotencyReservation,
        response: StoredResponse,
    ) {
        try {
            if (!idempotency.complete(reservation, response)) {
                log.warn("Reserva de idempotência {} não existe mais; resposta não guardada", reservation.id)
            }
        } catch (ex: Exception) {
            // A chave fica IN_PROGRESS (409) até vencer: mais seguro que deixar o efeito repetir.
            log.error("Falha ao guardar a resposta da chave de idempotência {}", reservation.id, ex)
        }
    }

    private fun replay(
        response: HttpServletResponse,
        stored: StoredResponse,
    ) {
        response.status = stored.status
        stored.headers.forEach { (name, values) ->
            if (name.equals(HttpHeaders.CONTENT_TYPE, ignoreCase = true)) {
                response.contentType = values.firstOrNull()
            } else {
                values.forEach { response.addHeader(name, it) }
            }
        }
        response.setHeader(IDEMPOTENT_REPLAYED_HEADER, "true")
        response.setContentLength(stored.body.size)
        response.outputStream.write(stored.body)
    }

    @Suppress("TooGenericExceptionCaught") // qualquer falha na busca: não é idempotente, o MVC responde
    private fun isIdempotent(request: HttpServletRequest): Boolean {
        val handler =
            try {
                // Numa cópia dos atributos: a busca do handler não pode deixar rastro na requisição.
                handlerOf(AttributeIsolatingRequest(request))
            } catch (ex: Exception) {
                // Método não suportado, mídia não aceita etc.: o DispatcherServlet responde o erro.
                log.trace("Sem handler para idempotência em {}: {}", request.requestURI, ex.toString())
                null
            }
        return (handler as? HandlerMethod)?.hasMethodAnnotation(Idempotent::class.java) == true
    }

    /** `user:<id do usuário>`; sem usuário autenticado por token, não há escopo. */
    private fun scopeOfCurrentUser(): String? = currentActor.currentOrNull()?.let { "user:${it.userId}" }

    /** O corpo inteiro, ou `null` se passar do limite. */
    private fun readBody(request: HttpServletRequest): ByteArray? {
        if (request.contentLengthLong > maxRequestBodySize) return null
        val body = request.inputStream.readNBytes(maxRequestBodySize + 1)
        return body.takeIf { it.size <= maxRequestBodySize }
    }

    private companion object {
        /** 1 a 255 caracteres ASCII visíveis (sem espaço nem controle); um UUID é o recomendado. */
        val VALID_KEY = Regex("[\\x21-\\x7E]{1,255}")
    }
}

/**
 * Cabeçalhos da resposta guardados e repetidos, além do `Content-Type`. Os de segurança e
 * cache são escritos de novo pelo Spring Security na repetição; `Set-Cookie` nunca é guardado.
 */
private val STORED_HEADERS =
    listOf(
        HttpHeaders.LOCATION,
        HttpHeaders.ETAG,
        HttpHeaders.LAST_MODIFIED,
        HttpHeaders.CONTENT_LANGUAGE,
        HttpHeaders.CONTENT_DISPOSITION,
        HttpHeaders.RETRY_AFTER,
        HttpHeaders.LINK,
    )

private fun storedHeadersOf(response: ContentCachingResponseWrapper): Map<String, List<String>> {
    val headers = linkedMapOf<String, List<String>>()
    response.contentType?.let { headers[HttpHeaders.CONTENT_TYPE] = listOf(it) }
    STORED_HEADERS.forEach { name ->
        val values = response.getHeaders(name).toList()
        if (values.isNotEmpty()) headers[name] = values
    }
    return headers
}

/** Corpo de formulário ou multipart, que o servlet entregaria como parâmetros, não como JSON. */
private fun hasFormBody(request: HttpServletRequest): Boolean {
    val contentType =
        request.contentType?.let { runCatching { MediaType.parseMediaType(it) }.getOrNull() } ?: return false
    return MediaType.APPLICATION_FORM_URLENCODED.includes(contentType) || contentType.type == "multipart"
}

/** Recusa de autenticação ou autorização em algum ponto da cadeia de causas. */
private fun isSecurityRefusal(failure: Throwable): Boolean =
    generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }
        .any { it is AccessDeniedException || it is AuthenticationException }

/** SHA-256 em hexadecimal. */
private object RequestHashes {
    /** Identifica a requisição: método, caminho, query, `If-Match` e corpo, cada parte com o tamanho na frente. */
    fun of(
        request: HttpServletRequest,
        body: ByteArray,
    ): String {
        val digest = MessageDigest.getInstance(SHA_256)
        listOf(
            request.method,
            request.requestURI,
            request.queryString,
            request.getHeaders(HttpHeaders.IF_MATCH)?.toList()?.joinToString(","),
        ).forEach { part ->
            val bytes = part?.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes?.size ?: -1).array())
            bytes?.let(digest::update)
        }
        digest.update(body)
        return HexFormat.of().formatHex(digest.digest())
    }

    private const val SHA_256 = "SHA-256"
}

/** Requisição com o corpo já lido, que o Spring MVC pode ler de novo. */
private class CachedBodyRequest(
    request: HttpServletRequest,
    private val body: ByteArray,
) : HttpServletRequestWrapper(request) {
    override fun getInputStream(): ServletInputStream = ByteArrayServletInputStream(body)

    override fun getReader(): BufferedReader {
        val charset = characterEncoding?.let(Charset::forName) ?: Charsets.UTF_8
        return BufferedReader(InputStreamReader(ByteArrayInputStream(body), charset))
    }

    override fun getContentLength(): Int = body.size

    override fun getContentLengthLong(): Long = body.size.toLong()
}

private class ByteArrayServletInputStream(
    body: ByteArray,
) : ServletInputStream() {
    private val input = ByteArrayInputStream(body)

    override fun read(): Int = input.read()

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int = input.read(b, off, len)

    override fun isFinished(): Boolean = input.available() == 0

    override fun isReady(): Boolean = true

    override fun setReadListener(listener: ReadListener) = throw UnsupportedOperationException("leitura síncrona")
}

/**
 * Requisição com uma cópia própria dos atributos, para buscar o handler sem alterar os da
 * requisição verdadeira (o mesmo cuidado do `HandlerMappingIntrospector` do Spring).
 */
private class AttributeIsolatingRequest(
    request: HttpServletRequest,
) : HttpServletRequestWrapper(request) {
    private val attributes: MutableMap<String, Any> =
        request.attributeNames.toList().associateWithTo(HashMap()) { request.getAttribute(it) }

    override fun getAttribute(name: String): Any? = attributes[name]

    override fun getAttributeNames(): Enumeration<String> = Collections.enumeration(attributes.keys)

    override fun setAttribute(
        name: String,
        value: Any?,
    ) {
        if (value == null) attributes.remove(name) else attributes[name] = value
    }

    override fun removeAttribute(name: String) {
        attributes.remove(name)
    }
}
