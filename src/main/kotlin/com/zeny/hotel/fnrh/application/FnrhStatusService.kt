package com.zeny.hotel.fnrh.application

import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.FnrhPermissions
import com.zeny.hotel.shared.Authorization
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Como está a integração com a FNRH, no [environment] atual: [configured] diz se há usuário, senha e CPF padrão, e os
 * instantes são os do último sucesso e da última falha **desse ambiente** (`null` sem registro).
 */
data class FnrhStatus(
    val configured: Boolean,
    val enabled: Boolean,
    val environment: FnrhEnvironment,
    val lastSuccessAt: Instant?,
    val lastFailureAt: Instant?,
    val lastFailureOutcome: FnrhFailure?,
)

/** A última falha registrada: quando e qual o resultado. */
data class FnrhLastFailure(
    val at: Instant,
    val outcome: String,
)

/** A leitura do registro de integração (`fnrh.integration_call`), por ambiente. Implementada em `infrastructure`. */
interface FnrhCallHistory {
    fun lastSuccessAt(environment: FnrhEnvironment): Instant?

    fun lastFailure(environment: FnrhEnvironment): FnrhLastFailure?
}

/** Lê o status da integração sem checar permissão: serve ao [FnrhStatusService] (que checa) e às métricas. */
@Component
class FnrhStatusReader(
    private val store: FnrhSettingsStore,
    private val history: FnrhCallHistory,
) {
    @Transactional(readOnly = true)
    fun read(): FnrhStatus {
        val settings = store.settings()
        val environment = settings.environment
        val failure = history.lastFailure(environment)
        return FnrhStatus(
            configured =
                !settings.username.isNullOrEmpty() &&
                    !settings.password.isNullOrEmpty() &&
                    settings.defaultCpf != null,
            enabled = settings.enabled,
            environment = environment,
            lastSuccessAt = history.lastSuccessAt(environment),
            lastFailureAt = failure?.at,
            lastFailureOutcome = failure?.let { f -> FnrhFailure.entries.firstOrNull { it.name == f.outcome } },
        )
    }
}

/** O status da integração com a FNRH pela API: exige `fnrh.submissions.read`. */
@Service
class FnrhStatusService(
    private val reader: FnrhStatusReader,
    private val authorization: Authorization,
) {
    @Transactional(readOnly = true)
    fun status(): FnrhStatus {
        authorization.require(FnrhPermissions.SUBMISSIONS_READ)
        return reader.read()
    }
}
