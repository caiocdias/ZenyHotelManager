package com.zeny.hotel.reservations.web

import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.RestrictionAlert
import com.zeny.hotel.rates.CancellationPenaltyType
import com.zeny.hotel.rates.GuaranteeType
import com.zeny.hotel.rates.PackageCharge
import com.zeny.hotel.rates.PackagePostingRhythm
import com.zeny.hotel.rates.PackagePricing
import com.zeny.hotel.rates.RatePlanCategory
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.SalesChannel
import com.zeny.hotel.reservations.BookingId
import com.zeny.hotel.reservations.BookingSummary
import com.zeny.hotel.reservations.CancellationReason
import com.zeny.hotel.reservations.CancellationTerms
import com.zeny.hotel.reservations.GuaranteeMethod
import com.zeny.hotel.reservations.GuaranteeTerms
import com.zeny.hotel.reservations.MarketSegment
import com.zeny.hotel.reservations.MinorAuthorization
import com.zeny.hotel.reservations.MinorProofType
import com.zeny.hotel.reservations.NewBooking
import com.zeny.hotel.reservations.NewReservation
import com.zeny.hotel.reservations.NewStayGuest
import com.zeny.hotel.reservations.NightlyRateOverride
import com.zeny.hotel.reservations.NightlyRateSummary
import com.zeny.hotel.reservations.ReservationCancellation
import com.zeny.hotel.reservations.ReservationGuarantee
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationOrigin
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.StayGuestSummary
import com.zeny.hotel.reservations.StayTransitionRecord
import com.zeny.hotel.reservations.application.BookingService
import com.zeny.hotel.reservations.application.BookingView
import com.zeny.hotel.reservations.application.PendingGuaranteesService
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
import org.springframework.web.bind.annotation.RequestParam
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

@Schema(
    description =
        "A comprovação do menor no check-in (RN-24): o tipo, o hóspede adulto da mesma estadia responsável, o " +
            "parentesco (código do domínio da FNRH), o anexo (autorização escrita ou judicial) e a validade.",
)
data class MinorAuthorizationResponse(
    @field:ExtensibleEnum
    @field:Schema(
        description =
            "`PARENT_PRESENT` (pais presentes), `WRITTEN_AUTHORIZATION` (autorização escrita) ou `COURT_ORDER` " +
                "(autorização judicial). $UNKNOWN",
    )
    val proofType: MinorProofType,
    val responsibleGuestId: UUID,
    val relationship: String,
    val attachmentId: UUID?,
    val validUntil: LocalDate?,
) {
    companion object {
        fun of(authorization: MinorAuthorization) =
            MinorAuthorizationResponse(
                authorization.proofType,
                authorization.responsibleGuestId.value,
                authorization.relationship,
                authorization.attachmentId,
                authorization.validUntil,
            )
    }
}

@Schema(
    description =
        "Um hóspede da estadia, com os dados de check-in (motivo de viagem e meio de transporte como códigos dos " +
            "domínios da FNRH, entrada, saída e a comprovação do menor), nulos antes do check-in.",
)
data class StayGuestResponse(
    val guestId: UUID,
    val primary: Boolean,
    val travelReason: String?,
    val transportMode: String?,
    val checkedInAt: Instant?,
    val checkedOutAt: Instant?,
    val minorAuthorization: MinorAuthorizationResponse?,
) {
    companion object {
        fun of(guest: StayGuestSummary) =
            StayGuestResponse(
                guest.guestId.value,
                guest.primary,
                guest.travelReason,
                guest.transportMode,
                guest.checkedInAt,
                guest.checkedOutAt,
                guest.minorAuthorization?.let(MinorAuthorizationResponse::of),
            )
    }
}

@Schema(description = "Uma transição de hospedagem: a data operacional, o instante e quem (nulo = sistema).")
data class StayTransitionResponse(
    val businessDate: LocalDate,
    val at: Instant,
    val by: UUID?,
) {
    companion object {
        fun of(record: StayTransitionRecord) = StayTransitionResponse(record.businessDate, record.at, record.by?.value)
    }
}

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
    @field:Schema(
        description =
            "Categoria do plano da reserva (Q-15), copiada na criação e na troca de plano: `NORMAL`, `COURTESY` " +
                "(cortesia: conta na ocupação, não no ADR) ou `HOUSE_USE` (uso da casa: em nenhum dos dois).",
    )
    val ratePlanCategory: RatePlanCategory,
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
    @field:Schema(
        description =
            "O depósito que a política de garantia copiada exige (o percentual sobre o total da reserva); nulo fora " +
                "da garantia `DEPOSIT`.",
    )
    val requiredDeposit: Money?,
    @field:Schema(
        description = "Até quando a política pede o depósito (N dias antes da chegada); nulo fora de `DEPOSIT`.",
    )
    val depositDueOn: LocalDate?,
    @field:Schema(
        description =
            "A última garantia registrada (na confirmação da pré-reserva ou depois, numa reserva confirmada sem " +
                "garantia); nula se nenhuma foi registrada. Depois de uma troca de plano pode ser de outro meio que " +
                "não vale mais para a política copiada atual (a reserva aparece então nas garantias pendentes).",
    )
    val guarantee: ReservationGuaranteeResponse?,
    @field:Schema(
        description =
            "A UH alocada (a alocação ativa, com \"não mover\" e a marca de realocação); nula sem UH alocada (a " +
                "alocação é opcional até o check-in).",
    )
    val roomAssignment: RoomAssignmentResponse?,
    @field:Schema(
        description =
            "O bloqueio de grupo de onde a reserva veio pelo pick-up (a diária é a tarifa do grupo; o tipo de UH, o " +
                "plano e as datas não mudam); nulo na reserva comum.",
    )
    val blockId: UUID?,
    @field:Schema(
        description = "O check-in (data operacional, instante e quem); nulo antes dele ou depois de desfeito.",
    )
    val checkIn: StayTransitionResponse?,
    @field:Schema(description = "O check-out (data operacional, instante e quem); só na reserva encerrada.")
    val checkOut: StayTransitionResponse?,
) {
    companion object {
        fun of(
            reservation: ReservationSummary,
            roomNumber: String? = null,
        ) = ReservationResponse(
            id = reservation.id.value,
            code = reservation.code.toString(),
            bookingId = reservation.bookingId.value,
            status = reservation.status,
            provisionalUntil = reservation.provisionalUntil,
            roomTypeId = reservation.roomTypeId.value,
            ratePlanId = reservation.ratePlanId.value,
            ratePlanCategory = reservation.ratePlanCategory,
            arrival = reservation.arrival,
            departure = reservation.departure,
            dayUse = reservation.dayUse,
            adults = reservation.adults,
            childAges = reservation.childAges,
            segment = reservation.segment,
            specialRequests = reservation.specialRequests,
            guests = reservation.guests.map(StayGuestResponse::of),
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
            requiredDeposit = reservation.requiredDeposit,
            depositDueOn = reservation.guaranteeTerms.depositDueOn(reservation.arrival),
            guarantee = reservation.guarantee?.let(ReservationGuaranteeResponse::of),
            roomAssignment = reservation.roomAssignment?.let { RoomAssignmentResponse.of(it, roomNumber) },
            blockId = reservation.blockId?.value,
            checkIn = reservation.checkIn?.let(StayTransitionResponse::of),
            checkOut = reservation.checkOut?.let(StayTransitionResponse::of),
        )
    }
}

@Schema(
    description =
        "A última garantia registrada (na confirmação da pré-reserva ou depois, numa reserva confirmada sem " +
            "garantia): o meio, quando, por quem (nulo = sistema) e, no depósito, o exigido pela política e o total " +
            "líquido de depósitos da conta naquele momento. Depois de uma troca de plano, pode ser de outro meio que " +
            "não vale mais para a política copiada atual (a reserva aparece então nas garantias pendentes).",
)
data class ReservationGuaranteeResponse(
    @field:ExtensibleEnum
    @field:Schema(description = "`CARD` (informado) ou `DEPOSIT` (conferido na conta). $UNKNOWN")
    val method: GuaranteeMethod,
    val confirmedAt: Instant,
    val confirmedBy: UUID?,
    val requiredDeposit: Money?,
    val depositReceived: Money?,
) {
    companion object {
        fun of(guarantee: ReservationGuarantee) =
            ReservationGuaranteeResponse(
                guarantee.method,
                guarantee.confirmedAt,
                guarantee.confirmedBy?.value,
                guarantee.requiredDeposit,
                guarantee.depositReceived,
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
                ReservationResponse.of(view.reservation, view.roomNumber),
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
                reservations = booking.reservations.map { ReservationResponse.of(it, view.roomNumbers[it.id]) },
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
 * As reservas (M4 E07 a E09): a consulta, a alteração, o cancelamento, a reativação e o histórico de uma reserva; a
 * confirmação da pré-reserva com a garantia e a lista das pré-reservas que vencem num intervalo.
 */
@ApiV1Controller
@RequestMapping("/reservations")
@Tag(name = RESERVATIONS_TAG, description = RESERVATIONS_TAG_DESCRIPTION)
class ReservationsController(
    private val bookings: BookingService,
    private val lifecycle: ReservationLifecycleService,
    private val pendingGuarantees: PendingGuaranteesService,
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
                "pré-reserva ou confirmada (senão 409 `RESERVATION_TRANSITION_NOT_ALLOWED`); numa **hospedada**, " +
                "só a saída (extensão e redução), o segmento e os pedidos especiais: mudar a chegada, o tipo, o " +
                "plano ou a ocupação é 409 `IN_HOUSE_STAY_FIXED` (campos em `fields`), a saída nova vem depois da " +
                "data operacional (422 `IN_HOUSE_DEPARTURE_INVALID`) e a UH alocada vai até ela (outra reserva na UH " +
                "nas noites novas: 409 `ROOM_ALREADY_ASSIGNED`; OOO: 422 `ROOM_OUT_OF_ORDER`; nada muda). Com " +
                "`If-Match` (412 com outra versão; 428 sem ele). Exige `reservations.reservations.manage`. " +
                "Recalcula **só as noites " +
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

    @PostMapping("/{id}/confirm")
    @Idempotent
    @Operation(
        operationId = "confirmReservation",
        summary = "Confirmar uma pré-reserva (ou registrar a garantia de uma reserva) com a garantia",
        description =
            "Registra a garantia recebida: confirma a pré-reserva ainda na validade (senão 422 " +
                "`PROVISIONAL_RESERVATION_EXPIRED`) ou, numa reserva confirmada sem garantia (a política copiada " +
                "exige uma e nenhuma foi registrada, ou a registrada é de outro meio), só grava a garantia nova; " +
                "outra situação, 409 " +
                "`RESERVATION_TRANSITION_NOT_ALLOWED`. Exige `reservations.reservations.manage` e " +
                "`Idempotency-Key`. A garantia tem de ser a que a política copiada pede (422 " +
                "`GUARANTEE_METHOD_NOT_ACCEPTED`): `CARD`, o cartão informado pelo hóspede (nenhum dado do " +
                "cartão entra aqui), ou `DEPOSIT`, conferido na conta " +
                "da reserva: os pagamentos com `deposit` menos os reembolsos deles precisam cobrir o percentual da " +
                "política sobre o total da reserva (422 `DEPOSIT_INSUFFICIENT`, com `requiredDeposit` e " +
                "`depositReceived`). Grava o histórico (`CONFIRM` ou `GUARANTEE`) e, na pré-reserva, publica " +
                "`ReservationConfirmed`; o inventário não muda.",
    )
    fun confirm(
        @PathVariable id: UUID,
        @Valid @RequestBody request: ReservationConfirmRequest,
    ): ResponseEntity<ReservationDetailResponse> =
        detail(lifecycle.confirm(ReservationId(id), checkNotNull(request.guarantee)))

    @GetMapping("/pending-guarantees")
    @Operation(
        operationId = "listPendingGuarantees",
        summary = "Garantias pendentes que vencem num intervalo",
        description =
            "As reservas que ainda devem a garantia, com o vencimento de `from` (inclusive; sem ele, desde as já " +
                "vencidas) a `to` (exclusive; sem ele, agora mais a antecedência do alerta do hotel, " +
                "`preReservationAlertLead`), do que vence primeiro ao último, por cursor. `kind` `PROVISIONAL`: a " +
                "pré-reserva, que vence na validade (`provisionalUntil`) e o job cancela; `UNGUARANTEED`: a reserva " +
                "confirmada cuja política copiada exige garantia e não tem nenhuma registrada, ou tem uma de outro " +
                "meio (troca de plano, reativação), que vence no horário de check-in do hotel do dia do prazo do " +
                "depósito ou, no cartão, do dia da chegada, e não expira. Cada item traz o depósito exigido, o já " +
                "recebido na conta e o prazo da política. Exige `reservations.reservations.read`. Fim antes do " +
                "início: 400 " +
                "`RESERVATION_PERIOD_INVALID` (campo `to`).",
    )
    fun pendingGuarantees(
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @Valid page: PageParams,
    ): CursorPage<PendingGuaranteeResponse> = pendingGuarantees.list(from, to, page).map(PendingGuaranteeResponse::of)

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
