package com.zeny.hotel.rates.application

import com.zeny.hotel.rates.domain.CancellationPolicy
import com.zeny.hotel.rates.domain.DerivationRule
import com.zeny.hotel.rates.domain.GuaranteePolicy
import com.zeny.hotel.rates.domain.PriceSet
import com.zeny.hotel.rates.domain.RatePackage
import com.zeny.hotel.rates.domain.RatePlan
import com.zeny.hotel.rates.domain.RestrictionValues
import com.zeny.hotel.rates.domain.Season

/**
 * Tipos de entidade, ações e retratos do `rates` na trilha de auditoria (RF-ACS-11). Os retratos levam ids, códigos,
 * nomes de configuração, situações e valores; **nunca texto livre** (a descrição do plano entra só como
 * `hasDescription`).
 */
internal object RatesAudit {
    const val RATE_PLAN = "rates.rate-plan"
    const val CANCELLATION_POLICY = "rates.cancellation-policy"
    const val GUARANTEE_POLICY = "rates.guarantee-policy"
    const val PACKAGE = "rates.package"
    const val RATE_PRICES = "rates.rate-price"
    const val SEASON = "rates.season"
    const val RESTRICTIONS = "rates.restriction"

    const val CREATE_RATE_PLAN = "rates.rate-plan.create"
    const val UPDATE_RATE_PLAN = "rates.rate-plan.update"
    const val DEACTIVATE_RATE_PLAN = "rates.rate-plan.deactivate"
    const val REACTIVATE_RATE_PLAN = "rates.rate-plan.reactivate"
    const val CREATE_CANCELLATION_POLICY = "rates.cancellation-policy.create"
    const val UPDATE_CANCELLATION_POLICY = "rates.cancellation-policy.update"
    const val DEACTIVATE_CANCELLATION_POLICY = "rates.cancellation-policy.deactivate"
    const val REACTIVATE_CANCELLATION_POLICY = "rates.cancellation-policy.reactivate"
    const val CREATE_GUARANTEE_POLICY = "rates.guarantee-policy.create"
    const val UPDATE_GUARANTEE_POLICY = "rates.guarantee-policy.update"
    const val DEACTIVATE_GUARANTEE_POLICY = "rates.guarantee-policy.deactivate"
    const val REACTIVATE_GUARANTEE_POLICY = "rates.guarantee-policy.reactivate"
    const val CREATE_PACKAGE = "rates.package.create"
    const val UPDATE_PACKAGE = "rates.package.update"
    const val DEACTIVATE_PACKAGE = "rates.package.deactivate"
    const val REACTIVATE_PACKAGE = "rates.package.reactivate"
    const val UPDATE_PRICES = "rates.rate-price.bulk-update"
    const val REMOVE_PRICES = "rates.rate-price.bulk-remove"
    const val SET_DERIVATION = "rates.rate-plan.set-derivation"
    const val REMOVE_DERIVATION = "rates.rate-plan.remove-derivation"
    const val CREATE_SEASON = "rates.season.create"
    const val UPDATE_SEASON = "rates.season.update"
    const val DEACTIVATE_SEASON = "rates.season.deactivate"
    const val REACTIVATE_SEASON = "rates.season.reactivate"
    const val UPDATE_RESTRICTIONS = "rates.restriction.bulk-update"
}

internal fun RatePlan.auditState(): Map<String, Any?> =
    mapOf(
        "code" to code,
        "name" to name,
        "hasDescription" to (description != null),
        "mealPlan" to mealPlan.name,
        "category" to category.name,
        "channels" to channels.map { it.name }.sorted(),
        "roomTypeIds" to roomTypeIds.map { it.toString() }.sorted(),
        "packageIds" to packageIds.map { it.toString() }.sorted(),
        "cancellationPolicyId" to cancellationPolicyId.toString(),
        "guaranteePolicyId" to guaranteePolicyId.toString(),
        "derivation" to derivation?.auditState(),
        "active" to active,
    )

internal fun DerivationRule.auditState(): Map<String, Any?> {
    val public = toPublic()
    return mapOf(
        "basePlanId" to public.basePlanId.toString(),
        "type" to public.type.name,
        "percent" to public.percent?.toString(),
        "amount" to public.amount?.toString(),
        "perPerson" to public.perPerson,
    )
}

internal fun PriceSet.auditState(): Map<String, Any?> =
    mapOf(
        "oneAdult" to oneAdult?.toString(),
        "twoAdults" to twoAdults?.toString(),
        "extraAdult" to extraAdult?.toString(),
        "children" to
            children.map { mapOf("minAge" to it.minAge, "maxAge" to it.maxAge, "amount" to it.amount.toString()) },
    )

internal fun Season.auditState(): Map<String, Any?> =
    mapOf(
        "name" to name,
        "startsOn" to startsOn.toString(),
        "endsOn" to endsOn.toString(),
        "active" to active,
    )

internal fun CancellationPolicy.auditState(): Map<String, Any?> =
    mapOf(
        "name" to name,
        "deadlineHours" to deadlineHours,
        "penaltyType" to penaltyType.name,
        "penaltyPercent" to penaltyPercent?.toString(),
        "penaltyNights" to penaltyNights,
        "penaltyAmount" to penaltyAmount?.toString(),
        "active" to active,
    )

internal fun GuaranteePolicy.auditState(): Map<String, Any?> =
    mapOf(
        "name" to name,
        "type" to type.name,
        "depositPercent" to depositPercent?.toString(),
        "dueDaysBefore" to dueDaysBefore,
        "active" to active,
    )

internal fun RatePackage.auditState(): Map<String, Any?> =
    mapOf(
        "code" to code,
        "name" to name,
        "price" to price.toString(),
        "pricing" to pricing.name,
        "postingRhythm" to postingRhythm.name,
        "includedInRate" to includedInRate,
        "transactionCodeId" to transactionCodeId.toString(),
        "active" to active,
    )

internal fun RestrictionValues.auditState(): Map<String, Any?> =
    mapOf(
        "closed" to closed,
        "closedToArrival" to closedToArrival,
        "closedToDeparture" to closedToDeparture,
        "minStay" to minStay,
        "maxStay" to maxStay,
        "minAdvanceDays" to minAdvanceDays,
        "maxAdvanceDays" to maxAdvanceDays,
    )
