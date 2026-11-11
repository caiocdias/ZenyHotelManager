package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhSubmission
import com.zeny.hotel.fnrh.domain.FnrhSubmissionFailure
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.util.UUID

/** A métrica `zeny_fnrh_submissions` por situação, no registro e em `/actuator/prometheus` (M5 E08, Task 10). */
@IntegrationTest
class FnrhSubmissionMetricsTest(
    @Autowired private val registry: MeterRegistry,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val store: FnrhSubmissionStore,
    @Autowired private val jdbc: JdbcClient,
) {
    private fun gauge(status: String) = registry.get("zeny.fnrh.submissions").tag("status", status).gauge()

    @Test
    fun `the gauge counts a FAILED submission and prometheus shows the three statuses`() {
        val before = gauge("failed").value()
        val now = Instant.now()
        val submission =
            FnrhSubmission.create(
                reservationId = UUID.randomUUID(),
                reservationCode = "R-METRIC",
                operation = FnrhOperation.REGISTER_RESERVATION,
                eventKey = "created",
                causalVersion = 0,
                performedBy = null,
                occurredAt = now,
                now = now,
            )
        store.recordIfAbsent(submission) shouldBe true
        jdbc
            .sql("UPDATE fnrh.submission SET status = 'FAILED', last_failure = :f WHERE id = :id")
            .param("f", FnrhSubmissionFailure.VALIDATION.name)
            .param("id", submission.id)
            .update()

        gauge("failed").value() shouldBeGreaterThanOrEqual before + 1

        val body =
            mockMvc
                .get("/actuator/prometheus")
                .andReturn()
                .response.contentAsString
        listOf("pending", "failed", "invalid").forEach {
            body shouldContain "zeny_fnrh_submissions{application=\"zeny-hotel-manager\",status=\"$it\"}"
        }
    }
}
