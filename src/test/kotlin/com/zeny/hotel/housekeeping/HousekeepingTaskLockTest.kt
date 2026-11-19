package com.zeny.hotel.housekeeping

import com.zeny.hotel.checklists.ChecklistPhotoPolicy
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.application.HousekeepingTaskExecution
import com.zeny.hotel.housekeeping.application.ScheduledCleaningsService
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.rooms.application.HousekeepingTransition
import com.zeny.hotel.rooms.application.RoomStatusService
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A ordem de trava da execução da tarefa (M6 E04, Task 3; D9 da E04: tarefa, execução do checklist, UH, faxina
 * programada): ações concorrentes na mesma tarefa, e a conclusão concorrente com o `rooms` e com a edição da faxina, se
 * enfileiram sem deadlock, e a segunda vê o que a primeira confirmou.
 *
 * - **Ação × conclusão** na mesma tarefa: a segunda espera a trava da tarefa e vê o estado novo (`412` com a versão
 *   lida antes, `409 HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED` sem versão, como o offline da E19), nunca `500` nem
 *   `409 CONCURRENT_UPDATE`.
 * - **Conclusão × limpeza manual da UH** (`RoomStatusService.changeHousekeeping`): a limpeza manual confirmada antes
 *   deixa a UH Limpa, e a conclusão que a trava depois a acha Limpa e vai a `DONE` (D3), sem inspeção pendente; a
 *   conclusão confirmada antes leva a UH Suja a Limpa com a inspeção pendente, e a limpeza manual com a versão lida
 *   antes dá `412`.
 * - **Conclusão × edição da faxina programada**: a conclusão espera a edição na trava da faxina e conclui sem `412`
 *   (a faxina é relida com a trava); a edição que espera a conclusão dá `412` (a conclusão registrou a faxina feita).
 *
 * Determinístico, sem espera cega (padrão do `ScheduledCleaningsTypeLockTest`): a primeira operação roda numa
 * transação que fica aberta depois dela; a segunda começa noutra thread, e o teste espera (com prazo) que a sessão dela
 * fique bloqueada pela da primeira (`pg_blocking_pids`) com a relação disputada entre as travadas. Só então a primeira
 * confirma. Os casos de uso rodam direto, cada um como o seu usuário; toda transação das threads tem `lock_timeout`,
 * todo latch tem prazo, e o `finally` solta o latch e encerra as threads. As tarefas daqui não têm checklist (os
 * modelos gerais de governança são neutralizados antes de cada teste).
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList") // os casos de uso e os apoios das corridas
class HousekeepingTaskLockTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val roomStatus: RoomStatusService,
    @Autowired private val tasks: HousekeepingTaskExecution,
    @Autowired private val schedules: ScheduledCleaningsService,
    @Autowired staffApi: StaffApi,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val write = TransactionTemplate(transactionManager)
    private val execution = HousekeepingExecutionTestSupport(iam, jdbc, staffApi)
    private val housekeeping = execution.housekeeping

    @BeforeEach
    fun neutralizeGeneralTemplates() = execution.neutralizeGeneralHousekeepingTemplates()

    // --- (a) ação × conclusão na mesma tarefa --------------------------------------------------------------------

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a pause in flight holds the completion, which then sees the new version - 412`() {
        val (attendant, task) = startedTask(type())
        val version = taskVersion(task)
        race(
            first = attendant to { tasks.pause(task, actorOf(attendant), Instant.now(), version) },
            second = attendant to { complete(task, attendant, version) },
            relation = TASK_RELATION,
        ) { first, second ->
            first.isSuccess shouldBe true
            second.code() shouldBe "PRECONDITION_FAILED"
        }
        taskStatus(task) shouldBe "PAUSED"
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a completion in flight holds the pause, which then sees the task done - 409 transition`() {
        val (attendant, task) = startedTask(type())
        val version = taskVersion(task)
        race(
            first = attendant to { complete(task, attendant, version) },
            second = attendant to { tasks.pause(task, actorOf(attendant), Instant.now(), null) },
            relation = TASK_RELATION,
        ) { first, second ->
            first.isSuccess shouldBe true
            second.code() shouldBe "HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED"
        }
        taskStatus(task) shouldBe "DONE"
    }

    // --- (b) conclusão × limpeza manual da UH pelo rooms ----------------------------------------------------------

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a manual cleaning in flight holds the completion, which then finds the room clean - DONE`() {
        val room = dirtyRoom()
        val (attendant, task) = startedTask(type(requiresInspection = true), room)
        val roomVersion = roomsApi.status(room).shouldNotBeNull().version
        race(
            first = admin() to { manualClean(room, roomVersion) },
            second = attendant to { complete(task, attendant, taskVersion(task)) },
            relation = ROOM_RELATION,
        ) { first, second ->
            first.isSuccess shouldBe true
            withClue(second.exceptionOrNull()) { second.isSuccess shouldBe true }
        }
        taskStatus(task) shouldBe "DONE"
        roomsApi.status(room).shouldNotBeNull().let {
            it.housekeepingStatus shouldBe HousekeepingStatus.CLEAN
            it.inspectionPending shouldBe false
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a completion in flight holds the manual cleaning, which then sees the new room version - 412`() {
        val room = dirtyRoom()
        val (attendant, task) = startedTask(type(requiresInspection = true), room)
        val roomVersion = roomsApi.status(room).shouldNotBeNull().version
        val version = taskVersion(task)
        race(
            first = attendant to { complete(task, attendant, version) },
            second = admin() to { manualClean(room, roomVersion) },
            relation = ROOM_RELATION,
        ) { first, second ->
            first.isSuccess shouldBe true
            second.code() shouldBe "PRECONDITION_FAILED"
        }
        taskStatus(task) shouldBe "AWAITING_INSPECTION"
        roomsApi.status(room).shouldNotBeNull().let {
            it.housekeepingStatus shouldBe HousekeepingStatus.CLEAN
            it.inspectionPending shouldBe true
        }
    }

    // --- (c) conclusão × edição da faxina programada ---------------------------------------------------------------

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a schedule edit in flight holds the completion, which then completes without 412`() {
        val type = type()
        val room = housekeeping.createRoom()
        val today = hotel.currentBusinessDate()
        val schedule = schedule(type, room, today.minusDays(1))
        val (attendant, task) = startedScheduledTask(schedule, type, room, today)
        val scheduleVersion = scheduleVersion(schedule)
        race(
            first =
                admin() to {
                    schedules.update(
                        ScheduledCleaningId(schedule),
                        scheduleVersion,
                        CleaningTypeId(type),
                        NEW_FREQUENCY,
                    )
                },
            second = attendant to { complete(task, attendant, taskVersion(task)) },
            relation = SCHEDULE_RELATION,
        ) { first, second ->
            first.isSuccess shouldBe true
            withClue(second.exceptionOrNull()) { second.isSuccess shouldBe true }
        }
        taskStatus(task) shouldBe "DONE"
        scheduleRow(schedule).let {
            it["frequency_days"] shouldBe NEW_FREQUENCY
            it["last_done_on"].toString() shouldBe today.toString()
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a completion in flight holds the schedule edit, which then sees the new schedule version - 412`() {
        val type = type()
        val room = housekeeping.createRoom()
        val today = hotel.currentBusinessDate()
        val schedule = schedule(type, room, today.minusDays(1))
        val (attendant, task) = startedScheduledTask(schedule, type, room, today)
        val scheduleVersion = scheduleVersion(schedule)
        val version = taskVersion(task)
        race(
            first = attendant to { complete(task, attendant, version) },
            second =
                admin() to {
                    schedules.update(
                        ScheduledCleaningId(schedule),
                        scheduleVersion,
                        CleaningTypeId(type),
                        NEW_FREQUENCY,
                    )
                },
            relation = SCHEDULE_RELATION,
        ) { first, second ->
            first.isSuccess shouldBe true
            second.code() shouldBe "PRECONDITION_FAILED"
        }
        taskStatus(task) shouldBe "DONE"
        scheduleRow(schedule)["last_done_on"].toString() shouldBe today.toString()
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    /**
     * Roda [first] (como o usuário do par) numa transação que fica aberta, depois [second] em outra thread; espera
     * a sessão de [second] ficar bloqueada pela de [first] com [relation] entre as travadas, confirma [first] e
     * entrega os dois resultados a [verify].
     */
    private fun race(
        first: Pair<UUID, () -> Any>,
        second: Pair<UUID, () -> Any>,
        relation: String,
        verify: (Result<Any>, Result<Any>) -> Unit,
    ) {
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val pid = AtomicInteger()
            val ran = CountDownLatch(1)
            val held =
                pool.submit<Result<Any>> {
                    inTransaction(first.first) {
                        pid.set(backendPid())
                        runCatching(first.second).also {
                            ran.countDown()
                            release.await(LATCH_SECONDS, TimeUnit.SECONDS)
                        }
                    }
                }
            if (!ran.await(LATCH_SECONDS, TimeUnit.SECONDS)) {
                if (held.isDone) held.get()
                error("A primeira operação não rodou em $LATCH_SECONDS s")
            }
            val contender = pool.submit<Result<Any>> { inTransaction(second.first) { runCatching(second.second) } }
            awaitBlocked(pid.get(), relation, contender)
            release.countDown()

            verify(held.get(LATCH_SECONDS, TimeUnit.SECONDS), contender.get(LATCH_SECONDS, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            pool.shutdown()
            pool.awaitTermination(LATCH_SECONDS * 2, TimeUnit.SECONDS)
            pool.shutdownNow()
        }
    }

    /**
     * Espera, com prazo, alguma sessão bloqueada pela sessão [holder] (`pg_blocking_pids`) e com [relation] entre as
     * relações travadas; [contender] não pode terminar antes disso.
     */
    private fun awaitBlocked(
        holder: Int,
        relation: String,
        contender: Future<*>,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LATCH_SECONDS)
        while (blockedOn(holder, relation) == 0) {
            withClue("a segunda operação terminou sem esperar a trava de $relation: ${contender.resultText()}") {
                contender.isDone shouldBe false
            }
            check(System.nanoTime() < deadline) { "A segunda operação não chegou a esperar a sessão $holder" }
            Thread.sleep(POLL_MILLIS)
        }
        contender.isDone shouldBe false
    }

    private fun Future<*>.resultText(): String = if (isDone) runCatching { get() }.toString() else "em andamento"

    private fun blockedOn(
        holder: Int,
        relation: String,
    ): Int =
        jdbc
            .sql(
                """
                SELECT count(*) FROM pg_stat_activity a
                WHERE a.datname = current_database() AND :holder = ANY (pg_blocking_pids(a.pid))
                  AND EXISTS (SELECT 1 FROM pg_locks l
                              WHERE l.pid = a.pid AND l.relation = CAST(:relation AS regclass))
                """.trimIndent(),
            ).param("holder", holder)
            .param("relation", relation)
            .query(Int::class.java)
            .single()

    /**
     * Roda [block] como o usuário [userId], numa transação com `lock_timeout`; uma recusa do caso de uso desfaz a
     * transação (que ele já marcou para desfazer), sem `UnexpectedRollbackException`.
     */
    private fun inTransaction(
        userId: UUID,
        block: () -> Result<Any>,
    ): Result<Any> =
        actingAs(userId) {
            checkNotNull(
                write.execute { status ->
                    jdbc.sql("SET LOCAL lock_timeout = '$LOCK_TIMEOUT'").update()
                    block().also { if (it.isFailure) status.setRollbackOnly() }
                },
            )
        }

    private fun <T> actingAs(
        userId: UUID,
        block: () -> T,
    ): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(userId.toString())
                .claim("sid", SESSION.toString())
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun actorOf(userId: UUID) = Actor(UserId(userId), DeviceSessionId(SESSION))

    private fun complete(
        task: HousekeepingTaskId,
        userId: UUID,
        expectedVersion: Long?,
    ) = tasks.complete(
        task,
        null,
        null,
        null,
        actorOf(userId),
        Instant.now(),
        expectedVersion,
        ChecklistPhotoPolicy.REFUSE,
    )

    private fun manualClean(
        room: RoomId,
        version: Long,
    ) = roomStatus.changeHousekeeping(room, version, HousekeepingTransition.MARK_CLEAN, requiresInspection = false)

    private fun Result<Any>.code(): String? = (exceptionOrNull() as? DomainException)?.code?.value

    private fun admin(): UUID = iam.adminId()

    private fun type(requiresInspection: Boolean = false): UUID =
        housekeeping.createCleaningType(housekeeping.cleaningTypeBody(requiresInspection = requiresInspection))

    private fun dirtyRoom(): RoomId =
        RoomId(housekeeping.createRoom()).also { roomsApi.markDirty(it, RoomStatusSource.MANUAL) }

    /** Uma tarefa avulsa da data operacional (numa UH nova, sem [room]) atribuída a uma camareira nova e iniciada. */
    private fun startedTask(
        type: UUID,
        room: RoomId = RoomId(housekeeping.createRoom()),
    ): Pair<UUID, HousekeepingTaskId> {
        val attendant = execution.attendant()
        val task =
            UUID.fromString(
                housekeeping.requestTask(housekeeping.taskBody(type, roomId = room.value))["id"].asString(),
            )
        execution.assign(task, attendant.employee)
        execution.acted(task, "start", iam.asTestUser(attendant.user))
        return attendant.user.id.value to HousekeepingTaskId(task)
    }

    /** A tarefa `SCHEDULED` da faxina [schedule] na [date], atribuída a uma camareira nova e iniciada. */
    private fun startedScheduledTask(
        schedule: UUID,
        type: UUID,
        room: UUID,
        date: LocalDate,
    ): Pair<UUID, HousekeepingTaskId> {
        val attendant = execution.attendant()
        val task = scheduledTask(attendant.employee, schedule, type, room, date)
        execution.acted(task, "start", iam.asTestUser(attendant.user))
        return attendant.user.id.value to HousekeepingTaskId(task)
    }

    private fun schedule(
        type: UUID,
        room: UUID,
        lastDoneOn: LocalDate,
    ): UUID =
        housekeeping.createSchedule(
            housekeeping.scheduleBody(type, roomId = room, frequencyDays = FREQUENCY, lastDoneOn = "$lastDoneOn"),
        )

    /**
     * Uma tarefa `SCHEDULED` da faxina [schedule] na [date], já atribuída a [employee]: escrita direta (o que a geração
     * grava), porque a geração roda para o hotel inteiro no banco compartilhado. A faxina tem a próxima data depois de
     * [date], então a geração não cria outra.
     */
    @Suppress("LongParameterList") // os campos da tarefa
    private fun scheduledTask(
        employee: UUID,
        schedule: UUID,
        type: UUID,
        room: UUID,
        date: LocalDate,
    ): UUID {
        val id = UuidV7.generate()
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        jdbc
            .sql(
                """
                INSERT INTO housekeeping.housekeeping_task (id, room_id, business_date, cleaning_type_id, source,
                    status, priority, credits, arrival_expected, scheduled_cleaning_id, assigned_employee_id,
                    assignment_position, created_at, updated_at, last_transition_at, version)
                VALUES (:id, :room, :date, :type, 'SCHEDULED', 'ASSIGNED', 3, 0, false, :schedule, :employee, 1,
                    :now, :now, :now, 0)
                """.trimIndent(),
            ).param("id", id)
            .param("room", room)
            .param("date", date)
            .param("type", type)
            .param("schedule", schedule)
            .param("employee", employee)
            .param("now", now)
            .update() shouldBe 1
        return id
    }

    private fun taskVersion(task: HousekeepingTaskId): Long = row("housekeeping_task", task.value)["version"] as Long

    private fun scheduleVersion(schedule: UUID): Long = scheduleRow(schedule)["version"] as Long

    private fun taskStatus(task: HousekeepingTaskId): String = row("housekeeping_task", task.value)["status"] as String

    private fun scheduleRow(schedule: UUID): Map<String, Any?> = row("scheduled_cleaning", schedule)

    private fun row(
        table: String,
        id: UUID,
    ): Map<String, Any?> =
        jdbc
            .sql("SELECT * FROM housekeeping.$table WHERE id = :id")
            .param("id", id)
            .query()
            .singleRow()

    private fun backendPid(): Int = jdbc.sql("SELECT pg_backend_pid()").query(Int::class.java).single()

    private companion object {
        const val TASK_RELATION = "housekeeping.housekeeping_task"
        const val ROOM_RELATION = "rooms.room"
        const val SCHEDULE_RELATION = "housekeeping.scheduled_cleaning"
        const val FREQUENCY = 7
        const val NEW_FREQUENCY = 5
        const val LATCH_SECONDS = 10L
        const val POLL_MILLIS = 20L
        const val LOCK_TIMEOUT = "15s"

        /** A sessão das ações (o `sid` do token e a do [Actor]); a execução não a confere. */
        val SESSION: UUID = UuidV7.generate()
    }
}
