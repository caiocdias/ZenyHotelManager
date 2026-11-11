package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.domain.FnrhDocument
import com.zeny.hotel.fnrh.domain.FnrhDocumentType
import com.zeny.hotel.fnrh.domain.FnrhFieldError
import com.zeny.hotel.fnrh.domain.FnrhGuest
import com.zeny.hotel.fnrh.domain.FnrhLocalValidator
import com.zeny.hotel.fnrh.domain.FnrhPersonData
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import java.time.Instant
import java.time.LocalDate

/**
 * Ataque à montagem do envio da FNRH (M5 E08, Task 5): o validador do contexto usa o catálogo e os municípios reais,
 * e acusa o município com o campo e o código exatos.
 */
@IntegrationTest
class FnrhSubmissionConfigurationAttackTest(
    @Autowired private val validator: FnrhLocalValidator,
    @Autowired private val context: ApplicationContext,
) {
    private val arrival = LocalDate.of(2026, 11, 10)
    private val guest = FnrhGuest(true, Instant.parse("2026-11-10T15:00:00Z"), "LAZER_FERIAS", "AVIAO", null)

    private fun person(
        cityIbge: Int?,
        state: String? = "SP",
    ) = FnrhPersonData(
        name = "Maria da Silva",
        nationality = "BR",
        gender = "MULHER",
        birthDate = LocalDate.of(1990, 5, 10),
        race = "PARDA",
        document = FnrhDocument(FnrhDocumentType.CPF, "52998224725"),
        residenceCountry = "BR",
        cityIbge = cityIbge,
        state = state,
    )

    @Test
    fun `a real municipality of the right state passes the context validator with no error at all`() {
        validator.validate(person(3550308), guest, arrival, true).shouldBeEmpty()
    }

    @Test
    fun `a missing municipality is reported with the exact field and code of the municipality only`() {
        validator.validate(person(1), guest, arrival, true) shouldContainExactly
            listOf(FnrhFieldError("person.cityIbge", FnrhFieldCodes.NOT_IN_DOMAIN))
    }

    @Test
    fun `a real municipality with the state of another one is inconsistent on the state field only`() {
        validator.validate(person(3550308, "RJ"), guest, arrival, true) shouldContainExactly
            listOf(FnrhFieldError("person.state", FnrhFieldCodes.INCONSISTENT))
    }

    @Test
    fun `the context validator uses the real domain catalog and rejects a value out of it`() {
        val bad = person(3550308).copy(race = "NAO_EXISTE")
        validator.validate(bad, guest, arrival, true) shouldContainExactly
            listOf(FnrhFieldError("person.race", FnrhFieldCodes.NOT_IN_DOMAIN))
    }

    @Test
    fun `there is a single validator in the context`() {
        context.getBeansOfType(FnrhLocalValidator::class.java).size shouldBe 1
    }
}
