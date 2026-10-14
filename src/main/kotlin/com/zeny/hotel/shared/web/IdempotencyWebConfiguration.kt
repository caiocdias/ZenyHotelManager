package com.zeny.hotel.shared.web

import com.zeny.hotel.shared.API_V1
import com.zeny.hotel.shared.application.idempotency.IdempotencyProperties
import com.zeny.hotel.shared.application.idempotency.IdempotencyService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.json.JsonMapper

/**
 * Registra o [IdempotencyFilter] em `/api/...`, logo depois da cadeia do Spring Security: a
 * requisição chega autenticada (o usuário é o escopo da chave) e quem não está autenticado
 * recebe `401` antes de qualquer checagem de idempotência.
 */
@Configuration(proxyBeanMethods = false)
class IdempotencyWebConfiguration {
    @Bean
    fun idempotencyFilter(
        idempotency: IdempotencyService,
        properties: IdempotencyProperties,
        jsonMapper: JsonMapper,
        @Qualifier("requestMappingHandlerMapping") handlerMapping: ObjectProvider<RequestMappingHandlerMapping>,
    ): FilterRegistrationBean<*> {
        // O handler mapping do MVC é resolvido na primeira requisição, não na criação dos filtros.
        val filter =
            IdempotencyFilter(
                idempotency = idempotency,
                handlerOf = { request -> handlerMapping.getObject().getHandler(request)?.handler },
                problems = ProblemWriter(jsonMapper),
                maxRequestBodySize = properties.maxRequestBodySize.toBytes().toInt(),
            )
        return FilterRegistrationBean(filter).apply {
            setName("idempotencyFilter")
            addUrlPatterns("$API_PREFIX/*")
            order = SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1
        }
    }

    private companion object {
        /** `/api`: o filtro olha toda a API; só age nos handlers marcados. */
        val API_PREFIX = API_V1.substringBeforeLast('/')
    }
}
