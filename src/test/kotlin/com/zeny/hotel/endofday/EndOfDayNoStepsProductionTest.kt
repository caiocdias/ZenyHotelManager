package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.application.EndOfDayTrigger
import com.zeny.hotel.endofday.application.TickOutcome
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.IDEMPOTENCY_KEY_HEADER
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID

/**
 * O que a `main` faz entre a E10 e a E11 (registro de etapas vazio), na configuração de produção: o contexto **não**
 * importa o `EndOfDayTestConfiguration`, então nenhuma etapa (falsa ou real) está registrada. Só o relógio mutável e o
 * apoio de usuários do `iam`, que não registram etapas. Banco isolado.
 */
@EndOfDayIntegrationTest
@Import(MutableClockConfiguration::class, IamTestConfiguration::class)
@Suppress("LongParameterList") // os apoios do contexto
class EndOfDayNoStepsProductionTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val json: JsonMapper,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val trigger: EndOfDayTrigger,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val clock: MutableClock,
) {
    private val execute = EndOfDayPermissions.permissions.single { it.code == EndOfDayPermissions.RUNS_EXECUTE }

    private fun runIds(): List<UUID?> =
        jdbc.sql("SELECT id FROM endofday.run ORDER BY id").query(UUID::class.java).list()

    private fun noticesOf(userId: UUID): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM notifications.notification " +
                    "WHERE type = 'notifications.end-of-day-pending' AND recipient_id = :id",
            ).param("id", userId)
            .query(Int::class.java)
            .single()

    @Test
    fun `without registered steps the manual run is 409 not configured and creates no run`() {
        val manager = iam.createUserWith(TestGrant(execute, ALL))
        val date = hotelApi.currentBusinessDate()
        val before = runIds()

        val response =
            mockMvc
                .post("/api/v1/end-of-day/runs") {
                    with(asUser(manager.id.value))
                    header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = json.writeValueAsString(mapOf("businessDate" to date.toString()))
                }.andReturn()
                .response

        withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 409 }
        json.readTree(response.getContentAsString(Charsets.UTF_8))["code"].asString() shouldBe
            "END_OF_DAY_NOT_CONFIGURED"
        runIds() shouldBe before
        jdbc
            .sql("SELECT count(*) FROM endofday.run WHERE business_date = :d")
            .param("d", date)
            .query(Int::class.java)
            .single() shouldBe 0
    }

    @Test
    fun `without registered steps the trigger does nothing after the due time and after the window`() {
        val manager = iam.createUserWith(TestGrant(execute, ALL))
        val date = hotelApi.currentBusinessDate()
        val dueAt: Instant =
            ZonedDateTime
                .of(date.plusDays(1), hotelApi.operationalSettings().endOfDayTime, hotelApi.timezone())
                .toInstant()
        val original = clock.instant()
        val before = runIds()
        try {
            clock.advance(Duration.between(original, dueAt.plusSeconds(1)))
            trigger.tick() shouldBe TickOutcome.NO_STEPS
            clock.advance(Duration.between(clock.instant(), dueAt.plus(Duration.ofHours(25))))
            trigger.tick() shouldBe TickOutcome.NO_STEPS
        } finally {
            clock.advance(Duration.between(clock.instant(), original))
        }

        runIds() shouldBe before
        noticesOf(manager.id.value) shouldBe 0
    }
}
