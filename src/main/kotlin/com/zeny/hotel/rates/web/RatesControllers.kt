package com.zeny.hotel.rates.web

import com.zeny.hotel.rates.CancellationPolicyId
import com.zeny.hotel.rates.GuaranteePolicyId
import com.zeny.hotel.rates.PackageId
import com.zeny.hotel.rates.RatePlanCategory
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.SalesChannel
import com.zeny.hotel.rates.application.CancellationPolicyService
import com.zeny.hotel.rates.application.CancellationPolicyView
import com.zeny.hotel.rates.application.GuaranteePolicyService
import com.zeny.hotel.rates.application.GuaranteePolicyView
import com.zeny.hotel.rates.application.PackageService
import com.zeny.hotel.rates.application.PackageView
import com.zeny.hotel.rates.application.RatePlanFilter
import com.zeny.hotel.rates.application.RatePlanService
import com.zeny.hotel.rates.application.RatePlanView
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.PageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
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

private const val RATES_TAG = "rates"
private const val RATES_TAG_DESCRIPTION = "Planos tarifários, políticas de cancelamento e de garantia e pacotes."
private const val ACTIVE_FILTER = "Só os ativos (`true`) ou só os inativos (`false`); omita para todos."
private const val IF_MATCH_RULE = "o `If-Match` (ausente: 428; versão diferente: 412)"
private const val READ = "Exige `rates.plans.read`."
private const val MANAGE = "Exige `rates.plans.manage`."

/**
 * Planos tarifários (RF-TAR-01): consultar exige `rates.plans.read`; escrever, `rates.plans.manage`. Nunca são
 * apagados, só desativados.
 */
@ApiV1Controller
@RequestMapping("/rate-plans")
@Tag(name = RATES_TAG, description = RATES_TAG_DESCRIPTION)
class RatePlansController(
    private val plans: RatePlanService,
) {
    @GetMapping
    @Operation(
        operationId = "listRatePlans",
        summary = "Listar planos tarifários",
        description = "Em ordem de código. $READ",
    )
    fun list(
        @Parameter(description = ACTIVE_FILTER)
        @RequestParam(required = false) active: Boolean?,
        @RequestParam(required = false) category: RatePlanCategory?,
        @Parameter(description = "Só os vendidos no canal.")
        @RequestParam(required = false) channel: SalesChannel?,
        @Parameter(description = "Só os que se aplicam ao tipo de UH.")
        @RequestParam(required = false) roomTypeId: UUID?,
        @Valid page: PageParams,
    ): CursorPage<RatePlanResponse> =
        plans
            .list(RatePlanFilter(active, category, channel, roomTypeId?.let(::RoomTypeId)), page)
            .map(RatePlanResponse::of)

    @PostMapping
    @Operation(
        operationId = "createRatePlan",
        summary = "Cadastrar um plano tarifário",
        description =
            "$MANAGE A categoria não muda depois. Código repetido (inclusive de um desativado): 409 " +
                "`RATE_PLAN_CODE_ALREADY_IN_USE`. $PLAN_REFERENCES",
    )
    @ApiResponse(responseCode = "201", description = "Plano cadastrado.")
    fun create(
        @Valid @RequestBody request: RatePlanCreateRequest,
    ): ResponseEntity<RatePlanResponse> {
        val view = plans.create(request.toCommand())
        return ResponseEntity
            .created(URI.create("/api/v1/rate-plans/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(RatePlanResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getRatePlan",
        summary = "Consultar um plano tarifário",
        description = "$READ Inexistente: 404 `RATE_PLAN_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<RatePlanResponse> = ok(plans.get(RatePlanId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateRatePlan",
        summary = "Editar um plano tarifário",
        description =
            "Troca tudo menos a categoria (os conjuntos inteiros). $MANAGE e $IF_MATCH_RULE. Só as referências novas " +
                "são conferidas. $PLAN_REFERENCES",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: RatePlanUpdateRequest,
    ): ResponseEntity<RatePlanResponse> =
        ok(plans.update(RatePlanId(id), ETags.expectedVersion(ifMatch), request.toCommand()))

    @PostMapping("/{id}/deactivate")
    @Operation(
        operationId = "deactivateRatePlan",
        summary = "Desativar um plano tarifário",
        description = "$MANAGE Nada é apagado; desativar de novo não muda nada.",
    )
    fun deactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<RatePlanResponse> = ok(plans.deactivate(RatePlanId(id)))

    @PostMapping("/{id}/reactivate")
    @Operation(
        operationId = "reactivateRatePlan",
        summary = "Reativar um plano tarifário",
        description =
            "$MANAGE Exige as políticas e os pacotes do plano ativos (422 `CANCELLATION_POLICY_INACTIVE`, " +
                "`GUARANTEE_POLICY_INACTIVE` ou `PACKAGE_INACTIVE`).",
    )
    fun reactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<RatePlanResponse> = ok(plans.reactivate(RatePlanId(id)))

    private fun ok(view: RatePlanView) =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(RatePlanResponse.of(view))

    private companion object {
        const val PLAN_REFERENCES =
            "Tipo de UH inexistente: 400 `UNKNOWN_ROOM_TYPE` (campo `roomTypeIds`), desativado: 422 " +
                "`ROOM_TYPE_INACTIVE`; política inexistente: 400 `UNKNOWN_CANCELLATION_POLICY` ou " +
                "`UNKNOWN_GUARANTEE_POLICY` (com o campo), desativada: 422 `CANCELLATION_POLICY_INACTIVE` ou " +
                "`GUARANTEE_POLICY_INACTIVE`; pacote inexistente: 400 `UNKNOWN_PACKAGE` (campo `packageIds`), " +
                "desativado: 422 `PACKAGE_INACTIVE`."
    }
}

/**
 * Políticas de cancelamento (RF-TAR-07): consultar exige `rates.plans.read`; escrever, `rates.plans.manage`. Não são
 * versionadas: a edição vale na hora para as reservas criadas depois (a reserva copia os termos, RN-15).
 */
@ApiV1Controller
@RequestMapping("/cancellation-policies")
@Tag(name = RATES_TAG, description = RATES_TAG_DESCRIPTION)
class CancellationPoliciesController(
    private val policies: CancellationPolicyService,
) {
    @GetMapping
    @Operation(
        operationId = "listCancellationPolicies",
        summary = "Listar políticas de cancelamento",
        description = "Em ordem de nome. $READ",
    )
    fun list(
        @Parameter(description = ACTIVE_FILTER)
        @RequestParam(required = false) active: Boolean?,
        @Valid page: PageParams,
    ): CursorPage<CancellationPolicyResponse> = policies.list(active, page).map(CancellationPolicyResponse::of)

    @PostMapping
    @Operation(
        operationId = "createCancellationPolicy",
        summary = "Cadastrar uma política de cancelamento",
        description = "$MANAGE $CANCELLATION_RULES",
    )
    @ApiResponse(responseCode = "201", description = "Política cadastrada.")
    fun create(
        @Valid @RequestBody request: CancellationPolicyRequest,
    ): ResponseEntity<CancellationPolicyResponse> {
        val view = policies.create(request.toCommand())
        return ResponseEntity
            .created(URI.create("/api/v1/cancellation-policies/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(CancellationPolicyResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getCancellationPolicy",
        summary = "Consultar uma política de cancelamento",
        description = "$READ Inexistente: 404 `CANCELLATION_POLICY_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<CancellationPolicyResponse> = ok(policies.get(CancellationPolicyId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateCancellationPolicy",
        summary = "Editar uma política de cancelamento",
        description =
            "Troca os termos; vale na hora para as reservas criadas depois, sem mudar as existentes. $MANAGE e " +
                "$IF_MATCH_RULE. $CANCELLATION_RULES",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: CancellationPolicyRequest,
    ): ResponseEntity<CancellationPolicyResponse> =
        ok(policies.update(CancellationPolicyId(id), ETags.expectedVersion(ifMatch), request.toCommand()))

    @PostMapping("/{id}/deactivate")
    @Operation(
        operationId = "deactivateCancellationPolicy",
        summary = "Desativar uma política de cancelamento",
        description = "$MANAGE Usada por um plano ativo: 422 `CANCELLATION_POLICY_IN_USE`. Nada é apagado.",
    )
    fun deactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<CancellationPolicyResponse> = ok(policies.deactivate(CancellationPolicyId(id)))

    @PostMapping("/{id}/reactivate")
    @Operation(
        operationId = "reactivateCancellationPolicy",
        summary = "Reativar uma política de cancelamento",
        description = MANAGE,
    )
    fun reactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<CancellationPolicyResponse> = ok(policies.reactivate(CancellationPolicyId(id)))

    private fun ok(view: CancellationPolicyView) =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(CancellationPolicyResponse.of(view))

    private companion object {
        const val CANCELLATION_RULES =
            "Nome repetido (sem diferenciar maiúsculas, inclusive de uma desativada): 409 " +
                "`CANCELLATION_POLICY_NAME_ALREADY_IN_USE`; valor da multa faltando, de outro tipo ou fora da faixa: " +
                "400 `CANCELLATION_POLICY_INVALID` (com o campo)."
    }
}

/**
 * Políticas de garantia (RF-TAR-07): consultar exige `rates.plans.read`; escrever, `rates.plans.manage`. Não são
 * versionadas: a reserva copia os termos (RN-15).
 */
@ApiV1Controller
@RequestMapping("/guarantee-policies")
@Tag(name = RATES_TAG, description = RATES_TAG_DESCRIPTION)
class GuaranteePoliciesController(
    private val policies: GuaranteePolicyService,
) {
    @GetMapping
    @Operation(
        operationId = "listGuaranteePolicies",
        summary = "Listar políticas de garantia",
        description = "Em ordem de nome. $READ",
    )
    fun list(
        @Parameter(description = ACTIVE_FILTER)
        @RequestParam(required = false) active: Boolean?,
        @Valid page: PageParams,
    ): CursorPage<GuaranteePolicyResponse> = policies.list(active, page).map(GuaranteePolicyResponse::of)

    @PostMapping
    @Operation(
        operationId = "createGuaranteePolicy",
        summary = "Cadastrar uma política de garantia",
        description = "$MANAGE $GUARANTEE_RULES",
    )
    @ApiResponse(responseCode = "201", description = "Política cadastrada.")
    fun create(
        @Valid @RequestBody request: GuaranteePolicyRequest,
    ): ResponseEntity<GuaranteePolicyResponse> {
        val view = policies.create(request.toCommand())
        return ResponseEntity
            .created(URI.create("/api/v1/guarantee-policies/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(GuaranteePolicyResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getGuaranteePolicy",
        summary = "Consultar uma política de garantia",
        description = "$READ Inexistente: 404 `GUARANTEE_POLICY_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<GuaranteePolicyResponse> = ok(policies.get(GuaranteePolicyId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateGuaranteePolicy",
        summary = "Editar uma política de garantia",
        description =
            "Troca os termos; vale na hora para as reservas criadas depois, sem mudar as existentes. $MANAGE e " +
                "$IF_MATCH_RULE. $GUARANTEE_RULES",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: GuaranteePolicyRequest,
    ): ResponseEntity<GuaranteePolicyResponse> =
        ok(policies.update(GuaranteePolicyId(id), ETags.expectedVersion(ifMatch), request.toCommand()))

    @PostMapping("/{id}/deactivate")
    @Operation(
        operationId = "deactivateGuaranteePolicy",
        summary = "Desativar uma política de garantia",
        description = "$MANAGE Usada por um plano ativo: 422 `GUARANTEE_POLICY_IN_USE`. Nada é apagado.",
    )
    fun deactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<GuaranteePolicyResponse> = ok(policies.deactivate(GuaranteePolicyId(id)))

    @PostMapping("/{id}/reactivate")
    @Operation(
        operationId = "reactivateGuaranteePolicy",
        summary = "Reativar uma política de garantia",
        description = MANAGE,
    )
    fun reactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<GuaranteePolicyResponse> = ok(policies.reactivate(GuaranteePolicyId(id)))

    private fun ok(view: GuaranteePolicyView) =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(GuaranteePolicyResponse.of(view))

    private companion object {
        const val GUARANTEE_RULES =
            "Nome repetido (sem diferenciar maiúsculas, inclusive de uma desativada): 409 " +
                "`GUARANTEE_POLICY_NAME_ALREADY_IN_USE`; depósito sem percentual ou prazo, ou eles noutro tipo: 400 " +
                "`GUARANTEE_POLICY_INVALID` (com o campo)."
    }
}

/**
 * Pacotes (RF-TAR-06): consultar exige `rates.plans.read`; escrever, `rates.plans.manage`. Cada pacote é lançado num
 * código de lançamento do `billing`. Nunca são apagados, só desativados.
 */
@ApiV1Controller
@RequestMapping("/packages")
@Tag(name = RATES_TAG, description = RATES_TAG_DESCRIPTION)
class PackagesController(
    private val packages: PackageService,
) {
    @GetMapping
    @Operation(operationId = "listPackages", summary = "Listar pacotes", description = "Em ordem de código. $READ")
    fun list(
        @Parameter(description = ACTIVE_FILTER)
        @RequestParam(required = false) active: Boolean?,
        @Valid page: PageParams,
    ): CursorPage<PackageResponse> = packages.list(active, page).map(PackageResponse::of)

    @PostMapping
    @Operation(operationId = "createPackage", summary = "Cadastrar um pacote", description = "$MANAGE $PACKAGE_RULES")
    @ApiResponse(responseCode = "201", description = "Pacote cadastrado.")
    fun create(
        @Valid @RequestBody request: PackageRequest,
    ): ResponseEntity<PackageResponse> {
        val view = packages.create(request.toCommand())
        return ResponseEntity
            .created(URI.create("/api/v1/packages/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(PackageResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getPackage",
        summary = "Consultar um pacote",
        description = "$READ Inexistente: 404 `PACKAGE_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<PackageResponse> = ok(packages.get(PackageId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updatePackage",
        summary = "Editar um pacote",
        description = "$MANAGE e $IF_MATCH_RULE. Só um código de lançamento novo é conferido. $PACKAGE_RULES",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: PackageRequest,
    ): ResponseEntity<PackageResponse> =
        ok(packages.update(PackageId(id), ETags.expectedVersion(ifMatch), request.toCommand()))

    @PostMapping("/{id}/deactivate")
    @Operation(
        operationId = "deactivatePackage",
        summary = "Desativar um pacote",
        description = "$MANAGE Num plano ativo: 422 `PACKAGE_IN_USE`. Nada é apagado.",
    )
    fun deactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<PackageResponse> = ok(packages.deactivate(PackageId(id)))

    @PostMapping("/{id}/reactivate")
    @Operation(operationId = "reactivatePackage", summary = "Reativar um pacote", description = MANAGE)
    fun reactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<PackageResponse> = ok(packages.reactivate(PackageId(id)))

    private fun ok(view: PackageView) = ResponseEntity.ok().eTag(ETags.of(view.version)).body(PackageResponse.of(view))

    private companion object {
        const val PACKAGE_RULES =
            "Código repetido: 409 `PACKAGE_CODE_ALREADY_IN_USE`; preço fora da faixa ou preço por estadia sem o " +
                "ritmo `ON_ARRIVAL`: 400 `PACKAGE_INVALID`; código de lançamento inexistente: 400 " +
                "`UNKNOWN_TRANSACTION_CODE` (campo `transactionCodeId`), desativado: 422 " +
                "`TRANSACTION_CODE_INACTIVE`, de outro tipo que não consumo: 422 `TRANSACTION_CODE_KIND_NOT_ALLOWED`."
    }
}
