package com.zeny.hotel.audit.application

import com.zeny.hotel.audit.AuditPermissions
import com.zeny.hotel.shared.AuditOutcome
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.FieldViolationCodes
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** Uma entrada da trilha como a consulta a devolve; [before] e [after] já vêm mascarados. */
data class AuditEntryView(
    val id: UUID,
    val occurredAt: Instant,
    val userId: UUID?,
    val deviceSessionId: UUID?,
    val action: String,
    val outcome: AuditOutcome,
    val errorCode: String?,
    val entityType: String?,
    val entityId: String?,
    val before: Map<String, Any?>?,
    val after: Map<String, Any?>?,
    val approvalId: UUID?,
)

/**
 * Filtros da consulta (RF-ACS-11), todos opcionais e combinados com "e": quem agiu, a entidade
 * (tipo e, opcionalmente, id) e o período (`from` inclusive, `to` exclusive).
 */
data class AuditEntryFilter(
    val userId: UUID? = null,
    val entityType: String? = null,
    val entityId: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
)

/** A posição de uma página: depois da entrada ([occurredAt], [id]), em ordem decrescente. */
data class AuditEntryPosition(
    val occurredAt: Instant,
    val id: UUID,
)

/** Leitura da trilha (`JdbcClient`, ARCHITECTURE §11). */
interface AuditEntries {
    /** Até [limit] entradas do filtro, das mais recentes para as mais antigas, depois de [after]. */
    fun page(
        filter: AuditEntryFilter,
        after: AuditEntryPosition?,
        limit: Int,
    ): List<AuditEntryView>
}

/** Consulta da trilha de auditoria; exige `audit.read`. A trilha não tem escrita pela API. */
@Service
class AuditQueries(
    private val entries: AuditEntries,
    private val authorization: Authorization,
) {
    @Transactional(readOnly = true)
    fun list(
        filter: AuditEntryFilter,
        page: PageParams,
    ): CursorPage<AuditEntryView> {
        authorization.require(AuditPermissions.READ)
        if (filter.entityId != null && filter.entityType == null) {
            throw invalid("entityId", "Informe também o tipo da entidade (entityType).")
        }
        if (filter.from != null && filter.to != null && !filter.from.isBefore(filter.to)) {
            throw invalid("to", "O fim do período precisa ser posterior ao início.")
        }
        val cursor = page.decodedCursor()
        val after = cursor?.let { AuditEntryPosition(it.instant(0), it.uuid(1)) }
        val rows = entries.page(filter, after, page.limit + 1)
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.occurredAt.toString(), it.id.toString()) }
    }

    private fun invalid(
        field: String,
        message: String,
    ) = InvalidInputException(
        ErrorCodes.VALIDATION_FAILED,
        message,
        listOf(FieldViolation(field, FieldViolationCodes.INVALID_VALUE, message)),
    )
}

/** Publica as permissões do `audit` no catálogo. */
@Configuration(proxyBeanMethods = false)
class AuditPermissionsConfiguration {
    @Bean
    fun auditPermissions(): ModulePermissions = AuditPermissions
}
