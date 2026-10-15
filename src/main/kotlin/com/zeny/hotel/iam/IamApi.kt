package com.zeny.hotel.iam

import com.zeny.hotel.shared.UserId

/**
 * API pública do `iam` para o único módulo que pode depender dele, `staff` (ARCHITECTURE §3.1):
 * a consulta de um usuário para validar o vínculo colaborador ↔ usuário (RF-ACS-01, RF-PES-01).
 * Não checa permissão: quem chama é um caso de uso que já se autorizou. Os demais módulos usam só
 * as portas do `shared` (`CurrentActor`, `Authorization`).
 */
interface IamApi {
    /** O usuário com o [id], ativo ou não, ou `null` se não existe. */
    fun findUser(id: UserId): UserAccountSummary?
}

/** Um usuário como os outros módulos o veem: sem login, senha, PIN nem roles. */
data class UserAccountSummary(
    val id: UserId,
    val name: String,
    val active: Boolean,
)
