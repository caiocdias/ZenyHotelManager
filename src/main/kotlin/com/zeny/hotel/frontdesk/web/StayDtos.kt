package com.zeny.hotel.frontdesk.web

import com.zeny.hotel.billing.CardPreAuthorizationStatus
import com.zeny.hotel.billing.CardPreAuthorizationSummary
import com.zeny.hotel.billing.NewCardPreAuthorization
import com.zeny.hotel.frontdesk.EarlyLateOffer
import com.zeny.hotel.frontdesk.application.CheckInCommand
import com.zeny.hotel.frontdesk.application.CheckInOutcome
import com.zeny.hotel.frontdesk.application.EarlyLateQuote
import com.zeny.hotel.frontdesk.application.UndoCheckInOutcome
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.RestrictionAlert
import com.zeny.hotel.reservations.CheckInGuest
import com.zeny.hotel.reservations.MinorAuthorization
import com.zeny.hotel.reservations.MinorProofType
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.StayGuestSummary
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OccupancyStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatus
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

private const val UNKNOWN = "A lista pode crescer (`x-extensible-enum`): trate um valor desconhecido sem falhar."
private const val FNRH_CODE =
    "Código opaco do domínio da FNRH (letras, dígitos, `_`, `.` e `-`, até 40), conferido pelo `fnrh` no envio."
private const val MAX_GUESTS = 40

// --- Pedido ---------------------------------------------------------------------------------------------------------

@Schema(
    description =
        "A comprovação de um hóspede menor (RN-24, RF-REC-08): o tipo, o hóspede adulto da mesma estadia " +
            "responsável, o parentesco e, na autorização escrita e na judicial, o anexo (finalidade " +
            "`guests.minor-authorization-photo` ou `guests.document-pdf`, vinculado ao perfil do menor por " +
            "`POST /api/v1/guests/{id}/attachments`).",
)
data class CheckInMinorAuthorizationRequest(
    @field:NotNull
    val proofType: MinorProofType?,
    @field:NotNull
    val responsibleGuestId: UUID?,
    @field:Schema(description = "O parentesco do responsável. $FNRH_CODE", example = "MAE")
    @field:NotBlank
    @field:Size(max = 40)
    val relationship: String?,
    @field:Schema(description = "O anexo da autorização (obrigatório na escrita e na judicial).", nullable = true)
    val attachmentId: UUID? = null,
    @field:Schema(description = "A validade da autorização, não antes da data operacional.", nullable = true)
    val validUntil: LocalDate? = null,
) {
    fun toMinorAuthorization() =
        MinorAuthorization(
            proofType = checkNotNull(proofType),
            responsibleGuestId = GuestId(checkNotNull(responsibleGuestId)),
            relationship = checkNotNull(relationship),
            attachmentId = attachmentId,
            validUntil = validUntil,
        )
}

@Schema(
    description =
        "Um hóspede no check-in: o perfil (cadastrado e com os dados FNRH confirmados antes pelo `guests`), se é o " +
            "principal, o motivo de viagem e o meio de transporte e, se for menor, a comprovação.",
)
data class CheckInGuestRequest(
    @field:NotNull
    val guestId: UUID?,
    val primary: Boolean = false,
    @field:Schema(description = "O motivo de viagem. $FNRH_CODE", nullable = true, example = "LAZER")
    val travelReason: String? = null,
    @field:Schema(description = "O meio de transporte. $FNRH_CODE", nullable = true, example = "AUTOMOVEL")
    val transportMode: String? = null,
    @field:Valid
    @field:Schema(description = "Só do hóspede menor na data operacional.", nullable = true)
    val minorAuthorization: CheckInMinorAuthorizationRequest? = null,
) {
    fun toGuest() =
        CheckInGuest(
            guestId = GuestId(checkNotNull(guestId)),
            primary = primary,
            travelReason = travelReason,
            transportMode = transportMode,
            minorAuthorization = minorAuthorization?.toMinorAuthorization(),
        )
}

@Schema(
    description =
        "Uma caução (pré-autorização de cartão de crédito) feita numa maquininha não integrada, registrada na conta " +
            "da reserva no check-in (RF-CTA-08 no recorte manual). Não entra no saldo até ser capturada (pelo " +
            "`billing`, com turno de caixa). **Nunca** envie o número do cartão nem o CVV (PCI-DSS, RNF-10).",
)
data class CheckInPreAuthorizationRequest(
    @field:Schema(description = "Valor autorizado (0,01 a 1.000.000,00).", example = "500.00")
    @field:NotNull
    val amount: Money?,
    @field:Schema(description = "Validade na adquirente (instante em UTC): depois de agora e até 90 dias.")
    @field:NotNull
    val expiresAt: Instant?,
    @field:Schema(
        description = "Código de autorização (obrigatório; até 20 letras e dígitos, no máximo 12 dígitos).",
        maxLength = 20,
    )
    val authorizationCode: String? = null,
    @field:Schema(description = "NSU (até 20 letras e dígitos, no máximo 12 dígitos).", maxLength = 20)
    val nsu: String? = null,
    @field:Schema(description = "A bandeira (só letras e espaços, até 20).", maxLength = 20, example = "VISA")
    val cardBrand: String? = null,
    @field:Schema(description = "Exatamente os 4 últimos dígitos do cartão.", pattern = "^[0-9]{4}$")
    val cardLast4: String? = null,
) {
    fun toNew() =
        NewCardPreAuthorization(
            amount = checkNotNull(amount),
            expiresAt = checkNotNull(expiresAt),
            authorizationCode = authorizationCode,
            nsu = nsu,
            cardBrand = cardBrand,
            cardLast4 = cardLast4,
        )
}

@Schema(
    description =
        "O check-in: a lista **completa** dos hóspedes da estadia (exatamente um principal, até a ocupação da " +
            "reserva), o número de pessoas na UH, a UH (opcional: aloca ou realoca antes), a caução opcional e o " +
            "valor cotado do early check-in (opcional).",
)
data class CheckInRequest(
    @field:Schema(
        description =
            "A UH em que o hóspede entra; nula ou igual à alocada, vale a alocada. Diferente, aloca ou realoca " +
                "antes (exige `reservations.assignments.manage`).",
        nullable = true,
    )
    val roomId: UUID? = null,
    @field:NotNull
    @field:Size(min = 1, max = MAX_GUESTS)
    val guests: List<@Valid CheckInGuestRequest>?,
    @field:Schema(
        description = "O número de pessoas na UH (de 1 a 40, não abaixo da lista); nulo, o tamanho da lista.",
        nullable = true,
    )
    @field:Min(1)
    @field:Max(MAX_GUESTS.toLong())
    val guestCount: Int? = null,
    @field:Valid
    @field:Schema(nullable = true)
    val preAuthorization: CheckInPreAuthorizationRequest? = null,
    @field:Schema(description = EARLY_FEE_DESCRIPTION, nullable = true, example = "60.00")
    val earlyCheckInFee: Money? = null,
) {
    fun toCommand() =
        CheckInCommand(
            roomId = roomId?.let(::RoomId),
            guests = checkNotNull(guests).map { it.toGuest() },
            guestCount = guestCount,
            preAuthorization = preAuthorization?.toNew(),
            earlyCheckInFee = requirePositiveEarlyFee(earlyCheckInFee),
        )
}

internal const val EARLY_POSTING_DESCRIPTION =
    "O early check-in lançado na conta da reserva; nulo sem `earlyCheckInFee`."

internal const val EARLY_FEE_DESCRIPTION =
    "O valor do early check-in cotado ao hóspede (`GET /api/v1/reservations/{id}/early-late-quote`, RN-11): " +
        "positivo, até duas casas, conferido com o da política vigente (`409 EARLY_LATE_FEE_CHANGED` se mudou, " +
        "`422 EARLY_LATE_NOT_OFFERED` se não é oferecido). Nulo: sem early."

/** O valor do early check-in do pedido, se informado, precisa ser positivo (`400 VALIDATION_FAILED`). */
internal fun requirePositiveEarlyFee(fee: Money?): Money? {
    if (fee != null && !fee.isPositive()) {
        val message = "O valor do early check-in precisa ser positivo."
        throw InvalidInputException(
            ErrorCodes.VALIDATION_FAILED,
            message,
            listOf(FieldViolation("earlyCheckInFee", "POSITIVE", message)),
        )
    }
    return fee
}

// --- Resposta -------------------------------------------------------------------------------------------------------

@Schema(description = "Uma transição de hospedagem: a data operacional, o instante e quem a fez.")
data class StayTransitionRecordResponse(
    val businessDate: LocalDate,
    val at: Instant,
    @field:Schema(description = "O usuário; nulo = o sistema.", nullable = true)
    val by: UUID?,
)

@Schema(description = "A comprovação de um menor como foi registrada no check-in.")
data class StayMinorAuthorizationResponse(
    @field:ExtensibleEnum
    @field:Schema(description = "`PARENT_PRESENT`, `WRITTEN_AUTHORIZATION` ou `COURT_ORDER`. $UNKNOWN")
    val proofType: MinorProofType,
    val responsibleGuestId: UUID,
    val relationship: String,
    @field:Schema(nullable = true)
    val attachmentId: UUID?,
    @field:Schema(nullable = true)
    val validUntil: LocalDate?,
)

@Schema(description = "Um hóspede da estadia com os dados de check-in (só ids e códigos, sem dado pessoal).")
data class StayGuestCheckInResponse(
    val guestId: UUID,
    val primary: Boolean,
    @field:Schema(nullable = true)
    val travelReason: String?,
    @field:Schema(nullable = true)
    val transportMode: String?,
    @field:Schema(nullable = true)
    val checkedInAt: Instant?,
    @field:Schema(nullable = true)
    val minorAuthorization: StayMinorAuthorizationResponse?,
) {
    companion object {
        fun of(guest: StayGuestSummary) =
            StayGuestCheckInResponse(
                guest.guestId.value,
                guest.primary,
                guest.travelReason,
                guest.transportMode,
                guest.checkedInAt,
                guest.minorAuthorization?.let {
                    StayMinorAuthorizationResponse(
                        it.proofType,
                        it.responsibleGuestId.value,
                        it.relationship,
                        it.attachmentId,
                        it.validUntil,
                    )
                },
            )
    }
}

@Schema(
    description =
        "A reserva depois da transição, resumida (o detalhe completo, com as noites e os termos, é " +
            "`GET /api/v1/reservations/{id}`).",
)
data class StayReservationResponse(
    val id: UUID,
    val code: String,
    val bookingId: UUID,
    @field:ExtensibleEnum
    @field:Schema(
        description =
            "Situação (SPEC §8.4): `PROVISIONAL`, `CONFIRMED`, `IN_HOUSE`, `CHECKED_OUT`, `CANCELLED`, `NO_SHOW`. " +
                UNKNOWN,
    )
    val status: ReservationStatus,
    val roomTypeId: UUID,
    val arrival: LocalDate,
    val departure: LocalDate,
    val dayUse: Boolean,
    val adults: Int,
    val children: Int,
    val folioId: UUID,
    @field:Schema(description = "A UH alocada; nula sem alocação.", nullable = true)
    val roomId: UUID?,
    @field:Schema(description = "O check-in; nulo depois de desfeito.", nullable = true)
    val checkIn: StayTransitionRecordResponse?,
    val guests: List<StayGuestCheckInResponse>,
    @field:Schema(description = "A versão da reserva (o `ETag` da resposta).")
    val version: Long,
) {
    companion object {
        fun of(reservation: ReservationSummary) =
            StayReservationResponse(
                id = reservation.id.value,
                code = reservation.code.toString(),
                bookingId = reservation.bookingId.value,
                status = reservation.status,
                roomTypeId = reservation.roomTypeId.value,
                arrival = reservation.arrival,
                departure = reservation.departure,
                dayUse = reservation.dayUse,
                adults = reservation.adults,
                children = reservation.childAges.size,
                folioId = reservation.folioId.value,
                roomId = reservation.roomAssignment?.roomId?.value,
                checkIn =
                    reservation.checkIn?.let {
                        StayTransitionRecordResponse(
                            it.businessDate,
                            it.at,
                            it.by?.value,
                        )
                    },
                guests = reservation.guests.map(StayGuestCheckInResponse::of),
                version = reservation.version,
            )
    }
}

@Schema(description = "O status da UH depois da transição.")
data class StayRoomResponse(
    val roomId: UUID,
    val number: String,
    val occupancy: OccupancyStatus,
    @field:Schema(description = "Número de hóspedes; só com a UH ocupada.", nullable = true)
    val guestCount: Int?,
    val housekeepingStatus: HousekeepingStatus,
    val inspectionPending: Boolean,
    @field:Schema(description = "A versão do status da UH.")
    val version: Long,
) {
    companion object {
        fun of(
            status: RoomStatus,
            number: String,
        ) = StayRoomResponse(
            status.roomId.value,
            number,
            status.occupancy,
            status.guestCount,
            status.housekeepingStatus,
            status.inspectionPending,
            status.version,
        )
    }
}

@Schema(description = "A caução registrada no check-in (não entra no saldo da conta até ser capturada).")
data class StayPreAuthorizationResponse(
    val id: UUID,
    val folioId: UUID,
    @field:ExtensibleEnum
    @field:Schema(description = "`ACTIVE` na criação. $UNKNOWN")
    val status: CardPreAuthorizationStatus,
    val amount: Money,
    val expiresAt: Instant,
) {
    companion object {
        fun of(summary: CardPreAuthorizationSummary) =
            StayPreAuthorizationResponse(
                summary.id.value,
                summary.folioId.value,
                summary.status,
                summary.amount,
                summary.expiresAt,
            )
    }
}

@Schema(description = "Um hóspede da estadia na lista de restrição (não bloqueia o check-in).")
data class StayRestrictionAlertResponse(
    val guestId: UUID,
    val reason: String,
    val since: Instant,
) {
    companion object {
        fun of(alert: RestrictionAlert) = StayRestrictionAlertResponse(alert.guestId.value, alert.reason, alert.since)
    }
}

@Schema(
    description =
        "O check-in feito: a reserva `IN_HOUSE`, a UH Ocupada, a caução registrada (nula sem caução) e os alertas da " +
            "lista de restrição, mais o early check-in lançado (nulo sem ele). Nenhum número de documento nem dado " +
            "sensível.",
)
data class CheckInResponse(
    val reservation: StayReservationResponse,
    val room: StayRoomResponse,
    @field:Schema(nullable = true)
    val preAuthorization: StayPreAuthorizationResponse?,
    val restrictionAlerts: List<StayRestrictionAlertResponse>,
    @field:Schema(
        description = "O early check-in lançado na conta da reserva; nulo sem `earlyCheckInFee`.",
        nullable = true,
    )
    val earlyCheckIn: CheckOutPostingResponse? = null,
) {
    companion object {
        fun of(outcome: CheckInOutcome) =
            CheckInResponse(
                StayReservationResponse.of(outcome.reservation),
                StayRoomResponse.of(outcome.room, outcome.roomNumber),
                outcome.preAuthorization?.let(StayPreAuthorizationResponse::of),
                outcome.restrictionAlerts.map(StayRestrictionAlertResponse::of),
                outcome.earlyCheckIn?.let(CheckOutPostingResponse::of),
            )
    }
}

@Schema(description = "O check-in desfeito: a reserva de novo `CONFIRMED`, com a UH alocada, e a UH Vaga.")
data class UndoCheckInResponse(
    val reservation: StayReservationResponse,
    val room: StayRoomResponse,
) {
    companion object {
        fun of(outcome: UndoCheckInOutcome) =
            UndoCheckInResponse(
                StayReservationResponse.of(outcome.reservation),
                StayRoomResponse.of(outcome.room, outcome.roomNumber),
            )
    }
}

@Schema(description = "O early check-in ou o late check-out pela política vigente do hotel.")
data class EarlyLateOfferResponse(
    val offered: Boolean,
    @field:Schema(
        description = "Early: o horário mais cedo de entrada; late: o mais tarde de saída. Nulo se não oferecido.",
        nullable = true,
        example = "10:00",
    )
    val limitTime: LocalTime?,
    @field:Schema(description = "O valor (nulo se não oferecido).", nullable = true)
    val fee: Money?,
    @field:Schema(
        description = "Início da janela em UTC (early: a chegada no limite; late: a saída no horário de check-out).",
        nullable = true,
    )
    val from: Instant?,
    @field:Schema(
        description = "Fim da janela em UTC (early: a chegada no horário de check-in; late: a saída no limite).",
        nullable = true,
    )
    val until: Instant?,
) {
    companion object {
        fun of(offer: EarlyLateOffer) =
            EarlyLateOfferResponse(offer.offered, offer.limitTime, offer.fee, offer.from, offer.until)
    }
}

@Schema(description = "A cotação de early check-in e late check-out de uma reserva (RN-11), no fuso do hotel.")
data class EarlyLateQuoteResponse(
    val reservationId: UUID,
    val arrival: LocalDate,
    val departure: LocalDate,
    @field:Schema(description = "O fuso do hotel (IANA).", example = "America/Sao_Paulo")
    val timezone: String,
    @field:Schema(example = "14:00")
    val checkInTime: LocalTime,
    @field:Schema(example = "12:00")
    val checkOutTime: LocalTime,
    val earlyCheckIn: EarlyLateOfferResponse,
    val lateCheckOut: EarlyLateOfferResponse,
) {
    companion object {
        fun of(quote: EarlyLateQuote) =
            EarlyLateQuoteResponse(
                reservationId = quote.reservation.id.value,
                arrival = quote.reservation.arrival,
                departure = quote.reservation.departure,
                timezone = quote.timezone.id,
                checkInTime = quote.checkInTime,
                checkOutTime = quote.checkOutTime,
                earlyCheckIn = EarlyLateOfferResponse.of(quote.earlyCheckIn),
                lateCheckOut = EarlyLateOfferResponse.of(quote.lateCheckOut),
            )
    }
}
