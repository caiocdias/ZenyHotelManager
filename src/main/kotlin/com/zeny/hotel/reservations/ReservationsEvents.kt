// Convenção do projeto (CLAUDE.md): os eventos de um módulo ficam em <Módulo>Events.kt, na raiz.
@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package com.zeny.hotel.reservations

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A disponibilidade do tipo de UH [roomTypeId] mudou em alguma data de [from] a [to] (inclusive; SPEC §8.3, RF-TAR-08):
 * um contador do inventário mudou (vendidas, bloqueadas, total de UHs ativas, OOO ou o limite de overbooking) ou o
 * horizonte ganhou datas. Publicado por `reservations` pelo outbox na transação da mudança, uma vez por tipo e por
 * transação, só quando algo mudou. O channel manager (M8) o escuta para enviar a disponibilidade (ARI) e relê os
 * contadores do intervalo (a mensagem não traz os números: um evento reentregue ou fora de ordem não envia um valor
 * velho).
 */
data class AvailabilityChanged(
    val roomTypeId: UUID,
    val from: LocalDate,
    val to: LocalDate,
    val changedAt: Instant,
)
