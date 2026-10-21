package com.zeny.hotel.rates

import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.Money
import java.time.LocalDate

/**
 * Uma estadia a precificar ou avaliar (M4 E04, `RatesApi.priceFor` e `isSellable`): o plano, o tipo de UH, a chegada
 * e a saída (datas de negócio; as noites vão da chegada à véspera da saída, de 1 a 365), o número de adultos (ao menos
 * um) e a **idade de cada criança** em anos completos na data de chegada (de 0 à idade limite de criança do hotel,
 * `HotelApi.policy().childMaxAge`; acima dela a pessoa é adulta e conta em [adults]).
 */
data class StayQuery(
    val ratePlanId: RatePlanId,
    val roomTypeId: RoomTypeId,
    val arrival: LocalDate,
    val departure: LocalDate,
    val adults: Int,
    val childAges: List<Int> = emptyList(),
)

/**
 * Um motivo de recusa da vendabilidade ou do preço (M4 E04): o [code] estável (em [RatesErrorCodes]), a mensagem em
 * pt-BR para o usuário final (sem dado pessoal) e as datas a que ele se refere ([dates]: as noites fechadas, sem preço;
 * a data de chegada no CTA e na estadia mínima; a de saída no CTD; vazia quando não é de uma data). Quem chama decide a
 * resposta HTTP (o `reservations` recusa com `422` e o `code`).
 */
data class SaleRefusal(
    val code: ErrorCode,
    val message: String,
    val dates: List<LocalDate> = emptyList(),
)

/**
 * A vendabilidade de uma estadia (`RatesApi.isSellable`): vendável quando não há nenhum motivo de recusa; senão,
 * [refusals] traz **todos** os motivos encontrados, numa ordem fixa (ver `RatesApi.isSellable`).
 */
data class Sellability(
    val refusals: List<SaleRefusal>,
) {
    val sellable: Boolean get() = refusals.isEmpty()

    /** Os códigos dos motivos, na ordem. */
    val codes: List<ErrorCode> get() = refusals.map { it.code }
}

/** O resultado de `RatesApi.priceFor`: o preço ([Priced]) ou os motivos por que a estadia não tem preço. */
sealed interface StayPriceResult {
    data class Priced(
        val price: StayPrice,
    ) : StayPriceResult

    data class NotPriced(
        val refusals: List<SaleRefusal>,
    ) : StayPriceResult
}

/**
 * O preço de uma estadia (M4 E04, RF-TAR-02, 03, 06, RN-02): o detalhe de cada noite ([nights], em ordem de data) e os
 * totais, todos em [Money] e **sempre iguais à soma das noites** (sem arredondamento: só somas e produtos por
 * inteiros dos preços efetivos, que já passaram pela regra única de `Money` na derivação).
 *
 * - [roomTotal]: as diárias (adultos, adultos extras e crianças), que já contêm os pacotes inclusos;
 * - [includedPackagesTotal]: a parte das diárias que é de pacotes inclusos (informativa; lançada à parte quando a
 *   classificação fiscal exigir, RF-TAR-06 e RF-FIS-04);
 * - [accommodationTotal] = [roomTotal] − [includedPackagesTotal] (a hospedagem sem os pacotes inclusos);
 * - [additionalPackagesTotal]: os pacotes adicionais, cobrados além da diária;
 * - [total] = [roomTotal] + [additionalPackagesTotal] (o que o hóspede paga).
 */
data class StayPrice(
    val query: StayQuery,
    val nights: List<NightPrice>,
    val roomTotal: Money,
    val includedPackagesTotal: Money,
    val accommodationTotal: Money,
    val additionalPackagesTotal: Money,
    val total: Money,
)

/**
 * O preço de uma noite (a data é a da noite, de negócio). A diária [roomAmount] = [baseRate] (a de um adulto, ou a de
 * dois com dois ou mais) + [extraAdultsAmount] ([extraAdults] × o preço do adulto extra, por adulto além do segundo) +
 * a soma de [children] (o preço da faixa etária de cada criança). [packages] traz todos os pacotes do plano nesta noite
 * (os inclusos, que são parte da diária, e os adicionais); [accommodationAmount] = [roomAmount] − os inclusos;
 * [additionalPackagesAmount] = os adicionais; [total] = [roomAmount] + [additionalPackagesAmount].
 */
data class NightPrice(
    val date: LocalDate,
    val baseRate: Money,
    val extraAdults: Int,
    val extraAdultsAmount: Money,
    val children: List<ChildCharge>,
    val roomAmount: Money,
    val packages: List<PackageCharge>,
    val includedPackagesAmount: Money,
    val accommodationAmount: Money,
    val additionalPackagesAmount: Money,
    val total: Money,
)

/** O preço de uma criança numa noite: a idade, a faixa etária atual do hotel em que ela cai e o preço da faixa. */
data class ChildCharge(
    val age: Int,
    val minAge: Int,
    val maxAge: Int,
    val label: String,
    val amount: Money,
)

/**
 * Um pacote do plano numa noite (RF-TAR-06): [quantity] × [unitPrice] = [amount] (por noite: 1; por pessoa e noite: o
 * número de hóspedes, adultos e crianças; por estadia: 1, só na noite da chegada). [includedInRate] diz se é parte da
 * diária (incluso) ou cobrado além dela (adicional); [postingRhythm] e [transactionCodeId] dizem como ele é lançado
 * (na chegada pelo check-in do M5, todo dia pelo fechamento do M6), o que não muda a noite a que o valor pertence.
 */
data class PackageCharge(
    val packageId: PackageId,
    val code: String,
    val name: String,
    val transactionCodeId: TransactionCodeId,
    val pricing: PackagePricing,
    val postingRhythm: PackagePostingRhythm,
    val includedInRate: Boolean,
    val quantity: Int,
    val unitPrice: Money,
    val amount: Money,
)

/**
 * A cotação de uma estadia em vários planos e tipos de UH de uma vez (M4 E06, RF-TAR-11; `RatesApi.stayOptions`): a
 * chegada, a saída, a ocupação e o canal valem para todas as combinações; [roomTypeIds] são os tipos cotados (a ordem
 * não importa) e [ratePlanIds] os planos (nulo = todos os planos **ativos** que têm algum dos tipos).
 */
data class StayOptionsQuery(
    val arrival: LocalDate,
    val departure: LocalDate,
    val adults: Int,
    val childAges: List<Int>,
    val channel: SalesChannel,
    val roomTypeIds: Set<RoomTypeId>,
    val ratePlanIds: Set<RatePlanId>? = null,
) {
    /** A estadia num plano e num tipo de UH. */
    fun stay(
        ratePlanId: RatePlanId,
        roomTypeId: RoomTypeId,
    ) = StayQuery(ratePlanId, roomTypeId, arrival, departure, adults, childAges)
}

/**
 * Uma combinação de plano e tipo de UH da cotação (`RatesApi.stayOptions`): a vendabilidade no canal, **a mesma** de
 * `RatesApi.isSellable` (vendável implica ter preço), e o preço, **o mesmo** de `RatesApi.priceFor` (nulo quando a
 * estadia não tem preço; presente sempre que vendável, e às vezes também quando não é, por outro motivo).
 */
data class StayOption(
    val ratePlan: RatePlanSummary,
    val roomTypeId: RoomTypeId,
    val sellability: Sellability,
    val price: StayPrice?,
)
