package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.SCHEDULED_CLEANINGS
import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.TASKS
import com.zeny.hotel.housekeeping.application.HousekeepingTaskGeneration
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.OutOfOrderKind
import com.zeny.hotel.rooms.OutOfOrderRequest
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * As tarefas do dia geradas pela virada da data (M6 E02, Task 3; RF-GOV-02), chamando
 * [HousekeepingTaskGeneration.generateForDate] direto, com a data operacional do banco ou uma futura: as permanências
 * (D9-E02, Review Focus 3), as pré-chegadas (D3-E02), as faxinas programadas vencidas (Review Focus 5), a idempotência
 * (Review Focus 2) e as duas ordens do check-out e da virada (Review Focus 1, ordem B por chamadas diretas; a ordem A
 * ponta a ponta fica no `HousekeepingEndOfDayTasksTest`).
 *
 * O banco é compartilhado e recebe avanços de data de outros testes, e a geração vale para o hotel inteiro: cada teste
 * afirma só sobre as UHs e áreas que criou, nunca sobre totais nem sobre as contagens do resultado; não avança a data
 * nem mexe nos tipos da carga; e deixa inativas as faxinas programadas que cria.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList") // as portas que o teste usa
class HousekeepingDailyTasksTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired private val generation: HousekeepingTaskGeneration,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)
    private val reservations = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val stays = HousekeepingStays(reservations, reservationsApi, hotel)
    private val today: LocalDate get() = hotel.currentBusinessDate()

    // --- Permanência ----------------------------------------------------------------------------------------------

    @Test
    fun `an in-house room gets a STAYOVER, a vacant room none, and a guest leaving on the date none`() {
        // As hospedadas fazem o check-in hoje (D): a permanência começa em D+1 (I1 da revisão final).
        val date = today.plusDays(1)
        val (type, rooms) = reservations.inventory.roomTypeWithRooms(3, today)
        val plan = stays.planFor(type)
        stays.inHouse(type, plan, rooms[0], departure = date.plusDays(2))
        stays.inHouse(type, plan, rooms[2], departure = date.plusDays(1))

        // Cada data duas vezes: a entrega repetida do avanço não duplica nada (Review Focus 2).
        generation.generateForDate(date)
        generation.generateForDate(date)
        generation.generateForDate(date.plusDays(1))
        generation.generateForDate(date.plusDays(1))

        tasksOf(rooms[0]).map { it.date to it.source } shouldBe
            listOf(date to "STAYOVER", date.plusDays(1) to "STAYOVER")
        tasksOf(rooms[0]).forEach {
            it.cleaningTypeId shouldBe systemType("STAYOVER")
            it.status shouldBe "PENDING"
            it.arrivalExpected shouldBe false
            it.scheduledCleaningId shouldBe null
            it.credits shouldBe 0
            generatedBySystem(it.id) shouldBe true
        }
        tasksOf(rooms[1]).shouldBeEmpty()
        // A hospedada que sai em D+1 tem permanência em D, mas não em D+1 (Review Focus 3).
        tasksOf(rooms[2]).map { it.date to it.source } shouldBe listOf(date to "STAYOVER")
    }

    @Test
    fun `a guest checked in on the date gets no STAYOVER on it, only from the next date on`() {
        val date = today
        val (type, rooms) = reservations.inventory.roomTypeWithRooms(1, date)
        stays.inHouse(type, stays.planFor(type), rooms[0], departure = date.plusDays(3))

        // Mesmo com a geração do dia rodando depois do check-in (reentrega atrasada do outbox, I1): a UH acabou de
        // ser preparada para a chegada. Em D+1, a hospedada chegou na véspera e recebe a permanência.
        generation.generateForDate(date)
        tasksOf(rooms[0]).shouldBeEmpty()
        generation.generateForDate(date.plusDays(1))

        tasksOf(rooms[0]).map { it.date to it.source } shouldBe listOf(date.plusDays(1) to "STAYOVER")
    }

    @Test
    fun `the trail of the generated stayover and pre-arrival carries no guest nor reservation data`() {
        val date = today
        val (type, rooms) = reservations.inventory.roomTypeWithRooms(2, date)
        val plan = stays.planFor(type)
        val suffix = UUID.randomUUID().toString().takeLast(SUFFIX_LENGTH)
        val (staying, stayingHolder) =
            stays.inHouse(type, plan, rooms[0], departure = date.plusDays(2), name = "Hóspede Fica $suffix")
        clean(rooms[1])
        val (arriving, arrivingHolder) = stays.reservation(type, plan, arrival = date, name = "Hóspede Chega $suffix")
        assign(arriving, rooms[1])

        generation.generateForDate(date)

        val stayover = tasksOn(rooms[0], date).single()
        val preArrival = tasksOn(rooms[1], date).single()
        stayover.source shouldBe "STAYOVER"
        preArrival.source shouldBe "PRE_ARRIVAL"
        // RN-13: só ids do local e do tipo, a data e a origem; nada do hóspede nem da reserva.
        listOf(stayover.id, preArrival.id).forEach { task ->
            val trail = generateTrail(task)
            listOf(suffix, stayingHolder.toString(), arrivingHolder.toString(), staying.value.toString())
                .plus(arriving.value.toString())
                .forEach { trail shouldNotContain it }
        }
    }

    // --- Pré-chegada ----------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // uma UH por condição da pré-chegada
    fun `a vacant clean room with an arrival and no recent task gets a PRE_ARRIVAL, and the others do not`() {
        val date = today
        val (type, rooms) = reservations.inventory.roomTypeWithRooms(7, date)
        val plan = stays.planFor(type)
        val ready = rooms[0]
        val twoDays = rooms[1]
        val threeDays = rooms[2]
        val dirty = rooms[3]
        val outOfOrder = rooms[4]
        val outOfService = rooms[5]
        val noArrival = rooms[6]
        rooms.forEach { room -> if (room != dirty) clean(room) }
        rooms.filter { it != noArrival }.forEach { room ->
            assign(stays.reservation(type, plan, arrival = date).first, room)
        }
        // A última tarefa há 2 dias barra a pré-chegada; há 3 dias (o padrão de pre-arrival-after-days), não.
        generation.generateCheckout(RoomId(twoDays), date.minusDays(2), TaskSource.CHECKOUT) shouldBe true
        generation.generateCheckout(RoomId(threeDays), date.minusDays(3), TaskSource.CHECKOUT) shouldBe true
        openOutOfOrder(outOfOrder, OutOfOrderKind.OUT_OF_ORDER, date)
        openOutOfOrder(outOfService, OutOfOrderKind.OUT_OF_SERVICE, date)

        // Duas vezes: a segunda rodada não duplica a pré-chegada (Review Focus 2) nem é barrada por ela.
        generation.generateForDate(date)
        generation.generateForDate(date)

        tasksOn(ready, date).single().let {
            it.source shouldBe "PRE_ARRIVAL"
            it.cleaningTypeId shouldBe systemType("PRE_ARRIVAL")
            it.arrivalExpected shouldBe true
            it.status shouldBe "PENDING"
            generatedBySystem(it.id) shouldBe true
        }
        tasksOf(ready).map { it.date to it.source } shouldBe listOf(date to "PRE_ARRIVAL")
        tasksOn(threeDays, date).map { it.source } shouldBe listOf("PRE_ARRIVAL")
        listOf(twoDays, dirty, outOfOrder, outOfService, noArrival).forEach { room ->
            withClue(room) { tasksOn(room, date).shouldBeEmpty() }
        }
    }

    @Test
    fun `in the day list the room with an arrival comes before the stayover`() {
        // Em D+1: a hospedada do check-in de hoje já tem permanência, e a chegada de D+1 tem a pré-chegada.
        val date = today.plusDays(1)
        val (type, rooms) = reservations.inventory.roomTypeWithRooms(2, today)
        val plan = stays.planFor(type)
        stays.inHouse(type, plan, rooms[0], departure = date.plusDays(2))
        clean(rooms[1])
        assign(stays.reservation(type, plan, arrival = date).first, rooms[1])

        generation.generateForDate(date)

        val mine = setOf(rooms[0].toString(), rooms[1].toString())
        val listed =
            housekeeping
                .listAll("$TASKS?date=$date&limit=100")
                .filter { it["roomId"].asString() in mine }
                .map { it["source"].asString() to it["arrivalExpected"].asBoolean() }
        listed shouldBe listOf("PRE_ARRIVAL" to true, "STAYOVER" to false)
    }

    // --- Faxinas programadas --------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // uma faxina por condição, a idempotência e a tarefa aberta
    fun `a due schedule gets a SCHEDULED task, only on an active place out of OOO and without an open task`() {
        val date = today
        val roomType = housekeeping.rooms.createRoomType()
        val cleaningType =
            housekeeping.createCleaningType(
                housekeeping.cleaningTypeBody(credits = listOf(housekeeping.credit(roomType, 7))),
            )
        val room = housekeeping.rooms.createRoom(roomType)
        val area = housekeeping.rooms.createArea()
        val inactiveSchedule = housekeeping.rooms.createRoom(roomType)
        val inactiveRoom = housekeeping.rooms.createRoom(roomType)
        val outOfOrder = housekeeping.rooms.createRoom(roomType)
        val inactiveArea = housekeeping.rooms.createArea()
        val notDue = housekeeping.rooms.createRoom(roomType)
        val schedules =
            mapOf(
                room to schedule(cleaningType, roomId = room),
                area to schedule(cleaningType, areaId = area),
                inactiveSchedule to schedule(cleaningType, roomId = inactiveSchedule),
                inactiveRoom to schedule(cleaningType, roomId = inactiveRoom),
                outOfOrder to schedule(cleaningType, roomId = outOfOrder),
                inactiveArea to schedule(cleaningType, areaId = inactiveArea),
                notDue to schedule(cleaningType, roomId = notDue, lastDoneOn = date.minusDays(1)),
            )
        try {
            deactivateSchedule(schedules.getValue(inactiveSchedule))
            with(iam) {
                call(POST, "/api/v1/rooms/$inactiveRoom/deactivate", asAdmin()).ok()
                call(POST, "/api/v1/areas/$inactiveArea/deactivate", asAdmin()).ok()
            }
            openOutOfOrder(outOfOrder, OutOfOrderKind.OUT_OF_ORDER, date)

            generation.generateForDate(date)
            generation.generateForDate(date)

            val roomTask = tasksOf(room).single()
            roomTask.date shouldBe date
            roomTask.source shouldBe "SCHEDULED"
            roomTask.cleaningTypeId shouldBe cleaningType
            roomTask.scheduledCleaningId shouldBe schedules.getValue(room)
            roomTask.credits shouldBe 7
            generatedBySystem(roomTask.id) shouldBe true
            val areaTask = tasksOfArea(area).single()
            areaTask.source shouldBe "SCHEDULED"
            areaTask.scheduledCleaningId shouldBe schedules.getValue(area)
            areaTask.credits shouldBe 0
            listOf(
                inactiveSchedule,
                inactiveRoom,
                outOfOrder,
                notDue,
            ).forEach { withClue(it) { tasksOf(it).shouldBeEmpty() } }
            tasksOfArea(inactiveArea).shouldBeEmpty()

            // Aberta em qualquer status fora DONE e CANCELLED: a tarefa ASSIGNED barra outra no dia seguinte; a DONE,
            // não (a faxina continua vencida até a E04 registrar a feita).
            setStatus(roomTask.id, "ASSIGNED")
            setStatus(areaTask.id, "DONE")
            generation.generateForDate(date.plusDays(1))

            tasksOf(room).map { it.id } shouldBe listOf(roomTask.id)
            tasksOfArea(area).map { it.date to it.source } shouldBe
                listOf(date to "SCHEDULED", date.plusDays(1) to "SCHEDULED")
        } finally {
            schedules.values.forEach(::deactivateSchedule)
        }
    }

    @Test
    fun `a due schedule colliding with an ad hoc task of its type on the same room and date gets no task`() {
        val date = today
        val cleaningType = housekeeping.createCleaningType()
        val room = housekeeping.createRoom()
        val adHoc =
            UUID.fromString(
                housekeeping
                    .requestTask(housekeeping.taskBody(cleaningType, roomId = room, date = "$date"))["id"]
                    .asString(),
            )
        val schedule = schedule(cleaningType, roomId = room)
        try {
            // D11-E02: a colisão não lança nem grava a SCHEDULED; a avulsa fica como estava.
            generation.generateForDate(date)
            generation.generateForDate(date)

            tasksOf(room).single().let {
                it.id shouldBe adHoc
                it.source shouldBe "AD_HOC"
                it.scheduledCleaningId shouldBe null
                it.status shouldBe "PENDING"
            }
            housekeeping.auditActions("housekeeping.task", adHoc) shouldBe listOf("housekeeping.task.request")
        } finally {
            deactivateSchedule(schedule)
        }
    }

    @Test
    fun `a due schedule gets no task on a room out of service nor with its own cleaning type deactivated`() {
        val date = today
        val roomType = housekeeping.rooms.createRoomType()
        val cleaningType = housekeeping.createCleaningType()
        val deactivatedType = housekeeping.createCleaningType()
        val control = housekeeping.rooms.createRoom(roomType)
        val outOfService = housekeeping.rooms.createRoom(roomType)
        val withDeactivatedType = housekeeping.rooms.createRoom(roomType)
        val schedules =
            listOf(
                schedule(cleaningType, roomId = control),
                schedule(cleaningType, roomId = outOfService),
                schedule(deactivatedType, roomId = withDeactivatedType),
            )
        try {
            openOutOfOrder(outOfService, OutOfOrderKind.OUT_OF_SERVICE, date)
            // A API recusa desativar um tipo com faxina ativa (CLEANING_TYPE_HAS_ACTIVE_SCHEDULES): o tipo, criado por
            // este teste (nunca um da carga), é desativado direto no banco, o estado que a geração precisa tolerar.
            jdbc
                .sql("UPDATE housekeeping.cleaning_type SET active = FALSE WHERE id = :id")
                .param("id", deactivatedType)
                .update() shouldBe 1

            generation.generateForDate(date)

            tasksOf(control).map { it.source to it.cleaningTypeId } shouldBe listOf("SCHEDULED" to cleaningType)
            withClue("OOS") { tasksOf(outOfService).shouldBeEmpty() }
            withClue("tipo desativado") { tasksOf(withDeactivatedType).shouldBeEmpty() }
        } finally {
            schedules.forEach(::deactivateSchedule)
        }
    }

    // --- Ordens do check-out e da virada (Review Focus 1) ---------------------------------------------------------

    @Test
    fun `order B, the date advance before the check-out listener, ends with one CHECKOUT the day before`() {
        val date = today
        val room = roomLeftByCheckOut(date)

        generation.generateForDate(date)
        tasksOf(room).shouldBeEmpty()
        generation.generateCheckout(RoomId(room), date.minusDays(1), TaskSource.CHECKOUT) shouldBe true

        tasksOf(room).map { it.date to it.source } shouldBe listOf(date.minusDays(1) to "CHECKOUT")
    }

    @Test
    fun `order A, the check-out listener before the date advance, gives the same result`() {
        val date = today
        val room = roomLeftByCheckOut(date)

        generation.generateCheckout(RoomId(room), date.minusDays(1), TaskSource.CHECKOUT) shouldBe true
        generation.generateForDate(date)

        tasksOf(room).map { it.date to it.source } shouldBe listOf(date.minusDays(1) to "CHECKOUT")
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private data class Task(
        val id: UUID,
        val date: LocalDate,
        val cleaningTypeId: UUID,
        val source: String,
        val status: String,
        val arrivalExpected: Boolean,
        val scheduledCleaningId: UUID?,
        val credits: Int,
    )

    /**
     * Uma UH sem hospedada, limpa e com uma chegada alocada na [date], deixada Suja pelo check-out confirmado
     * (`markVacant` com a origem `CHECK_OUT`, o que o `frontdesk` faz na transação do check-out), sem tarefa: o estado
     * "check-out confirmado, listener ainda não rodou".
     */
    private fun roomLeftByCheckOut(date: LocalDate): UUID {
        val (type, rooms) = reservations.inventory.roomTypeWithRooms(1, date)
        val room = rooms.single()
        clean(room)
        assign(stays.reservation(type, stays.planFor(type), arrival = date).first, room)
        roomsApi.markVacant(RoomId(room), RoomStatusSource.CHECK_OUT)
        return room
    }

    private fun assign(
        reservation: ReservationId,
        room: UUID,
    ) {
        reservationsApi.assignRoom(reservation, RoomId(room))
    }

    private fun clean(room: UUID) {
        roomsApi.markClean(RoomId(room), false, RoomStatusSource.MANUAL)
    }

    private fun openOutOfOrder(
        room: UUID,
        kind: OutOfOrderKind,
        date: LocalDate,
    ) {
        roomsApi.openOutOfOrder(RoomId(room), kind, OutOfOrderRequest(date, date.plusDays(1), "Reparo", "Manutenção"))
    }

    private fun schedule(
        cleaningType: UUID,
        roomId: UUID? = null,
        areaId: UUID? = null,
        lastDoneOn: LocalDate? = null,
    ): UUID =
        housekeeping.createSchedule(
            housekeeping.scheduleBody(
                cleaningType,
                roomId,
                areaId,
                frequencyDays = 7,
                lastDoneOn = lastDoneOn?.toString(),
            ),
        )

    private fun deactivateSchedule(id: UUID) {
        with(iam) { call(POST, "$SCHEDULED_CLEANINGS/$id/deactivate", asAdmin()).ok() }
    }

    /** O status gravado direto no banco: as transições da tarefa são da E03 e da E04. */
    private fun setStatus(
        task: UUID,
        status: String,
    ) {
        jdbc
            .sql("UPDATE housekeeping.housekeeping_task SET status = :status WHERE id = :id")
            .param("status", status)
            .param("id", task)
            .update() shouldBe 1
    }

    private fun tasksOf(room: UUID): List<Task> = tasks("room_id", room)

    private fun tasksOfArea(area: UUID): List<Task> = tasks("area_id", area)

    private fun tasksOn(
        room: UUID,
        date: LocalDate,
    ): List<Task> = tasksOf(room).filter { it.date == date }

    private fun tasks(
        column: String,
        location: UUID,
    ): List<Task> =
        jdbc
            .sql("SELECT * FROM housekeeping.housekeeping_task WHERE $column = :location ORDER BY business_date, id")
            .param("location", location)
            .query { rs, _ ->
                Task(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("business_date", LocalDate::class.java),
                    rs.getObject("cleaning_type_id", UUID::class.java),
                    rs.getString("source"),
                    rs.getString("status"),
                    rs.getBoolean("arrival_expected"),
                    rs.getObject("scheduled_cleaning_id", UUID::class.java),
                    rs.getInt("credits"),
                )
            }.list()

    private fun systemType(key: String): UUID =
        jdbc
            .sql("SELECT id FROM housekeeping.cleaning_type WHERE system_key = :key")
            .param("key", key)
            .query(UUID::class.java)
            .single()

    /** O retrato (antes e depois) da entrada `housekeeping.task.generate` da tarefa, como texto. */
    private fun generateTrail(task: UUID): String =
        jdbc
            .sql(
                "SELECT coalesce(before_state::text, '') || coalesce(after_state::text, '') FROM audit.audit_entry " +
                    "WHERE action = 'housekeeping.task.generate' AND entity_id = :id",
            ).param("id", task.toString())
            .query(String::class.java)
            .single()

    /** A tarefa tem uma entrada `housekeeping.task.generate` na trilha, com o ator sistema. */
    private fun generatedBySystem(task: UUID): Boolean =
        jdbc
            .sql(
                "SELECT count(*) FROM audit.audit_entry WHERE action = 'housekeeping.task.generate' " +
                    "AND entity_type = 'housekeeping.task' AND entity_id = :id AND user_id IS NULL",
            ).param("id", task.toString())
            .query(Int::class.java)
            .single() == 1

    private companion object {
        const val SUFFIX_LENGTH = 12
    }
}
