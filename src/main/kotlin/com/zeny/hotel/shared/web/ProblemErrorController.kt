package com.zeny.hotel.shared.web

import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Substitui o `BasicErrorController` do Spring Boot: os erros que o servlet encaminha para
 * `/error` (exceção fora de um controller, erro do Tomcat) também saem como problema RFC 9457
 * com `code` estável, sem mensagem interna nem stack trace. Só atende o encaminhamento de
 * erro: a [SecurityConfiguration] nega o acesso direto a `/error`.
 */
@Hidden
@RestController
@RequestMapping("\${server.error.path:/error}")
class ProblemErrorController : ErrorController {
    @RequestMapping
    fun error(request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val status =
            (request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int)
                ?.let(HttpStatusCode::valueOf)
                ?.takeIf { it.isError }
                ?: HttpStatus.NOT_FOUND
        val problem = Problems.forStatus(status)
        problem.instance = Problems.instanceOf(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) as? String)
        return ResponseEntity.status(status).body(problem)
    }
}
