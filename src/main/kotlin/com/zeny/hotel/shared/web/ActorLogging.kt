package com.zeny.hotel.shared.web

import com.zeny.hotel.shared.CurrentActor
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Põe no MDC dos logs quem está agindo (ARCHITECTURE §16): [USER_ID_MDC_KEY] e
 * [DEVICE_SESSION_ID_MDC_KEY], só os ids, nunca login, nome ou token. Roda logo depois da cadeia
 * do Spring Security, com a requisição já autenticada, e limpa o MDC no fim. No profile `prod`
 * os dois campos saem no JSON (ECS) ao lado do `traceId`; no `local`, no padrão de correlação.
 */
internal class ActorMdcFilter(
    private val currentActor: CurrentActor,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val actor = currentActor.currentOrNull()
        if (actor == null) {
            chain.doFilter(request, response)
            return
        }
        MDC.put(USER_ID_MDC_KEY, actor.userId.toString())
        MDC.put(DEVICE_SESSION_ID_MDC_KEY, actor.deviceSessionId.toString())
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove(USER_ID_MDC_KEY)
            MDC.remove(DEVICE_SESSION_ID_MDC_KEY)
        }
    }

    override fun shouldNotFilterAsyncDispatch(): Boolean = false

    override fun shouldNotFilterErrorDispatch(): Boolean = false
}

/** Chave do MDC com o id do usuário autenticado. */
const val USER_ID_MDC_KEY = "userId"

/** Chave do MDC com o id da sessão de dispositivo do usuário autenticado. */
const val DEVICE_SESSION_ID_MDC_KEY = "deviceSessionId"

@Configuration(proxyBeanMethods = false)
class ActorLoggingConfiguration {
    @Bean
    fun actorMdcFilter(currentActor: CurrentActor): FilterRegistrationBean<*> =
        FilterRegistrationBean(ActorMdcFilter(currentActor)).apply {
            setName("actorMdcFilter")
            addUrlPatterns("/*")
            // Depois da segurança (a requisição já tem o ator) e antes da idempotência.
            order = SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1
        }
}
