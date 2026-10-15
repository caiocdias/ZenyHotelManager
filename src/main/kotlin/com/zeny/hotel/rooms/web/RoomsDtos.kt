package com.zeny.hotel.rooms.web

import com.zeny.hotel.rooms.AreaKind
import com.zeny.hotel.rooms.Occupancy
import com.zeny.hotel.rooms.RoomFeatureId
import com.zeny.hotel.rooms.RoomFeatureKey
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.application.AreaCommand
import com.zeny.hotel.rooms.application.AreaView
import com.zeny.hotel.rooms.application.RoomCommand
import com.zeny.hotel.rooms.application.RoomFeatureView
import com.zeny.hotel.rooms.application.RoomTypeCommand
import com.zeny.hotel.rooms.application.RoomTypeView
import com.zeny.hotel.rooms.application.RoomView
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

private const val CODE_PATTERN = "[A-Za-z0-9][A-Za-z0-9_-]{0,19}"
private const val NUMBER_PATTERN = "[0-9A-Za-z][0-9A-Za-z-]{0,9}"
private const val MAX_GUESTS = 20L
private const val MAX_DISPLAY_ORDER = 9999L
private const val MAX_FEATURES = 50

private fun Collection<RoomFeatureId>.sortedValues(): List<UUID> = map { it.value }.sorted()

@Schema(description = "Código e nome de uma característica.")
data class RoomFeatureRequest(
    @field:Schema(description = "Letras, dígitos, `_` e `-`, até 20; guardado em maiúsculas.", example = "SEA_VIEW")
    @field:NotBlank
    @field:Pattern(regexp = CODE_PATTERN)
    val code: String,
    @field:Schema(example = "Vista para o mar")
    @field:NotBlank
    @field:Size(max = 80)
    val name: String,
)

@Schema(description = "Uma característica de tipo de UH ou de UH.")
data class RoomFeatureResponse(
    val id: UUID,
    @field:Schema(
        description =
            "Chave estável das características pré-carregadas (vista, acessível, conjugada); " +
                "nula nas criadas pela API. Não muda quando o código ou o nome mudam.",
        nullable = true,
    )
    val key: RoomFeatureKey?,
    val code: String,
    val name: String,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: RoomFeatureView) =
            RoomFeatureResponse(
                view.id.value,
                view.key,
                view.code,
                view.name,
                view.active,
                view.createdAt,
                view.updatedAt,
            )
    }
}

@Schema(description = "Dados de um tipo de UH.")
data class RoomTypeRequest(
    @field:Schema(description = "Letras, dígitos, `_` e `-`, até 20; guardado em maiúsculas.", example = "STD")
    @field:NotBlank
    @field:Pattern(regexp = CODE_PATTERN)
    val code: String,
    @field:Schema(example = "Standard casal")
    @field:NotBlank
    @field:Size(max = 80)
    val name: String,
    @field:Schema(nullable = true)
    @field:Size(max = 1000)
    val description: String? = null,
    @field:Schema(description = "Adultos na ocupação padrão (ao menos 1).")
    @field:Min(1)
    @field:Max(MAX_GUESTS)
    val standardAdults: Int,
    @field:Schema(description = "Crianças na ocupação padrão.")
    @field:Min(0)
    @field:Max(MAX_GUESTS)
    val standardChildren: Int,
    @field:Schema(description = "Máximo de adultos; nunca menor que a ocupação padrão.")
    @field:Min(1)
    @field:Max(MAX_GUESTS)
    val maxAdults: Int,
    @field:Schema(description = "Máximo de crianças; nunca menor que a ocupação padrão.")
    @field:Min(0)
    @field:Max(MAX_GUESTS)
    val maxChildren: Int,
    @field:Schema(description = "Configuração de camas.", nullable = true, example = "1 casal + 1 solteiro")
    @field:Size(max = 200)
    val bedSetup: String? = null,
    @field:Schema(description = "Características do tipo (o conjunto inteiro).")
    @field:Size(max = MAX_FEATURES)
    val featureIds: List<UUID> = emptyList(),
    @field:Schema(description = "Ordem de exibição (menor primeiro).")
    @field:Min(0)
    @field:Max(MAX_DISPLAY_ORDER)
    val displayOrder: Int = 0,
) {
    fun toCommand() =
        RoomTypeCommand(
            code = code,
            name = name,
            description = description,
            occupancy = Occupancy(standardAdults, standardChildren, maxAdults, maxChildren),
            bedSetup = bedSetup,
            displayOrder = displayOrder,
            featureIds = featureIds.map(::RoomFeatureId).toSet(),
        )
}

@Schema(description = "Um tipo de UH.")
data class RoomTypeResponse(
    val id: UUID,
    val code: String,
    val name: String,
    @field:Schema(nullable = true)
    val description: String?,
    val standardAdults: Int,
    val standardChildren: Int,
    val maxAdults: Int,
    val maxChildren: Int,
    @field:Schema(nullable = true)
    val bedSetup: String?,
    val displayOrder: Int,
    @field:Schema(description = "Características do tipo, em ordem de id.")
    val featureIds: List<UUID>,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: RoomTypeView) =
            RoomTypeResponse(
                id = view.id.value,
                code = view.code,
                name = view.name,
                description = view.description,
                standardAdults = view.occupancy.standardAdults,
                standardChildren = view.occupancy.standardChildren,
                maxAdults = view.occupancy.maxAdults,
                maxChildren = view.occupancy.maxChildren,
                bedSetup = view.bedSetup,
                displayOrder = view.displayOrder,
                featureIds = view.featureIds.sortedValues(),
                active = view.active,
                createdAt = view.createdAt,
                updatedAt = view.updatedAt,
            )
    }
}

@Schema(description = "Dados de uma UH.")
data class RoomRequest(
    @field:Schema(
        description = "Número único, sem diferenciar maiúsculas: letras, dígitos e `-`, até 10.",
        example = "101",
    )
    @field:NotBlank
    @field:Pattern(regexp = NUMBER_PATTERN)
    val number: String,
    @field:Schema(description = "Andar ou ala.", nullable = true, example = "1º andar")
    @field:Size(max = 40)
    val floor: String? = null,
    @field:Schema(description = "Tipo da UH.")
    @field:NotNull
    val roomTypeId: UUID,
    @field:Schema(description = "Características da UH (vista, acessível, conjugada…; o conjunto inteiro).")
    @field:Size(max = MAX_FEATURES)
    val featureIds: List<UUID> = emptyList(),
    @field:Schema(description = "Observações.", nullable = true)
    @field:Size(max = 1000)
    val notes: String? = null,
) {
    fun toCommand() =
        RoomCommand(
            number = number,
            floor = floor,
            roomTypeId = RoomTypeId(roomTypeId),
            featureIds = featureIds.map(::RoomFeatureId).toSet(),
            notes = notes,
        )
}

@Schema(description = "Uma UH.")
data class RoomResponse(
    val id: UUID,
    val number: String,
    @field:Schema(description = "Andar ou ala.", nullable = true)
    val floor: String?,
    val roomTypeId: UUID,
    @field:Schema(description = "Características da UH, em ordem de id.")
    val featureIds: List<UUID>,
    @field:Schema(nullable = true)
    val notes: String?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: RoomView) =
            RoomResponse(
                id = view.id.value,
                number = view.number,
                floor = view.floor,
                roomTypeId = view.roomTypeId.value,
                featureIds = view.featureIds.sortedValues(),
                notes = view.notes,
                active = view.active,
                createdAt = view.createdAt,
                updatedAt = view.updatedAt,
            )
    }
}

@Schema(description = "Dados de uma área comum.")
data class AreaRequest(
    @field:Schema(example = "Piscina")
    @field:NotBlank
    @field:Size(max = 80)
    val name: String,
    @field:NotNull
    val kind: AreaKind,
    @field:Schema(nullable = true)
    @field:Size(max = 500)
    val description: String? = null,
) {
    fun toCommand() = AreaCommand(name, kind, description)
}

@Schema(description = "Uma área comum.")
data class AreaResponse(
    val id: UUID,
    val name: String,
    val kind: AreaKind,
    @field:Schema(nullable = true)
    val description: String?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: AreaView) =
            AreaResponse(
                view.id.value,
                view.name,
                view.kind,
                view.description,
                view.active,
                view.createdAt,
                view.updatedAt,
            )
    }
}
