package com.zeny.hotel.fnrh.application

import com.zeny.hotel.fnrh.domain.FnrhSubmissionFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FnrhSubmissionFailuresTest {
    @Test
    fun `toda falha do Serpro menos NOT_CONFIGURED vira a de mesmo nome e mesma temporariedade`() {
        for (failure in FnrhFailure.entries - FnrhFailure.NOT_CONFIGURED) {
            val mapped = failure.toSubmissionFailure()

            assertEquals(failure.name, mapped.name)
            assertEquals(failure.temporary, mapped.temporary)
        }
    }

    @Test
    fun `NOT_CONFIGURED nao e falha de submissao`() {
        assertThrows<IllegalArgumentException> { FnrhFailure.NOT_CONFIGURED.toSubmissionFailure() }
        assertEquals(
            FnrhFailure.entries.size - 1,
            FnrhSubmissionFailure.entries.count {
                it.name in
                    FnrhFailure.entries.map(FnrhFailure::name)
            },
        )
    }
}
