package com.zeny.hotel.housekeeping.web

import com.zeny.hotel.housekeeping.LostAndFoundItemId
import com.zeny.hotel.housekeeping.LostAndFoundStatus
import com.zeny.hotel.housekeeping.OccurrenceSource
import com.zeny.hotel.housekeeping.application.HousekeepingLostAndFound
import com.zeny.hotel.housekeeping.application.LostAndFoundItemView
import com.zeny.hotel.housekeeping.application.LostAndFoundRegistration
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.PageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.net.URI
import java.time.Instant
import java.util.UUID

private const val READ_RULES =
    "Exige `housekeeping.lostfound.manage` (todos os itens) ou `housekeeping.occurrences.report` (com `OWN`, só os " +
        "registrados pelo usuário); sem nenhuma, 403 `PERMISSION_DENIED`. O `returnedTo` só vem para quem tem " +
        "`housekeeping.lostfound.manage`."
private const val ACTION_RULES =
    "Exige `housekeeping.lostfound.manage` (só `ALL`; 403 `PERMISSION_DENIED` antes de qualquer outra recusa, " +
        "inclusive para quem achou o item). Item inexistente: 404 `HOUSEKEEPING_LOST_FOUND_NOT_FOUND`. Com o " +
        "`If-Match` do item (412 com outra versão; 428 sem ele). Item fora de `STORED` (já devolvido, descartado ou " +
        "doado): 409 `HOUSEKEEPING_LOST_FOUND_TRANSITION_NOT_ALLOWED` (com `itemStatus` e `action`), sem mudança. " +
        "Responde o item com o `ETag` novo."
private const val EXPIRY_RULE =
    "Vencido: guardado e com a data local em que foi achado, no fuso do hotel, mais o prazo de guarda " +
        "(`lostAndFoundRetentionDays`) até a data operacional corrente."

// Os limites dos textos (os do domínio, que os confere), só para o contrato.
private const val MAX_DESCRIPTION = 500
private const val MAX_STORAGE_PLACE = 100
private const val MAX_RETURNED_TO = 200
private const val MAX_REASON = 500
private const val TEXT_WARNING = "**Não escreva dado de hóspede** (nome, documento, contato): a governança vê o item."

/**
 * Achados e perdidos (RF-GOV-08, M6 E06): o registro online, a consulta (com o escopo `OWN` da camareira e o filtro
 * `expired`) e os destinos como subrecursos de ação, com o `If-Match` do item (ARCHITECTURE §7). O instante é o do
 * relógio do servidor. Não há `DELETE`.
 */
@ApiV1Controller
@RequestMapping("/lost-and-found-items")
@Tag(name = HOUSEKEEPING_TAG, description = HOUSEKEEPING_TAG_DESCRIPTION)
class HousekeepingLostAndFoundController(
    private val lostAndFound: HousekeepingLostAndFound,
) {
    @GetMapping
    @Operation(
        operationId = "listLostAndFoundItems",
        summary = "Listar os achados e perdidos",
        description = "Do achado mais recente para o mais antigo. $READ_RULES $EXPIRY_RULE",
    )
    fun list(
        @Parameter(description = "`true`: só os vencidos; `false`: só os não vencidos; omita para todos.")
        @RequestParam(required = false) expired: Boolean?,
        @Valid page: PageParams,
    ): CursorPage<LostAndFoundItemResponse> = lostAndFound.list(expired, page).map(LostAndFoundItemResponse::of)

    @PostMapping
    @Operation(
        operationId = "registerLostAndFoundItem",
        summary = "Registrar um item achado",
        description =
            "Numa UH (`roomId`) ou numa área comum (`areaId`), exatamente um: os dois ou nenhum, 400 " +
                "`HOUSEKEEPING_OCCURRENCE_LOCATION_INVALID`; local inexistente, 400 " +
                "`HOUSEKEEPING_OCCURRENCE_UNKNOWN_LOCATION` no campo. O item nasce `STORED`, achado agora, com " +
                "origem `MANUAL` e quem registra em `reportedBy` (o dono no escopo `OWN`). Exige " +
                "`housekeeping.occurrences.report` (qualquer escopo; 403 `PERMISSION_DENIED` antes de qualquer outra " +
                "recusa). Texto fora do formato: 400 `HOUSEKEEPING_DATA_INVALID` no campo.",
    )
    @ApiResponse(responseCode = "201", description = "Item registrado.")
    fun register(
        @Valid @RequestBody request: LostAndFoundItemRequest,
    ): ResponseEntity<LostAndFoundItemResponse> {
        val view =
            lostAndFound.register(
                LostAndFoundRegistration(
                    request.roomId?.let(::RoomId),
                    request.areaId?.let(::AreaId),
                    request.description,
                    request.storagePlace,
                ),
            )
        return ResponseEntity
            .created(URI.create("/api/v1/lost-and-found-items/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(LostAndFoundItemResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getLostAndFoundItem",
        summary = "Consultar um item de achados e perdidos",
        description =
            "$READ_RULES Inexistente, ou de outro usuário com o escopo `OWN`: 404 " +
                "`HOUSEKEEPING_LOST_FOUND_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<LostAndFoundItemResponse> = respond(lostAndFound.get(LostAndFoundItemId(id)))

    @PostMapping("/{id}/return")
    @Operation(
        operationId = "returnLostAndFoundItem",
        summary = "Devolver um item achado",
        description =
            "Devolve o item guardado a qualquer tempo: grava a quem (`returnedTo`), quando e por quem, e o item vai " +
                "a `RETURNED`. $ACTION_RULES",
    )
    fun returnItem(
        @PathVariable id: UUID,
        @Valid @RequestBody request: LostAndFoundReturnRequest,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<LostAndFoundItemResponse> {
        val expected = ETags.expectedVersion(ifMatch)
        return respond(lostAndFound.returnItem(LostAndFoundItemId(id), request.returnedTo, expected))
    }

    @PostMapping("/{id}/discard")
    @Operation(
        operationId = "discardLostAndFoundItem",
        summary = "Descartar um item achado vencido",
        description =
            "Descarta o item guardado e vencido: grava o motivo, quando e por quem, e o item vai a `DISCARDED`. " +
                "Antes do fim do prazo: 409 `HOUSEKEEPING_LOST_FOUND_NOT_EXPIRED`, sem mudança. $EXPIRY_RULE " +
                "$ACTION_RULES",
    )
    fun discard(
        @PathVariable id: UUID,
        @Valid @RequestBody request: LostAndFoundDisposalRequest,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<LostAndFoundItemResponse> {
        val expected = ETags.expectedVersion(ifMatch)
        return respond(lostAndFound.discard(LostAndFoundItemId(id), request.reason, expected))
    }

    @PostMapping("/{id}/donate")
    @Operation(
        operationId = "donateLostAndFoundItem",
        summary = "Doar um item achado vencido",
        description =
            "Doa o item guardado e vencido: grava o motivo, quando e por quem, e o item vai a `DONATED`. Antes do " +
                "fim do prazo: 409 `HOUSEKEEPING_LOST_FOUND_NOT_EXPIRED`, sem mudança. $EXPIRY_RULE $ACTION_RULES",
    )
    fun donate(
        @PathVariable id: UUID,
        @Valid @RequestBody request: LostAndFoundDisposalRequest,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<LostAndFoundItemResponse> {
        val expected = ETags.expectedVersion(ifMatch)
        return respond(lostAndFound.donate(LostAndFoundItemId(id), request.reason, expected))
    }

    private fun respond(view: LostAndFoundItemView) =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(LostAndFoundItemResponse.of(view))
}

@Schema(description = "O registro de um item achado, numa UH ou numa área comum (exatamente um local).")
data class LostAndFoundItemRequest(
    @field:Schema(description = "A UH onde foi achado; informe esta ou `areaId`, uma só.", nullable = true)
    val roomId: UUID? = null,
    @field:Schema(description = "A área comum onde foi achado; informe esta ou `roomId`, uma só.", nullable = true)
    val areaId: UUID? = null,
    // Os limites são do domínio (em code points, depois de aparar); aqui só os documentam.
    @field:NotBlank
    @field:Schema(
        description = "O que foi achado, até 500 caracteres. $TEXT_WARNING",
        maxLength = MAX_DESCRIPTION,
    )
    val description: String?,
    @field:Schema(
        description = "Onde o item fica guardado, até 100 caracteres; omita se ainda não foi guardado.",
        nullable = true,
        maxLength = MAX_STORAGE_PLACE,
    )
    val storagePlace: String? = null,
)

@Schema(description = "A devolução de um item achado.")
data class LostAndFoundReturnRequest(
    @field:NotBlank
    @field:Schema(
        description =
            "A quem o item foi devolvido (nome livre de quem recebeu), até 200 caracteres. Só quem gere os achados " +
                "o vê, e ele não vai à trilha de auditoria.",
        maxLength = MAX_RETURNED_TO,
    )
    val returnedTo: String?,
)

@Schema(description = "O descarte ou a doação de um item achado vencido.")
data class LostAndFoundDisposalRequest(
    @field:NotBlank
    @field:Schema(description = "O motivo, até 500 caracteres. $TEXT_WARNING", maxLength = MAX_REASON)
    val reason: String?,
)

@Schema(description = "Um item de achados e perdidos, sem dado de hóspede.")
data class LostAndFoundItemResponse(
    val id: UUID,
    @field:Schema(description = "A UH onde foi achado; nula num achado de área.", nullable = true)
    val roomId: UUID?,
    @field:Schema(description = "A área comum onde foi achado; nula num achado de UH.", nullable = true)
    val areaId: UUID?,
    @field:Schema(description = "O que foi achado.")
    val description: String,
    @field:Schema(description = "Onde o item fica guardado; nulo se não informado.", nullable = true)
    val storagePlace: String?,
    @field:Schema(description = "Quando foi achado.")
    val foundAt: Instant,
    @field:Schema(description = "O usuário que registrou o item (o dono no escopo `OWN`).")
    val reportedBy: UUID,
    @field:ExtensibleEnum
    @field:Schema(description = "A origem do registro (trate um valor desconhecido sem falhar).")
    val source: OccurrenceSource,
    @field:ExtensibleEnum
    @field:Schema(description = "O status do item (trate um valor desconhecido sem falhar).")
    val status: LostAndFoundStatus,
    @field:Schema(
        description = "Se o item guardado venceu o prazo de guarda; sempre falso fora de `STORED`. $EXPIRY_RULE",
    )
    val expired: Boolean,
    @field:Schema(
        description =
            "A quem foi devolvido, só em `RETURNED` e só para quem tem `housekeeping.lostfound.manage`; nulo para os " +
                "outros.",
        nullable = true,
    )
    val returnedTo: String?,
    @field:Schema(description = "O motivo do descarte ou da doação; nulo nos outros status.", nullable = true)
    val disposalReason: String?,
    @field:Schema(description = "Quando o item foi devolvido, descartado ou doado; nulo em `STORED`.", nullable = true)
    val resolvedAt: Instant?,
    @field:Schema(description = "Quem devolveu, descartou ou doou o item; nulo em `STORED`.", nullable = true)
    val resolvedBy: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: LostAndFoundItemView) =
            LostAndFoundItemResponse(
                view.id.value,
                view.roomId?.value,
                view.areaId?.value,
                view.description,
                view.storagePlace,
                view.foundAt,
                view.reportedBy.value,
                view.source,
                view.status,
                view.expired,
                view.returnedTo,
                view.disposalReason,
                view.resolvedAt,
                view.resolvedBy?.value,
                view.createdAt,
                view.updatedAt,
            )
    }
}
