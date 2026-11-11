package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhSubmission
import com.zeny.hotel.fnrh.domain.FnrhSubmissionFailure
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.util.UUID

/** Ataques do testador à métrica `zeny_fnrh_submissions` (M5 E08, Task 10): contagem por situação e rótulos. */
@IntegrationTest
class FnrhSubmissionMetricsAttackTest(
    @Autowired private val registry: MeterRegistry,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val store: FnrhSubmissionStore,
    @Autowired private val jdbc: JdbcClient,
) {
    private val created = mutableListOf<UUID>()

    @AfterEach
    fun resolve() {
        // As linhas do teste saem da fila: outro teste do banco compartilhado não as envia.
        created.forEach {
            jdbc.sql("UPDATE fnrh.submission SET status = 'CANCELLED' WHERE id = :id").param("id", it).update()
        }
        created.clear()
    }

    private fun gauge(status: String) =
        registry
            .get("zeny.fnrh.submissions")
            .tag("status", status)
            .gauge()
            .value()

    private fun insert(status: FnrhSubmissionStatus) {
        val now = Instant.now()
        val submission =
            FnrhSubmission.create(
                reservationId = UUID.randomUUID(),
                reservationCode = CODE,
                operation = FnrhOperation.REGISTER_RESERVATION,
                eventKey = "created",
                causalVersion = 0,
                performedBy = null,
                occurredAt = now,
                now = now,
            )
        store.recordIfAbsent(submission) shouldBe true
        created += submission.id
        if (status != FnrhSubmissionStatus.PENDING) {
            jdbc
                .sql("UPDATE fnrh.submission SET status = :s, last_failure = :f WHERE id = :id")
                .param("s", status.name)
                .param("f", FnrhSubmissionFailure.VALIDATION.name)
                .param("id", submission.id)
                .update()
        }
    }

    private fun prometheus() =
        mockMvc
            .get("/actuator/prometheus")
            .andReturn()
            .response.contentAsString

    @Test
    fun `one PENDING, one FAILED and one INVALID change each count by exactly one`() {
        val before = listOf("pending", "failed", "invalid").associateWith(::gauge)

        insert(FnrhSubmissionStatus.PENDING)
        insert(FnrhSubmissionStatus.FAILED)
        insert(FnrhSubmissionStatus.INVALID)

        listOf("pending", "failed", "invalid").forEach { gauge(it) shouldBe before.getValue(it) + 1 }
    }

    @Test
    fun `SENT, CANCELLED and DISMISSED are not counted by any tag`() {
        val before = listOf("pending", "failed", "invalid").associateWith(::gauge)

        insert(FnrhSubmissionStatus.SENT)
        insert(FnrhSubmissionStatus.CANCELLED)
        insert(FnrhSubmissionStatus.DISMISSED)

        listOf("pending", "failed", "invalid").forEach { gauge(it) shouldBe before.getValue(it) }
        registry
            .find("zeny.fnrh.submissions")
            .gauges()
            .map { it.id.getTag("status") }
            .toSet() shouldBe
            setOf("pending", "failed", "invalid")
    }

    @Test
    fun `prometheus shows the value of each status and no guest or reservation data in the tags`() {
        insert(FnrhSubmissionStatus.FAILED)
        val failed = gauge("failed")

        val lines = prometheus().lines().filter { it.startsWith("zeny_fnrh_submissions") }

        lines.size shouldBe 3
        lines.single { it.contains("status=\"failed\"") }.substringAfterLast(' ').toDouble() shouldBe failed
        lines.forEach {
            it shouldNotContain CODE
            it shouldContain "application=\"zeny-hotel-manager\""
            it
                .substringAfter('{')
                .substringBefore('}')
                .split(',')
                .map { tag ->
                    tag.substringBefore('=')
                }.toSet() shouldBe
                setOf("application", "status")
        }
    }

    private companion object {
        const val CODE = "R-SECRET-CODE"
    }
}
