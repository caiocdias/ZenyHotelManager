package com.zeny.hotel.reservations.web

import com.zeny.hotel.rates.ChildAgeBandSummary
import com.zeny.hotel.rates.ChildCharge
import com.zeny.hotel.rates.DailyRates
import com.zeny.hotel.rates.DailyRestriction
import com.zeny.hotel.rates.MealPlan
import com.zeny.hotel.rates.NightPrice
import com.zeny.hotel.rates.PackageCharge
import com.zeny.hotel.rates.PackagePostingRhythm
import com.zeny.hotel.rates.PackagePricing
import com.zeny.hotel.rates.RatePlanCategory
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.RatePlanSummary
import com.zeny.hotel.rates.SaleRefusal
import com.zeny.hotel.rates.SalesChannel
import com.zeny.hotel.reservations.application.AvailabilityCalendar
import com.zeny.hotel.reservations.application.AvailabilityDay
import com.zeny.hotel.reservations.application.AvailabilityQuery
import com.zeny.hotel.reservations.application.AvailabilityService
import com.zeny.hotel.reservations.application.Quote
import com.zeny.hotel.reservations.application.QuoteOffer
import com.zeny.hotel.reservations.application.QuoteQuery
import com.zeny.hotel.reservations.application.RoomTypeAvailability
import com.zeny.hotel.reservations.application.UnavailableOption
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.Money
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.LocalDate
import java.util.UUID

private const val UNKNOWN_VALUE = "trate um valor desconhecido sem falhar"

@Schema(description = "O plano tarifário do calendário.")
data class QuotedRatePlanResponse(
    val id: UUID,
    val code: String,
    val name: String,
    @field:ExtensibleEnum
    @field:Schema(description = "Regime de alimentação ($UNKNOWN_VALUE).")
    val mealPlan: MealPlan,
    val category: RatePlanCategory,
    val active: Boolean,
    val cancellationPolicyId: UUID,
    val guaranteePolicyId: UUID,
) {
    companion object {
        fun of(plan: RatePlanSummary) =
            QuotedRatePlanResponse(
                plan.id.value,
                plan.code,
                plan.name,
                plan.mealPlan,
                plan.category,
                plan.active,
                plan.cancellationPolicyId.value,
                plan.guaranteePolicyId.value,
            )
    }
}

@Schema(description = "Uma faixa etária de criança atual do hotel (anos completos, inclusive).")
data class ChildAgeBandResponse(
    val minAge: Int,
    val maxAge: Int,
    val label: String,
) {
    companion object {
        fun of(band: ChildAgeBandSummary) = ChildAgeBandResponse(band.minAge, band.maxAge, band.label)
    }
}

@Schema(description = "O preço de uma faixa atual de criança numa data; nulo = sem preço para criança dessa idade.")
data class DailyChildRateResponse(
    val minAge: Int,
    val maxAge: Int,
    val label: String,
    @field:Schema(nullable = true)
    val amount: Money?,
)

@Schema(
    description =
        "Os preços efetivos do plano no tipo de UH numa data (num plano derivado, os do base ajustados): a diária de " +
            "um e de dois adultos, o adulto extra (por adulto além do segundo) e um preço por faixa atual de " +
            "criança. " +
            "Nulo = aquela ocupação não tem preço na data.",
)
data class DailyRatesResponse(
    @field:Schema(nullable = true)
    val oneAdult: Money?,
    @field:Schema(nullable = true)
    val twoAdults: Money?,
    @field:Schema(nullable = true)
    val extraAdult: Money?,
    val children: List<DailyChildRateResponse>,
) {
    companion object {
        fun of(rates: DailyRates) =
            DailyRatesResponse(
                rates.oneAdult,
                rates.twoAdults,
                rates.extraAdult,
                rates.children.map { DailyChildRateResponse(it.minAge, it.maxAge, it.label, it.amount) },
            )
    }
}

@Schema(
    description =
        "A restrição efetiva do plano no tipo de UH numa data (a do tipo sobre a geral do plano, campo a campo): " +
            "fechado vale em cada noite; CTA só na chegada; CTD só na saída; estadia mínima e máxima e antecedência, " +
            "as da data de chegada. Nulo = não restringe.",
)
data class DailyRestrictionResponse(
    val closed: Boolean,
    val closedToArrival: Boolean,
    val closedToDeparture: Boolean,
    @field:Schema(nullable = true)
    val minStay: Int?,
    @field:Schema(nullable = true)
    val maxStay: Int?,
    @field:Schema(nullable = true)
    val minAdvanceDays: Int?,
    @field:Schema(nullable = true)
    val maxAdvanceDays: Int?,
) {
    companion object {
        fun of(restriction: DailyRestriction) =
            with(restriction) {
                DailyRestrictionResponse(
                    closed,
                    closedToArrival,
                    closedToDeparture,
                    minStay,
                    maxStay,
                    minAdvanceDays,
                    maxAdvanceDays,
                )
            }
    }
}

@Schema(description = "Um tipo de UH numa data do calendário. $INVENTORY_COUNTERS")
data class AvailabilityDayResponse(
    val date: LocalDate,
    val total: Int,
    val sold: Int,
    val blocked: Int,
    val outOfOrder: Int,
    val overbookingLimit: Int,
    val available: Int,
    @field:Schema(description = "Disponível negativo: a data está sobrevendida.")
    val overbooked: Boolean,
    @field:Schema(
        nullable = true,
        description = "Os preços do plano pedido; nulo sem plano, com o tipo fora dele ou sem preço na data.",
    )
    val prices: DailyRatesResponse?,
    @field:Schema(nullable = true, description = "A restrição efetiva do plano pedido; nula sem plano ou fora dele.")
    val restriction: DailyRestrictionResponse?,
) {
    companion object {
        fun of(day: AvailabilityDay) =
            with(day.inventory) {
                AvailabilityDayResponse(
                    date,
                    total,
                    sold,
                    blocked,
                    outOfOrder,
                    overbookingLimit,
                    available,
                    day.overbooked,
                    day.prices?.let(DailyRatesResponse::of),
                    day.restriction?.let(DailyRestrictionResponse::of),
                )
            }
    }
}

@Schema(description = "Um tipo de UH no calendário.")
data class RoomTypeAvailabilityResponse(
    val roomTypeId: UUID,
    val code: String,
    val name: String,
    val active: Boolean,
    @field:Schema(nullable = true, description = "Se o tipo está no plano pedido; nulo sem plano.")
    val inRatePlan: Boolean?,
    @field:Schema(description = "As datas sobrevendidas (disponível negativo), em ordem.")
    val overbookedDates: List<LocalDate>,
    @field:Schema(description = "Um dia por data com inventário mantido, em ordem; as de fora não aparecem.")
    val days: List<AvailabilityDayResponse>,
) {
    companion object {
        fun of(item: RoomTypeAvailability) =
            RoomTypeAvailabilityResponse(
                item.roomType.id.value,
                item.roomType.code,
                item.roomType.name,
                item.roomType.active,
                item.inRatePlan,
                item.overbookedDates,
                item.days.map(AvailabilityDayResponse::of),
            )
    }
}

@Schema(description = "O calendário de disponibilidade por tipo de UH e data, com os preços e as restrições do plano.")
data class AvailabilityResponse(
    val from: LocalDate,
    val to: LocalDate,
    @field:Schema(nullable = true, description = "O plano pedido; nulo sem plano.")
    val ratePlan: QuotedRatePlanResponse?,
    @field:Schema(
        nullable = true,
        description = "As faixas de criança atuais do hotel, na ordem dos preços de criança; nulas sem plano.",
    )
    val childAgeBands: List<ChildAgeBandResponse>?,
    @field:Schema(description = "Um item por tipo de UH, na ordem de exibição e de código.")
    val roomTypes: List<RoomTypeAvailabilityResponse>,
) {
    companion object {
        fun of(calendar: AvailabilityCalendar) =
            AvailabilityResponse(
                calendar.from,
                calendar.to,
                calendar.ratePlan?.let(QuotedRatePlanResponse::of),
                calendar.childAgeBands?.map(ChildAgeBandResponse::of),
                calendar.roomTypes.map(RoomTypeAvailabilityResponse::of),
            )
    }
}

@Schema(description = "O preço de uma criança numa noite: a idade, a faixa atual do hotel em que ela cai e o preço.")
data class QuoteChildChargeResponse(
    val age: Int,
    val minAge: Int,
    val maxAge: Int,
    val label: String,
    val amount: Money,
) {
    companion object {
        fun of(charge: ChildCharge) =
            QuoteChildChargeResponse(charge.age, charge.minAge, charge.maxAge, charge.label, charge.amount)
    }
}

@Schema(
    description =
        "Um pacote do plano numa noite: `quantity` × `unitPrice` = `amount`. O incluso é parte da diária; o " +
            "adicional soma ao total da noite.",
)
data class QuotePackageChargeResponse(
    val packageId: UUID,
    val code: String,
    val name: String,
    val pricing: PackagePricing,
    val postingRhythm: PackagePostingRhythm,
    val includedInRate: Boolean,
    val quantity: Int,
    val unitPrice: Money,
    val amount: Money,
) {
    companion object {
        fun of(charge: PackageCharge) =
            QuotePackageChargeResponse(
                charge.packageId.value,
                charge.code,
                charge.name,
                charge.pricing,
                charge.postingRhythm,
                charge.includedInRate,
                charge.quantity,
                charge.unitPrice,
                charge.amount,
            )
    }
}

@Schema(
    description =
        "Uma noite da oferta: `roomAmount` = `baseRate` (diária de um ou de dois adultos) + `extraAdultsAmount` + as " +
            "crianças; `accommodationAmount` = `roomAmount` − os pacotes inclusos; `total` = `roomAmount` + os " +
            "pacotes adicionais.",
)
data class QuoteNightResponse(
    val date: LocalDate,
    val baseRate: Money,
    val extraAdults: Int,
    val extraAdultsAmount: Money,
    val children: List<QuoteChildChargeResponse>,
    val roomAmount: Money,
    val packages: List<QuotePackageChargeResponse>,
    val includedPackagesAmount: Money,
    val accommodationAmount: Money,
    val additionalPackagesAmount: Money,
    val total: Money,
) {
    companion object {
        fun of(night: NightPrice) =
            QuoteNightResponse(
                night.date,
                night.baseRate,
                night.extraAdults,
                night.extraAdultsAmount,
                night.children.map(QuoteChildChargeResponse::of),
                night.roomAmount,
                night.packages.map(QuotePackageChargeResponse::of),
                night.includedPackagesAmount,
                night.accommodationAmount,
                night.additionalPackagesAmount,
                night.total,
            )
    }
}

@Schema(
    description =
        "Uma oferta da cotação: o tipo de UH, o plano, o menor número de unidades disponíveis entre as noites e o " +
            "preço; os totais são sempre a soma das noites.",
)
data class QuoteOfferResponse(
    val roomTypeId: UUID,
    val roomTypeCode: String,
    val roomTypeName: String,
    val ratePlanId: UUID,
    val ratePlanCode: String,
    val ratePlanName: String,
    @field:ExtensibleEnum
    @field:Schema(description = "Regime de alimentação do plano ($UNKNOWN_VALUE).")
    val mealPlan: MealPlan,
    val category: RatePlanCategory,
    @field:Schema(description = "A política de cancelamento do plano (a reserva copia os termos dela, RN-15).")
    val cancellationPolicyId: UUID,
    @field:Schema(description = "A política de garantia do plano.")
    val guaranteePolicyId: UUID,
    @field:Schema(description = "O menor disponível entre as noites (ao menos 1).")
    val availableUnits: Int,
    @field:Schema(description = "As diárias (com os pacotes inclusos).")
    val roomTotal: Money,
    @field:Schema(description = "A parte das diárias que é de pacotes inclusos.")
    val includedPackagesTotal: Money,
    @field:Schema(description = "`roomTotal` − `includedPackagesTotal`.")
    val accommodationTotal: Money,
    @field:Schema(description = "Os pacotes adicionais, além da diária.")
    val additionalPackagesTotal: Money,
    @field:Schema(description = "`roomTotal` + `additionalPackagesTotal`: o que o hóspede paga.")
    val total: Money,
    val nights: List<QuoteNightResponse>,
) {
    companion object {
        fun of(offer: QuoteOffer) =
            QuoteOfferResponse(
                offer.roomType.id.value,
                offer.roomType.code,
                offer.roomType.name,
                offer.ratePlan.id.value,
                offer.ratePlan.code,
                offer.ratePlan.name,
                offer.ratePlan.mealPlan,
                offer.ratePlan.category,
                offer.ratePlan.cancellationPolicyId.value,
                offer.ratePlan.guaranteePolicyId.value,
                offer.availableUnits,
                offer.price.roomTotal,
                offer.price.includedPackagesTotal,
                offer.price.accommodationTotal,
                offer.price.additionalPackagesTotal,
                offer.price.total,
                offer.price.nights.map(QuoteNightResponse::of),
            )
    }
}

@Schema(description = "Um motivo de a combinação não ser oferecida, com as datas a que ele se refere.")
data class QuoteRefusalResponse(
    @field:Schema(
        description =
            "Código estável: os de vendabilidade e preço do `rates` (`RATE_PLAN_INACTIVE`, " +
                "`SALES_CHANNEL_NOT_ALLOWED`, `CLOSED_TO_ARRIVAL`, `RATE_NOT_PRICED`…) ou do inventário " +
                "(`ROOM_TYPE_UNAVAILABLE`, `INVENTORY_DATE_OUT_OF_HORIZON`); a lista cresce ($UNKNOWN_VALUE).",
    )
    val code: String,
    val message: String,
    val dates: List<LocalDate>,
) {
    companion object {
        fun of(refusal: SaleRefusal) = QuoteRefusalResponse(refusal.code.value, refusal.message, refusal.dates)
    }
}

@Schema(description = "Uma combinação de tipo de UH e plano que não é oferecida, com todos os motivos.")
data class QuoteUnavailableResponse(
    val roomTypeId: UUID,
    val roomTypeCode: String,
    val ratePlanId: UUID,
    val ratePlanCode: String,
    @field:Schema(description = "Todos os motivos: os do plano e da tarifa, depois os do inventário.")
    val reasons: List<QuoteRefusalResponse>,
) {
    companion object {
        fun of(option: UnavailableOption) =
            QuoteUnavailableResponse(
                option.roomType.id.value,
                option.roomType.code,
                option.ratePlan.id.value,
                option.ratePlan.code,
                option.reasons.map(QuoteRefusalResponse::of),
            )
    }
}

@Schema(description = "A cotação de uma estadia (RF-TAR-11).")
data class QuoteResponse(
    val arrival: LocalDate,
    val departure: LocalDate,
    val nights: Int,
    val adults: Int,
    val childAges: List<Int>,
    @field:ExtensibleEnum
    @field:Schema(description = "O canal da cotação ($UNKNOWN_VALUE).")
    val channel: SalesChannel,
    @field:Schema(
        description =
            "As combinações vendáveis no canal e com unidade em todas as noites, na ordem de exibição do tipo de UH, " +
                "depois o total e o código do plano.",
    )
    val offers: List<QuoteOfferResponse>,
    @field:Schema(
        nullable = true,
        description = "As combinações de fora, com os motivos; só com `includeUnavailable=true` (senão nula).",
    )
    val unavailable: List<QuoteUnavailableResponse>?,
) {
    companion object {
        fun of(quote: Quote) =
            QuoteResponse(
                quote.query.arrival,
                quote.query.departure,
                quote.nights,
                quote.query.adults,
                quote.query.childAges,
                quote.query.channel,
                quote.offers.map(QuoteOfferResponse::of),
                quote.unavailable?.map(QuoteUnavailableResponse::of),
            )
    }
}

/**
 * A disponibilidade em calendário (RF-TAR-08, M4 E06): por tipo de UH e data, os contadores do inventário, as datas
 * sobrevendidas e, com o plano, os preços e as restrições efetivos. Exige `reservations.availability.read`.
 */
@ApiV1Controller
@RequestMapping("/availability")
@Tag(name = RESERVATIONS_TAG, description = RESERVATIONS_TAG_DESCRIPTION)
class AvailabilityController(
    private val availability: AvailabilityService,
) {
    @GetMapping
    @Operation(
        operationId = "getAvailabilityCalendar",
        summary = "Consultar a disponibilidade em calendário",
        description =
            "Um período de até 366 dias, inclusive o passado (fim antes do início: 400 `INVENTORY_PERIOD_INVALID`; " +
                "mais: 400 `INVENTORY_PERIOD_TOO_LONG`), nos `roomTypeIds` (até 50, sem repetir, senão 400 " +
                "`AVAILABILITY_QUERY_INVALID`; inexistente 400 `UNKNOWN_ROOM_TYPE`; sem eles, os tipos ativos) e, " +
                "com " +
                "`ratePlanId` (inexistente 400 `UNKNOWN_RATE_PLAN`), os preços e as restrições efetivos do plano nos " +
                "tipos dele. Um dia por data com inventário mantido. $INVENTORY_COUNTERS Exige " +
                "`reservations.availability.read`.",
    )
    fun calendar(
        @RequestParam from: LocalDate,
        @RequestParam to: LocalDate,
        @Parameter(description = "Os tipos de UH, separados por vírgula (sem eles, os ativos).")
        @RequestParam(required = false) roomTypeIds: List<UUID>?,
        @RequestParam(required = false) ratePlanId: UUID?,
    ): AvailabilityResponse =
        AvailabilityResponse.of(
            availability.calendar(
                AvailabilityQuery(from, to, roomTypeIds?.map(::RoomTypeId), ratePlanId?.let(::RatePlanId)),
            ),
        )
}

/**
 * A cotação (RF-TAR-11, M4 E06): os tipos de UH e os planos vendáveis no canal e disponíveis em todas as noites, com o
 * total e o detalhe por noite. Exige `reservations.availability.read`.
 */
@ApiV1Controller
@RequestMapping("/quotes")
@Tag(name = RESERVATIONS_TAG, description = RESERVATIONS_TAG_DESCRIPTION)
class QuotesController(
    private val availability: AvailabilityService,
) {
    @GetMapping
    @Operation(
        operationId = "quoteStay",
        summary = "Cotar uma estadia",
        description =
            "Para a chegada, a saída (de 1 a 365 noites), os adultos (ao menos 1, até 40), as idades das crianças na " +
                "chegada (até 20; de 0 à idade limite de criança do hotel) e o canal, devolve as combinações de tipo " +
                "de UH (`roomTypeIds`, até 50, sem eles os ativos) e plano (`ratePlanIds`, até 50, sem eles os " +
                "ativos " +
                "com algum dos tipos) **vendáveis** no canal (plano ativo, canal, tipo no plano e ativo, ocupação " +
                "máxima, antecedência pela data operacional, restrições e preço) e com ao menos uma UH disponível em " +
                "**todas** as noites, com o total e o detalhe por noite. Com `includeUnavailable=true`, também as " +
                "combinações de fora, com os motivos. Estadia fora do formato: 400 `STAY_INVALID` (campo " +
                "`departure`, `adults` ou `childAges[i]`); listas longas ou repetidas: 400 " +
                "`AVAILABILITY_QUERY_INVALID`; tipo inexistente: 400 `UNKNOWN_ROOM_TYPE`; plano inexistente: 400 " +
                "`UNKNOWN_RATE_PLAN`. Leitura sem trava: a reserva confere de novo. Exige " +
                "`reservations.availability.read`.",
    )
    @Suppress("LongParameterList") // os parâmetros da cotação
    fun quote(
        @RequestParam arrival: LocalDate,
        @RequestParam departure: LocalDate,
        @RequestParam adults: Int,
        @Parameter(description = "As idades das crianças na chegada, separadas por vírgula.")
        @RequestParam(required = false) childAges: List<Int>?,
        @RequestParam channel: SalesChannel,
        @Parameter(description = "Os tipos de UH, separados por vírgula (sem eles, os ativos).")
        @RequestParam(required = false) roomTypeIds: List<UUID>?,
        @Parameter(description = "Os planos, separados por vírgula (sem eles, os ativos com algum dos tipos).")
        @RequestParam(required = false) ratePlanIds: List<UUID>?,
        @RequestParam(defaultValue = "false") includeUnavailable: Boolean,
    ): QuoteResponse =
        QuoteResponse.of(
            availability.quote(
                QuoteQuery(
                    arrival,
                    departure,
                    adults,
                    childAges.orEmpty(),
                    channel,
                    roomTypeIds?.map(::RoomTypeId),
                    ratePlanIds?.map(::RatePlanId),
                    includeUnavailable,
                ),
            ),
        )
}
