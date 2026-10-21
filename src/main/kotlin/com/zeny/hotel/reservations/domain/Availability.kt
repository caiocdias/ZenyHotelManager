package com.zeny.hotel.reservations.domain

import com.zeny.hotel.rates.SaleRefusal
import com.zeny.hotel.reservations.ReservationsErrorCodes
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * A disponibilidade de um tipo de UH nas noites de uma estadia (M4 E06, RF-TAR-08 e 11): a estadia cabe no inventário
 * quando **toda** noite tem linha mantida ([outOfInventory] vazio) e ao menos uma unidade disponível ([soldOut] vazio:
 * disponível = total − vendidas − bloqueadas − OOO + limite de overbooking ≥ 1). [units] é o menor disponível entre as
 * noites com linha (zero sem nenhuma). É uma leitura sem trava: a reserva (E07) confere de novo, travando as linhas.
 */
data class StayAvailability(
    val outOfInventory: List<LocalDate>,
    val soldOut: List<LocalDate>,
    val units: Int,
) {
    val available: Boolean get() = outOfInventory.isEmpty() && soldOut.isEmpty()

    /** Os motivos de a estadia não caber (vazio se cabe), com as noites: esgotadas e fora do inventário. */
    fun refusals(): List<SaleRefusal> =
        buildList {
            if (soldOut.isNotEmpty()) {
                add(
                    SaleRefusal(
                        ReservationsErrorCodes.ROOM_TYPE_UNAVAILABLE,
                        "Não há UH disponível do tipo em ${soldOut.joinToString(", ") { it.format(DATE) }}.",
                        soldOut,
                    ),
                )
            }
            if (outOfInventory.isNotEmpty()) {
                add(
                    SaleRefusal(
                        ReservationsErrorCodes.INVENTORY_DATE_OUT_OF_HORIZON,
                        "Há noites fora do inventário mantido (até ${InventoryHorizon.DAYS} dias da data operacional).",
                        outOfInventory,
                    ),
                )
            }
        }

    companion object {
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

        /** A disponibilidade das [nights] pelas linhas [rows] do tipo (a noite sem linha está fora do inventário). */
        fun of(
            nights: List<LocalDate>,
            rows: Map<LocalDate, InventoryCounters>,
        ): StayAvailability {
            val kept = nights.mapNotNull { night -> rows[night]?.let { night to it.available } }
            return StayAvailability(
                outOfInventory = nights.filter { it !in rows },
                soldOut = kept.filter { it.second < 1 }.map { it.first },
                units = kept.minOfOrNull { it.second }?.coerceAtLeast(0) ?: 0,
            )
        }
    }
}

/** As regras de formato das consultas de disponibilidade e de cotação (M4 E06). */
object AvailabilityRules {
    /** Tipos de UH e planos por consulta, no máximo. */
    const val MAX_IDS = 50

    /** Adultos e crianças por cotação, no máximo (a ocupação máxima do tipo recusa antes). */
    const val MAX_ADULTS = 40
    const val MAX_CHILDREN = 20

    /** A lista de ids pedida, sem repetir e até [MAX_IDS]; senão `400 AVAILABILITY_QUERY_INVALID` no [field]. */
    fun <T> requireIds(
        ids: List<T>,
        field: String,
    ): List<T> {
        if (ids.size > MAX_IDS || ids.toSet().size != ids.size) {
            throw InventoryRules.invalid(
                ReservationsErrorCodes.AVAILABILITY_QUERY_INVALID,
                field,
                "Informe até $MAX_IDS ids, sem repetir.",
            )
        }
        return ids
    }

    /** A ocupação dentro dos limites da consulta (o formato fino é do `rates`: ao menos um adulto, idades). */
    fun requireOccupancy(
        adults: Int,
        childAges: List<Int>,
    ) {
        if (adults > MAX_ADULTS) {
            throw InventoryRules.invalid(
                ReservationsErrorCodes.AVAILABILITY_QUERY_INVALID,
                "adults",
                "Informe até $MAX_ADULTS adultos.",
            )
        }
        if (childAges.size > MAX_CHILDREN) {
            throw InventoryRules.invalid(
                ReservationsErrorCodes.AVAILABILITY_QUERY_INVALID,
                "childAges",
                "Informe até $MAX_CHILDREN crianças.",
            )
        }
    }
}
