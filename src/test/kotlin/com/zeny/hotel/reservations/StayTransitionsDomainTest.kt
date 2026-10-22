package com.zeny.hotel.reservations

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.rates.CancellationPenaltyType
import com.zeny.hotel.rates.CancellationPolicyId
import com.zeny.hotel.rates.GuaranteePolicyId
import com.zeny.hotel.rates.GuaranteeType
import com.zeny.hotel.rates.RatePlanCategory
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.reservations.domain.CheckInGuestFacts
import com.zeny.hotel.reservations.domain.CheckInRules
import com.zeny.hotel.reservations.domain.CheckOutPlan
import com.zeny.hotel.reservations.domain.GuestCheckInRefusedException
import com.zeny.hotel.reservations.domain.InHouseChangeRules
import com.zeny.hotel.reservations.domain.InHouseStayFixedException
import com.zeny.hotel.reservations.domain.MinorRules
import com.zeny.hotel.reservations.domain.ReservationAction
import com.zeny.hotel.reservations.domain.ReservationTransitionNotAllowedException
import com.zeny.hotel.reservations.domain.ReservationTransitions
import com.zeny.hotel.reservations.domain.RoomMoveRules
import com.zeny.hotel.reservations.domain.StayShape
import com.zeny.hotel.reservations.domain.UndoCheckInRules
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * As regras das transições de hospedagem (M5 E01; SPEC §8.4, RN-24, H10): a máquina de estados do check-in, do
 * desfazer, do check-out e da troca de UH; o formato e a data do check-in; a comprovação do menor; o check-out no dia,
 * antecipado, no dia da chegada e atrasado; a alteração do hospedado (só a saída); e a troca de UH.
 */
class StayTransitionsDomainTest {
    // --- Máquina de estados (SPEC §8.4) ---------------------------------------------------------------------------

    @Test
    fun `only a confirmed checks in and only an in-house undoes, checks out and moves`() {
        val hospitality =
            listOf(
                ReservationAction.CHECK_IN,
                ReservationAction.UNDO_CHECK_IN,
                ReservationAction.CHECK_OUT,
                ReservationAction.MOVE_ROOM,
            )
        val table =
            ReservationStatus.entries.associateWith { status ->
                hospitality.filter { ReservationTransitions.allows(it, status) }
            }

        table shouldBe
            mapOf(
                ReservationStatus.PROVISIONAL to emptyList(),
                ReservationStatus.CONFIRMED to listOf(ReservationAction.CHECK_IN),
                ReservationStatus.IN_HOUSE to
                    listOf(ReservationAction.UNDO_CHECK_IN, ReservationAction.CHECK_OUT, ReservationAction.MOVE_ROOM),
                ReservationStatus.CHECKED_OUT to emptyList(),
                ReservationStatus.CANCELLED to emptyList(),
                ReservationStatus.NO_SHOW to emptyList(),
            )
        listOf(ReservationStatus.PROVISIONAL, ReservationStatus.CANCELLED, ReservationStatus.CHECKED_OUT).forEach {
            val refused =
                shouldThrow<ReservationTransitionNotAllowedException> {
                    ReservationTransitions.require(ReservationAction.CHECK_IN, it)
                }
            refused.code shouldBe ReservationsErrorCodes.RESERVATION_TRANSITION_NOT_ALLOWED
            refused.problemProperties shouldBe mapOf("reservationStatus" to it.name, "action" to "CHECK_IN")
        }
        // A hospedada não é cancelada nem reativada; a encerrada não muda mais.
        ReservationTransitions.allows(ReservationAction.CANCEL, ReservationStatus.IN_HOUSE) shouldBe false
        ReservationTransitions.allows(ReservationAction.CHANGE, ReservationStatus.IN_HOUSE) shouldBe true
        ReservationTransitions.allows(ReservationAction.CHANGE, ReservationStatus.CHECKED_OUT) shouldBe false
    }

    // --- Check-in -------------------------------------------------------------------------------------------------

    @Test
    fun `the check-in lists every guest once with exactly one primary, within the occupancy, with valid codes`() {
        val a = GuestId(UuidV7.generate())
        val b = GuestId(UuidV7.generate())

        fieldsOf { CheckInRules.requireValid(CheckIn(emptyList()), 2) } shouldContainExactly listOf("guests")
        fieldsOf {
            CheckInRules.requireValid(CheckIn(listOf(guest(a, true), guest(b, true), guest(newGuest()))), 2)
        } shouldContainExactly listOf("guests", "guests")
        fieldsOf { CheckInRules.requireValid(CheckIn(listOf(guest(a, true), guest(a))), 2) } shouldContainExactly
            listOf("guests[1].guestId")
        fieldsOf {
            CheckInRules.requireValid(
                CheckIn(
                    listOf(CheckInGuest(a, true, travelReason = "lazer com espaço", transportMode = "x".repeat(41))),
                ),
                2,
            )
        } shouldContainExactly listOf("guests[0].travelReason", "guests[0].transportMode")
        fieldsOf {
            CheckInRules.requireValid(
                CheckIn(
                    listOf(
                        guest(a, true),
                        CheckInGuest(b, minorAuthorization = authorization(b, MinorProofType.WRITTEN_AUTHORIZATION)),
                    ),
                ),
                2,
            )
        } shouldContainExactly
            listOf("guests[1].minorAuthorization.responsibleGuestId", "guests[1].minorAuthorization.attachmentId")
        fieldsOf {
            CheckInRules.requireValid(
                CheckIn(
                    listOf(
                        guest(a, true),
                        CheckInGuest(
                            b,
                            minorAuthorization =
                                authorization(a, MinorProofType.PARENT_PRESENT, attachment = UuidV7.generate())
                                    .copy(relationship = "mãe"),
                        ),
                    ),
                ),
                2,
            )
        } shouldContainExactly
            listOf("guests[1].minorAuthorization.relationship", "guests[1].minorAuthorization.attachmentId")
        // Válido: códigos opacos, a autorização escrita com o anexo.
        CheckInRules.requireValid(
            CheckIn(
                listOf(
                    CheckInGuest(a, true, travelReason = "LAZER", transportMode = "AUTOMOVEL"),
                    CheckInGuest(
                        b,
                        minorAuthorization =
                            authorization(a, MinorProofType.WRITTEN_AUTHORIZATION, attachment = UuidV7.generate()),
                    ),
                ),
            ),
            2,
        )
    }

    @Test
    fun `the check-in date is within the stay, a late arrival is accepted and an early one refused`() {
        val stay = reservation(ARRIVAL, ARRIVAL.plusDays(3))

        codeOf { CheckInRules.requireDate(stay, ARRIVAL.minusDays(1)) } shouldBe
            ReservationsErrorCodes.CHECK_IN_BEFORE_ARRIVAL.value
        CheckInRules.requireDate(stay, ARRIVAL)
        // A chegada atrasada (sem no-show ainda): a data operacional dentro da estadia.
        CheckInRules.requireDate(stay, ARRIVAL.plusDays(2))
        codeOf { CheckInRules.requireDate(stay, ARRIVAL.plusDays(3)) } shouldBe
            ReservationsErrorCodes.RESERVATION_STAY_ENDED.value
        val dayUse = reservation(ARRIVAL, ARRIVAL)
        CheckInRules.requireDate(dayUse, ARRIVAL)
        codeOf { CheckInRules.requireDate(dayUse, ARRIVAL.plusDays(1)) } shouldBe
            ReservationsErrorCodes.RESERVATION_STAY_ENDED.value
    }

    @Test
    fun `RN-24 asks every birth date, a proof for each minor and an adult of the stay as the responsible`() {
        val adult = GuestId(UuidV7.generate())
        val otherAdult = GuestId(UuidV7.generate())
        val minor = GuestId(UuidV7.generate())
        val sibling = GuestId(UuidV7.generate())
        val parent = authorization(adult, MinorProofType.PARENT_PRESENT)

        refusal(listOf(facts(adult, false), facts(minor, null))) shouldBe
            (ReservationsErrorCodes.GUEST_BIRTH_DATE_REQUIRED.value to minor)
        refusal(listOf(facts(adult, false), facts(minor, true))) shouldBe
            (ReservationsErrorCodes.MINOR_AUTHORIZATION_REQUIRED.value to minor)
        // O responsável fora da estadia, ou menor também.
        refusal(
            listOf(facts(adult, false), facts(minor, true, authorization(otherAdult, MinorProofType.PARENT_PRESENT))),
        ) shouldBe
            (ReservationsErrorCodes.MINOR_AUTHORIZATION_INVALID.value to minor)
        refusal(
            listOf(
                facts(adult, false),
                facts(sibling, true, parent),
                facts(minor, true, authorization(sibling, MinorProofType.PARENT_PRESENT)),
            ),
        ) shouldBe (ReservationsErrorCodes.MINOR_AUTHORIZATION_INVALID.value to minor)
        // A autorização vencida, e a comprovação num adulto.
        refusal(
            listOf(facts(adult, false), facts(minor, true, parent.copy(validUntil = ARRIVAL.minusDays(1)))),
        ) shouldBe
            (ReservationsErrorCodes.MINOR_AUTHORIZATION_INVALID.value to minor)
        refusal(
            listOf(facts(adult, false), facts(otherAdult, false, authorization(adult, MinorProofType.PARENT_PRESENT))),
        ) shouldBe
            (ReservationsErrorCodes.MINOR_AUTHORIZATION_INVALID.value to otherAdult)
        // Válidas: os pais presentes; a autorização escrita com validade até a data.
        MinorRules.require(listOf(facts(adult, false), facts(minor, true, parent)), ARRIVAL)
        MinorRules.require(
            listOf(
                facts(adult, false),
                facts(
                    minor,
                    true,
                    authorization(adult, MinorProofType.COURT_ORDER, UuidV7.generate()).copy(validUntil = ARRIVAL),
                ),
            ),
            ARRIVAL,
        )
        // Só adultos: nada a comprovar.
        MinorRules.require(listOf(facts(adult, false), facts(otherAdult, false)), ARRIVAL)
    }

    @Test
    fun `the check-in is undone only on the business date it was made`() {
        val record = StayTransitionRecord(ARRIVAL, Instant.parse("2026-11-10T15:00:00Z"), null)

        UndoCheckInRules.require(record, ARRIVAL)
        codeOf { UndoCheckInRules.require(record, ARRIVAL.plusDays(1)) } shouldBe
            ReservationsErrorCodes.UNDO_CHECK_IN_EXPIRED.value
    }

    // --- Check-out ------------------------------------------------------------------------------------------------

    @Test
    fun `the check-out keeps the stay on the departure and drops the nights after an early one, keeping the rest`() {
        val stay = reservation(ARRIVAL, ARRIVAL.plusDays(4))

        CheckOutPlan.of(stay, ARRIVAL.plusDays(4)) shouldBe CheckOutPlan(ARRIVAL.plusDays(4), stay.nightlyRates, false)
        val early = CheckOutPlan.of(stay, ARRIVAL.plusDays(2))
        early.early shouldBe true
        early.departure shouldBe ARRIVAL.plusDays(2)
        early.nights shouldContainExactly stay.nightlyRates.take(2)
        // No dia da chegada, vira day use na data: a noite da chegada fica (com o preço congelado).
        val sameDay = CheckOutPlan.of(stay, ARRIVAL)
        sameDay.departure shouldBe ARRIVAL
        sameDay.nights shouldContainExactly stay.nightlyRates.take(1)
        // Atrasado (sem extensão): a estadia fica como está.
        CheckOutPlan.of(stay, ARRIVAL.plusDays(6)) shouldBe CheckOutPlan(ARRIVAL.plusDays(4), stay.nightlyRates, false)
        val dayUse = reservation(ARRIVAL, ARRIVAL)
        CheckOutPlan.of(dayUse, ARRIVAL) shouldBe CheckOutPlan(ARRIVAL, dayUse.nightlyRates, false)
    }

    // --- Alteração do hospedado -----------------------------------------------------------------------------------

    @Test
    fun `an in-house stay only changes the departure, which stays after the business date`() {
        val old = StayShape(TYPE, PLAN, ARRIVAL, ARRIVAL.plusDays(3), 2, emptyList())
        val today = ARRIVAL.plusDays(1)

        InHouseChangeRules.require(old, old.copy(departure = ARRIVAL.plusDays(5)), today)
        InHouseChangeRules.require(old, old.copy(departure = ARRIVAL.plusDays(2)), today)
        val fixed =
            shouldThrow<InHouseStayFixedException> {
                InHouseChangeRules.require(
                    old,
                    old.copy(
                        arrival = ARRIVAL.plusDays(1),
                        roomTypeId = RoomTypeId(UuidV7.generate()),
                        ratePlanId = RatePlanId(UuidV7.generate()),
                        adults = 1,
                    ),
                    today,
                )
            }
        fixed.code shouldBe ReservationsErrorCodes.IN_HOUSE_STAY_FIXED
        fixed.problemProperties shouldBe mapOf("fields" to listOf("arrival", "roomTypeId", "ratePlanId", "adults"))
        codeOf { InHouseChangeRules.require(old, old.copy(departure = today), today) } shouldBe
            ReservationsErrorCodes.IN_HOUSE_DEPARTURE_INVALID.value
    }

    // --- Troca de UH ----------------------------------------------------------------------------------------------

    @Test
    fun `the room move ends the current room on the business date and opens the new one until the departure`() {
        val stay = reservation(ARRIVAL, ARRIVAL.plusDays(4))
        val today = ARRIVAL.plusDays(1)
        val current = assignment(stay, ARRIVAL, ARRIVAL.plusDays(4))

        shouldThrow<InvalidInputException> {
            RoomMoveRules.requireValid(RoomMove(RoomId(UuidV7.generate()), RoomAssignmentReason.INITIAL))
        }.violations.map { it.field } shouldContainExactly listOf("reason")
        codeOf {
            RoomMoveRules.requireOtherRoom(current, RoomMove(current.roomId, RoomAssignmentReason.GUEST_REQUEST))
        } shouldBe
            ReservationsErrorCodes.ROOM_MOVE_SAME_ROOM.value
        codeOf { RoomMoveRules.requireRemaining(stay, ARRIVAL.plusDays(4)) } shouldBe
            ReservationsErrorCodes.RESERVATION_STAY_ENDED.value
        RoomMoveRules.newPeriod(stay, today).let { it.startsOn to it.endsOn } shouldBe (today to ARRIVAL.plusDays(4))
        RoomMoveRules.oldEnd(current, today) shouldBe today
        RoomMoveRules.repricedNights(stay, today) shouldContainExactly
            listOf(today, ARRIVAL.plusDays(2), ARRIVAL.plusDays(3))
        // No day use, a alocação nova é a própria data, e a atual fica como está.
        val dayUse = reservation(ARRIVAL, ARRIVAL)
        RoomMoveRules.newPeriod(dayUse, ARRIVAL).let { it.startsOn to it.endsOn } shouldBe (ARRIVAL to ARRIVAL)
        RoomMoveRules.oldEnd(assignment(dayUse, ARRIVAL, ARRIVAL), ARRIVAL) shouldBe ARRIVAL
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private fun fieldsOf(block: () -> Unit): List<String> =
        shouldThrow<InvalidInputException>(block)
            .also {
                it.code shouldBe ReservationsErrorCodes.CHECK_IN_INVALID
            }.violations
            .map { it.field }

    private fun codeOf(block: () -> Unit): String = shouldThrow<BusinessRuleException>(block).code.value

    private fun refusal(guests: List<CheckInGuestFacts>): Pair<String, GuestId> =
        shouldThrow<GuestCheckInRefusedException> { MinorRules.require(guests, ARRIVAL) }.let {
            it.problemProperties shouldBe mapOf("guestId" to it.guestId.toString())
            it.code.value to it.guestId
        }

    private fun facts(
        id: GuestId,
        minor: Boolean?,
        authorization: MinorAuthorization? = null,
    ) = CheckInGuestFacts(CheckInGuest(id, minorAuthorization = authorization), minor)

    private fun newGuest() = GuestId(UuidV7.generate())

    private fun guest(
        id: GuestId,
        primary: Boolean = false,
    ) = CheckInGuest(id, primary)

    private fun authorization(
        responsible: GuestId,
        type: MinorProofType,
        attachment: UUID? = null,
    ) = MinorAuthorization(type, responsible, "MAE", attachment)

    private fun assignment(
        reservation: ReservationSummary,
        startsOn: LocalDate,
        endsOn: LocalDate,
    ) = RoomAssignmentSummary(
        id = RoomAssignmentId.new(),
        reservationId = reservation.id,
        roomId = RoomId(UuidV7.generate()),
        startsOn = startsOn,
        endsOn = endsOn,
        reason = RoomAssignmentReason.INITIAL,
        doNotMove = false,
        reallocation = null,
        assignedAt = Instant.parse("2026-11-01T12:00:00Z"),
        assignedBy = null,
    )

    @Suppress("LongMethod") // a reserva inteira, com todos os campos
    private fun reservation(
        arrival: LocalDate,
        departure: LocalDate,
    ): ReservationSummary {
        val nights =
            (if (arrival == departure) listOf(arrival) else arrival.datesUntil(departure).toList()).map {
                NightlyRateSummary(
                    date = it,
                    ratePlanId = PLAN,
                    calculatedRoomAmount = Money.of("400.00"),
                    roomAmount = Money.of("400.00"),
                    includedPackagesAmount = Money.ZERO,
                    additionalPackagesAmount = Money.ZERO,
                    amount = Money.of("400.00"),
                    overridden = false,
                    discountPercent = null,
                    packages = emptyList(),
                )
            }
        val total = Money.of("400.00") * nights.size.toBigDecimal()
        return ReservationSummary(
            id = ReservationId.new(),
            code = DocumentNumber.parse("R-2026-000001"),
            bookingId = BookingId.new(),
            status = ReservationStatus.CONFIRMED,
            provisionalUntil = null,
            roomTypeId = TYPE,
            ratePlanId = PLAN,
            ratePlanCategory = RatePlanCategory.NORMAL,
            arrival = arrival,
            departure = departure,
            adults = 2,
            childAges = emptyList(),
            segment = MarketSegment.LEISURE,
            specialRequests = null,
            guests = emptyList(),
            nightlyRates = nights,
            roomTotal = total,
            additionalPackagesTotal = Money.ZERO,
            total = total,
            cancellationTerms =
                CancellationTerms(
                    CancellationPolicyId(UuidV7.generate()),
                    "Flexível",
                    0,
                    24,
                    CancellationPenaltyType.PERCENT_OF_STAY,
                    Percent.of("10"),
                    null,
                    null,
                ),
            guaranteeTerms =
                GuaranteeTerms(
                    GuaranteePolicyId(UuidV7.generate()),
                    "Sem",
                    0,
                    GuaranteeType.NONE,
                    null,
                    null,
                ),
            folioId = FolioId(UuidV7.generate()),
            approvalId = null,
            approvedBy = null,
            version = 0,
            createdAt = Instant.parse("2026-11-01T12:00:00Z"),
            createdBy = null,
        )
    }

    private companion object {
        val ARRIVAL: LocalDate = LocalDate.of(2026, 11, 10)
        val TYPE = RoomTypeId(UuidV7.generate())
        val PLAN = RatePlanId(UuidV7.generate())
    }
}
