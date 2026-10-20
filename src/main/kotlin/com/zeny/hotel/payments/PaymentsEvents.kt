// Convenção do projeto (CLAUDE.md): os eventos de um módulo ficam em <Módulo>Events.kt, na raiz.
@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package com.zeny.hotel.payments

import com.zeny.hotel.shared.Money
import java.time.Instant
import java.util.UUID

/**
 * A intenção [intentId] foi confirmada pelo provedor (SPEC §8.3: Pagamentos → Contas; H1 do M3, M3 E08). Publicado
 * pelo `payments` pelo outbox, na transação em que a intenção ficou `SUCCEEDED`: no provedor `MANUAL`, a mesma de quem
 * a iniciou. Leva a finalidade e o destino opacos ([purpose], [targetType], [targetId]), o valor, o provedor e a
 * referência dele; nada de dado de cartão nem de pessoa. Quem é dono do destino o escuta com um
 * `@ApplicationModuleListener` **idempotente** pelo [intentId] (o `billing` ignora a intenção cujo pagamento já
 * registrou; o evento pode ser entregue mais de uma vez).
 */
data class PaymentSucceeded(
    val intentId: UUID,
    val purpose: String,
    val targetType: String,
    val targetId: UUID,
    val amount: Money,
    val provider: String,
    val providerReference: String?,
    val succeededAt: Instant,
)
