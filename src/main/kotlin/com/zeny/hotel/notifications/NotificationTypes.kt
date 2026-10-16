package com.zeny.hotel.notifications

/**
 * Código de um tipo de notificação: o módulo que o declara e o nome, em minúsculas e kebab-case
 * (`notifications.task-assigned`). As preferências e as notificações gravadas o referenciam: um código
 * publicado não muda (renomear é declarar outro).
 */
@JvmInline
value class NotificationTypeCode(
    val value: String,
) {
    init {
        require(value.length <= MAX_LENGTH && PATTERN.matches(value)) {
            "Código de tipo de notificação fora do padrão <módulo>.<nome>: '$value'"
        }
    }

    /** O módulo que declara o tipo (antes do ponto). */
    val module: String get() = value.substringBefore('.')

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 64
        private val PATTERN = Regex("[a-z][a-z0-9]*\\.[a-z][a-z0-9]*(-[a-z0-9]+)*")
    }
}

/**
 * Um tipo de notificação (RF-NOT-01), declarado em código num [ModuleNotificationTypes].
 *
 * - [description]: o que o usuário vê na tela de preferências (pt-BR).
 * - [mandatory]: o painel não pode ser desligado para este tipo (a notificação é criada mesmo com a preferência
 *   do painel desligada); o push continua desligável.
 */
data class NotificationType(
    val code: NotificationTypeCode,
    val description: String,
    val mandatory: Boolean = false,
) {
    init {
        require(description.isNotBlank()) { "Tipo de notificação $code sem descrição" }
    }
}

/**
 * Os tipos de notificação de um módulo. O módulo publica um bean deste tipo (numa `@Configuration` de
 * `application`, como o das permissões); o `notifications` junta todos no boot e recusa a partida com código
 * fora do módulo, repetido ou módulo declarado duas vezes.
 */
interface ModuleNotificationTypes {
    val module: String
    val types: List<NotificationType>
}

/**
 * Os sete tipos de RF-NOT-01, declarados pelo próprio `notifications` (decisão do usuário em 2026-10-16, H7 do
 * M2) para os módulos que ainda não existem os usarem com `notify`: tarefa atribuída e UH liberada e inspeção
 * reprovada (governança, M6), OS urgente (manutenção, M6), estoque mínimo (estoque, M9), falha de integração
 * (integrações, M3 em diante) e pendência do fechamento (fechamento do dia, M6). Obrigatórios no painel:
 * [INTEGRATION_FAILURE] e [END_OF_DAY_PENDING].
 */
object NotificationsTypes : ModuleNotificationTypes {
    override val module = "notifications"

    val TASK_ASSIGNED = NotificationTypeCode("notifications.task-assigned")
    val ROOM_RELEASED = NotificationTypeCode("notifications.room-released")
    val INSPECTION_REJECTED = NotificationTypeCode("notifications.inspection-rejected")
    val URGENT_WORK_ORDER = NotificationTypeCode("notifications.urgent-work-order")
    val LOW_STOCK = NotificationTypeCode("notifications.low-stock")
    val INTEGRATION_FAILURE = NotificationTypeCode("notifications.integration-failure")
    val END_OF_DAY_PENDING = NotificationTypeCode("notifications.end-of-day-pending")

    override val types =
        listOf(
            NotificationType(TASK_ASSIGNED, "Tarefa atribuída a você"),
            NotificationType(ROOM_RELEASED, "UH liberada"),
            NotificationType(INSPECTION_REJECTED, "Inspeção reprovada"),
            NotificationType(URGENT_WORK_ORDER, "Ordem de serviço urgente"),
            NotificationType(LOW_STOCK, "Estoque abaixo do mínimo"),
            NotificationType(INTEGRATION_FAILURE, "Falha de integração", mandatory = true),
            NotificationType(END_OF_DAY_PENDING, "Pendência do fechamento do dia", mandatory = true),
        )
}

/** A preferência de um usuário para um tipo (RF-NOT-02): no painel ([inApp]) e por push ([push]). */
data class NotificationPreference(
    val type: NotificationTypeCode,
    val inApp: Boolean,
    val push: Boolean,
)
