package com.zeny.hotel.reservations.infrastructure

import com.zeny.hotel.reservations.application.ProvisionalExpiry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * A expiração das pré-reservas (M4 E09; RN-23, ARCHITECTURE §13), a cada
 * `zeny.reservations.provisional-expiry.interval` (padrão 5 min) e sob a trava do ShedLock
 * (`reservations-provisional-expiry`): cancela as pré-reservas com a validade vencida, cada uma na transação dela e com
 * o ator sistema, sem multa, devolvendo o inventário e publicando `ReservationCancelled` com o motivo `EXPIRED`
 * ([ProvisionalExpiry]). Não devolve nada: o ShedLock só trava método sem retorno (ou com objeto).
 */
@Component
class ProvisionalExpiryJob(
    private val expiry: ProvisionalExpiry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${zeny.reservations.provisional-expiry.interval}",
        initialDelayString = "\${zeny.reservations.provisional-expiry.initial-delay}",
    )
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT10M")
    fun expireProvisionalReservations() {
        val run = expiry.expireDue()
        if (run.expired > 0 || run.failed > 0) {
            log.info("Expiração das pré-reservas: {} expiradas, {} com falha", run.expired, run.failed)
        }
    }

    companion object {
        const val LOCK_NAME = "reservations-provisional-expiry"
    }
}
