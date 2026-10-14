package com.zeny.hotel.iam.domain

import java.time.Duration

/**
 * Bloqueio progressivo do login (RF-ACS-10, RNF-08). A partir de [maxFailures] falhas seguidas,
 * cada nova falha bloqueia o login por [baseLockout] dobrado a cada falha além do limite
 * (1 min, 2 min, 4 min…), até [maxLockout]. Um login bem-sucedido zera a contagem.
 */
data class LockoutPolicy(
    val maxFailures: Int,
    val baseLockout: Duration,
    val maxLockout: Duration,
) {
    init {
        require(maxFailures > 0) { "O número de falhas antes do bloqueio deve ser positivo" }
        require(!baseLockout.isNegative && !baseLockout.isZero) { "O bloqueio base deve ser positivo" }
        require(maxLockout >= baseLockout) { "O bloqueio máximo não pode ser menor que o base" }
    }

    /** Por quanto tempo bloquear depois da falha número [failures], ou `null` se ainda não bloqueia. */
    fun lockoutAfter(failures: Int): Duration? {
        if (failures < maxFailures) return null
        val doublings = (failures - maxFailures).coerceAtMost(MAX_DOUBLINGS)
        val lockout = baseLockout.multipliedBy(1L shl doublings)
        return if (lockout > maxLockout) maxLockout else lockout
    }

    private companion object {
        /** Evita estouro: 2^30 vezes o bloqueio base já passa de qualquer máximo razoável. */
        const val MAX_DOUBLINGS = 30
    }
}
