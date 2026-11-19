// Convenção do projeto (CLAUDE.md): os eventos de um módulo ficam em <Módulo>Events.kt, na raiz.
@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package com.zeny.hotel.housekeeping

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A tarefa de governança [taskId] foi concluída (RF-GOV-04, SPEC §8.3, D7 da M6 E04): o local (a UH [roomId] **ou** a
 * área [areaId]), o tipo de limpeza, a data operacional da tarefa, o status final ([TaskStatus.DONE] ou
 * [TaskStatus.AWAITING_INSPECTION]), o usuário que concluiu e o instante da conclusão. Publicado uma vez pelo outbox,
 * na transação da conclusão; ainda sem consumidor.
 *
 * Sem dado de hóspede (RN-13): nem nome, documento, contato, id de hóspede ou de reserva, nem as observações da tarefa.
 */
data class HousekeepingTaskCompleted(
    val taskId: UUID,
    val roomId: UUID?,
    val areaId: UUID?,
    val cleaningTypeId: UUID,
    val businessDate: LocalDate,
    val finalStatus: TaskStatus,
    val completedBy: UUID,
    val completedAt: Instant,
)
