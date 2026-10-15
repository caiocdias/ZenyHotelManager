package com.zeny.hotel.hotel.domain

import com.zeny.hotel.hotel.HotelErrorCodes
import com.zeny.hotel.hotel.OperationalSettings
import com.zeny.hotel.hotel.TaxSettings
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/**
 * Taxas do hotel (SPEC §8.2.2, RF-CFG-03): taxa de serviço padrão e ISS em [Percent] e taxa de
 * turismo por pessoa/noite em [Money], desligada (nula) por padrão (Q-03).
 */
@Entity
@Table(name = "tax_settings", schema = "hotel")
class HotelTaxes private constructor(
    @Id
    @Column(name = "hotel_id")
    val hotelId: HotelId,
    settings: TaxSettings,
    now: Instant,
) {
    @Column(name = "service_charge_rate", nullable = false)
    private var serviceChargeRate: Percent = settings.serviceChargeRate

    @Column(name = "iss_rate", nullable = false)
    private var issRate: Percent = settings.issRate

    @Column(name = "tourism_fee_per_person_night")
    private var tourismFeePerPersonNight: Money? = settings.tourismFeePerPersonNight

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = now
        protected set

    @Version
    var version: Long? = null
        protected set

    fun settings(): TaxSettings = TaxSettings(serviceChargeRate, issRate, tourismFeePerPersonNight)

    /** Troca as taxas, validadas; devolve `false` se nada mudou. */
    fun update(
        settings: TaxSettings,
        now: Instant,
    ): Boolean {
        validate(settings)
        if (settings == settings()) return false
        serviceChargeRate = settings.serviceChargeRate
        issRate = settings.issRate
        tourismFeePerPersonNight = settings.tourismFeePerPersonNight
        updatedAt = now
        return true
    }

    companion object {
        /** ISS: até 5% (LC 116/2003, art. 8º). */
        val MAX_ISS_RATE: Percent = Percent.of("5")

        /** Sem taxa de serviço, ISS a informar e sem taxa de turismo. */
        val DEFAULTS = TaxSettings(Percent.ZERO, Percent.ZERO, null)

        fun create(
            hotelId: HotelId,
            now: Instant,
        ): HotelTaxes = HotelTaxes(hotelId, DEFAULTS, now)

        private fun validate(settings: TaxSettings) {
            rule(
                settings.serviceChargeRate >= Percent.ZERO && settings.serviceChargeRate <= Percent.HUNDRED,
                HotelErrorCodes.SERVICE_CHARGE_RATE_INVALID,
                "serviceChargeRate",
            ) { "A taxa de serviço vai de 0% a 100%." }
            rule(
                settings.issRate >= Percent.ZERO && settings.issRate <= MAX_ISS_RATE,
                HotelErrorCodes.ISS_RATE_INVALID,
                "issRate",
            ) { "A alíquota de ISS vai de 0% a 5%." }
            rule(
                settings.tourismFeePerPersonNight?.isPositive() ?: true,
                HotelErrorCodes.TOURISM_FEE_INVALID,
                "tourismFeePerPersonNight",
            ) { "A taxa de turismo, quando cobrada, tem valor positivo; deixe-a vazia para não cobrar." }
        }
    }
}

/** Parâmetros operacionais do hotel (RF-CFG-04). */
@Entity
@Table(name = "operational_settings", schema = "hotel")
class HotelOperations private constructor(
    @Id
    @Column(name = "hotel_id")
    val hotelId: HotelId,
    settings: OperationalSettings,
    now: Instant,
) {
    @Column(name = "end_of_day_time", nullable = false)
    private var endOfDayTime: LocalTime = settings.endOfDayTime

    @Column(name = "pre_reservation_expiry_hours", nullable = false)
    private var preReservationExpiryHours: Int = settings.preReservationExpiry.toHours().toInt()

    @Column(name = "pre_reservation_alert_lead_hours", nullable = false)
    private var preReservationAlertLeadHours: Int = settings.preReservationAlertLead.toHours().toInt()

    @Column(name = "lost_and_found_retention_days", nullable = false)
    private var lostAndFoundRetentionDays: Int = settings.lostAndFoundRetentionDays

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = now
        protected set

    @Version
    var version: Long? = null
        protected set

    fun settings(): OperationalSettings =
        OperationalSettings(
            endOfDayTime = endOfDayTime,
            preReservationExpiry = Duration.ofHours(preReservationExpiryHours.toLong()),
            preReservationAlertLead = Duration.ofHours(preReservationAlertLeadHours.toLong()),
            lostAndFoundRetentionDays = lostAndFoundRetentionDays,
        )

    /** Troca os parâmetros, validados; devolve `false` se nada mudou. */
    fun update(
        settings: OperationalSettings,
        now: Instant,
    ): Boolean {
        validate(settings)
        if (settings == settings()) return false
        endOfDayTime = settings.endOfDayTime
        preReservationExpiryHours = settings.preReservationExpiry.toHours().toInt()
        preReservationAlertLeadHours = settings.preReservationAlertLead.toHours().toInt()
        lostAndFoundRetentionDays = settings.lostAndFoundRetentionDays
        updatedAt = now
        return true
    }

    companion object {
        /** Pré-reserva: de 1 hora a 30 dias. */
        const val MAX_PRE_RESERVATION_HOURS = 720L

        /** Achados e perdidos: de 1 dia a 5 anos. */
        const val MAX_RETENTION_DAYS = 1825

        /** Fechamento às 3h, pré-reserva de 48h com alerta 12h antes, achados guardados por 90 dias. */
        val DEFAULTS =
            OperationalSettings(
                endOfDayTime = LocalTime.of(3, 0),
                preReservationExpiry = Duration.ofHours(48),
                preReservationAlertLead = Duration.ofHours(12),
                lostAndFoundRetentionDays = 90,
            )

        fun create(
            hotelId: HotelId,
            now: Instant,
        ): HotelOperations = HotelOperations(hotelId, DEFAULTS, now)

        private fun validate(settings: OperationalSettings) {
            requireWholeMinute(settings.endOfDayTime, "endOfDayTime")
            val expiry = settings.preReservationExpiry
            val lead = settings.preReservationAlertLead
            rule(
                wholeHours(expiry) && expiry.toHours() in 1..MAX_PRE_RESERVATION_HOURS,
                HotelErrorCodes.OPERATIONAL_SETTING_INVALID,
                "preReservationExpiryHours",
            ) { "A validade da pré-reserva vai de 1 a $MAX_PRE_RESERVATION_HOURS horas." }
            rule(
                wholeHours(lead) && !lead.isNegative && lead < expiry,
                HotelErrorCodes.OPERATIONAL_SETTING_INVALID,
                "preReservationAlertLeadHours",
            ) { "O alerta da pré-reserva vem antes do vencimento: de 0 hora até menos que a validade." }
            rule(
                settings.lostAndFoundRetentionDays in 1..MAX_RETENTION_DAYS,
                HotelErrorCodes.OPERATIONAL_SETTING_INVALID,
                "lostAndFoundRetentionDays",
            ) { "A guarda de achados e perdidos vai de 1 a $MAX_RETENTION_DAYS dias." }
        }

        private fun wholeHours(duration: Duration): Boolean = duration == Duration.ofHours(duration.toHours())
    }
}
