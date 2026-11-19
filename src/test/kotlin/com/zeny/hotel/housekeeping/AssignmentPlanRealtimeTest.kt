package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.FILES_PURGE_DEFERRED
import com.zeny.hotel.testsupport.GUESTS_REKEY_DEFERRED
import com.zeny.hotel.testsupport.INVENTORY_HORIZON_DEFERRED
import com.zeny.hotel.testsupport.LOST_FOUND_EXPIRY_DEFERRED
import com.zeny.hotel.testsupport.OUTBOX_PURGE_DEFERRED
import com.zeny.hotel.testsupport.PROVISIONAL_EXPIRY_DEFERRED
import com.zeny.hotel.testsupport.PostgresInitializer
import com.zeny.hotel.testsupport.RESUBMISSION_JOB_DEFERRED
import com.zeny.hotel.testsupport.SseTestClient
import com.zeny.hotel.testsupport.TEST_MIGRATIONS
import com.zeny.hotel.testsupport.TestAuthInitializer
import com.zeny.hotel.testsupport.TestEncryptionKeysInitializer
import com.zeny.hotel.testsupport.TestStorageInitializer
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * O tópico `housekeeping` do canal de tempo real (M6 E03, Task 3; D9-E03) com um cliente SSE de verdade, num servidor
 * de porta aleatória (como o `RealtimeStreamTest`): quem tem `housekeeping.tasks.read` recebe a publicação do plano
 * (`housekeeping.assignment-plan`, com o `id` do plano e a versão), sem ela a inscrição é `403`, e a sugestão e o
 * ajuste não publicam.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        TEST_MIGRATIONS,
        RESUBMISSION_JOB_DEFERRED,
        OUTBOX_PURGE_DEFERRED,
        FILES_PURGE_DEFERRED,
        GUESTS_REKEY_DEFERRED,
        INVENTORY_HORIZON_DEFERRED,
        PROVISIONAL_EXPIRY_DEFERRED,
        LOST_FOUND_EXPIRY_DEFERRED,
    ],
)
@ActiveProfiles("local")
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = [
        PostgresInitializer::class,
        TestEncryptionKeysInitializer::class,
        TestAuthInitializer::class,
        TestStorageInitializer::class,
    ],
)
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList") // os beans de que os cenários precisam
class AssignmentPlanRealtimeTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val hotel: HotelApi,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired jdbc: JdbcClient,
    @Autowired staffApi: StaffApi,
    @LocalServerPort private val port: Int,
) {
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)
    private val plans = AssignmentPlanTestSupport(iam, jdbc, staffApi)
    private val clients = CopyOnWriteArrayList<SseTestClient>()

    @BeforeEach
    fun synchronizeTestPermissions() {
        // O banco é compartilhado: outro contexto que suba depois marca as permissões só de teste obsoletas.
        synchronizer.synchronize()
    }

    @AfterEach
    fun closeClients() {
        clients.forEach { it.close() }
    }

    @Test
    fun `a tasks reader receives the publication only, with the plan id and version`() {
        val date = hotel.currentBusinessDate().plusDays(DAYS_AHEAD)
        val attendant = plans.attendant(date)
        val task = plans.insertTask(date, area = housekeeping.rooms.createArea())
        val client = open(tokenOf(iam.createUserWith(tasksRead())))
        client.expectReady()

        val etag = plans.draft(date, listOf(attendant), listOf(task to attendant))
        withClue("a sugestão e o ajuste não publicam") { client.eventsWithin(QUIET).shouldBeEmpty() }
        val response = plans.publish(date, etag)
        withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 200 }

        val event = client.nextEvent(Duration.ofSeconds(2)).shouldNotBeNull()
        event.event shouldBe "housekeeping"
        val data = jsonMapper.readTree(event.data.shouldNotBeNull())
        data.propertyNames().toList() shouldContainExactly listOf("type", "id", "version")
        data["type"].asString() shouldBe "housekeeping.assignment-plan"
        data["id"].asString() shouldBe with(iam) { plans.plan(date).ok() }["id"].asString()
        "\"${data["version"].asLong()}\"" shouldBe response.getHeader(HttpHeaders.ETAG)
    }

    @Test
    fun `subscribing to housekeeping without tasks read is 403`() {
        val client = open(tokenOf(iam.createUser()))

        withClue(client.errorBody) {
            client.status shouldBe 403
            client.contentType.shouldNotBeNull() shouldStartWith "application/problem+json"
            jsonMapper.readTree(client.errorBody)["code"].asString() shouldBe "PERMISSION_DENIED"
        }
    }

    private fun tasksRead() = TestGrant(HousekeepingTestSupport.definition(HousekeepingPermissions.TASKS_READ), ALL)

    private fun tokenOf(user: TestUser): String =
        iam.loginOk(user, "device-${UuidV7.generate()}")["accessToken"].asString()

    private fun open(token: String): SseTestClient =
        SseTestClient.open("http://localhost:$port", token, "housekeeping").also { clients += it }

    private fun SseTestClient.expectReady() {
        withClue(errorBody) { status shouldBe 200 }
        nextEvent().shouldNotBeNull().event shouldBe "ready"
    }

    private companion object {
        const val DAYS_AHEAD = 4L
        val QUIET: Duration = Duration.ofMillis(1500)
    }
}
