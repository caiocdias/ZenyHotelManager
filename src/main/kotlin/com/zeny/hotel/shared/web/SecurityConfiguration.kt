package com.zeny.hotel.shared.web

import com.zeny.hotel.shared.API_V1
import com.zeny.hotel.shared.ErrorCodes
import jakarta.servlet.DispatcherType
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import tools.jackson.databind.json.JsonMapper

/**
 * Segurança **mínima e temporária** até o M1 (roadmap M0, E04), quando chegam a autenticação
 * JWT e a autorização reais (ARCHITECTURE §8.1 e §8.2):
 *
 * - `GET /api/v1/meta/version` é o único endpoint da API aberto sem autenticação;
 * - o resto de `/api/...` exige autenticação, e **não há como autenticar**: não existe usuário,
 *   senha, HTTP Basic nem login por formulário (o `UserDetailsServiceAutoConfiguration` do
 *   Boot está excluído no `application.yaml`). Na prática, a API fica fechada;
 * - a documentação (`/v3/api-docs`, Swagger UI) fica aberta só onde o springdoc está ligado,
 *   isto é, fora do profile `prod`;
 * - o actuator ainda não existe; a E06 abre `health/liveness`, `health/readiness` e `prometheus`;
 * - todo o resto é negado.
 *
 * Sem sessão nem cookie (API sem estado, com token de portador a partir do M1), então sem
 * CSRF. As recusas saem como problema RFC 9457: `401 UNAUTHENTICATED` e `403 ACCESS_DENIED`.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    fun apiSecurityFilterChain(
        http: HttpSecurity,
        jsonMapper: JsonMapper,
        @Value("\${springdoc.api-docs.enabled:true}") apiDocsEnabled: Boolean,
        @Value("\${springdoc.swagger-ui.enabled:true}") swaggerUiEnabled: Boolean,
    ): SecurityFilterChain {
        val writer = ProblemWriter(jsonMapper)
        http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { requests ->
                // Encaminhamento interno de erro para o ProblemErrorController; acesso direto a /error é negado.
                requests.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                // Os únicos endpoints da API abertos sem autenticação até o M1.
                requests.requestMatchers(HttpMethod.GET, "$API_V1/meta/version").permitAll()
                if (apiDocsEnabled) {
                    requests
                        .requestMatchers(
                            HttpMethod.GET,
                            "/v3/api-docs",
                            "/v3/api-docs/**",
                            "/v3/api-docs.yaml",
                        ).permitAll()
                }
                if (swaggerUiEnabled) {
                    requests.requestMatchers(HttpMethod.GET, "/swagger-ui.html", "/swagger-ui/**").permitAll()
                }
                // M1 troca isto por autenticação JWT e autorização por permissão (ARCHITECTURE §8).
                requests.requestMatchers("/api/**").authenticated()
                requests.anyRequest().denyAll()
            }.exceptionHandling { handling ->
                handling.authenticationEntryPoint(writer.entryPoint())
                handling.accessDeniedHandler(writer.accessDeniedHandler())
            }
        return http.build()
    }
}

/** Escreve as recusas do Spring Security como problema RFC 9457. */
private class ProblemWriter(
    private val jsonMapper: JsonMapper,
) {
    fun entryPoint() =
        AuthenticationEntryPoint { request, response, _ ->
            write(request, response, Problems.of(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHENTICATED))
        }

    fun accessDeniedHandler() =
        AccessDeniedHandler { request, response, _ ->
            write(request, response, Problems.of(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED))
        }

    private fun write(
        request: HttpServletRequest,
        response: HttpServletResponse,
        problem: ProblemDetail,
    ) {
        problem.instance = Problems.instanceOf(request.requestURI)
        response.status = problem.status
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        jsonMapper.writeValue(response.outputStream, problem)
    }
}
