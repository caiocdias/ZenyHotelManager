package com.zeny.hotel.endofday

import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatus
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import org.springframework.beans.factory.config.BeanPostProcessor

/**
 * A sonda do `RoomsApi.markDirty` no contexto das etapas reais do fechamento ([EndOfDayRealStepsTestConfiguration]):
 * embrulha o bean `RoomsApi` e, armada, roda um gancho do teste (por exemplo, o check-out da UH) logo antes de a
 * marcação chegar ao `rooms`, o instante entre a leitura da etapa e o item. Inerte desarmada.
 */
class RoomsMarkProbe : BeanPostProcessor {
    @Volatile
    private var beforeMarkDirty: ((RoomsApi, RoomId) -> Unit)? = null

    /** Roda o [hook] antes de todo `markDirty`, com a API real e a UH marcada. */
    fun beforeMarkDirty(hook: (RoomsApi, RoomId) -> Unit) {
        beforeMarkDirty = hook
    }

    /** Desarma tudo (o `EndOfDayTestRuns.settle` o chama). */
    fun reset() {
        beforeMarkDirty = null
    }

    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any = if (bean is RoomsApi) ProbedRoomsApi(bean, this) else bean

    internal fun before(
        target: RoomsApi,
        id: RoomId,
    ) {
        beforeMarkDirty?.invoke(target, id)
    }
}

/** O `RoomsApi` com o `markDirty` passando antes pela [probe]; o resto delega direto. */
private class ProbedRoomsApi(
    private val target: RoomsApi,
    private val probe: RoomsMarkProbe,
) : RoomsApi by target {
    override fun markDirty(
        id: RoomId,
        source: RoomStatusSource,
    ): RoomStatus {
        probe.before(target, id)
        return target.markDirty(id, source)
    }
}
