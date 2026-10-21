package com.zeny.hotel.reservations

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Id de um bloqueio de grupo (`RoomBlock`, RF-RES-09). */
@JvmInline
value class RoomBlockId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): RoomBlockId = RoomBlockId(UuidV7.generate())
    }
}

/**
 * Situação do bloqueio (M4 E11): [ACTIVE] segura as unidades não utilizadas no contador `blocked` do inventário e
 * aceita pick-up (até a data de corte); [RELEASED] devolveu o não utilizado ao inventário (`releaseUnused`, RN-16) e
 * não aceita mais pick-up nem alteração. Nas respostas é `x-extensible-enum`.
 */
enum class RoomBlockStatus {
    ACTIVE,
    RELEASED,
}

/**
 * As unidades de um tipo de UH num período de noites (de [from] a [to], inclusive: a saída não é noite) para o
 * bloqueio: [quantity] UHs por noite, à tarifa do grupo [rate] por UH e por noite (a diária de quem fizer o pick-up,
 * congelada na reserva).
 */
data class BlockAllocationRange(
    val roomTypeId: RoomTypeId,
    val from: LocalDate,
    val to: LocalDate,
    val quantity: Int,
    val rate: Money,
)

/**
 * Um bloqueio de grupo a criar (`POST /api/v1/room-blocks` e `ReservationsApi.createBlock`): o nome do grupo, o
 * titular (um hóspede **ou** uma empresa do `guests`), o plano tarifário (de onde vêm as políticas de cancelamento e de
 * garantia copiadas nas reservas do pick-up), a data de corte e as unidades por tipo e período.
 */
data class NewRoomBlock(
    val name: String,
    val holderGuestId: GuestId? = null,
    val holderCompanyId: CompanyId? = null,
    val ratePlanId: RatePlanId,
    val cutoffDate: LocalDate,
    val allocations: List<BlockAllocationRange>,
)

/**
 * A alteração de um bloqueio ativo: o nome, a data de corte e as unidades das noites a partir da data operacional (as
 * anteriores ficam como estão). As unidades pedidas substituem as gravadas dessas noites.
 */
data class RoomBlockChange(
    val name: String,
    val cutoffDate: LocalDate,
    val allocations: List<BlockAllocationRange>,
)

/**
 * As unidades do bloqueio de um tipo de UH numa noite: [quantity] contratadas, [pickedUp] já convertidas em reservas
 * ativas, [released] devolvidas ao inventário pela liberação e a tarifa do grupo [rate].
 */
data class BlockAllocationSummary(
    val roomTypeId: RoomTypeId,
    val date: LocalDate,
    val quantity: Int,
    val pickedUp: Int,
    val released: Int,
    val rate: Money,
) {
    /** As unidades que ainda aceitam pick-up (num bloqueio ativo). */
    val remaining: Int get() = quantity - pickedUp - released
}

/** Um bloqueio de grupo como os outros módulos o veem (M6 fechamento, M10 eventos). */
data class RoomBlockSummary(
    val id: RoomBlockId,
    val code: DocumentNumber,
    val name: String,
    val holderGuestId: GuestId?,
    val holderCompanyId: CompanyId?,
    val ratePlanId: RatePlanId,
    val cutoffDate: LocalDate,
    val status: RoomBlockStatus,
    /** A conta master do grupo no `billing` (dono `GROUP` com o id do bloqueio). */
    val folioId: FolioId,
    /** As unidades por tipo de UH e noite, em ordem de tipo (pelo id) e data. */
    val allocations: List<BlockAllocationSummary>,
    val releasedAt: Instant?,
    val releasedOn: LocalDate?,
    val releasedBy: UserId?,
    val version: Long,
    val createdAt: Instant,
    val createdBy: UserId?,
) {
    override fun toString(): String = "RoomBlockSummary(id=$id, code=$code, status=$status)"
}

/**
 * Uma reserva do pick-up: o tipo de UH, a chegada e a saída (noites do bloqueio; sem day use), a ocupação, o segmento,
 * os pedidos especiais e os hóspedes da estadia (vazio = o titular da reserva agrupadora como principal). A diária é a
 * tarifa do grupo de cada noite, congelada; o plano é o do bloqueio.
 */
data class BlockReservationRequest(
    val roomTypeId: RoomTypeId,
    val arrival: LocalDate,
    val departure: LocalDate,
    val adults: Int,
    val childAges: List<Int> = emptyList(),
    val segment: MarketSegment = MarketSegment.EVENT,
    val specialRequests: String? = null,
    val guests: List<NewStayGuest> = emptyList(),
)

/**
 * Uma rooming list (ou um pick-up, com uma reserva): as reservas criadas a partir do bloqueio numa só reserva
 * agrupadora, com o titular [holderGuestId] (nulo = o hóspede titular do bloqueio; obrigatório num bloqueio de
 * empresa) e a origem.
 */
data class RoomingList(
    val holderGuestId: GuestId?,
    val origin: ReservationOrigin,
    val reservations: List<BlockReservationRequest>,
)
