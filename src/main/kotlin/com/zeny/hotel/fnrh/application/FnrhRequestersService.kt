package com.zeny.hotel.fnrh.application

import com.zeny.hotel.fnrh.FnrhErrorCodes
import com.zeny.hotel.fnrh.FnrhPermissions
import com.zeny.hotel.fnrh.domain.FnrhRequester
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.shared.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** O CPF solicitante de um usuário como a API o mostra: sempre mascarado. */
data class FnrhRequesterView(
    val userId: UUID,
    val cpf: String,
)

/** Consulta do CPF solicitante de um usuário, para a E08 escolher o CPF de cada chamada. Sem checagem de permissão. */
fun interface FnrhRequesterDirectory {
    /** O CPF vigente de [userId], ou `null` (sem cadastro ou inativo). */
    fun cpfOf(userId: UUID): Cpf?
}

/** Os CPFs solicitantes gravados, na transação corrente. */
interface FnrhRequesterStore {
    /** A linha do usuário, ativa ou não. */
    fun find(userId: UUID): FnrhRequester?

    /** Só os ativos. */
    fun all(): List<FnrhRequester>

    fun save(requester: FnrhRequester)

    /** Grava na hora as mudanças pendentes. */
    fun flush()
}

/**
 * O CPF solicitante por usuário (RF-FNRH-01, D-E07-7): consultar, trocar e desativar exigem `fnrh.settings.manage`. O
 * id do usuário é opaco e não se confere se existe. A trilha só recebe o CPF mascarado.
 */
@Service
class FnrhRequestersService(
    private val store: FnrhRequesterStore,
    private val authorization: Authorization,
    private val audit: AuditContext,
) {
    @Transactional(readOnly = true)
    fun list(): List<FnrhRequesterView> {
        authorization.require(FnrhPermissions.SETTINGS_MANAGE)
        return store.all().map { it.view() }
    }

    @Audited(FnrhAudit.SET_REQUESTER)
    @Transactional
    fun set(
        userId: UUID,
        cpf: Cpf,
    ): FnrhRequesterView {
        authorization.require(FnrhPermissions.SETTINGS_MANAGE)
        val existing = store.find(userId)
        val requester =
            if (existing != null) {
                audit.track(FnrhAudit.REQUESTER, userId) { existing.auditState() }
                existing.replaceCpf(cpf)
                existing
            } else {
                FnrhRequester(userId, cpf).also {
                    store.save(it)
                    audit.created(FnrhAudit.REQUESTER, userId) { it.auditState() }
                }
            }
        store.flush()
        return requester.view()
    }

    /** Apaga o CPF do usuário e marca a linha como inativa, sem removê-la. */
    @Audited(FnrhAudit.DEACTIVATE_REQUESTER)
    @Transactional
    fun deactivate(userId: UUID) {
        authorization.require(FnrhPermissions.SETTINGS_MANAGE)
        val requester =
            store.find(userId)?.takeIf { it.active }
                ?: throw NotFoundException(
                    FnrhErrorCodes.FNRH_REQUESTER_NOT_FOUND,
                    "Não há CPF solicitante cadastrado para o usuário.",
                )
        audit.track(FnrhAudit.REQUESTER, userId) { requester.auditState() }
        requester.deactivate()
        store.flush()
    }

    private fun FnrhRequester.view() = FnrhRequesterView(userId, maskCpf(checkNotNull(cpf).digits))
}
