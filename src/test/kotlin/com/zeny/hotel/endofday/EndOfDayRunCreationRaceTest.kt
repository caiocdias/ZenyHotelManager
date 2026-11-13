package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.application.EndOfDayRunCommands
import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepHandler
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import com.zeny.hotel.endofday.domain.EndOfDayRun
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.hotel.HotelApi
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import org.springframework.dao.DataIntegrityViolationException
import java.time.Clock
import java.time.LocalDate

/**
 * A corrida perdida na criação da execução do fechamento (M6 E10, Task 9): só a violação de `run_business_date_uk`
 * (outra transação gravou a execução da data entre a checagem e a gravação) vira `END_OF_DAY_RUN_EXISTS`, sem `runId`;
 * qualquer outra violação de integridade sobe como está.
 */
class EndOfDayRunCreationRaceTest {
    private val date = LocalDate.of(2026, 11, 13)
    private val runs = mockk<EndOfDayRunRepository>()

    private val commands =
        EndOfDayRunCommands(
            runs,
            mockk<HotelApi> { every { currentBusinessDate() } returns date },
            EndOfDayStepRegistry(listOf(Step)),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            Clock.systemUTC(),
            mockk(relaxed = true),
        )

    private object Step : EndOfDayStepHandler {
        override val name = "only-step"
        override val position = 10

        override fun execute(context: EndOfDayStepContext) = EndOfDayStepOutcome.DONE
    }

    private fun violationOf(
        sqlState: String,
        constraint: String,
    ) = DataIntegrityViolationException(
        "could not execute statement",
        PSQLException(ServerErrorMessage("SERROR\u0000C$sqlState\u0000Mviolation\u0000n$constraint\u0000")),
    )

    private fun insertFailsWith(error: Throwable) {
        every { runs.findByBusinessDate(date) } returns null
        every { runs.saveAndFlush(any<EndOfDayRun>()) } throws error
    }

    @Test
    fun `the violation of run_business_date_uk is END_OF_DAY_RUN_EXISTS without runId`() {
        insertFailsWith(violationOf("23505", "run_business_date_uk"))

        val error = shouldThrow<EndOfDayRunExistsException> { commands.createAutomatic(date) }

        error.runId.shouldBeNull()
        error.problemProperties shouldBe emptyMap()
    }

    @Test
    fun `another unique violation escapes as it is`() {
        val other = violationOf("23505", "run_step_pk")
        insertFailsWith(other)

        shouldThrow<DataIntegrityViolationException> { commands.createAutomatic(date) } shouldBeSameInstanceAs other
    }

    @Test
    fun `another integrity violation on the same constraint name escapes as it is`() {
        val notUnique = violationOf("23514", "run_business_date_uk")
        insertFailsWith(notUnique)

        shouldThrow<DataIntegrityViolationException> { commands.createAutomatic(date) } shouldBeSameInstanceAs
            notUnique
    }

    @Test
    fun `an integrity violation without the server error escapes as it is`() {
        val bare = DataIntegrityViolationException("sem causa do driver")
        insertFailsWith(bare)

        shouldThrow<DataIntegrityViolationException> { commands.createAutomatic(date) } shouldBeSameInstanceAs bare
    }
}
