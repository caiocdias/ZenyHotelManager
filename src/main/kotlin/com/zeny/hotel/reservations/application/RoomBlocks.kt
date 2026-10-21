package com.zeny.hotel.reservations.application

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.billing.NewFolio
import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.rates.CancellationPolicySummary
import com.zeny.hotel.rates.GuaranteePolicySummary
import com.zeny.hotel.rates.RatePlanSummary
import com.zeny.hotel.rates.RatesApi
import com.zeny.hotel.rates.RatesErrorCodes
import com.zeny.hotel.rates.SaleRefusal
import com.zeny.hotel.reservations.BlockAllocationRange
import com.zeny.hotel.reservations.BlockAllocationSummary
import com.zeny.hotel.reservations.BookingSummary
import com.zeny.hotel.reservations.NewBooking
import com.zeny.hotel.reservations.NewReservation
import com.zeny.hotel.reservations.NewRoomBlock
import com.zeny.hotel.reservations.NightlyRateSummary
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.ReservationsErrorCodes
import com.zeny.hotel.reservations.ReservationsPermissions
import com.zeny.hotel.reservations.RoomBlockChange
import com.zeny.hotel.reservations.RoomBlockId
import com.zeny.hotel.reservations.RoomBlockStatus
import com.zeny.hotel.reservations.RoomBlockSummary
import com.zeny.hotel.reservations.RoomingList
import com.zeny.hotel.reservations.domain.AllocationTerms
import com.zeny.hotel.reservations.domain.BlockAllocations
import com.zeny.hotel.reservations.domain.INVENTORY_ORDER
import com.zeny.hotel.reservations.domain.InventoryChange
import com.zeny.hotel.reservations.domain.InventoryKey
import com.zeny.hotel.reservations.domain.InventoryNights
import com.zeny.hotel.reservations.domain.InventoryUsage
import com.zeny.hotel.reservations.domain.InventoryUse
import com.zeny.hotel.reservations.domain.RoomBlockCutoff
import com.zeny.hotel.reservations.domain.RoomBlockCutoffNotReachedException
import com.zeny.hotel.reservations.domain.RoomBlockPeriodInPastException
import com.zeny.hotel.reservations.domain.RoomBlockRules
import com.zeny.hotel.reservations.domain.RoomBlockUnavailableException
import com.zeny.hotel.reservations.domain.RoomTypeNotInBlockPlanException
import com.zeny.hotel.reservations.domain.StayNotSellableException
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.DocumentNumberGenerator
import com.zeny.hotel.shared.DocumentNumberType
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.requireCurrentVersion
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * O pick-up de um bloqueio já preparado, com a linha do bloqueio travada: o plano e as políticas do bloqueio (copiadas
 * nas reservas, RN-15), as noites de cada reserva com a tarifa do grupo congelada (RN-14), as mudanças do inventário
 * (cada noite passa uma unidade de `blocked` para `sold`) e as unidades do bloqueio depois do pick-up.
 */
data class BlockPickUpPlan(
    val block: RoomBlockSummary,
    val plan: RatePlanSummary,
    val cancellation: CancellationPolicySummary,
    val guarantee: GuaranteePolicySummary,
    val nights: List<List<NightlyRateSummary>>,
    val inventoryChanges: List<InventoryChange>,
    val allocations: List<BlockAllocationSummary>,
)

/**
 * O pick-up dentro da criação das reservas ([BookingCreation], M4 E11; RF-RES-09): trava a linha do bloqueio (depois
 * das travas dos hóspedes, que a criação já tomou) e confere, na ordem, que o bloqueio está ativo e a data de corte não
 * passou (`409 ROOM_BLOCK_RELEASED`, `422 ROOM_BLOCK_CUTOFF_PASSED`), o formato de cada estadia (sem day use, crianças
 * até a idade limite do hotel: `400 STAY_INVALID`), a chegada a partir da data operacional e a ocupação máxima do tipo
 * de UH (`422` com os códigos do `rates`, como na venda), e que cada noite está no bloqueio
 * (`422 ROOM_BLOCK_DATES_NOT_COVERED`) e ainda tem unidade (`409 ROOM_BLOCK_UNAVAILABLE`, contando todas as reservas do
 * pedido juntas). A tarifa de cada noite é a do grupo naquela noite; a venda pelo `rates` (restrições, canal, preço)
 * não é conferida: o bloqueio já é o contrato com o grupo.
 */
@Component
class RoomBlockPickUps(
    private val store: RoomBlockStore,
    private val rates: RatesApi,
    private val rooms: RoomsApi,
    private val hotel: HotelApi,
    private val audit: AuditContext,
) {
    fun prepare(
        blockId: RoomBlockId,
        command: NewBooking,
    ): BlockPickUpPlan {
        val block = store.blockForUpdate(blockId) ?: throw blockNotFound()
        val businessDate = hotel.currentBusinessDate()
        RoomBlockCutoff.requirePickUpOpen(block, businessDate)
        val plan = checkNotNull(rates.ratePlan(block.ratePlanId)) { "Bloqueio com um plano inexistente" }
        val maxChildAge = hotel.policy().childMaxAge
        command.reservations.forEachIndexed { index, reservation -> requireStay(index, reservation, maxChildAge) }
        val nightsByReservation =
            command.reservations.mapIndexed { index, reservation ->
                requireSale(index, reservation, businessDate)
                BlockAllocations.nights(reservation.roomTypeId, reservation.arrival, reservation.departure)
            }
        val rows = BlockAllocations.byKey(block.allocations)
        val demand = nightsByReservation.flatten().groupingBy { it }.eachCount()
        val allocations = BlockAllocations.pickUp(rows, demand)
        return BlockPickUpPlan(
            block = block,
            plan = plan,
            cancellation =
                checkNotNull(rates.cancellationPolicy(plan.cancellationPolicyId)) { "Plano sem a política" },
            guarantee = checkNotNull(rates.guaranteePolicy(plan.guaranteePolicyId)) { "Plano sem a política" },
            nights = nightsByReservation.map { BlockAllocations.nightlyRates(rows, it, plan.id) },
            inventoryChanges =
                command.reservations.flatMap {
                    listOf(
                        InventoryChange.release(it.roomTypeId, it.arrival, it.departure, 1, InventoryUse.BLOCKED),
                        InventoryChange.take(it.roomTypeId, it.arrival, it.departure, 1, InventoryUse.SOLD),
                    )
                },
            allocations = allocations,
        )
    }

    /** Grava as unidades depois do pick-up (com a versão do bloqueio avançada) e a trilha do bloqueio. */
    fun record(
        plan: BlockPickUpPlan,
        now: Instant,
    ) {
        val before = plan.block
        val merged = BlockAllocations.byKey(before.allocations) + BlockAllocations.byKey(plan.allocations)
        val after =
            before.copy(
                allocations = merged.entries.sortedWith(compareBy(INVENTORY_ORDER) { it.key }).map { it.value },
                version = before.version + 1,
            )
        store.update(after, plan.allocations, emptyList(), now)
        audit.record(
            ReservationsAudit.ROOM_BLOCK,
            before.id.value,
            ReservationsAudit.blockSnapshot(before),
            ReservationsAudit.blockSnapshot(after),
        )
    }

    /** O formato da estadia: noites (sem day use) e as idades das crianças (`400 STAY_INVALID`, com o campo). */
    private fun requireStay(
        index: Int,
        reservation: NewReservation,
        maxChildAge: Int,
    ) {
        val path = "reservations[$index]"
        val violations =
            buildList {
                if (!reservation.departure.isAfter(reservation.arrival)) {
                    add(stayViolation("$path.departure", "A reserva do bloqueio tem ao menos uma noite (sem day use)."))
                }
                reservation.childAges.forEachIndexed { position, age ->
                    if (age !in 0..maxChildAge) {
                        add(
                            stayViolation(
                                "$path.childAges[$position]",
                                "Informe a idade da criança de 0 a $maxChildAge.",
                            ),
                        )
                    }
                }
            }
        if (violations.isNotEmpty()) {
            throw InvalidInputException(ReservationsErrorCodes.STAY_INVALID, violations.first().message, violations)
        }
    }

    /** A chegada a partir da data operacional e a ocupação máxima do tipo de UH (os códigos do `rates`). */
    private fun requireSale(
        index: Int,
        reservation: NewReservation,
        businessDate: LocalDate,
    ) {
        val refusals = mutableListOf<SaleRefusal>()
        if (reservation.arrival.isBefore(businessDate)) {
            refusals +=
                SaleRefusal(
                    RatesErrorCodes.ARRIVAL_BEFORE_BUSINESS_DATE,
                    "A chegada é anterior à data operacional do hotel.",
                )
        }
        val type =
            rooms.roomType(reservation.roomTypeId)
                ?: throw invalid(
                    ReservationsErrorCodes.UNKNOWN_ROOM_TYPE,
                    "reservations[$index].roomTypeId",
                    "O tipo de UH não existe.",
                )
        if (reservation.adults > type.occupancy.maxAdults || reservation.childAges.size > type.occupancy.maxChildren) {
            refusals +=
                SaleRefusal(
                    RatesErrorCodes.OCCUPANCY_EXCEEDS_ROOM_TYPE,
                    "A ocupação passa da máxima do tipo de UH.",
                )
        }
        if (refusals.isNotEmpty()) throw StayNotSellableException(index, refusals)
    }

    private fun stayViolation(
        field: String,
        message: String,
    ) = FieldViolation(field, ReservationsErrorCodes.STAY_INVALID.value, message)
}

/**
 * As unidades do bloqueio no ciclo de vida de uma reserva do pick-up ([ReservationLifecycle], M4 E11; decidida pelo
 * orquestrador em nome do usuário): enquanto o bloqueio está **ativo**, o cancelamento (e a expiração) devolve a
 * unidade ao bloqueio (cada noite a partir da data operacional passa de `sold` para `blocked`, e `pickedUp` diminui),
 * e a reativação a toma do bloqueio de novo (`409 ROOM_BLOCK_UNAVAILABLE` se o bloqueio não tem mais unidade). Com o
 * bloqueio **liberado**, a unidade vai ao inventário geral (e a reativação o consome), e as noites dela passam de
 * `pickedUp` para `released` (e de volta, na reativação). Trava a linha do bloqueio, depois da reserva e antes do
 * inventário. Uma reserva comum só tem as unidades vendidas.
 */
@Component
class BlockUnits(
    private val store: RoomBlockStore,
    private val audit: AuditContext,
) {
    /** As mudanças do inventário do cancelamento da reserva (devolver), já gravando as unidades do bloqueio. */
    @Suppress("ReturnCount") // sem noite futura, reserva comum, bloqueio liberado e bloqueio ativo
    fun onCancellation(
        reservation: ReservationSummary,
        businessDate: LocalDate,
        now: Instant,
    ): List<InventoryChange> {
        val sold =
            InventoryNights.of(reservation.roomTypeId, reservation.arrival, reservation.departure, businessDate, false)
                ?: return emptyList()
        val block = blockOf(reservation) ?: return listOf(sold)
        val nights = BlockAllocations.nights(sold.roomTypeId, sold.from, sold.until)
        val rows = BlockAllocations.byKey(block.allocations)
        if (block.status != RoomBlockStatus.ACTIVE) {
            save(block, BlockAllocations.toInventory(rows, nights), now)
            return listOf(sold)
        }
        save(block, BlockAllocations.giveBack(rows, nights), now)
        return listOf(sold, InventoryChange.take(sold.roomTypeId, sold.from, sold.until, 1, InventoryUse.BLOCKED))
    }

    /** As mudanças do inventário da reativação da reserva (tomar), já gravando as unidades do bloqueio. */
    @Suppress("ReturnCount") // sem noite futura, reserva comum, bloqueio liberado e bloqueio ativo
    fun onReactivation(
        reservation: ReservationSummary,
        businessDate: LocalDate,
        now: Instant,
    ): List<InventoryChange> {
        val sold =
            InventoryNights.of(reservation.roomTypeId, reservation.arrival, reservation.departure, businessDate, true)
                ?: return emptyList()
        val block = blockOf(reservation) ?: return listOf(sold)
        val nights = BlockAllocations.nights(sold.roomTypeId, sold.from, sold.until)
        val rows = BlockAllocations.byKey(block.allocations)
        if (block.status != RoomBlockStatus.ACTIVE) {
            save(block, BlockAllocations.fromInventory(rows, nights), now)
            return listOf(sold)
        }
        val missing = nights.filter { (rows[it]?.remaining ?: 0) < 1 }
        if (missing.isNotEmpty()) throw RoomBlockUnavailableException(missing)
        save(block, BlockAllocations.pickUp(rows, nights.associateWith { 1 }), now)
        return listOf(sold, InventoryChange.release(sold.roomTypeId, sold.from, sold.until, 1, InventoryUse.BLOCKED))
    }

    /** O bloqueio da reserva, travado, se ela veio de um. */
    private fun blockOf(reservation: ReservationSummary): RoomBlockSummary? {
        val id = reservation.blockId ?: return null
        return checkNotNull(store.blockForUpdate(id)) { "Reserva de um bloqueio inexistente" }
    }

    private fun save(
        block: RoomBlockSummary,
        saved: List<BlockAllocationSummary>,
        now: Instant,
    ) {
        if (saved.isEmpty()) return
        val merged = BlockAllocations.byKey(block.allocations) + BlockAllocations.byKey(saved)
        val after =
            block.copy(
                allocations = merged.entries.sortedWith(compareBy(INVENTORY_ORDER) { it.key }).map { it.value },
                version = block.version + 1,
            )
        store.update(after, saved, emptyList(), now)
        audit.record(
            ReservationsAudit.ROOM_BLOCK,
            block.id.value,
            ReservationsAudit.blockSnapshot(block),
            ReservationsAudit.blockSnapshot(after),
        )
    }
}

/**
 * Os bloqueios de grupo pela API (M4 E11; RF-RES-09, RN-16): criar e alterar exigem `reservations.blocks.manage`;
 * consultar, `reservations.blocks.read`; o pick-up e a rooming list, `reservations.blocks.pickup`. A liberação do não
 * utilizado ([releaseUnused]) é só da `ReservationsApi` (o fechamento do dia, M6), nunca automática nesta etapa.
 *
 * - **Criação:** o titular (hóspede pela `GuestsApi`, mesclado vira o sobrevivente e anonimizado é recusado; ou
 *   empresa ativa), o plano (ativo), a data de corte (a partir da data operacional), as unidades por tipo de UH ativo e
 *   noite (a partir da data operacional) tomadas no contador `blocked` numa só `RoomTypeInventory.apply` (sem
 *   disponibilidade, `409 ROOM_TYPE_UNAVAILABLE` e nada gravado) e a conta master do grupo
 *   (`BillingApi.openFolio`, dono `GROUP` com o id do bloqueio, a empresa titular como pagadora), tudo na mesma
 *   transação.
 * - **Alteração:** com o bloqueio travado e o `If-Match`; as unidades pedidas substituem as das noites a partir da data
 *   operacional ([BlockAllocations.replace]), nunca abaixo do já convertido, e a diferença vai ao inventário numa só
 *   `apply`; a tarifa nova vale só para os pick-ups seguintes (as reservas já feitas guardam a congelada).
 * - **Pick-up e rooming list:** a criação das reservas ([BookingCreation]) com o bloqueio ([RoomBlockPickUps]).
 */
@Service
@Suppress("LongParameterList", "TooManyFunctions") // as portas e os casos de uso do bloqueio
class RoomBlockService(
    private val store: RoomBlockStore,
    private val creation: BookingCreation,
    private val guestResolution: GuestResolution,
    private val guests: GuestsApi,
    private val rates: RatesApi,
    private val rooms: RoomsApi,
    private val hotel: HotelApi,
    private val billing: BillingApi,
    private val inventory: RoomTypeInventory,
    private val numbers: DocumentNumberGenerator,
    private val authorization: Authorization,
    private val currentActor: CurrentActor,
    private val views: ReservationViews,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Audited(ReservationsAudit.CREATE_BLOCK)
    @Transactional
    fun create(command: NewRoomBlock): RoomBlockSummary {
        authorization.require(ReservationsPermissions.BLOCKS_MANAGE)
        return doCreate(command)
    }

    /** A criação pela `ReservationsApi` (os eventos do M10), sem permissão (quem chama já se autorizou). */
    @Audited(ReservationsAudit.CREATE_BLOCK)
    @Transactional
    fun createAuthorized(command: NewRoomBlock): RoomBlockSummary = doCreate(command)

    private fun doCreate(command: NewRoomBlock): RoomBlockSummary {
        val units =
            RoomBlockRules.requireValid(
                command.name,
                command.holderGuestId to command.holderCompanyId,
                command.allocations,
                requireAllocations = true,
            )
        val businessDate = hotel.currentBusinessDate()
        requireCutoff(command.cutoffDate, businessDate)
        requireNotPast(units, businessDate)
        val holderGuest =
            command.holderGuestId?.let {
                guestResolution.resolve(listOf(it to "holderGuestId")).getValue(it)
            }
        command.holderCompanyId?.let(::requireCompany)
        val plan = requirePlan(command)
        requireRoomTypes(command.allocations, emptySet(), plan.roomTypeIds)
        inventory.apply(units.map { (key, terms) -> take(key, terms.quantity) })
        val id = RoomBlockId.new()
        val folio =
            billing.openFolio(NewFolio(FolioOwnerType.GROUP, id.value, payerCompanyId = command.holderCompanyId))
        val now = now()
        val block =
            RoomBlockSummary(
                id = id,
                code = numbers.next(BLOCK_NUMBER, businessDate.year),
                name = command.name,
                holderGuestId = holderGuest,
                holderCompanyId = command.holderCompanyId,
                ratePlanId = command.ratePlanId,
                cutoffDate = command.cutoffDate,
                status = RoomBlockStatus.ACTIVE,
                folioId = folio.id,
                allocations =
                    units.map { (key, terms) ->
                        BlockAllocationSummary(key.roomTypeId, key.date, terms.quantity, 0, 0, terms.rate)
                    },
                releasedAt = null,
                releasedOn = null,
                releasedBy = null,
                version = 0,
                createdAt = now,
                createdBy = currentActor.currentOrNull()?.userId,
            )
        store.insert(block)
        audit.record(ReservationsAudit.ROOM_BLOCK, id.value, null, ReservationsAudit.blockSnapshot(block))
        return checkNotNull(store.block(id)) { "Bloqueio recém-gravado não encontrado" }
    }

    @Audited(ReservationsAudit.UPDATE_BLOCK)
    @Transactional
    fun change(
        id: RoomBlockId,
        expectedVersion: Long,
        change: RoomBlockChange,
    ): RoomBlockSummary {
        authorization.require(ReservationsPermissions.BLOCKS_MANAGE)
        val units = RoomBlockRules.requireValid(change.name, null, change.allocations, requireAllocations = false)
        val block = store.blockForUpdate(id) ?: throw blockNotFound()
        requireCurrentVersion(expectedVersion, block.version)
        RoomBlockCutoff.requireActive(block)
        val businessDate = hotel.currentBusinessDate()
        if (change.cutoffDate != block.cutoffDate) requireCutoff(change.cutoffDate, businessDate)
        val plan = checkNotNull(rates.ratePlan(block.ratePlanId)) { "Bloqueio com um plano inexistente" }
        requireRoomTypes(change.allocations, block.allocations.map { it.roomTypeId }.toSet(), plan.roomTypeIds)
        val replacement = BlockAllocations.replace(block.allocations, units, businessDate)
        if (replacement.empty && change.name == block.name && change.cutoffDate == block.cutoffDate) {
            audit.noChanges()
            return block
        }
        inventory.apply(replacement.inventoryChanges)
        val rows =
            BlockAllocations.byKey(block.allocations) - replacement.removed.toSet() +
                BlockAllocations.byKey(replacement.saved)
        val updated =
            block.copy(
                name = change.name,
                cutoffDate = change.cutoffDate,
                allocations = rows.entries.sortedWith(compareBy(INVENTORY_ORDER) { it.key }).map { it.value },
                version = block.version + 1,
            )
        store.update(updated, replacement.saved, replacement.removed, now())
        audit.record(
            ReservationsAudit.ROOM_BLOCK,
            id.value,
            ReservationsAudit.blockSnapshot(block),
            ReservationsAudit.blockSnapshot(updated),
        )
        return checkNotNull(store.block(id))
    }

    @Transactional(readOnly = true)
    fun block(id: RoomBlockId): RoomBlockSummary {
        authorization.require(ReservationsPermissions.BLOCKS_READ)
        return store.block(id) ?: throw blockNotFound()
    }

    @Transactional(readOnly = true)
    fun list(
        status: RoomBlockStatus?,
        page: PageParams,
    ): CursorPage<RoomBlockSummary> {
        authorization.require(ReservationsPermissions.BLOCKS_READ)
        val after = page.decodedCursor()?.let { RoomBlockId(it.uuid(0)) }
        val ids = store.page(status, after, page.limit + 1)
        return CursorPage
            .fromRows(ids, page.limit) { PageCursor.of(it.value.toString()) }
            .map { checkNotNull(store.block(it)) }
    }

    /**
     * O pick-up (uma reserva) ou a rooming list (várias): uma reserva agrupadora com as reservas do bloqueio, com o
     * titular pedido ou, sem ele, o hóspede titular do bloqueio (num bloqueio de empresa, o titular é obrigatório:
     * `400 BOOKING_INVALID`, campo `holderGuestId`).
     */
    @Audited(ReservationsAudit.PICK_UP_BLOCK)
    @Transactional
    fun pickUp(
        id: RoomBlockId,
        list: RoomingList,
    ): BookingView {
        authorization.require(ReservationsPermissions.BLOCKS_PICK_UP)
        val block = store.block(id) ?: throw blockNotFound()
        val holder =
            list.holderGuestId ?: block.holderGuestId
                ?: throw invalid(
                    ReservationsErrorCodes.BOOKING_INVALID,
                    "holderGuestId",
                    "Informe o titular da reserva: o bloqueio é de uma empresa.",
                )
        val command =
            NewBooking(
                holderGuestId = holder,
                origin = list.origin,
                reservations =
                    list.reservations.map {
                        NewReservation(
                            roomTypeId = it.roomTypeId,
                            ratePlanId = block.ratePlanId,
                            arrival = it.arrival,
                            departure = it.departure,
                            adults = it.adults,
                            childAges = it.childAges,
                            segment = it.segment,
                            specialRequests = it.specialRequests,
                            guests = it.guests,
                        )
                    },
            )
        val booking: BookingSummary = creation.create(command, DiscountAuthorization.AlreadyAuthorized, id)
        return views.bookingView(booking)
    }

    /**
     * A liberação do não utilizado (RN-16), pela `ReservationsApi` (o fechamento do dia, M6): com o bloqueio travado,
     * recusada antes da data de corte (`409 ROOM_BLOCK_CUTOFF_NOT_REACHED`); a partir dela, devolve ao inventário as
     * unidades que não viraram reserva nas noites a partir da data operacional (numa só `apply`, que publica
     * `AvailabilityChanged`) e deixa o bloqueio liberado. Um bloqueio já liberado não muda (nada na trilha).
     */
    @Audited(ReservationsAudit.RELEASE_BLOCK)
    @Transactional
    fun releaseUnused(id: RoomBlockId): RoomBlockSummary {
        val block = store.blockForUpdate(id) ?: throw blockNotFound()
        if (block.status == RoomBlockStatus.RELEASED) {
            audit.noChanges()
            return block
        }
        val businessDate = hotel.currentBusinessDate()
        if (!RoomBlockCutoff.releasable(block.cutoffDate, businessDate)) {
            throw RoomBlockCutoffNotReachedException(block.code.toString(), block.cutoffDate)
        }
        val release = BlockAllocations.release(block.allocations, businessDate)
        inventory.apply(release.inventoryChanges)
        val now = now()
        val rows = BlockAllocations.byKey(block.allocations) + BlockAllocations.byKey(release.saved)
        val updated =
            block.copy(
                status = RoomBlockStatus.RELEASED,
                allocations = rows.entries.sortedWith(compareBy(INVENTORY_ORDER) { it.key }).map { it.value },
                releasedAt = now,
                releasedOn = businessDate,
                releasedBy = currentActor.currentOrNull()?.userId,
                version = block.version + 1,
            )
        store.update(updated, release.saved, emptyList(), now)
        audit.record(
            ReservationsAudit.ROOM_BLOCK,
            id.value,
            ReservationsAudit.blockSnapshot(block),
            ReservationsAudit.blockSnapshot(updated) + ("releasedUnits" to release.units),
        )
        return checkNotNull(store.block(id))
    }

    /** Os bloqueios ativos com a data de corte já alcançada (a data operacional), para o fechamento liberar. */
    @Transactional(readOnly = true)
    fun dueForRelease(): List<RoomBlockId> = store.dueForRelease(hotel.currentBusinessDate())

    @Transactional(readOnly = true)
    fun blockAuthorized(id: RoomBlockId): RoomBlockSummary? = store.block(id)

    private fun requireCutoff(
        cutoff: LocalDate,
        businessDate: LocalDate,
    ) {
        if (cutoff.isBefore(businessDate)) {
            throw BusinessRuleException(
                ReservationsErrorCodes.ROOM_BLOCK_CUTOFF_IN_PAST,
                "A data de corte não pode ser anterior à data operacional.",
            )
        }
    }

    private fun requireNotPast(
        units: Map<InventoryKey, AllocationTerms>,
        businessDate: LocalDate,
    ) {
        val past = units.keys.filter { it.date.isBefore(businessDate) }
        if (past.isNotEmpty()) throw RoomBlockPeriodInPastException(past.map { it.date })
    }

    private fun requireCompany(id: CompanyId) {
        val company =
            guests.company(id)
                ?: throw invalid(ReservationsErrorCodes.UNKNOWN_COMPANY, "holderCompanyId", "A empresa não existe.")
        if (!company.active) {
            throw BusinessRuleException(ReservationsErrorCodes.COMPANY_INACTIVE, "A empresa titular está desativada.")
        }
    }

    private fun requirePlan(command: NewRoomBlock): RatePlanSummary {
        val plan =
            rates.ratePlan(command.ratePlanId)
                ?: throw invalid(
                    ReservationsErrorCodes.UNKNOWN_RATE_PLAN,
                    "ratePlanId",
                    "O plano tarifário não existe.",
                )
        if (!plan.active) {
            throw BusinessRuleException(ReservationsErrorCodes.RATE_PLAN_INACTIVE, "O plano tarifário está desativado.")
        }
        return plan
    }

    /**
     * Os tipos de UH das faixas: todos no plano do bloqueio ([inPlan]; senão `422 ROOM_TYPE_NOT_IN_PLAN`, com o campo),
     * para a reserva do pick-up nunca ficar num plano sem o tipo dela; os novos no bloqueio (os já presentes em
     * [known] não são conferidos de novo) existentes e ativos.
     */
    @Suppress("ThrowsCount") // fora do plano, inexistente e desativado
    private fun requireRoomTypes(
        ranges: List<BlockAllocationRange>,
        known: Set<RoomTypeId>,
        inPlan: Set<RoomTypeId>,
    ) {
        ranges.forEachIndexed { index, range ->
            val field = "allocations[$index].roomTypeId"
            if (range.roomTypeId !in known) {
                val type =
                    rooms.roomType(range.roomTypeId)
                        ?: throw invalid(ReservationsErrorCodes.UNKNOWN_ROOM_TYPE, field, "O tipo de UH não existe.")
                if (!type.active) {
                    throw BusinessRuleException(
                        ReservationsErrorCodes.ROOM_TYPE_INACTIVE,
                        "O tipo de UH está desativado.",
                    )
                }
            }
            if (range.roomTypeId !in inPlan) throw RoomTypeNotInBlockPlanException(field, range.roomTypeId)
        }
    }

    private fun take(
        key: InventoryKey,
        quantity: Int,
    ) = InventoryChange.take(key.roomTypeId, key.date, key.date.plusDays(1), quantity, InventoryUse.BLOCKED)

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private companion object {
        val BLOCK_NUMBER = DocumentNumberType("G", "reservations.room_block_number_seq")
    }
}

/** O uso do inventário pelos bloqueios para a recontagem (E05): as unidades ainda não convertidas dos ativos. */
@Component
class RoomBlocksInventoryUsage(
    private val store: RoomBlockStore,
) : InventoryUsageSource {
    override fun usage(
        roomTypeIds: Collection<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
    ): Map<InventoryKey, InventoryUsage> = store.blockedUnits(roomTypeIds, from, to)
}

internal fun blockNotFound() =
    NotFoundException(ReservationsErrorCodes.ROOM_BLOCK_NOT_FOUND, "Bloqueio de grupo não encontrado.")

private fun invalid(
    code: ErrorCode,
    field: String,
    message: String,
) = InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))
