// Convenção do projeto (CLAUDE.md): os eventos de um módulo ficam em <Módulo>Events.kt, na raiz.
@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package com.zeny.hotel.checklists

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Uma execução de checklist foi concluída (SPEC §8.2.11 e §8.3, ARCHITECTURE §6.2). Publicado uma vez por
 * execução, pelo outbox, na transação da conclusão, com as respostas que disparam ações ([actions], RF-CHK-05):
 * estoque trata `CONSUME_STOCK`, rouparia `LINEN_EXCHANGE`, manutenção e governança `OPEN_OCCURRENCE`; o
 * `checklists` não conhece nenhum deles. Os listeners precisam ser idempotentes (o outbox reentrega): a chave é
 * [executionId] com [ChecklistTriggeredAction.itemKey] e o tipo da ação. Ids em UUID; sem texto livre além da
 * observação da resposta que abre ocorrência (em claro, decisão do usuário em 2026-10-16).
 *
 * [duplicateOf]: a execução é a registrada à parte de uma rotina que outra pessoa já tinha assumido (RN-22). Ela
 * também publica o próprio evento com as ações (decisão do usuário em 2026-10-16): uma rotina feita por duas
 * pessoas offline gera dois eventos, e quem consome (M6, M9) decide por [duplicateOf] e [routineId] se aplica as
 * ações (por exemplo, não baixar estoque em dobro).
 */
data class ChecklistCompleted(
    val executionId: UUID,
    val templateId: UUID,
    val versionId: UUID,
    val versionNumber: Int,
    val context: ChecklistContext,
    val subjectType: String?,
    val subjectId: UUID?,
    val routineId: UUID?,
    val executorId: UUID,
    val startedAt: Instant,
    val completedAt: Instant,
    val actions: List<ChecklistTriggeredAction>,
    val duplicateOf: UUID? = null,
)

/**
 * Uma ação disparada por uma resposta (RF-CHK-05), com os parâmetros do tipo dela ([type]) e nenhum outro:
 * `CONSUME_STOCK` ([stockItemId] e a [quantity] respondida, maior que zero), `LINEN_EXCHANGE` ([linenTypeId],
 * [movement] e a [quantity]) e `OPEN_OCCURRENCE` ([occurrenceKind], [category] e a [note] da resposta "não ok").
 */
data class ChecklistTriggeredAction(
    val itemKey: String,
    val type: ItemActionType,
    val quantity: BigDecimal? = null,
    val stockItemId: UUID? = null,
    val linenTypeId: UUID? = null,
    val movement: LinenMovement? = null,
    val occurrenceKind: OccurrenceKind? = null,
    val category: String? = null,
    val note: String? = null,
)
