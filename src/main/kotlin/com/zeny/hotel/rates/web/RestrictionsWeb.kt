package com.zeny.hotel.rates.web

import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.RestrictionField
import com.zeny.hotel.rates.SeasonId
import com.zeny.hotel.rates.application.DateTarget
import com.zeny.hotel.rates.application.EffectiveRestrictionView
import com.zeny.hotel.rates.application.RestrictionChangeView
import com.zeny.hotel.rates.application.RestrictionCommand
import com.zeny.hotel.rates.application.RestrictionData
import com.zeny.hotel.rates.application.RestrictionService
import com.zeny.hotel.rates.application.RestrictionsView
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.ApiV1Controller
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

private const val RESTRICTIONS_TAG = "rates"
private const val RESTRICTIONS_TAG_DESCRIPTION = "Planos tarifários, políticas de cancelamento e de garantia e pacotes."
private const val MAX_ROOM_TYPES = 50
private const val SEMANTICS =
    "Semântica (avaliada na cotação e na reserva): `closed` fecha a venda de **qualquer noite** da estadia; " +
        "`closedToArrival` (CTA) impede **chegar** nesta data e `closedToDeparture` (CTD) impede **sair** nesta " +
        "data (só a data exata; as noites do meio não importam); `minStay`/`maxStay` (noites) e " +
        "`minAdvanceDays`/`maxAdvanceDays` (dias da data operacional até a chegada) valem os da **data de " +
        "chegada**. Precedência: a restrição do tipo de UH substitui a geral do plano **campo a campo** (o campo " +
        "não informado herda o da geral; `false` num booleano do tipo abre uma exceção sobre a geral `true`)."

@Schema(
    description =
        "Os campos de uma restrição; nulo = não informado. Estadia de 1 a 365 noites; antecedência de 0 a 730 dias.",
)
data class RestrictionValuesDto(
    @field:Schema(nullable = true, description = "Fechado para venda.")
    val closed: Boolean? = null,
    @field:Schema(nullable = true, description = "CTA: fechado para chegada nesta data.")
    val closedToArrival: Boolean? = null,
    @field:Schema(nullable = true, description = "CTD: fechado para saída nesta data.")
    val closedToDeparture: Boolean? = null,
    @field:Schema(nullable = true, description = "Estadia mínima (noites), para chegadas nesta data.")
    val minStay: Int? = null,
    @field:Schema(nullable = true, description = "Estadia máxima (noites), para chegadas nesta data.")
    val maxStay: Int? = null,
    @field:Schema(nullable = true, description = "Antecedência mínima (dias) para chegar nesta data.")
    val minAdvanceDays: Int? = null,
    @field:Schema(nullable = true, description = "Antecedência máxima (dias) para chegar nesta data.")
    val maxAdvanceDays: Int? = null,
) {
    fun toData() =
        RestrictionData(closed, closedToArrival, closedToDeparture, minStay, maxStay, minAdvanceDays, maxAdvanceDays)

    companion object {
        fun of(data: RestrictionData) =
            RestrictionValuesDto(
                data.closed,
                data.closedToArrival,
                data.closedToDeparture,
                data.minStay,
                data.maxStay,
                data.minAdvanceDays,
                data.maxAdvanceDays,
            )
    }
}

@Schema(
    description =
        "Edição em massa de restrições (RF-TAR-05): os campos de `set` passam a valer e os de `clear` voltam a " +
            "\"não informado\" em todas as linhas selecionadas; os demais campos de cada linha ficam como estão.",
)
data class RestrictionUpdateRequest(
    @field:Schema(description = "Tipos de UH do plano (até 50); vazio = a restrição geral do plano (todos os tipos).")
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
    @field:Schema(description = "Os campos a gravar (os nulos não mudam).")
    val set: RestrictionValuesDto = RestrictionValuesDto(),
    @field:Schema(description = "Os campos a retirar (voltam a não informado; a linha sem nenhum campo sai).")
    @field:Size(max = 7)
    val clear: List<RestrictionField> = emptyList(),
) {
    fun toCommand() =
        RestrictionCommand(
            roomTypeIds.map(::RoomTypeId).toSet(),
            DateTarget(from, to, seasonId?.let(::SeasonId), weekdays.toSet()),
            set.toData(),
            clear.toSet(),
        )
}

@Schema(description = "O resultado de uma edição de restrições.")
data class RestrictionChangeResponse(
    val ratePlanId: UUID,
    @field:Schema(description = "Se a edição foi na restrição geral do plano.")
    val general: Boolean,
    @field:Schema(description = "Os tipos de UH afetados (na geral, todos os do plano); vazio se nada mudou.")
    val roomTypeIds: List<UUID>,
    @field:Schema(description = "Datas selecionadas (do período, nos dias da semana pedidos).")
    val matchedDates: Int,
    @field:Schema(description = "Linhas (tipo de UH ou geral × data) novas.")
    val created: Int,
    @field:Schema(description = "Linhas que já existiam e mudaram.")
    val changed: Int,
    @field:Schema(description = "Linhas que ficaram sem nenhum campo e saíram.")
    val removed: Int,
    @field:Schema(description = "Os campos que mudaram em alguma linha.")
    val fields: List<RestrictionField>,
    @field:Schema(nullable = true, description = "Primeira data mudada; nula se nada mudou.")
    val from: LocalDate?,
    @field:Schema(nullable = true, description = "Última data mudada; nula se nada mudou.")
    val to: LocalDate?,
) {
    companion object {
        fun of(view: RestrictionChangeView) =
            RestrictionChangeResponse(
                view.ratePlanId.value,
                view.general,
                view.roomTypeIds.map { it.value },
                view.matchedDates,
                view.created,
                view.changed,
                view.removed,
                view.fields,
                view.from,
                view.to,
            )
    }
}

@Schema(
    description =
        "A restrição efetiva de um tipo de UH numa data: a do tipo sobre a geral do plano, campo a campo; o que " +
            "nenhuma informa não restringe (booleanos falsos, mínimos e máximos nulos).",
)
data class EffectiveRestrictionResponse(
    val closed: Boolean,
    val closedToArrival: Boolean,
    val closedToDeparture: Boolean,
    @field:Schema(nullable = true)
    val minStay: Int?,
    @field:Schema(nullable = true)
    val maxStay: Int?,
    @field:Schema(nullable = true)
    val minAdvanceDays: Int?,
    @field:Schema(nullable = true)
    val maxAdvanceDays: Int?,
    @field:Schema(
        description = "Os campos que vieram da restrição do tipo de UH (os demais vêm da geral, ou de nenhuma).",
    )
    val fromRoomType: List<RestrictionField>,
) {
    companion object {
        fun of(view: EffectiveRestrictionView) =
            EffectiveRestrictionResponse(
                view.closed,
                view.closedToArrival,
                view.closedToDeparture,
                view.minStay,
                view.maxStay,
                view.minAdvanceDays,
                view.maxAdvanceDays,
                view.fromRoomType,
            )
    }
}

@Schema(description = "Um tipo de UH numa data: a restrição gravada do tipo e a efetiva.")
data class RoomTypeRestrictionResponse(
    val roomTypeId: UUID,
    @field:Schema(nullable = true, description = "A restrição gravada do tipo; nula se não há.")
    val specific: RestrictionValuesDto?,
    val effective: EffectiveRestrictionResponse,
)

@Schema(description = "Uma data com alguma restrição gravada.")
data class RestrictionDayResponse(
    val date: LocalDate,
    @field:Schema(nullable = true, description = "A restrição geral do plano; nula se não há.")
    val general: RestrictionValuesDto?,
    @field:Schema(description = "Cada tipo de UH do plano (ou o pedido), em ordem de id.")
    val roomTypes: List<RoomTypeRestrictionResponse>,
)

@Schema(description = "As restrições de um plano num período.")
data class RestrictionsResponse(
    val ratePlanId: UUID,
    val from: LocalDate,
    val to: LocalDate,
    @field:Schema(description = "Uma linha por data com alguma restrição gravada; as demais datas não têm restrição.")
    val items: List<RestrictionDayResponse>,
) {
    companion object {
        fun of(view: RestrictionsView) =
            RestrictionsResponse(
                view.ratePlanId.value,
                view.from,
                view.to,
                view.items.map { day ->
                    RestrictionDayResponse(
                        day.date,
                        day.general?.let(RestrictionValuesDto::of),
                        day.roomTypes.map {
                            RoomTypeRestrictionResponse(
                                it.roomTypeId.value,
                                it.specific?.let(RestrictionValuesDto::of),
                                EffectiveRestrictionResponse.of(it.effective),
                            )
                        },
                    )
                },
            )
    }
}

/**
 * Restrições de venda de um plano tarifário (RF-TAR-05): consultar exige `rates.plans.read`; gravar e retirar,
 * `rates.restrictions.manage` (não sensível).
 */
@ApiV1Controller
@RequestMapping("/rate-plans/{id}/restrictions")
@Tag(name = RESTRICTIONS_TAG, description = RESTRICTIONS_TAG_DESCRIPTION)
class RatePlanRestrictionsController(
    private val restrictions: RestrictionService,
) {
    @GetMapping
    @Operation(
        operationId = "listRatePlanRestrictions",
        summary = "Consultar as restrições de um plano num período",
        description =
            "Um período de até 366 dias, inclusive o passado (fim antes do início: 400 " +
                "`RESTRICTION_PERIOD_INVALID`; mais: 400 `RESTRICTION_PERIOD_TOO_LONG`). Por data com alguma " +
                "restrição gravada: a geral do plano e, por tipo de UH, a do tipo e a efetiva (com os campos que " +
                "vieram do tipo em `fromRoomType`). $SEMANTICS Exige `rates.plans.read`.",
    )
    fun list(
        @PathVariable id: UUID,
        @RequestParam from: LocalDate,
        @RequestParam to: LocalDate,
        @Parameter(description = "Só este tipo de UH.")
        @RequestParam(required = false) roomTypeId: UUID?,
    ): RestrictionsResponse =
        RestrictionsResponse.of(restrictions.read(RatePlanId(id), roomTypeId?.let(::RoomTypeId), from, to))

    @PostMapping("/bulk-update")
    @Operation(
        operationId = "updateRatePlanRestrictions",
        summary = "Gravar restrições em massa",
        description =
            "As linhas são a restrição geral do plano (`roomTypeIds` vazio) ou as dos tipos de UH pedidos (do plano; " +
                "senão 422 `ROOM_TYPE_NOT_IN_PLAN`) × as datas do período (`from` e `to`, até 366 dias, a partir da " +
                "data operacional) **ou** da temporada (`seasonId`, cortada na data operacional), só nos `weekdays` " +
                "(vazio = todos). Os campos de `set` passam a valer, os de `clear` voltam a não informado, os demais " +
                "ficam. Período e temporada juntos, ou nenhum: 400 `RESTRICTION_SELECTION_INVALID`; fim antes do " +
                "início ou nenhuma data nos dias da semana: 400 `RESTRICTION_PERIOD_INVALID`; mais de 366 dias: 400 " +
                "`RESTRICTION_PERIOD_TOO_LONG`; começo antes da data operacional: 422 `RESTRICTION_PERIOD_IN_PAST`; " +
                "temporada inexistente 400 `UNKNOWN_SEASON`, desativada 422 `SEASON_INACTIVE`. Valor fora da faixa " +
                "(inclusive negativo), campo em `set` e `clear` ou nada a mudar: 400 `RESTRICTION_INVALID`; mínimo " +
                "maior que o máximo no pedido: 400 `RESTRICTION_RANGE_INVALID`; com o que já está gravado (na mesma " +
                "linha ou na efetiva de um tipo): 422 `RESTRICTION_RANGE_CONFLICT`. $SEMANTICS Exige " +
                "`rates.restrictions.manage`; publica `RestrictionsChanged`.",
    )
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: RestrictionUpdateRequest,
    ): RestrictionChangeResponse =
        RestrictionChangeResponse.of(restrictions.update(RatePlanId(id), request.toCommand()))
}
