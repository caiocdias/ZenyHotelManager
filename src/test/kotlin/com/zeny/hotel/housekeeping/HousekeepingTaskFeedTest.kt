package com.zeny.hotel.housekeeping

import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestsTestSupport
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.reservations.CheckIn
import com.zeny.hotel.reservations.CheckInGuest
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.InventoryListenersQuiescence
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * A fonte offline `housekeeping-tasks` (M6 E19, Task 2, D6): a camareira (`OWN`) recebe as tarefas abertas dela,
 * inclusive de datas anteriores, com a `version` que é o `ETag` online; reatribuir manda o *tombstone* à antiga dona e
 * a tarefa volta a ela se for reatribuída de volta (Review Focus 5); concluir manda o *tombstone*; a Governanta (`ALL`)
 * vê a tarefa o tempo todo; nada de `notes` nem dado de hóspede (RN-13); usuário sem colaborador recebe a fonte vazia.
 *
 * O banco é compartilhado: cada teste afirma só sobre as próprias tarefas. A reatribuição passa pela publicação do
 * plano numa data futura da janela ([DAYS_AHEAD], tolerando o plano alheio); as demais tarefas são atribuídas por
 * escrita direta (o que a publicação grava), sem passar pelo plano do dia.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList") // os apoios do contexto
class HousekeepingTaskFeedTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired staffApi: StaffApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val execution = HousekeepingExecutionTestSupport(iam, jdbc, staffApi)
    private val housekeeping = execution.housekeeping
    private val plans = execution.plans
    private val reservations = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)

    private val today: LocalDate get() = hotel.currentBusinessDate()

    @BeforeEach
    fun neutralizeGeneralTemplates() = execution.neutralizeGeneralHousekeepingTemplates()

    @Test
    fun `the attendant receives only her open tasks, including earlier dates, with the online ETag as version`() {
        val ana = execution.attendant()
        val bia = execution.attendant()
        val mine = execution.assignedTask(ana.employee)
        val hers = execution.assignedTask(bia.employee)
        // Uma tarefa aberta de três dias atrás, ainda da Ana: a API só cria avulsas da data operacional em diante,
        // então ela é gravada direto no banco, como a publicação a deixaria.
        val earlier =
            plans.insertTask(
                today.minusDays(3),
                room = housekeeping.createRoom(),
                status = "ASSIGNED",
                employee = ana.employee,
                position = 1,
            )

        val tasks = tasksOf(download(iam.asTestUser(ana.user)).changes)

        tasks.keys shouldContain mine
        tasks.keys shouldContain earlier
        tasks.keys shouldNotContain hers
        val item = tasks.getValue(mine)
        val etag = housekeeping.etagOf("${HousekeepingTestSupport.TASKS}/$mine")
        assertSoftly {
            item["deleted"].asBoolean() shouldBe false
            "\"${item["version"].asLong()}\"" shouldBe etag
            item["data"]["status"].asString() shouldBe "ASSIGNED"
            item["data"]["position"].asInt() shouldBe 1
            item["data"]["businessDate"].asString() shouldBe "$today"
            item["data"]["roomId"].isNull shouldBe false
            tasks.getValue(earlier)["data"]["businessDate"].asString() shouldBe "${today.minusDays(3)}"
        }
    }

    @Test
    fun `a reassigned task is a tombstone for the former owner and comes back if reassigned back (Review Focus 5)`() {
        val date = today.plusDays(DAYS_AHEAD)
        val (ana, anaUser) = scheduledAttendant(date)
        val (bia, biaUser) = scheduledAttendant(date)
        val housekeeper = iam.asTestUser(execution.userWithTemplate(RoleTemplate.HOUSEKEEPER))
        val task =
            UUID.fromString(
                housekeeping
                    .requestTask(
                        housekeeping.taskBody(
                            AssignmentPlanTestSupport.TOUCH_UP_TYPE,
                            roomId = housekeeping.createRoom(),
                            date = "$date",
                        ),
                    )["id"]
                    .asString(),
            )
        publish(date, listOf(ana, bia), task to ana)
        val anaFirst = download(anaUser)
        val keeperFirst = download(housekeeper)
        tasksOf(anaFirst.changes).getValue(task)["deleted"].asBoolean() shouldBe false
        tasksOf(keeperFirst.changes).getValue(task)["deleted"].asBoolean() shouldBe false

        publish(date, listOf(ana, bia), task to bia)

        val anaAfter = download(anaUser, anaFirst.cursor)
        withClue("a antiga dona recebe o tombstone") {
            tasksOf(anaAfter.changes).getValue(task)["deleted"].asBoolean() shouldBe true
        }
        val biaFirst = download(biaUser)
        tasksOf(biaFirst.changes).getValue(task)["deleted"].asBoolean() shouldBe false
        val keeperAfter = download(housekeeper, keeperFirst.cursor)
        withClue("a Governanta (ALL) continua com a tarefa") {
            changesOf(keeperAfter.changes, task).map { it["deleted"].asBoolean() } shouldBe listOf(false)
        }

        publish(date, listOf(ana, bia), task to ana)

        val anaBack = download(anaUser, anaAfter.cursor)
        withClue("a tarefa volta à Ana como upsert") {
            val back = tasksOf(anaBack.changes).getValue(task)
            back["deleted"].asBoolean() shouldBe false
            back["data"]["status"].asString() shouldBe "ASSIGNED"
        }
        withClue("a Bia, que perdeu a tarefa, recebe o tombstone") {
            tasksOf(download(biaUser, biaFirst.cursor).changes).getValue(task)["deleted"].asBoolean() shouldBe true
        }
    }

    @Test
    fun `a completed task goes as a tombstone`() {
        val ana = execution.attendant()
        val auth = iam.asTestUser(ana.user)
        val task = execution.assignedTask(ana.employee)
        val before = download(auth)
        tasksOf(before.changes).getValue(task)["deleted"].asBoolean() shouldBe false

        execution.acted(task, "start", auth)
        execution.acted(task, "complete", auth, body = emptyMap<String, Any?>())

        tasksOf(download(auth, before.cursor).changes).getValue(task)["deleted"].asBoolean() shouldBe true
    }

    @Test
    fun `the feed carries no notes nor guest or reservation data (RN-13)`() {
        val ana = execution.attendant()
        val notes = "Hóspede pediu travesseiro extra ${UUID.randomUUID()}"
        val stay = inHouseGuest()
        val task =
            UUID.fromString(
                housekeeping
                    .requestTask(
                        housekeeping.taskBody(housekeeping.createCleaningType(), roomId = stay.room, notes = notes),
                    )["id"]
                    .asString(),
            )
        execution.assign(task, ana.employee)

        val feed = download(iam.asTestUser(ana.user))

        tasksOf(feed.changes).keys shouldContain task
        val body = feed.bodies.joinToString("\n")
        assertSoftly {
            body shouldNotContain notes
            body shouldNotContain "travesseiro"
            body shouldNotContain stay.name
            body shouldNotContain stay.email
            body shouldNotContain stay.cpf
            body shouldNotContain stay.cpfDigits
            body shouldNotContain stay.guest.toString()
            body shouldNotContain stay.reservation.toString()
        }
    }

    @Test
    fun `a user with OWN and no linked employee gets the source empty, without error`() {
        val user = execution.userWithTemplate(RoleTemplate.ROOM_ATTENDANT)
        execution.assignedTask(execution.attendant().employee)

        tasksOf(download(iam.asTestUser(user)).changes).keys.shouldBeEmpty()
    }

    @Test
    fun `the task carries the offline change position, filled by the trigger and indexed`() {
        val nullable =
            jdbc
                .sql(
                    "SELECT column_name, is_nullable FROM information_schema.columns WHERE table_schema = " +
                        "'housekeeping' AND table_name = 'housekeeping_task' AND column_name IN ('change_seq', " +
                        "'change_xid')",
                ).query { rs, _ -> rs.getString(1) to rs.getString(2) }
                .list()
                .toMap()
        nullable shouldBe mapOf("change_seq" to "NO", "change_xid" to "NO")
        jdbc
            .sql(
                "SELECT count(*) FROM pg_trigger WHERE tgname = 'housekeeping_task_offline_change' " +
                    "AND tgrelid = 'housekeeping.housekeeping_task'::regclass",
            ).query(Int::class.java)
            .single() shouldBe 1
        jdbc
            .sql(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'housekeeping' " +
                    "AND indexname = 'housekeeping_task_offline_change_idx'",
            ).query(String::class.java)
            .optional()
            .orElse(null)
            .shouldNotBeNull()
        val task = execution.pendingTask()
        val before = position(task)
        execution.assign(task, execution.attendant().employee)
        withClue("toda escrita muda a posição") { (position(task) > before) shouldBe true }
    }

    // --- Apoio -------------------------------------------------------------------------------------------------------

    private data class Download(
        val changes: List<JsonNode>,
        val cursor: String,
        val bodies: List<String>,
    )

    /** O download desde [from] (sem cursor: o completo), página a página, com os corpos crus. */
    private fun download(
        auth: RequestPostProcessor,
        from: String? = null,
    ): Download =
        with(iam) {
            val changes = mutableListOf<JsonNode>()
            val bodies = mutableListOf<String>()
            var cursor = from
            var pages = 0
            do {
                // O download só serve transações terminadas: espere os listeners do inventário (M4 E05).
                InventoryListenersQuiescence.await(jdbc)
                val response =
                    call(GET, "/api/v1/sync/changes?limit=200" + cursor?.let { "&cursor=$it" }.orEmpty(), auth)
                val page = response.ok()
                bodies += response.getContentAsString(Charsets.UTF_8)
                changes += page["changes"].values()
                cursor = page["cursor"].asString()
                pages++
                withClue("download sem fim") { (pages < MAX_PAGES) shouldBe true }
            } while (page["hasMore"].asBoolean())
            Download(changes, checkNotNull(cursor), bodies)
        }

    /** As mudanças da fonte `housekeeping-tasks`, a última de cada tarefa. */
    private fun tasksOf(changes: List<JsonNode>): Map<UUID, JsonNode> =
        changes
            .filter { it["source"].asString() == HousekeepingOffline.TASKS.name }
            .associateBy { UUID.fromString(it["id"].asString()) }

    private fun changesOf(
        changes: List<JsonNode>,
        task: UUID,
    ): List<JsonNode> =
        changes.filter {
            it["source"].asString() == HousekeepingOffline.TASKS.name && it["id"].asString() == "$task"
        }

    /** Uma camareira escalada na [date], vinculada a um usuário com o modelo Camareira. */
    private fun scheduledAttendant(date: LocalDate): Pair<UUID, RequestPostProcessor> {
        val user: TestUser = execution.userWithTemplate(RoleTemplate.ROOM_ATTENDANT)
        return plans.attendant(date, userId = user.id.value) to iam.asTestUser(user)
    }

    private fun publish(
        date: LocalDate,
        employees: List<UUID>,
        item: Pair<UUID, UUID>,
    ) {
        plans.draft(date, employees, listOf(item))
        plans.published(date)
    }

    private fun position(task: UUID): Long =
        jdbc
            .sql("SELECT change_seq FROM housekeeping.housekeeping_task WHERE id = :id")
            .param("id", task)
            .query(Long::class.java)
            .single()

    private data class Stay(
        val room: UUID,
        val reservation: ReservationId,
        val guest: UUID,
        val name: String,
        val email: String,
        val cpf: String,
        val cpfDigits: String,
    )

    /** Uma reserva hospedada hoje numa UH nova, com titular de nome, e-mail e CPF conhecidos. */
    private fun inHouseGuest(): Stay {
        val (type, rooms) = reservations.inventory.roomTypeWithRooms(1, today)
        val room = rooms.single()
        val plan = reservations.plan(listOf(type))
        reservations.setPrices(plan.id, listOf(type), today, today.plusDays(2))
        val guests = reservations.guests
        val name = "Hóspede Feed ${UUID.randomUUID().toString().takeLast(NAME_SUFFIX)}"
        val email = guests.uniqueEmail()
        val cpfDigits = GuestsTestSupport.newCpf()
        val cpf = GuestsTestSupport.formattedCpf(cpfDigits)
        val guest =
            UUID.fromString(
                guests
                    .createGuest(
                        guests.createBody(
                            profile = guests.profileBody(fullName = name, email = email),
                            documents = listOf(guests.document("CPF", cpf)),
                        ),
                    )["id"]
                    .asString(),
            )
        val created =
            reservations.created(
                reservations.booking(
                    guest,
                    reservations.reservation(type, plan.id, today, today.plusDays(2), adults = 1),
                ),
            )["reservations"][0]
        val reservation = ReservationId(UUID.fromString(created["id"].asString()))
        reservationsApi.assignRoom(reservation, RoomId(room))
        reservationsApi.checkIn(
            reservation,
            CheckIn(
                listOf(
                    CheckInGuest(GuestId(guest), primary = true, travelReason = "LAZER", transportMode = "AUTOMOVEL"),
                ),
            ),
        )
        return Stay(room, reservation, guest, name, email, cpf, cpfDigits)
    }

    private companion object {
        /** A data do plano da reatribuição, dentro da janela de 7 dias (o plano alheio é tolerado). */
        const val DAYS_AHEAD = 6L
        const val MAX_PAGES = 500
        const val NAME_SUFFIX = 8
    }
}
