package com.zeny.hotel.billing.application

import com.zeny.hotel.billing.BillingErrorCodes
import com.zeny.hotel.billing.BillingNotificationTypes
import com.zeny.hotel.billing.BillingPermissions
import com.zeny.hotel.billing.CashMovementId
import com.zeny.hotel.billing.CashMovementType
import com.zeny.hotel.billing.CashPointId
import com.zeny.hotel.billing.CashierShiftId
import com.zeny.hotel.billing.CashierShiftStatus
import com.zeny.hotel.billing.CashierShiftSummary
import com.zeny.hotel.billing.PaymentMethod
import com.zeny.hotel.billing.domain.BillingRules
import com.zeny.hotel.billing.domain.CashMovement
import com.zeny.hotel.billing.domain.CashPoint
import com.zeny.hotel.billing.domain.CashierClosing
import com.zeny.hotel.billing.domain.CashierDifferenceNotice
import com.zeny.hotel.billing.domain.CashierShift
import com.zeny.hotel.billing.domain.ShiftExpectation
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.notifications.ModuleNotificationTypes
import com.zeny.hotel.notifications.NotificationRecipients
import com.zeny.hotel.notifications.NotificationsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.DocumentNumberGenerator
import com.zeny.hotel.shared.DocumentNumberType
import com.zeny.hotel.shared.GrantedPermission
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.requireCurrentVersion
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Os tipos de notificação do módulo no catálogo do `notifications` (H14). */
@Configuration(proxyBeanMethods = false)
class BillingNotificationsConfiguration {
    @Bean
    fun billingNotificationTypes(): ModuleNotificationTypes = BillingNotificationTypes
}

/** Um ponto de caixa lido. */
data class CashPointView(
    val id: CashPointId,
    val name: String,
    val blindClose: Boolean,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

/** Um turno de caixa numa listagem (sem movimentos nem esperado; a diferença total só depois do fechamento). */
data class CashierShiftListView(
    val id: CashierShiftId,
    val number: DocumentNumber,
    val cashPointId: CashPointId,
    val operatorId: UserId,
    val status: CashierShiftStatus,
    val blindClose: Boolean,
    val openingFloat: Money,
    val openedOn: LocalDate,
    val openedAt: Instant,
    val closedOn: LocalDate?,
    val closedAt: Instant?,
    val differenceTotal: Money?,
)

/** Uma sangria ou um suprimento lido. */
data class CashMovementView(
    val id: CashMovementId,
    val shiftId: CashierShiftId,
    val type: CashMovementType,
    val amount: Money,
    val reason: String,
    val businessDate: LocalDate,
    val recordedAt: Instant,
    val recordedBy: UserId,
)

/** O esperado de uma forma de pagamento, com a composição. */
data class ExpectedAmountView(
    val method: PaymentMethod,
    val openingFloat: Money,
    val supplies: Money,
    val withdrawals: Money,
    val payments: Money,
    val expected: Money,
)

/** A contagem do fechamento de uma forma de pagamento. */
data class CashCountView(
    val method: PaymentMethod,
    val declared: Money,
    val expected: Money,
    val difference: Money,
)

/**
 * Um turno de caixa lido, com os movimentos. [expected] e [expectedTotal] são o esperado por forma de pagamento,
 * **nulos** quando não é visível a quem consulta ([expectedVisible] falso: o fechamento cego antes da contagem, para
 * quem não confere os turnos). [counts] e os totais do fechamento são nulos num turno aberto.
 */
data class CashierShiftView(
    val id: CashierShiftId,
    val number: DocumentNumber,
    val cashPointId: CashPointId,
    val cashPointName: String,
    val operatorId: UserId,
    val status: CashierShiftStatus,
    val blindClose: Boolean,
    val openingFloat: Money,
    val openedOn: LocalDate,
    val openedAt: Instant,
    val closedOn: LocalDate?,
    val closedAt: Instant?,
    val movements: List<CashMovementView>,
    val expectedVisible: Boolean,
    val expected: List<ExpectedAmountView>?,
    val expectedTotal: Money?,
    val counts: List<CashCountView>?,
    val declaredTotal: Money?,
    val differenceTotal: Money?,
    val hasDifference: Boolean?,
    val version: Long,
)

/** O relatório de fechamento de um turno (RF-CTA-09; o PDF é do M5). */
data class CashierShiftReport(
    val shift: CashierShiftView,
    val generatedAt: Instant,
)

/** A contagem declarada no fechamento: o valor de cada forma informada (as omitidas valem zero). */
data class DeclaredCount(
    val method: PaymentMethod,
    val amount: Money,
)

/**
 * Pontos de caixa (RF-CTA-09, M3 E07): consultar exige `billing.shifts.read` (qualquer escopo: quem opera escolhe o
 * ponto) ou `billing.cashpoints.manage`; escrever, `billing.cashpoints.manage`. O nome é único sem diferenciar
 * maiúsculas (`409 CASH_POINT_NAME_ALREADY_IN_USE`, inclusive na corrida); nada é apagado, só desativado.
 */
@Service
class CashPointService(
    private val store: CashierStore,
    private val directory: CashierDirectory,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(
        active: Boolean?,
        page: PageParams,
    ): CursorPage<CashPointView> {
        requireRead()
        val after = page.decodedCursor()?.let { it.string(0) to it.uuid(1) }
        val rows = directory.cashPoints(active, after, page.limit + 1)
        val cursorPage = CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.first, it.second.toString()) }
        val found = store.cashPoints(cursorPage.items.map { it.second }).associateBy { it.id.value }
        return CursorPage(cursorPage.items.mapNotNull { found[it.second]?.view() }, cursorPage.nextCursor)
    }

    @Transactional(readOnly = true)
    fun get(id: CashPointId): CashPointView {
        requireRead()
        return find(id).view()
    }

    @Audited(BillingAudit.CREATE_CASH_POINT)
    @Transactional
    fun create(
        name: String,
        blindClose: Boolean,
    ): CashPointView {
        authorization.require(BillingPermissions.CASH_POINTS_MANAGE)
        val cashPoint = CashPoint.create(name, blindClose, now())
        audit.created(BillingAudit.CASH_POINT, cashPoint.id.value) { cashPoint.auditState() }
        if (!store.tryAdd(cashPoint)) throw nameInUse()
        return cashPoint.view()
    }

    @Audited(BillingAudit.UPDATE_CASH_POINT)
    @Transactional
    fun update(
        id: CashPointId,
        expectedVersion: Long,
        name: String,
        blindClose: Boolean,
    ): CashPointView {
        authorization.require(BillingPermissions.CASH_POINTS_MANAGE)
        val cashPoint = find(id)
        requireCurrentVersion(expectedVersion, checkNotNull(cashPoint.version))
        audit.track(BillingAudit.CASH_POINT, id.value) { cashPoint.auditState() }
        if (cashPoint.update(name, blindClose, now()) && !store.tryFlushCashPoint()) throw nameInUse()
        return cashPoint.view()
    }

    @Audited(BillingAudit.DEACTIVATE_CASH_POINT)
    @Transactional
    fun deactivate(id: CashPointId): CashPointView {
        authorization.require(BillingPermissions.CASH_POINTS_MANAGE)
        val cashPoint = find(id)
        audit.track(BillingAudit.CASH_POINT, id.value) { cashPoint.auditState() }
        if (cashPoint.deactivate(now())) store.flush()
        return cashPoint.view()
    }

    @Audited(BillingAudit.REACTIVATE_CASH_POINT)
    @Transactional
    fun reactivate(id: CashPointId): CashPointView {
        authorization.require(BillingPermissions.CASH_POINTS_MANAGE)
        val cashPoint = find(id)
        audit.track(BillingAudit.CASH_POINT, id.value) { cashPoint.auditState() }
        if (cashPoint.reactivate(now())) store.flush()
        return cashPoint.view()
    }

    private fun requireRead() {
        authorization.grantOrNull(BillingPermissions.CASH_POINTS_MANAGE)
            ?: authorization.require(BillingPermissions.SHIFTS_READ)
    }

    private fun find(id: CashPointId): CashPoint = store.cashPoint(id) ?: throw cashPointNotFound()

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private fun nameInUse() =
        ConflictException(
            BillingErrorCodes.CASH_POINT_NAME_ALREADY_IN_USE,
            "Já existe um ponto de caixa com este nome; se estiver desativado, reative-o.",
        )
}

/**
 * Turnos de caixa (RF-CTA-09 e 10, H13 e H14 do M3, M3 E07).
 *
 * - **Operar** (`billing.cashier.operate`): abrir o próprio turno num ponto ativo, com o fundo de troco (no máximo um
 *   aberto por usuário, em qualquer ponto: `409 CASHIER_SHIFT_ALREADY_OPEN`, inclusive em duas aberturas simultâneas,
 *   pelo índice único parcial), registrar sangria e suprimento (em dinheiro, com motivo) e fechar com a contagem por
 *   forma de pagamento. Só o operador move e fecha o turno (`403 CASHIER_SHIFT_OPERATOR_REQUIRED`), com a linha do
 *   turno travada (os recebimentos da E08 também a travam, por `BillingApi.requireOpenShift`).
 * - **Consultar** (`billing.shifts.read`): com `OWN`, só os próprios turnos (filtro na SQL; o de outro, `404`).
 * - **Fechamento cego** ([CashierShift.expectedVisible]): num turno cego aberto, o esperado não sai a ninguém além de
 *   quem tem `billing.shifts.review`; depois do fechamento, sai a todos que leem o turno, com a diferença por forma.
 * - **Diferença** (H14): o fechamento com alguma diferença notifica, no mesmo caso de uso (a transação desfeita não
 *   notifica), quem tem `billing.shifts.review`, menos o próprio operador, com o tipo
 *   `billing.cashier-shift-difference` e um texto sem dado pessoal ([CashierDifferenceNotice]).
 */
@Service
@Suppress("LongParameterList", "TooManyFunctions") // as portas do caixa; os casos de uso de um recurso só
class CashierShiftService(
    private val store: CashierStore,
    private val ledger: CashierLedger,
    private val directory: CashierDirectory,
    private val numbers: DocumentNumberGenerator,
    private val hotel: HotelApi,
    private val notifications: NotificationsApi,
    private val authorization: Authorization,
    private val currentActor: CurrentActor,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(
        filter: CashierShiftFilter,
        page: PageParams,
    ): CursorPage<CashierShiftListView> {
        val owner = authorization.scopeFilter(BillingPermissions.SHIFTS_READ).ownerId
        val ids = directory.shifts(filter, owner, page.decodedCursor()?.uuid(0), page.limit + 1)
        val cursorPage = CursorPage.fromRows(ids, page.limit) { PageCursor.of(it.toString()) }
        return CursorPage(store.shifts(cursorPage.items).map { it.listView() }, cursorPage.nextCursor)
    }

    @Transactional(readOnly = true)
    fun get(id: CashierShiftId): CashierShiftView = view(readable(id))

    /** O turno aberto do próprio usuário (`404 CASHIER_SHIFT_NOT_FOUND` sem turno aberto). */
    @Transactional(readOnly = true)
    fun current(): CashierShiftView {
        authorization.require(BillingPermissions.CASHIER_OPERATE)
        val shift = store.openShiftOf(currentActor.current().userId) ?: throw shiftNotFound()
        return view(shift)
    }

    @Transactional(readOnly = true)
    fun report(id: CashierShiftId): CashierShiftReport {
        val shift = readable(id)
        if (shift.status != CashierShiftStatus.CLOSED) {
            throw ConflictException(
                BillingErrorCodes.CASHIER_SHIFT_NOT_CLOSED,
                "O relatório de fechamento só existe depois do fechamento do turno ${shift.number}.",
            )
        }
        return CashierShiftReport(view(shift), now())
    }

    @Audited(BillingAudit.OPEN_CASHIER_SHIFT)
    @Transactional
    @Suppress("ThrowsCount") // uma recusa por motivo
    fun open(
        cashPointId: CashPointId,
        openingFloat: Money,
    ): CashierShiftView {
        authorization.require(BillingPermissions.CASHIER_OPERATE)
        val operator = currentActor.current().userId
        val cashPoint =
            store.cashPoint(cashPointId)
                ?: throw BillingRules.invalid(
                    BillingErrorCodes.UNKNOWN_CASH_POINT,
                    "cashPointId",
                    "Ponto de caixa inexistente.",
                )
        CashierShift.requireActive(cashPoint)
        if (store.openShiftOf(operator) != null) throw alreadyOpen()
        val businessDate = hotel.currentBusinessDate()
        val shift =
            CashierShift.open(
                numbers.next(SHIFT_NUMBER, businessDate.year),
                cashPoint,
                operator,
                openingFloat,
                businessDate,
                now(),
            )
        audit.created(BillingAudit.CASHIER_SHIFT, shift.id.value) { shift.auditState() }
        if (!store.tryAdd(shift)) throw alreadyOpen()
        return view(shift, cashPoint)
    }

    @Audited(BillingAudit.RECORD_CASH_MOVEMENT)
    @Transactional
    fun recordMovement(
        id: CashierShiftId,
        type: CashMovementType,
        amount: Money,
        reason: String?,
    ): CashMovementView {
        authorization.require(BillingPermissions.CASHIER_OPERATE)
        val shift = store.shiftForUpdate(id) ?: throw shiftNotFound()
        val movement =
            CashMovement.record(
                shift,
                type,
                amount,
                reason,
                hotel.currentBusinessDate(),
                now(),
                currentActor.current().userId,
            )
        audit.created(BillingAudit.CASH_MOVEMENT, movement.id.value) { movement.auditState() }
        ledger.add(movement)
        store.markChanged(shift)
        return movement.view()
    }

    /**
     * Fecha o próprio turno com a contagem declarada e devolve o relatório de fechamento (com o esperado, agora
     * visível, e a diferença por forma). Com alguma diferença, notifica quem confere os turnos (menos o operador).
     */
    @Audited(BillingAudit.CLOSE_CASHIER_SHIFT)
    @Transactional
    fun close(
        id: CashierShiftId,
        counts: List<DeclaredCount>,
    ): CashierShiftReport {
        authorization.require(BillingPermissions.CASHIER_OPERATE)
        val shift = store.shiftForUpdate(id) ?: throw shiftNotFound()
        // O operador antes da contagem: quem não é o operador recebe 403, nunca a validação do corpo.
        shift.requireOperator(currentActor.current().userId)
        val declared = CashierClosing.declared(counts.map { it.method to it.amount })
        shift.requireOpen()
        var closing: CashierClosing? = null
        audit.track(BillingAudit.CASHIER_SHIFT, id.value) { shift.auditState(closing) }
        // Os pagamentos do turno entram no esperado na E08; até lá, só o fundo de troco e os movimentos.
        val expectation = ShiftExpectation.of(shift.openingFloat, ledger.movementsOf(id))
        val closedOn = hotel.currentBusinessDate()
        val result = shift.close(declared, expectation, closedOn, now())
        closing = result
        store.flush()
        ledger.addCounts(id, result.counts)
        if (result.hasDifference) notifyDifference(shift, result, closedOn)
        return CashierShiftReport(view(shift), now())
    }

    private fun notifyDifference(
        shift: CashierShift,
        closing: CashierClosing,
        closedOn: LocalDate,
    ) {
        val cashPoint = checkNotNull(store.cashPoint(shift.cashPointId)) { "Ponto ${shift.cashPointId} ausente" }
        notifications.notify(
            BillingNotificationTypes.CASHIER_SHIFT_DIFFERENCE,
            CashierDifferenceNotice.title(shift.number),
            CashierDifferenceNotice.body(shift.number, cashPoint.name, closedOn, closing),
            NotificationRecipients(
                permissions = setOf(BillingPermissions.SHIFTS_REVIEW),
                except = setOf(shift.operatorId),
            ),
        )
    }

    /** O turno que o ator pode ler: com `OWN`, só o próprio (o de outro responde 404, sem revelar que existe). */
    private fun readable(id: CashierShiftId): CashierShift {
        val owner = authorization.scopeFilter(BillingPermissions.SHIFTS_READ).ownerId
        return store.shift(id)?.takeIf { owner == null || it.operatorId.value == owner } ?: throw shiftNotFound()
    }

    private fun view(
        shift: CashierShift,
        cashPoint: CashPoint =
            checkNotNull(store.cashPoint(shift.cashPointId)) { "Ponto ${shift.cashPointId} ausente" },
    ): CashierShiftView {
        val reviewer: GrantedPermission? = authorization.grantOrNull(BillingPermissions.SHIFTS_REVIEW)
        val visible = shift.expectedVisible(reviewer != null)
        val movements = ledger.movementsOf(shift.id)
        val closing = ledger.countsOf(shift.id).takeIf { it.isNotEmpty() }?.let(::CashierClosing)
        val expectation = if (visible) ShiftExpectation.of(shift.openingFloat, movements) else null
        return CashierShiftView(
            shift.id,
            shift.number,
            shift.cashPointId,
            cashPoint.name,
            shift.operatorId,
            shift.status,
            shift.blindClose,
            shift.openingFloat,
            shift.openedOn,
            shift.openedAt,
            shift.closedOn,
            shift.closedAt,
            movements.map { it.view() },
            visible,
            expectation?.methods?.map {
                ExpectedAmountView(it.method, it.openingFloat, it.supplies, it.withdrawals, it.payments, it.expected)
            },
            expectation?.expectedTotal,
            closing?.counts?.map { CashCountView(it.method, it.declared, it.expected, it.difference) },
            closing?.declaredTotal,
            closing?.differenceTotal,
            closing?.hasDifference,
            checkNotNull(shift.version),
        )
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private fun alreadyOpen() =
        ConflictException(
            BillingErrorCodes.CASHIER_SHIFT_ALREADY_OPEN,
            "Você já tem um turno de caixa aberto; feche-o antes de abrir outro.",
        )

    private companion object {
        val SHIFT_NUMBER = DocumentNumberType("CX", "billing.cashier_shift_number_seq")
    }
}

internal fun shiftNotFound() =
    NotFoundException(BillingErrorCodes.CASHIER_SHIFT_NOT_FOUND, "Turno de caixa não encontrado.")

internal fun cashPointNotFound() =
    NotFoundException(BillingErrorCodes.CASH_POINT_NOT_FOUND, "Ponto de caixa não encontrado.")

internal fun CashPoint.view() = CashPointView(id, name, blindClose, active, createdAt, updatedAt, checkNotNull(version))

internal fun CashMovement.view() =
    CashMovementView(id, shiftId, type, amount, reason, businessDate, recordedAt, recordedBy)

internal fun CashierShift.listView() =
    CashierShiftListView(
        id,
        number,
        cashPointId,
        operatorId,
        status,
        blindClose,
        openingFloat,
        openedOn,
        openedAt,
        closedOn,
        closedAt,
        differenceTotal,
    )

internal fun CashierShift.summary() =
    CashierShiftSummary(id, number, cashPointId, operatorId, status, openedOn, openedAt, openingFloat)

/** Retrato da trilha de um ponto de caixa. */
internal fun CashPoint.auditState(): Map<String, Any?> =
    mapOf("name" to name, "blindClose" to blindClose, "active" to active)

/** Retrato da trilha de um turno: ids, situação, datas e valores; no fechamento, a contagem por forma (H10). */
internal fun CashierShift.auditState(closing: CashierClosing? = null): Map<String, Any?> =
    mapOf(
        "number" to number.toString(),
        "cashPointId" to cashPointId.toString(),
        "operatorId" to operatorId.toString(),
        "status" to status.name,
        "blindClose" to blindClose,
        "openingFloat" to openingFloat.amount.toPlainString(),
        "openedOn" to openedOn.toString(),
        "closedOn" to closedOn?.toString(),
        "differenceTotal" to differenceTotal?.amount?.toPlainString(),
        "counts" to
            closing?.counts?.map {
                mapOf(
                    "method" to it.method.name,
                    "declared" to it.declared.amount.toPlainString(),
                    "expected" to it.expected.amount.toPlainString(),
                    "difference" to it.difference.amount.toPlainString(),
                )
            },
    )

/** Retrato da trilha de um movimento: sem o motivo (texto livre, H10). */
internal fun CashMovement.auditState(): Map<String, Any?> =
    mapOf(
        "shiftId" to shiftId.toString(),
        "type" to type.name,
        "amount" to amount.amount.toPlainString(),
        "businessDate" to businessDate.toString(),
    )
