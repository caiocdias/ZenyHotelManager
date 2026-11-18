package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.housekeeping.CleaningTypeId
import com.zeny.hotel.housekeeping.CleaningTypeKey
import com.zeny.hotel.housekeeping.HousekeepingErrorCodes
import com.zeny.hotel.housekeeping.HousekeepingPermissions
import com.zeny.hotel.housekeeping.domain.CleaningCredit
import com.zeny.hotel.housekeeping.domain.CleaningType
import com.zeny.hotel.housekeeping.domain.CleaningTypeDetails
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.requireCurrentVersion
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Persistência dos tipos de limpeza, na transação corrente. Gravar e trocar o código convertem a violação da
 * unicidade do código em `false` (o caso de uso responde `409`), nunca em `500`.
 */
interface CleaningTypeStore {
    fun cleaningType(id: CleaningTypeId): CleaningType?

    /**
     * O tipo com a linha travada (`SELECT … FOR NO KEY UPDATE`, que não conflita com o `FOR KEY SHARE` da FK de quem
     * referencia o tipo) e relido. A trava sai sempre depois da da faxina programada (ordem faxina → tipo).
     */
    fun forUpdate(id: CleaningTypeId): CleaningType?

    /** Os tipos de [ids], na ordem de [ids] (os inexistentes ficam de fora). */
    fun cleaningTypes(ids: List<UUID>): List<CleaningType>

    /** As chaves da ordenação (código, id) da página depois de [after], só os ativos ou inativos se [active]. */
    fun page(
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<Pair<String, UUID>>

    /** Grava um tipo novo; `false` se o código já está em uso. */
    fun tryAdd(cleaningType: CleaningType): Boolean

    /** Grava as mudanças pendentes; `false` se o código novo já está em uso. */
    fun tryFlush(): Boolean

    fun flush()

    /** Se há faxina programada ativa com o tipo (as faxinas programadas chegam na Task 3 da E01). */
    fun hasActiveSchedules(id: CleaningTypeId): Boolean
}

/** Um tipo de limpeza, com o aviso dos tipos de UH ativos sem crédito, calculado na leitura (D6). */
data class CleaningTypeView(
    val id: CleaningTypeId,
    val key: CleaningTypeKey?,
    val code: String,
    val name: String,
    val requiresInspection: Boolean,
    val defaultPriority: Int,
    val linenChangeEveryNights: Int?,
    val checklistTemplateId: UUID?,
    val credits: List<CleaningCreditView>,
    val missingCreditRoomTypeIds: List<RoomTypeId>,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class CleaningCreditView(
    val roomTypeId: RoomTypeId,
    val credits: Int,
)

/** Os dados de criação ou de edição de um tipo de limpeza (na edição, os créditos são a lista inteira). */
data class CleaningTypeCommand(
    val code: String,
    val name: String,
    val requiresInspection: Boolean,
    val defaultPriority: Int,
    val linenChangeEveryNights: Int?,
    val checklistTemplateId: UUID?,
    val credits: List<CleaningCreditView>,
) {
    internal val details: CleaningTypeDetails
        get() =
            CleaningTypeDetails(
                code,
                name,
                requiresInspection,
                defaultPriority,
                linenChangeEveryNights,
                checklistTemplateId,
                credits.map { CleaningCredit(it.roomTypeId, it.credits) },
            )
}

/**
 * Tipos de limpeza (RF-GOV-01, M6 E01). Consultar exige `housekeeping.config.read`; escrever,
 * `housekeeping.config.manage`, conferida **antes** de qualquer recusa que revele estado (`404`, `409`, `400
 * …UNKNOWN_…`). A edição confere a versão (`If-Match`). Nada é apagado, só desativado.
 *
 * Os tipos de UH dos créditos são conferidos pela `RoomsApi` (nunca pelas tabelas do `rooms`), só os novos no tipo:
 * inexistente `400 CLEANING_TYPE_UNKNOWN_ROOM_TYPE` (H16); desativado é aceito (D6). Um tipo ativo sem crédito para um
 * tipo de UH ativo não é recusado, porque o gestor configura aos poucos: a leitura o aponta em
 * `missingCreditRoomTypeIds`, que muda quando um tipo de UH é criado depois. O vínculo ao modelo de checklist chega na
 * Task 2: até lá, só `null`.
 */
@Service
@Suppress("TooManyFunctions") // um caso de uso por operação e as conferências
class CleaningTypesService(
    private val store: CleaningTypeStore,
    private val rooms: RoomsApi,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(
        active: Boolean?,
        page: PageParams,
    ): CursorPage<CleaningTypeView> {
        authorization.require(HousekeepingPermissions.CONFIG_READ)
        val after = page.decodedCursor()?.let { textKey(it) to it.uuid(1) }
        val rows = store.page(active, after, page.limit + 1)
        val keys = CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.first, it.second.toString()) }
        val activeRoomTypes = activeRoomTypes()
        return CursorPage(
            store.cleaningTypes(keys.items.map { it.second }).map { it.view(activeRoomTypes) },
            keys.nextCursor,
        )
    }

    @Transactional(readOnly = true)
    fun get(id: CleaningTypeId): CleaningTypeView {
        authorization.require(HousekeepingPermissions.CONFIG_READ)
        return find(id).view(activeRoomTypes())
    }

    @Audited(HousekeepingAudit.CREATE_CLEANING_TYPE)
    @Transactional
    fun create(command: CleaningTypeCommand): CleaningTypeView {
        authorization.require(HousekeepingPermissions.CONFIG_MANAGE)
        val cleaningType = CleaningType.create(command.details, clock.instant())
        requireLinkable(cleaningType.details, current = null)
        audit.created(HousekeepingAudit.CLEANING_TYPE, cleaningType.id.value) { cleaningType.auditState() }
        if (!store.tryAdd(cleaningType)) throw codeInUse()
        return cleaningType.view(activeRoomTypes())
    }

    @Audited(HousekeepingAudit.UPDATE_CLEANING_TYPE)
    @Transactional
    fun update(
        id: CleaningTypeId,
        expectedVersion: Long,
        command: CleaningTypeCommand,
    ): CleaningTypeView {
        authorization.require(HousekeepingPermissions.CONFIG_MANAGE)
        val cleaningType = find(id)
        requireCurrentVersion(expectedVersion, checkNotNull(cleaningType.version))
        audit.track(HousekeepingAudit.CLEANING_TYPE, id.value) { cleaningType.auditState() }
        requireLinkable(CleaningType.validate(command.details), current = cleaningType.details)
        if (cleaningType.update(command.details, clock.instant()) && !store.tryFlush()) throw codeInUse()
        return cleaningType.view(activeRoomTypes())
    }

    @Audited(HousekeepingAudit.DEACTIVATE_CLEANING_TYPE)
    @Transactional
    fun deactivate(id: CleaningTypeId): CleaningTypeView {
        authorization.require(HousekeepingPermissions.CONFIG_MANAGE)
        val cleaningType = find(id)
        audit.track(HousekeepingAudit.CLEANING_TYPE, id.value) { cleaningType.auditState() }
        if (cleaningType.deactivate(clock.instant())) store.flush()
        return cleaningType.view(activeRoomTypes())
    }

    @Audited(HousekeepingAudit.REACTIVATE_CLEANING_TYPE)
    @Transactional
    fun reactivate(id: CleaningTypeId): CleaningTypeView {
        authorization.require(HousekeepingPermissions.CONFIG_MANAGE)
        val cleaningType = find(id)
        audit.track(HousekeepingAudit.CLEANING_TYPE, id.value) { cleaningType.auditState() }
        if (cleaningType.reactivate(clock.instant())) store.flush()
        return cleaningType.view(activeRoomTypes())
    }

    // --- Apoio -------------------------------------------------------------------------------------

    /** As referências novas em [details] (as que não estão em [current]) existem. */
    private fun requireLinkable(
        details: CleaningTypeDetails,
        current: CleaningTypeDetails?,
    ) {
        if (details.checklistTemplateId != null) {
            throw invalid(
                HousekeepingErrorCodes.HOUSEKEEPING_DATA_INVALID,
                "checklistTemplateId",
                "O vínculo a um modelo de checklist ainda não está disponível; informe null.",
            )
        }
        val known =
            current
                ?.credits
                .orEmpty()
                .map { it.roomTypeId }
                .toSet()
        val unknown =
            details.credits
                .map { it.roomTypeId }
                .filter { it !in known }
                .any { rooms.roomType(it) == null }
        if (unknown) {
            throw invalid(
                HousekeepingErrorCodes.CLEANING_TYPE_UNKNOWN_ROOM_TYPE,
                "credits",
                "Algum tipo de UH dos créditos não existe.",
            )
        }
    }

    private fun activeRoomTypes(): List<RoomTypeId> = rooms.activeRoomTypes().map { it.id }

    /** A chave de texto do cursor; com NUL (que o PostgreSQL não aceita num texto), o cursor é inválido. */
    private fun textKey(cursor: PageCursor): String =
        cursor.string(0).takeIf { NUL !in it }
            ?: throw InvalidInputException(
                ErrorCodes.INVALID_CURSOR,
                "Cursor de paginação inválido. Recomece da primeira página.",
            )

    private fun find(id: CleaningTypeId) =
        store.cleaningType(id)
            ?: throw NotFoundException(
                HousekeepingErrorCodes.CLEANING_TYPE_NOT_FOUND,
                "Tipo de limpeza não encontrado.",
            )

    private fun codeInUse() =
        ConflictException(
            HousekeepingErrorCodes.CLEANING_TYPE_CODE_ALREADY_IN_USE,
            "Já existe um tipo de limpeza com este código; se estiver desativado, reative-o.",
        )

    private fun invalid(
        code: ErrorCode,
        field: String,
        message: String,
    ) = InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))

    private companion object {
        const val NUL = '\u0000'
    }
}

/** Ações e entidades da trilha do `housekeeping` (retrato só da configuração). */
internal object HousekeepingAudit {
    const val CLEANING_TYPE = "housekeeping.cleaning-type"

    const val CREATE_CLEANING_TYPE = "housekeeping.cleaning-type.create"
    const val UPDATE_CLEANING_TYPE = "housekeeping.cleaning-type.update"
    const val DEACTIVATE_CLEANING_TYPE = "housekeeping.cleaning-type.deactivate"
    const val REACTIVATE_CLEANING_TYPE = "housekeeping.cleaning-type.reactivate"
}

internal fun CleaningType.auditState(): Map<String, Any?> =
    mapOf(
        "key" to key?.name,
        "code" to code,
        "name" to name,
        "requiresInspection" to requiresInspection,
        "defaultPriority" to defaultPriority,
        "linenChangeEveryNights" to linenChangeEveryNights,
        "checklistTemplateId" to checklistTemplateId?.toString(),
        "credits" to credits.associate { it.roomTypeId.toString() to it.credits }.toSortedMap(),
        "active" to active,
    )

/** O tipo, com os [activeRoomTypes] sem crédito (vazio se o tipo está desativado). */
internal fun CleaningType.view(activeRoomTypes: List<RoomTypeId>): CleaningTypeView {
    val credited = credits.map { it.roomTypeId }.toSet()
    return CleaningTypeView(
        id,
        key,
        code,
        name,
        requiresInspection,
        defaultPriority,
        linenChangeEveryNights,
        checklistTemplateId,
        credits.map { CleaningCreditView(it.roomTypeId, it.credits) },
        if (active) activeRoomTypes.filter { it !in credited }.sortedBy { it.value } else emptyList(),
        active,
        createdAt,
        updatedAt,
        checkNotNull(version),
    )
}

@Configuration(proxyBeanMethods = false)
class HousekeepingConfiguration {
    /** As permissões do `housekeeping` no catálogo (sincronizado pelo `iam` no boot). */
    @Bean
    fun housekeepingPermissions(): ModulePermissions = HousekeepingPermissions
}
