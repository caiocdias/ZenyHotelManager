package com.zeny.hotel.fnrh.domain

/**
 * Os domínios oficiais da FNRH guardados em cache (M5 E07, D-E07-5): a semente da migração desde o primeiro dia e o
 * último `refresh` depois. A validação das fichas (E07, tarefa 7) consulta por aqui. Implementado em `infrastructure`.
 */
interface FnrhDomainCatalog {
    /** Se [code] consta do [domain] (maiúsculas e minúsculas contam: o código é o `id` que o Serpro aceita). */
    fun contains(
        domain: FnrhDomain,
        code: String,
    ): Boolean

    /** Os valores do [domain], em ordem de código. */
    fun values(domain: FnrhDomain): List<FnrhDomainValue>
}
