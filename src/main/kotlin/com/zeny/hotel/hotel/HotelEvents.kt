// Convenção do projeto (CLAUDE.md): os eventos de um módulo ficam em <Módulo>Events.kt, na raiz.
@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package com.zeny.hotel.hotel

import java.time.Instant
import java.time.LocalDate

/**
 * A data operacional avançou um dia (RN-01): de [previous] para [current] (sempre o dia seguinte),
 * em [advancedAt]. Publicado por `hotel` pelo outbox (Event Publication Registry) na transação do
 * avanço; é o gancho das rotinas que dependem da virada do dia (governança, preventivas,
 * checklists — ARCHITECTURE §3.1 e §13). Os listeners precisam ser idempotentes.
 */
data class BusinessDateAdvanced(
    val previous: LocalDate,
    val current: LocalDate,
    val advancedAt: Instant,
)
