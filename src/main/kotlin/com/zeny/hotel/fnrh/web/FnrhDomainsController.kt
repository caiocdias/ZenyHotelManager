package com.zeny.hotel.fnrh.web

import com.zeny.hotel.fnrh.application.FnrhDomainCount
import com.zeny.hotel.fnrh.application.FnrhDomainValueView
import com.zeny.hotel.fnrh.application.FnrhDomainsRefreshResult
import com.zeny.hotel.fnrh.application.FnrhDomainsService
import com.zeny.hotel.shared.ApiV1Controller
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping

/**
 * Os domínios oficiais da FNRH (RF-FNRH-02), guardados em cache: a semente da migração e o último `refresh`. Ler exige
 * `fnrh.domains.read`; atualizar a partir do Serpro, `fnrh.settings.manage`.
 */
@ApiV1Controller
@RequestMapping("/fnrh/domains")
@Tag(name = "FNRH", description = "Integração com a FNRH do Serpro.")
class FnrhDomainsController(
    private val domains: FnrhDomainsService,
) {
    @GetMapping("/{domain}")
    @Operation(
        operationId = "listFnrhDomainValues",
        summary = "Listar os valores de um domínio oficial da FNRH",
        description =
            "Exige `fnrh.domains.read`. O `{domain}` é um de `transport-modes`, `travel-reasons`, `guest-statuses`, " +
                "`genders`, `disability-options`, `races`, `disability-types`, `document-types`, `booking-statuses` " +
                "ou `record-statuses`; outro nome é 404 `FNRH_DOMAIN_NOT_FOUND`. Os valores vêm do cache (a semente " +
                "da migração ou o último `refresh`), em ordem de código.",
    )
    fun values(
        @PathVariable domain: String,
    ): List<FnrhDomainValueResponse> = domains.values(domain).map { FnrhDomainValueResponse.of(it) }

    @PostMapping("/refresh")
    @Operation(
        operationId = "refreshFnrhDomains",
        summary = "Atualizar os domínios oficiais a partir do Serpro",
        description =
            "Exige `fnrh.settings.manage`. Busca os dez domínios no Serpro e, só se todas as buscas derem certo, " +
                "substitui o cache de uma vez (tudo ou nada). Sem configuração: 422 `FNRH_NOT_CONFIGURED`. " +
                "Credenciais recusadas: 422 `FNRH_CREDENTIALS_REJECTED`. Serpro fora do ar: 503 " +
                "`FNRH_UNAVAILABLE`. Consulta recusada ou domínio vazio: 502 `FNRH_REJECTED`. A resposta traz a " +
                "contagem por domínio.",
    )
    fun refresh(): FnrhDomainsRefreshResponse = FnrhDomainsRefreshResponse.of(domains.refresh())
}

@Schema(description = "Um valor de domínio oficial da FNRH.")
data class FnrhDomainValueResponse(
    @field:Schema(description = "O `id` que a API do Serpro aceita.", example = "ONIBUS")
    val code: String,
    @field:Schema(description = "O rótulo para o usuário.", example = "Ônibus")
    val label: String,
) {
    companion object {
        fun of(value: FnrhDomainValueView) = FnrhDomainValueResponse(value.code, value.label)
    }
}

@Schema(description = "Quantos valores um domínio tem depois do `refresh`.")
data class FnrhDomainCountResponse(
    @field:Schema(description = "O nome do domínio na API.", example = "transport-modes")
    val domain: String,
    val count: Int,
) {
    companion object {
        fun of(count: FnrhDomainCount) = FnrhDomainCountResponse(count.domain, count.count)
    }
}

@Schema(description = "O resultado do `refresh`: a contagem de cada domínio.")
data class FnrhDomainsRefreshResponse(
    val domains: List<FnrhDomainCountResponse>,
) {
    companion object {
        fun of(result: FnrhDomainsRefreshResult) =
            FnrhDomainsRefreshResponse(result.domains.map { FnrhDomainCountResponse.of(it) })
    }
}
