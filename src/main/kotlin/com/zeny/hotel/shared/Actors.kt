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

/**
 * Porta do estado das sessões de dispositivo, parte do contexto de segurança do kernel: o canal de tempo
 * real (ARCHITECTURE §10) a consulta na inscrição, porque o access token de uma sessão revogada continua
 * válido até vencer (até 15 min). Implementada por `iam`.
 */
interface DeviceSessionStatus {
    /** Se a sessão foi revogada (ou não existe). */
    fun isRevoked(sessionId: DeviceSessionId): Boolean
}

/**
 * Evento do kernel (H8 do M2): sessões de dispositivo de [userId] foram revogadas (logout, revogação pelo
 * usuário ou pelo administrador, troca ou redefinição de senha, novo login no mesmo dispositivo, reuso de
 * refresh token, desativação do usuário). Publicado por `iam` na transação da revogação, uma vez por
 * revogação efetiva; o canal de tempo real o escuta em processo e, depois do commit, encerra as conexões
 * dessas sessões. Só ids, em `UUID` (nada de login nem motivo).
 */
data class DeviceSessionsRevoked(
    val userId: UUID,
    val sessionIds: List<UUID>,
)
