package com.zeny.hotel.fnrh.application

import com.zeny.hotel.fnrh.domain.FnrhFieldError
import com.zeny.hotel.fnrh.domain.FnrhLocalValidator
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhSubmission
import com.zeny.hotel.fnrh.domain.FnrhSubmissionError
import com.zeny.hotel.guests.FnrhGuestData
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestIdentification
import com.zeny.hotel.guests.GuestStatus
import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.StayGuestSummary
import com.zeny.hotel.shared.UuidV7
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Os erros que o `CHECK_IN` grava quando a validação local recusa os hóspedes (M5 E08, revisão final M-2 e I-3): sem
 * duplicatas (a chave de `fnrh.submission_error` é o erro inteiro), sem dizer qual dado sensível (RN-19) está errado
 * e sem nenhuma chamada ao Serpro.
 */
class FnrhCheckInStepErrorsTest {
    private val guests = mockk<GuestsApi>()
    private val gateway = mockk<FnrhGateway>()
    private val sync = mockk<FnrhReservationSync>()
    private val validator = mockk<FnrhLocalValidator>()
    private val step =
        FnrhCheckInStep(sync, guests, gateway, mockk<FnrhRemoteIds>(), validator, mockk<FnrhRequesterDirectory>())
    private val arrival = LocalDate.of(2026, 11, 20)
    private val submission =
        FnrhSubmission.create(
            reservationId = UuidV7.generate(),
            reservationCode = "R-1",
            operation = FnrhOperation.CHECK_IN,
            eventKey = "checked-in:v1",
            causalVersion = 1,
            performedBy = null,
            occurredAt = Instant.parse("2026-11-20T15:00:00Z"),
            now = Instant.parse("2026-11-20T15:00:00Z"),
        )

    @Test
    fun `the same survivor twice in the reservation after a merge yields each error only once`() {
        val (merged, survivor) = GuestId(UuidV7.generate()) to GuestId(UuidV7.generate())
        survives(merged, survivor)
        survives(survivor, survivor)
        every { guests.fnrhData(survivor) } returns data(survivor)
        every { validator.validate(any(), any(), any(), any()) } returns
            listOf(FnrhFieldError("person.gender", "REQUIRED"))

        val outcome = step.registerGuests(submission, reservation(listOf(merged, survivor)), connection())

        outcome shouldBe
            FnrhStepOutcome.Invalid(listOf(FnrhSubmissionError(survivor.value, "person.gender", "REQUIRED")))
        verify(exactly = 0) { sync.writeInFlight(any()) }
    }

    @Test
    fun `the errors of race and disability become one neutral sensitive data error per guest`() {
        val guest = GuestId(UuidV7.generate())
        survives(guest, guest)
        every { guests.fnrhData(guest) } returns data(guest)
        every { validator.validate(any(), any(), any(), any()) } returns
            listOf(
                FnrhFieldError("person.race", "REQUIRED"),
                FnrhFieldError("person.gender", "REQUIRED"),
                FnrhFieldError("person.disabilityOption", "NOT_IN_DOMAIN"),
                FnrhFieldError("person.disabilityType", "NOT_IN_DOMAIN"),
            )

        val outcome = step.registerGuests(submission, reservation(listOf(guest)), connection())

        outcome shouldBe
            FnrhStepOutcome.Invalid(
                listOf(
                    FnrhSubmissionError(guest.value, "person.sensitiveData", "INVALID_VALUE"),
                    FnrhSubmissionError(guest.value, "person.gender", "REQUIRED"),
                ),
            )
    }

    @Test
    fun `a disability type error alone does not say which sensitive field is wrong`() {
        val guest = GuestId(UuidV7.generate())
        survives(guest, guest)
        every { guests.fnrhData(guest) } returns data(guest)
        every { validator.validate(any(), any(), any(), any()) } returns
            listOf(FnrhFieldError("person.disabilityType", "REQUIRED"))

        val outcome = step.registerGuests(submission, reservation(listOf(guest)), connection())

        outcome shouldBe
            FnrhStepOutcome.Invalid(listOf(FnrhSubmissionError(guest.value, "person.sensitiveData", "INVALID_VALUE")))
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private fun survives(
        id: GuestId,
        survivor: GuestId,
    ) {
        every { guests.identification(id) } returns
            GuestIdentification(survivor, GuestStatus.ACTIVE, "BR", LocalDate.of(1988, 3, 14), emptyList())
    }

    private fun reservation(stayGuests: List<GuestId>): ReservationSummary {
        val reservation = mockk<ReservationSummary>()
        every { reservation.id } returns ReservationId(submission.reservationId)
        every { reservation.arrival } returns arrival
        every { reservation.guests } returns
            stayGuests.mapIndexed { index, id ->
                StayGuestSummary(id, primary = index == 0, checkedInAt = Instant.parse("2026-11-20T14:00:00Z"))
            }
        return reservation
    }

    private fun data(id: GuestId) =
        FnrhGuestData(
            id = id,
            fullName = "Hóspede de teste",
            socialName = null,
            nationality = "BR",
            birthDate = LocalDate.of(1988, 3, 14),
            gender = null,
            email = null,
            phone = null,
            residenceCountry = null,
            address = null,
            documents = emptyList(),
            raceColor = null,
            disability = null,
        )

    private fun connection() = mockk<FnrhConnection>(relaxed = true)
}
