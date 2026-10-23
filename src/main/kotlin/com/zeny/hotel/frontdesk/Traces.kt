package com.zeny.hotel.frontdesk

import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UuidV7
import java.util.UUID

/** Id de um recado da recepção (M5 E06). */
@JvmInline
value class TraceId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): TraceId = TraceId(UuidV7.generate())
    }
}

/** Situação calculada de um recado (D5): aberto, sem resolução, ou resolvido. */
enum class TraceStatus {
    OPEN,
    RESOLVED,
}
