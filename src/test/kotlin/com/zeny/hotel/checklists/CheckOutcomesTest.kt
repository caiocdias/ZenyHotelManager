package com.zeny.hotel.checklists

import com.zeny.hotel.checklists.domain.CheckOutcomes
import com.zeny.hotel.shared.Percent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A conformidade dos relatórios (M2 E08, RF-CHK-09): "não se aplica" fica fora do denominador. */
class CheckOutcomesTest {
    @Test
    fun `not applicable answers are left out of the denominator`() {
        val outcomes = CheckOutcomes(ok = 6, notOk = 3, notApplicable = 6)

        outcomes.evaluated shouldBe 9
        outcomes.complianceRate shouldBe Percent.of("66.6667")
        outcomes.failureRate shouldBe Percent.of("33.3333")
        CheckOutcomes(ok = 6, notOk = 3, notApplicable = 0).complianceRate shouldBe outcomes.complianceRate
    }

    @Test
    fun `nothing evaluated has no rate, never zero`() {
        val onlyNotApplicable = CheckOutcomes(ok = 0, notOk = 0, notApplicable = 4)

        onlyNotApplicable.evaluated shouldBe 0
        onlyNotApplicable.complianceRate.shouldBeNull()
        onlyNotApplicable.failureRate.shouldBeNull()
        CheckOutcomes(0, 0, 0).complianceRate.shouldBeNull()
    }

    @Test
    fun `rates are rounded half even to 4 places and stay within 0 and 100`() {
        CheckOutcomes(ok = 1, notOk = 0, notApplicable = 9).complianceRate shouldBe Percent.HUNDRED
        CheckOutcomes(ok = 0, notOk = 2, notApplicable = 1).complianceRate shouldBe Percent.of("0.0000")
        CheckOutcomes(ok = 2, notOk = 1, notApplicable = 0).complianceRate shouldBe Percent.of("66.6667")
        CheckOutcomes(ok = 1, notOk = 7, notApplicable = 0).complianceRate shouldBe Percent.of("12.5000")
        shouldThrow<IllegalArgumentException> { CheckOutcomes(ok = -1, notOk = 0, notApplicable = 0) }
    }
}
