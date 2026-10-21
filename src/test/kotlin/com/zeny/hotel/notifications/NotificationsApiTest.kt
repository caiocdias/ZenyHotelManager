package com.zeny.hotel.notifications

import com.zeny.hotel.billing.BillingNotificationTypes
import com.zeny.hotel.iam.IamApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.reservations.ReservationsNotificationTypes
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import notificationsexample.NotificationsExamplePermissions
import notificationsexample.NotificationsExampleTypes
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Notificações internas (M2 E09, RF-NOT-01 e 02): `notify` por usuário e por permissão (H9), o painel só com as
 * próprias, leitura, preferências (inclusive os tipos obrigatórios), registro de dispositivos de push vinculados
 * à sessão e auditoria. O push em si fica no [NotificationsPushTest] (mesmo contexto, com o FCM no WireMock).
 */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class, NotificationsTestConfiguration::class)
@Suppress("LargeClass") // os cenários do painel, das preferências e dos dispositivos, com o mesmo apoio
class NotificationsApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val iamApi: IamApi,
    @Autowired private val notifications: NotificationsApi,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionTemplate,
) {
    @BeforeEach
    fun setUp() {
        // O banco é compartilhado: outro contexto que suba depois marca as permissões só de teste obsoletas.
        synchronizer.synchronize()
        FcmTestServer.reset()
    }

    // ---- painel ---------------------------------------------------------------------------------------------

    @Test
    fun `a notification to a user shows only in that user's panel`() {
        val alice = iam.createUser()
        val bob = iam.createUser()

        val ids = notify(NotificationsTypes.TASK_ASSIGNED, "Tarefa na UH 12", "Limpeza de saída", alice.id)

        ids shouldHaveSize 1
        val page = panel(alice)
        page["items"].values().map { it["id"].asString() } shouldContainExactly listOf(ids.single().toString())
        val item = page["items"][0]
        item["type"].asString() shouldBe "notifications.task-assigned"
        item["title"].asString() shouldBe "Tarefa na UH 12"
        item["body"].asString() shouldBe "Limpeza de saída"
        item["readAt"].isNull shouldBe true
        item["version"].asLong() shouldBe 0
        unreadCount(alice) shouldBe 1

        panel(bob)["items"].values().toList().shouldBeEmpty()
        unreadCount(bob) shouldBe 0
        // A notificação de outro usuário não existe para quem a consulta ou tenta lê-la.
        with(iam) {
            call(
                GET,
                "/api/v1/notifications/${ids.single()}",
                iam.asTestUser(bob),
            ).problem(404, "NOTIFICATION_NOT_FOUND")
            call(POST, "/api/v1/notifications/${ids.single()}/read", iam.asTestUser(bob))
                .problem(404, "NOTIFICATION_NOT_FOUND")
        }
        unreadCount(alice) shouldBe 1
    }

    @Test
    fun `the recipient reads one notification, or all of them, and reading again changes nothing`() {
        val user = iam.createUser()
        val first = notify(NotificationsTypes.ROOM_RELEASED, "UH 12 liberada", "Pronta para check-in", user.id).single()
        val second =
            notify(
                NotificationsTypes.ROOM_RELEASED,
                "UH 14 liberada",
                "Pronta para check-in",
                user.id,
            ).single()
        val third =
            notify(
                NotificationsTypes.LOW_STOCK,
                "Estoque mínimo",
                "Sabonete abaixo do mínimo",
                user.id,
            ).single()

        val read = with(iam) { call(POST, "/api/v1/notifications/$first/read", iam.asTestUser(user)).ok() }
        read["readAt"].isNull shouldBe false
        read["version"].asLong() shouldBe 1
        val again = with(iam) { call(POST, "/api/v1/notifications/$first/read", iam.asTestUser(user)).ok() }
        again["readAt"].asString() shouldBe read["readAt"].asString()
        again["version"].asLong() shouldBe 1

        val detail = iam.call(GET, "/api/v1/notifications/$first", iam.asTestUser(user))
        detail.status shouldBe 200
        detail.getHeader("ETag") shouldBe "\"1\""

        unreadCount(user) shouldBe 2
        panel(user, "?unread=true")["items"].values().map { it["id"].asString() } shouldContainExactly
            listOf(third, second).map(NotificationId::toString)

        val all = with(iam) { call(POST, "/api/v1/notifications/read-all", iam.asTestUser(user)).ok() }
        all["read"].asInt() shouldBe 2
        unreadCount(user) shouldBe 0
        with(iam) { call(POST, "/api/v1/notifications/read-all", iam.asTestUser(user)).ok() }["read"].asInt() shouldBe 0
        // Da mais nova para a mais antiga.
        panel(user)["items"].values().map { it["id"].asString() } shouldContainExactly
            listOf(third, second, first).map(NotificationId::toString)
    }

    @Test
    fun `the panel pages by cursor, newest first`() {
        val user = iam.createUser()
        val ids = (1..5).map { notify(NotificationsTypes.TASK_ASSIGNED, "Tarefa $it", "UH $it", user.id).single() }

        val first = panel(user, "?limit=2")
        first["items"].values().map { it["id"].asString() } shouldContainExactly
            ids.reversed().take(2).map { it.toString() }
        val second = panel(user, "?limit=2&cursor=${first["nextCursor"].asString()}")
        second["items"].values().map { it["id"].asString() } shouldContainExactly
            ids
                .reversed()
                .drop(2)
                .take(2)
                .map { it.toString() }
        val last = panel(user, "?limit=2&cursor=${second["nextCursor"].asString()}")
        last["items"].values().map { it["id"].asString() } shouldContainExactly listOf(ids.first().toString())
        last["nextCursor"].isNull shouldBe true
    }

    @Test
    fun `the panel needs authentication but no permission`() {
        iam.mockMvcStatus(GET, "/api/v1/notifications") shouldBe 401
        val nobody = iam.createUser()
        with(iam) { call(GET, "/api/v1/notifications", iam.asTestUser(nobody)).ok() }
    }

    @Test
    fun `a rolled back transaction notifies nobody`() {
        val user = iam.createUser()

        transactions.executeWithoutResult { status ->
            notifications.notify(
                NotificationsTypes.TASK_ASSIGNED,
                "Tarefa",
                "UH 3",
                NotificationRecipients.user(user.id),
            )
            status.setRollbackOnly()
        }

        panel(user)["items"].values().toList().shouldBeEmpty()
    }

    @Test
    fun `notify refuses an unknown type, an undeclared permission and texts out of bounds`() {
        val user = iam.createUser()
        val recipients = NotificationRecipients.user(user.id)

        shouldThrow<IllegalArgumentException> {
            notifications.notify(NotificationTypeCode("notifications.unknown-type"), "Título", "Texto", recipients)
        }
        shouldThrow<IllegalArgumentException> {
            notifications.notify(
                NotificationsTypes.LOW_STOCK,
                "Título",
                "Texto",
                NotificationRecipients.withPermission(PermissionCode("nobody.declares.this")),
            )
        }
        shouldThrow<IllegalArgumentException> {
            notifications.notify(
                NotificationsTypes.LOW_STOCK,
                " ",
                "Texto",
                recipients,
            )
        }
        shouldThrow<IllegalArgumentException> {
            notifications.notify(NotificationsTypes.LOW_STOCK, "x".repeat(121), "Texto", recipients)
        }
        shouldThrow<IllegalArgumentException> {
            notifications.notify(NotificationsTypes.LOW_STOCK, "Título", "x".repeat(1001), recipients)
        }
        panel(user)["items"].values().toList().shouldBeEmpty()
    }

    @Test
    fun `notify is audited without the title or the body`() {
        val user = iam.createUser()
        val id = notify(NotificationsTypes.URGENT_WORK_ORDER, "OS urgente", "Vazamento na UH 7", user.id).single()

        val entry =
            jdbc
                .sql(
                    "SELECT action, after_state::text AS after FROM audit.audit_entry " +
                        "WHERE entity_type = 'notifications.notification' AND entity_id = :id",
                ).param("id", id.toString())
                .query { rs, _ -> rs.getString("action") to rs.getString("after") }
                .single()
        entry.first shouldBe "notifications.notification.create"
        entry.second.contains(user.id.toString()) shouldBe true
        entry.second.contains("Vazamento") shouldBe false
    }

    // ---- destinatários --------------------------------------------------------------------------------------

    @Test
    fun `notify by permission reaches exactly the active users with the effective permission`() {
        val alerts = TestGrant(NotificationsExamplePermissions.permissions.single(), ALL)
        val withAll = iam.createUserWith(alerts)
        val withOwn = iam.createUserWith(TestGrant(NotificationsExamplePermissions.permissions.single(), OWN))
        val without = iam.createUser()
        val deactivated = iam.createUserWith(alerts).also(iam::deactivate)
        val inactiveRole =
            iam.createUser().also { user ->
                iam.assignRole(user, iam.createRole(alerts).also(iam::deactivateRole))
            }
        val revoked =
            iam.createUser().also { user ->
                val role = iam.createRole(alerts)
                iam.assignRole(user, role)
                iam.revokeGrant(role, NotificationsExamplePermissions.ALERTS_RECEIVE)
            }

        val holders = iamApi.usersWithPermission(NotificationsExamplePermissions.ALERTS_RECEIVE)
        // Os administradores ativos recebem toda permissão vigente; ninguém mais, fora dos usuários deste teste,
        // tem a permissão só de teste.
        holders shouldBe setOf(withAll.id, withOwn.id) + activeAdministrators()
        listOf(without, deactivated, inactiveRole, revoked).forEach { holders.contains(it.id) shouldBe false }

        val ids =
            notifications.notify(
                NotificationsExampleTypes.PING,
                "Aviso",
                "Para quem tem a permissão",
                NotificationRecipients(
                    users = setOf(withAll.id, without.id, deactivated.id),
                    permissions = setOf(NotificationsExamplePermissions.ALERTS_RECEIVE),
                ),
            )

        // Por permissão, mais os usuários dados (o inativo é ignorado), sem repetir ninguém.
        recipientsOf(ids) shouldContainExactlyInAnyOrder (holders + without.id).toList()
        panel(withOwn)["items"].values().map { it["type"].asString() } shouldContainExactly
            listOf("notificationsexample.ping")
        panel(deactivated)["items"].values().toList().shouldBeEmpty()
        panel(revoked)["items"].values().toList().shouldBeEmpty()

        // Os usuários de `except` (M3 E07) ficam de fora, dados ou pela permissão.
        val excepted =
            notifications.notify(
                NotificationsExampleTypes.PING,
                "Aviso",
                "Menos quem causou",
                NotificationRecipients(
                    users = setOf(without.id),
                    permissions = setOf(NotificationsExamplePermissions.ALERTS_RECEIVE),
                    except = setOf(withAll.id, without.id),
                ),
            )
        recipientsOf(excepted) shouldContainExactlyInAnyOrder (holders - withAll.id).toList()
    }

    @Test
    fun `the iam API lists only the active sessions of a user`() {
        val user = iam.createUser()
        val phone = UUID.fromString(iam.loginOk(user, "phone")["sessionId"].asString())
        val tablet = iam.loginOk(user, "tablet")
        val laptop = UUID.fromString(iam.loginOk(user, "laptop")["sessionId"].asString())

        iam.postWithToken("/api/v1/auth/logout", tablet["accessToken"].asString()).status shouldBe 204
        jdbc
            .sql("UPDATE iam.device_session SET expires_at = now() - interval '1 minute' WHERE id = :id")
            .param("id", laptop)
            .update() shouldBe 1

        iamApi.activeSessionsOf(user.id).map { it.value } shouldContainExactly listOf(phone)
    }

    // ---- preferências ---------------------------------------------------------------------------------------

    @Test
    fun `preferences list every type, all on by default, and keep what the user sets`() {
        val user = iam.createUser()

        val defaults = preferences(user)
        defaults.map { it["type"].asString() } shouldContainExactly
            (
                NotificationsTypes.types + NotificationsExampleTypes.types + BillingNotificationTypes.types +
                    ReservationsNotificationTypes.types
            ).map { it.code.value }
                .sorted()
        defaults.forEach {
            it["inApp"].asBoolean() shouldBe true
            it["push"].asBoolean() shouldBe true
        }
        defaults
            .single {
                it["type"].asString() == "notifications.integration-failure"
            }["mandatory"]
            .asBoolean() shouldBe
            true
        defaults
            .single {
                it["type"].asString() == "notifications.end-of-day-pending"
            }["mandatory"]
            .asBoolean() shouldBe
            true
        defaults.filter { it["mandatory"].asBoolean() }.map { it["type"].asString() } shouldHaveSize 2

        val updated =
            with(iam) {
                call(
                    PUT,
                    "/api/v1/notification-preferences",
                    iam.asTestUser(user),
                    mapOf(
                        "preferences" to
                            listOf(
                                mapOf("type" to "notifications.low-stock", "inApp" to false, "push" to false),
                                mapOf("type" to "notifications.task-assigned", "inApp" to true, "push" to false),
                                mapOf("type" to "notifications.integration-failure", "inApp" to true, "push" to false),
                            ),
                    ),
                ).ok()
            }["preferences"].values().toList()
        updated.single { it["type"].asString() == "notifications.low-stock" }["inApp"].asBoolean() shouldBe false
        updated.single { it["type"].asString() == "notifications.task-assigned" }["push"].asBoolean() shouldBe false
        updated.single { it["type"].asString() == "notifications.integration-failure" }["push"].asBoolean() shouldBe
            false
        // O que não foi enviado fica como estava.
        updated.single { it["type"].asString() == "notifications.room-released" }["push"].asBoolean() shouldBe true
        preferences(user).map { it.toString() } shouldBe updated.map { it.toString() }
        // As preferências são de cada usuário.
        preferences(iam.createUser()).forEach { it["inApp"].asBoolean() shouldBe true }
    }

    @Test
    fun `the panel preference off creates nothing for that type, except for mandatory types`() {
        val user = iam.createUser()
        setPreference(user, "notifications.low-stock", inApp = false, push = false)
        setPreference(user, "notifications.task-assigned", inApp = true, push = false)
        // Um tipo obrigatório com o painel desligado direto no banco (a API recusa): a notificação sai mesmo assim.
        jdbc
            .sql(
                "INSERT INTO notifications.notification_preference (user_id, type, in_app, push, updated_at) " +
                    "VALUES (:user, 'notifications.end-of-day-pending', false, false, now())",
            ).param("user", user.id.value)
            .update() shouldBe 1

        notify(NotificationsTypes.LOW_STOCK, "Estoque mínimo", "Toalhas", user.id).shouldBeEmpty()
        notify(NotificationsTypes.TASK_ASSIGNED, "Tarefa", "UH 9", user.id) shouldHaveSize 1
        notify(NotificationsTypes.END_OF_DAY_PENDING, "Fechamento", "Caixa aberto", user.id) shouldHaveSize 1

        panel(user)["items"].values().map { it["type"].asString() } shouldContainExactly
            listOf("notifications.end-of-day-pending", "notifications.task-assigned")
    }

    @Test
    fun `preferences refuse unknown types, push without the panel and the panel off for a mandatory type`() {
        val user = iam.createUser()

        fun put(vararg items: Map<String, Any>) =
            iam.call(
                PUT,
                "/api/v1/notification-preferences",
                iam.asTestUser(user),
                mapOf("preferences" to items.toList()),
            )

        with(iam) {
            put(mapOf("type" to "notifications.nope", "inApp" to true, "push" to true))
                .problem(400, "UNKNOWN_NOTIFICATION_TYPE")["errors"][0]["field"]
                .asString() shouldBe "preferences[0].type"
            put(mapOf("type" to "notifications.low-stock", "inApp" to false, "push" to true))
                .problem(400, "NOTIFICATION_PREFERENCE_INVALID")
            put(
                mapOf("type" to "notifications.low-stock", "inApp" to true, "push" to true),
                mapOf("type" to "notifications.low-stock", "inApp" to false, "push" to false),
            ).problem(400, "NOTIFICATION_PREFERENCE_INVALID")
            put(mapOf("type" to "notifications.integration-failure", "inApp" to false, "push" to false))
                .problem(422, "NOTIFICATION_TYPE_MANDATORY")
            put().problem(400, "VALIDATION_FAILED")
        }
        preferences(user).forEach { it["inApp"].asBoolean() shouldBe true }
    }

    // ---- dispositivos de push -------------------------------------------------------------------------------

    @Test
    fun `a push device is registered for the current session, once per token and per session`() {
        val user = iam.createUser()
        val phone = iam.loginOk(user, "phone")
        val token = "fcm-${UuidV7.generate()}"

        val created = iam.call(POST, "/api/v1/push-devices", bearer(phone["accessToken"].asString()), device(token))
        created.status shouldBe 201
        val device = iam.json(created)
        device["platform"].asString() shouldBe "ANDROID"
        device["active"].asBoolean() shouldBe true
        device["currentSession"].asBoolean() shouldBe true
        device.has("token") shouldBe false

        // O mesmo token na mesma sessão: o mesmo dispositivo.
        val again =
            with(
                iam,
            ) { call(POST, "/api/v1/push-devices", bearer(phone["accessToken"].asString()), device(token)).ok() }
        again["id"].asString() shouldBe device["id"].asString()

        // Um token novo na mesma sessão (o app renovou o token): o anterior sai.
        val renewed =
            iam.call(
                POST,
                "/api/v1/push-devices",
                bearer(phone["accessToken"].asString()),
                device("$token-2"),
            )
        renewed.status shouldBe 201
        devices(user).associate { it["id"].asString() to it["deactivationReason"] }.let {
            it.getValue(device["id"].asString()).asString() shouldBe "REPLACED"
            it.getValue(iam.json(renewed)["id"].asString()).isNull shouldBe true
        }

        // O mesmo token registrado por outro usuário (o aparelho trocou de mãos): sai do anterior.
        val other = iam.createUser()
        val otherSession = iam.loginOk(other, "shared-phone")
        iam
            .call(
                POST,
                "/api/v1/push-devices",
                bearer(otherSession["accessToken"].asString()),
                device("$token-2"),
            ).status shouldBe
            201
        devices(user).filter { it["active"].asBoolean() }.shouldBeEmpty()
        devices(other).single()["active"].asBoolean() shouldBe true
    }

    @Test
    fun `a push device is deactivated only by its owner, and a revoked session cannot register one`() {
        val user = iam.createUser()
        val session = iam.loginOk(user, "phone")
        val auth = bearer(session["accessToken"].asString())
        val id =
            iam
                .json(
                    iam.call(POST, "/api/v1/push-devices", auth, device("fcm-${UuidV7.generate()}")),
                )["id"]
                .asString()

        val intruder = iam.createUser()
        with(iam) {
            call(
                POST,
                "/api/v1/push-devices/$id/deactivate",
                iam.asTestUser(intruder),
            ).problem(404, "PUSH_DEVICE_NOT_FOUND")
            call(GET, "/api/v1/push-devices", iam.asTestUser(intruder))
                .ok()["items"]
                .values()
                .toList()
                .shouldBeEmpty()

            val deactivated = call(POST, "/api/v1/push-devices/$id/deactivate", auth).ok()
            deactivated["active"].asBoolean() shouldBe false
            deactivated["deactivationReason"].asString() shouldBe "USER"
            call(POST, "/api/v1/push-devices/$id/deactivate", auth).ok()["deactivationReason"].asString() shouldBe
                "USER"

            call(POST, "/api/v1/push-devices", auth, mapOf("platform" to "ANDROID", "token" to " "))
                .problem(400, "VALIDATION_FAILED")
            call(POST, "/api/v1/push-devices", auth, mapOf("platform" to "PAGER", "token" to "abc"))
                .problem(400, "VALIDATION_FAILED")
        }

        iam.postWithToken("/api/v1/auth/logout", session["accessToken"].asString()).status shouldBe 204
        // O access token ainda vale, mas a sessão foi revogada.
        with(iam) {
            call(POST, "/api/v1/push-devices", auth, device("fcm-${UuidV7.generate()}")).problem(401, "UNAUTHENTICATED")
        }
    }

    @Test
    fun `the push token is stored encrypted and never audited`() {
        val user = iam.createUser()
        val token = "fcm-secret-${UuidV7.generate()}"
        val session = iam.loginOk(user, "phone")
        val id =
            iam
                .json(
                    iam.call(POST, "/api/v1/push-devices", bearer(session["accessToken"].asString()), device(token)),
                )["id"]
                .asString()

        val stored =
            jdbc
                .sql("SELECT token FROM notifications.push_device WHERE id = :id")
                .param("id", UUID.fromString(id))
                .query(String::class.java)
                .single()
        stored shouldNotBe token
        stored.startsWith("v1:") shouldBe true
        jdbc
            .sql("SELECT count(*) FROM audit.audit_entry WHERE entity_id = :id AND after_state::text LIKE :token")
            .param("id", id)
            .param("token", "%$token%")
            .query(Long::class.java)
            .single() shouldBe 0
        jdbc
            .sql(
                "SELECT action FROM audit.audit_entry " +
                    "WHERE entity_type = 'notifications.push-device' AND entity_id = :id",
            ).param("id", id)
            .query(String::class.java)
            .list() shouldContainExactly listOf("notifications.push-device.register")
    }

    // ---- apoio ----------------------------------------------------------------------------------------------

    private fun notify(
        type: NotificationTypeCode,
        title: String,
        body: String,
        user: UserId,
    ): List<NotificationId> = notifications.notify(type, title, body, NotificationRecipients.user(user))

    private fun panel(
        user: TestUser,
        query: String = "",
    ): JsonNode = with(iam) { call(GET, "/api/v1/notifications$query", asUser(user.id.value)).ok() }

    private fun unreadCount(user: TestUser): Int =
        with(iam) { call(GET, "/api/v1/notifications/unread-count", asUser(user.id.value)).ok() }["count"].asInt()

    private fun preferences(user: TestUser): List<JsonNode> =
        with(
            iam,
        ) { call(GET, "/api/v1/notification-preferences", asUser(user.id.value)).ok() }["preferences"].values().toList()

    private fun setPreference(
        user: TestUser,
        type: String,
        inApp: Boolean,
        push: Boolean,
    ) = with(iam) {
        call(
            PUT,
            "/api/v1/notification-preferences",
            asUser(user.id.value),
            mapOf("preferences" to listOf(mapOf("type" to type, "inApp" to inApp, "push" to push))),
        ).ok()
    }

    private fun devices(user: TestUser): List<JsonNode> =
        with(iam) { call(GET, "/api/v1/push-devices", asUser(user.id.value)).ok() }["items"].values().toList()

    private fun device(token: String) = mapOf("platform" to "ANDROID", "token" to token)

    private fun recipientsOf(ids: List<NotificationId>): List<UserId> =
        jdbc
            .sql("SELECT recipient_id FROM notifications.notification WHERE id = ANY(:ids)")
            .param("ids", ids.map { it.value }.toTypedArray())
            .query { rs, _ -> UserId(rs.getObject("recipient_id", UUID::class.java)) }
            .list()

    private fun activeAdministrators(): Set<UserId> =
        jdbc
            .sql(
                """
                SELECT DISTINCT u.id FROM iam.user_account u
                JOIN iam.user_role ur ON ur.user_id = u.id JOIN iam.role r ON r.id = ur.role_id
                WHERE u.active AND r.system_role AND r.active
                """.trimIndent(),
            ).query { rs, _ -> UserId(rs.getObject("id", UUID::class.java)) }
            .set()

    private fun IamTestSupport.mockMvcStatus(
        method: org.springframework.http.HttpMethod,
        path: String,
    ): Int = call(method, path, { it }).status
}
