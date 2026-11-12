package com.zeny.hotel.billing

import java.time.LocalDate
import java.util.UUID

/**
 * A porta pela qual o `billing` descreve o dono de uma conta no extrato (M5 E11, D-E11-12): o `billing` não conhece
 * reservas, grupos nem eventos, então quem é dono de um tipo de conta o implementa (o `reservations` para
 * [FolioOwnerType.RESERVATION]; grupo, evento e empresa nos marcos seguintes). No máximo um descritor por [ownerType];
 * dois do mesmo tipo recusam a partida. Dono sem descritor aparece só pelo tipo.
 */
interface FolioOwnerDescriber {
    val ownerType: FolioOwnerType

    /** O dono descrito, ou `null` se ele não existe mais. */
    fun describe(ownerId: UUID): FolioOwnerDescription?
}

/**
 * Como o dono da conta aparece no extrato: o [label] (código da reserva, nome do grupo), o hóspede, a UH e as datas
 * da estadia, quando houver. O [guestName] é dado pessoal e **nunca** entra em `toString`.
 */
data class FolioOwnerDescription(
    val label: String,
    val guestName: String?,
    val roomNumber: String?,
    val arrival: LocalDate?,
    val departure: LocalDate?,
) {
    override fun toString(): String =
        "FolioOwnerDescription(label=$label, roomNumber=$roomNumber, arrival=$arrival, departure=$departure)"
}
