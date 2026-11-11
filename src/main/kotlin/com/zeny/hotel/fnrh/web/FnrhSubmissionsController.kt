package com.zeny.hotel.fnrh.web

import com.zeny.hotel.fnrh.application.FnrhSubmissionView
import com.zeny.hotel.fnrh.application.FnrhSubmissionsService
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhSubmissionFailure
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.PageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Instant
import java.util.UUID

/** As submissões à FNRH: pendências, reenvio e encerramento (RF-FNRH-03). */
@ApiV1Controller
@RequestMapping("/fnrh/submissions")
@Tag(name = "FNRH", description = "Integração com a FNRH do Serpro.")
class FnrhSubmissionsController(
    private val submissions: FnrhSubmissionsService,
) {
    @GetMapping
    @Operation(
        operationId = "listFnrhSubmissions",
        summary = "Listar as submissões à FNRH",
        description =
            "Exige `fnrh.submissions.read`. Paginação por cursor, da mais antiga à mais nova; `status` filtra por " +
                "situação. Sem dado de hóspede nem o link de pré-check-in.",
    )
    fun list(
        @Parameter(description = "Só as submissões nesta situação.")
        @RequestParam(required = false) status: FnrhSubmissionStatus?,
        @Valid page: PageParams,
    ): CursorPage<FnrhSubmissionResponse> = submissions.list(status, page).map(FnrhSubmissionResponse::of)

    @PostMapping("/{id}/retry")
    @Operation(
        operationId = "retryFnrhSubmission",
        summary = "Reenviar uma submissão que falhou ou ficou inválida",
        description =
            "Exige `fnrh.submissions.manage`. Só `FAILED` e `INVALID` (senão 409 `FNRH_SUBMISSION_NOT_RETRYABLE`): " +
                "volta a `PENDING`, sem tentativas nem erros. Inexistente: 404 `FNRH_SUBMISSION_NOT_FOUND`.",
    )
    fun retry(
        @PathVariable id: UUID,
    ): FnrhSubmissionResponse = FnrhSubmissionResponse.of(submissions.retry(id))

    @PostMapping("/{id}/dismiss")
    @Operation(
        operationId = "dismissFnrhSubmission",
        summary = "Encerrar à mão uma submissão que falhou ou ficou inválida",
        description =
            "Exige `fnrh.submissions.manage`. Só `FAILED` e `INVALID` (senão 409 `FNRH_SUBMISSION_NOT_DISMISSABLE`): " +
                "passa a `DISMISSED` e deixa de segurar as submissões seguintes da reserva. Inexistente: 404 " +
                "`FNRH_SUBMISSION_NOT_FOUND`.",
    )
    fun dismiss(
        @PathVariable id: UUID,
    ): FnrhSubmissionResponse = FnrhSubmissionResponse.of(submissions.dismiss(id))
}

@Schema(description = "Um erro de validação local de um hóspede: campo e código, nunca o valor.")
data class FnrhSubmissionErrorResponse(
    val guestId: UUID,
    val field: String,
    val code: String,
)

@Schema(description = "Uma submissão à FNRH.")
data class FnrhSubmissionResponse(
    val id: UUID,
    val reservationId: UUID,
    val reservationCode: String,
    @field:ExtensibleEnum
    @field:Schema(description = "A operação. A lista pode crescer (`x-extensible-enum`).")
    val operation: FnrhOperation,
    @field:ExtensibleEnum
    @field:Schema(description = "A situação. A lista pode crescer (`x-extensible-enum`).")
    val status: FnrhSubmissionStatus,
    val attempts: Int,
    val nextAttemptAt: Instant,
    @field:ExtensibleEnum
    @field:Schema(description = "Por que falhou. A lista pode crescer (`x-extensible-enum`).", nullable = true)
    val lastFailure: FnrhSubmissionFailure?,
    val errors: List<FnrhSubmissionErrorResponse>,
    val createdAt: Instant,
    @field:Schema(nullable = true)
    val sentAt: Instant?,
) {
    companion object {
        fun of(view: FnrhSubmissionView) =
            FnrhSubmissionResponse(
                id = view.id,
                reservationId = view.reservationId,
                reservationCode = view.reservationCode,
                operation = view.operation,
                status = view.status,
                attempts = view.attempts,
                nextAttemptAt = view.nextAttemptAt,
                lastFailure = view.lastFailure,
                errors = view.errors.map { FnrhSubmissionErrorResponse(it.guestId, it.field, it.code) },
                createdAt = view.createdAt,
                sentAt = view.sentAt,
            )
    }
}
