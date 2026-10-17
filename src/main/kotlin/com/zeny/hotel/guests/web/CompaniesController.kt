package com.zeny.hotel.guests.web

import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.guests.application.CompanyFilter
import com.zeny.hotel.guests.application.CompanyService
import com.zeny.hotel.guests.application.CompanyView
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.Cnpj
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.PageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
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
import java.util.UUID

private const val CNPJ_RULES =
    "CNPJ com dígito verificador errado: 400 `VALIDATION_FAILED` (campo `cnpj`, `INVALID_FORMAT`); já usado por " +
        "outra empresa, inclusive desativada: 409 `CNPJ_ALREADY_IN_USE`."

/**
 * Empresas (RF-HOS-06): pagadoras de contas, tomadoras de nota e clientes de eventos. Consultar exige
 * `guests.companies.read`; escrever, `guests.companies.manage`. CNPJ único sempre; nunca são apagadas, só
 * desativadas.
 */
@ApiV1Controller
@RequestMapping("/companies")
@Tag(name = GUESTS_TAG, description = GUESTS_TAG_DESCRIPTION)
class CompaniesController(
    private val companies: CompanyService,
) {
    @GetMapping
    @Operation(
        operationId = "listCompanies",
        summary = "Buscar empresas",
        description =
            "Em ordem de nome (sem acento). `name` procura cada palavra como trecho da razão social ou do nome " +
                "fantasia; `cnpj` é exato. Exige `guests.companies.read`.",
    )
    fun list(
        @Parameter(description = "Trecho da razão social ou do nome fantasia (de 2 a 200 caracteres).")
        @RequestParam(required = false)
        @Size(min = 2, max = 200)
        name: String?,
        @Parameter(description = "CNPJ, com ou sem pontuação.")
        @RequestParam(required = false) cnpj: Cnpj?,
        @Parameter(description = "Só as ativas (`true`) ou só as desativadas (`false`); omita para todas.")
        @RequestParam(required = false) active: Boolean?,
        @Valid page: PageParams,
    ): CursorPage<CompanyResponse> = companies.search(CompanyFilter(name, cnpj, active), page).map(CompanyResponse::of)

    @PostMapping
    @Operation(
        operationId = "createCompany",
        summary = "Cadastrar uma empresa",
        description = "Exige `guests.companies.manage`. $CNPJ_RULES",
    )
    @ApiResponse(responseCode = "201", description = "Empresa cadastrada.")
    fun create(
        @Valid @RequestBody request: CompanyRequest,
    ): ResponseEntity<CompanyResponse> {
        val view = companies.create(request.toCommand())
        return ResponseEntity
            .created(URI.create("/api/v1/companies/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(CompanyResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getCompany",
        summary = "Consultar uma empresa",
        description = "Exige `guests.companies.read`. Inexistente: 404 `COMPANY_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<CompanyResponse> = ok(companies.company(CompanyId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateCompany",
        summary = "Editar uma empresa",
        description =
            "Troca todos os dados. Exige `guests.companies.manage` e o `If-Match` (ausente: 428; versão diferente: " +
                "412). $CNPJ_RULES",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: CompanyRequest,
    ): ResponseEntity<CompanyResponse> =
        ok(companies.update(CompanyId(id), ETags.expectedVersion(ifMatch), request.toCommand()))

    @PostMapping("/{id}/deactivate")
    @Operation(
        operationId = "deactivateCompany",
        summary = "Desativar uma empresa",
        description =
            "Exige `guests.companies.manage`. Nada é apagado e o CNPJ continua dela; perfis novos não a recebem " +
                "(422 `COMPANY_INACTIVE`).",
    )
    fun deactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<CompanyResponse> = ok(companies.deactivate(CompanyId(id)))

    @PostMapping("/{id}/reactivate")
    @Operation(
        operationId = "reactivateCompany",
        summary = "Reativar uma empresa",
        description = "Exige `guests.companies.manage`.",
    )
    fun reactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<CompanyResponse> = ok(companies.reactivate(CompanyId(id)))

    private fun ok(view: CompanyView) = ResponseEntity.ok().eTag(ETags.of(view.version)).body(CompanyResponse.of(view))
}
