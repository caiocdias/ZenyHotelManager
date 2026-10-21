package com.zeny.hotel.reservations.infrastructure

import com.zeny.hotel.reservations.application.InventoryRecalculation
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * O horizonte do inventário (H3 do M4), a cada `zeny.reservations.inventory-horizon.interval` (padrão 1 dia) e sob
 * trava do ShedLock (`reservations-inventory-horizon`): para cada tipo de UH ativo, numa transação própria e auditada
 * com o ator sistema, cria as datas que faltam até 730 dias da data operacional e refaz o total e o OOO do horizonte
 * pela `RoomsApi` (o que também repara uma publicação perdida do cadastro de UH ou do OOO). Um tipo que falha vai para
 * o log e não interrompe os outros. Não devolve nada: o ShedLock só trava método sem retorno (ou com objeto).
 */
@Component
class InventoryHorizonJob(
    private val recalculation: InventoryRecalculation,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${zeny.reservations.inventory-horizon.interval}",
        initialDelayString = "\${zeny.reservations.inventory-horizon.initial-delay}",
    )
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT30M")
    fun extendHorizon() {
        var changed = 0
        recalculation.horizonRoomTypes().forEach { type ->
            runCatching { recalculation.extendHorizon(type) }
                .onSuccess { changed += it }
                .onFailure { log.warn("Horizonte do inventário do tipo de UH {} falhou", type, it) }
        }
        if (changed > 0) log.info("Horizonte do inventário: {} linhas criadas ou refeitas", changed)
    }

    companion object {
        const val LOCK_NAME = "reservations-inventory-horizon"
    }
}
