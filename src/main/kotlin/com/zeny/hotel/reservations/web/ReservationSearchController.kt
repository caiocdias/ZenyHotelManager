package com.zeny.hotel.reservations.web

import com.zeny.hotel.guests.DocumentType
import com.zeny.hotel.rates.RatePlanCategory
import com.zeny.hotel.rates.SalesChannel
import com.zeny.hotel.reservations.MarketSegment
import com.zeny.hotel.reservations.ReservationOrigin
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.application.DepositShortfall
import com.zeny.hotel.reservations.application.ListedAssignment
import com.zeny.hotel.reservations.application.OperationalList
import com.zeny.hotel.reservations.application.ReservationListItem
import com.zeny.hotel.reservations.application.ReservationListRow
import com.zeny.hotel.reservations.application.ReservationQueries
import com.zeny.hotel.reservations.application.ReservationSearch
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.PageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val EXTENSIBLE_VALUE =
    "A lista pode crescer (`x-extensible-enum`): trate um valor desconhecido sem falhar."
private const val READ = "Exige `reservations.reservations.read`."
private const val DATE =
    "`date` é uma data operacional do hotel; sem ela, a data operacional corrente (`HotelApi`), nunca a do relógio."

@Schema(description = "A UH alocada de uma reserva na lista: a alocação ativa, com o número atual da UH.")
data class ListedAssignmentResponse(
    val id: UUID,
    val roomId: UUID,
    @field:Schema(description = "O número atual da UH; nulo se a UH não existe mais.")
    val roomNumber: String?,
    val doNotMove: Boolean,
    @field:Schema(description = "A UH entrou num OOO que atinge a estadia (realoque a reserva).")
    val reallocationRequired: Boolean,
) {
    companion object {
        fun of(assignment: ListedAssignment) =
            ListedAssignmentResponse(
                assignment.id.value,
                assignment.roomId.value,
                assignment.roomNumber,
                assignment.doNotMove,
                assignment.reallocationRequired,
            )
    }
}

@Schema(
    description =
        "Uma reserva numa busca ou lista operacional: o resumo para a recepção, sem as noites, os termos das " +
            "políticas nem os pedidos especiais (o detalhe está em `GET /api/v1/reservations/{id}`). Os hóspedes " +
            "vão só pelos ids (o nome está no `guests`).",
)
data class ReservationListItemResponse(
    val id: UUID,
    val code: String,
    val bookingId: UUID,
    val bookingCode: String,
    val holderGuestId: UUID,
    @field:Schema(description = "O hóspede principal da estadia.")
    val primaryGuestId: UUID?,
    @field:ExtensibleEnum
    @field:Schema(description = "Origem da reserva. $EXTENSIBLE_VALUE")
    val origin: ReservationOrigin,
    @field:ExtensibleEnum
    @field:Schema(description = "Canal de venda (derivado da origem). $EXTENSIBLE_VALUE")
    val channel: SalesChannel,
    @field:Schema(description = "O número da reserva na OTA (id externo), se houver.")
    val externalId: String?,
    @field:ExtensibleEnum
    @field:Schema(description = "Situação da reserva (SPEC §8.4). $EXTENSIBLE_VALUE")
    val status: ReservationStatus,
    val provisionalUntil: Instant?,
    val roomTypeId: UUID,
    val ratePlanId: UUID,
    @field:Schema(
        description =
            "Categoria do plano (Q-15): `NORMAL`, `COURTESY` (cortesia: conta na ocupação, não no ADR) ou " +
                "`HOUSE_USE` (uso da casa: em nenhum dos dois).",
    )
    val ratePlanCategory: RatePlanCategory,
    val arrival: LocalDate,
    val departure: LocalDate,
    val dayUse: Boolean,
    val adults: Int,
    val children: Int,
    @field:ExtensibleEnum
    @field:Schema(description = "Segmento de mercado. $EXTENSIBLE_VALUE")
    val segment: MarketSegment,
    val total: Money,
    val folioId: UUID,
    val blockId: UUID?,
    val roomAssignment: ListedAssignmentResponse?,
    @field:Schema(
        description =
            "Confirmada com a chegada antes da data operacional corrente: chegada pendente (no-show previsto, que o " +
                "fechamento do dia marca).",
    )
    val arrivalOverdue: Boolean,
    @field:Schema(
        description = "Hospedada com a saída antes da data operacional corrente: saída pendente (check-out em atraso).",
    )
    val departureOverdue: Boolean,
    val version: Long,
) {
    companion object {
        fun of(item: ReservationListItem): ReservationListItemResponse {
            val row = item.row
            return ReservationListItemResponse(
                id = row.id.value,
                code = row.code.toString(),
                bookingId = row.bookingId.value,
                bookingCode = row.bookingCode.toString(),
                holderGuestId = row.holderGuestId.value,
                primaryGuestId = row.primaryGuestId?.value,
                origin = row.origin,
                channel = row.channel,
                externalId = row.externalId,
                status = row.status,
                provisionalUntil = row.provisionalUntil,
                roomTypeId = row.roomTypeId.value,
                ratePlanId = row.ratePlanId.value,
                ratePlanCategory = row.ratePlanCategory,
                arrival = row.arrival,
                departure = row.departure,
                dayUse = row.dayUse,
                adults = row.adults,
                children = row.children,
                segment = row.segment,
                total = row.total,
                folioId = row.folioId.value,
                blockId = row.blockId?.value,
                roomAssignment = row.assignment?.let(ListedAssignmentResponse::of),
                arrivalOverdue = item.arrivalOverdue,
                departureOverdue = item.departureOverdue,
                version = row.version,
            )
        }

        fun of(row: ReservationListRow) = of(ReservationListItem(row, arrivalOverdue = false, departureOverdue = false))
    }
}

@Schema(description = "A busca de reservas pelo documento de um hóspede (o número vai no corpo, nunca na URL).")
data class ReservationDocumentSearchRequest(
    @field:NotNull
    val type: DocumentType?,
    @field:Schema(description = "País emissor (ISO 3166-1 alfa-2); nos documentos brasileiros pode ser omitido.")
    @field:Size(max = 2)
    val issuingCountry: String? = null,
    @field:NotBlank
    @field:Size(max = 40)
    val number: String?,
    @field:Schema(description = "Só as reservas nestas situações (vazio: todas).")
    @field:Size(max = 6)
    val statuses: List<ReservationStatus> = emptyList(),
) {
    override fun toString(): String = "ReservationDocumentSearchRequest(type=$type)"
}

@Schema(
    description =
        "Uma reserva confirmada com garantia por depósito cujo depósito líquido atual na conta ficou abaixo do que a " +
            "política copiada exige (um reembolso depois da confirmação, ou a troca para um plano de depósito maior).",
)
data class DepositShortfallResponse(
    val reservation: ReservationListItemResponse,
    val requiredDeposit: Money,
    val depositReceived: Money,
    val missing: Money,
    val guaranteedAt: Instant,
) {
    companion object {
        fun of(shortfall: DepositShortfall) =
            DepositShortfallResponse(
                ReservationListItemResponse.of(shortfall.row),
                shortfall.requiredDeposit,
                shortfall.depositReceived,
                shortfall.missing,
                shortfall.guaranteedAt,
            )
    }
}

@Schema(description = "As reservas com depósito abaixo do exigido, em ordem de chegada.")
data class DepositShortfallsResponse(
    val items: List<DepositShortfallResponse>,
)

/**
 * A busca de reservas (RF-RES-11) e as listas operacionais por data operacional (RF-RES-13; M4 E12), todas com
 * `reservations.reservations.read` e paginação por cursor. O documento do hóspede vai só no corpo.
 */
@ApiV1Controller
@RequestMapping("/reservations")
@Tag(name = RESERVATIONS_TAG, description = RESERVATIONS_TAG_DESCRIPTION)
class ReservationSearchController(
    private val queries: ReservationQueries,
) {
    @GetMapping
    @Operation(
        operationId = "searchReservations",
        summary = "Buscar reservas",
        description =
            "Os filtros combinam com \"e\" (os ausentes não filtram), em ordem de chegada, por cursor. `code`: o " +
                "número da reserva (`R-…`); `bookingCode`: o da reserva agrupadora (`B-…`); `externalId`: o número " +
                "da OTA; `name`: cada palavra como trecho do nome ou do nome social do titular ou de um hóspede da " +
                "estadia, sem diferenciar acento nem maiúsculas (até 1000 perfis: mais que isso, 422 " +
                "`GUEST_NAME_TOO_BROAD`); `from`/`to`: as estadias que tocam o período (do dia da chegada ao da " +
                "saída, inclusive; fim antes do início, 400 `RESERVATION_PERIOD_INVALID`, campo `to`); `status`: uma " +
                "ou mais situações. Para buscar por documento, use `POST /api/v1/reservations/search-by-document` (o " +
                "número não vai na URL). $READ",
    )
    @Suppress("LongParameterList") // os filtros da busca, cada um um parâmetro da query
    fun search(
        @Parameter(description = "Número da reserva (`R-…`), exato.")
        @RequestParam(required = false)
        @Size(min = 1, max = 30)
        code: String?,
        @Parameter(description = "Número da reserva agrupadora (`B-…`), exato.")
        @RequestParam(required = false)
        @Size(min = 1, max = 30)
        bookingCode: String?,
        @Parameter(description = "Número da reserva na OTA (id externo), exato.")
        @RequestParam(required = false)
        @Size(min = 1, max = 60)
        externalId: String?,
        @Parameter(description = "Trecho do nome do titular ou de um hóspede (de 2 a 200 caracteres).")
        @RequestParam(required = false)
        @Size(min = 2, max = 200)
        name: String?,
        @Parameter(description = "Início do período (data de negócio, inclusive).")
        @RequestParam(required = false) from: LocalDate?,
        @Parameter(description = "Fim do período (data de negócio, inclusive).")
        @RequestParam(required = false) to: LocalDate?,
        @Parameter(description = "Situações (repita o parâmetro ou separe por vírgula).")
        @RequestParam(required = false) status: List<ReservationStatus>?,
        @Valid page: PageParams,
    ): CursorPage<ReservationListItemResponse> =
        queries
            .search(
                ReservationSearch(
                    code = code,
                    bookingCode = bookingCode,
                    externalId = externalId,
                    from = from,
                    to = to,
                    statuses = status.orEmpty().toSet(),
                ),
                name,
                page,
            ).map(ReservationListItemResponse::of)

    @PostMapping("/search-by-document")
    @Operation(
        operationId = "searchReservationsByDocument",
        summary = "Buscar reservas pelo documento de um hóspede",
        description =
            "As reservas em que o hóspede do documento é titular ou hóspede da estadia, em ordem de chegada, por " +
                "cursor. O documento vai no corpo, nunca na URL, e é procurado pelo índice cego do `guests` (só " +
                "perfis ativos, sem decifrar nenhum documento). Número inválido para o tipo: 400 " +
                "`DOCUMENT_NUMBER_INVALID`; país incoerente: 400 `DOCUMENT_COUNTRY_INVALID`. $READ",
    )
    fun searchByDocument(
        @Valid @RequestBody request: ReservationDocumentSearchRequest,
        @Valid page: PageParams,
    ): CursorPage<ReservationListItemResponse> =
        queries
            .searchByDocument(
                checkNotNull(request.type),
                request.issuingCountry,
                checkNotNull(request.number),
                request.statuses.toSet(),
                page,
            ).map(ReservationListItemResponse::of)

    @GetMapping("/arrivals")
    @Operation(
        operationId = "listArrivals",
        summary = "Chegadas da data",
        description =
            "As reservas com a chegada na data, em qualquer situação menos `CANCELLED` e `NO_SHOW` (as que ainda vão " +
                "chegar e as que já chegaram), em ordem de chegada e id, por cursor. $DATE $READ",
    )
    fun arrivals(
        @RequestParam(required = false) date: LocalDate?,
        @Valid page: PageParams,
    ): CursorPage<ReservationListItemResponse> = list(OperationalList.ARRIVALS, date, page)

    @GetMapping("/departures")
    @Operation(
        operationId = "listDepartures",
        summary = "Saídas da data",
        description =
            "As reservas com a saída na data, em qualquer situação menos `CANCELLED` e `NO_SHOW`; na data " +
                "operacional corrente, também as hospedadas com a saída já passada (saída pendente, " +
                "`departureOverdue`: o check-out nunca é automático). Em ordem de saída e id, por cursor. $DATE $READ",
    )
    fun departures(
        @RequestParam(required = false) date: LocalDate?,
        @Valid page: PageParams,
    ): CursorPage<ReservationListItemResponse> = list(OperationalList.DEPARTURES, date, page)

    @GetMapping("/in-house")
    @Operation(
        operationId = "listInHouse",
        summary = "Hospedados na data",
        description =
            "As reservas hospedadas (`IN_HOUSE`, a partir do check-in do M5) cuja estadia alcança a data (chegada " +
                "até ela, saída a partir dela); na data operacional corrente, também as com a saída já passada " +
                "(`departureOverdue`). Em ordem de chegada e id, por cursor. $DATE $READ",
    )
    fun inHouse(
        @RequestParam(required = false) date: LocalDate?,
        @Valid page: PageParams,
    ): CursorPage<ReservationListItemResponse> = list(OperationalList.IN_HOUSE, date, page)

    @GetMapping("/expected-no-shows")
    @Operation(
        operationId = "listExpectedNoShows",
        summary = "No-shows previstos da data",
        description =
            "As reservas confirmadas (`CONFIRMED`; só a confirmada vira no-show) com a chegada na data que ainda não " +
                "chegaram; na data operacional corrente, também as com a chegada já passada (`arrivalOverdue`: o " +
                "fechamento do dia as marca como no-show). Em ordem de chegada e id, por cursor. $DATE $READ",
    )
    fun expectedNoShows(
        @RequestParam(required = false) date: LocalDate?,
        @Valid page: PageParams,
    ): CursorPage<ReservationListItemResponse> = list(OperationalList.EXPECTED_NO_SHOWS, date, page)

    @GetMapping("/deposit-shortfalls")
    @Operation(
        operationId = "listDepositShortfalls",
        summary = "Reservas com depósito abaixo do exigido",
        description =
            "As reservas confirmadas com garantia por depósito cujo total líquido de depósitos da conta (os " +
                "recebimentos com `deposit` menos os reembolsos deles) ficou abaixo do que a política copiada exige " +
                "(um reembolso depois da confirmação não a desfaz), em ordem de chegada. $READ",
    )
    fun depositShortfalls(): DepositShortfallsResponse =
        DepositShortfallsResponse(queries.depositShortfalls().map(DepositShortfallResponse::of))

    private fun list(
        list: OperationalList,
        date: LocalDate?,
        page: PageParams,
    ): CursorPage<ReservationListItemResponse> =
        queries.operational(list, date, page).map(ReservationListItemResponse::of)
}
