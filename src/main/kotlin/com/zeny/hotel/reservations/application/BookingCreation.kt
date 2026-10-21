package com.zeny.hotel.reservations.application

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.billing.NewFolio
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestStatus
import com.zeny.hotel.guests.GuestSummary
import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.rates.CancellationPolicySummary
import com.zeny.hotel.rates.GuaranteePolicySummary
import com.zeny.hotel.rates.RatePlanSummary
import com.zeny.hotel.rates.RatesApi
import com.zeny.hotel.rates.RatesErrorCodes
import com.zeny.hotel.rates.RatesPermissions
import com.zeny.hotel.rates.SalesChannel
import com.zeny.hotel.rates.StayPrice
import com.zeny.hotel.rates.StayPriceResult
import com.zeny.hotel.rates.StayQuery
import com.zeny.hotel.reservations.BookingId
import com.zeny.hotel.reservations.BookingSummary
import com.zeny.hotel.reservations.CancellationTerms
import com.zeny.hotel.reservations.GuaranteeTerms
import com.zeny.hotel.reservations.NewBooking
import com.zeny.hotel.reservations.NewReservation
import com.zeny.hotel.reservations.NightlyRateSummary
import com.zeny.hotel.reservations.ReservationCreated
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.ReservationsErrorCodes
import com.zeny.hotel.reservations.RoomBlockId
import com.zeny.hotel.reservations.domain.BookingRules
import com.zeny.hotel.reservations.domain.InitialStatus
import com.zeny.hotel.reservations.domain.InventoryChange
import com.zeny.hotel.reservations.domain.InventoryUse
import com.zeny.hotel.reservations.domain.NightlyRates
import com.zeny.hotel.reservations.domain.StayGuests
import com.zeny.hotel.reservations.domain.StayNotSellableException
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.DocumentNumberGenerator
import com.zeny.hotel.shared.DocumentNumberType
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.LimitAmount
import com.zeny.hotel.shared.SensitiveAction
import com.zeny.hotel.shared.SensitiveAuthorization
import com.zeny.hotel.shared.UserId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Como a criação trata o desconto (H7): conferido com o step-up (a API HTTP) ou já autorizado por quem chama. */
sealed interface DiscountAuthorization {
    /** Pela permissão sensível `rates.discounts.apply` e o limite `maxPercent`, ou pela aprovação de superior. */
    data class Required(
        val approvalToken: String?,
    ) : DiscountAuthorization

    /** Quem chama já se autorizou (a `ReservationsApi`: o channel manager grava a diária da OTA). */
    data object AlreadyAuthorized : DiscountAuthorization
}

/**
 * A criação de uma reserva agrupadora (M4 E07; RF-RES-01 a 03 e 14, RN-14, RN-15, RF-CTA-01, H5, H7, H8, H15), **na
 * transação de escrita e no caso de uso auditado de quem chama** (`BookingService`): a API HTTP e a `ReservationsApi`.
 *
 * Na ordem: o formato ([BookingRules]); os hóspedes ([GuestResolution]: mesclado vira o sobrevivente, anonimizado
 * `422`, travados por id); cada reserva vendável no canal da origem (`RatesApi.isSellable`) com o preço calculado
 * (`RatesApi.priceFor`) congelado por noite, com as diárias editadas ([NightlyRates]) e as políticas do plano copiadas
 * (RN-15); o desconto (o maior das noites, conferido uma vez pela permissão sensível do `rates` com o limite
 * `maxPercent`, ou pela aprovação de superior); **uma só tomada de inventário** para todas as reservas que pernoitam
 * (`RoomTypeInventory.apply`, com as linhas travadas: sem unidade, `409 ROOM_TYPE_UNAVAILABLE` e nada gravado); a
 * conta de cada reserva (`BillingApi.openFolio`, dono `RESERVATION` com o id da reserva); a gravação, a trilha (sem
 * pedidos especiais nem dado pessoal) e `ReservationCreated` por reserva, pelo outbox. Tudo na mesma transação: uma
 * recusa em qualquer passo desfaz tudo (inclusive as contas e as unidades).
 */
@Component
@Suppress("LongParameterList", "TooManyFunctions") // as portas e os passos da criação
class BookingCreation(
    private val store: BookingStore,
    private val guestResolution: GuestResolution,
    private val rates: RatesApi,
    private val rooms: RoomsApi,
    private val hotel: HotelApi,
    private val billing: BillingApi,
    private val inventory: RoomTypeInventory,
    private val pickUps: RoomBlockPickUps,
    private val numbers: DocumentNumberGenerator,
    private val authorization: Authorization,
    private val currentActor: CurrentActor,
    private val audit: AuditContext,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /**
     * Cria a reserva agrupadora [command]; com [block], as reservas vêm do pick-up desse bloqueio (E11,
     * [RoomBlockPickUps]): a tarifa do grupo congelada, as políticas do plano do bloqueio, sem conferir a venda nem o
     * desconto, e cada noite passando uma unidade de `blocked` para `sold` na mesma `RoomTypeInventory.apply`.
     */
    fun create(
        command: NewBooking,
        discount: DiscountAuthorization,
        block: RoomBlockId? = null,
    ): BookingSummary {
        check(
            TransactionSynchronizationManager.isActualTransactionActive() &&
                !TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
        ) { "A reserva só é criada dentro da transação de escrita de quem chama" }
        BookingRules.requireValid(command)
        val resolved = guestResolution.resolve(requestedGuests(command))
        val holder = resolved.getValue(command.holderGuestId)
        // O bloqueio é travado depois dos hóspedes e antes do inventário (a ordem das travas, RoomBlockStore).
        val pickUp = block?.let { pickUps.prepare(it, command) }
        val drafts =
            if (pickUp == null) {
                command.reservations.mapIndexed { index, reservation ->
                    draft(index, reservation, command.origin.channel)
                }
            } else {
                command.reservations.mapIndexed { index, reservation ->
                    Draft(reservation, pickUp.plan, pickUp.nights[index], pickUp.cancellation, pickUp.guarantee)
                }
            }
        val approval = if (pickUp == null) authorizeDiscount(drafts, holder, discount) else null
        // Uma só mudança do inventário para todas as reservas: a tomada das que pernoitam (o day use não toma, H5)
        // ou, no pick-up, a passagem de cada noite de `blocked` para `sold`; as linhas travadas em ordem.
        inventory.apply(
            pickUp?.inventoryChanges
                ?: drafts
                    .map { it.request }
                    .filterNot { it.arrival == it.departure }
                    .map { InventoryChange.take(it.roomTypeId, it.arrival, it.departure, 1, InventoryUse.SOLD) },
        )
        val context =
            Context(
                bookingId = BookingId.new(),
                holder = holder,
                now = clock.instant().truncatedTo(ChronoUnit.MICROS),
                year = hotel.currentBusinessDate().year,
                createdBy = currentActor.currentOrNull()?.userId,
                approval = approval,
                resolved = resolved,
                blockId = pickUp?.block?.id,
            )
        val booking =
            BookingSummary(
                id = context.bookingId,
                code = numbers.next(BOOKING_NUMBER, context.year),
                holderGuestId = holder,
                origin = command.origin,
                channel = command.origin.channel,
                externalId = command.externalId,
                reservations = drafts.map { reservation(it, context) },
                version = 0,
                createdAt = context.now,
                createdBy = context.createdBy,
            )
        store.insert(booking)
        pickUp?.let { pickUps.record(it, context.now) }
        record(booking)
        return checkNotNull(store.booking(booking.id)) { "Reserva agrupadora recém-gravada não encontrada" }
    }

    /** O que todas as reservas da agrupadora compartilham na criação. */
    private data class Context(
        val bookingId: BookingId,
        val holder: GuestId,
        val now: Instant,
        val year: Int,
        val createdBy: UserId?,
        val approval: SensitiveAuthorization.ByApproval?,
        val resolved: Map<GuestId, GuestId>,
        val blockId: RoomBlockId?,
    )

    /** A reserva de um rascunho, com a situação inicial e a conta aberta no `billing`. */
    private fun reservation(
        draft: Draft,
        context: Context,
    ): ReservationSummary {
        val id = ReservationId.new()
        val initial =
            InitialStatus.of(draft.guarantee.type, context.now, hotel.operationalSettings().preReservationExpiry)
        val folio = billing.openFolio(NewFolio(FolioOwnerType.RESERVATION, id.value))
        val (roomTotal, additional, total) = NightlyRates.totals(draft.nights)
        val approval = context.approval?.takeIf { NightlyRates.maxDiscount(draft.nights) != null }
        val request = draft.request
        return ReservationSummary(
            id = id,
            code = numbers.next(RESERVATION_NUMBER, context.year),
            bookingId = context.bookingId,
            status = initial.status,
            provisionalUntil = initial.provisionalUntil,
            roomTypeId = request.roomTypeId,
            ratePlanId = draft.plan.id,
            arrival = request.arrival,
            departure = request.departure,
            adults = request.adults,
            childAges = request.childAges,
            segment = request.segment,
            specialRequests = request.specialRequests,
            guests = StayGuests.resolve(request.guests, context.holder) { context.resolved.getValue(it) },
            nightlyRates = draft.nights,
            roomTotal = roomTotal,
            additionalPackagesTotal = additional,
            total = total,
            cancellationTerms = draft.cancellation.terms(),
            guaranteeTerms = draft.guarantee.terms(),
            folioId = folio.id,
            approvalId = approval?.approvalId,
            approvedBy = approval?.approverId,
            version = 0,
            createdAt = context.now,
            createdBy = context.createdBy,
            blockId = context.blockId,
        )
    }

    /** A trilha (sem pedidos especiais nem dado pessoal) e `ReservationCreated` por reserva, pelo outbox. */
    private fun record(booking: BookingSummary) {
        audit.record(ReservationsAudit.BOOKING, booking.id.value, null, ReservationsAudit.bookingSnapshot(booking))
        booking.reservations.forEach { reservation ->
            audit.record(
                ReservationsAudit.RESERVATION,
                reservation.id.value,
                null,
                ReservationsAudit.reservationSnapshot(reservation),
            )
            events.publishEvent(created(booking, reservation))
        }
    }

    /** O titular e os hóspedes de cada reserva, com o campo de cada um. */
    private fun requestedGuests(command: NewBooking): List<Pair<GuestId, String>> =
        listOf(command.holderGuestId to "holderGuestId") +
            command.reservations.flatMapIndexed { index, reservation ->
                reservation.guests.mapIndexed { position, guest ->
                    guest.guestId to "reservations[$index].guests[$position].guestId"
                }
            }

    /** Uma reserva conferida e precificada, antes do inventário e da gravação. */
    private data class Draft(
        val request: NewReservation,
        val plan: RatePlanSummary,
        val nights: List<NightlyRateSummary>,
        val cancellation: CancellationPolicySummary,
        val guarantee: GuaranteePolicySummary,
    )

    private fun draft(
        index: Int,
        request: NewReservation,
        channel: SalesChannel,
    ): Draft {
        val path = "reservations[$index]"
        val plan = planOf(request, path)
        val query =
            StayQuery(
                plan.id,
                request.roomTypeId,
                request.arrival,
                request.departure,
                request.adults,
                request.childAges,
            )
        val sellability = withStayFields(path) { rates.isSellable(query, channel) }
        if (!sellability.sellable) throw StayNotSellableException(index, sellability.refusals)
        val price = priced(index, withStayFields(path) { rates.priceFor(query) })
        val nights = NightlyRates.freeze(price, plan.id, request.nightlyRates.associate { it.date to it.roomAmount })
        val cancellation =
            checkNotNull(rates.cancellationPolicy(plan.cancellationPolicyId)) { "Plano sem a política de cancelamento" }
        val guarantee =
            checkNotNull(rates.guaranteePolicy(plan.guaranteePolicyId)) { "Plano sem a política de garantia" }
        return Draft(request, plan, nights, cancellation, guarantee)
    }

    /** O plano e o tipo de UH citados (inexistentes: `400` com o campo). */
    private fun planOf(
        request: NewReservation,
        path: String,
    ): RatePlanSummary {
        val plan =
            rates.ratePlan(request.ratePlanId)
                ?: throw invalid(
                    ReservationsErrorCodes.UNKNOWN_RATE_PLAN,
                    "$path.ratePlanId",
                    "O plano tarifário não existe.",
                )
        rooms.roomType(request.roomTypeId)
            ?: throw invalid(ReservationsErrorCodes.UNKNOWN_ROOM_TYPE, "$path.roomTypeId", "O tipo de UH não existe.")
        return plan
    }

    private fun priced(
        index: Int,
        result: StayPriceResult,
    ): StayPrice =
        when (result) {
            is StayPriceResult.Priced -> result.price
            is StayPriceResult.NotPriced -> throw StayNotSellableException(index, result.refusals)
        }

    /** O desconto das diárias editadas (H7): o maior, conferido uma vez para a reserva agrupadora inteira. */
    private fun authorizeDiscount(
        drafts: List<Draft>,
        holder: GuestId,
        discount: DiscountAuthorization,
    ): SensitiveAuthorization.ByApproval? {
        val maxDiscount = drafts.mapNotNull { NightlyRates.maxDiscount(it.nights) }.maxOrNull()
        if (maxDiscount == null || discount !is DiscountAuthorization.Required) return null
        val outcome =
            authorization.requireOrApproval(
                SensitiveAction(
                    ReservationsAudit.CREATE_BOOKING,
                    RatesPermissions.DISCOUNTS_APPLY,
                    "${ReservationsAudit.BOOKING}:new:$holder",
                    limit = LimitAmount(RatesPermissions.MAX_PERCENT, maxDiscount.value),
                ),
                discount.approvalToken,
            )
        return outcome as? SensitiveAuthorization.ByApproval
    }

    /** O formato da estadia recusado pelo `rates` (`400 STAY_INVALID`), com os campos da reserva. */
    private fun <T> withStayFields(
        path: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: InvalidInputException) {
            if (e.code != RatesErrorCodes.STAY_INVALID) throw e
            throw InvalidInputException(
                e.code,
                e.message,
                e.violations.map { it.copy(field = "$path.${it.field}") },
                e,
            )
        }

    private fun created(
        booking: BookingSummary,
        reservation: ReservationSummary,
    ) = ReservationCreated(
        reservationId = reservation.id.value,
        bookingId = booking.id.value,
        status = reservation.status,
        roomTypeId = reservation.roomTypeId.value,
        ratePlanId = reservation.ratePlanId.value,
        arrival = reservation.arrival,
        departure = reservation.departure,
        dayUse = reservation.dayUse,
        adults = reservation.adults,
        children = reservation.childAges.size,
        origin = booking.origin,
        channel = booking.channel,
        createdAt = reservation.createdAt,
    )

    private fun CancellationPolicySummary.terms() =
        CancellationTerms(id, name, version, deadlineHours, penaltyType, penaltyPercent, penaltyNights, penaltyAmount)

    private fun GuaranteePolicySummary.terms() = GuaranteeTerms(id, name, version, type, depositPercent, dueDaysBefore)

    private companion object {
        val BOOKING_NUMBER = DocumentNumberType("B", "reservations.booking_number_seq")
        val RESERVATION_NUMBER = DocumentNumberType("R", "reservations.reservation_number_seq")
    }
}

/**
 * Os hóspedes de uma reserva nova pela `GuestsApi` (H8): o perfil mesclado vira o sobrevivente (seguindo `mergedInto`),
 * o anonimizado é recusado (`422 GUEST_ANONYMIZED`) e o inexistente também (`400 UNKNOWN_GUEST`, com o campo).
 *
 * **Corrida com a mesclagem e a anonimização** (risco da E07): descobre a cadeia de cada perfil sem trava, trava todos
 * os perfis da cadeia (compartilhada, em ordem de id, [BookingStore.lockGuests]) e só então lê de novo. O listener de
 * `GuestsMerged` e a checagem de estadia ativa tomam a exclusiva: quem chega depois espera. Se uma mesclagem levou um
 * perfil para fora do que foi travado entre as duas leituras, `409 GUEST_PROFILE_CHANGED` (o cliente repete).
 */
@Component
class GuestResolution(
    private val guests: GuestsApi,
    private val store: BookingStore,
) {
    /** O perfil final de cada id pedido (com o campo de cada um, para as recusas). */
    fun resolve(requested: List<Pair<GuestId, String>>): Map<GuestId, GuestId> {
        val fields = linkedMapOf<GuestId, String>()
        requested.forEach { (id, field) -> fields.putIfAbsent(id, field) }
        val chain = fields.keys.flatMapTo(linkedSetOf()) { discover(it) }
        store.lockGuests(chain, exclusive = false)
        return fields.mapValues { (id, field) -> final(id, field, chain) }
    }

    private fun discover(id: GuestId): List<GuestId> {
        val visited = mutableListOf(id)
        var current = guests.guest(id)
        while (current?.status == GuestStatus.MERGED && visited.size < MAX_CHAIN) {
            val next = checkNotNull(current.mergedInto) { "Perfil mesclado sem o sobrevivente" }
            visited += next
            current = guests.guest(next)
        }
        return visited
    }

    private fun final(
        id: GuestId,
        field: String,
        locked: Set<GuestId>,
    ): GuestId {
        var current = guests.guest(id) ?: throw unknownGuest(field)
        while (current.status == GuestStatus.MERGED) {
            val next = checkNotNull(current.mergedInto) { "Perfil mesclado sem o sobrevivente" }
            if (next !in locked) throw profileChanged()
            current = checkNotNull(guests.guest(next)) { "Sobrevivente inexistente" }
        }
        return current.also(::requireNotAnonymized).id
    }

    private fun requireNotAnonymized(guest: GuestSummary) {
        if (guest.status == GuestStatus.ANONYMIZED) {
            throw BusinessRuleException(
                ReservationsErrorCodes.GUEST_ANONYMIZED,
                "O perfil de um hóspede da reserva foi anonimizado e não pode ser usado.",
            )
        }
    }

    private fun unknownGuest(field: String) =
        InvalidInputException(
            ReservationsErrorCodes.UNKNOWN_GUEST,
            "O hóspede informado não existe.",
            listOf(FieldViolation(field, ReservationsErrorCodes.UNKNOWN_GUEST.value, "O hóspede não existe.")),
        )

    private fun profileChanged() =
        ConflictException(
            ReservationsErrorCodes.GUEST_PROFILE_CHANGED,
            "Um hóspede da reserva acabou de ser mesclado noutro perfil; repita o pedido.",
        )

    private companion object {
        const val MAX_CHAIN = 50
    }
}

private fun invalid(
    code: ErrorCode,
    field: String,
    message: String,
) = InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))
