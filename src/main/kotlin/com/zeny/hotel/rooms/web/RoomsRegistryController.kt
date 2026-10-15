package com.zeny.hotel.rooms.web

import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.AreaKind
import com.zeny.hotel.rooms.RoomFeatureId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.application.AreaView
import com.zeny.hotel.rooms.application.RoomFeatureView
import com.zeny.hotel.rooms.application.RoomTypeView
import com.zeny.hotel.rooms.application.RoomView
import com.zeny.hotel.rooms.application.RoomsRegistryService
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

private const val ROOMS_TAG = "rooms"
private const val ROOMS_TAG_DESCRIPTION = "Tipos de UH, características, UHs e áreas comuns."
private const val ACTIVE_FILTER = "Só os ativos (`true`) ou só os inativos (`false`); omita para todos."
private const val IF_MATCH_RULE = "o `If-Match` (ausente: 428; versão diferente: 412)"

/**
 * Características de tipos de UH e de UHs (RF-UH-01 e 02): vista, acessível, conjugada… Consultar
 * exige `rooms.registry.read`; escrever, `rooms.registry.manage`. Nunca são apagadas, só desativadas.
 */
@ApiV1Controller
@RequestMapping("/room-features")
@Tag(name = ROOMS_TAG, description = ROOMS_TAG_DESCRIPTION)
class RoomFeaturesController(
    private val registry: RoomsRegistryService,
) {
    @GetMapping
    @Operation(
        operationId = "listRoomFeatures",
        summary = "Listar características",
        description = "Em ordem de código. Exige `rooms.registry.read`.",
    )
    fun list(
        @Parameter(description = ACTIVE_FILTER)
        @RequestParam(required = false) active: Boolean?,
        @Valid page: PageParams,
    ): CursorPage<RoomFeatureResponse> = registry.features(active, page).map(RoomFeatureResponse::of)

    @PostMapping
    @Operation(
        operationId = "createRoomFeature",
        summary = "Criar uma característica",
        description =
            "Exige `rooms.registry.manage`. O código fica em maiúsculas; já usado: 409 " +
                "`ROOM_FEATURE_CODE_ALREADY_IN_USE`.",
    )
    @ApiResponse(responseCode = "201", description = "Característica criada.")
    fun create(
        @Valid @RequestBody request: RoomFeatureRequest,
    ): ResponseEntity<RoomFeatureResponse> {
        val view = registry.createFeature(request.code, request.name)
        return ResponseEntity
            .created(URI.create("/api/v1/room-features/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(RoomFeatureResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getRoomFeature",
        summary = "Consultar uma característica",
        description = "Exige `rooms.registry.read`. Inexistente: 404 `ROOM_FEATURE_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<RoomFeatureResponse> = ok(registry.feature(RoomFeatureId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateRoomFeature",
        summary = "Editar uma característica",
        description =
            "Troca código e nome. Exige `rooms.registry.manage` e $IF_MATCH_RULE. Código já usado: 409 " +
                "`ROOM_FEATURE_CODE_ALREADY_IN_USE`.",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: RoomFeatureRequest,
    ): ResponseEntity<RoomFeatureResponse> =
        ok(registry.updateFeature(RoomFeatureId(id), ETags.expectedVersion(ifMatch), request.code, request.name))

    @PostMapping("/{id}/deactivate")
    @Operation(
        operationId = "deactivateRoomFeature",
        summary = "Desativar uma característica",
        description =
            "Exige `rooms.registry.manage`. Nada é apagado: os tipos e UHs que já a têm continuam com ela, mas ela " +
                "não é atribuída de novo (422 `ROOM_FEATURE_INACTIVE`). Desativar de novo não muda nada.",
    )
    fun deactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<RoomFeatureResponse> = ok(registry.deactivateFeature(RoomFeatureId(id)))

    @PostMapping("/{id}/reactivate")
    @Operation(
        operationId = "reactivateRoomFeature",
        summary = "Reativar uma característica",
        description = "Exige `rooms.registry.manage`.",
    )
    fun reactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<RoomFeatureResponse> = ok(registry.reactivateFeature(RoomFeatureId(id)))

    private fun ok(view: RoomFeatureView) =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(RoomFeatureResponse.of(view))
}

/**
 * Tipos de UH (RF-UH-01): código, nome, descrição, ocupação padrão e máxima, camas, características e
 * ordem de exibição. Consultar exige `rooms.registry.read`; escrever, `rooms.registry.manage`. Nunca
 * são apagados, só desativados (e só sem UHs ativas).
 */
@ApiV1Controller
@RequestMapping("/room-types")
@Tag(name = ROOMS_TAG, description = ROOMS_TAG_DESCRIPTION)
class RoomTypesController(
    private val registry: RoomsRegistryService,
) {
    @GetMapping
    @Operation(
        operationId = "listRoomTypes",
        summary = "Listar tipos de UH",
        description = "Em ordem de exibição. Exige `rooms.registry.read`.",
    )
    fun list(
        @Parameter(description = ACTIVE_FILTER)
        @RequestParam(required = false) active: Boolean?,
        @Valid page: PageParams,
    ): CursorPage<RoomTypeResponse> = registry.roomTypes(active, page).map(RoomTypeResponse::of)

    @PostMapping
    @Operation(
        operationId = "createRoomType",
        summary = "Criar um tipo de UH",
        description =
            "Exige `rooms.registry.manage`. O código fica em maiúsculas; já usado: 409 " +
                "`ROOM_TYPE_CODE_ALREADY_IN_USE`. Ocupação máxima menor que a padrão: 400 " +
                "`ROOM_TYPE_OCCUPANCY_INVALID`. Característica inexistente: 400 `UNKNOWN_ROOM_FEATURE`; desativada: " +
                "422 `ROOM_FEATURE_INACTIVE`.",
    )
    @ApiResponse(responseCode = "201", description = "Tipo de UH criado.")
    fun create(
        @Valid @RequestBody request: RoomTypeRequest,
    ): ResponseEntity<RoomTypeResponse> {
        val view = registry.createRoomType(request.toCommand())
        return ResponseEntity
            .created(URI.create("/api/v1/room-types/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(RoomTypeResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getRoomType",
        summary = "Consultar um tipo de UH",
        description = "Exige `rooms.registry.read`. Inexistente: 404 `ROOM_TYPE_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<RoomTypeResponse> = ok(registry.roomType(RoomTypeId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateRoomType",
        summary = "Editar um tipo de UH",
        description =
            "Troca todos os dados, inclusive o conjunto de características. Exige `rooms.registry.manage` e " +
                "$IF_MATCH_RULE. As regras do cadastro valem; só uma característica nova no tipo precisa estar ativa.",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: RoomTypeRequest,
    ): ResponseEntity<RoomTypeResponse> =
        ok(registry.updateRoomType(RoomTypeId(id), ETags.expectedVersion(ifMatch), request.toCommand()))

    @PostMapping("/{id}/deactivate")
    @Operation(
        operationId = "deactivateRoomType",
        summary = "Desativar um tipo de UH",
        description =
            "Exige `rooms.registry.manage`. Nada é apagado. Com UHs ativas: 422 `ROOM_TYPE_HAS_ACTIVE_ROOMS`. " +
                "Desativado, o tipo não recebe UH ativa (422 `ROOM_TYPE_INACTIVE`).",
    )
    fun deactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<RoomTypeResponse> = ok(registry.deactivateRoomType(RoomTypeId(id)))

    @PostMapping("/{id}/reactivate")
    @Operation(
        operationId = "reactivateRoomType",
        summary = "Reativar um tipo de UH",
        description = "Exige `rooms.registry.manage`.",
    )
    fun reactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<RoomTypeResponse> = ok(registry.reactivateRoomType(RoomTypeId(id)))

    private fun ok(view: RoomTypeView) =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(RoomTypeResponse.of(view))
}

/**
 * UHs (RF-UH-02): número único, andar ou ala, tipo, características, observações e ativa/inativa.
 * Consultar exige `rooms.registry.read`; escrever, `rooms.registry.manage`. UHs nunca são apagadas, só
 * desativadas.
 */
@ApiV1Controller
@RequestMapping("/rooms")
@Tag(name = ROOMS_TAG, description = ROOMS_TAG_DESCRIPTION)
class RoomsController(
    private val registry: RoomsRegistryService,
) {
    @GetMapping
    @Operation(
        operationId = "listRooms",
        summary = "Listar UHs",
        description =
            "Em ordem de número, opcionalmente de um tipo e só ativas ou inativas. Exige `rooms.registry.read`.",
    )
    fun list(
        @Parameter(description = "Só as UHs deste tipo.")
        @RequestParam(required = false) roomTypeId: UUID?,
        @Parameter(description = ACTIVE_FILTER)
        @RequestParam(required = false) active: Boolean?,
        @Valid page: PageParams,
    ): CursorPage<RoomResponse> = registry.rooms(roomTypeId?.let(::RoomTypeId), active, page).map(RoomResponse::of)

    @PostMapping
    @Operation(
        operationId = "createRoom",
        summary = "Cadastrar uma UH",
        description =
            "Exige `rooms.registry.manage`. Número já usado, sem diferenciar maiúsculas (inclusive por UH " +
                "desativada): 409 `ROOM_NUMBER_ALREADY_IN_USE`. Tipo inexistente: 400 `UNKNOWN_ROOM_TYPE`; " +
                "desativado: 422 `ROOM_TYPE_INACTIVE`. Característica inexistente: 400 `UNKNOWN_ROOM_FEATURE`; " +
                "desativada: 422 `ROOM_FEATURE_INACTIVE`.",
    )
    @ApiResponse(responseCode = "201", description = "UH cadastrada.")
    fun create(
        @Valid @RequestBody request: RoomRequest,
    ): ResponseEntity<RoomResponse> {
        val view = registry.createRoom(request.toCommand())
        return ResponseEntity
            .created(URI.create("/api/v1/rooms/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(RoomResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getRoom",
        summary = "Consultar uma UH",
        description = "Exige `rooms.registry.read`. Inexistente: 404 `ROOM_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<RoomResponse> = ok(registry.room(RoomId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateRoom",
        summary = "Editar uma UH",
        description =
            "Troca todos os dados, inclusive o número, o tipo e o conjunto de características. Exige " +
                "`rooms.registry.manage` e $IF_MATCH_RULE. As regras do cadastro valem; só um tipo ou uma " +
                "característica nova na UH precisa estar ativa.",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: RoomRequest,
    ): ResponseEntity<RoomResponse> =
        ok(registry.updateRoom(RoomId(id), ETags.expectedVersion(ifMatch), request.toCommand()))

    @PostMapping("/{id}/deactivate")
    @Operation(
        operationId = "deactivateRoom",
        summary = "Desativar uma UH",
        description =
            "Exige `rooms.registry.manage`. Nada é apagado: o cadastro, o número e o histórico ficam, e a UH sai " +
                "das listas de UHs ativas. Desativar de novo não muda nada.",
    )
    fun deactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<RoomResponse> = ok(registry.deactivateRoom(RoomId(id)))

    @PostMapping("/{id}/reactivate")
    @Operation(
        operationId = "reactivateRoom",
        summary = "Reativar uma UH",
        description = "Exige `rooms.registry.manage` e o tipo da UH ativo (422 `ROOM_TYPE_INACTIVE`).",
    )
    fun reactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<RoomResponse> = ok(registry.reactivateRoom(RoomId(id)))

    private fun ok(view: RoomView) = ResponseEntity.ok().eTag(ETags.of(view.version)).body(RoomResponse.of(view))
}

/**
 * Áreas comuns (RF-UH-03): lobby, piscina, cozinha, salão, corredor ou outra, locais de checklists,
 * tarefas e manutenção. Consultar exige `rooms.registry.read`; escrever, `rooms.registry.manage`. Nunca
 * são apagadas, só desativadas.
 */
@ApiV1Controller
@RequestMapping("/areas")
@Tag(name = ROOMS_TAG, description = ROOMS_TAG_DESCRIPTION)
class AreasController(
    private val registry: RoomsRegistryService,
) {
    @GetMapping
    @Operation(
        operationId = "listAreas",
        summary = "Listar áreas comuns",
        description =
            "Em ordem de nome, opcionalmente de um tipo e só ativas ou inativas. Exige `rooms.registry.read`.",
    )
    fun list(
        @Parameter(description = "Só as áreas deste tipo.")
        @RequestParam(required = false) kind: AreaKind?,
        @Parameter(description = ACTIVE_FILTER)
        @RequestParam(required = false) active: Boolean?,
        @Valid page: PageParams,
    ): CursorPage<AreaResponse> = registry.areas(kind, active, page).map(AreaResponse::of)

    @PostMapping
    @Operation(
        operationId = "createArea",
        summary = "Cadastrar uma área comum",
        description =
            "Exige `rooms.registry.manage`. Nome já usado, sem diferenciar maiúsculas: 409 `AREA_NAME_ALREADY_IN_USE`.",
    )
    @ApiResponse(responseCode = "201", description = "Área cadastrada.")
    fun create(
        @Valid @RequestBody request: AreaRequest,
    ): ResponseEntity<AreaResponse> {
        val view = registry.createArea(request.toCommand())
        return ResponseEntity
            .created(URI.create("/api/v1/areas/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(AreaResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getArea",
        summary = "Consultar uma área comum",
        description = "Exige `rooms.registry.read`. Inexistente: 404 `AREA_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<AreaResponse> = ok(registry.area(AreaId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateArea",
        summary = "Editar uma área comum",
        description =
            "Troca nome, tipo e descrição. Exige `rooms.registry.manage` e $IF_MATCH_RULE. Nome já usado: 409 " +
                "`AREA_NAME_ALREADY_IN_USE`.",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: AreaRequest,
    ): ResponseEntity<AreaResponse> =
        ok(registry.updateArea(AreaId(id), ETags.expectedVersion(ifMatch), request.toCommand()))

    @PostMapping("/{id}/deactivate")
    @Operation(
        operationId = "deactivateArea",
        summary = "Desativar uma área comum",
        description = "Exige `rooms.registry.manage`. Nada é apagado. Desativar de novo não muda nada.",
    )
    fun deactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<AreaResponse> = ok(registry.deactivateArea(AreaId(id)))

    @PostMapping("/{id}/reactivate")
    @Operation(
        operationId = "reactivateArea",
        summary = "Reativar uma área comum",
        description = "Exige `rooms.registry.manage`.",
    )
    fun reactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<AreaResponse> = ok(registry.reactivateArea(AreaId(id)))

    private fun ok(view: AreaView) = ResponseEntity.ok().eTag(ETags.of(view.version)).body(AreaResponse.of(view))
}
