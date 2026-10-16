package com.zeny.hotel.notifications

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.notifications.application.PushGateway
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * Sem `ZENY_FCM_CREDENTIALS` (o contexto padrão dos testes), o push fica desligado (H7): a notificação vai ao
 * painel e nada vai ao outbox, mesmo com um dispositivo registrado.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class NotificationsPushDisabledTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val notifications: NotificationsApi,
    @Autowired private val gateway: PushGateway,
    @Autowired private val jdbc: JdbcClient,
) {
    @Test
    fun `without FCM credentials the panel works and no push is queued`() {
        gateway.enabled shouldBe false
        val user = iam.createUser()
        val session = iam.loginOk(user, "phone")
        iam
            .call(
                POST,
                "/api/v1/push-devices",
                bearer(session["accessToken"].asString()),
                mapOf("platform" to "ANDROID", "token" to "fcm-${UuidV7.generate()}"),
            ).status shouldBe 201

        val ids =
            notifications.notify(
                NotificationsTypes.TASK_ASSIGNED,
                "Tarefa",
                "UH 2",
                NotificationRecipients.user(user.id),
            )

        ids shouldHaveSize 1
        jdbc
            .sql("SELECT count(*) FROM shared.event_publication WHERE event_type LIKE '%NotificationPushRequested'")
            .query(Long::class.java)
            .single() shouldBe 0
    }
}
