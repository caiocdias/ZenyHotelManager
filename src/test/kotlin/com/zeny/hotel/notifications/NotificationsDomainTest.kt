package com.zeny.hotel.notifications

import com.zeny.hotel.notifications.NotificationPreference
import com.zeny.hotel.notifications.application.NotificationTypeCatalog
import com.zeny.hotel.notifications.application.PushOutcome
import com.zeny.hotel.notifications.domain.Notification
import com.zeny.hotel.notifications.domain.PreferenceRules
import com.zeny.hotel.notifications.infrastructure.FcmErrors
import com.zeny.hotel.notifications.infrastructure.FcmProperties
import com.zeny.hotel.notifications.infrastructure.FcmServiceAccount
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64

/** Regras sem banco: catálogo de tipos, preferências, notificação, conta de serviço e erros do FCM. */
class NotificationsDomainTest {
    private val catalog = NotificationTypeCatalog(listOf(NotificationsTypes))

    @Test
    fun `the catalog refuses a type of another module, a repeated type and a module declared twice`() {
        catalog.find(NotificationsTypes.LOW_STOCK)?.description shouldBe "Estoque abaixo do mínimo"
        catalog.all.map { it.code } shouldBe NotificationsTypes.types.map { it.code }.sortedBy { it.value }

        shouldThrow<IllegalArgumentException> {
            NotificationTypeCatalog(listOf(types("rooms", NotificationType(NotificationTypeCode("checklists.x"), "X"))))
        }
        shouldThrow<IllegalArgumentException> {
            val type = NotificationType(NotificationTypeCode("rooms.x"), "X")
            NotificationTypeCatalog(listOf(types("rooms", type, type)))
        }
        shouldThrow<IllegalArgumentException> {
            NotificationTypeCatalog(
                listOf(NotificationsTypes, NotificationsTypes),
            )
        }
        shouldThrow<IllegalArgumentException> { NotificationTypeCode("Notifications.Bad") }
        shouldThrow<IllegalArgumentException> { NotificationType(NotificationTypeCode("rooms.x"), " ") }
    }

    @Test
    fun `preferences default to on, keep the panel of mandatory types and refuse incoherent changes`() {
        val lowStock = catalog.find(NotificationsTypes.LOW_STOCK)!!
        val failure = catalog.find(NotificationsTypes.INTEGRATION_FAILURE)!!

        PreferenceRules.effective(lowStock, null) shouldBe
            NotificationPreference(lowStock.code, inApp = true, push = true)
        PreferenceRules.effective(failure, NotificationPreference(failure.code, inApp = false, push = false)) shouldBe
            NotificationPreference(failure.code, inApp = true, push = false)

        PreferenceRules.validate(
            listOf(NotificationPreference(lowStock.code, inApp = true, push = false)),
            catalog::find,
        )
        shouldThrow<InvalidInputException> {
            PreferenceRules.validate(
                listOf(NotificationPreference(lowStock.code, inApp = false, push = true)),
                catalog::find,
            )
        }.code shouldBe NotificationsErrorCodes.NOTIFICATION_PREFERENCE_INVALID
        shouldThrow<InvalidInputException> {
            PreferenceRules.validate(
                listOf(NotificationPreference(NotificationTypeCode("notifications.nope"), inApp = true, push = true)),
                catalog::find,
            )
        }.code shouldBe NotificationsErrorCodes.UNKNOWN_NOTIFICATION_TYPE
        shouldThrow<BusinessRuleException> {
            PreferenceRules.validate(
                listOf(NotificationPreference(failure.code, inApp = false, push = false)),
                catalog::find,
            )
        }.code shouldBe NotificationsErrorCodes.NOTIFICATION_TYPE_MANDATORY
    }

    @Test
    fun `a notification is read once and validates its texts`() {
        val now = Instant.parse("2026-10-16T12:00:00Z")
        val notification = Notification.create(NotificationsTypes.LOW_STOCK, "Estoque", "Toalhas", UserId.new(), now)

        notification.markRead(now.plusSeconds(5)) shouldBe true
        notification.markRead(now.plusSeconds(9)) shouldBe false
        notification.readAt shouldBe now.plusSeconds(5)
        notification.version shouldBe 1

        shouldThrow<IllegalArgumentException> {
            Notification.create(
                NotificationsTypes.LOW_STOCK,
                "",
                "x",
                UserId.new(),
                now,
            )
        }
        shouldThrow<IllegalArgumentException> {
            Notification.create(NotificationsTypes.LOW_STOCK, "Título", " ", UserId.new(), now)
        }
    }

    @Test
    fun `the service account is read from base64 JSON and never shows the private key`() {
        FcmProperties().enabled shouldBe false
        FcmProperties(credentials = FcmTestServer.credentials).toString() shouldNotContain FcmTestServer.credentials

        val account = FcmServiceAccount.parse(FcmTestServer.credentials)
        account.projectId shouldBe FcmTestServer.PROJECT_ID
        account.clientEmail shouldBe FcmTestServer.CLIENT_EMAIL
        account.tokenUri.toString() shouldBe "${FcmTestServer.baseUrl}/token"
        account.toString() shouldNotContain "PRIVATE"

        val decoded = String(Base64.getDecoder().decode(FcmTestServer.credentials))
        listOf(
            "não é base64!",
            Base64.getEncoder().encodeToString("{".toByteArray()),
            encode(decoded.replace("service_account", "authorized_user")),
            encode(decoded.replace("\"project_id\"", "\"other\"")),
            encode(decoded.replace("BEGIN PRIVATE KEY", "BEGIN RSA PRIVATE KEY")),
        ).forEach { invalid ->
            val error = shouldThrow<IllegalArgumentException> { FcmServiceAccount.parse(invalid) }
            error.message.orEmpty() shouldNotContain "MII"
        }
    }

    @Test
    fun `FCM errors are classified into invalid token or temporary failure`() {
        fun body(code: String) =
            """{"error":{"status":"$code","details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"$code"}]}}"""

        FcmErrors.classify(404, body("UNREGISTERED")) shouldBe PushOutcome.InvalidToken
        FcmErrors.classify(403, body("SENDER_ID_MISMATCH")) shouldBe PushOutcome.InvalidToken
        FcmErrors.classify(400, body("INVALID_ARGUMENT")) shouldBe PushOutcome.InvalidToken
        FcmErrors.classify(503, body("UNAVAILABLE")).shouldBeInstanceOf<PushOutcome.TemporaryFailure>()
        FcmErrors.classify(429, body("QUOTA_EXCEEDED")).shouldBeInstanceOf<PushOutcome.TemporaryFailure>()
        FcmErrors.classify(500, body("INTERNAL")).shouldBeInstanceOf<PushOutcome.TemporaryFailure>()
        FcmErrors.classify(401, body("THIRD_PARTY_AUTH_ERROR")).shouldBeInstanceOf<PushOutcome.TemporaryFailure>()
        FcmErrors.classify(403, body("PERMISSION_DENIED")).shouldBeInstanceOf<PushOutcome.TemporaryFailure>()
        FcmErrors.classify(502, "<html>bad gateway</html>").shouldBeInstanceOf<PushOutcome.TemporaryFailure>()
    }

    private fun types(
        module: String,
        vararg types: NotificationType,
    ) = object : ModuleNotificationTypes {
        override val module = module
        override val types = types.toList()
    }

    private fun encode(json: String) = Base64.getEncoder().encodeToString(json.toByteArray())
}
