package com.zeny.hotel.notifications

import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UserId
import java.util.UUID

/** Id de uma notificação (UUIDv7, D15). */
@JvmInline
value class NotificationId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()
}

/**
 * Para quem vai uma notificação: os [users] dados e os usuários ativos com cada uma das [permissions] como
 * permissão efetiva no momento (H9 do M2), sem repetir ninguém. Usuário inexistente ou inativo é ignorado.
 */
data class NotificationRecipients(
    val users: Set<UserId> = emptySet(),
    val permissions: Set<PermissionCode> = emptySet(),
) {
    companion object {
        /** Só o [user]. */
        fun user(user: UserId) = NotificationRecipients(users = setOf(user))

        /** Os usuários ativos com a [permission] efetiva. */
        fun withPermission(permission: PermissionCode) = NotificationRecipients(permissions = setOf(permission))
    }
}

/**
 * API pública do `notifications` (RF-NOT-01, ARCHITECTURE §3.1): qualquer módulo de negócio chama [notify] no
 * próprio caso de uso; o `notifications` nunca escuta eventos de módulos de negócio.
 */
interface NotificationsApi {
    /**
     * Cria uma notificação do [type] para cada destinatário, na transação de quem chama (sem transação, numa
     * própria): aparece no painel dele e vai pelo tópico pessoal `notifications` depois do commit; uma transação
     * desfeita não notifica ninguém. Respeita as preferências (RF-NOT-02): com o painel desligado para o tipo,
     * nada é criado para aquele usuário, salvo tipo obrigatório; com o push ligado, um dispositivo ativo e o
     * push configurado, o envio sai depois do commit, assíncrono pelo outbox, e uma falha dele nunca desfaz
     * esta transação. Auditada (`notifications.notification.create`). Não checa permissão.
     *
     * [title] (até 120 caracteres) e [body] (até 1000) vão ao painel e ao push, que passa pelo Google e aparece
     * na tela de bloqueio: **nunca ponha dado pessoal de hóspede** (nome, documento, contato; RN-13). Use o
     * número da UH, da tarefa ou da OS.
     *
     * Tipo fora do catálogo, permissão que nenhum módulo declara ou texto fora dos limites: erro de programação
     * (`IllegalArgumentException`).
     *
     * @return as notificações criadas, uma por destinatário que a recebeu no painel.
     */
    fun notify(
        type: NotificationTypeCode,
        title: String,
        body: String,
        recipients: NotificationRecipients,
    ): List<NotificationId>
}
