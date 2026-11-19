package com.zeny.hotel.housekeeping

import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.checklists.ChecklistsApi
import com.zeny.hotel.checklists.ChecklistsTestSupport
import com.zeny.hotel.checklists.SyncCommand
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.GuestServiceFlag
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.InventoryListenersQuiescence
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * O ataque da etapa E19 (M6) aos comandos offline, ao feed `housekeeping-tasks` e ao conflito RN-22: o que as suítes
 * das fatias deixaram de fora (a defasagem do relógio contra a última transição nas respostas e na observação, a posse
 * antes da versão do checklist, a permissão, o sinal de não perturbe, os *tombstones* da retirada, o índice de mudança,
 * o aviso na segunda perda e nos lotes simultâneos, o resumo do conflito sem texto livre e sem dado de hóspede).
 *
 * O banco é compartilhado: cada teste afirma só sobre as próprias tarefas, UHs e camareiras. A reatribuição e a
 * retirada passam pela publicação do plano de uma data futura da janela ([DAYS_AHEAD]).
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList", "LargeClass") // os apoios do contexto; um ataque por comportamento
class HousekeepingTaskOfflineAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val checklistsApi: ChecklistsApi,
    @Autowired reservationsApi: ReservationsApi,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired jsonMapper: JsonMapper,
    @Autowired mockMvc: MockMvc,
    @Autowired staffApi: StaffApi,
) {
    private val execution = HousekeepingExecutionTestSupport(iam, jdbc, staffApi)
    private val housekeeping = execution.housekeeping
    private val plans = execution.plans
    private val templates = ChecklistsTestSupport(iam)
    private val reservations = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val stays = HousekeepingStays(reservations, reservationsApi, hotel)

    @BeforeEach
    fun neutralizeGeneralTemplates() = execution.neutralizeGeneralHousekeepingTemplates()

    // --- defasagem do relógio ---------------------------------------------------------------------------------------

    @Test
    fun `answeredAt and observedAt corrected to before the last transition are never written before it`() {
        val attendant = execution.attendant()
        val auth = iam.asTestUser(attendant.user)
        val room = housekeeping.createRoom()
        val task = task(attendant, type(publishedTemplate()), room)
        val now = Instant.now()
        // O início chega com o relógio certo: a última transição é agora.
        sync(auth, command("start-task", mapOf("taskId" to task), now, now)).single()["status"].asString() shouldBe
            "APPLIED"
        val lastTransition = instant(taskRow(task)["last_transition_at"])
        // Aparelho 2 h atrasado: conclusão "há 30 min" e resposta "há 45 min" do relógio dele, corrigidas, caem antes
        // do início da tarefa no servidor.
        val deviceClock = now.minus(Duration.ofHours(2))
        val answer = mapOf("itemKey" to "bed", "check" to "OK", "answeredAt" to deviceClock.minus(MIN_45))
        val results =
            sync(
                auth,
                command(
                    "complete-task",
                    mapOf("taskId" to task, "answers" to listOf(answer), "foundOccupancy" to "VACANT"),
                    deviceClock.minus(MIN_30),
                    deviceClock,
                ),
            )

        results.single()["status"].asString() shouldBe "APPLIED_WITH_WARNINGS"
        val row = taskRow(task)
        val checklist = checklistsApi.execution(ChecklistExecutionId(row["checklist_execution_id"] as UUID))!!
        val observedAt =
            jdbc
                .sql("SELECT observed_at FROM rooms.housekeeping_observation WHERE room_id = :id")
                .param("id", room)
                .query { rs, _ -> rs.getTimestamp(1).toInstant() }
                .single()
        assertSoftly {
            instant(row["finished_at"]) shouldBeGreaterThanOrEqualTo lastTransition
            checklist.completedAt.shouldNotBeNull() shouldBeGreaterThanOrEqualTo lastTransition
            checklist.answers.single { it.itemKey == "bed" }.answeredAt shouldBeGreaterThanOrEqualTo lastTransition
            observedAt shouldBeGreaterThanOrEqualTo lastTransition
        }
    }

    // --- autorização ------------------------------------------------------------------------------------------------

    @Test
    fun `an OWN attendant who never owned the task is out of scope before the checklist version is looked at`() {
        val owner = execution.attendant()
        val stranger = execution.attendant()
        val task = task(owner, type(), housekeeping.createRoom())
        val now = Instant.now()

        val result =
            sync(
                iam.asTestUser(stranger.user),
                command(
                    "start-task",
                    mapOf("taskId" to task, "checklistVersionId" to UuidV7.generate()),
                    now,
                    now,
                ),
            ).single()

        assertSoftly {
            result["status"].asString() shouldBe "REJECTED"
            result["code"].asString() shouldBe "RESOURCE_OUT_OF_SCOPE"
            taskRow(task)["status"] shouldBe "ASSIGNED"
            conflicts(task) shouldHaveSize 0
        }
    }

    @Test
    fun `a user without housekeeping tasks execute is rejected PERMISSION_DENIED and the task does not change`() {
        val owner = execution.attendant()
        val task = task(owner, type(), housekeeping.createRoom())
        val bare: TestUser = iam.createUser()
        val before = taskRow(task)
        val now = Instant.now()

        val results =
            sync(
                iam.asTestUser(bare),
                command("start-task", mapOf("taskId" to task), now, now),
                command("pause-task", mapOf("taskId" to task), now, now),
            )

        assertSoftly {
            results.map { it["status"].asString() } shouldContainExactly List(2) { "REJECTED" }
            results.map { it["code"].asString() } shouldContainExactly List(2) { "PERMISSION_DENIED" }
            taskRow(task)["version"] shouldBe before["version"]
            conflicts(task) shouldHaveSize 0
        }
    }

    // --- não perturbe -----------------------------------------------------------------------------------------------

    @Test
    fun `refuse-task offline with DO_NOT_DISTURB sets the flag on the occupied room only`() {
        val attendant = execution.attendant()
        val auth = iam.asTestUser(attendant.user)
        val occupied = RoomId(housekeeping.createRoom())
        roomsApi.markOccupied(occupied, 2, RoomStatusSource.CHECK_IN)
        val vacant = RoomId(housekeeping.createRoom())
        val vacantBefore = roomsApi.status(vacant).shouldNotBeNull()
        val onOccupied = task(attendant, type(), occupied.value)
        val onVacant = task(attendant, type(), vacant.value)
        val now = Instant.now()

        val results =
            sync(
                auth,
                command("refuse-task", mapOf("taskId" to onOccupied, "reason" to "DO_NOT_DISTURB"), now, now),
                command("refuse-task", mapOf("taskId" to onVacant, "reason" to "DO_NOT_DISTURB"), now, now),
            )

        assertSoftly {
            results.map { it["status"].asString() } shouldContainExactly List(2) { "APPLIED" }
            taskRow(onOccupied)["status"] shouldBe "REFUSED"
            taskRow(onOccupied)["refusal_reason"] shouldBe "DO_NOT_DISTURB"
            roomsApi.status(occupied).shouldNotBeNull().serviceFlag shouldBe GuestServiceFlag.DO_NOT_DISTURB
            taskRow(onVacant)["status"] shouldBe "REFUSED"
            roomsApi.status(vacant) shouldBe vacantBefore
        }
    }

    // --- tombstones e índice ----------------------------------------------------------------------------------------

    @Test
    fun `a withdrawn task is a tombstone for the former owner and a derived one for the ALL feed`() {
        val date = today.plusDays(DAYS_AHEAD)
        val ana = scheduledAttendant(date)
        val keeper = iam.asTestUser(execution.userWithTemplate(RoleTemplate.HOUSEKEEPER))
        val task = futureTask(date)
        publish(date, listOf(ana.employee), task to ana.employee)
        val anaFirst = download(ana.auth)
        val keeperFirst = download(keeper)
        changesOf(anaFirst.changes, task).last()["deleted"].asBoolean() shouldBe false

        publish(date, listOf(ana.employee), task to null)

        assertSoftly {
            taskRow(task)["status"] shouldBe "PENDING"
            withClue("Ana recebe o tombstone de escopo") {
                changesOf(download(ana.auth, anaFirst.cursor).changes, task)
                    .last()["deleted"]
                    .asBoolean() shouldBe true
            }
            withClue("a Governanta recebe a linha PENDING como tombstone derivado") {
                changesOf(download(keeper, keeperFirst.cursor).changes, task)
                    .last()["deleted"]
                    .asBoolean() shouldBe true
            }
            tombstoneScopes(task) shouldContainExactly listOf(setOf(ana.user))
        }
    }

    @Test
    fun `changing only the position creates no scope tombstone`() {
        val date = today.plusDays(DAYS_AHEAD)
        val ana = scheduledAttendant(date)
        val first = futureTask(date)
        val second = futureTask(date)
        publish(date, listOf(ana.employee), first to ana.employee, second to ana.employee)
        val before = download(ana.auth)

        publish(date, listOf(ana.employee), second to ana.employee, first to ana.employee)

        assertSoftly {
            tombstoneScopes(first) shouldHaveSize 0
            tombstoneScopes(second) shouldHaveSize 0
            val after = download(ana.auth, before.cursor)
            changesOf(after.changes, first).forEach { it["deleted"].asBoolean() shouldBe false }
            changesOf(after.changes, second).forEach { it["deleted"].asBoolean() shouldBe false }
        }
    }

    @Test
    fun `a former owner with no linked user generates neither tombstone nor error`() {
        val date = today.plusDays(DAYS_AHEAD)
        val noUser = plans.attendant(date)
        val bia = scheduledAttendant(date)
        val task = futureTask(date)
        publish(date, listOf(noUser, bia.employee), task to noUser)

        publish(date, listOf(noUser, bia.employee), task to bia.employee)

        assertSoftly {
            tombstoneScopes(task) shouldHaveSize 0
            changesOf(download(bia.auth).changes, task).last()["deleted"].asBoolean() shouldBe false
            taskRow(task)["assigned_employee_id"] shouldBe bia.employee
        }
    }

    @Test
    fun `the offline change index covers change_xid and change_seq in this order`() {
        val definition =
            jdbc
                .sql(
                    "SELECT indexdef FROM pg_indexes WHERE schemaname = 'housekeeping' " +
                        "AND tablename = 'housekeeping_task' AND indexname = 'housekeeping_task_offline_change_idx'",
                ).query(String::class.java)
                .single()

        definition shouldContain "(change_xid, change_seq)"
    }

    // --- conflito ---------------------------------------------------------------------------------------------------

    @Test
    fun `a second loss A to B to A to C records the conflict again but does not notify again`() {
        val ana = execution.attendant()
        val bia = execution.attendant()
        val carla = execution.attendant()
        val keeper = execution.userWithTemplate(RoleTemplate.HOUSEKEEPER)
        val room = housekeeping.createRoom()
        val task = task(ana, type(), room)
        execution.assign(task, bia.employee)
        val now = Instant.now()
        sync(iam.asTestUser(ana.user), command("pause-task", mapOf("taskId" to task), now, now))
            .single()["status"]
            .asString() shouldBe "APPLIED_WITH_WARNINGS"
        execution.assign(task, ana.employee)
        execution.assign(task, carla.employee)
        val versionBeforeSecond = taskRow(task)["version"]

        val second =
            sync(iam.asTestUser(ana.user), command("pause-task", mapOf("taskId" to task), now, now)).single()

        assertSoftly {
            second["status"].asString() shouldBe "APPLIED_WITH_WARNINGS"
            conflicts(task) shouldHaveSize 2
            taskRow(task)["has_conflict"] shouldBe true
            withClue("a marca já estava posta: a versão não sobe") {
                taskRow(task)["version"] shouldBe versionBeforeSecond
            }
            conflictNotices(keeper, roomNumber(room)) shouldHaveSize 1
        }
    }

    @Test
    @Timeout(value = 90, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `two simultaneous batches of the same former owner notify once`() {
        val ana = execution.attendant()
        val bia = execution.attendant()
        val keeper = execution.userWithTemplate(RoleTemplate.HOUSEKEEPER)
        val room = housekeeping.createRoom()
        val task = task(ana, type(), room)
        execution.assign(task, bia.employee)
        val auth = iam.asTestUser(ana.user)
        val now = Instant.now()
        val holderLocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holderPid = AtomicInteger()
        val pool = Executors.newFixedThreadPool(THREADS + 1)
        try {
            // A trava da tarefa fica numa transação à parte: os dois lotes chegam e esperam por ela.
            val holder =
                pool.submit {
                    transactions.executeWithoutResult {
                        holderPid.set(jdbc.sql("SELECT pg_backend_pid()").query(Int::class.java).single())
                        jdbc
                            .sql("SELECT id FROM housekeeping.housekeeping_task WHERE id = :id FOR UPDATE")
                            .param("id", task)
                            .query(UUID::class.java)
                            .single()
                        holderLocked.countDown()
                        release.await(LATCH_SECONDS, TimeUnit.SECONDS)
                    }
                }
            holderLocked.await(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe true
            val batches =
                List(THREADS) {
                    pool.submit<JsonNode> {
                        sync(auth, command("pause-task", mapOf("taskId" to task), now, now)).single()
                    }
                }
            try {
                await().atMost(Duration.ofSeconds(LATCH_SECONDS)).until { waitingOn(holderPid.get()) == THREADS }
            } finally {
                release.countDown()
            }
            holder.get(LATCH_SECONDS, TimeUnit.SECONDS)
            val results = batches.map { it.get(LATCH_SECONDS, TimeUnit.SECONDS) }

            assertSoftly {
                results.map { it["status"].asString() } shouldContainExactly List(THREADS) { "APPLIED_WITH_WARNINGS" }
                conflicts(task) shouldHaveSize THREADS
                taskRow(task)["has_conflict"] shouldBe true
                conflictNotices(keeper, roomNumber(room)) shouldHaveSize 1
            }
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `the conflict notice and record carry no guest or reservation data of the stay in the room`() {
        val (roomType, rooms) = reservations.inventory.roomTypeWithRooms(1, today)
        val plan = stays.planFor(roomType)
        val guestName = "Hospede Conflito ${UuidV7.generate().toString().takeLast(NAME_SUFFIX)}"
        val (reservation, holder) = stays.inHouse(roomType, plan, rooms.single(), today.plusDays(1), guestName)
        val ana = execution.attendant()
        val bia = execution.attendant()
        val keeper = execution.userWithTemplate(RoleTemplate.HOUSEKEEPER)
        val task = task(ana, type(), rooms.single())
        execution.assign(task, bia.employee)
        val now = Instant.now()
        val refuse = command("refuse-task", mapOf("taskId" to task, "reason" to "GUEST_REFUSED"), now, now)

        sync(iam.asTestUser(ana.user), refuse).single()["status"].asString() shouldBe "APPLIED_WITH_WARNINGS"

        val notices = conflictNotices(keeper, roomNumber(rooms.single()))
        val text = notices.joinToString("\n") { it.toString() } + "\n" + conflictsText(task)
        assertSoftly {
            notices shouldHaveSize 1
            text shouldNotContain guestName
            text shouldNotContain guestName.substringBefore(" ")
            text shouldNotContain holder.toString()
            text shouldNotContain reservation.value.toString()
        }
    }

    @Test
    fun `the conflict summary drops item keys and choices that are not in the checklist key format`() {
        val ana = execution.attendant()
        val bia = execution.attendant()
        val task = task(ana, type(), housekeeping.createRoom())
        execution.assign(task, bia.employee)
        val now = Instant.now()
        val answers =
            listOf(
                mapOf("itemKey" to "Joao Silva CPF 123.456.789-09", "check" to "OK"),
                mapOf("itemKey" to "pick", "choice" to "Rua das Flores 12 apto 3", "note" to NOTE),
                mapOf("itemKey" to "multi", "choices" to listOf("ok", "Maria Souza", "tel 11 98888-7777")),
                mapOf("itemKey" to "free", "text" to NOTE),
            )

        val result =
            sync(
                iam.asTestUser(ana.user),
                command("complete-task", mapOf("taskId" to task, "answers" to answers), now, now),
            ).single()

        val text = conflictsText(task)
        assertSoftly {
            result["status"].asString() shouldBe "APPLIED_WITH_WARNINGS"
            conflicts(task) shouldHaveSize 1
            listOf("Joao", "Silva", "123.456", "Flores", "Maria", "Souza", "98888", NOTE).forEach {
                withClue(it) { text shouldNotContain it }
            }
            text shouldContain "multi"
            text shouldContain "\"ok\""
        }
    }

    // --- apoio ------------------------------------------------------------------------------------------------------

    private val today: LocalDate get() = hotel.currentBusinessDate()

    private fun command(
        verb: String,
        payload: Map<String, Any?>,
        occurredAt: Instant,
        deviceClock: Instant,
    ) = SyncCommand(UuidV7.generate(), "housekeeping.$verb", payload, occurredAt, deviceClock)

    private fun sync(
        auth: RequestPostProcessor,
        vararg commands: SyncCommand,
    ): List<JsonNode> =
        with(iam) {
            call(POST, "/api/v1/sync/commands", auth, mapOf("commands" to commands.map { it.body() }))
                .ok()["results"]
                .values()
                .toList()
        }

    private fun type(checklistTemplateId: UUID? = null): UUID =
        housekeeping.createCleaningType(housekeeping.cleaningTypeBody(checklistTemplateId = checklistTemplateId))

    private fun task(
        attendant: HousekeepingExecutionTestSupport.Attendant,
        type: UUID,
        room: UUID,
    ): UUID =
        UUID
            .fromString(housekeeping.requestTask(housekeeping.taskBody(type, roomId = room))["id"].asString())
            .also { execution.assign(it, attendant.employee) }

    /** Um modelo publicado com `bed` obrigatório e `towels`, que só entra pelo vínculo do tipo. */
    private fun publishedTemplate(): UUID {
        val id =
            templates.create(
                templates.templateBody(
                    context = ChecklistContext.HOUSEKEEPING,
                    applicability = listOf(mapOf("cleaningTypeIds" to listOf(UuidV7.generate()))),
                    sections =
                        listOf(
                            templates.section(
                                "Quarto",
                                templates.item("bed", extra = mapOf("required" to true)),
                                templates.item("towels"),
                            ),
                        ),
                ),
            )
        templates.publish(id)
        return id
    }

    private fun taskRow(task: UUID): Map<String, Any?> =
        jdbc
            .sql("SELECT * FROM housekeeping.housekeeping_task WHERE id = :id")
            .param("id", task)
            .query()
            .singleRow()

    private fun instant(value: Any?): Instant = (value as Timestamp).toInstant()

    private fun conflicts(task: UUID): List<Map<String, Any?>> =
        jdbc
            .sql("SELECT * FROM housekeeping.task_conflict WHERE task_id = :task ORDER BY recorded_at, id")
            .param("task", task)
            .query()
            .listOfRows()

    private fun conflictsText(task: UUID): String =
        jdbc
            .sql("SELECT string_agg(row_to_json(c)::text, ' ') FROM housekeeping.task_conflict c WHERE task_id = :t")
            .param("t", task)
            .query(String::class.java)
            .single()

    private fun roomNumber(room: UUID): String = roomsApi.room(RoomId(room)).shouldNotBeNull().number

    private fun conflictNotices(
        user: TestUser,
        number: String,
    ): List<JsonNode> =
        with(iam) { call(GET, "/api/v1/notifications?limit=100", asTestUser(user)).ok() }["items"]
            .values()
            .filter { it["type"].asString() == "housekeeping.task-conflict" }
            .filter { "UH $number" in it["title"].asString() + " " + it["body"].asString() }

    /** Os `left_scope_of` dos tombstones da fonte `housekeeping-tasks` gravados para a [task]. */
    private fun tombstoneScopes(task: UUID): List<Set<UUID>> =
        jdbc
            .sql("SELECT left_scope_of FROM shared.sync_tombstone WHERE source = :s AND entity_id = :id")
            .param("s", HousekeepingOffline.TASKS.name)
            .param("id", task)
            .query { rs, _ -> (rs.getArray(1).array as Array<*>).map { it as UUID }.toSet() }
            .list()

    /** Sessões esperando a trava de [holder] (`pg_blocking_pids`), direta ou na fila atrás de outra que espera. */
    private fun waitingOn(holder: Int): Int =
        jdbc
            .sql(
                """
                SELECT count(*) FROM pg_stat_activity a
                WHERE a.datname = current_database() AND a.wait_event_type = 'Lock'
                  AND (:holder = ANY (pg_blocking_pids(a.pid))
                       OR EXISTS (SELECT 1 FROM unnest(pg_blocking_pids(a.pid)) AS b(pid)
                                  WHERE :holder = ANY (pg_blocking_pids(b.pid))))
                """.trimIndent(),
            ).param("holder", holder)
            .query(Int::class.java)
            .single()

    private data class Scheduled(
        val employee: UUID,
        val user: UUID,
        val auth: RequestPostProcessor,
    )

    /** Uma camareira escalada em [date] e vinculada a um usuário com o modelo Camareira. */
    private fun scheduledAttendant(date: LocalDate): Scheduled {
        val user = execution.userWithTemplate(RoleTemplate.ROOM_ATTENDANT)
        return Scheduled(plans.attendant(date, userId = user.id.value), user.id.value, iam.asTestUser(user))
    }

    private fun futureTask(date: LocalDate): UUID =
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

    private fun publish(
        date: LocalDate,
        employees: List<UUID>,
        vararg items: Pair<UUID, UUID?>,
    ) {
        plans.draft(date, employees, items.toList())
        plans.published(date)
    }

    private data class Download(
        val changes: List<JsonNode>,
        val cursor: String,
    )

    private fun download(
        auth: RequestPostProcessor,
        from: String? = null,
    ): Download =
        with(iam) {
            val changes = mutableListOf<JsonNode>()
            var cursor = from
            var pages = 0
            do {
                InventoryListenersQuiescence.await(jdbc)
                val page =
                    call(GET, "/api/v1/sync/changes?limit=200" + cursor?.let { "&cursor=$it" }.orEmpty(), auth).ok()
                changes += page["changes"].values()
                cursor = page["cursor"].asString()
                pages++
                withClue("download sem fim") { (pages < MAX_PAGES) shouldBe true }
            } while (page["hasMore"].asBoolean())
            Download(changes, checkNotNull(cursor))
        }

    private fun changesOf(
        changes: List<JsonNode>,
        task: UUID,
    ): List<JsonNode> =
        changes
            .filter { it["source"].asString() == HousekeepingOffline.TASKS.name && it["id"].asString() == "$task" }
            .also { withClue("o feed traz a tarefa $task") { it.isEmpty() shouldBe false } }

    private companion object {
        const val DAYS_AHEAD = 6L
        const val MAX_PAGES = 500
        const val NAME_SUFFIX = 8
        const val THREADS = 2
        const val LATCH_SECONDS = 30L
        const val NOTE = "Ligar para a Dona Maria no (11) 98888-7777"
        val MIN_30: Duration = Duration.ofMinutes(30)
        val MIN_45: Duration = Duration.ofMinutes(45)
    }
}
