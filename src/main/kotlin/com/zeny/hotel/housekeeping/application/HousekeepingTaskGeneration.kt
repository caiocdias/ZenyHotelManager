package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.housekeeping.CleaningTypeKey
import com.zeny.hotel.housekeeping.TaskSource
import com.zeny.hotel.housekeeping.domain.HousekeepingTask
import com.zeny.hotel.housekeeping.domain.ScheduledCleaning
import com.zeny.hotel.housekeeping.domain.TaskLocation
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomSummary
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/**
 * Quantas tarefas a geração do dia criou, por origem: as das faxinas programadas, as permanências e as pré-chegadas.
 * Serve só ao log: a geração vale para o hotel inteiro.
 */
data class DailyGenerationResult(
    val scheduled: Int,
    val stayovers: Int,
    val preArrivals: Int,
)

/**
 * Geração automática das tarefas de governança (RF-GOV-02, M6 E02), chamada pelos listeners
 * ([HousekeepingTaskListeners]) com o ator sistema e auditada como `housekeeping.task.generate`: uma entrada por
 * tarefa criada, com o retrato só de ids, local, data, tipo e origem (RN-13); sem tarefa criada, `noChanges`.
 *
 * Idempotente pela unicidade (local, data, tipo) de D5-E02: a entrega repetida do evento não cria outra tarefa nem
 * lança. Estados esperados nunca lançam (D12-E02): o tipo da origem inativo (D1-E02), a UH ou a área inexistente,
 * desativada ou (para a pré-chegada e a programada) em OOO/OOS não geram tarefa, com log só com ids e datas. Um erro
 * inesperado desfaz a geração inteira, e o outbox reentrega o evento.
 */
@Service
@Suppress("LongParameterList") // as portas que a geração usa
class HousekeepingTaskGeneration(
    private val store: HousekeepingTaskStore,
    private val scheduledCleanings: ScheduledCleaningStore,
    private val cleaningTypes: CleaningTypeStore,
    private val rooms: RoomsApi,
    private val reservations: ReservationsApi,
    private val arrivals: ArrivalsByRoom,
    private val properties: HousekeepingProperties,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    /**
     * A tarefa de saída (`CHECKOUT`, D1-E02) na UH [roomId] que ficou vaga, na data [date] do evento (D4-E02), com a
     * origem [source] (`CHECKOUT` no check-out, `ROOM_MOVE` na UH de origem da troca), os créditos do tipo para o tipo
     * da UH (D2-E02) e a marca de chegada prevista na data (D8-E02). Devolve se criou a tarefa.
     */
    @Audited(HousekeepingAudit.GENERATE_TASK)
    @Transactional
    fun generateCheckout(
        roomId: RoomId,
        date: LocalDate,
        source: TaskSource,
    ): Boolean {
        require(source == TaskSource.CHECKOUT || source == TaskSource.ROOM_MOVE) { "Origem sem saída: $source" }
        val task = checkoutTask(roomId, date, source)
        if (task == null || !record(task)) {
            audit.noChanges()
            return false
        }
        return true
    }

    /**
     * As tarefas do dia [date] (a `current` do avanço da data, D4-E02), nesta ordem (D11-E02), todas com a marca de
     * chegada prevista na data (D8-E02):
     * 1. as das faxinas programadas ativas vencidas até a data, com o tipo e o id da faxina, num local ativo, fora de
     *    OOO/OOS e sem tarefa aberta da faxina (Review Focus 5); a que colide com uma tarefa já existente do mesmo
     *    (local, data, tipo) não gera, com log `INFO`;
     * 2. as permanências (`STAYOVER`): as UHs ativas com hospedada que chegou antes da data e não sai nela (D9-E02,
     *    Review Focus 3); a que chegou na data só a partir do dia seguinte;
     * 3. as pré-chegadas (`PRE_ARRIVAL`, D3-E02): as UHs ativas, fora de OOO/OOS, sem hospedada, não Sujas, com
     *    chegada alocada na data e sem tarefa (fora as `CANCELLED`) há menos de
     *    `zeny.housekeeping.pre-arrival-after-days` dias. A UH que o check-out sujou não recebe, qualquer que seja a
     *    ordem dos eventos (Review Focus 1).
     *
     * O OOO/OOS e o status de governança das UHs são os de agora (`RoomsApi.status`, na data operacional), não os de
     * [date]: o listener sempre gera para a data corrente; numa chamada direta com data futura, valem os de hoje.
     *
     * Rodar de novo não duplica nada (Review Focus 2). A guarda do avanço atrasado (D10-E02) é do listener.
     */
    @Audited(HousekeepingAudit.GENERATE_TASK)
    @Transactional
    fun generateForDate(date: LocalDate): DailyGenerationResult {
        val day = dayOf(date)
        val scheduled = scheduledCleanings.dueOn(date).mapNotNull { scheduledTask(it, day) }.count(::recordScheduled)
        val stayovers = roomTasks(TaskSource.STAYOVER, CleaningTypeKey.STAYOVER, day.staying, day).count(::record)
        val preArrivals =
            roomTasks(TaskSource.PRE_ARRIVAL, CleaningTypeKey.PRE_ARRIVAL, preArrivalRooms(day), day).count(::record)
        val result = DailyGenerationResult(scheduled, stayovers, preArrivals)
        if (scheduled + stayovers + preArrivals == 0) audit.noChanges()
        return result
    }

    /** A tarefa de saída a gravar; `null` (com log `INFO`) com o tipo `CHECKOUT` inativo ou a UH fora do cadastro. */
    private fun checkoutTask(
        roomId: RoomId,
        date: LocalDate,
        source: TaskSource,
    ): HousekeepingTask? {
        val type = cleaningTypes.byKey(CleaningTypeKey.CHECKOUT)?.takeIf { it.active }
        val room = rooms.room(roomId)?.takeIf { it.active }
        if (type == null || room == null) {
            log.info(
                "Tarefa de saída não gerada na UH {} em {} ({}): {}",
                roomId,
                date,
                source,
                if (type == null) "tipo CHECKOUT inativo ou ausente" else "UH inexistente ou desativada",
            )
            return null
        }
        return HousekeepingTask.create(
            type,
            TaskLocation.Room(roomId),
            date,
            source,
            arrivalExpected = roomId in arrivals.arrivalsByRoom(date),
            credits = HousekeepingTask.creditsFor(type, room.roomTypeId),
            now = clock.instant(),
        )
    }

    // --- Tarefas do dia ---------------------------------------------------------------------------------------------

    /**
     * O retrato do dia: as UHs ativas (na ordem de número), as com chegada prevista, as ocupadas pela `reservations`
     * (hospedadas na data, inclusive quem chegou nela) e as que ficam: a hospedada que chegou **antes** da data (em
     * `inHouse(date - 1)`) e não sai nela. Quem fez o check-in na data não recebe permanência nela, mesmo com a geração
     * rodando depois do check-in (reentrega atrasada do outbox, restart): a UH acabou de ser preparada para a chegada.
     */
    private class Day(
        val date: LocalDate,
        val rooms: Map<RoomId, RoomSummary>,
        val arrivals: Set<RoomId>,
        val occupied: Set<RoomId>,
        val staying: Set<RoomId>,
    )

    private fun dayOf(date: LocalDate): Day {
        // As saídas antes das hospedadas: um check-out entre as leituras some das duas, sem virar permanência.
        val departing = reservations.pendingDepartures(date).toSet()
        val hosted = reservations.assignedRooms(reservations.inHouse(date))
        val arrivedBefore = reservations.assignedRooms(reservations.inHouse(date.minusDays(1)))
        return Day(
            date,
            rooms.activeRooms().associateBy { it.id },
            arrivals.arrivalsByRoom(date),
            hosted.values.toSet(),
            arrivedBefore.filterKeys { it !in departing }.values.toSet(),
        )
    }

    /**
     * A tarefa da faxina programada [schedule] vencida; `null` (com log `INFO`) com o tipo dela inativo, o local
     * inexistente, desativado ou em OOO/OOS, ou uma tarefa aberta da faxina.
     */
    private fun scheduledTask(
        schedule: ScheduledCleaning,
        day: Day,
    ): HousekeepingTask? {
        val type = cleaningTypes.cleaningType(schedule.cleaningTypeId)?.takeIf { it.active }
        val roomId = schedule.roomId
        val room = roomId?.let { day.rooms[it] }?.takeIf { rooms.status(it.id)?.outOfOrder == null }
        val placeActive = if (roomId != null) room != null else schedule.areaId?.let { rooms.area(it)?.active } == true
        if (type == null || !placeActive || store.hasOpenTaskFor(schedule.id)) {
            log.info("Faxina programada {} sem tarefa em {}: tipo, local ou tarefa aberta", schedule.id, day.date)
            return null
        }
        val location = roomId?.let { TaskLocation.Room(it) } ?: TaskLocation.Area(checkNotNull(schedule.areaId))
        return HousekeepingTask.create(
            type,
            location,
            day.date,
            TaskSource.SCHEDULED,
            arrivalExpected = roomId in day.arrivals,
            credits = HousekeepingTask.creditsFor(type, room?.roomTypeId),
            now = clock.instant(),
            scheduledCleaningId = schedule.id,
        )
    }

    /** As UHs que recebem a pré-chegada (D3-E02): com chegada, sem hospedada, fora de OOO/OOS, não Sujas e paradas. */
    private fun preArrivalRooms(day: Day): Set<RoomId> {
        val since = day.date.minusDays(properties.preArrivalAfterDays.toLong())
        return (day.arrivals - day.occupied)
            .filter { room -> cleanAndAvailable(room) && store.lastTaskDate(room, day.date)?.isAfter(since) != true }
            .toSet()
    }

    /** A UH existe, não está Suja e está fora de OOO/OOS na data operacional. */
    private fun cleanAndAvailable(room: RoomId): Boolean =
        rooms.status(room)?.let { it.outOfOrder == null && it.housekeepingStatus != HousekeepingStatus.DIRTY } == true

    /**
     * As tarefas da origem [source], do tipo de chave [key] (D1-E02), nas UHs ativas de [roomIds], na ordem de número;
     * nenhuma (log `INFO`) com o tipo inativo ou ausente.
     */
    private fun roomTasks(
        source: TaskSource,
        key: CleaningTypeKey,
        roomIds: Set<RoomId>,
        day: Day,
    ): List<HousekeepingTask> {
        val type = cleaningTypes.byKey(key)?.takeIf { it.active }
        if (type == null) {
            if (roomIds.isNotEmpty()) log.info("Tarefas {} de {} não geradas: tipo inativo ou ausente", key, day.date)
            return emptyList()
        }
        return day.rooms.values
            .filter { it.id in roomIds }
            .map { room ->
                HousekeepingTask.create(
                    type,
                    TaskLocation.Room(room.id),
                    day.date,
                    source,
                    arrivalExpected = room.id in day.arrivals,
                    credits = HousekeepingTask.creditsFor(type, room.roomTypeId),
                    now = clock.instant(),
                )
            }
    }

    /** Grava a tarefa se ainda não há outra no mesmo (local, data, tipo) e a audita; devolve se gravou. */
    private fun record(task: HousekeepingTask): Boolean {
        if (!store.insertIfAbsent(task)) return false
        audit.created(HousekeepingAudit.TASK, task.id.value) { task.auditState() }
        return true
    }

    /** [record] da tarefa de uma faxina programada, com o log `INFO` da colisão (D11-E02). */
    private fun recordScheduled(task: HousekeepingTask): Boolean =
        record(task).also { recorded ->
            if (!recorded) {
                log.info(
                    "Faxina programada {} sem tarefa em {}: já há tarefa do mesmo tipo no local",
                    task.scheduledCleaningId,
                    task.businessDate,
                )
            }
        }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(HousekeepingTaskGeneration::class.java)
    }
}
