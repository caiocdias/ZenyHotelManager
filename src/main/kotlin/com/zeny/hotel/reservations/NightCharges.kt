package com.zeny.hotel.reservations

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.NewPosting
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** O que uma cobrança da noite lança: a hospedagem, um pacote da noite ou a taxa de turismo. */
enum class NightChargeKind {
    /** A diária sem os pacotes inclusos, no código `ROOM_CHARGE` (quem lança o resolve). */
    ROOM,

    /** Um pacote da noite (incluso ou adicional), no código dele. */
    PACKAGE,

    /** A taxa de turismo por pessoa e noite, no código `TOURISM_FEE` (quem lança o resolve). */
    TOURISM_FEE,
}

/**
 * Uma cobrança devida de uma noite da estadia (M6 E11): [transactionCodeId] é nulo em [NightChargeKind.ROOM] e
 * [NightChargeKind.TOURISM_FEE]; [packageId] só no pacote (o `endofday` não usa tipos do `rates`); [sourceReference] é
 * a chave de idempotência do lançamento, só com ids e datas (RN-13). [includedInRate] diz se o valor já está na tarifa
 * cotada (a hospedagem e os pacotes inclusos): esses nunca levam taxa de serviço (RN-11).
 */
data class NightCharge(
    val kind: NightChargeKind,
    val date: LocalDate,
    val transactionCodeId: TransactionCodeId?,
    val packageId: UUID?,
    val quantity: BigDecimal,
    val unitPrice: Money,
    val description: String?,
    val sourceReference: String,
    val includedInRate: Boolean,
) {
    /** O valor que o `billing` lança: valor unitário × quantidade, arredondado pela regra de `Money` (RN-02). */
    val amount: Money get() = unitPrice * quantity

    /**
     * O lançamento do fechamento do dia (origem `END_OF_DAY`): o código é o da cobrança ou, sem ele, o que [codes]
     * resolve para o [kind]; sem taxa de serviço (`Percent.ZERO`) no que já está na tarifa, e a regra do código nos
     * demais (`null`).
     */
    fun toNewPosting(codes: (NightChargeKind) -> TransactionCodeId): NewPosting =
        NewPosting(
            transactionCodeId = transactionCodeId ?: codes(kind),
            unitPrice = unitPrice,
            quantity = quantity,
            description = description,
            source = PostingSource.END_OF_DAY,
            serviceChargeRate = if (includedInRate) Percent.ZERO else null,
            sourceReference = sourceReference,
        )
}

/**
 * As cobranças devidas de uma noite congelada da estadia (RN-14; o mesmo cálculo do fechamento do dia e do
 * check-out). Valores zero não se lançam.
 */
object ReservationNightCharges {
    private const val PREFIX = "reservations.reservation"
    private const val TOURISM_FEE_DESCRIPTION = "Taxa de turismo"

    /** A base das chaves de lançamento da noite: `reservations.reservation:<id>:<noite>`. */
    fun reference(
        id: ReservationId,
        night: LocalDate,
    ): String = "$PREFIX:$id:$night"

    /**
     * A hospedagem (`roomAmount` menos os pacotes inclusos, que já estão nela), cada pacote da noite, inclusos e
     * adicionais, em receitas separadas (RF-TAR-06) e, fora do day use e com a taxa configurada, a taxa de turismo
     * (`adultos + crianças` × [tourismFeePerPersonNight]). Vazia se [night] não é uma noite congelada da reserva.
     */
    fun of(
        reservation: ReservationSummary,
        night: LocalDate,
        tourismFeePerPersonNight: Money?,
    ): List<NightCharge> {
        val frozen = reservation.nightlyRates.firstOrNull { it.date == night } ?: return emptyList()
        val base = reference(reservation.id, night)
        val room =
            NightCharge(
                kind = NightChargeKind.ROOM,
                date = night,
                transactionCodeId = null,
                packageId = null,
                quantity = BigDecimal.ONE,
                unitPrice = frozen.roomAmount - frozen.includedPackagesAmount,
                description = null,
                sourceReference = "$base:room",
                includedInRate = true,
            )
        val packages =
            frozen.packages.map {
                NightCharge(
                    kind = NightChargeKind.PACKAGE,
                    date = night,
                    transactionCodeId = it.transactionCodeId,
                    packageId = it.packageId.value,
                    quantity = BigDecimal(it.quantity),
                    unitPrice = it.unitPrice,
                    description = it.name,
                    sourceReference = "$base:package:${it.packageId}",
                    includedInRate = it.includedInRate,
                )
            }
        val tourism =
            if (reservation.dayUse || tourismFeePerPersonNight == null) {
                emptyList()
            } else {
                listOf(
                    NightCharge(
                        kind = NightChargeKind.TOURISM_FEE,
                        date = night,
                        transactionCodeId = null,
                        packageId = null,
                        quantity = BigDecimal(reservation.adults + reservation.childAges.size),
                        unitPrice = tourismFeePerPersonNight,
                        description = TOURISM_FEE_DESCRIPTION,
                        sourceReference = "$base:tourism-fee",
                        includedInRate = false,
                    ),
                )
            }
        return (listOf(room) + packages + tourism).filterNot { it.amount.isZero() }
    }
}

/**
 * As cobranças devidas de uma hospedada até uma data ([ReservationsApi.chargesDue]): a [folioId] da conta da reserva e
 * as [charges] de cada noite, em ordem de noite.
 */
data class StayCharges(
    val reservationId: ReservationId,
    val folioId: FolioId,
    val charges: List<NightCharge>,
)
