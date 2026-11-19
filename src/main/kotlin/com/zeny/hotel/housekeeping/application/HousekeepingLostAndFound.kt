package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.HousekeepingErrorCodes
import com.zeny.hotel.housekeeping.HousekeepingNotificationTypes
import com.zeny.hotel.housekeeping.HousekeepingPermissions
import com.zeny.hotel.housekeeping.LostAndFoundItemId
import com.zeny.hotel.housekeeping.LostAndFoundStatus
import com.zeny.hotel.housekeeping.OccurrenceSource
import com.zeny.hotel.housekeeping.domain.HousekeepingRules
import com.zeny.hotel.housekeeping.domain.LostAndFoundAction
import com.zeny.hotel.housekeeping.domain.LostAndFoundItem
import com.zeny.hotel.notifications.NotificationRecipients
import com.zeny.hotel.notifications.NotificationsApi
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
import java.time.format.DateTimeFormatter
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

    /** Grava o destino ou o local de guarda do item travado e sobe a versão; a nova já vale em [item] na volta. */
    fun update(item: LostAndFoundItem)

    /** O item registrado pelo item [itemKey] da execução de checklist [execution], ou `null` (D5-E06). */
    fun findByChecklistItem(
        execution: ChecklistExecutionId,
        itemKey: String,
    ): LostAndFoundItem?

    /**
     * Marca o aviso de vencido (`expiry_notified_at` = [at]) dos itens guardados achados antes de [cutoff] ainda não
     * avisados e os devolve, do achado mais antigo ao mais novo (D3-E06): cada item só é devolvido uma vez.
     */
    fun markExpiryNotices(
        cutoff: Instant,
        at: Instant,
    ): List<LostAndFoundItem>

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
 * Um achado a registrar pelas entradas sem autorização própria (o comando offline e o ouvinte do checklist, D5 e
 * D6-E06): o [id] (no offline, o `commandId`), o instante [foundAt] (o `occurredAt` corrigido, ou o `completedAt` do
 * checklist) e, na origem `CHECKLIST`, a execução e o item ([checklistExecutionId], [checklistItemKey]).
 */
data class LostAndFoundDraft(
    val id: LostAndFoundItemId,
    val roomId: RoomId?,
    val areaId: AreaId?,
    val description: String?,
    val storagePlace: String?,
    val foundAt: Instant,
    val reportedBy: UserId,
    val source: OccurrenceSource,
    val checklistExecutionId: ChecklistExecutionId? = null,
    val checklistItemKey: String? = null,
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
 * - dar destino ou trocar o local de guarda, `housekeeping.lostfound.manage`; depois a trava do item, `404
 *   HOUSEKEEPING_LOST_FOUND_NOT_FOUND`, a versão (`412`), o status (`409
 *   HOUSEKEEPING_LOST_FOUND_TRANSITION_NOT_ALLOWED`) e, no descarte e na doação, o prazo de guarda (`409
 *   HOUSEKEEPING_LOST_FOUND_NOT_EXPIRED`).
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
    private val notifications: NotificationsApi,
) {
    /**
     * Registra um item achado agora, `STORED`, com origem `MANUAL`. Local inexistente: `400
     * HOUSEKEEPING_OCCURRENCE_UNKNOWN_LOCATION` no campo `roomId` ou `areaId` (H16).
     */
    @Audited(HousekeepingAudit.REGISTER_LOST_FOUND_ITEM)
    @Transactional
    fun register(registration: LostAndFoundRegistration): LostAndFoundItemView {
        authorization.require(HousekeepingPermissions.OCCURRENCES_REPORT)
        val item =
            insert(
                LostAndFoundDraft(
                    LostAndFoundItemId.new(),
                    registration.roomId,
                    registration.areaId,
                    registration.description,
                    registration.storagePlace,
                    clock.instant(),
                    actor.current().userId,
                    OccurrenceSource.MANUAL,
                ),
            )
        return Expiry.current(hotel).view(item, showReturnedTo = canManage())
    }

    /**
     * Registra o achado [draft] **sem conferir permissão** (o sistema): a entrada do ouvinte do checklist. Mesmas
     * regras do [register]; a origem do checklist já registrada (a reentrega do evento) devolve o item gravado, sem
     * outro.
     */
    @Audited(HousekeepingAudit.REGISTER_LOST_FOUND_ITEM)
    @Transactional
    fun record(draft: LostAndFoundDraft): LostAndFoundItemView = recordOffline(draft)

    /**
     * O registro do [draft] dentro do caso de uso auditado de quem chama (o comando offline, que já conferiu a
     * permissão): a resposta não leva o "a quem".
     */
    internal fun recordOffline(draft: LostAndFoundDraft): LostAndFoundItemView =
        Expiry.current(hotel).view(insert(draft), showReturnedTo = false)

    /**
     * O aviso do prazo de guarda (D3-E06), pelo job diário: marca os itens guardados e vencidos ainda não avisados e
     * avisa cada um, uma vez, a quem tem `housekeeping.lostfound.manage`
     * ([HousekeepingNotificationTypes.LOST_FOUND_EXPIRED]), só com o id, a UH ou a área e a data em que foi achado (sem
     * a descrição nem o local de guarda, texto livre). Não dá destino ao item. Devolve quantos foram avisados.
     */
    @Audited(HousekeepingAudit.NOTIFY_LOST_FOUND_EXPIRY)
    @Transactional
    fun notifyExpired(): Int {
        val expiry = Expiry.current(hotel)
        val now = clock.instant()
        val items = store.markExpiryNotices(expiry.cutoff, now)
        if (items.isEmpty()) audit.noChanges()
        items.forEach { item ->
            audit.record(
                HousekeepingAudit.LOST_FOUND_ITEM,
                item.id.value,
                mapOf("expiryNotifiedAt" to null),
                mapOf("expiryNotifiedAt" to now.toString()),
            )
            val location = label(item)
            notifications.notify(
                HousekeepingNotificationTypes.LOST_FOUND_EXPIRED,
                "Achado vencido na $location",
                "O item ${item.id} de achados e perdidos, achado na $location em " +
                    "${DATE.format(item.foundAt.atZone(hotel.timezone()).toLocalDate())}, passou do prazo de guarda: " +
                    "dê a ele o destino (devolução, descarte ou doação).",
                NotificationRecipients.withPermission(HousekeepingPermissions.LOSTFOUND_MANAGE),
            )
        }
        return items.size
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

    /** Define ou troca o local de guarda do item guardado ([storagePlace], até 100). */
    @Audited(HousekeepingAudit.CHANGE_LOST_FOUND_STORAGE_PLACE)
    @Transactional
    fun changeStoragePlace(
        id: LostAndFoundItemId,
        storagePlace: String?,
        expectedVersion: Long,
    ): LostAndFoundItemView = act(id, expectedVersion) { item, _, _, at -> item.changeStoragePlace(storagePlace, at) }

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

    /**
     * Grava o achado do [draft] depois de conferir o local; a origem do checklist já registrada devolve o item gravado,
     * sem outra entrada na trilha (o índice único da origem segura a corrida).
     */
    private fun insert(draft: LostAndFoundDraft): LostAndFoundItem {
        val execution = draft.checklistExecutionId
        if (execution != null) {
            store.findByChecklistItem(execution, checkNotNull(draft.checklistItemKey))?.let {
                audit.noChanges()
                return it
            }
        }
        val item =
            LostAndFoundItem.register(
                draft.id,
                draft.roomId,
                draft.areaId,
                draft.description,
                draft.storagePlace,
                draft.reportedBy,
                draft.foundAt,
                draft.source,
                clock.instant(),
                draft.checklistExecutionId,
                draft.checklistItemKey,
            )
        requireLocationExists(item)
        store.insert(item)
        audit.created(HousekeepingAudit.LOST_FOUND_ITEM, item.id.value) { item.auditState() }
        return item
    }

    /** O local do item no aviso: o número da UH ou o nome da área. */
    private fun label(item: LostAndFoundItem): String =
        item.roomId?.let { "UH ${rooms.room(it)?.number ?: "?"}" }
            ?: "área ${rooms.area(checkNotNull(item.areaId))?.name ?: "?"}"

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

/** A data em que o item foi achado, no aviso do prazo de guarda. */
private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
