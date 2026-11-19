package com.zeny.hotel.housekeeping.infrastructure

import com.zeny.hotel.housekeeping.application.HousekeepingLostAndFound
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * O prazo de guarda dos achados (D3-E06, RF-GOV-08), a cada `zeny.housekeeping.lost-found-expiry.interval` (padrão 1
 * dia) e sob trava do ShedLock (`housekeeping-lost-found-expiry`): numa transação auditada com o ator sistema, avisa
 * uma vez cada item guardado e vencido a quem tem `housekeeping.lostfound.manage`
 * ([HousekeepingLostAndFound.notifyExpired]). Não descarta nem doa: o destino é sempre de uma pessoa. A falha escapa
 * para o tratador do agendador (só as classes no log) e a próxima execução tenta de novo, porque a marca do aviso é
 * desfeita com a transação. Não devolve nada: o ShedLock só trava método sem retorno (ou com objeto).
 */
@Component
class LostAndFoundExpiryJob(
    private val lostAndFound: HousekeepingLostAndFound,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${zeny.housekeeping.lost-found-expiry.interval}",
        initialDelayString = "\${zeny.housekeeping.lost-found-expiry.initial-delay}",
    )
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT30M")
    fun notifyExpiredItems() {
        val notified = lostAndFound.notifyExpired()
        if (notified > 0) log.info("Prazo de guarda: {} achados vencidos avisados", notified)
    }

    companion object {
        const val LOCK_NAME = "housekeeping-lost-found-expiry"
    }
}
