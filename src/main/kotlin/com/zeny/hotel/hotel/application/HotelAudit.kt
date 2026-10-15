package com.zeny.hotel.hotel.application

import com.zeny.hotel.hotel.OperationalSettings
import com.zeny.hotel.hotel.PolicySettings
import com.zeny.hotel.hotel.TaxSettings
import com.zeny.hotel.hotel.domain.BusinessDay
import com.zeny.hotel.hotel.domain.Hotel
import java.time.Instant
import java.time.LocalDate

/**
 * Tipos de entidade e retratos do `hotel` na trilha de auditoria (RF-ACS-11). Nada aqui é dado
 * pessoal nem segredo: dados cadastrais de empresa e parâmetros.
 */
internal object HotelAudit {
    const val HOTEL = "hotel.hotel"
    const val POLICY = "hotel.policy"
    const val TAX_SETTINGS = "hotel.tax-settings"
    const val OPERATIONAL_SETTINGS = "hotel.operational-settings"
    const val BUSINESS_DAY = "hotel.business-day"

    const val INITIALIZE = "hotel.hotel.initialize"
    const val UPDATE_HOTEL = "hotel.hotel.update"
    const val UPDATE_POLICY = "hotel.policy.update"
    const val UPDATE_TAX_SETTINGS = "hotel.tax-settings.update"
    const val UPDATE_OPERATIONAL_SETTINGS = "hotel.operational-settings.update"
    const val ADVANCE_BUSINESS_DATE = "hotel.business-date.advance"
}

internal data class HotelAuditState(
    val configured: Boolean,
    val legalName: String?,
    val tradeName: String?,
    val cnpj: String?,
    val municipalRegistration: String?,
    val cadastur: String?,
    val address: Map<String, String?>?,
    val timezone: String,
    val taxRegime: String?,
    val logoFileId: String?,
)

internal fun Hotel.auditState() =
    HotelAuditState(
        configured = configured,
        legalName = legalName,
        tradeName = tradeName,
        cnpj = cnpj?.value,
        municipalRegistration = municipalRegistration,
        cadastur = cadastur,
        address =
            address?.let {
                mapOf(
                    "street" to it.street,
                    "number" to it.number,
                    "complement" to it.complement,
                    "district" to it.district,
                    "city" to it.city,
                    "state" to it.state.name,
                    "postalCode" to it.postalCode,
                    "ibgeCityCode" to it.ibgeCityCode,
                )
            },
        timezone = timezone.id,
        taxRegime = taxRegime?.name,
        logoFileId = logoFileId?.toString(),
    )

internal fun PolicySettings.auditState(): Map<String, Any?> =
    mapOf(
        "checkInTime" to checkInTime.toString(),
        "checkOutTime" to checkOutTime.toString(),
        "cleaningWindowMinutes" to cleaningWindow.toMinutes(),
        "earlyCheckIn" to
            earlyCheckIn?.let { mapOf("limitTime" to it.limitTime.toString(), "fee" to it.fee.toString()) },
        "lateCheckOut" to
            lateCheckOut?.let { mapOf("limitTime" to it.limitTime.toString(), "fee" to it.fee.toString()) },
        "childMaxAge" to childMaxAge,
        "childAgeBands" to
            childAgeBands.map { mapOf("minAge" to it.minAge, "maxAge" to it.maxAge, "label" to it.label) },
        "stayPolicyText" to stayPolicyText,
        "additionalPolicyText" to additionalPolicyText,
    )

internal fun TaxSettings.auditState(): Map<String, Any?> =
    mapOf(
        "serviceChargeRate" to serviceChargeRate.toString(),
        "issRate" to issRate.toString(),
        "tourismFeePerPersonNight" to tourismFeePerPersonNight?.toString(),
    )

internal fun OperationalSettings.auditState(): Map<String, Any?> =
    mapOf(
        "endOfDayTime" to endOfDayTime.toString(),
        "preReservationExpiryHours" to preReservationExpiry.toHours(),
        "preReservationAlertLeadHours" to preReservationAlertLead.toHours(),
        "lostAndFoundRetentionDays" to lostAndFoundRetentionDays,
    )

internal data class BusinessDayAuditState(
    val businessDate: LocalDate,
    val advancedAt: Instant?,
)

internal fun BusinessDay.auditState() = BusinessDayAuditState(current, advancedAt)
