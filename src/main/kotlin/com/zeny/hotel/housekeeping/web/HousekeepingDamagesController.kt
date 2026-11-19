package com.zeny.hotel.housekeeping.web

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.housekeeping.DamageReportId
import com.zeny.hotel.housekeeping.DamageReportStatus
import com.zeny.hotel.housekeeping.OccurrenceSource
import com.zeny.hotel.housekeeping.application.DamageReportRegistration
import com.zeny.hotel.housekeeping.application.DamageReportView
import com.zeny.hotel.housekeeping.application.DamageReview
import com.zeny.hotel.housekeeping.application.HousekeepingDamages
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.PageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import java.net.URI
import java.time.Instant
import java.util.UUID

private const val DAMAGE_READ_RULES =
    "Exige `housekeeping.damages.manage` (todos os danos) ou `housekeeping.occurrences.report` (com `OWN`, só os " +
        "registrados pelo usuário); sem nenhuma, 403 `PERMISSION_DENIED`. A reserva vinculada (`reservationId`), a " +
        "conta (`folioId`) e o lançamento (`postingId`) só vêm para quem tem `housekeeping.damages.manage`."
private const val DAMAGE_ACTION_RULES =
    "Exige `housekeeping.damages.manage` (só `ALL`; 403 `PERMISSION_DENIED` antes de qualquer recusa que dependa do " +
        "dano, inclusive para quem o registrou; o 400 de formato do corpo e o 428 sem `If-Match` vêm antes do 403; o " +
        "400 de id inexistente no corpo vem depois dele e do status). Dano inexistente: 404 " +
        "`HOUSEKEEPING_DAMAGE_NOT_FOUND`. Com o `If-Match` do dano (412 com outra versão; 428 sem ele). Ação fora do " +
        "status: 409 `HOUSEKEEPING_DAMAGE_TRANSITION_NOT_ALLOWED` (com `damageStatus` e `action`), sem mudança. " +
        "Responde o dano com o `ETag` novo."

// Os limites dos textos (os do domínio, que os confere), só para o contrato.
private const val MAX_DAMAGE_DESCRIPTION = 500
private const val MAX_DAMAGE_REASON = 500
private const val DAMAGE_TEXT_WARNING =
    "**Não escreva dado de hóspede** (nome, documento, contato): a governança vê o dano."

/**
 * Danos (RF-GOV-09, M6 E06): o registro online (com o vínculo sugerido à reserva da UH), a consulta (com o escopo
 * `OWN` da camareira) e as ações de revisão, cobrança e dispensa como subrecursos, com o `If-Match` do dano
 * (ARCHITECTURE §7). O instante do fato no registro online é o do relógio do servidor. Não há `DELETE`.
 */
@ApiV1Controller
@RequestMapping("/damage-reports")
@Tag(name = HOUSEKEEPING_TAG, description = HOUSEKEEPING_TAG_DESCRIPTION)
class HousekeepingDamagesController(
    private val damages: HousekeepingDamages,
) {
    @GetMapping
    @Operation(
        operationId = "listDamageReports",
        summary = "Listar os danos",
        description = "Do fato mais recente para o mais antigo. $DAMAGE_READ_RULES",
    )
    fun list(
        @Valid page: PageParams,
    ): CursorPage<DamageReportResponse> = damages.list(page).map(DamageReportResponse::of)

    @PostMapping
    @Operation(
        operationId = "registerDamageReport",
        summary = "Registrar um dano",
        description =
            "Numa UH (`roomId`) ou numa área comum (`areaId`), exatamente um: os dois ou nenhum, 400 " +
                "`HOUSEKEEPING_OCCURRENCE_LOCATION_INVALID`; local inexistente, 400 " +
                "`HOUSEKEEPING_OCCURRENCE_UNKNOWN_LOCATION` no campo. O dano nasce `REPORTED`, com o fato agora, " +
                "origem `MANUAL` e quem registra em `reportedBy` (o dono no escopo `OWN`). Numa UH ocupada, " +
                "vincula a reserva hospedada nela desde antes do fato (visível só a quem tem " +
                "`housekeeping.damages.manage`). " +
                "Exige `housekeeping.occurrences.report` (qualquer escopo; 403 `PERMISSION_DENIED` antes de qualquer " +
                "recusa que dependa do local; o 400 do corpo vem antes). Custo estimado (`estimatedCost`) opcional, " +
                "de 0 a 99999.99, com até 2 casas. Texto ou valor fora do formato: 400 `HOUSEKEEPING_DATA_INVALID` " +
                "no campo.",
    )
    @ApiResponse(responseCode = "201", description = "Dano registrado.")
    fun register(
        @Valid @RequestBody request: DamageReportRequest,
    ): ResponseEntity<DamageReportResponse> {
        val view =
            damages.register(
                DamageReportRegistration(
                    request.roomId?.let(::RoomId),
                    request.areaId?.let(::AreaId),
                    request.description,
                    request.estimatedCost,
                ),
            )
        return ResponseEntity
            .created(URI.create("/api/v1/damage-reports/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(DamageReportResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getDamageReport",
        summary = "Consultar um dano",
        description =
            "$DAMAGE_READ_RULES Inexistente, ou de outro usuário com o escopo `OWN`: 404 " +
                "`HOUSEKEEPING_DAMAGE_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<DamageReportResponse> = respond(damages.get(DamageReportId(id)))

    @PostMapping("/{id}/review")
    @Operation(
        operationId = "reviewDamageReport",
        summary = "Revisar um dano",
        description =
            "Fixa o valor final (`finalAmount`, maior que zero e até 99999.99; um dano sem valor é dispensado), o " +
                "código de lançamento (`transactionCodeId`: inexistente, 400 " +
                "`HOUSEKEEPING_DAMAGE_UNKNOWN_TRANSACTION_CODE` no campo; desativado ou de tipo diferente de " +
                "`CHARGE` e `PENALTY`, 409 `HOUSEKEEPING_DAMAGE_TRANSACTION_CODE_INVALID`) e a reserva vinculada " +
                "(`reservationId`). **A revisão substitui o vínculo inteiro: para mantê-lo, envie o `reservationId` " +
                "atual (o do GET); nulo ou omitido retira.** Outra reserva troca o vínculo: inexistente, 400 " +
                "`HOUSEKEEPING_DAMAGE_UNKNOWN_RESERVATION` no campo; não hospedada, 409 " +
                "`HOUSEKEEPING_DAMAGE_RESERVATION_INVALID`; ela não precisa estar na UH do dano (o hóspede pode ter " +
                "trocado de UH). Vale em `REPORTED` e `REVIEWED`; o dano vai a `REVIEWED`. $DAMAGE_ACTION_RULES",
    )
    fun review(
        @PathVariable id: UUID,
        @Valid @RequestBody request: DamageReviewRequest,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<DamageReportResponse> {
        val expected = ETags.expectedVersion(ifMatch)
        val review =
            DamageReview(
                checkNotNull(request.finalAmount),
                TransactionCodeId(checkNotNull(request.transactionCodeId)),
                request.reservationId?.let(::ReservationId),
            )
        return respond(damages.review(DamageReportId(id), review, expected))
    }

    @PostMapping("/{id}/charge")
    @Operation(
        operationId = "chargeDamageReport",
        summary = "Cobrar um dano na conta da reserva",
        description =
            "Lança o valor final do dano revisado na conta `folioId`, que precisa ser uma conta aberta da reserva " +
                "vinculada (inexistente, 400 `HOUSEKEEPING_DAMAGE_UNKNOWN_FOLIO` no campo; encerrada ou de outro " +
                "dono, 409 `HOUSEKEEPING_DAMAGE_FOLIO_INVALID`): um lançamento só, com o código revisado, sem " +
                "taxa de serviço, a descrição fixa \"Dano na UH <número>\" e a data operacional corrente; o dano " +
                "vai a `CHARGED`. Uma regra de roteamento do `billing` pode desviar o lançamento para outra conta: o " +
                "`folioId` gravado no dano é o da conta final. Recusas do `billing`, sem mudança no dano: 422 " +
                "`TRANSACTION_CODE_INACTIVE` (código desativado depois da revisão), 409 `ROUTING_TARGET_CLOSED` " +
                "(destino do roteamento encerrado) e 409 `FOLIO_CLOSED` (conta encerrada durante a cobrança). Não " +
                "revisado: 409 `HOUSEKEEPING_DAMAGE_NOT_REVIEWED`; sem reserva vinculada: 409 " +
                "`HOUSEKEEPING_DAMAGE_NO_RESERVATION` (só pode ser dispensado). Não exige `billing.postings.create`. " +
                DAMAGE_ACTION_RULES,
    )
    fun charge(
        @PathVariable id: UUID,
        @Valid @RequestBody request: DamageChargeRequest,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<DamageReportResponse> {
        val expected = ETags.expectedVersion(ifMatch)
        return respond(damages.charge(DamageReportId(id), FolioId(checkNotNull(request.folioId)), expected))
    }

    @PostMapping("/{id}/waive")
    @Operation(
        operationId = "waiveDamageReport",
        summary = "Dispensar um dano",
        description =
            "Dispensa o dano sem lançar: grava o motivo, quando e por quem, e o dano vai a `WAIVED`. Vale em " +
                "`REPORTED` e `REVIEWED`. $DAMAGE_ACTION_RULES",
    )
    fun waive(
        @PathVariable id: UUID,
        @Valid @RequestBody request: DamageWaiverRequest,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<DamageReportResponse> {
        val expected = ETags.expectedVersion(ifMatch)
        return respond(damages.waive(DamageReportId(id), request.reason, expected))
    }

    private fun respond(view: DamageReportView) =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(DamageReportResponse.of(view))
}

@Schema(description = "O registro de um dano, numa UH ou numa área comum (exatamente um local).")
data class DamageReportRequest(
    @field:Schema(description = "A UH do dano; informe esta ou `areaId`, uma só.", nullable = true)
    val roomId: UUID? = null,
    @field:Schema(description = "A área comum do dano; informe esta ou `roomId`, uma só.", nullable = true)
    val areaId: UUID? = null,
    // Os limites são do domínio (em code points, depois de aparar); aqui só os documentam.
    @field:NotBlank
    @field:Schema(
        description = "O dano, até 500 caracteres. $DAMAGE_TEXT_WARNING",
        maxLength = MAX_DAMAGE_DESCRIPTION,
    )
    val description: String?,
    @field:Schema(
        description = "O custo estimado, de 0 a 99999.99, com até 2 casas; omita se não souber.",
        nullable = true,
        example = "120.00",
    )
    val estimatedCost: Money? = null,
)

@Schema(description = "A revisão de um dano.")
data class DamageReviewRequest(
    @field:NotNull
    @field:Schema(description = "O valor final a cobrar, maior que zero e até 99999.99, com até 2 casas.")
    val finalAmount: Money?,
    @field:NotNull
    @field:Schema(description = "O código de lançamento da cobrança, ativo e de tipo `CHARGE` ou `PENALTY`.")
    val transactionCodeId: UUID?,
    @field:Schema(
        description =
            "A reserva vinculada. A revisão substitui o vínculo inteiro: para mantê-lo, envie o `reservationId` " +
                "atual (o do GET); nulo ou omitido retira. Outra reserva hospedada troca o vínculo; ela não precisa " +
                "estar na UH do dano (o hóspede pode ter trocado de UH).",
        nullable = true,
    )
    val reservationId: UUID? = null,
)

@Schema(description = "A cobrança de um dano revisado.")
data class DamageChargeRequest(
    @field:NotNull
    @field:Schema(description = "A conta aberta da reserva vinculada ao dano.")
    val folioId: UUID?,
)

@Schema(description = "A dispensa de um dano.")
data class DamageWaiverRequest(
    @field:NotBlank
    @field:Schema(description = "O motivo, até 500 caracteres. $DAMAGE_TEXT_WARNING", maxLength = MAX_DAMAGE_REASON)
    val reason: String?,
)

@Schema(description = "Um dano, sem dado de hóspede.")
data class DamageReportResponse(
    val id: UUID,
    @field:Schema(description = "A UH do dano; nula num dano de área.", nullable = true)
    val roomId: UUID?,
    @field:Schema(description = "A área comum do dano; nula num dano de UH.", nullable = true)
    val areaId: UUID?,
    @field:Schema(description = "O dano.")
    val description: String,
    @field:Schema(description = "O custo estimado; nulo se não informado.", nullable = true)
    val estimatedCost: Money?,
    @field:Schema(description = "O instante do fato.")
    val occurredAt: Instant,
    @field:Schema(description = "O usuário que registrou o dano (o dono no escopo `OWN`).")
    val reportedBy: UUID,
    @field:ExtensibleEnum
    @field:Schema(description = "A origem do registro (trate um valor desconhecido sem falhar).")
    val source: OccurrenceSource,
    @field:ExtensibleEnum
    @field:Schema(description = "O status do dano (trate um valor desconhecido sem falhar).")
    val status: DamageReportStatus,
    @field:Schema(
        description = "A reserva vinculada, só para quem tem `housekeeping.damages.manage`; nula para os outros.",
        nullable = true,
    )
    val reservationId: UUID?,
    @field:Schema(description = "O valor final da revisão; nulo antes dela.", nullable = true)
    val finalAmount: Money?,
    @field:Schema(description = "O código de lançamento da revisão; nulo antes dela.", nullable = true)
    val transactionCodeId: UUID?,
    @field:Schema(description = "Quem revisou; nulo antes da revisão.", nullable = true)
    val reviewedBy: UUID?,
    @field:Schema(description = "Quando foi revisado; nulo antes da revisão.", nullable = true)
    val reviewedAt: Instant?,
    @field:Schema(description = "O motivo da dispensa; nulo fora de `WAIVED`.", nullable = true)
    val waiverReason: String?,
    @field:Schema(
        description = "A conta da cobrança, só em `CHARGED` e só para quem tem `housekeeping.damages.manage`.",
        nullable = true,
    )
    val folioId: UUID?,
    @field:Schema(
        description = "O lançamento da cobrança, só em `CHARGED` e só para quem tem `housekeeping.damages.manage`.",
        nullable = true,
    )
    val postingId: UUID?,
    @field:Schema(description = "Quando foi cobrado ou dispensado; nulo antes.", nullable = true)
    val resolvedAt: Instant?,
    @field:Schema(description = "Quem cobrou ou dispensou; nulo antes.", nullable = true)
    val resolvedBy: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: DamageReportView) =
            DamageReportResponse(
                view.id.value,
                view.roomId?.value,
                view.areaId?.value,
                view.description,
                view.estimatedCost,
                view.occurredAt,
                view.reportedBy.value,
                view.source,
                view.status,
                view.reservationId?.value,
                view.finalAmount,
                view.transactionCodeId?.value,
                view.reviewedBy?.value,
                view.reviewedAt,
                view.waiverReason,
                view.folioId?.value,
                view.postingId?.value,
                view.resolvedAt,
                view.resolvedBy?.value,
                view.createdAt,
                view.updatedAt,
            )
    }
}
