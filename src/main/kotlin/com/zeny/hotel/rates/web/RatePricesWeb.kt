package com.zeny.hotel.rates.web

import com.zeny.hotel.rates.RateAdjustmentType
import com.zeny.hotel.rates.RatePlanDerivation
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.SeasonId
import com.zeny.hotel.rates.application.ChildPriceValue
import com.zeny.hotel.rates.application.DailyPriceView
import com.zeny.hotel.rates.application.DerivationCommand
import com.zeny.hotel.rates.application.PriceChangeView
import com.zeny.hotel.rates.application.PriceInput
import com.zeny.hotel.rates.application.PriceTarget
import com.zeny.hotel.rates.application.RatePlanDerivationService
import com.zeny.hotel.rates.application.RatePlanView
import com.zeny.hotel.rates.application.RatePriceService
import com.zeny.hotel.rates.application.RatePricesView
import com.zeny.hotel.rates.application.SeasonCommand
import com.zeny.hotel.rates.application.SeasonService
import com.zeny.hotel.rates.application.SeasonView
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.APPROVAL_TOKEN_HEADER
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.Percent
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
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
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val PRICES_TAG = "rates"
private const val PRICES_TAG_DESCRIPTION = "Planos tarifários, políticas de cancelamento e de garantia e pacotes."
private const val MAX_ROOM_TYPES = 50
private const val MAX_CHILD_BANDS = 10
private const val MAX_CHILD_AGE = 17L
private const val PUBLISHED_RULE =
    "Exige `rates.prices.manage`. Alterar ou retirar uma **tarifa publicada** (preço já gravado, a partir da data " +
        "operacional, de um plano ativo ou base de um plano ativo) é ação sensível (`rates.prices.override`): " +
        "sem ela, 403 `APPROVAL_REQUIRED`; repita com o `X-Approval-Token`. Gravar onde não havia, regravar os " +
        "mesmos valores ou mexer num plano não publicado não pede aprovação."
private const val SELECTION_RULES =
    "As linhas são os tipos de UH (do plano; senão 422 `ROOM_TYPE_NOT_IN_PLAN`) × as datas do período (`from` e " +
        "`to`, até 366 dias, a partir da data operacional) **ou** da temporada (`seasonId`, cortada na data " +
        "operacional), só nos `weekdays` (vazio = todos). Período e temporada juntos, ou nenhum: 400 " +
        "`RATE_PRICE_SELECTION_INVALID`; fim antes do início ou nenhuma data nos dias da semana: 400 " +
        "`RATE_PRICE_PERIOD_INVALID`; mais de 366 dias: 400 `RATE_PRICE_PERIOD_TOO_LONG` (campo `to`); começo " +
        "antes da data operacional (ou temporada já terminada): 422 `RATE_PRICE_PERIOD_IN_PAST`; temporada " +
        "inexistente 400 `UNKNOWN_SEASON`, desativada 422 `SEASON_INACTIVE`; plano derivado: 422 `RATE_PLAN_DERIVED`."

// --- Preços --------------------------------------------------------------------------------------------------------

@Schema(description = "Preço de criança numa faixa etária (idades em anos completos, inclusive).")
data class ChildPriceRequest(
    @field:NotNull @field:Min(0) @field:Max(MAX_CHILD_AGE)
    val minAge: Int?,
    @field:NotNull @field:Min(0) @field:Max(MAX_CHILD_AGE)
    val maxAge: Int?,
    @field:NotNull
    val amount: Money?,
)

@Schema(
    description =
        "Os preços de uma data (RF-TAR-02), de 0,00 a 1.000.000,00: diária de 1 e de 2 adultos, por adulto extra " +
            "(além do segundo) e por criança, numa das faixas etárias atuais do hotel (a faixa única de 0 à idade " +
            "limite de criança, se o hotel não diferencia).",
)
data class PriceValuesRequest(
    @field:NotNull
    val oneAdult: Money?,
    @field:Schema(nullable = true, description = "Nulo: sem preço para 2 adultos nesta data.")
    val twoAdults: Money? = null,
    @field:Schema(nullable = true, description = "Nulo: sem preço de adulto extra nesta data.")
    val extraAdult: Money? = null,
    @field:Schema(description = "Um por faixa atual, no máximo; a faixa omitida fica sem preço de criança.")
    @field:Size(max = MAX_CHILD_BANDS)
    val children: List<@Valid ChildPriceRequest> = emptyList(),
) {
    fun toInput() =
        PriceInput(
            checkNotNull(oneAdult),
            twoAdults,
            extraAdult,
            children.map { ChildPriceValue(checkNotNull(it.minAge), checkNotNull(it.maxAge), checkNotNull(it.amount)) },
        )
}

@Schema(description = "Edição em massa de preços (RF-TAR-04): os valores gravados em todas as linhas selecionadas.")
data class RatePriceUpdateRequest(
    @field:Schema(description = "Tipos de UH do plano (1 a 50).")
    @field:NotEmpty
    @field:Size(max = MAX_ROOM_TYPES)
    val roomTypeIds: List<UUID> = emptyList(),
    @field:Schema(nullable = true, description = "Início do período (com `to`), ou use `seasonId`.")
    val from: LocalDate? = null,
    @field:Schema(nullable = true, description = "Fim do período, inclusive.")
    val to: LocalDate? = null,
    @field:Schema(nullable = true, description = "A temporada como período, em vez de `from` e `to`.")
    val seasonId: UUID? = null,
    @field:Schema(description = "Só estes dias da semana; vazio = todos.")
    @field:Size(max = 7)
    val weekdays: List<DayOfWeek> = emptyList(),
    @field:NotNull
    @field:Valid
    val prices: PriceValuesRequest?,
) {
    fun toTarget() = target(roomTypeIds, from, to, seasonId, weekdays)
}

@Schema(description = "Retirada em massa de preços: as linhas selecionadas ficam sem preço (não vendáveis).")
data class RatePriceRemovalRequest(
    @field:Schema(description = "Tipos de UH do plano (1 a 50).")
    @field:NotEmpty
    @field:Size(max = MAX_ROOM_TYPES)
    val roomTypeIds: List<UUID> = emptyList(),
    @field:Schema(nullable = true)
    val from: LocalDate? = null,
    @field:Schema(nullable = true)
    val to: LocalDate? = null,
    @field:Schema(nullable = true)
    val seasonId: UUID? = null,
    @field:Size(max = 7)
    val weekdays: List<DayOfWeek> = emptyList(),
) {
    fun toTarget() = target(roomTypeIds, from, to, seasonId, weekdays)
}

private fun target(
    roomTypeIds: List<UUID>,
    from: LocalDate?,
    to: LocalDate?,
    seasonId: UUID?,
    weekdays: List<DayOfWeek>,
) = PriceTarget(roomTypeIds.map(::RoomTypeId).toSet(), from, to, seasonId?.let(::SeasonId), weekdays.toSet())

@Schema(description = "O resultado de uma edição em massa de preços.")
data class RatePriceChangeResponse(
    val ratePlanId: UUID,
    @field:Schema(description = "Datas selecionadas (do período, nos dias da semana pedidos).")
    val matchedDates: Int,
    @field:Schema(description = "Linhas (tipo de UH × data) com preço novo.")
    val created: Int,
    @field:Schema(description = "Linhas que já tinham preço e mudaram.")
    val changed: Int,
    @field:Schema(description = "Linhas retiradas.")
    val removed: Int,
    @field:Schema(nullable = true, description = "Primeira data mudada; nula se nada mudou.")
    val from: LocalDate?,
    @field:Schema(nullable = true, description = "Última data mudada; nula se nada mudou.")
    val to: LocalDate?,
    @field:Schema(description = "Planos que derivam deste (os preços deles mudaram junto); vazio se nada mudou.")
    val derivedRatePlanIds: List<UUID>,
) {
    companion object {
        fun of(view: PriceChangeView) =
            RatePriceChangeResponse(
                view.ratePlanId.value,
                view.matchedDates,
                view.created,
                view.changed,
                view.removed,
                view.from,
                view.to,
                view.derivedRatePlanIds.map { it.value },
            )
    }
}

@Schema(description = "Preço de criança numa faixa etária atual do hotel.")
data class ChildRatePriceResponse(
    val minAge: Int,
    val maxAge: Int,
    val label: String,
    val amount: Money,
)

@Schema(description = "Preço de criança gravado numa faixa que o hotel não usa mais: ignorado até regravar a data.")
data class ObsoleteChildPriceResponse(
    val minAge: Int,
    val maxAge: Int,
    val amount: Money,
)

@Schema(
    description =
        "O que a derivação deixou sem preço nesta data: o ajuste do plano derivado deu zero ou menos (o valor " +
            "do base em 0,00 continua 0,00 sob percentual; um valor negativo somado a ele o deixa sem preço). A " +
            "ocupação fica não vendável, como uma sem preço gravado; a regra vale nas outras datas.",
)
data class UnpricedByDerivationResponse(
    val oneAdult: Boolean,
    val twoAdults: Boolean,
    val extraAdult: Boolean,
    @field:Schema(description = "As faixas de criança sem preço.")
    val children: List<AgeRangeResponse>,
)

@Schema(description = "Uma faixa etária de criança (idades em anos completos, inclusive).")
data class AgeRangeResponse(
    val minAge: Int,
    val maxAge: Int,
)

@Schema(description = "Os preços de um tipo de UH numa data.")
data class DailyRatePriceResponse(
    val date: LocalDate,
    val roomTypeId: UUID,
    @field:Schema(
        nullable = true,
        description = "Sempre presente num plano com preços próprios; nulo só num derivado que o deixou sem preço.",
    )
    val oneAdult: Money?,
    @field:Schema(nullable = true)
    val twoAdults: Money?,
    @field:Schema(nullable = true)
    val extraAdult: Money?,
    @field:Schema(description = "Um por faixa atual que tem preço, em ordem de idade.")
    val children: List<ChildRatePriceResponse>,
    @field:Schema(
        description =
            "Faixas obsoletas (gravadas com faixas etárias que o hotel não usa mais): não valem; a data fica sem " +
                "preço de criança nessas idades até o gestor regravar.",
    )
    val obsoleteChildren: List<ObsoleteChildPriceResponse>,
    @field:Schema(
        nullable = true,
        description =
            "Só num plano derivado: as ocupações que o ajuste deixou sem preço (zero ou menos) nesta data, não " +
                "vendáveis; nulo se nenhuma.",
    )
    val unpricedByDerivation: UnpricedByDerivationResponse?,
) {
    companion object {
        fun of(view: DailyPriceView) =
            DailyRatePriceResponse(
                view.date,
                view.roomTypeId.value,
                view.oneAdult,
                view.twoAdults,
                view.extraAdult,
                view.children.map { ChildRatePriceResponse(it.minAge, it.maxAge, it.label, it.amount) },
                view.obsoleteChildren.map { ObsoleteChildPriceResponse(it.minAge, it.maxAge, it.amount) },
                view.unpricedByDerivation?.let { gaps ->
                    UnpricedByDerivationResponse(
                        gaps.oneAdult,
                        gaps.twoAdults,
                        gaps.extraAdult,
                        gaps.children.map { AgeRangeResponse(it.minAge, it.maxAge) },
                    )
                },
            )
    }
}

@Schema(description = "Os preços de um plano num período (calculados na leitura, num plano derivado).")
data class RatePricesResponse(
    val ratePlanId: UUID,
    @field:Schema(description = "Se o plano é derivado (os preços vêm do base, ajustados).")
    val derived: Boolean,
    @field:Schema(description = "O plano cujos preços gravados são a origem (o próprio, se não é derivado).")
    val pricedBy: UUID,
    val from: LocalDate,
    val to: LocalDate,
    @field:Schema(
        description = "Uma linha por tipo de UH e data com preço, em ordem de data; datas sem preço ficam fora.",
    )
    val items: List<DailyRatePriceResponse>,
) {
    companion object {
        fun of(view: RatePricesView) =
            RatePricesResponse(
                view.ratePlanId.value,
                view.derived,
                view.pricedBy.value,
                view.from,
                view.to,
                view.items.map(DailyRatePriceResponse::of),
            )
    }
}

// --- Derivação -----------------------------------------------------------------------------------------------------

@Schema(description = "A regra de um plano derivado (RF-TAR-03).")
data class RatePlanDerivationDto(
    val basePlanId: UUID,
    val type: RateAdjustmentType,
    @field:Schema(nullable = true, description = "Só no `PERCENT`: percentual sobre cada valor do base.")
    val percent: Percent?,
    @field:Schema(nullable = true, description = "Só no `AMOUNT`: valor somado (negativo = desconto).")
    val amount: Money?,
    @field:Schema(nullable = true, description = "Só no `AMOUNT`: por pessoa (`true`) ou por UH (`false`).")
    val perPerson: Boolean?,
) {
    companion object {
        fun of(derivation: RatePlanDerivation) =
            RatePlanDerivationDto(
                derivation.basePlanId.value,
                derivation.type,
                derivation.percent,
                derivation.amount,
                derivation.perPerson,
            )
    }
}

@Schema(
    description =
        "A derivação de um plano (RF-TAR-03): `PERCENT` com `percent` (acima de -100; negativo = desconto) ou " +
            "`AMOUNT` com `amount` (de -1.000.000,00 a 1.000.000,00) e `perPerson` (por pessoa: uma vez por pessoa " +
            "que o valor cobre; por UH: só nas diárias de 1 e 2 adultos). Um valor calculado igual ou abaixo de zero " +
            "deixa a ocupação sem preço naquela data (não vendável, sinalizada em `unpricedByDerivation` na leitura).",
)
data class RatePlanDerivationRequest(
    @field:NotNull
    val basePlanId: UUID?,
    @field:NotNull
    val type: RateAdjustmentType?,
    @field:Schema(nullable = true)
    val percent: Percent? = null,
    @field:Schema(nullable = true)
    val amount: Money? = null,
    @field:Schema(nullable = true)
    val perPerson: Boolean? = null,
) {
    fun toCommand() =
        DerivationCommand(RatePlanId(checkNotNull(basePlanId)), checkNotNull(type), percent, amount, perPerson)
}

/**
 * Preços por data e ocupação (RF-TAR-02 a 04) e derivação (RF-TAR-03) de um plano tarifário. Consultar exige
 * `rates.plans.read`; gravar, `rates.prices.manage`, e alterar a tarifa publicada, `rates.prices.override` (sensível).
 */
@ApiV1Controller
@RequestMapping("/rate-plans/{id}")
@Tag(name = PRICES_TAG, description = PRICES_TAG_DESCRIPTION)
class RatePlanPricesController(
    private val prices: RatePriceService,
    private val derivations: RatePlanDerivationService,
) {
    @GetMapping("/prices")
    @Operation(
        operationId = "listRatePlanPrices",
        summary = "Consultar os preços de um plano num período",
        description =
            "Um período de até 366 dias (fim antes do início: 400 `RATE_PRICE_PERIOD_INVALID`; mais: 400 " +
                "`RATE_PRICE_PERIOD_TOO_LONG`). Num plano derivado, os preços são os do base com a regra aplicada " +
                "na leitura; a ocupação cujo ajuste deu zero ou menos fica sem preço e vem em " +
                "`unpricedByDerivation`. Os preços de criança gravados em faixas que o hotel não usa mais vêm em " +
                "`obsoleteChildren` e não valem. Exige `rates.plans.read`.",
    )
    fun list(
        @PathVariable id: UUID,
        @RequestParam from: LocalDate,
        @RequestParam to: LocalDate,
        @Parameter(description = "Só este tipo de UH.")
        @RequestParam(required = false) roomTypeId: UUID?,
    ): RatePricesResponse = RatePricesResponse.of(prices.read(RatePlanId(id), roomTypeId?.let(::RoomTypeId), from, to))

    @PostMapping("/prices/bulk-update")
    @Operation(
        operationId = "updateRatePlanPrices",
        summary = "Gravar preços em massa",
        description =
            "Grava os mesmos valores em todas as linhas selecionadas (regravar uma data troca todas as faixas de " +
                "criança dela). $SELECTION_RULES Criança fora das faixas atuais do hotel, ou repetida: 400 " +
                "`RATE_PRICE_CHILD_BANDS_INVALID`; valor fora da faixa: 400 `RATE_PRICE_INVALID`. $PUBLISHED_RULE",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
        @Valid @RequestBody request: RatePriceUpdateRequest,
    ): RatePriceChangeResponse =
        RatePriceChangeResponse.of(
            prices.update(
                RatePlanId(id),
                request.toTarget(),
                checkNotNull(request.prices).toInput(),
                approvalToken,
            ),
        )

    @PostMapping("/prices/bulk-remove")
    @Operation(
        operationId = "removeRatePlanPrices",
        summary = "Retirar preços em massa",
        description = "As linhas selecionadas ficam sem preço. $SELECTION_RULES $PUBLISHED_RULE",
    )
    fun remove(
        @PathVariable id: UUID,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
        @Valid @RequestBody request: RatePriceRemovalRequest,
    ): RatePriceChangeResponse =
        RatePriceChangeResponse.of(prices.remove(RatePlanId(id), request.toTarget(), approvalToken))

    @PutMapping("/derivation")
    @Operation(
        operationId = "setRatePlanDerivation",
        summary = "Definir a derivação de um plano",
        description =
            "O plano passa a derivar do `basePlanId`; os preços dele são calculados na leitura. Exige " +
                "`rates.prices.manage` e o `If-Match` do plano. Base inexistente: 400 `UNKNOWN_RATE_PLAN`; o próprio " +
                "plano ou um que deriva dele: 422 `RATE_PLAN_DERIVATION_CYCLE`; mais de 5 níveis: 422 " +
                "`RATE_PLAN_DERIVATION_TOO_DEEP`; plano com preços próprios a partir da data operacional: 422 " +
                "`RATE_PLAN_HAS_PRICES`; regra incoerente: 400 `RATE_PLAN_DERIVATION_INVALID`. Trocar a regra de " +
                "um plano publicado (ativo ou base de um ativo) é ação sensível (`rates.prices.override`).",
    )
    fun derive(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
        @Valid @RequestBody request: RatePlanDerivationRequest,
    ): ResponseEntity<RatePlanResponse> =
        plan(derivations.derive(RatePlanId(id), ETags.expectedVersion(ifMatch), request.toCommand(), approvalToken))

    @PostMapping("/derivation/remove")
    @Operation(
        operationId = "removeRatePlanDerivation",
        summary = "Retirar a derivação de um plano",
        description =
            "O plano deixa de derivar e fica sem preços até ganhar os próprios. Exige `rates.prices.manage` e o " +
                "`If-Match` do plano; num plano publicado, é ação sensível (`rates.prices.override`). Sem derivação, " +
                "nada muda.",
    )
    fun removeDerivation(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
    ): ResponseEntity<RatePlanResponse> =
        plan(derivations.removeDerivation(RatePlanId(id), ETags.expectedVersion(ifMatch), approvalToken))

    private fun plan(view: RatePlanView) =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(RatePlanResponse.of(view))
}

// --- Temporadas ----------------------------------------------------------------------------------------------------

@Schema(description = "Uma temporada nomeada: atalho de período (até 366 dias) para a edição em massa de preços.")
data class SeasonRequest(
    @field:Schema(example = "Alta temporada 2026/27")
    @field:NotBlank
    @field:Size(max = 80)
    val name: String?,
    @field:NotNull
    val startsOn: LocalDate?,
    @field:NotNull
    val endsOn: LocalDate?,
) {
    fun toCommand() = SeasonCommand(checkNotNull(name), checkNotNull(startsOn), checkNotNull(endsOn))
}

@Schema(description = "Uma temporada.")
data class SeasonResponse(
    val id: UUID,
    val name: String,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: SeasonView) =
            SeasonResponse(
                view.id.value,
                view.name,
                view.startsOn,
                view.endsOn,
                view.active,
                view.createdAt,
                view.updatedAt,
            )
    }
}

/**
 * Temporadas (RF-TAR-04): consultar exige `rates.plans.read`; escrever, `rates.prices.manage`. Nunca são apagadas, só
 * desativadas.
 */
@ApiV1Controller
@RequestMapping("/seasons")
@Tag(name = PRICES_TAG, description = PRICES_TAG_DESCRIPTION)
class SeasonsController(
    private val seasons: SeasonService,
) {
    @GetMapping
    @Operation(
        operationId = "listSeasons",
        summary = "Listar temporadas",
        description = "Em ordem de início. Exige `rates.plans.read`.",
    )
    fun list(
        @Parameter(description = "Só as ativas (`true`) ou só as inativas (`false`); omita para todas.")
        @RequestParam(required = false) active: Boolean?,
        @Valid page: PageParams,
    ): CursorPage<SeasonResponse> = seasons.list(active, page).map(SeasonResponse::of)

    @PostMapping
    @Operation(
        operationId = "createSeason",
        summary = "Cadastrar uma temporada",
        description = "Exige `rates.prices.manage`. $SEASON_RULES",
    )
    @ApiResponse(responseCode = "201", description = "Temporada cadastrada.")
    fun create(
        @Valid @RequestBody request: SeasonRequest,
    ): ResponseEntity<SeasonResponse> {
        val view = seasons.create(request.toCommand())
        return ResponseEntity
            .created(URI.create("/api/v1/seasons/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(SeasonResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getSeason",
        summary = "Consultar uma temporada",
        description = "Exige `rates.plans.read`. Inexistente: 404 `SEASON_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<SeasonResponse> = ok(seasons.get(SeasonId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateSeason",
        summary = "Editar uma temporada",
        description =
            "Exige `rates.prices.manage` e o `If-Match` (ausente: 428; versão diferente: 412). Os preços já " +
                "gravados com ela não mudam. $SEASON_RULES",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: SeasonRequest,
    ): ResponseEntity<SeasonResponse> =
        ok(seasons.update(SeasonId(id), ETags.expectedVersion(ifMatch), request.toCommand()))

    @PostMapping("/{id}/deactivate")
    @Operation(
        operationId = "deactivateSeason",
        summary = "Desativar uma temporada",
        description = "Exige `rates.prices.manage`. Nada é apagado; a desativada não serve à edição em massa.",
    )
    fun deactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<SeasonResponse> = ok(seasons.deactivate(SeasonId(id)))

    @PostMapping("/{id}/reactivate")
    @Operation(
        operationId = "reactivateSeason",
        summary = "Reativar uma temporada",
        description = "Exige `rates.prices.manage`.",
    )
    fun reactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<SeasonResponse> = ok(seasons.reactivate(SeasonId(id)))

    private fun ok(view: SeasonView) = ResponseEntity.ok().eTag(ETags.of(view.version)).body(SeasonResponse.of(view))

    private companion object {
        const val SEASON_RULES =
            "Nome repetido (sem diferenciar maiúsculas, inclusive de uma desativada): 409 " +
                "`SEASON_NAME_ALREADY_IN_USE`; fim antes do início ou mais de 366 dias: 400 `SEASON_INVALID` " +
                "(campo `endsOn`)."
    }
}
