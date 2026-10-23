package com.zeny.hotel.frontdesk.web

import com.zeny.hotel.billing.FolioStatus
import com.zeny.hotel.billing.FolioSummary
import com.zeny.hotel.billing.PostingSummary
import com.zeny.hotel.frontdesk.application.CheckOutCommand
import com.zeny.hotel.frontdesk.application.CheckOutOutcome
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate
import java.util.UUID

// --- Pedido ---------------------------------------------------------------------------------------------------------

@Schema(
    description =
        "O check-out (corpo opcional): o valor do late check-out que foi cotado ao hóspede " +
            "(`GET /api/v1/reservations/{id}/early-late-quote`, RN-11). Sem ele, nenhum late é cobrado.",
)
data class CheckOutRequest(
    @field:Schema(
        description =
            "O valor do late check-out cotado (positivo, até duas casas); conferido com o da política vigente. " +
                "Nulo: sem late.",
        nullable = true,
        example = "80.00",
    )
    val lateCheckOutFee: Money? = null,
) {
    fun toCommand(): CheckOutCommand {
        if (lateCheckOutFee != null && !lateCheckOutFee.isPositive()) {
            val message = "O valor do late check-out precisa ser positivo."
            throw InvalidInputException(
                ErrorCodes.VALIDATION_FAILED,
                message,
                listOf(FieldViolation("lateCheckOutFee", POSITIVE, message)),
            )
        }
        return CheckOutCommand(lateCheckOutFee)
    }

    companion object {
        /** O código da restrição como o da Bean Validation (`@Positive`). */
        private const val POSITIVE = "POSITIVE"

        /** Sem corpo: sem late. */
        val NONE = CheckOutRequest()
    }
}

// --- Resposta -------------------------------------------------------------------------------------------------------

@Schema(description = "Um lançamento feito pelo check-out, na conta final (depois do roteamento).")
data class CheckOutPostingResponse(
    val postingId: UUID,
    val folioId: UUID,
    val transactionCodeId: UUID,
    val amount: Money,
    @field:Schema(description = "A data operacional do lançamento.")
    val businessDate: LocalDate,
) {
    companion object {
        fun of(posting: PostingSummary) =
            CheckOutPostingResponse(
                posting.id.value,
                posting.folioId.value,
                posting.transactionCodeId.value,
                posting.amount,
                posting.businessDate,
            )
    }
}

@Schema(description = "Uma conta da reserva encerrada pelo check-out.")
data class CheckOutFolioResponse(
    val folioId: UUID,
    @field:Schema(description = "Número legível da conta, como `C-2026-000123`.")
    val number: DocumentNumber,
    val status: FolioStatus,
) {
    companion object {
        fun of(folio: FolioSummary) = CheckOutFolioResponse(folio.id.value, folio.number, folio.status)
    }
}

@Schema(
    description =
        "O check-out feito: a reserva `CHECKED_OUT`, a UH Vaga e Suja, as cobranças da noite da chegada (no day use " +
            "ou no pernoite que sai no dia em que chegou: a hospedagem sem os pacotes inclusos e cada pacote, no " +
            "código dele), o late cobrado (nulo sem late), as cauções liberadas e as contas encerradas. Nenhum dado " +
            "do hóspede.",
)
data class CheckOutResponse(
    val reservation: StayReservationResponse,
    val room: StayRoomResponse,
    val stayCharges: List<CheckOutPostingResponse>,
    @field:Schema(nullable = true)
    val lateCheckOut: CheckOutPostingResponse?,
    val releasedPreAuthorizations: List<StayPreAuthorizationResponse>,
    val closedFolios: List<CheckOutFolioResponse>,
) {
    companion object {
        fun of(outcome: CheckOutOutcome) =
            CheckOutResponse(
                StayReservationResponse.of(outcome.reservation),
                StayRoomResponse.of(outcome.room, outcome.roomNumber),
                outcome.stayCharges.map(CheckOutPostingResponse::of),
                outcome.lateCheckOut?.let(CheckOutPostingResponse::of),
                outcome.releasedPreAuthorizations.map(StayPreAuthorizationResponse::of),
                outcome.closedFolios.map(CheckOutFolioResponse::of),
            )
    }
}
