package com.zeny.hotel.iam.domain

import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Id de uma aprovação de superior. */
@JvmInline
value class ApprovalId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): ApprovalId = ApprovalId(UuidV7.generate())
    }
}

/**
 * O que uma aprovação autoriza: a operação ([action]), a permissão que ela exige, o registro
 * ([resourceRef]) e, numa ação acima do limite, a chave e o valor pedidos. O token só vale para
 * uma requisição com o mesmo vínculo; o valor do limite é comparado pelo número (`15` = `15.0`).
 */
class ApprovalBinding(
    val action: String,
    val permission: PermissionCode,
    val resourceRef: String,
    val limitKey: String?,
    limitAmount: BigDecimal?,
) {
    /** Sem zeros à direita: a mesma representação para o mesmo número. */
    val limitAmount: BigDecimal? = limitAmount?.stripTrailingZeros()

    init {
        require((limitKey == null) == (limitAmount == null)) { "Limite da aprovação sem chave ou sem valor" }
    }

    override fun equals(other: Any?): Boolean =
        other is ApprovalBinding &&
            action == other.action &&
            permission == other.permission &&
            resourceRef == other.resourceRef &&
            limitKey == other.limitKey &&
            sameAmount(limitAmount, other.limitAmount)

    override fun hashCode(): Int = listOf(action, permission, resourceRef, limitKey).hashCode()

    override fun toString(): String = "ApprovalBinding(action=$action, permission=$permission)"

    private fun sameAmount(
        a: BigDecimal?,
        b: BigDecimal?,
    ): Boolean = if (a == null || b == null) a == b else a.compareTo(b) == 0
}

/** Por que um token de aprovação não serve para a requisição. */
enum class ApprovalRejection {
    /** Outro solicitante, outra sessão, outra ação, permissão, recurso ou valor de limite. */
    MISMATCH,

    /** Passou da validade ([Approval.TTL]). */
    EXPIRED,
}

/**
 * Aprovação de superior (SPEC §8.2.1, RF-ACS-07): o aprovador ([approverId]), depois de conferido
 * o PIN e a permissão dele, autoriza o solicitante ([requesterId], na sessão [requesterSessionId]) a
 * fazer **uma** operação ([binding]) pelo motivo dado. Vale por [TTL] a partir de [approvedAt] e
 * uma única vez (o uso fica registrado fora do agregado, também só por inserção). Imutável: nunca
 * é alterada nem apagada, e as entradas de auditoria a referenciam. Do token, só o hash
 * ([tokenHash]).
 */
@Suppress("LongParameterList") // os dados da aprovação (SPEC §8.2.1)
class Approval(
    val id: ApprovalId,
    val binding: ApprovalBinding,
    val reason: String,
    val requesterId: UserId,
    val requesterSessionId: DeviceSessionId,
    val approverId: UserId,
    val approvedAt: Instant,
    val expiresAt: Instant,
    val tokenHash: String,
) {
    init {
        require(reason.isNotBlank() && reason.length <= MAX_REASON_LENGTH) { "Motivo da aprovação inválido" }
        require(requesterId != approverId) { "O aprovador não pode ser o próprio solicitante" }
        require(expiresAt.isAfter(approvedAt) && !expiresAt.isAfter(approvedAt.plus(TTL))) {
            "Validade da aprovação fora do limite"
        }
    }

    /**
     * Por que o token não serve para [binding] pedida por [requesterId] na sessão [sessionId] em
     * [now], ou `null` se serve (o uso único é conferido à parte).
     */
    fun rejectionFor(
        binding: ApprovalBinding,
        requesterId: UserId,
        sessionId: DeviceSessionId,
        now: Instant,
    ): ApprovalRejection? =
        when {
            requesterId != this.requesterId || sessionId != requesterSessionId || binding != this.binding -> {
                ApprovalRejection.MISMATCH
            }

            !now.isBefore(expiresAt) -> {
                ApprovalRejection.EXPIRED
            }

            else -> {
                null
            }
        }

    override fun toString(): String = "Approval(id=$id, binding=$binding)"

    companion object {
        /** Validade do token (ARCHITECTURE §6.3). */
        val TTL: Duration = Duration.ofMinutes(2)
        const val MAX_REASON_LENGTH = 500

        /**
         * Aprovação nova, que vence em [TTL]. O instante é truncado em microssegundos (a precisão do
         * banco), para a validade gravada nunca passar de [TTL].
         */
        @Suppress("LongParameterList")
        fun grant(
            binding: ApprovalBinding,
            reason: String,
            requesterId: UserId,
            requesterSessionId: DeviceSessionId,
            approverId: UserId,
            tokenHash: String,
            now: Instant,
        ): Approval {
            val approvedAt = now.truncatedTo(ChronoUnit.MICROS)
            return Approval(
                id = ApprovalId.new(),
                binding = binding,
                reason = reason.trim(),
                requesterId = requesterId,
                requesterSessionId = requesterSessionId,
                approverId = approverId,
                approvedAt = approvedAt,
                expiresAt = approvedAt.plus(TTL),
                tokenHash = tokenHash,
            )
        }
    }
}
