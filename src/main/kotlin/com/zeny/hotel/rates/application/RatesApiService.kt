package com.zeny.hotel.rates.application

import com.zeny.hotel.rates.CancellationPolicyId
import com.zeny.hotel.rates.CancellationPolicySummary
import com.zeny.hotel.rates.GuaranteePolicyId
import com.zeny.hotel.rates.GuaranteePolicySummary
import com.zeny.hotel.rates.PackageId
import com.zeny.hotel.rates.PackageSummary
import com.zeny.hotel.rates.RateCalendar
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.RatePlanSummary
import com.zeny.hotel.rates.RatesApi
import com.zeny.hotel.rates.RatesPermissions
import com.zeny.hotel.rates.SalesChannel
import com.zeny.hotel.rates.Sellability
import com.zeny.hotel.rates.StayOption
import com.zeny.hotel.rates.StayOptionsQuery
import com.zeny.hotel.rates.StayPriceResult
import com.zeny.hotel.rates.StayQuery
import com.zeny.hotel.rates.domain.RatePackage
import com.zeny.hotel.rates.domain.RatePlan
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.ModulePermissions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** A [RatesApi] para os outros módulos: leituras, sem conferir permissão (quem chama já se autorizou). */
@Service
class RatesApiService(
    private val store: RatesStore,
    private val stays: StayEvaluator,
) : RatesApi {
    @Transactional(readOnly = true)
    override fun ratePlan(id: RatePlanId): RatePlanSummary? = store.ratePlan(id)?.summary()

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
    override fun ratePackage(id: PackageId): PackageSummary? = store.ratePackage(id)?.summary()

    @Transactional(readOnly = true)
    override fun priceFor(query: StayQuery): StayPriceResult = stays.priceFor(query)

    @Transactional(readOnly = true)
    override fun isSellable(
        query: StayQuery,
        channel: SalesChannel,
    ): Sellability = stays.isSellable(query, channel)

    @Transactional(readOnly = true)
    override fun stayOptions(query: StayOptionsQuery): List<StayOption> = stays.stayOptions(query)

    @Transactional(readOnly = true)
    override fun rateCalendar(
        ratePlanId: RatePlanId,
        roomTypeIds: Collection<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
    ): RateCalendar? = stays.rateCalendar(ratePlanId, roomTypeIds, from, to)
}

/** O plano para os outros módulos (e para a cotação). */
internal fun RatePlan.summary() =
    RatePlanSummary(
        id,
        code,
        name,
        mealPlan,
        category,
        channels,
        roomTypeIds,
        packageIds,
        cancellationPolicyId,
        guaranteePolicyId,
        active,
        checkNotNull(version),
        derivation?.toPublic(),
    )

/** O pacote para os outros módulos (e para o preço de uma estadia). */
internal fun RatePackage.summary() =
    PackageSummary(
        id,
        code,
        name,
        price,
        pricing,
        postingRhythm,
        includedInRate,
        transactionCodeId,
        active,
        checkNotNull(version),
    )

@Configuration(proxyBeanMethods = false)
class RatesPermissionsConfiguration {
    @Bean
    fun ratesPermissions(): ModulePermissions = RatesPermissions
}
