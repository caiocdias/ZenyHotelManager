package com.zeny.hotel.rates.application

import com.zeny.hotel.rates.CancellationPolicyId
import com.zeny.hotel.rates.CancellationPolicySummary
import com.zeny.hotel.rates.GuaranteePolicyId
import com.zeny.hotel.rates.GuaranteePolicySummary
import com.zeny.hotel.rates.PackageId
import com.zeny.hotel.rates.PackageSummary
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.RatePlanSummary
import com.zeny.hotel.rates.RatesApi
import com.zeny.hotel.rates.RatesPermissions
import com.zeny.hotel.shared.ModulePermissions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** A [RatesApi] para os outros módulos: leituras, sem conferir permissão (quem chama já se autorizou). */
@Service
class RatesApiService(
    private val store: RatesStore,
) : RatesApi {
    @Transactional(readOnly = true)
    override fun ratePlan(id: RatePlanId): RatePlanSummary? =
        store.ratePlan(id)?.let {
            RatePlanSummary(
                it.id,
                it.code,
                it.name,
                it.mealPlan,
                it.category,
                it.channels,
                it.roomTypeIds,
                it.packageIds,
                it.cancellationPolicyId,
                it.guaranteePolicyId,
                it.active,
                checkNotNull(it.version),
                it.derivation?.toPublic(),
            )
        }

    @Transactional(readOnly = true)
    override fun cancellationPolicy(id: CancellationPolicyId): CancellationPolicySummary? =
        store.cancellationPolicy(id)?.let {
            CancellationPolicySummary(
                it.id,
                it.name,
                it.deadlineHours,
                it.penaltyType,
                it.penaltyPercent,
                it.penaltyNights,
                it.penaltyAmount,
                it.active,
                checkNotNull(it.version),
            )
        }

    @Transactional(readOnly = true)
    override fun guaranteePolicy(id: GuaranteePolicyId): GuaranteePolicySummary? =
        store.guaranteePolicy(id)?.let {
            GuaranteePolicySummary(
                it.id,
                it.name,
                it.type,
                it.depositPercent,
                it.dueDaysBefore,
                it.active,
                checkNotNull(it.version),
            )
        }

    @Transactional(readOnly = true)
    override fun ratePackage(id: PackageId): PackageSummary? =
        store.ratePackage(id)?.let {
            PackageSummary(
                it.id,
                it.code,
                it.name,
                it.price,
                it.pricing,
                it.postingRhythm,
                it.includedInRate,
                it.transactionCodeId,
                it.active,
                checkNotNull(it.version),
            )
        }
}

@Configuration(proxyBeanMethods = false)
class RatesPermissionsConfiguration {
    @Bean
    fun ratesPermissions(): ModulePermissions = RatesPermissions
}
