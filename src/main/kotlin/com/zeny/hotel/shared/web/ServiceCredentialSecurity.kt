package com.zeny.hotel.shared.web

import com.zeny.hotel.shared.API_V1
import com.zeny.hotel.shared.ServiceCredentialRealm
import com.zeny.hotel.shared.ServicePrincipal
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.authentication.InsufficientAuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.filter.OncePerRequestFilter

/** Validação da partida dos realms de serviço (D-E10-1). */
internal object ServiceCredentialRealms {
    /** Falha a partida se um prefixo sai de `/api/v1/`, não termina em `/`, ou se dois realms se sobrepõem. */
    fun validate(realms: List<ServiceCredentialRealm>) {
        realms.forEach { realm ->
            val prefix = realm.pathPrefix
            check(
                prefix.startsWith("$API_V1/") && prefix.length > "$API_V1/".length && prefix.endsWith("/") &&
                    !prefix.contains("//") && !prefix.contains('*') && !prefix.contains('{'),
            ) { "Realm de serviço '${realm.name}': prefixo '$prefix' deve começar por $API_V1/ e terminar em /." }
        }
        check(realms.map { it.name }.toSet().size == realms.size) { "Realms de serviço com nome repetido." }
        check(realms.map { it.openApiScheme }.toSet().size == realms.size) {
            "Realms de serviço com esquema OpenAPI repetido."
        }
        realms.forEachIndexed { index, first ->
            realms.drop(index + 1).forEach { second ->
                check(
                    !first.pathPrefix.startsWith(second.pathPrefix) && !second.pathPrefix.startsWith(first.pathPrefix),
                ) {
                    "Realms de serviço '${first.name}' e '${second.name}' têm prefixos sobrepostos."
                }
            }
        }
    }

    /** O casamento do realm: o prefixo sem a barra final também é do realm. */
    fun matcher(realm: ServiceCredentialRealm): RequestMatcher =
        PathPatternRequestMatcher.pathPattern(realm.pathPrefix + "**")
}

/**
 * Autentica as requisições dos realms de serviço: escolhe o realm pelo mesmo casamento do
 * `securityMatcher` da cadeia, lê só o cabeçalho `Authorization` (um único valor, `Bearer` e um
 * token de caracteres seguros) e põe o [ServicePrincipal] no contexto. Sem token, com cabeçalho
 * malformado ou com token recusado, responde `401` pelo [entryPoint], sem detalhe do motivo.
 */
internal class ServiceCredentialAuthenticationFilter(
    realms: List<ServiceCredentialRealm>,
    private val entryPoint: AuthenticationEntryPoint,
) : OncePerRequestFilter() {
    private val routes = realms.map { ServiceCredentialRealms.matcher(it) to it }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val realm = routes.firstOrNull { (matcher, _) -> matcher.matches(request) }?.second
        val token = resolveToken(request)
        val principal = if (realm != null && token != null) realm.authenticate(token) else null
        if (principal == null) {
            SecurityContextHolder.clearContext()
            entryPoint.commence(request, response, REJECTED)
            return
        }
        val context = SecurityContextHolder.getContextHolderStrategy().createEmptyContext()
        context.authentication = ServiceCredentialAuthentication(principal)
        SecurityContextHolder.getContextHolderStrategy().context = context
        filterChain.doFilter(request, response)
    }

    private fun resolveToken(request: HttpServletRequest): String? {
        val values = request.getHeaders(HttpHeaders.AUTHORIZATION).toList()
        return values.singleOrNull()?.let { BEARER.matchEntire(it)?.groupValues?.get(1) }
    }

    private companion object {
        /** `Bearer` (sem diferenciar caixa), um espaço e um token em caracteres seguros (RFC 6750 `b64token`). */
        val BEARER = Regex("^[Bb][Ee][Aa][Rr][Ee][Rr] ([A-Za-z0-9\\-._~+/]+=*)$")

        /** Só alimenta o entry point, que não usa a exceção. */
        val REJECTED = InsufficientAuthenticationException("service credential rejected")
    }
}

/** O `Authentication` da cadeia de serviço: o [ServicePrincipal], sem autoridades e sem guardar o token. */
internal class ServiceCredentialAuthentication(
    private val principal: ServicePrincipal,
) : AbstractAuthenticationToken(emptyList()) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): ServicePrincipal = principal
}
