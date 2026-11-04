package com.zeny.hotel.fnrh.web

import com.zeny.hotel.fnrh.application.FnrhRequesterView
import com.zeny.hotel.fnrh.application.FnrhRequestersService
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.Cpf
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import java.util.UUID

/**
 * O CPF solicitante da FNRH por usuário (RF-FNRH-01). Tudo exige `fnrh.settings.manage`. O CPF vai só no corpo, nunca
 * na URL, e volta mascarado.
 */
@ApiV1Controller
@RequestMapping("/fnrh/requesters")
@Tag(name = "FNRH", description = "Integração com a FNRH do Serpro.")
class FnrhRequestersController(
    private val requesters: FnrhRequestersService,
) {
    @GetMapping
    @Operation(
        operationId = "listFnrhRequesters",
        summary = "Listar os CPFs solicitantes por usuário",
        description = "Exige `fnrh.settings.manage`. O CPF vem mascarado.",
    )
    fun list(): List<FnrhRequesterResponse> = requesters.list().map(FnrhRequesterResponse::of)

    @PutMapping("/{userId}")
    @Operation(
        operationId = "setFnrhRequester",
        summary = "Definir o CPF solicitante de um usuário",
        description =
            "Exige `fnrh.settings.manage`. Cria ou troca o CPF do usuário; o id dele não é conferido. CPF inválido: " +
                "400 `VALIDATION_FAILED` (campo `cpf`, `INVALID_FORMAT`), sem o valor.",
    )
    fun set(
        @PathVariable userId: UUID,
        @RequestBody request: FnrhRequesterRequest,
    ): FnrhRequesterResponse = FnrhRequesterResponse.of(requesters.set(userId, request.cpf))

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        operationId = "removeFnrhRequester",
        summary = "Remover o CPF solicitante de um usuário",
        description = "Exige `fnrh.settings.manage`. Sem CPF cadastrado: 404 `FNRH_REQUESTER_NOT_FOUND`.",
    )
    fun remove(
        @PathVariable userId: UUID,
    ) = requesters.remove(userId)
}

@Schema(description = "O CPF solicitante de um usuário.")
data class FnrhRequesterRequest(
    @field:Schema(description = "CPF, com ou sem pontuação.")
    val cpf: Cpf,
) {
    /** Sem o CPF. */
    override fun toString(): String = "FnrhRequesterRequest()"
}

@Schema(description = "O CPF solicitante de um usuário, mascarado.")
data class FnrhRequesterResponse(
    val userId: UUID,
    @field:Schema(description = "CPF solicitante, só com os 2 últimos dígitos.", example = "***.***.***-25")
    val cpf: String,
) {
    companion object {
        fun of(view: FnrhRequesterView) = FnrhRequesterResponse(view.userId, view.cpf)
    }
}
