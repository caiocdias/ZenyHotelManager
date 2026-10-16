package com.zeny.hotel.iam.application

import com.zeny.hotel.iam.IamApi
import com.zeny.hotel.iam.UserAccountSummary
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** A API pública do `iam` ([IamApi]): leituras sem checar permissão, na transação de quem chama. */
@Service
class IamApiService(
    private val users: Users,
    private val authorizationData: AuthorizationData,
    private val effectivePermissions: EffectivePermissionService,
    private val sessions: SessionDirectory,
    private val clock: Clock,
) : IamApi {
    @Transactional(readOnly = true)
    override fun findUser(id: UserId): UserAccountSummary? =
        users.findById(id)?.let { UserAccountSummary(it.id, it.name, it.active) }

    /**
     * Os candidatos (usuários ativos com alguma role ativa que concede a permissão) passam pelas permissões
     * efetivas, que descartam a permissão obsoleta e o escopo que ela não suporta mais.
     */
    @Transactional(readOnly = true)
    override fun usersWithPermission(permission: PermissionCode): Set<UserId> =
        authorizationData
            .activeUsersGranted(permission)
            .filter { effectivePermissions.of(it).of(permission) != null }
            .toSet()

    @Transactional(readOnly = true)
    override fun activeSessionsOf(userId: UserId): Set<DeviceSessionId> =
        sessions.activeSessionIdsOf(userId, clock.instant())
}
