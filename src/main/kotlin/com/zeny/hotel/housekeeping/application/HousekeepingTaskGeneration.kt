package com.zeny.hotel.housekeeping.application

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.CleaningTypeKey
import com.zeny.hotel.housekeeping.TaskSource
import com.zeny.hotel.housekeeping.domain.HousekeepingTask
import com.zeny.hotel.housekeeping.domain.TaskLocation
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.rooms.RoomId
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
 * Geração automática das tarefas de governança (RF-GOV-02, M6 E02), chamada pelos listeners
 * ([HousekeepingTaskListeners]) com o ator sistema e auditada como `housekeeping.task.generate`: uma entrada por
 * tarefa criada, com o retrato só de ids, local, data, tipo e origem (RN-13); sem tarefa criada, `noChanges`.
 *
 * Idempotente pela unicidade (local, data, tipo) de D5-E02: a entrega repetida do evento não cria outra tarefa nem
 * lança. Estados esperados nunca lançam (D12-E02): o tipo da origem inativo (D1-E02) e a UH inexistente ou desativada
 * não geram tarefa, com log `INFO` só com ids e datas.
 */
@Service
@Suppress("LongParameterList") // as portas que a geração usa
class HousekeepingTaskGeneration(
    private val store: HousekeepingTaskStore,
    private val cleaningTypes: CleaningTypeStore,
    private val rooms: RoomsApi,
    private val reservations: ReservationsApi,
    private val hotel: HotelApi,
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
        if (task == null || !store.insertIfAbsent(task)) {
            audit.noChanges()
            return false
        }
        audit.created(HousekeepingAudit.TASK, task.id.value) { task.auditState() }
        return true
    }

    /**
     * As UHs com chegada prevista na [date] (D8-E02): as alocadas às reservas confirmadas que chegam na data. Até a
     * data operacional, são as de `ReservationsApi.pendingArrivals(date)`, com chegada até a data (inclusive a
     * atrasada); numa data futura, só as que chegam exatamente nela (as pendentes na véspera terão chegado antes).
     * Um retrato do momento: alocação, cancelamento ou no-show posteriores não mudam as tarefas já geradas.
     */
    @Transactional(readOnly = true)
    fun arrivalsByRoom(date: LocalDate): Set<RoomId> {
        val pending = reservations.pendingArrivals(date)
        val arriving =
            if (date.isAfter(hotel.currentBusinessDate())) {
                pending - reservations.pendingArrivals(date.minusDays(1)).toSet()
            } else {
                pending
            }
        return reservations.assignedRooms(arriving).values.toSet()
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
            arrivalExpected = roomId in arrivalsByRoom(date),
            credits = HousekeepingTask.creditsFor(type, room.roomTypeId),
            now = clock.instant(),
        )
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(HousekeepingTaskGeneration::class.java)
    }
}
