package com.zeny.hotel.shared.web

import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.ErrorKind
import com.zeny.hotel.shared.FieldViolation
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import java.net.URI
import java.net.URISyntaxException

/**
 * Montagem dos problemas RFC 9457 da API (ARCHITECTURE §7): `type`, `title` (frase do status
 * em pt-BR), `status`, `detail`, `instance`, o `code` estável e, nos erros de validação,
 * `errors[]` por campo. Todo erro que sai da aplicação passa por aqui: o handler de exceções,
 * o controller de erro do servlet e os pontos de entrada do Spring Security.
 */
internal object Problems {
    const val CODE = "code"
    const val ERRORS = "errors"

    fun of(
        status: HttpStatusCode,
        code: ErrorCode,
        detail: String = defaultDetail(code),
        violations: List<FieldViolation> = emptyList(),
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            title = titleOf(status)
            setProperty(CODE, code.value)
            if (violations.isNotEmpty()) setProperty(ERRORS, violations)
        }

    /** O problema genérico de um status, para erros que não vieram de uma exceção conhecida. */
    fun forStatus(status: HttpStatusCode): ProblemDetail = of(status, codeOf(status))

    /**
     * Garante as convenções num problema montado pelo Spring (título e detalhe em pt-BR e o
     * `code` do status). Problemas que já têm `code` ficam como estão.
     */
    fun normalize(problem: ProblemDetail): ProblemDetail {
        if (problem.properties?.containsKey(CODE) == true) return problem
        val status = HttpStatusCode.valueOf(problem.status)
        val code = codeOf(status)
        problem.title = titleOf(status)
        problem.detail = defaultDetail(code)
        problem.setProperty(CODE, code.value)
        return problem
    }

    fun statusOf(kind: ErrorKind): HttpStatus =
        when (kind) {
            ErrorKind.INVALID_INPUT -> HttpStatus.BAD_REQUEST
            ErrorKind.UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED
            ErrorKind.NOT_FOUND -> HttpStatus.NOT_FOUND
            ErrorKind.CONFLICT -> HttpStatus.CONFLICT
            ErrorKind.RULE_VIOLATION -> HttpStatus.UNPROCESSABLE_CONTENT
            ErrorKind.PERMISSION_DENIED -> HttpStatus.FORBIDDEN
            ErrorKind.PRECONDITION_FAILED -> HttpStatus.PRECONDITION_FAILED
            ErrorKind.PRECONDITION_REQUIRED -> HttpStatus.PRECONDITION_REQUIRED
        }

    fun codeOf(status: HttpStatusCode): ErrorCode =
        CODES_BY_STATUS[status.value()]
            ?: if (status.is5xxServerError) ErrorCodes.INTERNAL_ERROR else ErrorCodes.REQUEST_REJECTED

    fun titleOf(status: HttpStatusCode): String =
        TITLES[status.value()] ?: if (status.is5xxServerError) "Erro no servidor" else "Requisição recusada"

    fun defaultDetail(code: ErrorCode): String = DETAILS[code] ?: "A requisição foi recusada."

    /** O caminho da requisição como `instance`, ou nada se não for uma URI válida. */
    fun instanceOf(path: String?): URI? =
        path?.let {
            try {
                URI(it)
            } catch (_: URISyntaxException) {
                null
            }
        }

    private val CODES_BY_STATUS: Map<Int, ErrorCode> =
        mapOf(
            400 to ErrorCodes.MALFORMED_REQUEST,
            401 to ErrorCodes.UNAUTHENTICATED,
            403 to ErrorCodes.ACCESS_DENIED,
            404 to ErrorCodes.NOT_FOUND,
            405 to ErrorCodes.METHOD_NOT_ALLOWED,
            406 to ErrorCodes.NOT_ACCEPTABLE,
            409 to ErrorCodes.CONFLICT,
            412 to ErrorCodes.PRECONDITION_FAILED,
            413 to ErrorCodes.PAYLOAD_TOO_LARGE,
            415 to ErrorCodes.UNSUPPORTED_MEDIA_TYPE,
            422 to ErrorCodes.BUSINESS_RULE_VIOLATED,
            428 to ErrorCodes.PRECONDITION_REQUIRED,
            500 to ErrorCodes.INTERNAL_ERROR,
            503 to ErrorCodes.SERVICE_UNAVAILABLE,
        )

    private val TITLES: Map<Int, String> =
        mapOf(
            400 to "Requisição inválida",
            401 to "Não autenticado",
            403 to "Acesso negado",
            404 to "Não encontrado",
            405 to "Método não permitido",
            406 to "Representação não aceitável",
            409 to "Conflito",
            412 to "Pré-condição falhou",
            413 to "Conteúdo grande demais",
            415 to "Tipo de conteúdo não suportado",
            422 to "Regra de negócio violada",
            428 to "Pré-condição obrigatória",
            500 to "Erro interno",
            503 to "Serviço indisponível",
        )

    private val DETAILS: Map<ErrorCode, String> =
        mapOf(
            ErrorCodes.VALIDATION_FAILED to "A requisição tem campos inválidos.",
            ErrorCodes.MALFORMED_REQUEST to "A requisição está malformada e não pôde ser lida.",
            ErrorCodes.INVALID_CURSOR to "Cursor de paginação inválido. Recomece da primeira página.",
            ErrorCodes.UNAUTHENTICATED to "Autentique-se para acessar este recurso.",
            ErrorCodes.ACCESS_DENIED to "Você não tem permissão para esta ação.",
            ErrorCodes.PASSWORD_CHANGE_REQUIRED to "Troque a sua senha antes de continuar.",
            ErrorCodes.NOT_FOUND to "Recurso não encontrado.",
            ErrorCodes.METHOD_NOT_ALLOWED to "Método HTTP não suportado neste recurso.",
            ErrorCodes.NOT_ACCEPTABLE to "Não há representação disponível no formato pedido.",
            ErrorCodes.CONFLICT to "A requisição conflita com o estado atual do recurso.",
            ErrorCodes.PRECONDITION_FAILED to
                "O recurso foi alterado por outra requisição. Leia de novo e refaça a alteração.",
            ErrorCodes.PAYLOAD_TOO_LARGE to "O conteúdo enviado é grande demais.",
            ErrorCodes.UNSUPPORTED_MEDIA_TYPE to "Tipo de conteúdo não suportado. Envie application/json.",
            ErrorCodes.BUSINESS_RULE_VIOLATED to "A requisição viola uma regra de negócio.",
            ErrorCodes.PRECONDITION_REQUIRED to "Envie o cabeçalho If-Match com a ETag da última leitura do recurso.",
            ErrorCodes.IDEMPOTENCY_KEY_REQUIRED to
                "Envie o cabeçalho Idempotency-Key com um identificador único da operação.",
            ErrorCodes.IDEMPOTENCY_KEY_INVALID to
                "O cabeçalho Idempotency-Key deve ter de 1 a 255 caracteres ASCII visíveis, sem espaços.",
            ErrorCodes.IDEMPOTENCY_KEY_IN_USE to
                "Uma requisição com esta Idempotency-Key ainda está em andamento. Aguarde e tente de novo.",
            ErrorCodes.IDEMPOTENCY_KEY_REUSED to
                "Esta Idempotency-Key já foi usada com outra requisição. Gere uma chave nova para cada operação.",
            ErrorCodes.INTERNAL_ERROR to "Erro interno. Tente de novo; se persistir, avise o suporte.",
            ErrorCodes.SERVICE_UNAVAILABLE to "Serviço temporariamente indisponível. Tente de novo em instantes.",
            ErrorCodes.REQUEST_REJECTED to "A requisição foi recusada.",
        )
}
