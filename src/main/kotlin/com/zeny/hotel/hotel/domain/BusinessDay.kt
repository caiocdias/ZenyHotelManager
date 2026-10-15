package com.zeny.hotel.hotel.domain

import com.zeny.hotel.hotel.BusinessDateAdvanced
import com.zeny.hotel.hotel.HotelErrorCodes
import com.zeny.hotel.shared.ConflictException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate

/**
 * A data operacional do hotel (RN-01, glossário: `BusinessDay`): a data de negócio a que
 * lançamentos, diárias e estatísticas se referem. Nasce com o hotel e **só** avança por
 * [advance], exatamente um dia, a partir da data que quem avança espera (o fechamento do dia, M6);
 * não volta nem pula. O banco recusa qualquer outra mudança da data (trigger em
 * `hotel.business_day`).
 */
@Entity
@Table(name = "business_day", schema = "hotel")
class BusinessDay private constructor(
    @Id
    @Column(name = "hotel_id")
    val hotelId: HotelId,
    businessDate: LocalDate,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Column(name = "business_date", nullable = false)
    var current: LocalDate = businessDate
        protected set

    /** Quando a data avançou pela última vez (nulo até o primeiro fechamento). */
    @Column(name = "advanced_at")
    var advancedAt: Instant? = null
        protected set

    @Version
    var version: Long? = null
        protected set

    /**
     * Avança de [expectedCurrent] para o dia seguinte. Se a data corrente não for [expectedCurrent],
     * `409 BUSINESS_DATE_MISMATCH` sem mudar nada: uma repetição do mesmo fechamento não avança de
     * novo.
     */
    fun advance(
        expectedCurrent: LocalDate,
        now: Instant,
    ): BusinessDateAdvanced {
        if (expectedCurrent != current) {
            throw ConflictException(
                HotelErrorCodes.BUSINESS_DATE_MISMATCH,
                "A data operacional atual é $current, não $expectedCurrent: ela só avança a partir da data atual.",
            )
        }
        val previous = current
        current = previous.plusDays(1)
        advancedAt = now
        return BusinessDateAdvanced(previous, current, now)
    }

    companion object {
        /** A data operacional inicial do hotel, criada com ele. */
        fun start(
            hotelId: HotelId,
            businessDate: LocalDate,
            now: Instant,
        ): BusinessDay = BusinessDay(hotelId, businessDate, now)
    }
}
