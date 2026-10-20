package com.zeny.hotel.billing.domain

import com.zeny.hotel.billing.BillingErrorCodes
import com.zeny.hotel.billing.CardPreAuthorizationId
import com.zeny.hotel.billing.CardPreAuthorizationStatus
import com.zeny.hotel.billing.CardPreAuthorizationStatus.ACTIVE
import com.zeny.hotel.billing.CardPreAuthorizationStatus.CAPTURED
import com.zeny.hotel.billing.CardPreAuthorizationStatus.EXPIRED
import com.zeny.hotel.billing.CardPreAuthorizationStatus.PARTIALLY_CAPTURED
import com.zeny.hotel.billing.CardPreAuthorizationStatus.RELEASED
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.PaymentMethod
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Caução (RF-CTA-08 no recorte manual, SPEC §8.2.7, M3 E09; decisões do usuário em 2026-10-19): a pré-autorização de
 * cartão de crédito feita numa maquininha não integrada, registrada na conta. **Não entra no saldo da conta**: só os
 * recebimentos das capturas entram.
 *
 * - A situação guardada ([status]) é `ACTIVE`, `PARTIALLY_CAPTURED`, `CAPTURED` ou `RELEASED`; `EXPIRED` é calculada
 *   na leitura ([statusAt]: agora ≥ [expiresAt] numa caução ainda capturável), sem job nem escrita.
 * - Captura ([capture]): parcial ou total, várias até o autorizado, enquanto não vencer nem for liberada; cada uma é um
 *   recebimento no cartão de crédito (pelo fluxo dos pagamentos), vinculado à caução.
 * - Liberação ([release]): registra que a sobra (o autorizado menos o capturado) foi liberada na maquininha e encerra
 *   a caução.
 *
 * Neutra quanto ao provedor: a pré-autorização integrada (M7) cria a mesma caução com outra origem.
 */
data class CardPreAuthorization(
    val id: CardPreAuthorizationId,
    val folioId: FolioId,
    val amount: Money,
    val capturedAmount: Money,
    val status: CardPreAuthorizationStatus,
    val expiresAt: Instant,
    val references: PaymentReferences,
    val businessDate: LocalDate,
    val registeredAt: Instant,
    val registeredBy: UserId,
    val updatedAt: Instant,
    val version: Long,
) {
    init {
        require(status != EXPIRED) { "A expiração da caução é calculada na leitura, nunca gravada" }
    }

    /** Capturada por inteiro ou liberada: não muda mais. */
    val settled: Boolean get() = status == CAPTURED || status == RELEASED

    /** O que ainda está autorizado (o autorizado menos o capturado), sem olhar a validade nem a liberação. */
    val uncaptured: Money get() = amount - capturedAmount

    /** A situação em [now]: a guardada, ou `EXPIRED` se ainda capturável e [now] ≥ [expiresAt]. */
    fun statusAt(now: Instant): CardPreAuthorizationStatus {
        val expired = !settled && !now.isBefore(expiresAt)
        return if (expired) EXPIRED else status
    }

    /** O que ainda pode ser capturado em [now]: zero se encerrada ou vencida. */
    fun capturableAt(now: Instant): Money = if (statusAt(now) in OPEN) uncaptured else Money.ZERO

    /**
     * Captura [value] em [now] (a data, o instante e o autor vêm do recebimento): encerrada `409
     * PRE_AUTHORIZATION_SETTLED`; vencida `422 PRE_AUTHORIZATION_EXPIRED`; acima do que ainda está autorizado `422
     * CAPTURE_EXCEEDS_AUTHORIZED`. Devolve a caução com o capturado somado (`CAPTURED` quando chega ao autorizado).
     */
    fun capture(
        value: Money,
        now: Instant,
    ): CardPreAuthorization {
        requireAmount(value)
        requireCapturable(now)
        if (value > uncaptured) {
            throw BusinessRuleException(
                BillingErrorCodes.CAPTURE_EXCEEDS_AUTHORIZED,
                "A captura passa do que ainda está autorizado na caução (R$ ${uncaptured.amount.toPlainString()}).",
            )
        }
        val captured = capturedAmount + value
        return copy(
            capturedAmount = captured,
            status = if (captured == amount) CAPTURED else PARTIALLY_CAPTURED,
            updatedAt = now,
            version = version + 1,
        )
    }

    /**
     * Libera a sobra (o que ainda está autorizado) em [stamp]: encerrada `409 PRE_AUTHORIZATION_SETTLED`. Vale também
     * depois da validade (decisão do usuário em 2026-10-20: a recepção registra a liberação de uma caução vencida, que
     * termina `RELEASED`). Devolve a caução `RELEASED` e o registro da liberação.
     */
    fun release(stamp: PostingStamp): Pair<CardPreAuthorization, PreAuthorizationRelease> {
        requireNotSettled()
        val releasedBy = checkNotNull(stamp.postedBy) { "Liberação de caução sem usuário" }
        val released =
            copy(status = RELEASED, updatedAt = stamp.postedAt, version = version + 1)
        return released to
            PreAuthorizationRelease(
                UuidV7.generate(),
                id,
                folioId,
                uncaptured,
                stamp.businessDate,
                stamp.postedAt,
                releasedBy,
            )
    }

    private fun requireCapturable(now: Instant) {
        requireNotSettled()
        if (statusAt(now) == EXPIRED) {
            throw BusinessRuleException(
                BillingErrorCodes.PRE_AUTHORIZATION_EXPIRED,
                "A caução venceu: não pode mais ser capturada.",
            )
        }
    }

    private fun requireNotSettled() {
        if (settled) {
            throw ConflictException(
                BillingErrorCodes.PRE_AUTHORIZATION_SETTLED,
                "A caução já foi ${if (status == CAPTURED) "capturada por inteiro" else "liberada"}.",
            )
        }
    }

    override fun toString(): String = "CardPreAuthorization(id=$id, folioId=$folioId, status=$status)"

    companion object {
        /** A validade vai até 90 dias depois do registro (uma pré-autorização de cartão dura dias, não meses). */
        val MAX_VALIDITY: Duration = Duration.ofDays(90)

        /** O valor autorizado e o de cada captura vão de 0,01 a 1.000.000,00. */
        val MAX_AMOUNT: Money = PaymentDraft.MAX_AMOUNT

        private val OPEN = setOf(ACTIVE, PARTIALLY_CAPTURED)

        /**
         * Registra na conta (aberta) a caução feita na maquininha, em [stamp] (a data operacional, o instante e quem
         * registra; não exige turno de caixa): o valor de 0,01 a 1.000.000,00, a validade depois de agora e até
         * [MAX_VALIDITY], a autorização obrigatória e o NSU, a bandeira e os 4 últimos dígitos opcionais (as regras
         * de [PaymentReferences.of] no cartão de crédito). Fora disso, `400 PRE_AUTHORIZATION_INVALID` com o campo.
         */
        fun register(
            folio: Folio,
            value: Money,
            expiresAt: Instant,
            references: PaymentReferences,
            stamp: PostingStamp,
        ): CardPreAuthorization {
            requireAmount(value)
            if (!expiresAt.isAfter(stamp.postedAt) || expiresAt.isAfter(stamp.postedAt.plus(MAX_VALIDITY))) {
                throw invalid("expiresAt", "A validade da caução vai de agora a 90 dias.")
            }
            if (references.authorizationCode == null) {
                throw invalid("authorizationCode", "Informe o código de autorização da caução.")
            }
            folio.requireOpen()
            return CardPreAuthorization(
                CardPreAuthorizationId.new(),
                folio.id,
                value,
                Money.ZERO,
                ACTIVE,
                expiresAt,
                references,
                stamp.businessDate,
                stamp.postedAt,
                checkNotNull(stamp.postedBy) { "Caução sem usuário" },
                stamp.postedAt,
                0,
            )
        }

        /** Os dados da maquininha da caução ou da captura (sempre no cartão de crédito). */
        fun references(
            nsu: String?,
            authorizationCode: String?,
            cardBrand: String? = null,
            cardLast4: String? = null,
        ): PaymentReferences =
            PaymentReferences.of(
                PaymentMethod.CREDIT_CARD,
                nsu,
                authorizationCode,
                cardBrand,
                cardLast4,
                BillingErrorCodes.PRE_AUTHORIZATION_INVALID,
            )

        fun requireAmount(value: Money) {
            if (!value.isPositive() ||
                value > MAX_AMOUNT
            ) {
                throw invalid("amount", "O valor vai de 0,01 a 1.000.000,00.")
            }
        }

        private fun invalid(
            field: String,
            message: String,
        ) = BillingRules.invalid(BillingErrorCodes.PRE_AUTHORIZATION_INVALID, field, message)
    }
}

/** A liberação da sobra de uma caução (fato imutável): o que restava autorizado, quando e por quem. */
data class PreAuthorizationRelease(
    val id: UUID,
    val preAuthorizationId: CardPreAuthorizationId,
    val folioId: FolioId,
    val amount: Money,
    val businessDate: LocalDate,
    val releasedAt: Instant,
    val releasedBy: UserId,
)
