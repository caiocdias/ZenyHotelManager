package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhRemoteIds
import com.zeny.hotel.fnrh.application.FnrhSubmissionSender
import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.fnrh.infrastructure.FnrhSubmissionJob
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.enableFnrh
import io.kotest.matchers.shouldBe
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper

/**
 * O job do envio à FNRH (M5 E08, Task 10): chama o envio fora de transação e só confere a submissão da própria reserva
 * (o banco é compartilhado, as demais podem sair junto). A trava do ShedLock e a agenda vêm das anotações.
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    InventoryTestConfiguration::class,
    MutableClockConfiguration::class,
    FnrhShortReadTimeoutConfiguration::class,
)
@Suppress("LongParameterList") // as portas e os apoios do fluxo real
class FnrhSubmissionJobTest(
    @Autowired iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
    @Autowired hotelApi: HotelApi,
    @Autowired reservationsApi: ReservationsApi,
    @Autowired sender: FnrhSubmissionSender,
    @Autowired store: FnrhSubmissionStore,
    @Autowired remoteIds: FnrhRemoteIds,
    @Autowired clock: MutableClock,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired tx: TransactionTemplate,
    @Autowired private val job: FnrhSubmissionJob,
) : FnrhReservationSendTestBase(
        iam,
        jdbc,
        hotelApi,
        reservationsApi,
        sender,
        store,
        remoteIds,
        clock,
        mockMvc,
        jsonMapper,
        tx,
    ) {
    @Test
    fun `the job sends the due submission outside a transaction and leaves it SENT`() {
        val id = reservation()
        val submission = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())

        job.send()

        row(submission.id).status shouldBe FnrhSubmissionStatus.SENT
    }

    @Test
    fun `without configuration the job sends nothing and keeps the submission PENDING`() {
        val id = reservation()
        val submission = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)

        job.send()

        row(submission.id).status shouldBe FnrhSubmissionStatus.PENDING
        row(submission.id).attempts shouldBe 0
    }

    @Test
    fun `the job is scheduled by the submissions properties and locked by name with a lock that covers a full batch`() {
        val method = FnrhSubmissionJob::class.java.getMethod("send")
        val scheduled = AnnotatedElementUtils.findMergedAnnotation(method, Scheduled::class.java)!!
        scheduled.fixedDelayString shouldBe "\${zeny.fnrh.submissions.interval}"
        scheduled.initialDelayString shouldBe "\${zeny.fnrh.submissions.initial-delay}"
        val lock = AnnotatedElementUtils.findMergedAnnotation(method, SchedulerLock::class.java)!!
        lock.name shouldBe "fnrh-submissions-send"
        lock.lockAtMostFor shouldBe "PT1H"
    }
}
