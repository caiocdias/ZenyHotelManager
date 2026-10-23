package com.zeny.hotel.frontdesk.application

import com.zeny.hotel.frontdesk.TraceId
import com.zeny.hotel.frontdesk.TraceStatus
import com.zeny.hotel.frontdesk.domain.Trace
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.staff.DepartmentId
import java.time.LocalDate
import java.util.UUID

/** Os filtros da lista de recados (M5 E06); cada um que vem preenchido restringe, todos juntos. */
data class TraceFilter(
    val departmentId: DepartmentId? = null,
    val dueOn: LocalDate? = null,
    val status: TraceStatus? = null,
    val reservationId: ReservationId? = null,
    val roomId: RoomId? = null,
)

/** A persistência dos recados (M5 E06): `frontdesk.trace`, por SQL. */
interface TraceStore {
    fun add(trace: Trace)

    /**
     * Grava as mudanças do recado e incrementa a versão (por id e pela versão do [trace] lido); devolve o recado com a
     * versão nova. Falha (`IllegalStateException`) se a versão não é a gravada: a escrita é sempre sob [forUpdate].
     */
    fun update(trace: Trace): Trace

    fun find(id: TraceId): Trace?

    /** Lê o recado travando a linha (`SELECT … FOR UPDATE`) até o fim da transação. */
    fun forUpdate(id: TraceId): Trace?

    /** Uma página em ordem `(due_on, id)`, depois de [after] (o cursor com essas duas chaves), no máximo [limit]. */
    fun page(
        filter: TraceFilter,
        after: Pair<LocalDate, UUID>?,
        limit: Int,
    ): List<Trace>
}
