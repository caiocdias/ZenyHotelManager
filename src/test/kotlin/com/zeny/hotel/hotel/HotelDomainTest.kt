package com.zeny.hotel.hotel

import com.zeny.hotel.hotel.domain.BusinessDay
import com.zeny.hotel.hotel.domain.Hotel
import com.zeny.hotel.hotel.domain.HotelOperations
import com.zeny.hotel.hotel.domain.HotelPolicy
import com.zeny.hotel.hotel.domain.HotelTaxes
import com.zeny.hotel.hotel.domain.HotelTimezones
import com.zeny.hotel.shared.Address
import com.zeny.hotel.shared.BrazilianState
import com.zeny.hotel.shared.Cnpj
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Regras do módulo `hotel` (RF-CFG-01 a 05, RN-01, RN-11, RNF-14): validação dos dados, das
 * políticas (Portaria MTur 28/2025), das taxas e dos parâmetros, e o avanço da data operacional
 * (exatamente um dia, só a partir da data esperada).
 */
class HotelDomainTest {
    private val now = Instant.parse("2026-10-14T12:00:00Z")
    private val later = Instant.parse("2026-10-14T13:00:00Z")
    private val hotel = Hotel.create(Hotel.DEFAULT_TIMEZONE, now)

    private fun policy() = HotelPolicy.create(hotel.id, now)

    private val validPolicy =
        PolicySettings(
            checkInTime = LocalTime.of(14, 0),
            checkOutTime = LocalTime.of(12, 0),
            cleaningWindow = Duration.ofHours(2),
            earlyCheckIn = EarlyLateTerms(LocalTime.of(10, 0), Money.of("80.00")),
            lateCheckOut = EarlyLateTerms(LocalTime.of(18, 0), Money.of("120.00")),
            childMaxAge = 12,
            childAgeBands = listOf(ChildAgeBand(0, 5, "0 a 5 anos"), ChildAgeBand(6, 12, "6 a 12 anos")),
            stayPolicyText = "A diária tem 24 horas, com até 2 horas reservadas à arrumação da UH.",
            additionalPolicyText = null,
        )

    private val profile =
        HotelProfile(
            legalName = "Pousada Exemplo Ltda",
            tradeName = "Pousada Exemplo",
            cnpj = Cnpj.of("11.222.333/0001-81"),
            municipalRegistration = "12345",
            cadastur = "21.012345.10.0001-1",
            address = Address("Rua A", "10", null, "Centro", "São Paulo", BrazilianState.SP, "01310-100", "3550308"),
            timezone = ZoneId.of("America/Sao_Paulo"),
            taxRegime = TaxRegime.SIMPLES_NACIONAL,
            logoFileId = null,
        )

    private inline fun <reified T : DomainException> rejected(
        code: String,
        block: () -> Unit,
    ): T {
        val error = shouldThrow<T>(block)
        error.code shouldBe ErrorCode(code)
        return error
    }

    @Test
    fun `a new hotel is not configured, uses the default timezone and is configured with valid data`() {
        hotel.configured shouldBe false
        hotel.timezone shouldBe ZoneId.of("America/Sao_Paulo")
        hotel.cnpj.shouldBeNull()

        hotel.configure(profile, later) shouldBe true

        hotel.configured shouldBe true
        hotel.profile() shouldBe profile
        hotel.updatedAt shouldBe later
        hotel.configure(profile, Instant.parse("2026-10-15T00:00:00Z")) shouldBe false
        hotel.updatedAt shouldBe later
    }

    @Test
    fun `the hotel requires names and a geographic timezone`() {
        rejected<InvalidInputException>("HOTEL_DATA_INVALID") {
            hotel.configure(profile.copy(legalName = "  "), later)
        }.violations.map { it.field } shouldContainExactly listOf("legalName")
        rejected<InvalidInputException>("HOTEL_DATA_INVALID") { hotel.configure(profile.copy(tradeName = ""), later) }
        rejected<InvalidInputException>("INVALID_TIMEZONE") { HotelTimezones.parse("Lua/Base_Alfa") }
        rejected<InvalidInputException>("INVALID_TIMEZONE") { HotelTimezones.parse("-03:00") }
        rejected<InvalidInputException>("INVALID_TIMEZONE") { HotelTimezones.parse("UTC") }
        HotelTimezones.parse("America/Manaus") shouldBe ZoneId.of("America/Manaus")
        hotel.configured shouldBe false
    }

    @Test
    fun `the default policy follows RN-11 and a valid policy is accepted`() {
        val policy = policy()
        val defaults = policy.settings()
        defaults.checkOutTime shouldBe LocalTime.of(12, 0)
        defaults.checkInTime shouldBe LocalTime.of(14, 0)
        defaults.cleaningWindow shouldBe Duration.ofHours(2)
        defaults.earlyCheckIn.shouldBeNull()
        defaults.lateCheckOut.shouldBeNull()

        policy.update(validPolicy, later) shouldBe true

        policy.settings() shouldBe validPolicy
        policy.update(validPolicy, later) shouldBe false
    }

    @Test
    fun `the cleaning window has at most 3 hours, inside the 24-hour daily rate (RN-11)`() {
        val policy = policy()

        // Janela acima de 3h.
        rejected<InvalidInputException>("CLEANING_WINDOW_TOO_LONG") {
            policy.update(
                validPolicy.copy(
                    checkOutTime = LocalTime.of(11, 0),
                    checkInTime = LocalTime.of(15, 0),
                    cleaningWindow = Duration.ofMinutes(181),
                ),
                later,
            )
        }.violations.map { it.field } shouldContainExactly listOf("cleaningWindowMinutes")
        // Entre o check-out e o check-in mais de 3h: o hóspede ficaria com menos de 21h da diária.
        rejected<InvalidInputException>("CLEANING_WINDOW_TOO_LONG") {
            policy.update(
                validPolicy.copy(
                    checkOutTime = LocalTime.of(11, 0),
                    checkInTime = LocalTime.of(14, 1),
                    cleaningWindow = Duration.ofHours(2),
                ),
                later,
            )
        }
        // Janela vazia ou maior que o intervalo entre check-out e check-in.
        rejected<InvalidInputException>("CLEANING_WINDOW_INVALID") {
            policy.update(validPolicy.copy(cleaningWindow = Duration.ZERO), later)
        }
        rejected<InvalidInputException>("CLEANING_WINDOW_INVALID") {
            policy.update(validPolicy.copy(cleaningWindow = Duration.ofMinutes(121)), later)
        }
        // Exatamente 3h é aceito.
        policy.update(
            validPolicy.copy(
                checkOutTime = LocalTime.of(11, 0),
                checkInTime = LocalTime.of(14, 0),
                cleaningWindow = Duration.ofHours(3),
                earlyCheckIn = null,
            ),
            later,
        ) shouldBe true
    }

    @Test
    fun `check-in and check-out times must be coherent`() {
        val policy = policy()

        rejected<InvalidInputException>("CHECK_TIMES_INCOHERENT") {
            policy.update(validPolicy.copy(checkInTime = LocalTime.of(12, 0)), later)
        }
        rejected<InvalidInputException>("CHECK_TIMES_INCOHERENT") {
            policy.update(validPolicy.copy(checkInTime = LocalTime.of(11, 0)), later)
        }
        // Early check-in só antes do horário de check-in; late check-out só depois do check-out.
        rejected<InvalidInputException>("EARLY_CHECK_IN_INVALID") {
            policy.update(
                validPolicy.copy(earlyCheckIn = EarlyLateTerms(LocalTime.of(14, 0), Money.of("10.00"))),
                later,
            )
        }
        rejected<InvalidInputException>("EARLY_CHECK_IN_INVALID") {
            policy.update(validPolicy.copy(earlyCheckIn = EarlyLateTerms(LocalTime.of(9, 0), Money.of("-1.00"))), later)
        }
        rejected<InvalidInputException>("LATE_CHECK_OUT_INVALID") {
            policy.update(
                validPolicy.copy(lateCheckOut = EarlyLateTerms(LocalTime.of(12, 0), Money.of("10.00"))),
                later,
            )
        }
        // Horários só em horas e minutos.
        rejected<InvalidInputException>("TIME_NOT_WHOLE_MINUTE") {
            policy.update(validPolicy.copy(checkInTime = LocalTime.of(14, 0, 30)), later)
        }.violations.map { it.field } shouldContainExactly listOf("checkInTime")
        rejected<InvalidInputException>("TIME_NOT_WHOLE_MINUTE") {
            policy.update(
                validPolicy.copy(lateCheckOut = EarlyLateTerms(LocalTime.of(18, 0, 0, 1), Money.of("10.00"))),
                later,
            )
        }.violations.map { it.field } shouldContainExactly listOf("lateCheckOut.limitTime")
        policy.settings() shouldBe policy().settings()
    }

    @Test
    fun `child ages and the policy text are validated`() {
        val policy = policy()

        rejected<InvalidInputException>("CHILD_AGES_INVALID") {
            policy.update(validPolicy.copy(childMaxAge = 18), later)
        }
        rejected<InvalidInputException>("CHILD_AGES_INVALID") {
            policy.update(
                validPolicy.copy(childAgeBands = listOf(ChildAgeBand(0, 5, "a"), ChildAgeBand(7, 12, "b"))),
                later,
            )
        }
        rejected<InvalidInputException>("CHILD_AGES_INVALID") {
            policy.update(validPolicy.copy(childAgeBands = listOf(ChildAgeBand(0, 11, "a"))), later)
        }
        rejected<InvalidInputException>("CHILD_AGES_INVALID") {
            policy.update(validPolicy.copy(childAgeBands = listOf(ChildAgeBand(0, 12, " "))), later)
        }
        rejected<InvalidInputException>("POLICY_TEXT_REQUIRED") {
            policy.update(validPolicy.copy(stayPolicyText = " "), later)
        }
        policy.update(validPolicy.copy(childAgeBands = emptyList()), later) shouldBe true
    }

    @Test
    fun `tax rates are Percent within range and the tourism fee is off by default`() {
        val taxes = HotelTaxes.create(hotel.id, now)
        taxes.settings() shouldBe TaxSettings(Percent.ZERO, Percent.ZERO, null)

        rejected<InvalidInputException>("SERVICE_CHARGE_RATE_INVALID") {
            taxes.update(TaxSettings(Percent.of("100.0001"), Percent.ZERO, null), later)
        }
        rejected<InvalidInputException>("SERVICE_CHARGE_RATE_INVALID") {
            taxes.update(TaxSettings(Percent.of("-1"), Percent.ZERO, null), later)
        }
        rejected<InvalidInputException>("ISS_RATE_INVALID") {
            taxes.update(TaxSettings(Percent.of("10"), Percent.of("5.01"), null), later)
        }
        rejected<InvalidInputException>("TOURISM_FEE_INVALID") {
            taxes.update(TaxSettings(Percent.of("10"), Percent.of("5"), Money.ZERO), later)
        }
        val valid = TaxSettings(Percent.of("10"), Percent.of("5"), Money.of("3.50"))
        taxes.update(valid, later) shouldBe true
        taxes.settings() shouldBe valid
    }

    @Test
    fun `operational settings are validated`() {
        val operations = HotelOperations.create(hotel.id, now)
        val defaults = operations.settings()

        rejected<InvalidInputException>("OPERATIONAL_SETTING_INVALID") {
            operations.update(defaults.copy(preReservationExpiry = Duration.ZERO), later)
        }.violations.map { it.field } shouldContainExactly listOf("preReservationExpiryHours")
        rejected<InvalidInputException>("OPERATIONAL_SETTING_INVALID") {
            operations.update(
                defaults.copy(
                    preReservationExpiry = Duration.ofHours(24),
                    preReservationAlertLead = Duration.ofHours(24),
                ),
                later,
            )
        }
        rejected<InvalidInputException>("OPERATIONAL_SETTING_INVALID") {
            operations.update(defaults.copy(lostAndFoundRetentionDays = 0), later)
        }
        rejected<InvalidInputException>("TIME_NOT_WHOLE_MINUTE") {
            operations.update(defaults.copy(endOfDayTime = LocalTime.of(3, 0, 15)), later)
        }.violations.map { it.field } shouldContainExactly listOf("endOfDayTime")
        val valid =
            OperationalSettings(
                endOfDayTime = LocalTime.of(2, 30),
                preReservationExpiry = Duration.ofHours(72),
                preReservationAlertLead = Duration.ofHours(6),
                lostAndFoundRetentionDays = 180,
            )
        operations.update(valid, later) shouldBe true
        operations.settings() shouldBe valid
    }

    @Test
    fun `the business date advances exactly one day, only from the expected date`() {
        val day = BusinessDay.start(hotel.id, LocalDate.of(2026, 10, 14), now)

        rejected<ConflictException>("BUSINESS_DATE_MISMATCH") { day.advance(LocalDate.of(2026, 10, 13), later) }
        rejected<ConflictException>("BUSINESS_DATE_MISMATCH") { day.advance(LocalDate.of(2026, 10, 15), later) }
        day.current shouldBe LocalDate.of(2026, 10, 14)
        day.advancedAt.shouldBeNull()

        val event = day.advance(LocalDate.of(2026, 10, 14), later)

        event shouldBe BusinessDateAdvanced(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 15), later)
        day.current shouldBe LocalDate.of(2026, 10, 15)
        day.advancedAt shouldBe later
        // A data esperada antiga não avança de novo: a repetição não pula um dia.
        rejected<ConflictException>("BUSINESS_DATE_MISMATCH") { day.advance(LocalDate.of(2026, 10, 14), later) }
        day.advance(LocalDate.of(2026, 10, 15), later).current shouldBe LocalDate.of(2026, 10, 16)
    }
}
