package com.zeny.hotel.printing.infrastructure

import com.zeny.hotel.printing.application.PrintAgentStore
import com.zeny.hotel.printing.domain.PrintAgent
import com.zeny.hotel.shared.ServiceCredentialRealm
import com.zeny.hotel.shared.ServicePrincipal
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * O realm do agente de impressão (D-E10-1, D-E10-2, D-E10-11): o token opaco `zpa_...` vale só em
 * `/api/v1/print-agents/me/`. Recusa sem consulta o que não tem o prefixo, procura o agente ativo pelo SHA-256 do
 * token e devolve o [PrintAgent.credentialStamp]. O contato (`last_seen_at`, no máximo uma vez por minuto) é gravado
 * numa transação própria e curta, sem trilha. O token nunca vai a log nem a mensagem de exceção.
 */
@Component
class PrintAgentRealm(
    private val agents: PrintAgentStore,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) : ServiceCredentialRealm {
    private val transaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    override val name = "print-agent"
    override val pathPrefix = "/api/v1/print-agents/me/"
    override val openApiScheme = "printAgentToken"

    override fun authenticate(token: String): ServicePrincipal? =
        if (token.startsWith(PrintAgent.TOKEN_PREFIX)) lookup(PrintAgent.hashOf(token)) else null

    private fun lookup(hash: String): ServicePrincipal? =
        transaction.execute {
            val agent = agents.byTokenHash(hash) ?: return@execute null
            agents.recordContact(agent.id, clock.instant())
            ServicePrincipal(name, agent.id, agent.credentialStamp)
        }
}
