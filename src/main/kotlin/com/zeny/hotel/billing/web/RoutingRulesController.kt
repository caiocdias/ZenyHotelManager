package com.zeny.hotel.billing.web

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.RoutingRuleId
import com.zeny.hotel.billing.application.RoutingRuleFilter
import com.zeny.hotel.billing.application.RoutingRuleService
import com.zeny.hotel.billing.application.RoutingRuleView
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.Idempotent
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

private const val RULE_CHECKS =
    "Com a regra ativa: origem encerrada: 409 `FOLIO_CLOSED`; destino encerrado: 409 `ROUTING_TARGET_CLOSED`; outra " +
        "regra ativa da mesma origem e do mesmo nível (por código, com algum código em comum, ou pelo mesmo grupo) " +
        "em período que se cruza: 409 `ROUTING_RULE_OVERLAP`; regra que formaria um ciclo (a cadeia voltaria à " +
        "origem, inclusive origem = destino): 422 `ROUTING_RULE_CYCLE`. Códigos e grupo juntos, nenhum dos dois, " +
        "código repetido ou fim antes do início: 400 `ROUTING_RULE_INVALID`; código inexistente: 400 " +
        "`UNKNOWN_TRANSACTION_CODE`; conta inexistente: 400 `UNKNOWN_FOLIO`."

/**
 * Regras de roteamento (RF-CTA-04): consultar exige `billing.folios.read`; escrever, `billing.routing.manage`. A regra
 * vale no momento do lançamento, na data operacional; nunca muda lançamentos já feitos. Nada é apagado.
 */
@ApiV1Controller
@RequestMapping("/routing-rules")
@Tag(name = BILLING_TAG, description = BILLING_TAG_DESCRIPTION)
class RoutingRulesController(
    private val rules: RoutingRuleService,
) {
    @GetMapping
    @Operation(
        operationId = "listRoutingRules",
        summary = "Listar as regras de roteamento",
        description = "Em ordem de criação. Exige `billing.folios.read`.",
    )
    fun list(
        @Parameter(description = "Só as da conta de origem.")
        @RequestParam(required = false) sourceFolioId: UUID?,
        @Parameter(description = "Só as que levam à conta de destino.")
        @RequestParam(required = false) targetFolioId: UUID?,
        @Parameter(description = "Só as ativas (`true`) ou só as desativadas (`false`); omita para todas.")
        @RequestParam(required = false) active: Boolean?,
        @Valid page: PageParams,
    ): CursorPage<RoutingRuleResponse> =
        rules
            .list(RoutingRuleFilter(sourceFolioId?.let(::FolioId), targetFolioId?.let(::FolioId), active), page)
            .map(RoutingRuleResponse::of)

    @PostMapping
    @Idempotent
    @Operation(
        operationId = "createRoutingRule",
        summary = "Criar uma regra de roteamento",
        description =
            "Os lançamentos pedidos na conta de origem, nos códigos ou no grupo de receita da regra e na data " +
                "operacional dentro do período, vão para a conta de destino; com A → B e B → C, o lançamento em A " +
                "vai a C. Na mesma conta, a regra por código prevalece sobre a por grupo. Exige " +
                "`billing.routing.manage`. $RULE_CHECKS",
    )
    @ApiResponse(responseCode = "201", description = "Regra criada.")
    fun create(
        @Valid @RequestBody request: RoutingRuleCreateRequest,
    ): ResponseEntity<RoutingRuleResponse> {
        val view = rules.create(FolioId(checkNotNull(request.sourceFolioId)), request.toCommand())
        return ResponseEntity
            .created(URI.create("/api/v1/routing-rules/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(RoutingRuleResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getRoutingRule",
        summary = "Consultar uma regra de roteamento",
        description = "Exige `billing.folios.read`. Inexistente: 404 `ROUTING_RULE_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<RoutingRuleResponse> = ok(rules.get(RoutingRuleId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateRoutingRule",
        summary = "Editar uma regra de roteamento",
        description =
            "Troca o destino, o critério e o período (a origem não muda). Exige " +
                "`billing.routing.manage` e o `If-Match` (ausente: 428; versão diferente: 412). Lançamentos já " +
                "feitos não mudam. $RULE_CHECKS",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: RoutingRuleRequest,
    ): ResponseEntity<RoutingRuleResponse> =
        ok(rules.update(RoutingRuleId(id), ETags.expectedVersion(ifMatch), request.toCommand()))

    @PostMapping("/{id}/deactivate")
    @Operation(
        operationId = "deactivateRoutingRule",
        summary = "Desativar uma regra de roteamento",
        description = "Os lançamentos seguintes ficam na origem. Exige `billing.routing.manage`.",
    )
    fun deactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<RoutingRuleResponse> = ok(rules.deactivate(RoutingRuleId(id)))

    @PostMapping("/{id}/reactivate")
    @Operation(
        operationId = "reactivateRoutingRule",
        summary = "Reativar uma regra de roteamento",
        description = "Exige `billing.routing.manage`. $RULE_CHECKS",
    )
    fun reactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<RoutingRuleResponse> = ok(rules.reactivate(RoutingRuleId(id)))

    private fun ok(view: RoutingRuleView) =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(RoutingRuleResponse.of(view))
}
