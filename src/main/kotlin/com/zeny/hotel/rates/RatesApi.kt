package com.zeny.hotel.rates

import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UuidV7
import java.time.LocalDate
import java.util.UUID

/**
 * API pública do `rates` para os módulos de cima (`reservations` no M4, `distribution` no M8): leituras de planos,
 * políticas e pacotes, ativos ou não, e, desde a E04, o preço e a vendabilidade de uma estadia ([priceFor],
 * [isSellable]). **Não confere permissão** (quem chama já se autorizou).
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

    /**
     * O preço da estadia [query] no plano (M4 E04, RF-TAR-02, 03 e 06, RN-02): o detalhe de cada noite e os totais,
     * cuja soma é sempre a das noites (`StayPrice`). Usa os preços **efetivos** do tipo de UH (num derivado, os do
     * base ajustados na leitura, H1), as faixas de criança **atuais** do hotel (H2) e os pacotes do plano (o incluso é
     * parte da diária e separado da hospedagem; o adicional soma ao total; o por estadia entra uma vez, na noite da
     * chegada). Não confere se o plano está ativo, o canal, as restrições nem a ocupação máxima ([isSellable]).
     *
     * Sem preço, devolve `StayPriceResult.NotPriced` com os motivos (`SaleRefusal`): `UNKNOWN_RATE_PLAN`,
     * `ROOM_TYPE_NOT_IN_PLAN`, `RATE_NOT_PRICED`, `OCCUPANCY_NOT_PRICED`, `CHILD_AGE_NOT_PRICED`,
     * `INCLUDED_PACKAGES_EXCEED_RATE`. Estadia fora do formato: lança `400 STAY_INVALID` com os campos.
     */
    fun priceFor(query: StayQuery): StayPriceResult

    /**
     * Se a estadia [query] pode ser vendida no [channel] (M4 E04, RF-TAR-01 e 05, H15), na data operacional do hotel
     * (`HotelApi.currentBusinessDate()`, nunca a do relógio). Devolve **todos** os motivos de recusa (`Sellability`),
     * nesta ordem: `UNKNOWN_RATE_PLAN` ou `UNKNOWN_ROOM_TYPE` (sozinhos); `RATE_PLAN_INACTIVE`,
     * `SALES_CHANNEL_NOT_ALLOWED`, `ROOM_TYPE_NOT_IN_PLAN`, `ROOM_TYPE_INACTIVE` (desativado no `rooms`),
     * `OCCUPANCY_EXCEEDS_ROOM_TYPE` (mais adultos ou crianças que a ocupação máxima do tipo);
     * `ARRIVAL_BEFORE_BUSINESS_DATE` ou `MIN_ADVANCE_NOT_MET`/`MAX_ADVANCE_EXCEEDED`; `CLOSED_TO_ARRIVAL`,
     * `CLOSED_TO_DEPARTURE`, `MIN_STAY_NOT_MET`, `MAX_STAY_EXCEEDED`, `STAY_DATE_CLOSED`; e, com o tipo no plano, os
     * motivos de [priceFor] (vendável implica ter preço). As restrições seguem a semântica de [RestrictionField].
     * Estadia fora do formato: `400 STAY_INVALID`. Não confere disponibilidade (inventário, `reservations`).
     */
    fun isSellable(
        query: StayQuery,
        channel: SalesChannel,
    ): Sellability

    /**
     * A cotação (M4 E06, RF-TAR-11): avalia de uma vez a estadia de [query] em cada combinação de plano e tipo de UH,
     * com a vendabilidade de [isSellable] e o preço de [priceFor] (os mesmos resultados, pelas mesmas regras), lendo o
     * plano, as restrições e os preços de cada plano uma vez só (RNF-06). Os planos são os de `ratePlanIds`
     * (inexistente: lança `400 UNKNOWN_RATE_PLAN`, campo `ratePlanIds`; desativado entra, recusado com
     * `RATE_PLAN_INACTIVE`) ou, sem
     * eles, todos os **ativos** que têm algum dos tipos; as combinações são cada plano com cada tipo pedido **que está
     * nele**, em ordem de código do plano e de id do tipo. Estadia fora do formato: `400 STAY_INVALID` (mesmo sem
     * nenhuma combinação). Não confere disponibilidade (inventário, `reservations`).
     */
    fun stayOptions(query: StayOptionsQuery): List<StayOption>

    /**
     * Os preços e as restrições **efetivos** do plano [ratePlanId] (ativo ou não) nos tipos de UH [roomTypeIds] que
     * estão nele, de [from] a [to] (inclusive, até 366 dias), para o calendário de disponibilidade (M4 E06, RF-TAR-08):
     * os preços como os vê o cálculo de [priceFor] (num derivado, os do base ajustados; as faixas de criança atuais) e
     * as restrições como as vê [isSellable] (a do tipo sobre a geral). `null` se o plano não existe.
     */
    fun rateCalendar(
        ratePlanId: RatePlanId,
        roomTypeIds: Collection<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
    ): RateCalendar?
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
 * Os campos de uma restrição de venda (RF-TAR-05, SPEC §8.2.5; M4 E03), na ordem da SPEC. A semântica, registrada para
 * o channel manager do M8 (decisões do usuário em 2026-10-20), é a da avaliação da E04:
 *
 * - [CLOSED]: fechado para venda; vale em **cada noite** da estadia (da chegada à véspera da saída);
 * - [CLOSED_TO_ARRIVAL] (CTA): impede **chegar** naquela data (só a data de chegada; as noites do meio não importam);
 * - [CLOSED_TO_DEPARTURE] (CTD): impede **sair** naquela data (só a data de saída, que não é noite da estadia);
 * - [MIN_STAY] e [MAX_STAY]: noites, valem as da **data de chegada** ("on arrival"), não as de cada noite;
 * - [MIN_ADVANCE_DAYS] e [MAX_ADVANCE_DAYS]: dias da data operacional até a chegada (H15), valem as da data de chegada.
 */
enum class RestrictionField {
    CLOSED,
    CLOSED_TO_ARRIVAL,
    CLOSED_TO_DEPARTURE,
    MIN_STAY,
    MAX_STAY,
    MIN_ADVANCE_DAYS,
    MAX_ADVANCE_DAYS,
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
