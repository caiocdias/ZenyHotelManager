package com.zeny.hotel.hotel.domain

import com.zeny.hotel.hotel.ChildAgeBand
import com.zeny.hotel.hotel.EarlyLateTerms
import com.zeny.hotel.hotel.HotelErrorCodes
import com.zeny.hotel.hotel.PolicySettings
import com.zeny.hotel.shared.Money
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/**
 * Políticas operacionais do hotel (SPEC §8.2.2, RF-CFG-02), sempre válidas pela RN-11 (Portaria
 * MTur 28/2025): a diária tem 24 horas e, entre o check-out e o check-in do mesmo dia, cabem no
 * máximo 3 horas de arrumação; a janela de arrumação cabe nesse intervalo. Early check-in e late
 * check-out, quando oferecidos, têm horário-limite coerente e preço informado (não negativo).
 */
@Entity
@Table(name = "policy", schema = "hotel")
@Suppress("LongParameterList", "TooManyFunctions") // os campos da política (RF-CFG-02)
class HotelPolicy private constructor(
    @Id
    @Column(name = "hotel_id")
    val hotelId: HotelId,
    settings: PolicySettings,
    now: Instant,
) {
    @Column(name = "check_in_time", nullable = false)
    private var checkInTime: LocalTime = settings.checkInTime

    @Column(name = "check_out_time", nullable = false)
    private var checkOutTime: LocalTime = settings.checkOutTime

    @Column(name = "cleaning_window_minutes", nullable = false)
    private var cleaningWindowMinutes: Int = settings.cleaningWindow.toMinutes().toInt()

    @Column(name = "early_check_in_limit")
    private var earlyCheckInLimit: LocalTime? = settings.earlyCheckIn?.limitTime

    @Column(name = "early_check_in_fee")
    private var earlyCheckInFee: Money? = settings.earlyCheckIn?.fee

    @Column(name = "late_check_out_limit")
    private var lateCheckOutLimit: LocalTime? = settings.lateCheckOut?.limitTime

    @Column(name = "late_check_out_fee")
    private var lateCheckOutFee: Money? = settings.lateCheckOut?.fee

    @Column(name = "child_max_age", nullable = false)
    private var childMaxAge: Int = settings.childMaxAge

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "child_age_band", schema = "hotel", joinColumns = [JoinColumn(name = "hotel_id")])
    @OrderColumn(name = "sort_order")
    private val childAgeBands: MutableList<StoredChildAgeBand> =
        settings.childAgeBands.map(StoredChildAgeBand::of).toMutableList()

    @Column(name = "stay_policy_text", nullable = false)
    private var stayPolicyText: String = settings.stayPolicyText

    @Column(name = "additional_policy_text")
    private var additionalPolicyText: String? = settings.additionalPolicyText

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = now
        protected set

    @Version
    var version: Long? = null
        protected set

    fun settings(): PolicySettings =
        PolicySettings(
            checkInTime = checkInTime,
            checkOutTime = checkOutTime,
            cleaningWindow = Duration.ofMinutes(cleaningWindowMinutes.toLong()),
            earlyCheckIn = terms(earlyCheckInLimit, earlyCheckInFee),
            lateCheckOut = terms(lateCheckOutLimit, lateCheckOutFee),
            childMaxAge = childMaxAge,
            childAgeBands = childAgeBands.map(StoredChildAgeBand::toBand),
            stayPolicyText = stayPolicyText,
            additionalPolicyText = additionalPolicyText,
        )

    /** Troca a política inteira, validada (RN-11); devolve `false` se nada mudou. */
    fun update(
        settings: PolicySettings,
        now: Instant,
    ): Boolean {
        val valid = PolicyRules.validated(settings)
        if (valid == settings()) return false
        checkInTime = valid.checkInTime
        checkOutTime = valid.checkOutTime
        cleaningWindowMinutes = valid.cleaningWindow.toMinutes().toInt()
        earlyCheckInLimit = valid.earlyCheckIn?.limitTime
        earlyCheckInFee = valid.earlyCheckIn?.fee
        lateCheckOutLimit = valid.lateCheckOut?.limitTime
        lateCheckOutFee = valid.lateCheckOut?.fee
        childMaxAge = valid.childMaxAge
        childAgeBands.clear()
        childAgeBands.addAll(valid.childAgeBands.map(StoredChildAgeBand::of))
        stayPolicyText = valid.stayPolicyText
        additionalPolicyText = valid.additionalPolicyText
        updatedAt = now
        return true
    }

    private fun terms(
        limit: LocalTime?,
        fee: Money?,
    ): EarlyLateTerms? = if (limit != null && fee != null) EarlyLateTerms(limit, fee) else null

    companion object {
        /**
         * A política inicial: check-in às 14h, check-out às 12h, 2 horas de arrumação, sem early nem
         * late, criança até 12 anos, sem faixas, e um texto das confirmações com essas regras.
         */
        val DEFAULTS =
            PolicySettings(
                checkInTime = LocalTime.of(14, 0),
                checkOutTime = LocalTime.of(12, 0),
                cleaningWindow = Duration.ofHours(2),
                earlyCheckIn = null,
                lateCheckOut = null,
                childMaxAge = 12,
                childAgeBands = emptyList(),
                stayPolicyText =
                    "A diária tem 24 horas: check-in a partir das 14h e check-out até as 12h. As 2 horas entre " +
                        "eles são reservadas à arrumação da UH, sem cobrança à parte.",
                additionalPolicyText = null,
            )

        fun create(
            hotelId: HotelId,
            now: Instant,
        ): HotelPolicy = HotelPolicy(hotelId, PolicyRules.validated(DEFAULTS), now)
    }
}

/** Faixa etária gravada (`hotel.child_age_band`), na ordem da política. */
@Embeddable
class StoredChildAgeBand(
    @Column(name = "min_age", nullable = false)
    val minAge: Int,
    @Column(name = "max_age", nullable = false)
    val maxAge: Int,
    @Column(nullable = false)
    val label: String,
) {
    fun toBand() = ChildAgeBand(minAge, maxAge, label)

    companion object {
        fun of(band: ChildAgeBand) = StoredChildAgeBand(band.minAge, band.maxAge, band.label)
    }
}

/** Validação das políticas (RN-11). Os campos de erro são os nomes da API. */
internal object PolicyRules {
    /** Máximo entre o check-out e o check-in (e da janela de arrumação): 3 horas (RN-11). */
    val MAX_CLEANING_WINDOW: Duration = Duration.ofHours(3)
    const val MAX_CHILD_AGE = 17
    const val MAX_AGE_BANDS = 10
    const val LABEL_MAX_LENGTH = 50
    const val TEXT_MAX_LENGTH = 4000

    /** A política com os textos aparados, ou `400` com o código da regra violada. */
    fun validated(settings: PolicySettings): PolicySettings {
        val normalized =
            settings.copy(
                childAgeBands = settings.childAgeBands.map { it.copy(label = it.label.trim()) },
                stayPolicyText = settings.stayPolicyText.trim(),
                additionalPolicyText = settings.additionalPolicyText?.trim()?.ifEmpty { null },
            )
        checkTimes(normalized)
        checkEarlyLate(normalized)
        checkChildAges(normalized)
        checkTexts(normalized)
        return normalized
    }

    private fun checkTimes(settings: PolicySettings) {
        requireWholeMinute(settings.checkInTime, "checkInTime")
        requireWholeMinute(settings.checkOutTime, "checkOutTime")
        requireWholeMinute(settings.earlyCheckIn?.limitTime, "earlyCheckIn.limitTime")
        requireWholeMinute(settings.lateCheckOut?.limitTime, "lateCheckOut.limitTime")
        rule(settings.checkOutTime < settings.checkInTime, HotelErrorCodes.CHECK_TIMES_INCOHERENT, "checkInTime") {
            "O check-out precisa ser antes do check-in, no mesmo dia."
        }
        val gap = Duration.between(settings.checkOutTime, settings.checkInTime)
        val window = settings.cleaningWindow
        rule(window <= MAX_CLEANING_WINDOW, HotelErrorCodes.CLEANING_WINDOW_TOO_LONG, "cleaningWindowMinutes") {
            "A janela de arrumação tem no máximo 3 horas (Portaria MTur 28/2025)."
        }
        rule(gap <= MAX_CLEANING_WINDOW, HotelErrorCodes.CLEANING_WINDOW_TOO_LONG, "checkInTime") {
            "Entre o check-out e o check-in cabem no máximo 3 horas: a diária tem 24 horas (Portaria MTur 28/2025)."
        }
        rule(window.isPositive && window <= gap, HotelErrorCodes.CLEANING_WINDOW_INVALID, "cleaningWindowMinutes") {
            "A janela de arrumação precisa ter ao menos 1 minuto e caber entre o check-out e o check-in."
        }
    }

    private fun checkEarlyLate(settings: PolicySettings) {
        settings.earlyCheckIn?.let { early ->
            rule(
                early.limitTime < settings.checkInTime,
                HotelErrorCodes.EARLY_CHECK_IN_INVALID,
                "earlyCheckIn.limitTime",
            ) {
                "O early check-in começa antes do horário de check-in."
            }
            rule(!early.fee.isNegative(), HotelErrorCodes.EARLY_CHECK_IN_INVALID, "earlyCheckIn.fee") {
                "O preço do early check-in não pode ser negativo."
            }
        }
        settings.lateCheckOut?.let { late ->
            rule(
                late.limitTime > settings.checkOutTime,
                HotelErrorCodes.LATE_CHECK_OUT_INVALID,
                "lateCheckOut.limitTime",
            ) {
                "O late check-out termina depois do horário de check-out."
            }
            rule(!late.fee.isNegative(), HotelErrorCodes.LATE_CHECK_OUT_INVALID, "lateCheckOut.fee") {
                "O preço do late check-out não pode ser negativo."
            }
        }
    }

    private fun checkChildAges(settings: PolicySettings) {
        val maxAge = settings.childMaxAge
        rule(maxAge in 0..MAX_CHILD_AGE, HotelErrorCodes.CHILD_AGES_INVALID, "childMaxAge") {
            "A idade limite de criança vai de 0 a $MAX_CHILD_AGE anos."
        }
        val bands = settings.childAgeBands
        if (bands.isEmpty()) return
        rule(bands.size <= MAX_AGE_BANDS, HotelErrorCodes.CHILD_AGES_INVALID, "childAgeBands") {
            "Use no máximo $MAX_AGE_BANDS faixas etárias."
        }
        var expectedMin = 0
        bands.forEachIndexed { index, band ->
            rule(
                band.minAge == expectedMin && band.maxAge >= band.minAge && band.maxAge <= maxAge,
                HotelErrorCodes.CHILD_AGES_INVALID,
                "childAgeBands[$index]",
            ) { "As faixas etárias vão de 0 à idade limite de criança, em ordem, sem lacunas nem sobreposição." }
            rule(
                band.label.isNotEmpty() && band.label.length <= LABEL_MAX_LENGTH,
                HotelErrorCodes.CHILD_AGES_INVALID,
                "childAgeBands[$index].label",
            ) { "Dê um nome à faixa etária, com até $LABEL_MAX_LENGTH caracteres." }
            expectedMin = band.maxAge + 1
        }
        rule(bands.last().maxAge == maxAge, HotelErrorCodes.CHILD_AGES_INVALID, "childAgeBands") {
            "A última faixa etária termina na idade limite de criança."
        }
    }

    private fun checkTexts(settings: PolicySettings) {
        rule(
            settings.stayPolicyText.isNotEmpty() && settings.stayPolicyText.length <= TEXT_MAX_LENGTH,
            HotelErrorCodes.POLICY_TEXT_REQUIRED,
            "stayPolicyText",
        ) { "Informe o texto de política das confirmações, com até $TEXT_MAX_LENGTH caracteres." }
        rule(
            (settings.additionalPolicyText?.length ?: 0) <= TEXT_MAX_LENGTH,
            HotelErrorCodes.POLICY_TEXT_REQUIRED,
            "additionalPolicyText",
        ) { "Use até $TEXT_MAX_LENGTH caracteres." }
    }
}
