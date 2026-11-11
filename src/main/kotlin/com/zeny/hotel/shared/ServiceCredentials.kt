package com.zeny.hotel.shared

import java.util.UUID

/**
 * Porta das credenciais de serviço (ARCHITECTURE §8.1): um "realm" é uma classe de cliente
 * máquina-a-máquina (por exemplo, o agente de impressão) com token opaco próprio e um prefixo
 * de caminho exclusivo em `/api/v1/`. O módulo dono do realm implementa esta porta; o `shared`
 * monta a cadeia de segurança, que lê só o cabeçalho `Authorization: Bearer` e chama
 * [authenticate].
 */
interface ServiceCredentialRealm {
    /** Nome do realm, único; vai no [ServicePrincipal.realm]. */
    val name: String

    /** Prefixo dos caminhos do realm: começa por `/api/v1/` e termina em `/`. Não se sobrepõe ao de outro realm. */
    val pathPrefix: String

    /** Nome do esquema de segurança do realm no contrato OpenAPI (HTTP bearer, formato opaco). */
    val openApiScheme: String

    /**
     * O principal do [token], ou `null` se ele não vale (desconhecido, trocado, credencial inativa).
     * Nunca lança por token inválido, nunca registra nem devolve o token.
     */
    fun authenticate(token: String): ServicePrincipal?
}

/**
 * Quem agiu numa rota de um realm de serviço: o `principal` do `Authentication` da cadeia, sem
 * autoridades. [credentialStamp] é opaco e muda quando a credencial é trocada (permite a uma
 * conexão aberta notar que o token que a autorizou deixou de valer).
 */
data class ServicePrincipal(
    val realm: String,
    val id: UUID,
    val credentialStamp: String,
) {
    /** Sem o carimbo da credencial: ele não deve ir a log. */
    override fun toString(): String = "ServicePrincipal(realm=$realm, id=$id)"
}
