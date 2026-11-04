package com.zeny.hotel.fnrh.application

import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.FnrhPermissions
import com.zeny.hotel.fnrh.domain.FnrhSettings
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.shared.requireCurrentVersion
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * A configuração da FNRH como a API a mostra: sem a senha (só se existe) e com o CPF mascarado. A [version] é o `ETag`.
 */
data class FnrhSettingsView(
    val environment: FnrhEnvironment,
    val username: String?,
    val passwordSet: Boolean,
    val defaultRequesterCpf: String?,
    val enabled: Boolean,
    val version: Long,
)

/**
 * A troca da configuração como chega da API. [password] nula mantém a atual. O `toString` não mostra senha nem CPF.
 */
data class FnrhSettingsCommand(
    val environment: FnrhEnvironment,
    val username: String?,
    val password: String?,
    val defaultRequesterCpf: Cpf?,
    val enabled: Boolean,
) {
    override fun toString(): String = "FnrhSettingsCommand(environment=$environment, enabled=$enabled)"
}

/**
 * A configuração da integração com a FNRH pela API (RF-FNRH-01): consultar e editar exigem `fnrh.settings.manage`; a
 * edição pede a versão da última leitura (`If-Match`) e passa pela trilha, que não recebe senha nem CPF em claro.
 */
@Service
class FnrhSettingsService(
    private val store: FnrhSettingsStore,
    private val authorization: Authorization,
    private val audit: AuditContext,
) {
    @Transactional(readOnly = true)
    fun settings(): FnrhSettingsView {
        authorization.require(FnrhPermissions.SETTINGS_MANAGE)
        return store.settings().view()
    }

    @Audited(FnrhAudit.UPDATE_SETTINGS)
    @Transactional
    fun update(
        expectedVersion: Long,
        command: FnrhSettingsCommand,
    ): FnrhSettingsView {
        authorization.require(FnrhPermissions.SETTINGS_MANAGE)
        val settings = store.settings()
        requireCurrentVersion(expectedVersion, checkNotNull(settings.version))
        audit.track(FnrhAudit.SETTINGS, FnrhSettings.SINGLETON_ID) { settings.auditState() }
        settings.update(
            command.environment,
            command.username,
            command.password,
            command.defaultRequesterCpf,
            command.enabled,
        )
        store.flush()
        return settings.view()
    }

    private fun FnrhSettings.view() =
        FnrhSettingsView(
            environment = environment,
            username = username,
            passwordSet = !password.isNullOrEmpty(),
            defaultRequesterCpf = defaultCpf?.let { maskCpf(it.digits) },
            enabled = enabled,
            version = checkNotNull(version),
        )
}
