package com.zeny.hotel.reservations.web

import com.zeny.hotel.reservations.InventoryDay
import com.zeny.hotel.reservations.InventoryRecount
import com.zeny.hotel.reservations.application.InventoryService
import com.zeny.hotel.reservations.application.OverbookingLimitChange
import com.zeny.hotel.reservations.application.OverbookingLimitCommand
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.ApiV1Controller
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

internal const val RESERVATIONS_TAG = "reservations"
internal const val RESERVATIONS_TAG_DESCRIPTION =
    "Inventário, disponibilidade em calendário e cotação por tipo de UH e data; reservas agrupadoras e reservas; " +
        "alocação de UH."
internal const val INVENTORY_COUNTERS =
    "Disponível = `total` (UHs ativas do tipo) − `sold` (reservas) − `blocked` (bloqueios de grupo) − `outOfOrder` " +
        "(UHs em OOO; o OOS não conta) + `overbookingLimit` (nasce zero: overbooking proibido, Q-09); pode ser " +
        "negativo (data sobrevendida). O inventário é mantido da data operacional até 730 dias à frente."

@Schema(description = "Os contadores de um tipo de UH numa data. $INVENTORY_COUNTERS")
data class InventoryDayResponse(
    val roomTypeId: UUID,
    val date: LocalDate,
    val total: Int,
    val sold: Int,
    val blocked: Int,
    val outOfOrder: Int,
    val overbookingLimit: Int,
    val available: Int,
) {
    companion object {
        fun of(day: InventoryDay) =
            InventoryDayResponse(
                day.roomTypeId.value,
                day.date,
                day.total,
                day.sold,
                day.blocked,
                day.outOfOrder,
                day.overbookingLimit,
                day.available,
            )
    }
}

@Schema(description = "O inventário de um tipo de UH num período.")
data class InventoryResponse(
    @field:Schema(description = "Uma linha por data mantida, em ordem; as datas fora do inventário não aparecem.")
    val items: List<InventoryDayResponse>,
)

@Schema(
    description =
        "O limite de overbooking (RF-TAR-09) dos tipos de UH nas datas do período (até 366 dias, a partir da data " +
            "operacional), só nos dias da semana pedidos (vazio = todos).",
)
data class OverbookingLimitRequest(
    @field:Schema(description = "Os tipos de UH (de 1 a 50, sem repetir).")
    @field:Size(max = 50)
    val roomTypeIds: List<UUID> = emptyList(),
    @field:NotNull
    val from: LocalDate?,
    @field:NotNull
    @field:Schema(description = "Fim do período, inclusive.")
    val to: LocalDate?,
    @field:Size(max = 7)
    val weekdays: List<DayOfWeek> = emptyList(),
    @field:NotNull
    @field:Schema(description = "Unidades aceitas além do disponível, de 0 a 50 (0 = sem overbooking).")
    val limit: Int?,
) {
    fun toCommand() =
        OverbookingLimitCommand(
            roomTypeIds.map(::RoomTypeId),
            checkNotNull(from),
            checkNotNull(to),
            weekdays.toSet(),
            checkNotNull(limit),
        )
}

@Schema(description = "O resultado de uma mudança do limite de overbooking.")
data class OverbookingLimitResponse(
    @field:Schema(description = "Datas selecionadas (do período, nos dias da semana pedidos).")
    val matchedDates: Int,
    @field:Schema(description = "Linhas (tipo de UH × data) que mudaram.")
    val changedRows: Int,
    @field:Schema(nullable = true, description = "Primeira data mudada; nula se nada mudou.")
    val from: LocalDate?,
    @field:Schema(nullable = true, description = "Última data mudada; nula se nada mudou.")
    val to: LocalDate?,
) {
    companion object {
        fun of(change: OverbookingLimitChange) =
            OverbookingLimitResponse(change.matchedDates, change.changedRows, change.from, change.to)
    }
}

@Schema(
    description =
        "O período da recontagem: de `from` (padrão e mínimo: a data operacional) a `to` (padrão e máximo: o fim do " +
            "horizonte, 730 dias).",
)
data class InventoryRecountRequest(
    @field:Schema(nullable = true)
    val from: LocalDate? = null,
    @field:Schema(nullable = true)
    val to: LocalDate? = null,
)

@Schema(description = "O resultado de uma recontagem.")
data class InventoryRecountResponse(
    val from: LocalDate,
    val to: LocalDate,
    @field:Schema(description = "Tipos de UH recontados.")
    val roomTypes: Int,
    @field:Schema(description = "Linhas (tipo de UH × data) que mudaram.")
    val changedRows: Int,
) {
    companion object {
        fun of(recount: InventoryRecount) =
            InventoryRecountResponse(recount.from, recount.to, recount.roomTypes, recount.changedRows)
    }
}

/**
 * O inventário por tipo de UH e data (RF-TAR-08 e 09, M4 E05): consultar exige `reservations.availability.read`; o
 * limite de overbooking e a recontagem, `reservations.inventory.manage`.
 */
@ApiV1Controller
@RequestMapping("/room-type-inventory")
@Tag(name = RESERVATIONS_TAG, description = RESERVATIONS_TAG_DESCRIPTION)
class InventoryController(
    private val inventory: InventoryService,
) {
    @GetMapping
    @Operation(
        operationId = "listRoomTypeInventory",
        summary = "Consultar o inventário de um tipo de UH num período",
        description =
            "Um período de até 366 dias, inclusive o passado (fim antes do início: 400 `INVENTORY_PERIOD_INVALID`; " +
                "mais: 400 `INVENTORY_PERIOD_TOO_LONG`); tipo de UH inexistente: 400 `UNKNOWN_ROOM_TYPE`. " +
                "$INVENTORY_COUNTERS Exige `reservations.availability.read`.",
    )
    fun list(
        @RequestParam roomTypeId: UUID,
        @RequestParam from: LocalDate,
        @RequestParam to: LocalDate,
    ): InventoryResponse =
        InventoryResponse(inventory.read(RoomTypeId(roomTypeId), from, to).map(InventoryDayResponse::of))

    @PostMapping("/overbooking-limit")
    @Operation(
        operationId = "setRoomTypeOverbookingLimit",
        summary = "Definir o limite de overbooking por tipo de UH e data",
        description =
            "Grava o mesmo `limit` (de 0 a 50; fora: 400 `OVERBOOKING_LIMIT_INVALID`) nos tipos de UH pedidos (de 1 " +
                "a 50, sem repetir; senão 400 `OVERBOOKING_LIMIT_INVALID`; inexistente 400 `UNKNOWN_ROOM_TYPE`) × as " +
                "datas do período nos `weekdays`. Fim antes do início ou nenhuma data nos dias da semana: 400 " +
                "`INVENTORY_PERIOD_INVALID`; mais de 366 dias: 400 `INVENTORY_PERIOD_TOO_LONG`; começo antes da data " +
                "operacional: 422 `INVENTORY_PERIOD_IN_PAST`; data fora do inventário mantido: 422 " +
                "`INVENTORY_DATE_OUT_OF_HORIZON` (datas em `dates`). Baixar o limite não desfaz vendas. Exige " +
                "`reservations.inventory.manage`; publica `AvailabilityChanged` por tipo que mudou.",
    )
    fun setOverbookingLimit(
        @Valid @RequestBody request: OverbookingLimitRequest,
    ): OverbookingLimitResponse = OverbookingLimitResponse.of(inventory.setOverbookingLimit(request.toCommand()))

    @PostMapping("/recount")
    @Operation(
        operationId = "recountRoomTypeInventory",
        summary = "Recontar o inventário",
        description =
            "Refaz os contadores de todos os tipos de UH ativos (e dos que já têm inventário) no período: o total e " +
                "o OOO pelas UHs e períodos OOO do cadastro, e as vendidas e bloqueadas pelas reservas e bloqueios; " +
                "cria as datas que faltam no horizonte. Repara uma divergência (uma atualização perdida). Fim antes " +
                "do início: 400 `INVENTORY_PERIOD_INVALID`. Exige `reservations.inventory.manage`; publica " +
                "`AvailabilityChanged` por tipo que mudou.",
    )
    fun recount(
        @Valid @RequestBody request: InventoryRecountRequest,
    ): InventoryRecountResponse = InventoryRecountResponse.of(inventory.recount(request.from, request.to))
}
