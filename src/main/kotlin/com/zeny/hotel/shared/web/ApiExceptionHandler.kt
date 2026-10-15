package com.zeny.hotel.shared.web

import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.FieldViolation
import jakarta.persistence.OptimisticLockException
import org.slf4j.LoggerFactory
import org.springframework.beans.TypeMismatchException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.ServletRequestBindingException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Converte toda exceção que sai de um controller num problema RFC 9457 com `code` estável
 * (ARCHITECTURE §7, RNF-13):
 *
 * - [DomainException]: o status da família, o `code` e a mensagem da exceção;
 * - erros de validação e de leitura da requisição: `400 VALIDATION_FAILED` com `errors[]`
 *   por campo, ou `400 MALFORMED_REQUEST` quando o corpo nem chega a ser lido;
 * - conflito de versão no banco (lock otimista): `412 PRECONDITION_FAILED`;
 * - exceções do Spring MVC: o status do Spring, com `code`, título e detalhe padronizados;
 * - qualquer outra: `500 INTERNAL_ERROR` genérico. A causa vai só para o log, nunca para a
 *   resposta (sem stack trace nem mensagem interna).
 */
@RestControllerAdvice
@Suppress("TooManyFunctions") // um método por família de exceção, como no ResponseEntityExceptionHandler
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(DomainException::class)
    fun handleDomain(
        ex: DomainException,
        request: WebRequest,
    ): ResponseEntity<Any> {
        val status = Problems.statusOf(ex.kind)
        log.debug("Erro esperado {} ({}): {}", ex.code, status.value(), ex.message)
        val problem = Problems.of(status, ex.code, ex.message, ex.violations)
        Problems.addProperties(problem, ex.problemProperties)
        return problem(problem, request)
    }

    @ExceptionHandler(OptimisticLockingFailureException::class, OptimisticLockException::class)
    fun handleOptimisticLock(
        ex: Exception,
        request: WebRequest,
    ): ResponseEntity<Any> {
        log.debug("Conflito de versão no banco: {}", ex.toString())
        return problem(Problems.of(HttpStatus.PRECONDITION_FAILED, ErrorCodes.PRECONDITION_FAILED), request)
    }

    /**
     * Falhas de autenticação e autorização do Spring Security lançadas dentro do controller
     * (segurança por método, a partir do M1) seguem para o `ExceptionTranslationFilter`, que
     * responde 401 ou 403 pelos pontos de entrada da [SecurityConfiguration].
     */
    @ExceptionHandler(AccessDeniedException::class, AuthenticationException::class)
    fun rethrowSecurity(ex: RuntimeException): Nothing = throw ex

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(
        ex: Exception,
        request: WebRequest,
    ): ResponseEntity<Any> {
        log.error("Erro inesperado em {}", describe(request), ex)
        return problem(Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR), request)
    }

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? = validationProblem(ex.bindingResult.allErrors.map(FieldViolations::of), headers, request)

    override fun handleHandlerMethodValidationException(
        ex: HandlerMethodValidationException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? = validationProblem(FieldViolations.of(ex), headers, request)

    override fun handleTypeMismatch(
        ex: TypeMismatchException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val field = (ex as? MethodArgumentTypeMismatchException)?.name ?: ex.propertyName ?: "?"
        return validationProblem(listOf(FieldViolations.invalidFormat(field)), headers, request)
    }

    override fun handleMissingServletRequestParameter(
        ex: MissingServletRequestParameterException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? = validationProblem(listOf(FieldViolations.required(ex.parameterName)), headers, request)

    override fun handleMissingServletRequestPart(
        ex: MissingServletRequestPartException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? = validationProblem(listOf(FieldViolations.required(ex.requestPartName)), headers, request)

    override fun handleServletRequestBindingException(
        ex: ServletRequestBindingException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? =
        if (ex is MissingRequestHeaderException) {
            validationProblem(listOf(FieldViolations.required(ex.headerName)), headers, request)
        } else {
            super.handleServletRequestBindingException(ex, headers, status, request)
        }

    /**
     * Corpo que não vira o DTO. Se o Jackson aponta o campo (tipo errado, valor recusado pelo
     * construtor de um tipo de valor como `Money`, obrigatório ausente), é erro de validação
     * daquele campo; se nem o JSON é legível, é `MALFORMED_REQUEST`.
     */
    override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val violation = FieldViolations.of(ex)
        if (violation != null) return validationProblem(listOf(violation), headers, request)
        log.debug("Corpo ilegível: {}", ex.mostSpecificCause.toString())
        return problem(Problems.of(HttpStatus.BAD_REQUEST, ErrorCodes.MALFORMED_REQUEST), request, headers)
    }

    /** Ponto por onde passam as respostas montadas pelo Spring: completa o problema com as convenções. */
    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        if (statusCode.is5xxServerError) {
            log.error("Erro {} em {}", statusCode.value(), describe(request), ex)
        } else {
            log.debug("Requisição recusada com {}: {}", statusCode.value(), ex.toString())
        }
        val problem = Problems.normalize(body as? ProblemDetail ?: ProblemDetail.forStatus(statusCode))
        return super.handleExceptionInternal(ex, problem, headers, statusCode, request)
    }

    private fun validationProblem(
        violations: List<FieldViolation>,
        headers: HttpHeaders,
        request: WebRequest,
    ): ResponseEntity<Any> {
        log.debug("Requisição com campos inválidos: {}", violations)
        val problem = Problems.of(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, violations = violations)
        return problem(problem, request, headers)
    }

    private fun problem(
        problem: ProblemDetail,
        request: WebRequest,
        headers: HttpHeaders = HttpHeaders(),
    ): ResponseEntity<Any> {
        problem.instance = problem.instance ?: Problems.instanceOf((request as? ServletWebRequest)?.request?.requestURI)
        return ResponseEntity.status(problem.status).headers(headers).body(problem)
    }

    private fun describe(request: WebRequest): String =
        (request as? ServletWebRequest)?.request?.let { "${it.method} ${it.requestURI}" }
            ?: request.getDescription(false)
}
