package com.zeny.hotel.reservations.application

import com.zeny.hotel.rates.ChildAgeBandSummary
import com.zeny.hotel.rates.DailyRates
import com.zeny.hotel.rates.DailyRestriction
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.RatePlanSummary
import com.zeny.hotel.rates.RatesApi
import com.zeny.hotel.rates.SaleRefusal
import com.zeny.hotel.rates.SalesChannel
import com.zeny.hotel.rates.StayOptionsQuery
import com.zeny.hotel.rates.StayPrice
import com.zeny.hotel.reservations.InventoryDay
import com.zeny.hotel.reservations.ReservationsErrorCodes
import com.zeny.hotel.reservations.ReservationsPermissions
import com.zeny.hotel.reservations.domain.AvailabilityRules
import com.zeny.hotel.reservations.domain.InventoryPeriod
import com.zeny.hotel.reservations.domain.InventoryRules
import com.zeny.hotel.reservations.domain.StayAvailability
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.RoomTypeSummary
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.Authorization
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** O pedido do calendário: o período, os tipos de UH (nulo = os ativos) e o plano (opcional). */
data class AvailabilityQuery(
    val from: LocalDate,
    val to: LocalDate,
    val roomTypeIds: List<RoomTypeId>?,
    val ratePlanId: RatePlanId?,
)

/**
 * O calendário de disponibilidade: o período, o plano pedido (com as faixas de criança atuais do hotel, na ordem dos
 * preços de criança) e um item por tipo de UH, na ordem de exibição.
 */
data class AvailabilityCalendar(
    val from: LocalDate,
    val to: LocalDate,
    val ratePlan: RatePlanSummary?,
    val childAgeBands: List<ChildAgeBandSummary>?,
    val roomTypes: List<RoomTypeAvailability>,
)

/**
 * Um tipo de UH no calendário: [inRatePlan] (nulo sem plano) e um dia por data com inventário mantido;
 * [overbookedDates] são as datas com o disponível negativo (sobrevendidas).
 */
data class RoomTypeAvailability(
    val roomType: RoomTypeSummary,
    val inRatePlan: Boolean?,
    val days: List<AvailabilityDay>,
) {
    val overbookedDates: List<LocalDate> get() = days.filter { it.overbooked }.map { it.inventory.date }
}

/**
 * Um dia do calendário: os contadores e, com o plano e o tipo nele, os preços efetivos ([prices], nulo sem preço na
 * data) e a restrição efetiva ([restriction]).
 */
data class AvailabilityDay(
    val inventory: InventoryDay,
    val prices: DailyRates?,
    val restriction: DailyRestriction?,
) {
    val overbooked: Boolean get() = inventory.available < 0
}

/**
 * O pedido de cotação: a estadia, a ocupação, o canal, os tipos de UH (nulo = os ativos), os planos (nulo = os ativos
 * com algum dos tipos) e se as combinações de fora vêm com os motivos.
 */
data class QuoteQuery(
    val arrival: LocalDate,
    val departure: LocalDate,
    val adults: Int,
    val childAges: List<Int>,
    val channel: SalesChannel,
    val roomTypeIds: List<RoomTypeId>?,
    val ratePlanIds: List<RatePlanId>?,
    val includeUnavailable: Boolean,
)

/** A cotação: as ofertas e, se pedidas, as combinações de fora com os motivos. */
data class Quote(
    val query: QuoteQuery,
    val nights: Int,
    val offers: List<QuoteOffer>,
    val unavailable: List<UnavailableOption>?,
)

/** Uma oferta: o tipo de UH, o plano, o menor número de unidades disponíveis entre as noites e o preço. */
data class QuoteOffer(
    val roomType: RoomTypeSummary,
    val ratePlan: RatePlanSummary,
    val availableUnits: Int,
    val price: StayPrice,
)

/** Uma combinação que não é oferecida, com os motivos (os do `rates`, depois os do inventário). */
data class UnavailableOption(
    val roomType: RoomTypeSummary,
    val ratePlan: RatePlanSummary,
    val reasons: List<SaleRefusal>,
)

/**
 * A disponibilidade em calendário e a cotação (RF-TAR-08 e 11, M4 E06), só leitura; as duas exigem
 * `reservations.availability.read`. Combinam o inventário (E05; uma consulta para todos os tipos e o período) com a
 * `RatesApi` (E04): o calendário com `rateCalendar` (os preços e as restrições efetivos do plano, numa chamada) e a
 * cotação com `stayOptions` (a vendabilidade e o preço de todos os planos e tipos, numa chamada, com o mesmo resultado
 * de `isSellable` e `priceFor`). O `reservations` nunca lê as tabelas do `rates`.
 *
 * - **Calendário** ([calendar]): até 366 dias, inclusive o passado; um dia por data com inventário mantido (as de fora
 *   não aparecem); o disponível pode ser negativo (a data sobrevendida, H4); o OOO entra pelo `out_of_order` do
 *   inventário, refeito pelo listener de `OutOfOrderChanged` (H10).
 * - **Cotação** ([quote]): oferece a combinação de plano e tipo **vendável** no canal (plano ativo, canal, tipo no
 *   plano e ativo, ocupação, antecedência, restrições e preço) **e** com ao menos uma unidade disponível em **todas**
 *   as noites (dentro do inventário); as ofertas vêm na ordem de exibição do tipo, depois o total e o código do plano.
 *   É uma leitura sem trava: a reserva (E07) confere de novo, travando o inventário.
 */
@Service
class AvailabilityService(
    private val store: InventoryStore,
    private val rooms: RoomsApi,
    private val rates: RatesApi,
    private val authorization: Authorization,
) {
    @Transactional(readOnly = true)
    fun calendar(query: AvailabilityQuery): AvailabilityCalendar {
        authorization.require(ReservationsPermissions.AVAILABILITY_READ)
        val period = InventoryPeriod.of(query.from, query.to)
        val types = roomTypes(query.roomTypeIds)
        val rateCalendar =
            query.ratePlanId?.let { plan ->
                rates.rateCalendar(plan, types.map { it.id }, period.from, period.to)
                    ?: throw InventoryRules.invalid(
                        ReservationsErrorCodes.UNKNOWN_RATE_PLAN,
                        "ratePlanId",
                        "O plano tarifário informado não existe.",
                    )
            }
        val rateDays = rateCalendar?.days.orEmpty().associateBy { it.roomTypeId to it.date }
        val rows = days(store.rows(types.map { it.id }, period.from, period.to)).groupBy { it.roomTypeId }
        return AvailabilityCalendar(
            period.from,
            period.to,
            rateCalendar?.ratePlan,
            rateCalendar?.childAgeBands,
            types.map { type ->
                val inPlan = rateCalendar?.let { type.id in it.ratePlan.roomTypeIds }
                RoomTypeAvailability(
                    type,
                    inPlan,
                    rows[type.id].orEmpty().map { day ->
                        val rate = rateDays[type.id to day.date]
                        AvailabilityDay(day, rate?.prices, rate?.restriction)
                    },
                )
            },
        )
    }

    @Transactional(readOnly = true)
    fun quote(query: QuoteQuery): Quote {
        authorization.require(ReservationsPermissions.AVAILABILITY_READ)
        if (!query.departure.isAfter(query.arrival)) {
            // O day use (chegada = saída, H5) não é cotado (decisão da E07): ele não toma inventário e a
            // disponibilidade dele depende da alocação de UH (E10); o preço é a tarifa da data, no calendário.
            throw InventoryRules.invalid(
                ReservationsErrorCodes.STAY_INVALID,
                "departure",
                "A cotação é de pernoite: a saída precisa ser depois da chegada.",
            )
        }
        AvailabilityRules.requireOccupancy(query.adults, query.childAges)
        val planIds = query.ratePlanIds?.let { AvailabilityRules.requireIds(it, "ratePlanIds").toSet() }
        val types = roomTypes(query.roomTypeIds)
        val options =
            rates.stayOptions(
                StayOptionsQuery(
                    query.arrival,
                    query.departure,
                    query.adults,
                    query.childAges,
                    query.channel,
                    types.mapTo(linkedSetOf()) { it.id },
                    planIds,
                ),
            )
        val nights = query.arrival.datesUntil(query.departure).toList()
        val rows =
            store
                .rows(types.map { it.id }, query.arrival, query.departure.minusDays(1))
                .entries
                .groupBy({ it.key.roomTypeId }, { it.key.date to it.value })
        val availability = types.associate { it.id to StayAvailability.of(nights, rows[it.id].orEmpty().toMap()) }
        val typeById = types.associateBy { it.id }
        val order = types.withIndex().associate { it.value.id to it.index }
        val offers = mutableListOf<QuoteOffer>()
        val unavailable = mutableListOf<UnavailableOption>()
        options.forEach { option ->
            val type = typeById.getValue(option.roomTypeId)
            val stay = availability.getValue(option.roomTypeId)
            val price = option.price
            if (option.sellability.sellable && stay.available && price != null) {
                offers += QuoteOffer(type, option.ratePlan, stay.units, price)
            } else {
                unavailable += UnavailableOption(type, option.ratePlan, option.sellability.refusals + stay.refusals())
            }
        }
        return Quote(
            query,
            ChronoUnit.DAYS.between(query.arrival, query.departure).toInt(),
            offers.sortedWith(
                compareBy<QuoteOffer>({ order.getValue(it.roomType.id) }, { it.price.total }, { it.ratePlan.code }),
            ),
            unavailable
                .sortedWith(compareBy({ order.getValue(it.roomType.id) }, { it.ratePlan.code }))
                .takeIf { query.includeUnavailable },
        )
    }

    /** Os tipos pedidos (todos precisam existir; sem repetir) ou os ativos, na ordem de exibição e de código. */
    private fun roomTypes(ids: List<RoomTypeId>?): List<RoomTypeSummary> {
        val types =
            if (ids.isNullOrEmpty()) {
                rooms.activeRoomTypes()
            } else {
                AvailabilityRules.requireIds(ids, "roomTypeIds").map { id ->
                    rooms.roomType(id)
                        ?: throw InventoryRules.invalid(
                            ReservationsErrorCodes.UNKNOWN_ROOM_TYPE,
                            "roomTypeIds",
                            "O tipo de UH informado não existe.",
                        )
                }
            }
        return types.sortedWith(compareBy({ it.displayOrder }, { it.code }))
    }
}
