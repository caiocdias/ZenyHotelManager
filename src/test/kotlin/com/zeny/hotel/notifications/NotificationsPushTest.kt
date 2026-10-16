package com.zeny.hotel.notifications

import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.SignedJWT
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.notifications.application.PushDispatcher
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.shared.infrastructure.events.EventPublicationResubmissionJob
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.net.URLDecoder
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.util.UUID

/**
 * Push pelo FCM HTTP v1 (M2 E09, H7, ARCHITECTURE §12) contra o [FcmTestServer] (WireMock): envio com o token
 * OAuth da conta de serviço, token inválido que desativa o dispositivo, erro temporário reprocessado pelo outbox
 * com o backoff da E12 (sem reenviar a quem já recebeu), sessão revogada sem push, preferência sem push e a
 * falha do FCM que nunca desfaz a transação de quem notificou.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class, NotificationsTestConfiguration::class)
@Suppress("LongParameterList") // os beans conferidos direto: notificações, outbox, relógio e banco
class NotificationsPushTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val notifications: NotificationsApi,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val resubmissionJob: EventPublicationResubmissionJob,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val dispatcher: PushDispatcher,
) {
    @BeforeEach
    @AfterEach
    fun cleanUp() {
        // O banco é compartilhado e os testes rodam um de cada vez: cada teste começa sem publicações pendentes.
        jdbc.sql("DELETE FROM shared.event_publication").update()
        FcmTestServer.reset()
    }

    @BeforeEach
    fun synchronize() {
        synchronizer.synchronize()
    }

    @Test
    fun `a notification is pushed to the active device with the service account token`() {
        // O token OAuth de um teste anterior fica em cache até perto de vencer: com o relógio 2 h à frente, este
        // teste pede um novo.
        clock.advance(Duration.ofHours(2))
        val user = iam.createUser()
        val device = registerDevice(user, platform = "IOS")

        val id = notify(user).single()

        awaitPublicationsCompleted()
        val send = FcmTestServer.sendsTo(device.token).single()
        send.getHeader("Authorization") shouldBe "Bearer ${FcmTestServer.ACCESS_TOKEN}"
        val message = jsonMapper.readTree(send.bodyAsString)["message"]
        message["token"].asString() shouldBe device.token
        message["notification"]["title"].asString() shouldBe "Tarefa na UH 12"
        message["notification"]["body"].asString() shouldBe "Limpeza de saída"
        message["data"]["type"].asString() shouldBe "notifications.task-assigned"
        message["data"]["notificationId"].asString() shouldBe id.toString()
        deliveriesOf(id) shouldContainExactly listOf(device.id to "DELIVERED")

        // O token OAuth é pedido com um JWT assinado pela chave da conta de serviço (grant jwt-bearer).
        val form =
            FcmTestServer.tokenRequests().first().bodyAsString.split('&').associate {
                val (key, value) = it.split('=', limit = 2)
                key to URLDecoder.decode(value, Charsets.UTF_8)
            }
        form["grant_type"] shouldBe "urn:ietf:params:oauth:grant-type:jwt-bearer"
        val assertion = SignedJWT.parse(form.getValue("assertion"))
        assertion.verify(RSASSAVerifier(FcmTestServer.keyPair.public as RSAPublicKey)) shouldBe true
        assertion.jwtClaimsSet.issuer shouldBe FcmTestServer.CLIENT_EMAIL
        assertion.jwtClaimsSet.audience shouldContainExactly listOf("${FcmTestServer.baseUrl}/token")
        assertion.jwtClaimsSet.getStringClaim("scope") shouldBe "https://www.googleapis.com/auth/firebase.messaging"

        // O token OAuth fica em cache até perto de vencer.
        notify(user)
        awaitPublicationsCompleted()
        FcmTestServer.sendsTo(device.token) shouldHaveSize 2
        FcmTestServer.tokenRequests() shouldHaveSize 1
    }

    @Test
    fun `an unregistered token deactivates the device, without retrying`() {
        val user = iam.createUser()
        val device = registerDevice(user)
        FcmTestServer.failFor(device.token, 404, "UNREGISTERED")

        val id = notify(user).single()

        awaitPublicationsCompleted()
        deviceState(device.id) shouldBe (false to "INVALID_TOKEN")
        deliveriesOf(id) shouldContainExactly listOf(device.id to "INVALID_TOKEN")

        // Desativado, não recebe mais nada; o painel continua.
        notify(user)
        awaitPublicationsCompleted()
        FcmTestServer.sendsTo(device.token) shouldHaveSize 1
        panelCount(user) shouldBe 2
    }

    @Test
    fun `a temporary error is resubmitted by the outbox with the backoff, without pushing twice`() {
        val user = iam.createUser()
        val healthy = registerDevice(user, deviceId = "tablet")
        val flaky = registerDevice(user, deviceId = "phone")
        FcmTestServer.failFor(flaky.token, 503, "UNAVAILABLE", scenario = "flaky-${UuidV7.generate()}", times = 1)

        val id = notify(user).single()

        val failed = awaitPublication { it.status == "FAILED" }
        FcmTestServer.sendsTo(healthy.token) shouldHaveSize 1
        FcmTestServer.sendsTo(flaky.token) shouldHaveSize 1
        deliveriesOf(id) shouldContainExactly listOf(healthy.id to "DELIVERED")
        deviceState(flaky.id) shouldBe (true to null)

        // Antes do 1º minuto, nada; no 1º minuto, o outbox reenvia (backoff da E12).
        clock.advance(Duration.between(clock.instant(), failed.publishedAt.plusSeconds(59)))
        resubmissionJob.resubmitFailedPublications()
        FcmTestServer.sendsTo(flaky.token) shouldHaveSize 1
        clock.advance(Duration.ofSeconds(1))
        resubmissionJob.resubmitFailedPublications()

        awaitPublication { it.status == "COMPLETED" }
        FcmTestServer.sendsTo(flaky.token) shouldHaveSize 2
        FcmTestServer.sendsTo(healthy.token) shouldHaveSize 1
        deliveriesOf(id).toSet() shouldBe setOf(healthy.id to "DELIVERED", flaky.id to "DELIVERED")
    }

    @Test
    fun `a revoked session gets no push and its device is deactivated`() {
        val user = iam.createUser()
        val revoked = registerDevice(user, deviceId = "phone")
        val active = registerDevice(user, deviceId = "tablet")
        iam.postWithToken("/api/v1/auth/logout", revoked.accessToken).status shouldBe 204

        notify(user)

        awaitPublicationsCompleted()
        FcmTestServer.sendsTo(revoked.token).shouldBeEmpty()
        FcmTestServer.sendsTo(active.token) shouldHaveSize 1
        deviceState(revoked.id) shouldBe (false to "SESSION_ENDED")
    }

    @Test
    fun `the push preference off keeps the panel and sends nothing`() {
        val user = iam.createUser()
        val device = registerDevice(user)
        val preference =
            iam.call(
                PUT,
                "/api/v1/notification-preferences",
                bearer(device.accessToken),
                mapOf(
                    "preferences" to
                        listOf(mapOf("type" to "notifications.task-assigned", "inApp" to true, "push" to false)),
                ),
            )
        preference.status shouldBe 200

        val muted = notify(user).single()
        // Nada vai ao outbox para o tipo com o push desligado.
        publications().shouldBeEmpty()
        // E o envio confere a preferência de novo na hora (desligada entre o notify e o envio): nada sai.
        dispatcher.dispatch(muted)
        FcmTestServer.sendsTo(device.token).shouldBeEmpty()

        // Um tipo com o push ligado chega.
        notifications.notify(
            NotificationsTypes.ROOM_RELEASED,
            "UH 3 liberada",
            "Pronta",
            NotificationRecipients.user(user.id),
        )

        awaitPublicationsCompleted()
        FcmTestServer
            .sendsTo(device.token)
            .map { jsonMapper.readTree(it.bodyAsString)["message"]["data"]["type"].asString() } shouldContainExactly
            listOf("notifications.room-released")
        panelCount(user) shouldBe 2
    }

    @Test
    fun `a failure of the FCM never rolls back the transaction that notified`() {
        val user = iam.createUser()
        val device = registerDevice(user)
        FcmTestServer.dropConnectionFor(device.token)

        val ids =
            transactions.execute {
                val first = notify(user)
                val second =
                    notifications.notify(
                        NotificationsTypes.LOW_STOCK,
                        "Estoque",
                        "Toalhas",
                        NotificationRecipients.user(user.id),
                    )
                first + second
            }

        // As duas notificações ficaram (a transação de quem notificou confirmou); o push falhou depois, à parte.
        ids shouldHaveSize 2
        panelCount(user) shouldBe 2
        await().atMost(Duration.ofSeconds(10)).until { publications().count { it.status == "FAILED" } == 2 }
        FcmTestServer.sendsTo(device.token) shouldHaveSize 2
        deviceState(device.id) shouldBe (true to null)

        // Um erro de autorização do FCM (credencial) também não desativa o dispositivo: é reprocessado.
        FcmTestServer.reset()
        FcmTestServer.failFor(device.token, 403, "PERMISSION_DENIED")
        notify(user)
        await().atMost(Duration.ofSeconds(10)).until { publications().count { it.status == "FAILED" } == 3 }
        deviceState(device.id) shouldBe (true to null)
    }

    @Test
    fun `nothing is published to the outbox for a user without an active device`() {
        val user = iam.createUser()

        notify(user) shouldHaveSize 1

        publications().shouldBeEmpty()
    }

    // ---- apoio ----------------------------------------------------------------------------------------------

    private data class Device(
        val id: UUID,
        val token: String,
        val accessToken: String,
    )

    private data class Publication(
        val status: String,
        val publishedAt: java.time.Instant,
    )

    private fun notify(user: TestUser): List<NotificationId> =
        notifications.notify(
            NotificationsTypes.TASK_ASSIGNED,
            "Tarefa na UH 12",
            "Limpeza de saída",
            NotificationRecipients.user(user.id),
        )

    private fun registerDevice(
        user: TestUser,
        deviceId: String = "phone",
        platform: String = "ANDROID",
    ): Device {
        val session = iam.loginOk(user, deviceId)
        val accessToken = session["accessToken"].asString()
        val token = "fcm-${UuidV7.generate()}"
        val response =
            iam.call(
                POST,
                "/api/v1/push-devices",
                bearer(accessToken),
                mapOf(
                    "platform" to platform,
                    "token" to token,
                ),
            )
        response.status shouldBe 201
        return Device(UUID.fromString(iam.json(response)["id"].asString()), token, accessToken)
    }

    private fun publications(): List<Publication> =
        jdbc
            .sql(
                "SELECT status, publication_date FROM shared.event_publication " +
                    "WHERE event_type LIKE '%NotificationPushRequested' ORDER BY publication_date",
            ).query { rs, _ ->
                Publication(
                    rs.getString("status"),
                    rs.getObject("publication_date", java.time.OffsetDateTime::class.java).toInstant(),
                )
            }.list()

    private fun awaitPublication(condition: (Publication) -> Boolean): Publication {
        var found: Publication? = null
        await().atMost(Duration.ofSeconds(10)).until {
            found = publications().singleOrNull()?.takeIf(condition)
            found != null
        }
        return found!!
    }

    private fun awaitPublicationsCompleted() =
        await().atMost(Duration.ofSeconds(10)).until {
            publications().let { it.isNotEmpty() && it.all { publication -> publication.status == "COMPLETED" } }
        }

    private fun deliveriesOf(id: NotificationId): List<Pair<UUID, String>> =
        jdbc
            .sql(
                "SELECT device_id, outcome FROM notifications.push_delivery " +
                    "WHERE notification_id = :id ORDER BY attempted_at",
            ).param("id", id.value)
            .query { rs, _ -> rs.getObject("device_id", UUID::class.java) to rs.getString("outcome") }
            .list()

    private fun deviceState(id: UUID): Pair<Boolean, String?> =
        jdbc
            .sql("SELECT active, deactivation_reason FROM notifications.push_device WHERE id = :id")
            .param("id", id)
            .query { rs, _ -> rs.getBoolean("active") to rs.getString("deactivation_reason") }
            .single()

    private fun panelCount(user: TestUser): Long =
        jdbc
            .sql("SELECT count(*) FROM notifications.notification WHERE recipient_id = :id")
            .param("id", user.id.value)
            .query(Long::class.java)
            .single()
}
