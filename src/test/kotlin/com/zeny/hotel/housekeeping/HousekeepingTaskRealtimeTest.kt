package com.zeny.hotel.housekeeping

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.FILES_PURGE_DEFERRED
import com.zeny.hotel.testsupport.GUESTS_REKEY_DEFERRED
import com.zeny.hotel.testsupport.INVENTORY_HORIZON_DEFERRED
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
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * As transições da tarefa de governança no tópico `housekeeping` (M6 E04, Task 3; D8 da E04) com um cliente SSE de
 * verdade, num servidor de porta aleatória (como o `AssignmentPlanRealtimeTest`): quem lê as tarefas (a Governanta,
 * com `housekeeping.tasks.read` em `ALL`) recebe uma mensagem `housekeeping.task` por transição (`start`, `pause`,
 * `resume`, `refuse`, `retry`, `complete`), com o `id` da tarefa e a versão nova (a do `ETag` da ação), e nada mais:
 * nem as `notes` nem dado de hóspede (RN-13). Uma ação recusada não publica.
 *
 * Os modelos **gerais** de governança são neutralizados antes de cada teste: as tarefas daqui não têm checklist.
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
class HousekeepingTaskRealtimeTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired jdbc: JdbcClient,
    @Autowired staffApi: StaffApi,
    @LocalServerPort private val port: Int,
) {
    private val execution = HousekeepingExecutionTestSupport(iam, jdbc, staffApi)
    private val clients = CopyOnWriteArrayList<SseTestClient>()

    @BeforeEach
    fun prepare() {
        // O banco é compartilhado: outro contexto que suba depois marca as permissões só de teste obsoletas.
        synchronizer.synchronize()
        execution.neutralizeGeneralHousekeepingTemplates()
    }

    @AfterEach
    fun closeClients() {
        clients.forEach { it.close() }
    }

    @Test
    fun `each transition publishes the task id and the new version, without notes`() {
        val attendant = execution.attendant()
        val auth = iam.asTestUser(attendant.user)
        val cleaned = execution.assignedTask(attendant.employee, notes = NOTES)
        val refused = execution.assignedTask(attendant.employee, notes = NOTES)
        val client = open(tokenOf(execution.userWithTemplate(RoleTemplate.HOUSEKEEPER)))
        client.expectReady()

        listOf(
            cleaned to "start",
            cleaned to "pause",
            cleaned to "resume",
            cleaned to "complete",
            refused to "refuse",
            refused to "retry",
        ).forEach { (task, action) ->
            withClue(action) {
                val body = if (action == "refuse") mapOf("reason" to "GUEST_REFUSED") else null
                val (etag, _) = execution.acted(task, action, auth, body)
                client.expectTaskMessage(task, etag)
            }
        }
    }

    @Test
    fun `a refused action publishes nothing`() {
        val attendant = execution.attendant()
        val auth = iam.asTestUser(attendant.user)
        val task = execution.assignedTask(attendant.employee)
        val client = open(tokenOf(execution.userWithTemplate(RoleTemplate.HOUSEKEEPER)))
        client.expectReady()

        execution.act(task, "pause", auth).status shouldBe CONFLICT

        client.eventsWithin(QUIET).filter { it.data.orEmpty().contains("$task") }.shouldBeEmpty()
    }

    private fun SseTestClient.expectTaskMessage(
        task: UUID,
        etag: String,
    ) {
        val event = nextEvent(Duration.ofSeconds(2)).shouldNotBeNull()
        event.event shouldBe "housekeeping"
        val raw = event.data.shouldNotBeNull()
        raw shouldNotContain NOTES
        val data = jsonMapper.readTree(raw)
        data.propertyNames().toList() shouldContainExactly listOf("type", "id", "version")
        data["type"].asString() shouldBe "housekeeping.task"
        data["id"].asString() shouldBe "$task"
        "\"${data["version"].asLong()}\"" shouldBe etag
    }

    private fun tokenOf(user: TestUser): String =
        iam.loginOk(user, "device-${UuidV7.generate()}")["accessToken"].asString()

    private fun open(token: String): SseTestClient =
        SseTestClient.open("http://localhost:$port", token, "housekeeping").also { clients += it }

    private fun SseTestClient.expectReady() {
        withClue(errorBody) { status shouldBe 200 }
        nextEvent().shouldNotBeNull().event shouldBe "ready"
    }

    private companion object {
        const val NOTES = "Hóspede Joaquim pediu toalhas extras"
        const val CONFLICT = 409
        val QUIET: Duration = Duration.ofMillis(1500)
    }
}
