package com.zeny.hotel.shared.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import tools.jackson.databind.json.JsonMapper

/**
 * Escreve um problema RFC 9457 direto na resposta, para quem responde fora do Spring MVC: os
 * pontos de entrada do Spring Security e o filtro de idempotência.
 */
internal class ProblemWriter(
    private val jsonMapper: JsonMapper,
) {
    fun write(
        request: HttpServletRequest,
        response: HttpServletResponse,
        problem: ProblemDetail,
    ) {
        val body = toBytes(request, problem)
        response.status = problem.status
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.setContentLength(body.size)
        response.outputStream.write(body)
    }

    /** O problema serializado, com o caminho da requisição como `instance`. */
    fun toBytes(
        request: HttpServletRequest,
        problem: ProblemDetail,
    ): ByteArray {
        problem.instance = problem.instance ?: Problems.instanceOf(request.requestURI)
        return jsonMapper.writeValueAsBytes(problem)
    }
}
