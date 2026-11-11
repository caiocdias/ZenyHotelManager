package com.zeny.hotel.shared.web

import com.zeny.hotel.shared.API_V1
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.PASSWORD_CHANGE_PENDING_AUTHORITY
import com.zeny.hotel.shared.ServiceCredentialRealm
import jakarta.servlet.DispatcherType
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.authorization.AuthenticatedAuthorizationManager
import org.springframework.security.authorization.AuthorityAuthorizationManager
import org.springframework.security.authorization.AuthorizationManagers
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.RequestMatcher
import tools.jackson.databind.json.JsonMapper

/**
 * Segurança da aplicação (ARCHITECTURE §8.1), em duas `SecurityFilterChain`.
 *
 * A [serviceSecurityFilterChain] (ordem 1) atende só os prefixos dos [ServiceCredentialRealm]
 * presentes (o agente de impressão, por exemplo): token opaco do realm no cabeçalho
 * `Authorization: Bearer`, [com.zeny.hotel.shared.ServicePrincipal] sem autoridades, `401
 * UNAUTHENTICATED` em qualquer falha, sem sessão, CSRF, Basic nem formulário. Sem realm, não casa com
 * nenhuma requisição. Um JWT de usuário num caminho de realm é recusado, e um token de realm fora do
 * prefixo cai na cadeia de usuário e é recusado.
 *
 * A `apiSecurityFilterChain` (a de usuário, abaixo) atende todo o resto:
 *
 * - toda requisição a `/api/...` exige um access token JWT válido no cabeçalho
 *   `Authorization: Bearer …` (Resource Server do Spring Security, com o `JwtDecoder` e o
 *   conversor do módulo `iam`: RS256, `kid` conhecido, assinatura, emissor e validade);
 * - as exceções são [PUBLIC_API_ENDPOINTS]: login, refresh, `GET /api/v1/meta/version` e o download
 *   de anexo sensível (`GET /api/v1/files/{id}/content`, autorizado pelo token da URL assinada). Neles
 *   o cabeçalho `Authorization` é ignorado, para que um access token vencido não impeça o
 *   refresh;
 * - token com troca de senha pendente ([PASSWORD_CHANGE_PENDING_AUTHORITY]) só alcança
 *   [PASSWORD_CHANGE_ENDPOINTS] (troca de senha e logout); o resto da API responde
 *   `403 PASSWORD_CHANGE_REQUIRED`;
 * - a documentação (`/v3/api-docs`, Swagger UI) fica aberta só onde o springdoc está ligado,
 *   isto é, fora do profile `prod`;
 * - do actuator, só `GET /actuator/health/liveness`, `/actuator/health/readiness` e
 *   `/actuator/prometheus` ficam abertos (sondas da imagem e raspagem do Prometheus na rede
 *   interna; o proxy não repassa o `prometheus`). O `/actuator/health` agregado, os componentes
 *   e qualquer outro endpoint caem no "todo o resto";
 * - todo o resto é negado.
 *
 * Não há usuário em memória, HTTP Basic nem login por formulário. Sem sessão nem cookie (API sem
 * estado, token de portador), então sem CSRF. As permissões (ARCHITECTURE §8.2) não estão no
 * token: a autorização fina fica na camada `application` de cada módulo. As recusas saem como
 * problema RFC 9457: `401 UNAUTHENTICATED`, `403 ACCESS_DENIED` e `403 PASSWORD_CHANGE_REQUIRED`.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    @Suppress("LongParameterList") // dependências injetadas pelo Spring no método @Bean
    fun apiSecurityFilterChain(
        http: HttpSecurity,
        jsonMapper: JsonMapper,
        jwtDecoder: JwtDecoder,
        jwtAuthenticationConverter: JwtAuthenticationConverter,
        @Value("\${springdoc.api-docs.enabled:true}") apiDocsEnabled: Boolean,
        @Value("\${springdoc.swagger-ui.enabled:true}") swaggerUiEnabled: Boolean,
    ): SecurityFilterChain {
        val writer = ProblemWriter(jsonMapper)
        val entryPoint = unauthenticatedEntryPoint(writer)
        val accessDeniedHandler = accessDeniedHandler(writer)
        http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .oauth2ResourceServer { resourceServer ->
                resourceServer.bearerTokenResolver(PublicEndpointsIgnoringBearerTokenResolver())
                resourceServer.authenticationEntryPoint(entryPoint)
                resourceServer.accessDeniedHandler(accessDeniedHandler)
                resourceServer.jwt { jwt ->
                    jwt.decoder(jwtDecoder)
                    jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)
                }
            }.authorizeHttpRequests { requests ->
                // Encaminhamento interno de erro para o ProblemErrorController; acesso direto a /error é negado.
                requests.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                // Fim de uma resposta assíncrona (o stream SSE de /api/v1/stream, encerrado pelo servidor): a
                // requisição original já passou pela autorização; o token pode até ter vencido desde então.
                requests.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                // Sondas e métricas (ARCHITECTURE §16); o resto do actuator nem existe na web.
                requests
                    .requestMatchers(
                        HttpMethod.GET,
                        "/actuator/health/liveness",
                        "/actuator/health/readiness",
                        "/actuator/prometheus",
                    ).permitAll()
                PUBLIC_API_ENDPOINTS.forEach { (method, path) -> requests.requestMatchers(method, path).permitAll() }
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
                PASSWORD_CHANGE_ENDPOINTS.forEach { (method, path) ->
                    requests.requestMatchers(method, path).authenticated()
                }
                requests.requestMatchers("/api/**").access(AUTHENTICATED_WITHOUT_PENDING_PASSWORD_CHANGE)
                requests.anyRequest().denyAll()
            }.exceptionHandling { handling ->
                handling.authenticationEntryPoint(entryPoint)
                handling.accessDeniedHandler(accessDeniedHandler)
            }
        return http.build()
    }

    /**
     * Cadeia das credenciais de serviço (D-E10-1): casa com `pathPrefix + "**"` de cada realm (o prefixo sem
     * a barra final também) e nada mais; sem realm, não casa com nada. O filtro escolhe o realm pelo mesmo
     * casamento.
     */
    @Bean
    @Order(SERVICE_CHAIN_ORDER)
    fun serviceSecurityFilterChain(
        http: HttpSecurity,
        jsonMapper: JsonMapper,
        realmsProvider: ObjectProvider<ServiceCredentialRealm>,
    ): SecurityFilterChain {
        val realms = realmsProvider.orderedStream().toList()
        ServiceCredentialRealms.validate(realms)
        val matchers = realms.map { ServiceCredentialRealms.matcher(it) }
        val entryPoint = unauthenticatedEntryPoint(ProblemWriter(jsonMapper))
        http
            .securityMatcher(RequestMatcher { request -> matchers.any { it.matches(request) } })
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .anonymous { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .addFilterBefore(ServiceCredentialAuthenticationFilter(realms, entryPoint), AuthorizationFilter::class.java)
            .authorizeHttpRequests { requests ->
                requests.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                requests.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                requests.anyRequest().authenticated()
            }.exceptionHandling { it.authenticationEntryPoint(entryPoint) }
        return http.build()
    }

    private fun unauthenticatedEntryPoint(writer: ProblemWriter) =
        AuthenticationEntryPoint { request, response, _ ->
            // RFC 6750 §3: sem detalhes do motivo, que só ajudariam um atacante.
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
            writer.write(request, response, Problems.of(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHENTICATED))
        }

    private fun accessDeniedHandler(writer: ProblemWriter) =
        AccessDeniedHandler { request, response, _ ->
            val code = if (passwordChangePending()) ErrorCodes.PASSWORD_CHANGE_REQUIRED else ErrorCodes.ACCESS_DENIED
            writer.write(request, response, Problems.of(HttpStatus.FORBIDDEN, code))
        }

    private fun passwordChangePending(): Boolean =
        SecurityContextHolder
            .getContext()
            .authentication
            ?.authorities
            .orEmpty()
            .any { it.authority == PASSWORD_CHANGE_PENDING_AUTHORITY }

    /**
     * O resolvedor padrão (só o cabeçalho `Authorization`, nunca query nem formulário), exceto
     * nos endpoints públicos: lá o cabeçalho é ignorado, e um token vencido ou inválido enviado
     * por hábito do cliente não transforma o login ou o refresh em `401`.
     */
    private class PublicEndpointsIgnoringBearerTokenResolver : BearerTokenResolver {
        private val delegate = DefaultBearerTokenResolver()
        private val publicEndpoints =
            PUBLIC_API_ENDPOINTS.map { (method, path) -> PathPatternRequestMatcher.pathPattern(method, path) }

        override fun resolve(request: HttpServletRequest): String? =
            if (publicEndpoints.any { it.matches(request) }) null else delegate.resolve(request)
    }

    companion object {
        private const val SERVICE_CHAIN_ORDER = 1

        /** Os únicos endpoints de `/api/...` abertos sem token. */
        val PUBLIC_API_ENDPOINTS: List<Pair<HttpMethod, String>> =
            listOf(
                HttpMethod.POST to "$API_V1/auth/login",
                HttpMethod.POST to "$API_V1/auth/refresh",
                HttpMethod.GET to "$API_V1/meta/version",
                // Download de anexo sensível (módulo files): autorizado pelo token da URL assinada pelo
                // servidor, de curta duração, e não pelo access token.
                HttpMethod.GET to "$API_V1/files/{id}/content",
            )

        /** O que um token com troca de senha pendente ainda alcança. */
        val PASSWORD_CHANGE_ENDPOINTS: List<Pair<HttpMethod, String>> =
            listOf(
                HttpMethod.POST to "$API_V1/auth/password",
                HttpMethod.POST to "$API_V1/auth/logout",
            )

        private val AUTHENTICATED_WITHOUT_PENDING_PASSWORD_CHANGE =
            AuthorizationManagers.allOf(
                AuthenticatedAuthorizationManager.authenticated<RequestAuthorizationContext>(),
                AuthorizationManagers.not(
                    AuthorityAuthorizationManager.hasAuthority<RequestAuthorizationContext>(
                        PASSWORD_CHANGE_PENDING_AUTHORITY,
                    ),
                ),
            )
    }
}
