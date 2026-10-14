package com.zeny.hotel.iam.application

import com.zeny.hotel.iam.domain.DeviceSession
import com.zeny.hotel.iam.domain.Role
import com.zeny.hotel.iam.domain.User
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.RoleTemplate
import java.time.Instant

/**
 * Tipos de entidade e retratos do `iam` para a trilha de auditoria (RF-ACS-11, M1 E04). Os
 * retratos levam só o que interessa a quem audita: **nunca** o hash da senha ou do PIN nem o do
 * refresh token (só se há PIN e se a troca de senha está pendente). A trilha ainda mascara o que
 * escapar pelo nome ou pelo formato.
 */
internal object IamAudit {
    const val USER = "iam.user"
    const val SESSION = "iam.session"
    const val ROLE = "iam.role"
    const val PERMISSION = "iam.permission"
}

/** Um usuário na trilha. O `updatedAt` muda em toda escrita, inclusive na troca da senha. */
internal data class UserAuditState(
    val name: String,
    val login: String,
    val active: Boolean,
    val roleIds: List<String>,
    val hasPin: Boolean,
    val passwordChangeRequired: Boolean,
    val failedLoginCount: Int,
    val lockedUntil: Instant?,
    val updatedAt: Instant,
)

internal fun User.auditState() =
    UserAuditState(
        name = name,
        login = login,
        active = active,
        roleIds = roleIds.map { it.value.toString() }.sorted(),
        hasPin = pinHash != null,
        passwordChangeRequired = passwordChangeRequired,
        failedLoginCount = failedLoginCount,
        lockedUntil = lockedUntil,
        updatedAt = updatedAt,
    )

/** Uma sessão de dispositivo na trilha, sem o refresh token. */
internal data class SessionAuditState(
    val userId: String,
    val deviceId: String,
    val deviceName: String,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val revocationReason: String?,
)

internal fun DeviceSession.auditState() =
    SessionAuditState(
        userId = userId.value.toString(),
        deviceId = deviceId,
        deviceName = deviceName,
        createdAt = createdAt,
        lastSeenAt = lastSeenAt,
        expiresAt = expiresAt,
        revokedAt = revokedAt,
        revocationReason = revocationReason?.name,
    )

/** Uma concessão na trilha; o limite sai como texto (`"unlimited"` ou o teto). */
internal data class GrantAuditState(
    val permission: String,
    val scope: PermissionScope,
    val limits: Map<String, String>,
)

/** Uma role na trilha, com todas as concessões gravadas (inclusive as de permissões obsoletas). */
internal data class RoleAuditState(
    val name: String,
    val description: String,
    val active: Boolean,
    val template: RoleTemplate?,
    val systemRole: Boolean,
    val customized: Boolean,
    val grants: List<GrantAuditState>,
    val updatedAt: Instant,
)

internal fun Role.auditState() =
    RoleAuditState(
        name = name,
        description = description,
        active = active,
        template = template,
        systemRole = systemRole,
        customized = customized,
        grants =
            grantValues()
                .map { grant ->
                    GrantAuditState(
                        grant.permission.value,
                        grant.scope,
                        grant.limits.toSortedMap().mapValues { (_, limit) -> limit.auditText() },
                    )
                }.sortedBy { it.permission },
        updatedAt = updatedAt,
    )

/** Uma permissão do catálogo na trilha (só o que a tabela guarda). */
internal data class PermissionAuditState(
    val description: String,
    val scopes: List<PermissionScope>,
    val sensitive: Boolean,
    val limits: List<String>,
    val obsolete: Boolean,
)

internal fun PermissionDefinition.auditState(obsolete: Boolean = false) =
    PermissionAuditState(
        description = description,
        scopes = scopes.sortedBy { it.breadth },
        sensitive = sensitive,
        limits = limits.map { "${it.key} (${it.unit})" }.sorted(),
        obsolete = obsolete,
    )

private fun LimitValue.auditText(): String =
    when (this) {
        LimitValue.Unlimited -> "unlimited"
        is LimitValue.Max -> value.toPlainString()
    }
