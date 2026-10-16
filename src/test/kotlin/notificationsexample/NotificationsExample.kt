package notificationsexample

import com.zeny.hotel.notifications.ModuleNotificationTypes
import com.zeny.hotel.notifications.NotificationType
import com.zeny.hotel.notifications.NotificationTypeCode
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN

/**
 * Um módulo de exemplo, só de teste, que declara uma permissão que só os usuários dos testes de notificações
 * recebem (e o Administrador, que recebe todas) e um tipo de notificação próprio, como um módulo de negócio
 * faria (catálogo por módulo).
 */
object NotificationsExamplePermissions : ModulePermissions {
    override val module = "notificationsexample"

    val ALERTS_RECEIVE = PermissionCode("notificationsexample.alerts.receive")

    override val permissions =
        listOf(PermissionDefinition(ALERTS_RECEIVE, "Receber alertas de exemplo", setOf(OWN, ALL)))
}

object NotificationsExampleTypes : ModuleNotificationTypes {
    override val module = "notificationsexample"

    val PING = NotificationTypeCode("notificationsexample.ping")

    override val types = listOf(NotificationType(PING, "Aviso de exemplo"))
}
