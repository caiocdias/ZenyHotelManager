package com.zeny.hotel.fnrh.application

import com.zeny.hotel.fnrh.FnrhErrorCodes
import com.zeny.hotel.fnrh.FnrhPermissions
import com.zeny.hotel.fnrh.domain.FnrhDomain
import com.zeny.hotel.fnrh.domain.FnrhDomainCatalog
import com.zeny.hotel.fnrh.domain.FnrhDomainValue
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BadGatewayException
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.ServiceUnavailableException
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/** Um valor de domínio como a API o mostra. */
data class FnrhDomainValueView(
    val code: String,
    val label: String,
)

/** Quantos valores um domínio passou a ter depois do `refresh`; [domain] é o nome em kebab-case da API. */
data class FnrhDomainCount(
    val domain: String,
    val count: Int,
)

/** O resultado do `refresh`: a contagem de cada um dos domínios. */
data class FnrhDomainsRefreshResult(
    val domains: List<FnrhDomainCount>,
)

/** Os nomes dos domínios na API (kebab-case, em inglês); o brief da E07 fixa a lista. */
object FnrhDomainNames {
    private val byDomain =
        mapOf(
            FnrhDomain.TRANSPORT_MODES to "transport-modes",
            FnrhDomain.TRAVEL_REASONS to "travel-reasons",
            FnrhDomain.GUEST_STATUSES to "guest-statuses",
            FnrhDomain.GENDERS to "genders",
            FnrhDomain.DISABILITY_OPTIONS to "disability-options",
            FnrhDomain.RACES to "races",
            FnrhDomain.DISABILITY_TYPES to "disability-types",
            FnrhDomain.DOCUMENT_TYPES to "document-types",
            FnrhDomain.RESERVATION_STATUSES to "booking-statuses",
            FnrhDomain.FORM_STATUSES to "record-statuses",
        )

    fun of(domain: FnrhDomain): String = byDomain.getValue(domain)

    fun parse(name: String): FnrhDomain? = byDomain.entries.firstOrNull { it.value == name }?.key
}

/** A escrita do cache dos domínios, implementada em `infrastructure`. */
interface FnrhDomainValueStore {
    /** Serializa os `refresh` até o fim da transação: o seguinte espera o anterior e grava por cima. */
    fun lockForRefresh()

    /** Quantos valores cada domínio tem agora. */
    fun counts(): Map<FnrhDomain, Int>

    /** Troca todo o cache por [values], marcado com [refreshedAt]. */
    fun replaceAll(
        values: Map<FnrhDomain, List<FnrhDomainValue>>,
        refreshedAt: Instant,
    )
}

/**
 * A gravação do `refresh`, num bean à parte para a transação e a trilha valerem: se ela fosse um método do
 * [FnrhDomainsService], a chamada de dentro da própria classe pularia o proxy e com ele o `@Transactional` e o
 * `@Audited`.
 */
@Component
class FnrhDomainValuesWriter(
    private val store: FnrhDomainValueStore,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Audited(FnrhAudit.REFRESH_DOMAINS)
    @Transactional
    fun replace(values: Map<FnrhDomain, List<FnrhDomainValue>>): Map<FnrhDomain, Int> {
        store.lockForRefresh()
        val now = clock.instant()
        val before = store.counts().named()
        store.replaceAll(values, now)
        val after = store.counts()
        // O instante entra no retrato: um `refresh` que não muda nenhum valor ainda deixa o rastro de que aconteceu.
        audit.record(
            FnrhAudit.DOMAIN_VALUES,
            FnrhAudit.DOMAIN_VALUES_ID,
            before,
            after.named() + ("refreshedAt" to now.toString()),
        )
        return after
    }

    private fun Map<FnrhDomain, Int>.named(): Map<String, Any> = mapKeys { FnrhDomainNames.of(it.key) }
}

/**
 * Os domínios oficiais da FNRH (RF-FNRH-02): ler exige `fnrh.domains.read`; atualizar a partir do Serpro exige
 * `fnrh.settings.manage`. O `refresh` não tem transação: busca os dez domínios fora de qualquer uma e só então chama o
 * [FnrhDomainValuesWriter], de modo que uma falha em qualquer busca não muda nada.
 */
@Service
class FnrhDomainsService(
    private val catalog: FnrhDomainCatalog,
    private val gateway: FnrhGateway,
    private val writer: FnrhDomainValuesWriter,
    private val authorization: Authorization,
) {
    @Transactional(readOnly = true)
    fun values(name: String): List<FnrhDomainValueView> {
        authorization.require(FnrhPermissions.DOMAINS_READ)
        val domain =
            FnrhDomainNames.parse(name)
                ?: throw NotFoundException(FnrhErrorCodes.FNRH_DOMAIN_NOT_FOUND, "O domínio da FNRH não existe.")
        return catalog.values(domain).map { FnrhDomainValueView(it.code, it.label) }
    }

    fun refresh(): FnrhDomainsRefreshResult {
        authorization.require(FnrhPermissions.SETTINGS_MANAGE)
        val fetched = FnrhDomain.entries.associateWith { fetch(it) }
        val counts = writer.replace(fetched)
        return FnrhDomainsRefreshResult(
            FnrhDomain.entries.map { FnrhDomainCount(FnrhDomainNames.of(it), counts.getValue(it)) },
        )
    }

    private fun fetch(domain: FnrhDomain): List<FnrhDomainValue> {
        val values =
            try {
                gateway.fetchDomain(domain)
            } catch (e: FnrhGatewayException) {
                throw e.toApiException()
            }
        val distinct = values.distinctBy { it.code }.map { it.copy(label = it.label.trim()) }
        // Dado do Serpro entra no cache e depois na ficha: valor fora do padrão ou domínio vazio (que apagaria as
        // opções do check-in) recusa o refresh inteiro.
        if (distinct.isEmpty() || distinct.any { !it.isWellFormed() }) {
            throw BadGatewayException(FnrhErrorCodes.FNRH_REJECTED, "O Serpro devolveu um domínio inválido ou vazio.")
        }
        return distinct
    }

    private fun FnrhDomainValue.isWellFormed() =
        CODE_PATTERN.matches(code) && label.length in 1..MAX_LABEL &&
            label.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }

    private fun FnrhGatewayException.toApiException() =
        when (failure) {
            FnrhFailure.NOT_CONFIGURED -> {
                BusinessRuleException(
                    FnrhErrorCodes.FNRH_NOT_CONFIGURED,
                    "A integração com a FNRH não está configurada.",
                )
            }

            FnrhFailure.UNAUTHORIZED, FnrhFailure.FORBIDDEN -> {
                BusinessRuleException(
                    FnrhErrorCodes.FNRH_CREDENTIALS_REJECTED,
                    "O Serpro recusou as credenciais da integração.",
                )
            }

            FnrhFailure.SERVER_ERROR, FnrhFailure.UNAVAILABLE, FnrhFailure.UNKNOWN_OUTCOME -> {
                ServiceUnavailableException(
                    FnrhErrorCodes.FNRH_UNAVAILABLE,
                    "O Serpro está indisponível. Tente de novo.",
                )
            }

            FnrhFailure.VALIDATION, FnrhFailure.NOT_FOUND -> {
                BadGatewayException(FnrhErrorCodes.FNRH_REJECTED, "O Serpro recusou a consulta.")
            }
        }

    private companion object {
        val CODE_PATTERN = Regex("[A-Z0-9_]{1,40}")
        const val MAX_LABEL = 255
    }
}
