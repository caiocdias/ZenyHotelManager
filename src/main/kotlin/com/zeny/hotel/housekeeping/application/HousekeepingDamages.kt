package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.FolioOwner
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.billing.FolioStatus
import com.zeny.hotel.billing.NewPosting
import com.zeny.hotel.billing.PostingId
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.billing.TransactionCodeKind
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.DamageReportId
import com.zeny.hotel.housekeeping.DamageReportStatus
import com.zeny.hotel.housekeeping.HousekeepingErrorCodes
import com.zeny.hotel.housekeeping.HousekeepingPermissions
import com.zeny.hotel.housekeeping.OccurrenceSource
import com.zeny.hotel.housekeeping.TaskSource
import com.zeny.hotel.housekeeping.domain.DamageReport
import com.zeny.hotel.housekeeping.domain.HousekeepingRules
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.ScopeFilter
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.requireCurrentVersion
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** A chave da ordenação da lista de danos: o instante do fato (desc) e id (desc). */
data class DamageReportPageKey(
    val occurredAt: Instant,
    val id: UUID,
)

/** Persistência dos danos (D4-E06), na transação corrente. */
interface DamageReportStore {
    fun insert(report: DamageReport)

    fun find(id: DamageReportId): DamageReport?

    /** O dano com a linha travada (`FOR UPDATE`), ou `null` se não existe. */
    fun findForUpdate(id: DamageReportId): DamageReport?

    /** Grava a revisão, a cobrança ou a dispensa do dano travado e sobe a versão; a nova já vale em [report]. */
    fun update(report: DamageReport)

    /** Até [limit] danos depois de [after], do fato mais recente ao mais antigo; [ownerId], só os dele. */
    fun page(
        ownerId: UUID?,
        after: DamageReportPageKey?,
        limit: Int,
    ): List<DamageReport>
}

/** O registro online de um dano: a UH **ou** a área, a descrição e o custo estimado (opcional). */
data class DamageReportRegistration(
    val roomId: RoomId?,
    val areaId: AreaId?,
    val description: String?,
    val estimatedCost: Money?,
)

/**
 * Um dano a registrar pelas entradas sem autorização própria (o registro online depois da permissão, o comando offline
 * e o ouvinte do checklist da Task 3): o [id] (no offline, o `commandId`), o instante do fato [occurredAt] e, quando
 * vem de uma tarefa de governança, a origem dela ([taskSource]): a de saída (`CHECKOUT`) e a de troca de UH
 * (`ROOM_MOVE`) nunca vinculam a reserva (D4-E06).
 */
data class DamageReportDraft(
    val id: DamageReportId,
    val roomId: RoomId?,
    val areaId: AreaId?,
    val description: String?,
    val estimatedCost: Money?,
    val occurredAt: Instant,
    val reportedBy: UserId,
    val source: OccurrenceSource,
    val taskSource: TaskSource? = null,
)

/** A revisão de um dano: o valor final, o código de lançamento e a reserva vinculada (nula retira o vínculo). */
data class DamageReview(
    val finalAmount: Money,
    val transactionCodeId: TransactionCodeId,
    val reservationId: ReservationId?,
)

/**
 * Um dano para a resposta. [reservationId], [folioId] e [postingId] só para quem tem `housekeeping.damages.manage`
 * (nulos para os outros, RN-13): a camareira não vê a reserva nem a conta.
 */
data class DamageReportView(
    val id: DamageReportId,
    val roomId: RoomId?,
    val areaId: AreaId?,
    val description: String,
    val estimatedCost: Money?,
    val occurredAt: Instant,
    val reportedBy: UserId,
    val source: OccurrenceSource,
    val status: DamageReportStatus,
    val reservationId: ReservationId?,
    val finalAmount: Money?,
    val transactionCodeId: TransactionCodeId?,
    val reviewedBy: UserId?,
    val reviewedAt: Instant?,
    val waiverReason: String?,
    val folioId: FolioId?,
    val postingId: PostingId?,
    val resolvedAt: Instant?,
    val resolvedBy: UserId?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

/**
 * Danos (RF-GOV-09, D4-E06): o registro (com o vínculo sugerido à reserva pelo instante do fato), a consulta, a
 * revisão, a cobrança na conta da reserva e a dispensa. Toda ação exige a permissão antes de qualquer recusa que
 * dependa do dano:
 * - registrar, `housekeeping.occurrences.report` (qualquer escopo; quem registra é o dono);
 * - consultar, `housekeeping.damages.manage` (todos, com a reserva e a conta) ou `housekeeping.occurrences.report` (com
 *   `OWN`, só os próprios; o de outro dá o mesmo `404` do inexistente), sem a reserva nem a conta (RN-13);
 * - revisar, cobrar e dispensar, `housekeeping.damages.manage`; depois a trava do dano (`FOR UPDATE`), `404`, a versão
 *   (`412`) e o status (`409`).
 *
 * **Vínculo sugerido:** a reserva hospedada hoje cuja alocação vigente é a UH do dano, só se ela entrou nesta UH (o
 * maior entre o check-in e a alocação, o que cobre a troca de UH) até o instante do fato; nunca numa área nem a partir
 * de uma tarefa de saída ou de troca de UH (Review Focus 6).
 *
 * **Cobrança:** trava o dano antes de chamar o `billing` (a segunda cobrança espera e vê o dano cobrado) e lança pela
 * [BillingApi.post] com a chave `housekeeping.damage:<id>` (idempotente, Review Focus 1), sem taxa de serviço, com a
 * descrição fixa "Dano na UH <número>" (sem o texto livre do dano), na data operacional corrente.
 */
@Service
@Suppress("LongParameterList", "TooManyFunctions") // as portas dos danos; uma função por ação
class HousekeepingDamages(
    private val store: DamageReportStore,
    private val rooms: RoomsApi,
    private val reservations: ReservationsApi,
    private val billing: BillingApi,
    private val hotel: HotelApi,
    private val authorization: Authorization,
    private val actor: CurrentActor,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    /**
     * Registra um dano achado agora, `REPORTED`, com origem `MANUAL`. Local inexistente: `400
     * HOUSEKEEPING_OCCURRENCE_UNKNOWN_LOCATION` no campo `roomId` ou `areaId` (H16).
     */
    @Audited(HousekeepingAudit.REGISTER_DAMAGE_REPORT)
    @Transactional
    fun register(registration: DamageReportRegistration): DamageReportView {
        authorization.require(HousekeepingPermissions.OCCURRENCES_REPORT)
        val report =
            insert(
                DamageReportDraft(
                    DamageReportId.new(),
                    registration.roomId,
                    registration.areaId,
                    registration.description,
                    registration.estimatedCost,
                    clock.instant(),
                    actor.current().userId,
                    OccurrenceSource.MANUAL,
                ),
            )
        return view(report, canManage())
    }

    /**
     * Registra o dano [draft] **sem conferir permissão** (quem chama já se autorizou ou é o próprio sistema): a entrada
     * do comando offline e do ouvinte do checklist. Mesmas regras do [register], com o instante do fato do rascunho.
     */
    @Audited(HousekeepingAudit.REGISTER_DAMAGE_REPORT)
    @Transactional
    fun record(draft: DamageReportDraft): DamageReportView = view(insert(draft), showLinks = false)

    @Transactional(readOnly = true)
    fun list(page: PageParams): CursorPage<DamageReportView> {
        val owner = readScope().ownerId
        val after = page.decodedCursor()?.let { DamageReportPageKey(it.instant(0), it.uuid(1)) }
        val rows = store.page(owner, after, page.limit + 1)
        val showLinks = canManage()
        return CursorPage
            .fromRows(rows, page.limit) { PageCursor.of(it.occurredAt.toString(), it.id.toString()) }
            .map { view(it, showLinks) }
    }

    @Transactional(readOnly = true)
    fun get(id: DamageReportId): DamageReportView {
        val owner = readScope().ownerId
        val report = store.find(id)?.takeIf { owner == null || it.reportedBy.value == owner } ?: throw notFound()
        return view(report, canManage())
    }

    /**
     * Revisa o dano: valor final (> 0, `400` de formato, antes da permissão), código ativo de tipo `CHARGE` ou
     * `PENALTY` (inexistente `400 HOUSEKEEPING_DAMAGE_UNKNOWN_TRANSACTION_CODE`; senão `409
     * HOUSEKEEPING_DAMAGE_TRANSACTION_CODE_INVALID`) e a reserva vinculada, que a revisão substitui inteira (a mesma
     * confirma; outra precisa existir, `400 HOUSEKEEPING_DAMAGE_UNKNOWN_RESERVATION`, e estar hospedada, `409
     * HOUSEKEEPING_DAMAGE_RESERVATION_INVALID`, em qualquer UH; nula retira). Os `400` de id inexistente dependem de
     * consulta: vêm depois da permissão e do status.
     */
    @Audited(HousekeepingAudit.REVIEW_DAMAGE_REPORT)
    @Transactional
    fun review(
        id: DamageReportId,
        review: DamageReview,
        expectedVersion: Long,
    ): DamageReportView {
        DamageReport.requireFinalAmount(review.finalAmount)
        return act(id, expectedVersion) { report, by, at ->
            if (report.status == DamageReportStatus.REPORTED || report.status == DamageReportStatus.REVIEWED) {
                requireChargeCode(review.transactionCodeId)
                if (review.reservationId != null && review.reservationId != report.reservationId) {
                    requireInHouse(review.reservationId, report.roomId)
                }
            }
            report.review(review.finalAmount, review.transactionCodeId, review.reservationId, by, at)
        }
    }

    /**
     * Cobra o dano revisado na conta [folioId] da reserva vinculada: inexistente `400
     * HOUSEKEEPING_DAMAGE_UNKNOWN_FOLIO` (depois da permissão e do status); encerrada ou de outro dono `409
     * HOUSEKEEPING_DAMAGE_FOLIO_INVALID`. Um lançamento só (trava do dano e chave idempotente); a conta gravada é a
     * final, depois do roteamento do `billing`.
     */
    @Audited(HousekeepingAudit.CHARGE_DAMAGE_REPORT)
    @Transactional
    fun charge(
        id: DamageReportId,
        folioId: FolioId,
        expectedVersion: Long,
    ): DamageReportView =
        act(id, expectedVersion) { report, by, at ->
            val reservation = report.requireChargeable()
            val folio =
                billing.folio(folioId)
                    ?: throw HousekeepingRules.invalid(
                        HousekeepingErrorCodes.HOUSEKEEPING_DAMAGE_UNKNOWN_FOLIO,
                        "folioId",
                        "A conta não existe.",
                    )
            if (folio.status != FolioStatus.OPEN ||
                folio.owner != FolioOwner(FolioOwnerType.RESERVATION, reservation.value)
            ) {
                throw ConflictException(
                    HousekeepingErrorCodes.HOUSEKEEPING_DAMAGE_FOLIO_INVALID,
                    "Informe uma conta aberta da reserva vinculada ao dano.",
                )
            }
            val reviewed = report.reviewed()
            val room = checkNotNull(report.roomId) { "Dano ${report.id} com reserva e sem UH" }
            val number = checkNotNull(rooms.room(room)) { "A UH $room do dano ${report.id} não existe" }.number
            val posting =
                billing.post(
                    folioId,
                    NewPosting(
                        transactionCodeId = reviewed.transactionCodeId,
                        unitPrice = reviewed.finalAmount,
                        description = "Dano na UH $number",
                        source = PostingSource.MANUAL,
                        serviceChargeRate = Percent.ZERO,
                        sourceReference = "$SOURCE_REFERENCE_PREFIX${report.id}",
                    ),
                )
            report.charged(posting.folioId, posting.id, by, at)
        }

    /** Dispensa o dano (`REPORTED|REVIEWED`) com o [reason] (até 500), sem lançar. */
    @Audited(HousekeepingAudit.WAIVE_DAMAGE_REPORT)
    @Transactional
    fun waive(
        id: DamageReportId,
        reason: String?,
        expectedVersion: Long,
    ): DamageReportView = act(id, expectedVersion) { report, by, at -> report.waive(reason, by, at) }

    private fun insert(draft: DamageReportDraft): DamageReport {
        val now = clock.instant()
        requireLocationExists(draft.roomId, draft.areaId)
        val report =
            DamageReport.register(
                draft.id,
                draft.roomId,
                draft.areaId,
                draft.description,
                draft.estimatedCost,
                draft.occurredAt,
                draft.reportedBy,
                draft.source,
                suggestedReservation(draft),
                now,
            )
        store.insert(report)
        audit.created(HousekeepingAudit.DAMAGE_REPORT, report.id.value) { report.auditState() }
        return report
    }

    /**
     * A reserva hospedada hoje com a alocação vigente na UH do dano, que entrou nela (o maior entre o check-in e a
     * alocação) até o instante do fato; a de entrada mais recente, se houver mais de uma. Nenhuma numa área, numa UH
     * vaga ou a partir de uma tarefa de saída ou de troca de UH (D4-E06, Review Focus 6).
     */
    private fun suggestedReservation(draft: DamageReportDraft): ReservationId? {
        val room = draft.roomId?.takeUnless { draft.taskSource in DEPARTURE_TASKS } ?: return null
        val inRoom =
            reservations
                .assignedRooms(reservations.inHouse(hotel.currentBusinessDate()))
                .filterValues { it == room }
                .keys
        return inRoom
            .mapNotNull { id ->
                val summary = reservations.reservation(id) ?: return@mapNotNull null
                val checkIn = summary.checkIn?.at ?: return@mapNotNull null
                val assigned =
                    summary.roomAssignment?.takeIf { it.roomId == room }?.assignedAt ?: return@mapNotNull null
                id to maxOf(checkIn, assigned)
            }.filter { (_, entered) -> !entered.isAfter(draft.occurredAt) }
            .maxByOrNull { (_, entered) -> entered }
            ?.first
    }

    /** O ato [change] no dano travado, na ordem: permissão, trava, `404`, versão (`412`) e a regra do domínio. */
    private fun act(
        id: DamageReportId,
        expectedVersion: Long,
        change: (DamageReport, UserId, Instant) -> Unit,
    ): DamageReportView {
        authorization.require(HousekeepingPermissions.DAMAGES_MANAGE)
        val report = store.findForUpdate(id) ?: throw notFound()
        requireCurrentVersion(expectedVersion, report.version)
        audit.track(HousekeepingAudit.DAMAGE_REPORT, report.id.value) { report.auditState() }
        change(report, actor.current().userId, clock.instant())
        store.update(report)
        return view(report, showLinks = true)
    }

    private fun requireChargeCode(id: TransactionCodeId) {
        val code =
            billing.transactionCode(id)
                ?: throw HousekeepingRules.invalid(
                    HousekeepingErrorCodes.HOUSEKEEPING_DAMAGE_UNKNOWN_TRANSACTION_CODE,
                    "transactionCodeId",
                    "O código de lançamento não existe.",
                )
        if (!code.active || code.kind !in CHARGEABLE_KINDS) {
            throw ConflictException(
                HousekeepingErrorCodes.HOUSEKEEPING_DAMAGE_TRANSACTION_CODE_INVALID,
                "Informe um código de lançamento ativo de consumo (CHARGE) ou multa (PENALTY).",
            )
        }
    }

    private fun requireInHouse(
        id: ReservationId,
        roomId: RoomId?,
    ) {
        val reservation =
            reservations.reservation(id)
                ?: throw HousekeepingRules.invalid(
                    HousekeepingErrorCodes.HOUSEKEEPING_DAMAGE_UNKNOWN_RESERVATION,
                    "reservationId",
                    "A reserva não existe.",
                )
        if (roomId == null || reservation.status != ReservationStatus.IN_HOUSE) {
            throw ConflictException(
                HousekeepingErrorCodes.HOUSEKEEPING_DAMAGE_RESERVATION_INVALID,
                "Informe uma reserva hospedada para vincular ao dano de UH.",
            )
        }
    }

    /**
     * O escopo de leitura: quem gere os danos lê todos; senão, pela `housekeeping.occurrences.report` (`403` sem ela;
     * com `OWN`, só os próprios).
     */
    private fun readScope(): ScopeFilter =
        if (canManage()) ScopeFilter.All else authorization.scopeFilter(HousekeepingPermissions.OCCURRENCES_REPORT)

    private fun canManage(): Boolean = authorization.grantOrNull(HousekeepingPermissions.DAMAGES_MANAGE) != null

    private fun requireLocationExists(
        roomId: RoomId?,
        areaId: AreaId?,
    ) {
        HousekeepingRules.requireOneLocation(roomId, areaId)
        val (exists, field) =
            roomId?.let { (rooms.room(it) != null) to "roomId" }
                ?: ((rooms.area(checkNotNull(areaId)) != null) to "areaId")
        if (!exists) {
            throw HousekeepingRules.invalid(
                HousekeepingErrorCodes.HOUSEKEEPING_OCCURRENCE_UNKNOWN_LOCATION,
                field,
                "O local da ocorrência não existe.",
            )
        }
    }

    private fun notFound() =
        NotFoundException(HousekeepingErrorCodes.HOUSEKEEPING_DAMAGE_NOT_FOUND, "Dano não encontrado.")

    private fun view(
        report: DamageReport,
        showLinks: Boolean,
    ) = DamageReportView(
        report.id,
        report.roomId,
        report.areaId,
        report.description,
        report.estimatedCost,
        report.occurredAt,
        report.reportedBy,
        report.source,
        report.status,
        report.reservationId.takeIf { showLinks },
        report.review?.finalAmount,
        report.review?.transactionCodeId,
        report.review?.reviewedBy,
        report.review?.reviewedAt,
        report.waiverReason,
        report.charge?.folioId.takeIf { showLinks },
        report.charge?.postingId.takeIf { showLinks },
        report.resolvedAt,
        report.resolvedBy,
        report.createdAt,
        report.updatedAt,
        report.version,
    )

    private companion object {
        /** A chave de origem do lançamento do dano (ids só, sem dado pessoal): `housekeeping.damage:<id>`. */
        const val SOURCE_REFERENCE_PREFIX = "housekeeping.damage:"

        val CHARGEABLE_KINDS = setOf(TransactionCodeKind.CHARGE, TransactionCodeKind.PENALTY)

        /** As tarefas de saída da UH: a reserva já não está nela (D4-E06). */
        val DEPARTURE_TASKS = setOf(TaskSource.CHECKOUT, TaskSource.ROOM_MOVE)
    }
}
