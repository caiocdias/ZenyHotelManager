package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.HousekeepingErrorCodes
import com.zeny.hotel.housekeeping.HousekeepingPermissions
import com.zeny.hotel.housekeeping.LostAndFoundItemId
import com.zeny.hotel.housekeeping.LostAndFoundStatus
import com.zeny.hotel.housekeeping.OccurrenceSource
import com.zeny.hotel.housekeeping.domain.HousekeepingRules
import com.zeny.hotel.housekeeping.domain.LostAndFoundAction
import com.zeny.hotel.housekeeping.domain.LostAndFoundItem
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.ScopeFilter
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.requireCurrentVersion
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** A chave da ordenação da lista de achados: quando foi achado (desc) e id (desc). */
data class LostAndFoundPageKey(
    val foundAt: Instant,
    val id: UUID,
)

/**
 * Os filtros da lista de achados: [expired] `true`, só os guardados vencidos (achados antes de [expiryCutoff]);
 * `false`, os demais; nulo, todos. [ownerId], com o escopo `OWN`, só os registrados por ele.
 */
data class LostAndFoundQuery(
    val expired: Boolean?,
    val expiryCutoff: Instant,
    val ownerId: UUID?,
)

/** Persistência dos achados e perdidos (D2-E06), na transação corrente. */
interface LostAndFoundStore {
    fun insert(item: LostAndFoundItem)

    fun find(id: LostAndFoundItemId): LostAndFoundItem?

    /** O item com a linha travada (`FOR UPDATE`), ou `null` se não existe. */
    fun findForUpdate(id: LostAndFoundItemId): LostAndFoundItem?

    /** Grava o destino do item travado e sobe a versão; a versão nova já vale em [item] na volta. */
    fun update(item: LostAndFoundItem)

    /** Até [limit] itens de [query] depois de [after], de quando foi achado (desc) e id (desc). */
    fun page(
        query: LostAndFoundQuery,
        after: LostAndFoundPageKey?,
        limit: Int,
    ): List<LostAndFoundItem>
}

/** O registro online de um achado: a UH **ou** a área, a descrição e o local de guarda. */
data class LostAndFoundRegistration(
    val roomId: RoomId?,
    val areaId: AreaId?,
    val description: String?,
    val storagePlace: String?,
)

/**
 * Um item de achados e perdidos para a resposta. [returnedTo] só para quem tem `housekeeping.lostfound.manage` (nulo
 * para os outros, D2-E06); [expired] pelo prazo de guarda na data operacional. Sem dado de hóspede (RN-13).
 */
data class LostAndFoundItemView(
    val id: LostAndFoundItemId,
    val roomId: RoomId?,
    val areaId: AreaId?,
    val description: String,
    val storagePlace: String?,
    val foundAt: Instant,
    val reportedBy: UserId,
    val source: OccurrenceSource,
    val status: LostAndFoundStatus,
    val expired: Boolean,
    val returnedTo: String?,
    val disposalReason: String?,
    val resolvedAt: Instant?,
    val resolvedBy: UserId?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

/**
 * Achados e perdidos (RF-GOV-08, D2-E06): o registro online, a consulta e os destinos (devolução, descarte e doação).
 * Toda ação exige a permissão antes de qualquer recusa que revele estado:
 * - registrar, `housekeeping.occurrences.report` (qualquer escopo; quem registra é o dono);
 * - consultar, `housekeeping.lostfound.manage` (todos) ou `housekeeping.occurrences.report` (com `OWN`, só os próprios;
 *   o item de outro dá o mesmo `404` do inexistente);
 * - dar destino, `housekeeping.lostfound.manage`; depois a trava do item, `404 HOUSEKEEPING_LOST_FOUND_NOT_FOUND`, a
 *   versão (`412`), o status (`409 HOUSEKEEPING_LOST_FOUND_TRANSITION_NOT_ALLOWED`) e, no descarte e na doação, o prazo
 *   de guarda (`409 HOUSEKEEPING_LOST_FOUND_NOT_EXPIRED`).
 * O vencimento é pela data operacional ([HotelApi.currentBusinessDate]), o fuso do hotel ([HotelApi.timezone]) e o
 * prazo de guarda ([HotelApi.operationalSettings]). A trilha guarda os textos livres só pelo tamanho e nunca a quem foi
 * devolvido.
 */
@Service
@Suppress("LongParameterList", "TooManyFunctions") // as portas dos achados; uma função por ação
class HousekeepingLostAndFound(
    private val store: LostAndFoundStore,
    private val rooms: RoomsApi,
    private val hotel: HotelApi,
    private val authorization: Authorization,
    private val actor: CurrentActor,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    /**
     * Registra um item achado agora, `STORED`, com origem `MANUAL`. Local inexistente: `400
     * HOUSEKEEPING_OCCURRENCE_UNKNOWN_LOCATION` no campo `roomId` ou `areaId` (H16).
     */
    @Audited(HousekeepingAudit.REGISTER_LOST_FOUND_ITEM)
    @Transactional
    fun register(registration: LostAndFoundRegistration): LostAndFoundItemView {
        authorization.require(HousekeepingPermissions.OCCURRENCES_REPORT)
        val now = clock.instant()
        val item =
            LostAndFoundItem.register(
                LostAndFoundItemId.new(),
                registration.roomId,
                registration.areaId,
                registration.description,
                registration.storagePlace,
                actor.current().userId,
                now,
                OccurrenceSource.MANUAL,
                now,
            )
        requireLocationExists(item)
        store.insert(item)
        audit.created(HousekeepingAudit.LOST_FOUND_ITEM, item.id.value) { item.auditState() }
        return Expiry.current(hotel).view(item, showReturnedTo = canManage())
    }

    @Transactional(readOnly = true)
    fun list(
        expired: Boolean?,
        page: PageParams,
    ): CursorPage<LostAndFoundItemView> {
        val owner = readScope().ownerId
        val after = page.decodedCursor()?.let { LostAndFoundPageKey(it.instant(0), it.uuid(1)) }
        val expiry = Expiry.current(hotel)
        val rows = store.page(LostAndFoundQuery(expired, expiry.cutoff, owner), after, page.limit + 1)
        val showReturnedTo = canManage()
        return CursorPage
            .fromRows(rows, page.limit) { PageCursor.of(it.foundAt.toString(), it.id.toString()) }
            .map { expiry.view(it, showReturnedTo) }
    }

    @Transactional(readOnly = true)
    fun get(id: LostAndFoundItemId): LostAndFoundItemView {
        val owner = readScope().ownerId
        val item = store.find(id)?.takeIf { owner == null || it.reportedBy.value == owner } ?: throw notFound()
        return Expiry.current(hotel).view(item, showReturnedTo = canManage())
    }

    /** Devolve o item guardado a [returnedTo] (texto livre, até 200), a qualquer tempo. */
    @Audited(HousekeepingAudit.RETURN_LOST_FOUND_ITEM)
    @Transactional
    fun returnItem(
        id: LostAndFoundItemId,
        returnedTo: String?,
        expectedVersion: Long,
    ): LostAndFoundItemView = act(id, expectedVersion) { item, _, by, at -> item.returnTo(returnedTo, by, at) }

    /** Descarta o item guardado e vencido, com o [reason]. */
    @Audited(HousekeepingAudit.DISCARD_LOST_FOUND_ITEM)
    @Transactional
    fun discard(
        id: LostAndFoundItemId,
        reason: String?,
        expectedVersion: Long,
    ): LostAndFoundItemView =
        act(id, expectedVersion) { item, expiry, by, at ->
            item.dispose(LostAndFoundAction.DISCARD, reason, expiry.isExpired(item), by, at)
        }

    /** Doa o item guardado e vencido, com o [reason]. */
    @Audited(HousekeepingAudit.DONATE_LOST_FOUND_ITEM)
    @Transactional
    fun donate(
        id: LostAndFoundItemId,
        reason: String?,
        expectedVersion: Long,
    ): LostAndFoundItemView =
        act(id, expectedVersion) { item, expiry, by, at ->
            item.dispose(LostAndFoundAction.DONATE, reason, expiry.isExpired(item), by, at)
        }

    /** O destino [change] no item travado, na ordem: permissão, trava, `404`, versão (`412`) e a regra do domínio. */
    private fun act(
        id: LostAndFoundItemId,
        expectedVersion: Long,
        change: (LostAndFoundItem, Expiry, UserId, Instant) -> Unit,
    ): LostAndFoundItemView {
        authorization.require(HousekeepingPermissions.LOSTFOUND_MANAGE)
        val item = store.findForUpdate(id) ?: throw notFound()
        requireCurrentVersion(expectedVersion, item.version)
        audit.track(HousekeepingAudit.LOST_FOUND_ITEM, item.id.value) { item.auditState() }
        val expiry = Expiry.current(hotel)
        change(item, expiry, actor.current().userId, clock.instant())
        store.update(item)
        return expiry.view(item, showReturnedTo = true)
    }

    /**
     * O escopo de leitura: quem gere os achados lê todos; senão, pela `housekeeping.occurrences.report` (`403` sem
     * ela; com `OWN`, só os próprios).
     */
    private fun readScope(): ScopeFilter =
        if (canManage()) ScopeFilter.All else authorization.scopeFilter(HousekeepingPermissions.OCCURRENCES_REPORT)

    private fun canManage(): Boolean = authorization.grantOrNull(HousekeepingPermissions.LOSTFOUND_MANAGE) != null

    private fun requireLocationExists(item: LostAndFoundItem) {
        val (exists, field) =
            item.roomId?.let { (rooms.room(it) != null) to "roomId" }
                ?: ((rooms.area(checkNotNull(item.areaId)) != null) to "areaId")
        if (!exists) {
            throw HousekeepingRules.invalid(
                HousekeepingErrorCodes.HOUSEKEEPING_OCCURRENCE_UNKNOWN_LOCATION,
                field,
                "O local da ocorrência não existe.",
            )
        }
    }

    private fun notFound() =
        NotFoundException(
            HousekeepingErrorCodes.HOUSEKEEPING_LOST_FOUND_NOT_FOUND,
            "Item de achados e perdidos não encontrado.",
        )
}

/** O prazo de guarda vigente: a data operacional, o fuso do hotel e os dias de guarda (D2-E06). */
private class Expiry(
    private val businessDate: LocalDate,
    private val zone: ZoneId,
    private val retentionDays: Int,
) {
    val cutoff: Instant = LostAndFoundItem.expiryCutoff(businessDate, zone, retentionDays)

    fun isExpired(item: LostAndFoundItem): Boolean = item.isExpired(businessDate, zone, retentionDays)

    fun view(
        item: LostAndFoundItem,
        showReturnedTo: Boolean,
    ) = LostAndFoundItemView(
        item.id,
        item.roomId,
        item.areaId,
        item.description,
        item.storagePlace,
        item.foundAt,
        item.reportedBy,
        item.source,
        item.status,
        isExpired(item),
        item.returnedTo.takeIf { showReturnedTo },
        item.disposalReason,
        item.resolvedAt,
        item.resolvedBy,
        item.createdAt,
        item.updatedAt,
        item.version,
    )

    companion object {
        fun current(hotel: HotelApi) =
            Expiry(
                hotel.currentBusinessDate(),
                hotel.timezone(),
                hotel.operationalSettings().lostAndFoundRetentionDays,
            )
    }
}
