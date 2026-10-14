package com.zeny.hotel.shared.web

import com.zeny.hotel.shared.Idempotent
import com.zeny.hotel.shared.application.idempotency.IdempotencyDecision
import com.zeny.hotel.shared.application.idempotency.IdempotencyReservation
import com.zeny.hotel.shared.application.idempotency.IdempotencyService
import com.zeny.hotel.shared.application.idempotency.StoredResponse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.method.HandlerMethod
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Caminhos do [IdempotencyFilter] que o Spring MVC real não alcança, porque o
 * `ApiExceptionHandler` converte toda `Exception` em resposta: exceção que escapa da cadeia
 * (recusa de segurança relançada ou falha fora do MVC) e falha ao gravar a resposta.
 */
class IdempotencyFilterTest {
    private val idempotency = mockk<IdempotencyService>()
    private val reservation = IdempotencyReservation(UUID.fromString("0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d"))
    private val handler = HandlerMethod(MarkedController(), MarkedController::class.java.getMethod("pay"))
    private val filter = IdempotencyFilter(idempotency, { handler }, ProblemWriter(JsonMapper.builder().build()), 1024)
    private val stored = slot<StoredResponse>()

    @BeforeEach
    fun setUp() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken.authenticated("alice", null, emptyList())
        every { idempotency.begin(any()) } returns IdempotencyDecision.Proceed(reservation)
        every { idempotency.complete(any(), capture(stored)) } returns true
        every { idempotency.release(any()) } just runs
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `an exception escaping the chain stores a generic 500 and is rethrown`() {
        val failure = IllegalStateException("falha fora do MVC com segredo")

        val thrown = shouldThrow<IllegalStateException> { run { _, _ -> throw failure } }

        thrown shouldBe failure
        stored.captured.status shouldBe 500
        stored.captured.headers["Content-Type"] shouldBe listOf(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        val body = String(stored.captured.body, Charsets.UTF_8)
        body shouldContain "INTERNAL_ERROR"
        body shouldNotContain "segredo"
        verify(exactly = 0) { idempotency.release(any()) }
    }

    @Test
    fun `a security refusal escaping the chain releases the key without storing anything`() {
        shouldThrow<ServletException> {
            run { _, _ -> throw ServletException("Request processing failed", AccessDeniedException("negado")) }
        }

        verify(exactly = 1) { idempotency.release(any()) }
        verify(exactly = 0) { idempotency.complete(any(), any()) }
    }

    @Test
    fun `a failure to store the response does not break the response`() {
        every { idempotency.complete(any(), any()) } throws IllegalStateException("banco fora do ar")

        val response =
            run { _, response ->
                response as jakarta.servlet.http.HttpServletResponse
                response.status = 201
                response.contentType = MediaType.APPLICATION_JSON_VALUE
                response.writer.write("""{"ok":true}""")
            }

        response.status shouldBe 201
        response.contentAsString shouldBe """{"ok":true}"""
    }

    private fun run(chain: FilterChain): MockHttpServletResponse {
        val request =
            MockHttpServletRequest("POST", "/api/v1/things").apply {
                contentType = MediaType.APPLICATION_JSON_VALUE
                setContent("{}".toByteArray())
                addHeader("Idempotency-Key", "k1")
            }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, chain)
        return response
    }

    class MarkedController {
        @Idempotent
        fun pay() = Unit
    }
}
