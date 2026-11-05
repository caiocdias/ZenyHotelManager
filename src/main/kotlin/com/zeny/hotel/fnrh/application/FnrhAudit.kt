package com.zeny.hotel.fnrh.application

import com.zeny.hotel.fnrh.domain.FnrhRequester
import com.zeny.hotel.fnrh.domain.FnrhSettings

internal object FnrhAudit {
    const val SETTINGS = "fnrh.settings"
    const val UPDATE_SETTINGS = "fnrh.settings.update"
    const val REQUESTER = "fnrh.requester"
    const val SET_REQUESTER = "fnrh.requester.set"
    const val REMOVE_REQUESTER = "fnrh.requester.remove"
    const val DOMAIN_VALUES = "fnrh.domains"
    const val DOMAIN_VALUES_ID = "all"
    const val REFRESH_DOMAINS = "fnrh.domains.refresh"
}

/** O retrato do CPF solicitante de um usuário para a trilha: só o CPF mascarado. */
internal data class FnrhRequesterAuditState(
    val userId: String,
    val cpf: String,
)

internal fun FnrhRequester.auditState() = FnrhRequesterAuditState(userId.toString(), maskCpf(cpf.digits))

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
