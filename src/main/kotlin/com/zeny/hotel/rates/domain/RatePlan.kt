package com.zeny.hotel.rates.domain

import com.zeny.hotel.rates.CancellationPolicyId
import com.zeny.hotel.rates.GuaranteePolicyId
import com.zeny.hotel.rates.MealPlan
import com.zeny.hotel.rates.PackageId
import com.zeny.hotel.rates.RateAdjustmentType
import com.zeny.hotel.rates.RatePlanCategory
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.SalesChannel
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import org.hibernate.annotations.BatchSize
import java.time.Instant
import java.util.UUID

/** Os dados editáveis de um plano tarifário (a categoria é fixa desde a criação). */
data class RatePlanDetails(
    val code: String,
    val name: String,
    val description: String?,
    val mealPlan: MealPlan,
    val channels: Set<SalesChannel>,
    val roomTypeIds: Set<RoomTypeId>,
    val packageIds: Set<PackageId>,
    val cancellationPolicyId: CancellationPolicyId,
    val guaranteePolicyId: GuaranteePolicyId,
)

/**
 * Plano tarifário (RF-TAR-01, SPEC §8.2.5): código único em maiúsculas, nome, descrição, regime de alimentação, os
 * canais em que é vendido (ao menos um), os tipos de UH a que se aplica (ao menos um), os pacotes, a política de
 * cancelamento e a de garantia e a [category] (normal, cortesia, uso da casa; Q-15), fixa desde a criação. As
 * referências são conferidas pelo caso de uso: tipos de UH pela `RoomsApi`; políticas e pacotes no próprio módulo. Os
 * preços (E02, tabela `rates.rate_price`) e as restrições (E03) ficam à parte. Nunca é apagado, só desativado.
 *
 * Um plano **derivado** (RF-TAR-03, H1; E02) tem a [derivation]: os preços dele são os do plano base ajustados,
 * calculados na leitura, e nunca são gravados nele. A derivação muda só pelos casos de uso próprios (com as regras de
 * ciclo e de profundidade, [Derivations]), nunca pelo `PUT` do plano.
 */
@Entity
@Table(name = "rate_plan", schema = "rates")
class RatePlan private constructor(
    @Id
    val id: RatePlanId,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    val category: RatePlanCategory,
    details: RatePlanDetails,
    createdAt: Instant,
) : RatesRecord(createdAt) {
    @Column(nullable = false)
    var code: String = ""
        protected set

    @Column(nullable = false)
    var name: String = ""
        protected set

    var description: String? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "meal_plan", nullable = false)
    var mealPlan: MealPlan = details.mealPlan
        protected set

    @Column(name = "cancellation_policy_id", nullable = false)
    var cancellationPolicyId: CancellationPolicyId = details.cancellationPolicyId
        protected set

    @Column(name = "guarantee_policy_id", nullable = false)
    var guaranteePolicyId: GuaranteePolicyId = details.guaranteePolicyId
        protected set

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "rate_plan_channel", schema = "rates", joinColumns = [JoinColumn(name = "rate_plan_id")])
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false)
    @BatchSize(size = BATCH)
    private val channelSet: MutableSet<SalesChannel> = mutableSetOf()

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "rate_plan_room_type", schema = "rates", joinColumns = [JoinColumn(name = "rate_plan_id")])
    @Column(name = "room_type_id", nullable = false)
    @Convert(converter = RoomTypeIdConverter::class)
    @BatchSize(size = BATCH)
    private val roomTypeIdSet: MutableSet<RoomTypeId> = mutableSetOf()

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "rate_plan_package", schema = "rates", joinColumns = [JoinColumn(name = "rate_plan_id")])
    @Column(name = "package_id", nullable = false)
    @Convert(converter = PackageIdConverter::class)
    @BatchSize(size = BATCH)
    private val packageIdSet: MutableSet<PackageId> = mutableSetOf()

    @Column(name = "base_rate_plan_id")
    private var baseRatePlanValue: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "derivation_type")
    private var derivationType: RateAdjustmentType? = null

    @Column(name = "derivation_percent")
    private var derivationPercent: Percent? = null

    @Column(name = "derivation_amount")
    private var derivationAmount: Money? = null

    @Column(name = "derivation_per_person")
    private var derivationPerPerson: Boolean? = null

    init {
        apply(details)
    }

    /** A regra de derivação; nula num plano com preços próprios. */
    val derivation: DerivationRule?
        get() {
            val base = baseRatePlanValue ?: return null
            val adjustment =
                PriceAdjustment.of(
                    checkNotNull(derivationType),
                    derivationPercent,
                    derivationAmount,
                    derivationPerPerson,
                )
            return DerivationRule(RatePlanId(base), adjustment)
        }

    /** Define ou retira ([rule] nulo) a derivação; devolve se mudou. As regras da árvore são do caso de uso. */
    fun derive(
        rule: DerivationRule?,
        now: Instant,
    ): Boolean {
        if (rule == derivation) return false
        require(rule?.basePlanId != id) { "Um plano não deriva de si mesmo" }
        baseRatePlanValue = rule?.basePlanId?.value
        derivationType = null
        derivationPercent = null
        derivationAmount = null
        derivationPerPerson = null
        when (val adjustment = rule?.adjustment) {
            is PriceAdjustment.ByPercent -> {
                derivationType = RateAdjustmentType.PERCENT
                derivationPercent = adjustment.percent
            }

            is PriceAdjustment.ByAmount -> {
                derivationType = RateAdjustmentType.AMOUNT
                derivationAmount = adjustment.amount
                derivationPerPerson = adjustment.perPerson
            }

            null -> {
                Unit
            }
        }
        return touched(true, now)
    }

    val channels: Set<SalesChannel> get() = channelSet.toSet()

    val roomTypeIds: Set<RoomTypeId> get() = roomTypeIdSet.toSet()

    val packageIds: Set<PackageId> get() = packageIdSet.toSet()

    val details: RatePlanDetails
        get() =
            RatePlanDetails(
                code,
                name,
                description,
                mealPlan,
                channels,
                roomTypeIds,
                packageIds,
                cancellationPolicyId,
                guaranteePolicyId,
            )

    /** Troca os dados editáveis; devolve se algo mudou. As referências são conferidas pelo caso de uso. */
    fun update(
        details: RatePlanDetails,
        now: Instant,
    ): Boolean = touched(apply(details), now)

    private fun apply(details: RatePlanDetails): Boolean {
        val valid = validate(details)
        val changed = valid != this.details
        code = valid.code
        name = valid.name
        description = valid.description
        mealPlan = valid.mealPlan
        cancellationPolicyId = valid.cancellationPolicyId
        guaranteePolicyId = valid.guaranteePolicyId
        replaceAll(channelSet, valid.channels)
        replaceAll(roomTypeIdSet, valid.roomTypeIds)
        replaceAll(packageIdSet, valid.packageIds)
        return changed
    }

    override fun toString(): String = "RatePlan(id=$id, code=$code)"

    companion object {
        const val MAX_NAME_LENGTH = 80
        const val MAX_DESCRIPTION_LENGTH = 1000
        const val MAX_ROOM_TYPES = 50
        const val MAX_PACKAGES = 20
        private const val BATCH = 100

        fun create(
            category: RatePlanCategory,
            details: RatePlanDetails,
            now: Instant,
        ): RatePlan = RatePlan(RatePlanId.new(), category, details, now)

        /** Os dados válidos (código em maiúsculas, textos sem espaços nas pontas); senão `400`. */
        fun validate(details: RatePlanDetails): RatePlanDetails =
            details.copy(
                code = RatesRules.code(details.code),
                name = RatesRules.required("name", details.name, MAX_NAME_LENGTH),
                description = RatesRules.optional("description", details.description, MAX_DESCRIPTION_LENGTH),
                channels = RatesRules.sized("channels", details.channels, 1, SalesChannel.entries.size),
                roomTypeIds = RatesRules.sized("roomTypeIds", details.roomTypeIds, 1, MAX_ROOM_TYPES),
                packageIds = RatesRules.sized("packageIds", details.packageIds, 0, MAX_PACKAGES),
            )
    }
}
