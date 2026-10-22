package com.zeny.hotel.testsupport

import com.zeny.hotel.rooms.RoomStatusChanged
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.ErrorCode
import org.springframework.context.event.EventListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Força a falha da transação que muda o status de uma UH "armada" (M5 E02): um listener **síncrono** de
 * `RoomStatusChanged` (roda dentro da transação de quem publicou, antes do commit) que lança um erro de domínio quando
 * a
 * UH está em [armed]. Serve para provar que o check-in orquestrado do `frontdesk` desfaz tudo (a caução, a transição da
 * reserva, a trilha e os eventos) quando o último passo falha. Inerte para as UHs não armadas.
 */
open class RoomStatusFailureProbe {
    @EventListener
    open fun on(event: RoomStatusChanged) {
        if (armed.contains(event.roomId)) {
            throw ConflictException(ErrorCode("TEST_FORCED_FAILURE"), "Falha forçada pelo teste depois da caução.")
        }
    }

    companion object {
        val armed: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    }
}
