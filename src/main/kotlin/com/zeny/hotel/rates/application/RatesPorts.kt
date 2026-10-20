package com.zeny.hotel.rates.application

import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.rates.CancellationPenaltyType
import com.zeny.hotel.rates.CancellationPolicyId
import com.zeny.hotel.rates.GuaranteePolicyId
import com.zeny.hotel.rates.GuaranteeType
import com.zeny.hotel.rates.MealPlan
import com.zeny.hotel.rates.PackageId
import com.zeny.hotel.rates.PackagePostingRhythm
import com.zeny.hotel.rates.PackagePricing
import com.zeny.hotel.rates.RatePlanCategory
import com.zeny.hotel.rates.RatePlanDerivation
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.SalesChannel
import com.zeny.hotel.rates.SeasonId
import com.zeny.hotel.rates.domain.CancellationPolicy
import com.zeny.hotel.rates.domain.CancellationTerms
import com.zeny.hotel.rates.domain.GuaranteePolicy
import com.zeny.hotel.rates.domain.GuaranteeTerms
import com.zeny.hotel.rates.domain.PackageDetails
import com.zeny.hotel.rates.domain.RatePackage
import com.zeny.hotel.rates.domain.RatePlan
import com.zeny.hotel.rates.domain.RatePlanDetails
import com.zeny.hotel.rates.domain.Season
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import java.time.Instant
import java.util.UUID

/**
 * Os agregados do `rates` para escrita (JPA), na transação corrente. As gravações que podem violar uma unicidade do
 * banco devolvem `false` em vez de falhar, para o caso de uso responder `409`. Os métodos `…ForUpdate` travam a linha
 * (`SELECT … FOR UPDATE`) até o fim da transação e a releem: o vínculo de um plano a uma política ou pacote e a
 * desativação dele passam por essa trava, então nunca sobra plano ativo com política ou pacote desativado.
 */
@Suppress("TooManyFunctions") // um método por acesso de cada agregado
interface RatesStore {
    fun cancellationPolicy(id: CancellationPolicyId): CancellationPolicy?

    fun cancellationPolicyForUpdate(id: CancellationPolicyId): CancellationPolicy?

    fun cancellationPolicies(ids: List<UUID>): List<CancellationPolicy>

    /** `false` se o nome já existe (sem diferenciar maiúsculas). */
    fun tryAdd(policy: CancellationPolicy): Boolean

    /** Se algum plano **ativo** usa a política (lido depois da trava da política). */
    fun cancellationPolicyInUse(id: CancellationPolicyId): Boolean

    fun guaranteePolicy(id: GuaranteePolicyId): GuaranteePolicy?

    fun guaranteePolicyForUpdate(id: GuaranteePolicyId): GuaranteePolicy?

    fun guaranteePolicies(ids: List<UUID>): List<GuaranteePolicy>

    /** `false` se o nome já existe (sem diferenciar maiúsculas). */
    fun tryAdd(policy: GuaranteePolicy): Boolean

    /** Se algum plano **ativo** usa a política (lido depois da trava da política). */
    fun guaranteePolicyInUse(id: GuaranteePolicyId): Boolean

    fun ratePackage(id: PackageId): RatePackage?

    fun ratePackageForUpdate(id: PackageId): RatePackage?

    /** Os pacotes existentes entre [ids], travados em ordem de id (sem deadlock entre duas escritas). */
    fun ratePackagesForUpdate(ids: Collection<PackageId>): List<RatePackage>

    fun ratePackages(ids: List<UUID>): List<RatePackage>

    /** `false` se o código já existe. */
    fun tryAdd(ratePackage: RatePackage): Boolean

    /** Se o pacote está em algum plano **ativo** (lido depois da trava do pacote). */
    fun ratePackageInUse(id: PackageId): Boolean

    fun ratePlan(id: RatePlanId): RatePlan?

    /** O plano travado (`SELECT … FOR UPDATE`) e relido: preços e derivação dele se escrevem um de cada vez. */
    fun ratePlanForUpdate(id: RatePlanId): RatePlan?

    fun ratePlans(ids: List<UUID>): List<RatePlan>

    /** `false` se o código já existe. */
    fun tryAdd(plan: RatePlan): Boolean

    fun season(id: SeasonId): Season?

    fun seasons(ids: List<UUID>): List<Season>

    /** `false` se o nome já existe (sem diferenciar maiúsculas). */
    fun tryAdd(season: Season): Boolean

    /** Grava já as mudanças pendentes (conferindo a versão: lock otimista). */
    fun flush()

    /** Como [flush], mas `false` se violar a unicidade [constraint] (nome de restrição ou índice único). */
    fun tryFlush(constraint: String): Boolean
}

/** Nomes das unicidades do banco que viram `409`. */
object RatesConstraints {
    const val RATE_PLAN_CODE = "rate_plan_code_uk"
    const val PACKAGE_CODE = "rate_package_code_uk"
    const val CANCELLATION_POLICY_NAME = "cancellation_policy_name_uk"
    const val GUARANTEE_POLICY_NAME = "guarantee_policy_name_uk"
    const val SEASON_NAME = "season_name_uk"
}

/** Filtros da lista de planos. */
data class RatePlanFilter(
    val active: Boolean? = null,
    val category: RatePlanCategory? = null,
    val channel: SalesChannel? = null,
    val roomTypeId: RoomTypeId? = null,
)

/**
 * Leituras de páginas do `rates` (`JdbcClient`, ARCHITECTURE §11): só a chave de ordem e o id de cada linha, em ordem
 * total e estável; o caso de uso carrega os agregados da página.
 */
interface RatesDirectory {
    /** Planos em ordem de código e id, depois de [after]. */
    fun ratePlans(
        filter: RatePlanFilter,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<Pair<String, UUID>>

    /** Políticas de cancelamento em ordem de nome (sem diferenciar maiúsculas) e id. */
    fun cancellationPolicies(
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<Pair<String, UUID>>

    /** Políticas de garantia em ordem de nome (sem diferenciar maiúsculas) e id. */
    fun guaranteePolicies(
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<Pair<String, UUID>>

    /** Pacotes em ordem de código e id. */
    fun ratePackages(
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<Pair<String, UUID>>

    /** Temporadas em ordem de início (`YYYY-MM-DD`) e id. */
    fun seasons(
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<Pair<String, UUID>>
}

data class RatePlanView(
    val id: RatePlanId,
    val code: String,
    val name: String,
    val description: String?,
    val mealPlan: MealPlan,
    val category: RatePlanCategory,
    val channels: Set<SalesChannel>,
    val roomTypeIds: Set<RoomTypeId>,
    val packageIds: Set<PackageId>,
    val cancellationPolicyId: CancellationPolicyId,
    val guaranteePolicyId: GuaranteePolicyId,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
    val derivation: RatePlanDerivation? = null,
)

data class CancellationPolicyView(
    val id: CancellationPolicyId,
    val name: String,
    val deadlineHours: Int,
    val penaltyType: CancellationPenaltyType,
    val penaltyPercent: Percent?,
    val penaltyNights: Int?,
    val penaltyAmount: Money?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class GuaranteePolicyView(
    val id: GuaranteePolicyId,
    val name: String,
    val type: GuaranteeType,
    val depositPercent: Percent?,
    val dueDaysBefore: Int?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class PackageView(
    val id: PackageId,
    val code: String,
    val name: String,
    val price: Money,
    val pricing: PackagePricing,
    val postingRhythm: PackagePostingRhythm,
    val includedInRate: Boolean,
    val transactionCodeId: TransactionCodeId,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

internal fun RatePlan.view() =
    RatePlanView(
        id,
        code,
        name,
        description,
        mealPlan,
        category,
        channels,
        roomTypeIds,
        packageIds,
        cancellationPolicyId,
        guaranteePolicyId,
        active,
        createdAt,
        updatedAt,
        checkNotNull(version),
        derivation?.toPublic(),
    )

internal fun CancellationPolicy.view() =
    CancellationPolicyView(
        id,
        name,
        deadlineHours,
        penaltyType,
        penaltyPercent,
        penaltyNights,
        penaltyAmount,
        active,
        createdAt,
        updatedAt,
        checkNotNull(version),
    )

internal fun GuaranteePolicy.view() =
    GuaranteePolicyView(
        id,
        name,
        type,
        depositPercent,
        dueDaysBefore,
        active,
        createdAt,
        updatedAt,
        checkNotNull(version),
    )

internal fun RatePackage.view() =
    PackageView(
        id,
        code,
        name,
        price,
        pricing,
        postingRhythm,
        includedInRate,
        transactionCodeId,
        active,
        createdAt,
        updatedAt,
        checkNotNull(version),
    )

/** Os dados editáveis de um plano tarifário, como chegam da API (RF-TAR-01). */
data class RatePlanCommand(
    val code: String,
    val name: String,
    val description: String?,
    val mealPlan: MealPlan,
    val channels: Set<SalesChannel>,
    val roomTypeIds: Set<RoomTypeId>,
    val packageIds: Set<PackageId>,
    val cancellationPolicyId: CancellationPolicyId,
    val guaranteePolicyId: GuaranteePolicyId,
) {
    internal val details: RatePlanDetails
        get() =
            RatePlanDetails(
                code,
                name,
                description,
                mealPlan,
                channels,
                roomTypeIds,
                packageIds,
                cancellationPolicyId,
                guaranteePolicyId,
            )
}

/** Um plano novo: a categoria (fixa depois) e os dados editáveis. */
data class NewRatePlanCommand(
    val category: RatePlanCategory,
    val plan: RatePlanCommand,
)

/** Os termos de uma política de cancelamento, como chegam da API (RF-TAR-07). */
data class CancellationPolicyCommand(
    val name: String,
    val deadlineHours: Int,
    val penaltyType: CancellationPenaltyType,
    val penaltyPercent: Percent?,
    val penaltyNights: Int?,
    val penaltyAmount: Money?,
) {
    internal val terms: CancellationTerms
        get() = CancellationTerms(name, deadlineHours, penaltyType, penaltyPercent, penaltyNights, penaltyAmount)
}

/** Os termos de uma política de garantia, como chegam da API (RF-TAR-07). */
data class GuaranteePolicyCommand(
    val name: String,
    val type: GuaranteeType,
    val depositPercent: Percent?,
    val dueDaysBefore: Int?,
) {
    internal val terms: GuaranteeTerms get() = GuaranteeTerms(name, type, depositPercent, dueDaysBefore)
}

/** Os dados de um pacote, como chegam da API (RF-TAR-06). */
data class PackageCommand(
    val code: String,
    val name: String,
    val price: Money,
    val pricing: PackagePricing,
    val postingRhythm: PackagePostingRhythm,
    val includedInRate: Boolean,
    val transactionCodeId: TransactionCodeId,
) {
    internal val details: PackageDetails
        get() = PackageDetails(code, name, price, pricing, postingRhythm, includedInRate, transactionCodeId)
}
