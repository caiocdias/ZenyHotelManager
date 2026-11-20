package com.zeny.hotel.reporting

import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

/** O contrato publicado (`api/openapi.yaml`) descreve o que o `reporting` devolve em texto livre de tipo aberto. */
class ReportingContractTest {
    private val contract = File("api/openapi.yaml").readText()

    @Test
    fun `the FNRH pending fields list the known values and say the list can grow`() {
        val block = contract.substringAfter("    PendingFnrhSubmissionResponse:\n").substringBefore("\n    Pending", "")
        for (value in listOf("REGISTER_RESERVATION", "CHECK_OUT", "PENDING", "INVALID", "UNKNOWN_OUTCOME")) {
            block shouldContain value
        }
        block shouldContain "a lista pode crescer"
    }
}
