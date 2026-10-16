package com.zeny.hotel.iam

import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.UserId

/**
 * API pública do `iam` para os módulos que podem depender dele (ARCHITECTURE §3.1): `staff`, que valida o
 * vínculo colaborador ↔ usuário (RF-ACS-01, RF-PES-01), e `notifications` (H9 do M2), que resolve os
 * destinatários por permissão e não manda push a sessão encerrada. Não checa permissão: quem chama é um caso
 * de uso que já se autorizou (ou o próprio sistema). Os demais módulos usam só as portas do `shared`
 * (`CurrentActor`, `Authorization`).
 */
interface IamApi {
    /** O usuário com o [id], ativo ou não, ou `null` se não existe. */
    fun findUser(id: UserId): UserAccountSummary?

    /**
     * Os usuários **ativos** que têm hoje a [permission] como permissão efetiva (RF-ACS-04: a união das roles
     * ativas, só com permissões vigentes e escopos ainda suportados), em qualquer escopo. A mudança de uma role,
     * concessão ou atribuição vale na chamada seguinte.
     */
    fun usersWithPermission(permission: PermissionCode): Set<UserId>

    /** As sessões de dispositivo do usuário ainda ativas agora: não revogadas e dentro da validade. */
    fun activeSessionsOf(userId: UserId): Set<DeviceSessionId>
}

/** Um usuário como os outros módulos o veem: sem login, senha, PIN nem roles. */
data class UserAccountSummary(
    val id: UserId,
    val name: String,
    val active: Boolean,
)
