package com.zeny.hotel.fnrh.application

import com.zeny.hotel.fnrh.domain.FnrhRequester
import com.zeny.hotel.fnrh.domain.FnrhSettings
import com.zeny.hotel.fnrh.domain.FnrhSubmission

internal object FnrhAudit {
    const val SETTINGS = "fnrh.settings"
    const val UPDATE_SETTINGS = "fnrh.settings.update"
    const val REQUESTER = "fnrh.requester"
    const val SET_REQUESTER = "fnrh.requester.set"
    const val DEACTIVATE_REQUESTER = "fnrh.requester.deactivate"
    const val DOMAIN_VALUES = "fnrh.domains"
    const val DOMAIN_VALUES_ID = "all"
    const val REFRESH_DOMAINS = "fnrh.domains.refresh"
    const val SUBMISSION = "fnrh.submission"
    const val SEND_SUBMISSION = "fnrh.submission.send"
    const val RETRY_SUBMISSION = "fnrh.submission.retry"
    const val DISMISS_SUBMISSION = "fnrh.submission.dismiss"
}

/**
 * O retrato de uma submissão à FNRH para a trilha: a operação, a situação, as tentativas e a última falha. Nunca o
 * código da reserva nem dado de hóspede (os erros da validação local ficam fora).
 */
internal data class FnrhSubmissionAuditState(
    val id: String,
    val operation: String,
    val status: String,
    val attempts: Int,
    val lastFailure: String?,
)

internal fun FnrhSubmission.auditState() =
    FnrhSubmissionAuditState(id.toString(), operation.name, status.name, attempts, lastFailure?.name)

/**
 * O retrato do CPF solicitante de um usuário para a trilha: só o CPF mascarado (nenhum, se inativo). A [version] sobe
 * a cada mudança gravada, para a troca por um CPF de mesmo final (retratos iguais) não sumir da trilha.
 */
internal data class FnrhRequesterAuditState(
    val userId: String,
    val cpf: String?,
    val active: Boolean,
    val version: Long?,
)

internal fun FnrhRequester.auditState() =
    FnrhRequesterAuditState(
        userId.toString(),
        cpf?.let {
            maskCpf(it.digits)
        },
        active,
        version,
    )

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
