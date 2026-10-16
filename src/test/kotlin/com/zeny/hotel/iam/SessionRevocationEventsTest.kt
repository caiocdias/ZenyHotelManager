package com.zeny.hotel.iam

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.zeny.hotel.iam.application.SessionRevocations
import com.zeny.hotel.iam.domain.DeviceSession
import com.zeny.hotel.iam.domain.SessionRevocationReason
import com.zeny.hotel.shared.DeviceSessionStatus
import com.zeny.hotel.shared.DeviceSessionsRevoked
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import java.time.Instant
import java.util.UUID

/**
 * O `iam` avisa o kernel de cada sessão revogada (M2 E04, H8), pelo evento [DeviceSessionsRevoked], para o
 * canal de tempo real encerrar as conexões dela: logout, revogação pelo usuário e pelo administrador,
 * troca e redefinição de senha, novo login no mesmo dispositivo, reuso de refresh token e desativação do
 * usuário. Só a [SessionRevocations] revoga uma sessão, e ela sempre publica o evento.
 */
@IntegrationTest
@RecordApplicationEvents
@Import(IamTestConfiguration::class)
class SessionRevocationEventsTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val sessionStatus: DeviceSessionStatus,
) {
    // Só por campo (ou parâmetro do método de teste), não pelo construtor.
    @Suppress("VarCouldBeVal") // lateinit, injetado pelo Spring
    @Autowired
    private lateinit var events: ApplicationEvents

    @Test
    fun `only SessionRevocations revokes a device session`() {
        val classes =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPackages("com.zeny.hotel")
        noClasses()
            .that()
            .doNotHaveFullyQualifiedName(SessionRevocations::class.java.name)
            .should()
            .callMethod(
                DeviceSession::class.java,
                "revoke",
                SessionRevocationReason::class.java,
                Instant::class.java,
            ).because("toda revogação de sessão publica DeviceSessionsRevoked (H8)")
            .check(classes)
    }

    @Test
    fun `logout and the revocation by the user publish the revoked session`() {
        val user = iam.createUser()
        val phone = iam.loginOk(user, "phone")
        val tablet = iam.loginOk(user, "tablet")

        iam.postWithToken("/api/v1/auth/sessions/${sessionOf(phone)}/revoke", tokenOf(tablet)).status shouldBe 204
        revoked() shouldContainExactly listOf(DeviceSessionsRevoked(user.id.value, listOf(sessionOf(phone))))

        events.clear()
        iam.postWithToken("/api/v1/auth/logout", tokenOf(tablet)).status shouldBe 204
        revoked() shouldContainExactly listOf(DeviceSessionsRevoked(user.id.value, listOf(sessionOf(tablet))))
        sessionStatus.isRevoked(
            com.zeny.hotel.shared
                .DeviceSessionId(sessionOf(tablet)),
        ) shouldBe true
    }

    @Test
    fun `changing the password publishes the other sessions, and a new login on the device the replaced one`() {
        val user = iam.createUser()
        val phone = iam.loginOk(user, "phone")
        val tablet = iam.loginOk(user, "tablet")
        events.clear()

        iam
            .postWithToken(
                "/api/v1/auth/password",
                tokenOf(phone),
                mapOf("currentPassword" to user.password, "newPassword" to "${user.password}-nova"),
            ).status shouldBe 204
        revoked() shouldContainExactly listOf(DeviceSessionsRevoked(user.id.value, listOf(sessionOf(tablet))))
        sessionStatus.isRevoked(
            com.zeny.hotel.shared
                .DeviceSessionId(sessionOf(phone)),
        ) shouldBe false

        events.clear()
        iam.loginOk(user.copy(password = "${user.password}-nova"), "phone")
        revoked() shouldContainExactly listOf(DeviceSessionsRevoked(user.id.value, listOf(sessionOf(phone))))
    }

    @Test
    fun `the administrator revoking a session, resetting the password or deactivating publishes them`() {
        val user = iam.createUser()
        val phone = iam.loginOk(user, "phone")
        val tablet = iam.loginOk(user, "tablet")
        val laptop = iam.loginOk(user, "laptop")
        events.clear()

        iam.call(POST, "/api/v1/users/${user.id}/sessions/${sessionOf(phone)}/revoke", iam.asAdmin()).status shouldBe
            204
        revoked() shouldContainExactly listOf(DeviceSessionsRevoked(user.id.value, listOf(sessionOf(phone))))

        events.clear()
        iam
            .call(
                POST,
                "/api/v1/users/${user.id}/reset-password",
                iam.asAdmin(),
                mapOf("newPassword" to "Provisoria-de-teste-1"),
            ).status shouldBe 204
        revoked().single().sessionIds shouldContainExactlyInAnyOrder listOf(sessionOf(tablet), sessionOf(laptop))

        val again = iam.loginOk(user.copy(password = "Provisoria-de-teste-1"), "phone")
        events.clear()
        iam.call(POST, "/api/v1/users/${user.id}/deactivate", iam.asAdmin()).status shouldBe 200
        revoked() shouldContainExactly listOf(DeviceSessionsRevoked(user.id.value, listOf(sessionOf(again))))
    }

    @Test
    fun `reusing a rotated refresh token publishes the revoked session, and nothing is published without a change`() {
        val user = iam.createUser()
        val login = iam.loginOk(user)
        iam.refresh(login["refreshToken"].asString()).status shouldBe 200
        events.clear()

        iam.refresh(login["refreshToken"].asString()).status shouldBe 401
        revoked() shouldContainExactly listOf(DeviceSessionsRevoked(user.id.value, listOf(sessionOf(login))))

        events.clear()
        // Revogar de novo uma sessão já revogada não muda nada nem publica.
        iam.refresh(login["refreshToken"].asString()).status shouldBe 401
        revoked().shouldBeEmpty()
    }

    private fun revoked(): List<DeviceSessionsRevoked> = events.stream(DeviceSessionsRevoked::class.java).toList()

    private fun sessionOf(login: tools.jackson.databind.JsonNode): UUID = UUID.fromString(login["sessionId"].asString())

    private fun tokenOf(login: tools.jackson.databind.JsonNode): String = login["accessToken"].asString()
}
