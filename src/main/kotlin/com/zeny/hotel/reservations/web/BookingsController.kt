package com.zeny.hotel.reservations.web

import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.RestrictionAlert
import com.zeny.hotel.rates.CancellationPenaltyType
import com.zeny.hotel.rates.GuaranteeType
import com.zeny.hotel.rates.PackageCharge
import com.zeny.hotel.rates.PackagePostingRhythm
import com.zeny.hotel.rates.PackagePricing
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.SalesChannel
import com.zeny.hotel.reservations.BookingId
import com.zeny.hotel.reservations.BookingSummary
import com.zeny.hotel.reservations.CancellationReason
import com.zeny.hotel.reservations.CancellationTerms
import com.zeny.hotel.reservations.GuaranteeTerms
import com.zeny.hotel.reservations.MarketSegment
import com.zeny.hotel.reservations.NewBooking
import com.zeny.hotel.reservations.NewReservation
import com.zeny.hotel.reservations.NewStayGuest
import com.zeny.hotel.reservations.NightlyRateOverride
import com.zeny.hotel.reservations.NightlyRateSummary
import com.zeny.hotel.reservations.ReservationCancellation
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationOrigin
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.application.BookingService
import com.zeny.hotel.reservations.application.BookingView
import com.zeny.hotel.reservations.application.ReservationLifecycleService
import com.zeny.hotel.reservations.application.ReservationView
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.APPROVAL_TOKEN_HEADER
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.Idempotent
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.Percent
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val UNKNOWN = "A lista pode crescer (`x-extensible-enum`): trate um valor desconhecido sem falhar."

@Schema(description = "Um hóspede da estadia: o perfil (do `guests`) e se é o principal.")
data class StayGuestRequest(
    @field:NotNull
    val guestId: UUID?,
    val primary: Boolean = false,
)

@Schema(description = "A diária gravada numa noite em vez da calculada; menor que a calculada é desconto.")
data class NightlyRateRequest(
    @field:NotNull
    val date: LocalDate?,
    @field:NotNull
    val roomAmount: Money?,
)

@Schema(
    description =
        "Uma reserva (uma UH): chegada = saída é day use (não toma inventário, precificado pela tarifa da data). " +
            "`guests` vazio = o titular como principal; senão, exatamente um principal. `nightlyRates` só com as " +
            "noites cuja diária muda.",
)
data class ReservationRequest(
    @field:NotNull
    val roomTypeId: UUID?,
    @field:NotNull
    val ratePlanId: UUID?,
    @field:NotNull
    val arrival: LocalDate?,
    @field:NotNull
    val departure: LocalDate?,
    @field:NotNull
    @field:Min(1)
    val adults: Int?,
    @field:Schema(description = "A idade de cada criança na chegada, em anos completos.")
    val childAges: List<Int> = emptyList(),
    @field:NotNull
    val segment: MarketSegment?,
    @field:Schema(description = "Pedidos especiais (texto livre, até 1000 caracteres; nunca vai à trilha).")
    val specialRequests: String? = null,
    val guests: List<@Valid StayGuestRequest> = emptyList(),
    val nightlyRates: List<@Valid NightlyRateRequest> = emptyList(),
) {
    fun toNew() =
        NewReservation(
            roomTypeId = RoomTypeId(checkNotNull(roomTypeId)),
            ratePlanId = RatePlanId(checkNotNull(ratePlanId)),
            arrival = checkNotNull(arrival),
            departure = checkNotNull(departure),
            adults = checkNotNull(adults),
            childAges = childAges,
            segment = checkNotNull(segment),
            specialRequests = specialRequests,
            guests = guests.map { NewStayGuest(GuestId(checkNotNull(it.guestId)), it.primary) },
            nightlyRates = nightlyRates.map { NightlyRateOverride(checkNotNull(it.date), checkNotNull(it.roomAmount)) },
        )
}

@Schema(description = "Uma reserva agrupadora: o titular, a origem (que define o canal), o id externo e as reservas.")
data class BookingRequest(
    @field:NotNull
    val holderGuestId: UUID?,
    @field:NotNull
    val origin: ReservationOrigin?,
    @field:Schema(description = "Id externo (o número da OTA), de 1 a 60 caracteres.")
    val externalId: String? = null,
    val reservations: List<@Valid ReservationRequest> = emptyList(),
) {
    fun toNew() =
        NewBooking(
            holderGuestId = GuestId(checkNotNull(holderGuestId)),
            origin = checkNotNull(origin),
            externalId = externalId,
            reservations = reservations.map { it.toNew() },
        )
}

@Schema(description = "Um alerta da lista de restrição de um hóspede da reserva (não impede a reserva).")
data class RestrictionAlertResponse(
    val guestId: UUID,
    val reason: String,
    val since: Instant,
) {
    companion object {
        fun of(alert: RestrictionAlert) = RestrictionAlertResponse(alert.guestId.value, alert.reason, alert.since)
    }
}

@Schema(description = "Um hóspede da estadia.")
data class StayGuestResponse(
    val guestId: UUID,
    val primary: Boolean,
)

@Schema(description = "Um pacote numa noite, com o código de lançamento do `billing` (para o check-in e o fechamento).")
data class NightlyPackageResponse(
    val packageId: UUID,
    val code: String,
    val name: String,
    val transactionCodeId: UUID,
    val pricing: PackagePricing,
    val postingRhythm: PackagePostingRhythm,
    val includedInRate: Boolean,
    val quantity: Int,
    val unitPrice: Money,
    val amount: Money,
) {
    companion object {
        fun of(charge: PackageCharge) =
            NightlyPackageResponse(
                charge.packageId.value,
                charge.code,
                charge.name,
                charge.transactionCodeId.value,
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
        "O preço congelado de uma noite (RN-14): `calculatedRoomAmount` é a diária calculada na criação, " +
            "`roomAmount` a gravada (`overridden` quando difere; `discountPercent` quando é menor), " +
            "`amount` = `roomAmount` + `additionalPackagesAmount`.",
)
data class NightlyRateResponse(
    val date: LocalDate,
    val ratePlanId: UUID,
    val calculatedRoomAmount: Money,
    val roomAmount: Money,
    val includedPackagesAmount: Money,
    val additionalPackagesAmount: Money,
    val amount: Money,
    val overridden: Boolean,
    val discountPercent: Percent?,
    val packages: List<NightlyPackageResponse>,
) {
    companion object {
        fun of(night: NightlyRateSummary) =
            NightlyRateResponse(
                night.date,
                night.ratePlanId.value,
                night.calculatedRoomAmount,
                night.roomAmount,
                night.includedPackagesAmount,
                night.additionalPackagesAmount,
                night.amount,
                night.overridden,
                night.discountPercent,
                night.packages.map(NightlyPackageResponse::of),
            )
    }
}

@Schema(description = "Os termos da política de cancelamento copiados na reserva (RN-15).")
data class CancellationTermsResponse(
    val policyId: UUID,
    val name: String,
    val version: Long,
    val deadlineHours: Int,
    val penaltyType: CancellationPenaltyType,
    val penaltyPercent: Percent?,
    val penaltyNights: Int?,
    val penaltyAmount: Money?,
) {
    companion object {
        fun of(terms: CancellationTerms) =
            CancellationTermsResponse(
                terms.policyId.value,
                terms.name,
                terms.version,
                terms.deadlineHours,
                terms.penaltyType,
                terms.penaltyPercent,
                terms.penaltyNights,
                terms.penaltyAmount,
            )
    }
}

@Schema(description = "Os termos da política de garantia copiados na reserva (RN-15).")
data class GuaranteeTermsResponse(
    val policyId: UUID,
    val name: String,
    val version: Long,
    @field:ExtensibleEnum
    @field:Schema(description = "Tipo de garantia. $UNKNOWN")
    val type: GuaranteeType,
    val depositPercent: Percent?,
    val dueDaysBefore: Int?,
) {
    companion object {
        fun of(terms: GuaranteeTerms) =
            GuaranteeTermsResponse(
                terms.policyId.value,
                terms.name,
                terms.version,
                terms.type,
                terms.depositPercent,
                terms.dueDaysBefore,
            )
    }
}

@Schema(
    description =
        "A multa de um cancelamento: o prazo (o horário de check-in da chegada, no fuso do hotel, menos as horas da " +
            "política copiada), o valor devido (zero antes do prazo), se foi isentada (com a aprovação de " +
            "superior) e " +
            "o lançamento na conta da reserva (código `CANCELLATION_FEE`).",
)
data class CancellationPenaltyResponse(
    val deadline: Instant,
    val amount: Money,
    val waived: Boolean,
    val postingId: UUID?,
    val waiverApprovalId: UUID?,
    val waiverApprovedBy: UUID?,
)

@Schema(description = "O cancelamento corrente de uma reserva.")
data class CancellationResponse(
    @field:ExtensibleEnum
    @field:Schema(description = "Motivo do cancelamento. $UNKNOWN")
    val reason: CancellationReason,
    @field:Schema(description = "Observação de quem cancelou (texto livre; nunca vai à trilha).")
    val notes: String?,
    @field:ExtensibleEnum
    @field:Schema(description = "Situação antes do cancelamento. $UNKNOWN")
    val previousStatus: ReservationStatus,
    val cancelledAt: Instant,
    val cancelledBy: UUID?,
    val penalty: CancellationPenaltyResponse,
) {
    companion object {
        fun of(cancellation: ReservationCancellation) =
            CancellationResponse(
                cancellation.reason,
                cancellation.notes,
                cancellation.previousStatus,
                cancellation.cancelledAt,
                cancellation.cancelledBy?.value,
                cancellation.penalty.let {
                    CancellationPenaltyResponse(
                        it.deadline,
                        it.amount,
                        it.waived,
                        it.postingId?.value,
                        it.waiverApprovalId,
                        it.waiverApprovedBy?.value,
                    )
                },
            )
    }
}

@Schema(description = "Uma reserva (uma UH num período).")
data class ReservationResponse(
    val id: UUID,
    val code: String,
    val bookingId: UUID,
    @field:ExtensibleEnum
    @field:Schema(
        description =
            "Situação (SPEC §8.4): `PROVISIONAL` (pré-reserva, válida até `provisionalUntil`), `CONFIRMED`, " +
                "`IN_HOUSE`, `CHECKED_OUT`, `CANCELLED`, `NO_SHOW`. $UNKNOWN",
    )
    val status: ReservationStatus,
    val provisionalUntil: Instant?,
    val roomTypeId: UUID,
    val ratePlanId: UUID,
    val arrival: LocalDate,
    val departure: LocalDate,
    @field:Schema(description = "Day use: chegada = saída (não toma inventário).")
    val dayUse: Boolean,
    val adults: Int,
    val childAges: List<Int>,
    @field:ExtensibleEnum
    @field:Schema(description = "Segmento de mercado. $UNKNOWN")
    val segment: MarketSegment,
    val specialRequests: String?,
    val guests: List<StayGuestResponse>,
    val nightlyRates: List<NightlyRateResponse>,
    val roomTotal: Money,
    val additionalPackagesTotal: Money,
    val total: Money,
    val cancellationTerms: CancellationTermsResponse,
    val guaranteeTerms: GuaranteeTermsResponse,
    @field:Schema(description = "A conta da reserva no `billing` (dono `RESERVATION`).")
    val folioId: UUID,
    @field:Schema(description = "A aprovação de superior do desconto, se houve.")
    val approvalId: UUID?,
    val approvedBy: UUID?,
    val version: Long,
    val createdAt: Instant,
    val createdBy: UUID?,
    @field:Schema(description = "O cancelamento corrente (só numa reserva `CANCELLED`).")
    val cancellation: CancellationResponse?,
    @field:Schema(
        description =
            "O total das multas de cancelamento já lançadas na conta desta reserva (um cancelamento depois de uma " +
                "reativação lança só o que falta para a multa da política).",
    )
    val penaltiesCharged: Money,
) {
    companion object {
        fun of(reservation: ReservationSummary) =
            ReservationResponse(
                id = reservation.id.value,
                code = reservation.code.toString(),
                bookingId = reservation.bookingId.value,
                status = reservation.status,
                provisionalUntil = reservation.provisionalUntil,
                roomTypeId = reservation.roomTypeId.value,
                ratePlanId = reservation.ratePlanId.value,
                arrival = reservation.arrival,
                departure = reservation.departure,
                dayUse = reservation.dayUse,
                adults = reservation.adults,
                childAges = reservation.childAges,
                segment = reservation.segment,
                specialRequests = reservation.specialRequests,
                guests = reservation.guests.map { StayGuestResponse(it.guestId.value, it.primary) },
                nightlyRates = reservation.nightlyRates.map(NightlyRateResponse::of),
                roomTotal = reservation.roomTotal,
                additionalPackagesTotal = reservation.additionalPackagesTotal,
                total = reservation.total,
                cancellationTerms = CancellationTermsResponse.of(reservation.cancellationTerms),
                guaranteeTerms = GuaranteeTermsResponse.of(reservation.guaranteeTerms),
                folioId = reservation.folioId.value,
                approvalId = reservation.approvalId,
                approvedBy = reservation.approvedBy?.value,
                version = reservation.version,
                createdAt = reservation.createdAt,
                createdBy = reservation.createdBy?.value,
                cancellation = reservation.cancellation?.let(CancellationResponse::of),
                penaltiesCharged = reservation.penaltiesCharged,
            )
    }
}

@Schema(description = "Uma reserva com o código e o titular da reserva agrupadora e os alertas da lista de restrição.")
data class ReservationDetailResponse(
    val reservation: ReservationResponse,
    val bookingCode: String,
    val holderGuestId: UUID,
    val restrictionAlerts: List<RestrictionAlertResponse>,
) {
    companion object {
        fun of(view: ReservationView) =
            ReservationDetailResponse(
                ReservationResponse.of(view.reservation),
                view.bookingCode,
                view.holderGuestId.value,
                view.restrictionAlerts.map(RestrictionAlertResponse::of),
            )
    }
}

@Schema(description = "Uma reserva agrupadora com as reservas e os alertas da lista de restrição dos hóspedes.")
data class BookingResponse(
    val id: UUID,
    val code: String,
    val holderGuestId: UUID,
    @field:ExtensibleEnum
    @field:Schema(description = "Origem da reserva. $UNKNOWN")
    val origin: ReservationOrigin,
    @field:ExtensibleEnum
    @field:Schema(description = "Canal de venda (derivado da origem). $UNKNOWN")
    val channel: SalesChannel,
    val externalId: String?,
    val reservations: List<ReservationResponse>,
    val restrictionAlerts: List<RestrictionAlertResponse>,
    val version: Long,
    val createdAt: Instant,
    val createdBy: UUID?,
) {
    companion object {
        fun of(view: BookingView): BookingResponse {
            val booking: BookingSummary = view.booking
            return BookingResponse(
                id = booking.id.value,
                code = booking.code.toString(),
                holderGuestId = booking.holderGuestId.value,
                origin = booking.origin,
                channel = booking.channel,
                externalId = booking.externalId,
                reservations = booking.reservations.map(ReservationResponse::of),
                restrictionAlerts = view.restrictionAlerts.map(RestrictionAlertResponse::of),
                version = booking.version,
                createdAt = booking.createdAt,
                createdBy = booking.createdBy?.value,
            )
        }
    }
}

/**
 * Reservas agrupadoras e reservas (M4 E07; RF-RES-01 a 03 e 14). Criar exige `reservations.reservations.manage`;
 * consultar, `reservations.reservations.read`. A criação exige `Idempotency-Key`.
 */
@ApiV1Controller
@RequestMapping("/bookings")
@Tag(name = RESERVATIONS_TAG, description = RESERVATIONS_TAG_DESCRIPTION)
class BookingsController(
    private val bookings: BookingService,
) {
    @PostMapping
    @Idempotent
    @Operation(
        operationId = "createBooking",
        summary = "Criar uma reserva agrupadora com as reservas",
        description =
            "Uma reserva por UH, sob o mesmo titular e canal (o canal vem da origem). Exige " +
                "`reservations.reservations.manage`. Hóspede inexistente: 400 `UNKNOWN_GUEST` (com o campo); " +
                "mesclado: vira o sobrevivente; anonimizado: 422 `GUEST_ANONYMIZED`. Plano ou tipo de UH " +
                "inexistente: 400 `UNKNOWN_RATE_PLAN`/`UNKNOWN_ROOM_TYPE`; estadia fora do formato: 400 " +
                "`STAY_INVALID`; não vendável no canal: 422 com o código do `rates` (o primeiro motivo; todos em " +
                "`refusals`, a reserva em `reservationIndex`). O preço é calculado e congelado por noite; uma diária " +
                "editada abaixo da calculada é **desconto**, ação sensível com o limite `maxPercent` " +
                "(`rates.discounts.apply`): sem a permissão ou acima do limite, 403 `APPROVAL_REQUIRED` (repita " +
                "com o `X-Approval-Token` e **outra** `Idempotency-Key`). Sem unidade do tipo em alguma noite: 409 " +
                "`ROOM_TYPE_UNAVAILABLE` (nada é gravado); fora do inventário mantido: 422 " +
                "`INVENTORY_DATE_OUT_OF_HORIZON`. Abre a conta de cada reserva no `billing`. Sem garantia exigida " +
                "pela política, nasce `CONFIRMED`; senão, `PROVISIONAL` com a validade da pré-reserva do hotel.",
    )
    @ApiResponse(responseCode = "201", description = "Reserva agrupadora criada.")
    fun create(
        @Valid @RequestBody request: BookingRequest,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
    ): ResponseEntity<BookingResponse> {
        val view = bookings.create(request.toNew(), approvalToken)
        return ResponseEntity
            .created(URI.create("/api/v1/bookings/${view.booking.id}"))
            .eTag(ETags.of(view.booking.version))
            .body(BookingResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getBooking",
        summary = "Consultar uma reserva agrupadora",
        description =
            "Com as reservas e os alertas da lista de restrição. Exige `reservations.reservations.read`. " +
                "Inexistente: 404 `BOOKING_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<BookingResponse> {
        val view = bookings.booking(BookingId(id))
        return ResponseEntity.ok().eTag(ETags.of(view.booking.version)).body(BookingResponse.of(view))
    }
}

/**
 * As reservas (M4 E07 e E08): a consulta, a alteração, o cancelamento, a reativação e o histórico de uma reserva.
 */
@ApiV1Controller
@RequestMapping("/reservations")
@Tag(name = RESERVATIONS_TAG, description = RESERVATIONS_TAG_DESCRIPTION)
class ReservationsController(
    private val bookings: BookingService,
    private val lifecycle: ReservationLifecycleService,
) {
    @GetMapping("/{id}")
    @Operation(
        operationId = "getReservation",
        summary = "Consultar uma reserva",
        description =
            "Com o preço congelado por noite, os termos das políticas, a conta e os alertas da lista de restrição. " +
                "Exige `reservations.reservations.read`. Inexistente: 404 `RESERVATION_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<ReservationDetailResponse> = detail(bookings.reservation(ReservationId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "changeReservation",
        summary = "Alterar uma reserva",
        description =
            "Troca a estadia (tipo de UH, plano, datas, ocupação), o segmento e os pedidos especiais de uma " +
                "pré-reserva ou confirmada (senão 409 `RESERVATION_TRANSITION_NOT_ALLOWED`), com `If-Match` (412 " +
                "com outra versão; 428 sem ele). Exige `reservations.reservations.manage`. Recalcula **só as noites " +
                "afetadas**: com o tipo, o plano ou a ocupação trocados, todas; com só as datas mudadas, as noites " +
                "novas (e, com a chegada mudada e um pacote por estadia, as noites da chegada antiga e da nova); as " +
                "mantidas guardam o preço congelado. A venda é conferida só no que mudou (422 com o código do " +
                "`rates` e os motivos em `refusals`). `nightlyRates` só nas noites recalculadas (senão 400 " +
                "`NIGHTLY_RATE_INVALID`); desconto: 403 `APPROVAL_REQUIRED` sem a permissão ou acima do limite " +
                "(`rates.discounts.apply`, `maxPercent`). Devolve as unidades antigas e toma as novas na mesma " +
                "transação: sem unidade, 409 `ROOM_TYPE_UNAVAILABLE` e nada muda. Plano novo copia as políticas " +
                "vigentes dele. Grava o histórico e publica `ReservationChanged`; sem diferença, nada muda.",
    )
    fun change(
        @PathVariable id: UUID,
        @Valid @RequestBody request: ReservationChangeRequest,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
    ): ResponseEntity<ReservationDetailResponse> {
        val expected = ETags.expectedVersion(ifMatch)
        return detail(lifecycle.change(ReservationId(id), expected, request.toChange(), approvalToken))
    }

    @PostMapping("/{id}/cancel")
    @Idempotent
    @Operation(
        operationId = "cancelReservation",
        summary = "Cancelar uma reserva",
        description =
            "Cancela uma pré-reserva ou confirmada (senão 409 `RESERVATION_TRANSITION_NOT_ALLOWED`). Exige " +
                "`reservations.reservations.manage` e `Idempotency-Key`. A multa segue a política **copiada na " +
                "reserva**: a partir do prazo (o horário de check-in da chegada, no fuso do hotel, menos as horas " +
                "da política), é lançada na conta da reserva no código `CANCELLATION_FEE`, na data operacional; " +
                "antes dele, não há multa. `waivePenalty` isenta a multa devida: ação sensível " +
                "(`reservations.penalties.waive`), 403 `APPROVAL_REQUIRED` sem a permissão (repita com o " +
                "`X-Approval-Token` e **outra** `Idempotency-Key`). Devolve ao inventário as unidades das noites a " +
                "partir da data operacional, grava o histórico e publica `ReservationCancelled`. A devolução de " +
                "depósito é um reembolso na conta (`billing`).",
    )
    fun cancel(
        @PathVariable id: UUID,
        @Valid @RequestBody request: ReservationCancelRequest,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
    ): ResponseEntity<ReservationDetailResponse> =
        detail(lifecycle.cancel(ReservationId(id), request.toRequest(), approvalToken))

    @PostMapping("/{id}/reactivate")
    @Idempotent
    @Operation(
        operationId = "reactivateReservation",
        summary = "Reativar uma reserva cancelada ou no-show",
        description =
            "Volta a reserva a `CONFIRMED` (só de `CANCELLED` ou `NO_SHOW`; senão 409 " +
                "`RESERVATION_TRANSITION_NOT_ALLOWED`), com o preço congelado. Exige " +
                "`reservations.reservations.manage` e `Idempotency-Key`; é ação sensível " +
                "(`reservations.reservations.reactivate`): 403 `APPROVAL_REQUIRED` sem a permissão. Estadia já " +
                "terminada: 422 `RESERVATION_STAY_ENDED`. Toma de novo as unidades das noites a partir da data " +
                "operacional: sem unidade, 409 `ROOM_TYPE_UNAVAILABLE` e nada muda. A multa de um cancelamento " +
                "anterior fica na conta (estorne-a pelo `billing`, se for o caso). Grava o histórico e publica " +
                "`ReservationReactivated`.",
    )
    fun reactivate(
        @PathVariable id: UUID,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
    ): ResponseEntity<ReservationDetailResponse> = detail(lifecycle.reactivate(ReservationId(id), approvalToken))

    @GetMapping("/{id}/history")
    @Operation(
        operationId = "listReservationHistory",
        summary = "Histórico de uma reserva",
        description =
            "As alterações, cancelamentos e reativações, da mais recente à mais antiga, com quem, quando, a " +
                "aprovação de superior, os campos mudados e o retrato antes e depois (sem texto livre). Exige " +
                "`reservations.reservations.read`. Inexistente: 404 `RESERVATION_NOT_FOUND`.",
    )
    fun history(
        @PathVariable id: UUID,
        @Valid page: PageParams,
    ): CursorPage<ReservationHistoryResponse> =
        lifecycle.history(ReservationId(id), page).map(ReservationHistoryResponse::of)

    private fun detail(view: ReservationView): ResponseEntity<ReservationDetailResponse> =
        ResponseEntity
            .ok()
            .eTag(ETags.of(view.reservation.version))
            .body(ReservationDetailResponse.of(view))
}
