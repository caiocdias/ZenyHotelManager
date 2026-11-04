package com.zeny.hotel.fnrh.infrastructure

import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.application.FnrhConnection
import com.zeny.hotel.fnrh.application.FnrhConnectionSource
import com.zeny.hotel.fnrh.application.FnrhRequesterDirectory
import com.zeny.hotel.fnrh.application.FnrhRequesterStore
import com.zeny.hotel.fnrh.application.FnrhSettingsStore
import com.zeny.hotel.fnrh.domain.FnrhRequester
import com.zeny.hotel.fnrh.domain.FnrhSettings
import com.zeny.hotel.shared.Cpf
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface FnrhSettingsJpaRepository : JpaRepository<FnrhSettings, UUID>

/** A linha única da configuração por JPA, na transação corrente. */
@Component
class JpaFnrhSettingsStore(
    private val settings: FnrhSettingsJpaRepository,
) : FnrhSettingsStore {
    override fun settings(): FnrhSettings =
        settings.findById(FnrhSettings.SINGLETON_ID).orElseThrow {
            IllegalStateException("A linha semente da configuração da FNRH não existe.")
        }

    override fun flush() = settings.flush()
}

interface FnrhRequesterJpaRepository : JpaRepository<FnrhRequester, UUID>

/** Os CPFs solicitantes por JPA, na transação corrente; também responde à consulta da E08. */
@Component
class JpaFnrhRequesterStore(
    private val requesters: FnrhRequesterJpaRepository,
) : FnrhRequesterStore,
    FnrhRequesterDirectory {
    override fun find(userId: UUID): FnrhRequester? = requesters.findById(userId).orElse(null)

    override fun all(): List<FnrhRequester> = requesters.findAll(Sort.by("userId"))

    override fun save(requester: FnrhRequester) {
        requesters.save(requester)
    }

    override fun delete(requester: FnrhRequester) = requesters.delete(requester)

    override fun flush() = requesters.flush()

    @Transactional(readOnly = true)
    override fun cpfOf(userId: UUID): Cpf? = find(userId)?.cpf
}

/** A conexão vigente a partir da configuração gravada e da URL do ambiente escolhido. */
@Component
class SettingsFnrhConnectionSource(
    private val store: FnrhSettingsStore,
    private val properties: FnrhProperties,
) : FnrhConnectionSource {
    @Transactional(readOnly = true)
    override fun current(): FnrhConnection? {
        // A tabela e o domínio impedem ligar incompleto; a conferência de nulos só evita um NPE se alguém o burlar.
        return store.settings().takeIf { it.enabled }?.let { settings ->
            val username = settings.username
            val password = settings.password
            val cpf = settings.defaultCpf
            if (username != null && password != null && cpf != null) {
                FnrhConnection(settings.environment, baseUrl(settings.environment), username, password, cpf)
            } else {
                null
            }
        }
    }

    private fun baseUrl(environment: FnrhEnvironment) =
        when (environment) {
            FnrhEnvironment.HOMOLOGATION -> properties.homologationUrl
            FnrhEnvironment.PRODUCTION -> properties.productionUrl
        }
}
