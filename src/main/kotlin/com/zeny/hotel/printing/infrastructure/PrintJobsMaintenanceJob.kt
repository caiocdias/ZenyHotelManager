package com.zeny.hotel.printing.infrastructure

import com.zeny.hotel.printing.application.PrintJobsMaintenance
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * A manutenção da fila de impressão (M5 E10; D-E10-10), a cada `zeny.printing.maintenance-interval` e sob a trava do
 * ShedLock (`printing-jobs-maintenance`): reenvio ou `ACK_TIMEOUT`, validade dos `PENDING`, jobs de impressora inativa
 * e expurgo do conteúdo ([PrintJobsMaintenance]). Não devolve nada: o ShedLock só trava método sem retorno.
 */
@Component
class PrintJobsMaintenanceJob(
    private val maintenance: PrintJobsMaintenance,
) {
    @Scheduled(
        fixedDelayString = "\${zeny.printing.maintenance-interval}",
        initialDelayString = "\${zeny.printing.maintenance-initial-delay}",
    )
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT10M")
    fun maintain() {
        maintenance.run()
    }

    companion object {
        const val LOCK_NAME = "printing-jobs-maintenance"
    }
}
