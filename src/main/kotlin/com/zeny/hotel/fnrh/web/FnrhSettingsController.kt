package com.zeny.hotel.fnrh.web

import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.application.FnrhSettingsCommand
import com.zeny.hotel.fnrh.application.FnrhSettingsService
import com.zeny.hotel.fnrh.application.FnrhSettingsView
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.ExtensibleEnum
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping

private const val UNKNOWN = "A lista pode crescer (`x-extensible-enum`): trate um valor desconhecido sem falhar."

/**
 * A configuração da integração com a FNRH (RF-FNRH-01). Consultar e editar exigem `fnrh.settings.manage`; a edição
 * pede o `If-Match` da última leitura (ausente: 428; outra versão: 412). A senha nunca volta e o CPF vem mascarado.
 */
@ApiV1Controller
@RequestMapping("/fnrh/settings")
@Tag(name = "FNRH", description = "Integração com a FNRH do Serpro.")
class FnrhSettingsController(
    private val settings: FnrhSettingsService,
) {
    @GetMapping
    @Operation(
        operationId = "getFnrhSettings",
        summary = "Consultar a configuração da FNRH",
        description =
            "Exige `fnrh.settings.manage`. A senha nunca volta (só `passwordSet`) e o CPF vem mascarado. O `ETag` " +
                "vai no `If-Match` da edição.",
    )
    fun get(): ResponseEntity<FnrhSettingsResponse> = ok(settings.settings())

    @PutMapping
    @Operation(
        operationId = "updateFnrhSettings",
        summary = "Editar a configuração da FNRH",
        description =
            "Exige `fnrh.settings.manage` e o `If-Match`. Sem `password`, a senha atual é mantida; uma senha em " +
                "branco é 400 no campo. CPF inválido: 400 `VALIDATION_FAILED` (campo `defaultRequesterCpf`, " +
                "`INVALID_FORMAT`), sem o valor. Trocar de ambiente sem informar a senha: 422 " +
                "`FNRH_PASSWORD_REQUIRED_FOR_ENVIRONMENT_CHANGE`. Ligar sem usuário, senha ou CPF: 422 " +
                "`FNRH_SETTINGS_INCOMPLETE`.",
    )
    fun update(
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: FnrhSettingsRequest,
    ): ResponseEntity<FnrhSettingsResponse> = ok(settings.update(ETags.expectedVersion(ifMatch), request.toCommand()))

    private fun ok(view: FnrhSettingsView): ResponseEntity<FnrhSettingsResponse> =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(FnrhSettingsResponse.of(view))
}

@Schema(description = "A configuração da integração com a FNRH.")
data class FnrhSettingsRequest(
    @field:Schema(
        description = "Ambiente do Serpro. Trocá-lo exige informar a senha de novo.",
        example = "HOMOLOGATION",
    )
    val environment: FnrhEnvironment,
    @field:Size(max = MAX_TEXT)
    @field:Pattern(regexp = "[^:]*")
    @field:Schema(description = "Usuário do Serpro (HTTP Basic): não pode conter ':'.", nullable = true)
    val username: String?,
    @field:Schema(
        description = "Senha do Serpro. Ausente: mantém a atual. Em branco: 400.",
        nullable = true,
        writeOnly = true,
    )
    @field:Size(max = MAX_TEXT)
    @field:Pattern(regexp = "(?s).*\\S.*")
    val password: String?,
    @field:Schema(description = "CPF solicitante padrão, com ou sem pontuação.")
    val defaultRequesterCpf: Cpf?,
    @field:Schema(description = "Liga a integração; exige usuário, senha e CPF.")
    val enabled: Boolean,
) {
    fun toCommand() = FnrhSettingsCommand(environment, username, password, defaultRequesterCpf, enabled)

    /** Sem senha nem CPF. */
    override fun toString(): String = "FnrhSettingsRequest(environment=$environment, enabled=$enabled)"

    private companion object {
        const val MAX_TEXT = 200
    }
}

@Schema(description = "A configuração da FNRH. Nunca traz a senha.")
data class FnrhSettingsResponse(
    @field:ExtensibleEnum
    @field:Schema(description = "Ambiente do Serpro. $UNKNOWN")
    val environment: FnrhEnvironment,
    val username: String?,
    @field:Schema(description = "Se há uma senha gravada.")
    val passwordSet: Boolean,
    @field:Schema(description = "CPF solicitante padrão, só com os 2 últimos dígitos.", example = "***.***.***-25")
    val defaultRequesterCpf: String?,
    val enabled: Boolean,
) {
    companion object {
        fun of(view: FnrhSettingsView) =
            FnrhSettingsResponse(
                environment = view.environment,
                username = view.username,
                passwordSet = view.passwordSet,
                defaultRequesterCpf = view.defaultRequesterCpf,
                enabled = view.enabled,
            )
    }
}
