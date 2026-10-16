package com.zeny.hotel.iam.application

import com.zeny.hotel.iam.domain.DeviceSession
import com.zeny.hotel.iam.domain.SessionRevocationReason
import com.zeny.hotel.shared.DeviceSessionsRevoked
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * O único caminho para revogar uma sessão de dispositivo (H8 do M2): revoga e publica
 * [DeviceSessionsRevoked] na transação do caso de uso, para o canal de tempo real do kernel encerrar as
 * conexões dessas sessões depois do commit. Sessão já revogada não muda nem entra no evento. Chame dentro
 * de uma transação de escrita (rollback desfaz a revogação e o canal não encerra nada).
 */
@Component
class SessionRevocations(
    private val events: ApplicationEventPublisher,
) {
    fun revoke(
        session: DeviceSession,
        reason: SessionRevocationReason,
        now: Instant,
    ) = revokeAll(listOf(session), reason, now)

    fun revokeAll(
        sessions: List<DeviceSession>,
        reason: SessionRevocationReason,
        now: Instant,
    ) {
        val revoked = sessions.filterNot { it.revoked }
        revoked.forEach { it.revoke(reason, now) }
        revoked.groupBy { it.userId }.forEach { (userId, ofUser) ->
            events.publishEvent(DeviceSessionsRevoked(userId.value, ofUser.map { it.id.value }))
        }
    }
}
