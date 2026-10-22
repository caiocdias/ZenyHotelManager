package com.zeny.hotel.reservations.application

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.guests.DocumentType
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.guests.MAX_NAME_MATCHES
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.rates.RatePlanCategory
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.SalesChannel
import com.zeny.hotel.reservations.BookingId
import com.zeny.hotel.reservations.MarketSegment
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationOrigin
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationsErrorCodes
import com.zeny.hotel.reservations.ReservationsPermissions
import com.zeny.hotel.reservations.RoomAssignmentId
import com.zeny.hotel.reservations.RoomBlockId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.Percent
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/**
 * As listas operacionais por data (RF-RES-13). A data é sempre uma **data operacional** do hotel (RN-01; padrão: a
 * corrente, `HotelApi.currentBusinessDate()`), nunca a do relógio.
 */
enum class OperationalList {
    /** Chegada na data, em qualquer situação menos `CANCELLED` e `NO_SHOW`. */
    ARRIVALS,

    /**
     * Saída na data, em qualquer situação menos `CANCELLED` e `NO_SHOW`; na data operacional corrente, também as
     * hospedadas (`IN_HOUSE`) com a saída já passada (saída pendente: o check-out nunca é automático, RF-FEC-02).
     */
    DEPARTURES,

    /**
     * As hospedadas (`IN_HOUSE`, a partir do M5) cuja estadia alcança a data (chegada até a data, saída a partir
     * dela); na data operacional corrente, também as com a saída já passada.
     */
    IN_HOUSE,

    /**
     * Os no-shows previstos: as confirmadas (`CONFIRMED`) com chegada na data, que ainda não chegaram (só a confirmada
     * vira no-show, SPEC §8.4); na data operacional corrente, também as com a chegada já passada (chegada pendente,
     * que o fechamento do dia do M6 marca como no-show).
     */
    EXPECTED_NO_SHOWS,
}

/**
 * Os filtros da busca de reservas (RF-RES-11), combinados com "e"; nulos ou vazios não filtram. [guestIds]: as
 * reservas em que algum desses hóspedes é titular da reserva agrupadora ou hóspede da estadia (a busca por nome e por
 * documento chegam aqui com os ids vindos da `GuestsApi`; vazio = nenhuma reserva). O período ([from], [to],
 * inclusive) pega as estadias que tocam o intervalo, do dia da chegada ao dia da saída.
 */
data class ReservationSearch(
    val code: String? = null,
    val bookingCode: String? = null,
    val externalId: String? = null,
    val guestIds: List<GuestId>? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val statuses: Set<ReservationStatus> = emptySet(),
)

/** A UH alocada de uma reserva na lista (E10): a alocação ativa, com "não mover" e a marca de realocação. */
data class ListedAssignment(
    val id: RoomAssignmentId,
    val roomId: RoomId,
    val roomNumber: String?,
    val doNotMove: Boolean,
    val reallocationRequired: Boolean,
)

/**
 * Uma reserva numa busca ou lista: só o que a lista mostra, lido numa consulta (sem as noites, os termos nem os
 * pedidos especiais). Os hóspedes vão só pelos ids (o nome fica no `guests`). [sortDate]: a data da ordem da lista
 * (a chegada; nas saídas, a saída), a chave do cursor com o id.
 */
data class ReservationListRow(
    val id: ReservationId,
    val code: DocumentNumber,
    val bookingId: BookingId,
    val bookingCode: DocumentNumber,
    val holderGuestId: GuestId,
    val primaryGuestId: GuestId?,
    val origin: ReservationOrigin,
    val channel: SalesChannel,
    val externalId: String?,
    val status: ReservationStatus,
    val provisionalUntil: Instant?,
    val roomTypeId: RoomTypeId,
    val ratePlanId: RatePlanId,
    val ratePlanCategory: RatePlanCategory,
    val arrival: LocalDate,
    val departure: LocalDate,
    val adults: Int,
    val children: Int,
    val segment: MarketSegment,
    val total: Money,
    val folioId: FolioId,
    val blockId: RoomBlockId?,
    val assignment: ListedAssignment?,
    val version: Long,
    val sortDate: LocalDate,
) {
    val dayUse: Boolean get() = arrival == departure
}

/**
 * Uma reserva na busca ou numa lista operacional, com o número atual da UH alocada e, nas listas da data operacional
 * corrente, se a chegada ou a saída já passou ([arrivalOverdue]: confirmada com a chegada antes da data operacional;
 * [departureOverdue]: hospedada com a saída antes dela).
 */
data class ReservationListItem(
    val row: ReservationListRow,
    val arrivalOverdue: Boolean,
    val departureOverdue: Boolean,
)

/**
 * Uma reserva confirmada com garantia por depósito cujo total líquido de depósitos da conta (`BillingApi.netDeposits`)
 * ficou abaixo do que a política copiada exige (um reembolso depois da confirmação, ou a troca para um plano de
 * depósito maior: a confirmação não é desfeita; decisão 8 do orquestrador na E09).
 */
data class DepositShortfall(
    val row: ReservationListRow,
    val requiredDeposit: Money,
    val depositReceived: Money,
    val guaranteedAt: Instant,
) {
    val missing: Money get() = requiredDeposit - depositReceived
}

/**
 * As consultas da busca e das listas (`JdbcClient`, só o schema `reservations`), sempre na ordem (data, id) e depois
 * da posição [after], até [limit].
 */
interface ReservationQueryStore {
    fun search(
        search: ReservationSearch,
        after: Pair<LocalDate, ReservationId>?,
        limit: Int,
    ): List<ReservationListRow>

    /** A lista [list] na data [date]; [businessDate] decide se entram as chegadas e saídas pendentes. */
    fun operational(
        list: OperationalList,
        date: LocalDate,
        businessDate: LocalDate,
        after: Pair<LocalDate, ReservationId>?,
        limit: Int,
    ): List<ReservationListRow>

    /**
     * As confirmadas cuja política copiada é `DEPOSIT` e cuja garantia registrada é um depósito, em ordem de chegada,
     * até [limit].
     */
    fun depositGuaranteed(limit: Int): List<DepositGuaranteedRow>
}

/**
 * Uma confirmada com garantia por depósito: a linha, o percentual de depósito da política copiada e quando a garantia
 * foi registrada.
 */
data class DepositGuaranteedRow(
    val row: ReservationListRow,
    val depositPercent: Percent,
    val guaranteedAt: Instant,
)

/**
 * A busca de reservas (RF-RES-11) e as listas operacionais por data (RF-RES-13), só leitura, com
 * `reservations.reservations.read`. A busca por nome e por documento passa **só pela `GuestsApi`** (H13): o
 * `reservations` nunca lê o schema `guests` nem guarda nome ou documento; o número do documento chega no corpo, nunca
 * na URL, e não vai a log nem à trilha. As listas usam a data operacional do hotel (RN-01, H15), nunca a do relógio.
 */
@Service
@Transactional(readOnly = true)
class ReservationQueries(
    private val store: ReservationQueryStore,
    private val guests: GuestsApi,
    private val rooms: RoomsApi,
    private val hotel: HotelApi,
    private val billing: BillingApi,
    private val authorization: Authorization,
) {
    /**
     * A busca por número, número da reserva agrupadora, número da OTA, nome, período e situação, por cursor em ordem de
     * chegada. Com [name], os perfis ativos cujo nome ou nome social contém cada palavra (`GuestsApi.findIdsByName`,
     * até [MAX_NAME_MATCHES]; mais que isso, `422 GUEST_NAME_TOO_BROAD`: acrescente palavras).
     */
    fun search(
        search: ReservationSearch,
        name: String?,
        page: PageParams,
    ): CursorPage<ReservationListItem> {
        authorization.require(ReservationsPermissions.RESERVATIONS_READ)
        requirePeriod(search.from, search.to)
        val byName = name?.let { guestIdsNamed(it) }
        val criteria =
            search.copy(
                code = search.code?.normalizedCode(),
                bookingCode = search.bookingCode?.normalizedCode(),
                guestIds = byName,
            )
        return page(page) { after, limit -> store.search(criteria, after, limit) }
    }

    /**
     * A busca pelo documento de um hóspede (titular ou hóspede da estadia), pelo índice cego do `guests`
     * (`GuestsApi.findByDocument`: só perfis ativos, sem decifrar nenhum documento), com as situações [statuses].
     */
    fun searchByDocument(
        type: DocumentType,
        issuingCountry: String?,
        number: String,
        statuses: Set<ReservationStatus>,
        page: PageParams,
    ): CursorPage<ReservationListItem> {
        authorization.require(ReservationsPermissions.RESERVATIONS_READ)
        val guestIds = guests.findByDocument(type, issuingCountry, number).map { it.id }
        val criteria = ReservationSearch(guestIds = guestIds, statuses = statuses)
        return page(page) { after, limit -> store.search(criteria, after, limit) }
    }

    /** A lista operacional [list] na data operacional [date] (padrão: a corrente do hotel), por cursor. */
    fun operational(
        list: OperationalList,
        date: LocalDate?,
        page: PageParams,
    ): CursorPage<ReservationListItem> {
        authorization.require(ReservationsPermissions.RESERVATIONS_READ)
        val businessDate = hotel.currentBusinessDate()
        val day = date ?: businessDate
        return page(page, businessDate) { after, limit -> store.operational(list, day, businessDate, after, limit) }
    }

    /**
     * As confirmadas com garantia por depósito cujo depósito líquido atual ficou abaixo do exigido pela política
     * copiada, em ordem de chegada (até [DEPOSIT_CANDIDATES] candidatas).
     */
    fun depositShortfalls(): List<DepositShortfall> {
        authorization.require(ReservationsPermissions.RESERVATIONS_READ)
        val candidates = store.depositGuaranteed(DEPOSIT_CANDIDATES)
        val deposits = billing.netDeposits(candidates.map { it.row.folioId })
        val numbers = roomNumbers(candidates.map { it.row })
        return candidates.mapNotNull { candidate ->
            val required = candidate.depositPercent.of(candidate.row.total)
            val received = deposits[candidate.row.folioId] ?: Money.ZERO
            if (received >= required) {
                null
            } else {
                DepositShortfall(candidate.row.withRoomNumber(numbers), required, received, candidate.guaranteedAt)
            }
        }
    }

    private fun page(
        page: PageParams,
        businessDate: LocalDate = hotel.currentBusinessDate(),
        rows: (Pair<LocalDate, ReservationId>?, Int) -> List<ReservationListRow>,
    ): CursorPage<ReservationListItem> {
        val after = page.decodedCursor()?.let { it.localDate(0) to ReservationId(it.uuid(1)) }
        val result =
            CursorPage.fromRows(rows(after, page.limit + 1), page.limit) {
                PageCursor.of(it.sortDate.toString(), it.id.toString())
            }
        val numbers = roomNumbers(result.items)
        return CursorPage(
            result.items.map { row ->
                ReservationListItem(
                    row = row.withRoomNumber(numbers),
                    arrivalOverdue = row.status == ReservationStatus.CONFIRMED && row.arrival < businessDate,
                    departureOverdue = row.status == ReservationStatus.IN_HOUSE && row.departure < businessDate,
                )
            },
            result.nextCursor,
        )
    }

    private fun guestIdsNamed(name: String): List<GuestId> {
        val ids = guests.findIdsByName(name, MAX_NAME_MATCHES)
        if (ids.size >= MAX_NAME_MATCHES) {
            throw BusinessRuleException(
                ReservationsErrorCodes.GUEST_NAME_TOO_BROAD,
                "O nome procurado encontra hóspedes demais; acrescente outra palavra do nome.",
            )
        }
        return ids
    }

    /** O número atual de cada UH alocada (lido na hora pela `RoomsApi`, uma vez por UH). */
    private fun roomNumbers(rows: List<ReservationListRow>): Map<RoomId, String> =
        rows
            .mapNotNull { it.assignment?.roomId }
            .distinct()
            .mapNotNull { id -> rooms.room(id)?.let { id to it.number } }
            .toMap()

    private fun ReservationListRow.withRoomNumber(numbers: Map<RoomId, String>): ReservationListRow =
        assignment?.let { copy(assignment = it.copy(roomNumber = numbers[it.roomId])) } ?: this

    private fun requirePeriod(
        from: LocalDate?,
        to: LocalDate?,
    ) {
        if (from != null && to != null && to.isBefore(from)) {
            val code = ReservationsErrorCodes.RESERVATION_PERIOD_INVALID
            val message = "O fim do período não pode ser antes do início."
            throw InvalidInputException(code, message, listOf(FieldViolation("to", code.value, message)))
        }
    }

    private fun String.normalizedCode(): String = trim().uppercase(Locale.ROOT)

    private companion object {
        const val DEPOSIT_CANDIDATES = 1000
    }
}
