package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.CleaningTypeId
import com.zeny.hotel.housekeeping.HousekeepingErrorCodes
import com.zeny.hotel.housekeeping.HousekeepingPermissions
import com.zeny.hotel.housekeeping.ScheduledCleaningId
import com.zeny.hotel.housekeeping.domain.CleaningType
import com.zeny.hotel.housekeeping.domain.ScheduledCleaning
import com.zeny.hotel.housekeeping.domain.ScheduledCleaningInput
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.requireCurrentVersion
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Persistência das faxinas programadas, na transação corrente. */
interface ScheduledCleaningStore {
    fun scheduledCleaning(id: ScheduledCleaningId): ScheduledCleaning?

    /** A faxina com a linha travada (`SELECT … FOR NO KEY UPDATE`) e relida; trava sempre antes do tipo. */
    fun forUpdate(id: ScheduledCleaningId): ScheduledCleaning?

    /** As faxinas de [ids], na ordem de [ids] (as inexistentes ficam de fora). */
    fun scheduledCleanings(ids: List<UUID>): List<ScheduledCleaning>

    /** As chaves da ordenação (próxima data, id) da página depois de [after], só as ativas ou inativas se [active]. */
    fun page(
        active: Boolean?,
        after: Pair<LocalDate, UUID>?,
        limit: Int,
    ): List<Pair<LocalDate, UUID>>

    /** Grava uma faxina nova; `false` se já há outra ativa no mesmo local com o mesmo tipo. */
    fun tryAdd(scheduledCleaning: ScheduledCleaning): Boolean

    /** Grava as mudanças pendentes; `false` se já há outra ativa no mesmo local com o mesmo tipo. */
    fun tryFlush(): Boolean

    fun flush()
}

/** Os dados de criação de uma faxina programada: o local (a UH **ou** a área), o tipo, a frequência e a última. */
data class ScheduledCleaningCommand(
    val roomId: RoomId?,
    val areaId: AreaId?,
    val cleaningTypeId: CleaningTypeId,
    val frequencyDays: Int,
    val lastDoneOn: LocalDate?,
) {
    internal val input: ScheduledCleaningInput
        get() = ScheduledCleaningInput(roomId, areaId, cleaningTypeId, frequencyDays, lastDoneOn)
}

/** Uma faxina programada. */
data class ScheduledCleaningView(
    val id: ScheduledCleaningId,
    val roomId: RoomId?,
    val areaId: AreaId?,
    val cleaningTypeId: CleaningTypeId,
    val frequencyDays: Int,
    val lastDoneOn: LocalDate?,
    val nextDueOn: LocalDate,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

/**
 * Faxinas programadas (RF-GOV-10, M6 E01). Consultar exige `housekeeping.config.read`; escrever,
 * `housekeeping.config.manage`, conferida **antes** de qualquer recusa (`400`, `404`, `409`, `422`). A edição confere
 * a versão (`If-Match`) e troca só o tipo e a frequência. Nada é apagado, só desativado.
 *
 * Referências, na ordem: o local (UH ou área, pela `RoomsApi`) e o tipo inexistentes são `400 …UNKNOWN_…` (H16); o
 * local ou o tipo desativados, `422`; o `lastDoneOn` depois da data operacional (`HotelApi`), `422`. A segunda faxina
 * ativa no mesmo (local, tipo) é `409`, pelo índice único parcial.
 *
 * Concorrência (Review Focus 4): criar, reativar e trocar o tipo travam o tipo (`CleaningTypeStore.forUpdate`) antes de
 * conferir que ele está ativo, e a desativação do tipo trava a mesma linha antes de conferir as faxinas ativas; quem
 * mexe numa faxina existente a trava antes (ordem faxina → tipo). Assim nunca sobra faxina ativa num tipo inativo.
 */
@Service
@Suppress("TooManyFunctions", "LongParameterList") // um caso de uso por operação; as portas que ele usa
class ScheduledCleaningsService(
    private val store: ScheduledCleaningStore,
    private val cleaningTypes: CleaningTypeStore,
    private val rooms: RoomsApi,
    private val hotel: HotelApi,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(
        active: Boolean?,
        page: PageParams,
    ): CursorPage<ScheduledCleaningView> {
        authorization.require(HousekeepingPermissions.CONFIG_READ)
        val after = page.decodedCursor()?.let { it.localDate(0) to it.uuid(1) }
        val rows = store.page(active, after, page.limit + 1)
        val keys = CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.first.toString(), it.second.toString()) }
        return CursorPage(store.scheduledCleanings(keys.items.map { it.second }).map { it.view() }, keys.nextCursor)
    }

    @Transactional(readOnly = true)
    fun get(id: ScheduledCleaningId): ScheduledCleaningView {
        authorization.require(HousekeepingPermissions.CONFIG_READ)
        return (store.scheduledCleaning(id) ?: throw notFound()).view()
    }

    @Audited(HousekeepingAudit.CREATE_SCHEDULED_CLEANING)
    @Transactional
    @Suppress("ThrowsCount") // uma recusa por motivo, na ordem 400 → 422 → 409
    fun create(command: ScheduledCleaningCommand): ScheduledCleaningView {
        authorization.require(HousekeepingPermissions.CONFIG_MANAGE)
        val input = command.input
        ScheduledCleaning.validate(input)
        val locationActive = locationActive(input.roomId, input.areaId) ?: throw unknownLocation(input.roomId)
        val type = lockedType(input.cleaningTypeId)
        if (!locationActive) throw locationInactive()
        requireActive(type)
        val scheduledCleaning = ScheduledCleaning.create(input, hotel.currentBusinessDate(), clock.instant())
        audit.created(HousekeepingAudit.SCHEDULED_CLEANING, scheduledCleaning.id.value) {
            scheduledCleaning.auditState()
        }
        if (!store.tryAdd(scheduledCleaning)) throw alreadyExists()
        return scheduledCleaning.view()
    }

    @Audited(HousekeepingAudit.UPDATE_SCHEDULED_CLEANING)
    @Transactional
    fun update(
        id: ScheduledCleaningId,
        expectedVersion: Long,
        cleaningTypeId: CleaningTypeId,
        frequencyDays: Int,
    ): ScheduledCleaningView {
        authorization.require(HousekeepingPermissions.CONFIG_MANAGE)
        val scheduledCleaning = store.forUpdate(id) ?: throw notFound()
        requireCurrentVersion(expectedVersion, checkNotNull(scheduledCleaning.version))
        audit.track(HousekeepingAudit.SCHEDULED_CLEANING, id.value) { scheduledCleaning.auditState() }
        // O tipo novo precisa existir e estar ativo, mesmo com a faxina desativada.
        if (cleaningTypeId != scheduledCleaning.cleaningTypeId) requireActive(lockedType(cleaningTypeId))
        val changed =
            scheduledCleaning.update(cleaningTypeId, frequencyDays, hotel.currentBusinessDate(), clock.instant())
        if (changed && !store.tryFlush()) throw alreadyExists()
        return scheduledCleaning.view()
    }

    @Audited(HousekeepingAudit.DEACTIVATE_SCHEDULED_CLEANING)
    @Transactional
    fun deactivate(id: ScheduledCleaningId): ScheduledCleaningView {
        authorization.require(HousekeepingPermissions.CONFIG_MANAGE)
        val scheduledCleaning = store.forUpdate(id) ?: throw notFound()
        audit.track(HousekeepingAudit.SCHEDULED_CLEANING, id.value) { scheduledCleaning.auditState() }
        if (scheduledCleaning.deactivate(clock.instant())) store.flush()
        return scheduledCleaning.view()
    }

    /**
     * Reativa com a próxima data antiga, mesmo vencida (a E02 decide se a recalcula), conferindo, como a criação, que
     * o tipo e o local estão ativos (`422`).
     */
    @Audited(HousekeepingAudit.REACTIVATE_SCHEDULED_CLEANING)
    @Transactional
    @Suppress("ThrowsCount") // inexistente, local desativado e outra ativa no mesmo (local, tipo)
    fun reactivate(id: ScheduledCleaningId): ScheduledCleaningView {
        authorization.require(HousekeepingPermissions.CONFIG_MANAGE)
        val scheduledCleaning = store.forUpdate(id) ?: throw notFound()
        audit.track(HousekeepingAudit.SCHEDULED_CLEANING, id.value) { scheduledCleaning.auditState() }
        if (scheduledCleaning.active) return scheduledCleaning.view()
        requireActive(lockedType(scheduledCleaning.cleaningTypeId))
        // Um local que sumiu (o `rooms` não apaga) conta como desativado.
        if (locationActive(scheduledCleaning.roomId, scheduledCleaning.areaId) != true) throw locationInactive()
        scheduledCleaning.reactivate(clock.instant())
        if (!store.tryFlush()) throw alreadyExists()
        return scheduledCleaning.view()
    }

    // --- Apoio -------------------------------------------------------------------------------------

    /** Se o local (a UH ou a área) está ativo, ou `null` se não existe. */
    private fun locationActive(
        roomId: RoomId?,
        areaId: AreaId?,
    ): Boolean? = roomId?.let { rooms.room(it)?.active } ?: areaId?.let { rooms.area(it)?.active }

    /** O tipo com a linha travada (sempre depois da faxina); inexistente, `400` (H16). */
    private fun lockedType(id: CleaningTypeId): CleaningType =
        cleaningTypes.forUpdate(id)
            ?: throw invalid(
                HousekeepingErrorCodes.SCHEDULED_CLEANING_UNKNOWN_CLEANING_TYPE,
                "cleaningTypeId",
                "O tipo de limpeza não existe.",
            )

    private fun requireActive(type: CleaningType) {
        if (!type.active) {
            throw BusinessRuleException(
                HousekeepingErrorCodes.CLEANING_TYPE_INACTIVE,
                "O tipo de limpeza está desativado.",
            )
        }
    }

    private fun unknownLocation(roomId: RoomId?) =
        invalid(
            HousekeepingErrorCodes.SCHEDULED_CLEANING_UNKNOWN_LOCATION,
            if (roomId != null) "roomId" else "areaId",
            "O local da faxina não existe.",
        )

    private fun locationInactive() =
        BusinessRuleException(
            HousekeepingErrorCodes.SCHEDULED_CLEANING_LOCATION_INACTIVE,
            "O local da faxina está desativado.",
        )

    private fun notFound() =
        NotFoundException(HousekeepingErrorCodes.SCHEDULED_CLEANING_NOT_FOUND, "Faxina programada não encontrada.")

    private fun alreadyExists() =
        ConflictException(
            HousekeepingErrorCodes.SCHEDULED_CLEANING_ALREADY_EXISTS,
            "Já há uma faxina programada ativa com este tipo de limpeza neste local.",
        )

    private fun invalid(
        code: ErrorCode,
        field: String,
        message: String,
    ) = InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))
}

internal fun ScheduledCleaning.auditState(): Map<String, Any?> =
    mapOf(
        "roomId" to roomId?.toString(),
        "areaId" to areaId?.toString(),
        "cleaningTypeId" to cleaningTypeId.toString(),
        "frequencyDays" to frequencyDays,
        "lastDoneOn" to lastDoneOn?.toString(),
        "nextDueOn" to nextDueOn.toString(),
        "active" to active,
    )

internal fun ScheduledCleaning.view() =
    ScheduledCleaningView(
        id,
        roomId,
        areaId,
        cleaningTypeId,
        frequencyDays,
        lastDoneOn,
        nextDueOn,
        active,
        createdAt,
        updatedAt,
        checkNotNull(version),
    )
