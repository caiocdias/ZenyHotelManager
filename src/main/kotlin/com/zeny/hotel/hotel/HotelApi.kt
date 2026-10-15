package com.zeny.hotel.hotel

import com.zeny.hotel.shared.Address
import com.zeny.hotel.shared.Cnpj
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * API pública do módulo `hotel` para os outros módulos (ARCHITECTURE §3.1): o fuso, as políticas,
 * as taxas, os parâmetros e a data operacional do hotel único, sem checar permissão (quem chama
 * é um caso de uso que já se autorizou).
 *
 * A data operacional (RN-01) só avança por [advanceBusinessDate], chamada pelo fechamento do dia
 * (`endofday`, M6); não há endpoint que a avance.
 */
interface HotelApi {
    /** O fuso do hotel (RNF-14): onde os instantes em UTC são exibidos. */
    fun timezone(): ZoneId

    /** A data operacional corrente (RN-01, RF-CFG-05). */
    fun currentBusinessDate(): LocalDate

    /** As políticas operacionais vigentes (RF-CFG-02). */
    fun policy(): PolicySettings

    /** As taxas vigentes (RF-CFG-03): a taxa de serviço aqui é a padrão do hotel. */
    fun taxSettings(): TaxSettings

    /** Os parâmetros operacionais vigentes (RF-CFG-04). */
    fun operationalSettings(): OperationalSettings

    /**
     * Avança a data operacional **exatamente um dia**, de [expectedCurrent] para o dia seguinte, e
     * publica [BusinessDateAdvanced] pelo outbox, na transação de quem chama (ou numa nova). Se a
     * data corrente não for [expectedCurrent] (outro fechamento já avançou, ou a data esperada está
     * errada), falha com `409 BUSINESS_DATE_MISMATCH` sem efeito. Auditada como
     * `hotel.business-date.advance`.
     */
    fun advanceBusinessDate(expectedCurrent: LocalDate): BusinessDateAdvanced
}

/** Regime tributário do hotel (RF-CFG-01, Q-02): configurável; o middleware fiscal resolve o resto. */
enum class TaxRegime {
    SIMPLES_NACIONAL,
    LUCRO_PRESUMIDO,
    LUCRO_REAL,
}

/**
 * Dados cadastrais do hotel (RF-CFG-01). O logotipo é o id de um anexo do módulo `files` da
 * finalidade [HotelFilePurposes.LOGO] (H8), conferido ao salvar.
 */
data class HotelProfile(
    val legalName: String,
    val tradeName: String,
    val cnpj: Cnpj,
    val municipalRegistration: String?,
    val cadastur: String?,
    val address: Address,
    val timezone: ZoneId,
    val taxRegime: TaxRegime,
    val logoFileId: UUID?,
)

/** Early check-in ou late check-out oferecido (RN-11): até/desde que horário e o preço informado antes. */
data class EarlyLateTerms(
    /** Early check-in: o horário mais cedo; late check-out: o mais tarde. */
    val limitTime: LocalTime,
    val fee: Money,
)

/** Faixa etária de criança, em anos completos, de [minAge] a [maxAge] inclusive. */
data class ChildAgeBand(
    val minAge: Int,
    val maxAge: Int,
    val label: String,
)

/**
 * Políticas operacionais (RF-CFG-02, RN-11, Portaria MTur 28/2025): a diária tem 24 horas; entre o
 * check-out e o check-in fica a janela de arrumação, de até 3 horas. Early e late, quando
 * oferecidos (não nulos), têm preço informado antes.
 */
data class PolicySettings(
    val checkInTime: LocalTime,
    val checkOutTime: LocalTime,
    val cleaningWindow: Duration,
    val earlyCheckIn: EarlyLateTerms?,
    val lateCheckOut: EarlyLateTerms?,
    /** Idade (anos completos) até a qual o hóspede é criança. */
    val childMaxAge: Int,
    /** Faixas de 0 a [childMaxAge], contíguas e em ordem; vazia se o hotel não diferencia. */
    val childAgeBands: List<ChildAgeBand>,
    /** Texto obrigatório das confirmações: regras da diária, horários e arrumação (RN-11). */
    val stayPolicyText: String,
    /** Texto complementar das confirmações (cancelamento, crianças, animais…). */
    val additionalPolicyText: String?,
)

/**
 * Taxas (RF-CFG-03). [serviceChargeRate] é a taxa de serviço padrão do hotel: pontos de venda e
 * códigos de lançamento (M3/M8) guardam a própria, quando diferente, e caem nesta quando não têm.
 * A taxa de turismo por pessoa/noite fica desligada (nula) até o município a exigir (Q-03).
 */
data class TaxSettings(
    val serviceChargeRate: Percent,
    val issRate: Percent,
    val tourismFeePerPersonNight: Money?,
)

/** Parâmetros operacionais (RF-CFG-04). */
data class OperationalSettings(
    /** Horário, no fuso do hotel, em que o fechamento do dia roda (M6). */
    val endOfDayTime: LocalTime,
    /** Validade de uma pré-reserva (RN-23). */
    val preReservationExpiry: Duration,
    /** Antecedência do alerta de pré-reserva perto do vencimento (limiar de alerta, RF-RES). */
    val preReservationAlertLead: Duration,
    /** Prazo de guarda de achados e perdidos, em dias (RF-GOV-08). */
    val lostAndFoundRetentionDays: Int,
)
