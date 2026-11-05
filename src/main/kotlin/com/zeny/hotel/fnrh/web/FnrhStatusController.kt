package com.zeny.hotel.fnrh.web

import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.application.FnrhFailure
import com.zeny.hotel.fnrh.application.FnrhStatus
import com.zeny.hotel.fnrh.application.FnrhStatusService
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.ExtensibleEnum
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import java.time.Instant

/** O status da integração com a FNRH (RF-FNRH-01). Exige `fnrh.submissions.read`. */
@ApiV1Controller
@RequestMapping("/fnrh/status")
@Tag(name = "FNRH", description = "Integração com a FNRH do Serpro.")
class FnrhStatusController(
    private val status: FnrhStatusService,
) {
    @GetMapping
    @Operation(
        operationId = "getFnrhStatus",
        summary = "Consultar o status da integração com a FNRH",
        description =
            "Exige `fnrh.submissions.read`. Os instantes são os do último sucesso e da última falha do ambiente " +
                "atual, lidos do registro de chamadas (que não guarda dado pessoal).",
    )
    fun get(): FnrhStatusResponse = FnrhStatusResponse.of(status.status())
}

@Schema(description = "O status da integração com a FNRH no ambiente atual.")
data class FnrhStatusResponse(
    @field:Schema(description = "Se há usuário, senha e CPF solicitante padrão gravados.")
    val configured: Boolean,
    @field:Schema(description = "Se a integração está ligada.")
    val enabled: Boolean,
    @field:ExtensibleEnum
    @field:Schema(description = "Ambiente do Serpro. A lista pode crescer (`x-extensible-enum`).")
    val environment: FnrhEnvironment,
    @field:Schema(description = "Instante da última chamada bem-sucedida neste ambiente.", nullable = true)
    val lastSuccessAt: Instant?,
    @field:Schema(description = "Instante da última chamada que falhou neste ambiente.", nullable = true)
    val lastFailureAt: Instant?,
    @field:ExtensibleEnum
    @field:Schema(
        description = "Resultado da última falha. A lista pode crescer (`x-extensible-enum`).",
        nullable = true,
    )
    val lastFailureOutcome: FnrhFailure?,
) {
    companion object {
        fun of(status: FnrhStatus) =
            FnrhStatusResponse(
                configured = status.configured,
                enabled = status.enabled,
                environment = status.environment,
                lastSuccessAt = status.lastSuccessAt,
                lastFailureAt = status.lastFailureAt,
                lastFailureOutcome = status.lastFailureOutcome,
            )
    }
}
