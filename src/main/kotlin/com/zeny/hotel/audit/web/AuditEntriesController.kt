package com.zeny.hotel.audit.web

import com.zeny.hotel.audit.application.AuditEntryFilter
import com.zeny.hotel.audit.application.AuditEntryView
import com.zeny.hotel.audit.application.AuditQueries
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.AuditOutcome
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.PageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Instant
import java.util.UUID

/** Consulta da trilha de auditoria (RF-ACS-11). Não há escrita: a trilha só cresce pelos casos de uso. */
@ApiV1Controller
@RequestMapping("/audit-entries")
@Tag(name = "audit", description = "Trilha de auditoria das escritas.")
class AuditEntriesController(
    private val queries: AuditQueries,
) {
    @GetMapping
    @Operation(
        operationId = "listAuditEntries",
        summary = "Consultar a trilha de auditoria",
        description =
            "Entradas da trilha, das mais recentes para as mais antigas, filtradas por quem agiu (`userId`), pela " +
                "entidade (`entityType` e, opcionalmente, `entityId`) e pelo período (`from` inclusive, `to` " +
                "exclusive, instantes em UTC). Cada entrada traz quem, quando e de qual sessão de dispositivo, a " +
                "ação e o resultado, a entidade, os valores antes e depois (com senha, PIN, tokens e campos " +
                "cifrados mascarados como `***`) e a aprovação associada. Exige `audit.read` (sem ela, 403 " +
                "`PERMISSION_DENIED`).",
    )
    @Suppress("LongParameterList") // os filtros da consulta, todos opcionais
    fun list(
        @Parameter(description = "Só as entradas deste usuário (quem agiu).")
        @RequestParam(required = false) userId: UUID?,
        @Parameter(description = "Tipo da entidade, como `iam.user` ou `iam.role`.")
        @RequestParam(required = false)
        @Size(max = MAX_TYPE_LENGTH) entityType: String?,
        @Parameter(description = "Id da entidade; exige `entityType`.")
        @RequestParam(required = false)
        @Size(max = MAX_ID_LENGTH) entityId: String?,
        @Parameter(description = "Início do período (inclusive), em UTC.", example = "2026-10-01T00:00:00Z")
        @RequestParam(required = false) from: Instant?,
        @Parameter(description = "Fim do período (exclusive), em UTC.", example = "2026-11-01T00:00:00Z")
        @RequestParam(required = false) to: Instant?,
        @Valid page: PageParams,
    ): CursorPage<AuditEntryResponse> =
        queries
            .list(AuditEntryFilter(userId, entityType, entityId, from, to), page)
            .map(AuditEntryResponse::of)

    private companion object {
        const val MAX_TYPE_LENGTH = 100
        const val MAX_ID_LENGTH = 200
    }
}

@Schema(description = "Uma entrada da trilha de auditoria.")
data class AuditEntryResponse(
    val id: UUID,
    @field:Schema(description = "Quando a ação aconteceu (UTC).")
    val occurredAt: Instant,
    @field:Schema(description = "Quem agiu; nulo quando foi o sistema (boot, jobs).")
    val userId: UUID?,
    @field:Schema(description = "A sessão de dispositivo de onde agiu, se houver.")
    val deviceSessionId: UUID?,
    @field:Schema(description = "Código estável da ação.", example = "iam.user.deactivate")
    val action: String,
    @field:Schema(description = "SUCCEEDED, ou REJECTED para uma recusa registrada (como a falha de login).")
    val outcome: AuditOutcome,
    @field:Schema(description = "O `code` do erro de uma recusa.", example = "INVALID_CREDENTIALS")
    val errorCode: String?,
    @field:Schema(description = "Tipo da entidade; nulo numa ação sem entidade.", example = "iam.user")
    val entityType: String?,
    val entityId: String?,
    @field:Schema(
        description = "Valores antes da ação; nulo numa criação. Campos sensíveis saem como `***`.",
        additionalProperties = Schema.AdditionalPropertiesValue.TRUE,
    )
    val before: Map<String, Any?>?,
    @field:Schema(
        description = "Valores depois da ação. Campos sensíveis saem como `***`.",
        additionalProperties = Schema.AdditionalPropertiesValue.TRUE,
    )
    val after: Map<String, Any?>?,
    @field:Schema(description = "A aprovação de superior associada, se houver.")
    val approvalId: UUID?,
) {
    companion object {
        fun of(view: AuditEntryView) =
            AuditEntryResponse(
                id = view.id,
                occurredAt = view.occurredAt,
                userId = view.userId,
                deviceSessionId = view.deviceSessionId,
                action = view.action,
                outcome = view.outcome,
                errorCode = view.errorCode,
                entityType = view.entityType,
                entityId = view.entityId,
                before = view.before,
                after = view.after,
                approvalId = view.approvalId,
            )
    }
}
