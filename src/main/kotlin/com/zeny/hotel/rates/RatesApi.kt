package com.zeny.hotel.rates

import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UuidV7
import java.util.UUID

/**
 * API pública do `rates` para os módulos de cima (`reservations` no M4, `distribution` no M8): leituras de planos,
 * políticas e pacotes, ativos ou não. **Não confere permissão** (quem chama já se autorizou). O preço e a
 * vendabilidade (`priceFor`, `isSellable`) entram na E04.
 *
 * A reserva **copia** os termos das políticas na criação (RN-15, M4 E07): a política editada depois (que vale na hora
 * para as reservas novas; decisão do usuário em 2026-10-20) não muda as reservas existentes.
 */
interface RatesApi {
    /** O plano tarifário, ativo ou não; `null` se não existe. */
    fun ratePlan(id: RatePlanId): RatePlanSummary?

    /** A política de cancelamento, ativa ou não; `null` se não existe. */
    fun cancellationPolicy(id: CancellationPolicyId): CancellationPolicySummary?

    /** A política de garantia, ativa ou não; `null` se não existe. */
    fun guaranteePolicy(id: GuaranteePolicyId): GuaranteePolicySummary?

    /** O pacote, ativo ou não; `null` se não existe. */
    fun ratePackage(id: PackageId): PackageSummary?
}

/** Id de um plano tarifário. */
@JvmInline
value class RatePlanId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): RatePlanId = RatePlanId(UuidV7.generate())
    }
}

/** Id de uma temporada (atalho de período para a edição em massa de preços, RF-TAR-04). */
@JvmInline
value class SeasonId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): SeasonId = SeasonId(UuidV7.generate())
    }
}

/** Id de uma política de cancelamento. */
@JvmInline
value class CancellationPolicyId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): CancellationPolicyId = CancellationPolicyId(UuidV7.generate())
    }
}

/** Id de uma política de garantia. */
@JvmInline
value class GuaranteePolicyId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): GuaranteePolicyId = GuaranteePolicyId(UuidV7.generate())
    }
}

/** Id de um pacote (serviço vendido com a diária). */
@JvmInline
value class PackageId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): PackageId = PackageId(UuidV7.generate())
    }
}

/**
 * Regime de alimentação do plano (RF-TAR-01; decisão do usuário em 2026-10-20: os três da SPEC). Nas respostas é
 * `x-extensible-enum`: um regime novo não é breaking change.
 */
enum class MealPlan {
    /** Sem café da manhã. */
    ROOM_ONLY,

    /** Com café da manhã. */
    BREAKFAST,

    /** Meia pensão (café e uma refeição). */
    HALF_BOARD,
}

/**
 * Canal em que o plano é vendido (RF-TAR-01; decisão do usuário em 2026-10-20: lista fixa). A OTA específica (Booking,
 * Expedia…) é a origem da reserva, mapeada no M8 pelo `distribution`. Nas respostas é `x-extensible-enum`.
 */
enum class SalesChannel {
    /** Venda direta: balcão, telefone, WhatsApp. */
    DIRECT,

    /** Motor de reservas do site do hotel. */
    WEBSITE,

    /** Agências on-line, pelo channel manager (M8). */
    OTA,
}

/**
 * Categoria do plano (SPEC Q-15), fixa desde a criação: a reclassificação mudaria os indicadores das estadias já
 * feitas. A cortesia conta na ocupação e não no ADR; o uso da casa não conta em nenhum dos dois (M6/M10).
 */
enum class RatePlanCategory {
    NORMAL,
    COURTESY,
    HOUSE_USE,
}

/** Como a multa de cancelamento é calculada (RF-TAR-07). */
enum class CancellationPenaltyType {
    /** Percentual do valor da estadia (`penaltyPercent`, de 0 a 100). */
    PERCENT_OF_STAY,

    /** Número de diárias (`penaltyNights`), até o número de noites da estadia. */
    NIGHTS,

    /** Valor fixo (`penaltyAmount`). */
    FIXED_AMOUNT,
}

/** Tipo de garantia exigida pela política (RF-TAR-07). Nas respostas é `x-extensible-enum` (o M7 integra o cartão). */
enum class GuaranteeType {
    /** Sem garantia. */
    NONE,

    /** Cartão de crédito (registrado manualmente até o M7). */
    CARD,

    /** Depósito de `depositPercent` da estadia até `dueDaysBefore` dias antes da chegada. */
    DEPOSIT,
}

/** Base de preço do pacote (RF-TAR-06). */
enum class PackagePricing {
    /** Por noite da estadia. */
    PER_NIGHT,

    /** Uma vez por estadia (lançado só na chegada). */
    PER_STAY,

    /** Por pessoa e por noite. */
    PER_PERSON_PER_NIGHT,
}

/** Ritmo de lançamento do pacote (RF-TAR-06): todo dia (fechamento, M6) ou uma vez na chegada (check-in, M5). */
enum class PackagePostingRhythm {
    DAILY,
    ON_ARRIVAL,
}

/** Como um plano derivado ajusta o preço do plano base (RF-TAR-03). */
enum class RateAdjustmentType {
    /** Percentual sobre cada valor do base (`percent`, de -99,9999 a 999,9999; negativo = desconto). */
    PERCENT,

    /** Valor somado (negativo = desconto), por UH ou por pessoa (`amount`, `perPerson`). */
    AMOUNT,
}

/**
 * A regra de um plano derivado (RF-TAR-03, H1): os preços dele são os do plano [basePlanId] ajustados, calculados na
 * leitura (nunca gravados). Só um dos dois vem preenchido, conforme o [type]: [percent] no `PERCENT`; [amount] e
 * [perPerson] no `AMOUNT`.
 */
data class RatePlanDerivation(
    val basePlanId: RatePlanId,
    val type: RateAdjustmentType,
    val percent: Percent?,
    val amount: Money?,
    val perPerson: Boolean?,
)

/** Um plano tarifário para os módulos de cima. */
data class RatePlanSummary(
    val id: RatePlanId,
    val code: String,
    val name: String,
    val mealPlan: MealPlan,
    val category: RatePlanCategory,
    val channels: Set<SalesChannel>,
    val roomTypeIds: Set<RoomTypeId>,
    val packageIds: Set<PackageId>,
    val cancellationPolicyId: CancellationPolicyId,
    val guaranteePolicyId: GuaranteePolicyId,
    val active: Boolean,
    val version: Long,
    /** A regra de derivação (E02); nula num plano com preços próprios. */
    val derivation: RatePlanDerivation? = null,
)

/**
 * Os termos de uma política de cancelamento (RF-TAR-07, H15): a multa vale para o cancelamento feito a menos de
 * [deadlineHours] horas do horário de check-in da data de chegada (no fuso do hotel); só o valor do [penaltyType]
 * vem preenchido. [version] identifica os termos copiados pela reserva (RN-15).
 */
data class CancellationPolicySummary(
    val id: CancellationPolicyId,
    val name: String,
    val deadlineHours: Int,
    val penaltyType: CancellationPenaltyType,
    val penaltyPercent: Percent?,
    val penaltyNights: Int?,
    val penaltyAmount: Money?,
    val active: Boolean,
    val version: Long,
)

/** Os termos de uma política de garantia (RF-TAR-07): no depósito, [depositPercent] até [dueDaysBefore] dias antes. */
data class GuaranteePolicySummary(
    val id: GuaranteePolicyId,
    val name: String,
    val type: GuaranteeType,
    val depositPercent: Percent?,
    val dueDaysBefore: Int?,
    val active: Boolean,
    val version: Long,
)

/** Um pacote (RF-TAR-06), lançado no código de lançamento [transactionCodeId] do `billing`. */
data class PackageSummary(
    val id: PackageId,
    val code: String,
    val name: String,
    val price: Money,
    val pricing: PackagePricing,
    val postingRhythm: PackagePostingRhythm,
    val includedInRate: Boolean,
    val transactionCodeId: TransactionCodeId,
    val active: Boolean,
    val version: Long,
)
