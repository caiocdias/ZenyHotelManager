package com.zeny.hotel.fnrh.application

import com.zeny.hotel.fnrh.domain.FnrhSettings

internal object FnrhAudit {
    const val SETTINGS = "fnrh.settings"
    const val UPDATE_SETTINGS = "fnrh.settings.update"
}

/**
 * O retrato da configuração para a trilha: sem senha nem usuário (só se existem) e com o CPF mascarado. A [version]
 * sobe a cada mudança gravada, inclusive a troca só da senha, que de outro modo não apareceria.
 */
internal data class FnrhSettingsAuditState(
    val environment: String,
    val usernameSet: Boolean,
    val passwordSet: Boolean,
    val defaultRequesterCpf: String?,
    val enabled: Boolean,
    val version: Long?,
)

internal fun FnrhSettings.auditState() =
    FnrhSettingsAuditState(
        environment = environment.name,
        usernameSet = !username.isNullOrEmpty(),
        passwordSet = !password.isNullOrEmpty(),
        defaultRequesterCpf = defaultCpf?.let { maskCpf(it.digits) },
        enabled = enabled,
        version = version,
    )
