package com.zeny.hotel.billing

import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UuidV7
import java.time.Instant
import java.util.UUID

/** Id de uma caução (pré-autorização de cartão) na conta (M3 E09). */
@JvmInline
value class CardPreAuthorizationId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): CardPreAuthorizationId = CardPreAuthorizationId(UuidV7.generate())
    }
}

/**
 * Situação de uma caução (RF-CTA-08, M3 E09). `EXPIRED` nunca é gravada: é calculada na leitura (agora ≥ a validade
 * numa caução `ACTIVE` ou `PARTIALLY_CAPTURED`; decisão do usuário em 2026-10-19). No contrato, nas respostas, é
 * `x-extensible-enum` (a renovação do M7 pode acrescentar uma situação).
 */
enum class CardPreAuthorizationStatus {
    /** Registrada, nada capturado. */
    ACTIVE,

    /** Parte capturada; o restante continua capturável até vencer ou ser liberado. */
    PARTIALLY_CAPTURED,

    /** Capturada por inteiro (encerrada). */
    CAPTURED,

    /** A sobra foi liberada (encerrada). */
    RELEASED,

    /**
     * Venceu sem ser capturada por inteiro nem liberada (calculada na leitura): não captura mais, mas a sobra ainda
     * pode ser liberada (decisão do usuário em 2026-10-20) e não impede o encerramento da conta (M3 E10).
     */
    EXPIRED,
}

/**
 * Uma caução lida pela [BillingApi]: o valor autorizado, o capturado, o que ainda pode ser capturado (zero quando
 * encerrada ou vencida) e a validade, com a situação calculada no instante da leitura.
 */
data class CardPreAuthorizationSummary(
    val id: CardPreAuthorizationId,
    val folioId: FolioId,
    val status: CardPreAuthorizationStatus,
    val amount: Money,
    val capturedAmount: Money,
    val capturableAmount: Money,
    val expiresAt: Instant,
)
