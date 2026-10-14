package com.zeny.hotel.shared

import java.util.UUID

/**
 * Id de um usuário (agregado `User` do módulo `iam`). Fica no kernel porque todo módulo
 * referencia usuários (quem criou, a quem está atribuído, escopo `OWN`) sem depender de `iam`.
 */
@JvmInline
value class UserId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): UserId = UserId(UuidV7.generate())
    }
}

/** Id de uma sessão de dispositivo (`DeviceSession` do módulo `iam`). */
@JvmInline
value class DeviceSessionId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): DeviceSessionId = DeviceSessionId(UuidV7.generate())
    }
}

/**
 * Quem está agindo na requisição corrente: o usuário autenticado e a sessão do dispositivo de
 * onde ele age (ARCHITECTURE §5 e §8.1). Nunca traz login, nome, senha nem token.
 */
data class Actor(
    val userId: UserId,
    val deviceSessionId: DeviceSessionId,
)

/**
 * Porta do ator corrente, parte do contexto de segurança do kernel: qualquer módulo descobre
 * quem está agindo sem depender de `iam`, que a implementa a partir do access token.
 */
interface CurrentActor {
    /** O ator da requisição corrente, ou `null` fora de uma requisição autenticada por token. */
    fun currentOrNull(): Actor?

    /** O ator da requisição corrente; sem ele, `401 UNAUTHENTICATED`. */
    fun current(): Actor =
        currentOrNull() ?: throw UnauthenticatedException(
            ErrorCodes.UNAUTHENTICATED,
            "Autentique-se para acessar este recurso.",
        )
}

/**
 * Autoridade do Spring Security que o access token carrega enquanto o usuário precisa trocar a
 * senha: com ela, só a troca de senha e o logout respondem; o resto da API devolve
 * `403 PASSWORD_CHANGE_REQUIRED`.
 */
const val PASSWORD_CHANGE_PENDING_AUTHORITY = "PASSWORD_CHANGE_PENDING"
