package com.zeny.hotel.shared

import java.util.UUID

/**
 * Um tópico do canal de tempo real (ARCHITECTURE §10, D10): `GET /api/v1/stream?topics=<name>,…`. O módulo
 * dono o declara como bean (`@Bean fun xTopic(): RealtimeTopic = XTopics.Y`, numa `@Configuration` de
 * `application`) e publica nele pelo [RealtimePublisher]. Na inscrição, o assinante precisa de
 * [permission]: com escopo `ALL` recebe toda mensagem do tópico; com `OWN`, só as que o têm entre os
 * [RealtimeMessage.owners]. O boot recusa nome repetido, fora do formato ou permissão que nenhum
 * `ModulePermissions` declara.
 *
 * @property name minúsculas, dígitos e hífen, começando por letra (até 40 caracteres): `rooms`, `tasks`.
 * @property permission a permissão de leitura que a inscrição exige.
 */
data class RealtimeTopic(
    val name: String,
    val permission: PermissionCode,
) {
    init {
        require(NAME.matches(name)) { "Nome de tópico inválido: '$name'" }
    }

    override fun toString(): String = name

    private companion object {
        val NAME = Regex("[a-z][a-z0-9-]{0,39}")
    }
}

/**
 * Uma mensagem do canal de tempo real, com o payload mínimo da ARCHITECTURE §10: o cliente recebe só
 * [type], [id] e [version] e aplica a mudança ou rebusca o recurso. Nada de dado pessoal (RN-13).
 *
 * @property type o que mudou, `<módulo>.<recurso>` em minúsculas e kebab-case (`rooms.room-status`).
 * @property id o id do registro que mudou.
 * @property version a versão do registro depois da mudança (o cliente descarta a mensagem mais velha que o
 *   que já tem).
 * @property owners os donos do registro, para o escopo `OWN` da permissão do tópico; nunca vão ao cliente.
 *   Vazio: só quem tem `ALL` recebe.
 */
data class RealtimeMessage(
    val type: String,
    val id: UUID,
    val version: Long,
    val owners: Set<UserId> = emptySet(),
) {
    init {
        require(TYPE.matches(type)) { "Tipo de mensagem inválido: '$type'" }
        require(version >= 0) { "Versão negativa" }
    }

    private companion object {
        val TYPE = Regex("[a-z][a-z0-9]*(?:-[a-z0-9]+)*(?:\\.[a-z][a-z0-9]*(?:-[a-z0-9]+)*)+")
    }
}

/**
 * Porta de publicação no canal de tempo real, implementada pelo kernel (`shared`, broadcaster em processo,
 * ARCHITECTURE §10). Chame dentro da transação de escrita do caso de uso que fez a mudança: a mensagem só
 * vai aos assinantes depois do commit (sincronização da transação, não o outbox: o buffer de reconexão é
 * em memória), e uma transação desfeita não envia nada. Fora de uma transação de escrita, falha com
 * `IllegalStateException`; tópico não registrado, com `IllegalArgumentException`.
 */
interface RealtimePublisher {
    fun publish(
        topic: RealtimeTopic,
        message: RealtimeMessage,
    )
}
