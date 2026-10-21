package com.zeny.hotel.rates.domain

import com.zeny.hotel.rates.ChildCharge
import com.zeny.hotel.rates.NightPrice
import com.zeny.hotel.rates.PackageCharge
import com.zeny.hotel.rates.PackagePricing
import com.zeny.hotel.rates.PackageSummary
import com.zeny.hotel.rates.RatesErrorCodes
import com.zeny.hotel.rates.SaleRefusal
import com.zeny.hotel.rates.SalesChannel
import com.zeny.hotel.rates.StayPrice
import com.zeny.hotel.rates.StayPriceResult
import com.zeny.hotel.rates.StayQuery
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.sum
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

private fun LocalDate.br(): String = format(DATE)

/** As regras de formato de uma estadia (M4 E04). */
object StayRules {
    /** Noites de uma estadia, no máximo (a estadia máxima das restrições, `RestrictionValues.MAX_NIGHTS`). */
    const val MAX_NIGHTS = RestrictionValues.MAX_NIGHTS

    /**
     * As noites da estadia: da chegada à véspera da saída. No day use (chegada = saída, H5 do M4, E07), a própria data,
     * precificada como uma "noite" pela tarifa do plano naquela data.
     */
    fun nights(query: StayQuery): List<LocalDate> =
        if (query.dayUse) {
            listOf(query.arrival)
        } else {
            generateSequence(query.arrival) { it.plusDays(1) }.takeWhile { it.isBefore(query.departure) }.toList()
        }

    /** A última data com preço da estadia de [arrival] a [departure]: a véspera da saída, ou a própria no day use. */
    fun lastPricedDate(
        arrival: LocalDate,
        departure: LocalDate,
    ): LocalDate = if (arrival == departure) arrival else departure.minusDays(1)

    /** O número de noites (saída − chegada; zero no day use). */
    fun nightCount(query: StayQuery): Long = ChronoUnit.DAYS.between(query.arrival, query.departure)

    /**
     * Confere o formato da estadia: saída não antes da chegada (igual é day use, H5) e até [MAX_NIGHTS] noites (campo
     * `departure`), ao menos um
     * adulto (campo `adults`) e cada idade de criança de 0 à idade limite das faixas atuais do hotel ([bands]; campo
     * `childAges[i]`). Senão, `400 STAY_INVALID` com todos os campos.
     */
    fun requireValid(
        query: StayQuery,
        bands: ChildBands,
    ) {
        val violations = mutableListOf<FieldViolation>()

        fun violation(
            field: String,
            message: String,
        ) {
            violations += FieldViolation(field, RatesErrorCodes.STAY_INVALID.value, message)
        }
        val nights = nightCount(query)
        if (nights < 0) violation("departure", "A saída não pode ser antes da chegada.")
        if (nights > MAX_NIGHTS) violation("departure", "A estadia pode ter até $MAX_NIGHTS noites.")
        if (query.adults < 1) violation("adults", "Informe ao menos um adulto.")
        query.childAges.forEachIndexed { index, age ->
            if (bands.bandOf(age) == null) {
                violation(
                    "childAges[$index]",
                    "Informe a idade da criança de 0 a ${bands.maxAge} anos; acima disso, conte como adulto.",
                )
            }
        }
        if (violations.isNotEmpty()) {
            throw InvalidInputException(RatesErrorCodes.STAY_INVALID, violations.first().message, violations)
        }
    }
}

/**
 * O preço de uma estadia (M4 E04, RF-TAR-02, 03 e 06, H2), a partir dos preços **efetivos** do tipo de UH em cada
 * noite (os do plano, ou os calculados de um derivado), das faixas de criança atuais do hotel e dos pacotes do plano:
 *
 * - a diária da noite é a de um adulto (com um) ou a de dois (com dois ou mais), mais o adulto extra por adulto além do
 *   segundo, mais o preço da faixa etária atual de cada criança; sem algum desses valores, a estadia não tem preço
 *   (`RATE_NOT_PRICED` sem a linha da data, `OCCUPANCY_NOT_PRICED` sem o valor dos adultos, inclusive a lacuna de um
 *   derivado, `CHILD_AGE_NOT_PRICED` sem o da faixa da criança — o preço obsoleto não conta e nunca cai no adulto
 *   extra);
 * - cada pacote entra em cada noite com a quantidade da base de preço (por noite: 1; por pessoa e noite: adultos mais
 *   crianças) ou, se for por estadia, **uma vez, na noite da chegada**; o ritmo de lançamento não muda a noite a que o
 *   valor pertence;
 * - o pacote **incluso** é parte da diária: não soma ao total e é separado da hospedagem
 *   ([NightPrice.accommodationAmount] = diária − inclusos); se os inclusos passarem da diária,
 *   `INCLUDED_PACKAGES_EXCEED_RATE`; o **adicional** soma ao total da noite.
 *
 * Só há somas e produtos por inteiros (nenhum arredondamento), então os totais são exatamente a soma das noites.
 */
object StayPricing {
    fun price(
        query: StayQuery,
        prices: Map<LocalDate, PriceSet>,
        bands: ChildBands,
        packages: List<PackageSummary>,
    ): StayPriceResult {
        val missing = linkedMapOf<ErrorCode, MutableList<LocalDate>>()
        val nights =
            StayRules.nights(query).mapIndexedNotNull { index, date ->
                when (val night = night(query, date, index == 0, prices[date], bands, packages)) {
                    is NightOutcome.Priced -> {
                        night.price
                    }

                    is NightOutcome.Missing -> {
                        night.codes.forEach { missing.getOrPut(it) { mutableListOf() } += date }
                        null
                    }
                }
            }
        if (missing.isNotEmpty()) {
            return StayPriceResult.NotPriced(
                PRICE_REFUSALS.mapNotNull { code -> missing[code]?.let { refusal(code, it) } },
            )
        }
        val room = nights.map { it.roomAmount }.sum()
        val included = nights.map { it.includedPackagesAmount }.sum()
        val additional = nights.map { it.additionalPackagesAmount }.sum()
        return StayPriceResult.Priced(
            StayPrice(
                query = query,
                nights = nights,
                roomTotal = room,
                includedPackagesTotal = included,
                accommodationTotal = nights.map { it.accommodationAmount }.sum(),
                additionalPackagesTotal = additional,
                total = nights.map { it.total }.sum(),
            ),
        )
    }

    private sealed interface NightOutcome {
        data class Priced(
            val price: NightPrice,
        ) : NightOutcome

        data class Missing(
            val codes: Set<ErrorCode>,
        ) : NightOutcome
    }

    @Suppress("LongParameterList") // a estadia, a noite e o que a precifica
    private fun night(
        query: StayQuery,
        date: LocalDate,
        arrivalNight: Boolean,
        prices: PriceSet?,
        bands: ChildBands,
        packages: List<PackageSummary>,
    ): NightOutcome {
        prices ?: return NightOutcome.Missing(setOf(RatesErrorCodes.RATE_NOT_PRICED))
        val extraAdults = (query.adults - 2).coerceAtLeast(0)
        val baseRate = if (query.adults == 1) prices.oneAdult else prices.twoAdults
        val extraRate = if (extraAdults > 0) prices.extraAdult else Money.ZERO
        val current = bands.classify(prices.children).first
        val children =
            query.childAges.map { age ->
                val band = checkNotNull(bands.bandOf(age)) { "Idade conferida por StayRules.requireValid." }
                current
                    .firstOrNull { it.first == band }
                    ?.let { ChildCharge(age, band.minAge, band.maxAge, band.label, it.second) }
            }
        val missing =
            buildSet {
                if (baseRate == null || extraRate == null) add(RatesErrorCodes.OCCUPANCY_NOT_PRICED)
                if (children.any { it == null }) add(RatesErrorCodes.CHILD_AGE_NOT_PRICED)
            }
        return if (missing.isNotEmpty() || baseRate == null || extraRate == null) {
            NightOutcome.Missing(missing)
        } else {
            val extraAmount = extraRate * extraAdults
            val charges = children.filterNotNull()
            val room = NightRoom(baseRate, extraAdults, extraAmount, charges)
            withPackages(date, room, packages.mapNotNull { charge(it, query.adults + charges.size, arrivalNight) })
        }
    }

    /** A diária de uma noite: a dos adultos, o adulto extra e as crianças. */
    private data class NightRoom(
        val baseRate: Money,
        val extraAdults: Int,
        val extraAdultsAmount: Money,
        val children: List<ChildCharge>,
    ) {
        val amount: Money get() = baseRate + extraAdultsAmount + children.map { it.amount }.sum()
    }

    /** A noite com os pacotes: os inclusos saem da hospedagem (nunca acima da diária), os adicionais somam. */
    private fun withPackages(
        date: LocalDate,
        room: NightRoom,
        packageCharges: List<PackageCharge>,
    ): NightOutcome {
        val roomAmount = room.amount
        val included = packageCharges.filter { it.includedInRate }.map { it.amount }.sum()
        val additional = packageCharges.filterNot { it.includedInRate }.map { it.amount }.sum()
        return if (included > roomAmount) {
            NightOutcome.Missing(setOf(RatesErrorCodes.INCLUDED_PACKAGES_EXCEED_RATE))
        } else {
            NightOutcome.Priced(
                NightPrice(
                    date = date,
                    baseRate = room.baseRate,
                    extraAdults = room.extraAdults,
                    extraAdultsAmount = room.extraAdultsAmount,
                    children = room.children,
                    roomAmount = roomAmount,
                    packages = packageCharges,
                    includedPackagesAmount = included,
                    accommodationAmount = roomAmount - included,
                    additionalPackagesAmount = additional,
                    total = roomAmount + additional,
                ),
            )
        }
    }

    /** O pacote numa noite; `null` no pacote por estadia fora da noite da chegada. */
    private fun charge(
        pkg: PackageSummary,
        persons: Int,
        arrivalNight: Boolean,
    ): PackageCharge? {
        val quantity =
            when (pkg.pricing) {
                PackagePricing.PER_NIGHT -> 1
                PackagePricing.PER_PERSON_PER_NIGHT -> persons
                PackagePricing.PER_STAY -> if (arrivalNight) 1 else return null
            }
        return PackageCharge(
            pkg.id,
            pkg.code,
            pkg.name,
            pkg.transactionCodeId,
            pkg.pricing,
            pkg.postingRhythm,
            pkg.includedInRate,
            quantity,
            pkg.price,
            pkg.price * quantity,
        )
    }

    /** Os motivos de "sem preço", na ordem em que aparecem. */
    private val PRICE_REFUSALS =
        listOf(
            RatesErrorCodes.RATE_NOT_PRICED,
            RatesErrorCodes.OCCUPANCY_NOT_PRICED,
            RatesErrorCodes.CHILD_AGE_NOT_PRICED,
            RatesErrorCodes.INCLUDED_PACKAGES_EXCEED_RATE,
        )

    private fun refusal(
        code: ErrorCode,
        dates: List<LocalDate>,
    ): SaleRefusal {
        val listed = dates.joinToString(", ") { it.br() }
        val message =
            when (code) {
                RatesErrorCodes.RATE_NOT_PRICED -> "O plano não tem preço para o tipo de UH em $listed."
                RatesErrorCodes.OCCUPANCY_NOT_PRICED -> "O plano não tem preço para esse número de adultos em $listed."
                RatesErrorCodes.CHILD_AGE_NOT_PRICED -> "O plano não tem preço para a idade de uma criança em $listed."
                else -> "Os pacotes inclusos passam do valor da diária em $listed."
            }
        return SaleRefusal(code, message, dates)
    }
}

/**
 * O que a vendabilidade confere de uma estadia além do preço (M4 E04): o plano ([planActive], [channels], os tipos de
 * UH dele, [planRoomTypeIds]), o tipo de UH ([roomTypeActive] e a ocupação máxima, `RoomsApi`), a data operacional
 * ([businessDate], RN-01, H15; nunca a data do relógio) e as restrições **efetivas** do tipo de UH no plano de cada
 * data da chegada à saída, inclusive ([restrictions]; a data ausente não tem restrição).
 */
data class SaleConditions(
    val planActive: Boolean,
    val channels: Set<SalesChannel>,
    val planRoomTypeIds: Set<RoomTypeId>,
    val roomTypeActive: Boolean,
    val maxAdults: Int,
    val maxChildren: Int,
    val businessDate: LocalDate,
    val restrictions: Map<LocalDate, EffectiveRestriction>,
)

/**
 * As regras de venda de uma estadia (M4 E04, RF-TAR-01 e 05, H15; semântica das restrições decidida pelo usuário na
 * E03, em `RestrictionField`). Devolve **todos** os motivos, nesta ordem: plano desativado, canal, tipo fora do plano,
 * tipo desativado, ocupação acima da máxima do tipo, chegada antes da data operacional, antecedência mínima e máxima,
 * CTA, CTD, estadia mínima e máxima e noites fechadas.
 *
 * - **fechado** vale em cada noite (da chegada à véspera da saída), nunca na data de saída;
 * - **CTA** só na data de chegada; **CTD** só na data de saída (que não é noite da estadia);
 * - **estadia mínima e máxima** em noites (saída − chegada), as da data de chegada ("on arrival"); não valem no day use
 *   (chegada = saída, H5), que não tem noite;
 * - **antecedência** em dias da data operacional até a chegada, as da data de chegada; a chegada antes da data
 *   operacional nunca é vendável (e então a antecedência não é avaliada).
 *
 * Uma restrição efetiva com o mínimo acima do máximo (o `rates` não a grava; só possível com linhas antigas de um tipo
 * que voltou ao plano) recusa qualquer estadia por um dos dois motivos, sem regra especial.
 */
object SaleRules {
    fun evaluate(
        query: StayQuery,
        channel: SalesChannel,
        conditions: SaleConditions,
    ): List<SaleRefusal> =
        buildList {
            addAll(planAndRoomType(query, channel, conditions))
            addAll(advance(query, conditions))
            addAll(restrictions(query, conditions))
        }

    private fun planAndRoomType(
        query: StayQuery,
        channel: SalesChannel,
        conditions: SaleConditions,
    ): List<SaleRefusal> =
        buildList {
            if (!conditions.planActive) {
                add(SaleRefusal(RatesErrorCodes.RATE_PLAN_INACTIVE, "O plano tarifário está desativado."))
            }
            if (channel !in conditions.channels) {
                add(SaleRefusal(RatesErrorCodes.SALES_CHANNEL_NOT_ALLOWED, "O plano não é vendido neste canal."))
            }
            if (query.roomTypeId !in conditions.planRoomTypeIds) {
                add(SaleRefusal(RatesErrorCodes.ROOM_TYPE_NOT_IN_PLAN, "O tipo de UH não está no plano tarifário."))
            }
            if (!conditions.roomTypeActive) {
                add(SaleRefusal(RatesErrorCodes.ROOM_TYPE_INACTIVE, "O tipo de UH está desativado."))
            }
            if (query.adults > conditions.maxAdults || query.childAges.size > conditions.maxChildren) {
                add(
                    SaleRefusal(
                        RatesErrorCodes.OCCUPANCY_EXCEEDS_ROOM_TYPE,
                        "O tipo de UH recebe até ${conditions.maxAdults} adulto(s) e " +
                            "${conditions.maxChildren} criança(s).",
                    ),
                )
            }
        }

    private fun advance(
        query: StayQuery,
        conditions: SaleConditions,
    ): List<SaleRefusal> {
        val arrival = query.arrival
        if (arrival.isBefore(conditions.businessDate)) {
            return listOf(
                SaleRefusal(
                    RatesErrorCodes.ARRIVAL_BEFORE_BUSINESS_DATE,
                    "A chegada é antes da data operacional do hotel (${conditions.businessDate.br()}).",
                    listOf(arrival),
                ),
            )
        }
        val days = ChronoUnit.DAYS.between(conditions.businessDate, arrival)
        val atArrival = conditions.restrictionOn(arrival)
        return buildList {
            atArrival.minAdvanceDays?.takeIf { days < it }?.let {
                add(
                    SaleRefusal(
                        RatesErrorCodes.MIN_ADVANCE_NOT_MET,
                        "A chegada em ${arrival.br()} exige reserva com ao menos $it dia(s) de antecedência.",
                        listOf(arrival),
                    ),
                )
            }
            atArrival.maxAdvanceDays?.takeIf { days > it }?.let {
                add(
                    SaleRefusal(
                        RatesErrorCodes.MAX_ADVANCE_EXCEEDED,
                        "A chegada em ${arrival.br()} aceita reserva com até $it dia(s) de antecedência.",
                        listOf(arrival),
                    ),
                )
            }
        }
    }

    private fun restrictions(
        query: StayQuery,
        conditions: SaleConditions,
    ): List<SaleRefusal> {
        val arrival = query.arrival
        val departure = query.departure
        val atArrival = conditions.restrictionOn(arrival)
        val nights = StayRules.nightCount(query)
        val closed = StayRules.nights(query).filter { conditions.restrictionOn(it).closed }
        return buildList {
            if (atArrival.closedToArrival) {
                add(
                    SaleRefusal(
                        RatesErrorCodes.CLOSED_TO_ARRIVAL,
                        "${arrival.br()} está fechada para chegada.",
                        listOf(arrival),
                    ),
                )
            }
            if (conditions.restrictionOn(departure).closedToDeparture) {
                add(
                    SaleRefusal(
                        RatesErrorCodes.CLOSED_TO_DEPARTURE,
                        "${departure.br()} está fechada para saída.",
                        listOf(departure),
                    ),
                )
            }
            atArrival.minStay?.takeIf { !query.dayUse && nights < it }?.let {
                add(
                    SaleRefusal(
                        RatesErrorCodes.MIN_STAY_NOT_MET,
                        "A chegada em ${arrival.br()} exige ao menos $it noite(s).",
                        listOf(arrival),
                    ),
                )
            }
            atArrival.maxStay?.takeIf { !query.dayUse && nights > it }?.let {
                add(
                    SaleRefusal(
                        RatesErrorCodes.MAX_STAY_EXCEEDED,
                        "A chegada em ${arrival.br()} aceita até $it noite(s).",
                        listOf(arrival),
                    ),
                )
            }
            if (closed.isNotEmpty()) {
                add(
                    SaleRefusal(
                        RatesErrorCodes.STAY_DATE_CLOSED,
                        "Fechada para venda em ${closed.joinToString(", ") { it.br() }}.",
                        closed,
                    ),
                )
            }
        }
    }

    private fun SaleConditions.restrictionOn(date: LocalDate): EffectiveRestriction =
        restrictions[date] ?: EffectiveRestriction.NONE
}
