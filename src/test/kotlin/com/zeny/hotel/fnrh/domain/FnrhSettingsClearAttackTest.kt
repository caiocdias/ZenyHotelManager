package com.zeny.hotel.fnrh.domain

import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.FnrhErrorCodes
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.Cpf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** Ataques à limpeza da credencial da FNRH a partir de estados parciais e dos dois ambientes (M5 E16, Task 1). */
class FnrhSettingsClearAttackTest {
    private val cpf = Cpf.of("52998224725")

    private fun assertCleared(
        settings: FnrhSettings,
        environment: FnrhEnvironment,
    ) {
        settings.username shouldBe null
        settings.password shouldBe null
        settings.defaultCpf shouldBe null
        settings.enabled shouldBe false
        settings.environment shouldBe environment
    }

    @Test
    fun `clearCredentials on an already empty configuration is a no-op that stays off`() {
        val settings = FnrhSettings.initial()

        settings.clearCredentials()

        assertCleared(settings, FnrhEnvironment.HOMOLOGATION)
    }

    @Test
    fun `clearCredentials is idempotent`() {
        val settings = FnrhSettings.initial()
        settings.update(FnrhEnvironment.PRODUCTION, "u", "p", cpf, true)

        settings.clearCredentials()
        settings.clearCredentials()

        assertCleared(settings, FnrhEnvironment.PRODUCTION)
    }

    @Test
    fun `clearCredentials with only the username set empties it`() {
        val settings = FnrhSettings.initial()
        settings.update(FnrhEnvironment.HOMOLOGATION, "so-usuario", null, null, false)
        settings.username shouldBe "so-usuario"

        settings.clearCredentials()

        assertCleared(settings, FnrhEnvironment.HOMOLOGATION)
    }

    @Test
    fun `clearCredentials with only the password set empties it`() {
        val settings = FnrhSettings.initial()
        settings.update(FnrhEnvironment.HOMOLOGATION, null, "so-senha", null, false)
        settings.password shouldBe "so-senha"

        settings.clearCredentials()

        assertCleared(settings, FnrhEnvironment.HOMOLOGATION)
    }

    @Test
    fun `clearCredentials with only the default CPF set empties it`() {
        val settings = FnrhSettings.initial()
        settings.update(FnrhEnvironment.HOMOLOGATION, null, null, cpf, false)
        settings.defaultCpf shouldBe cpf

        settings.clearCredentials()

        assertCleared(settings, FnrhEnvironment.HOMOLOGATION)
    }

    @Test
    fun `clearCredentials in HOMOLOGATION keeps HOMOLOGATION and empties everything`() {
        val settings = FnrhSettings.initial()
        settings.update(FnrhEnvironment.HOMOLOGATION, "u", "p", cpf, true)

        settings.clearCredentials()

        assertCleared(settings, FnrhEnvironment.HOMOLOGATION)
    }

    @Test
    fun `clearCredentials in PRODUCTION keeps PRODUCTION and empties everything`() {
        val settings = FnrhSettings.initial()
        settings.update(FnrhEnvironment.PRODUCTION, "u", "p", cpf, true)

        settings.clearCredentials()

        assertCleared(settings, FnrhEnvironment.PRODUCTION)
    }

    @Test
    fun `the old password is not resurrected by an update without password after clearCredentials`() {
        val settings = FnrhSettings.initial()
        settings.update(FnrhEnvironment.PRODUCTION, "u", "velha", cpf, true)
        settings.clearCredentials()

        val error =
            shouldThrow<BusinessRuleException> {
                settings.update(FnrhEnvironment.PRODUCTION, "novo", null, cpf, true)
            }

        error.code shouldBe FnrhErrorCodes.FNRH_SETTINGS_INCOMPLETE
        settings.password shouldBe null
        settings.enabled shouldBe false
    }

    @Test
    fun `an update without password after clearCredentials stores no password even when disabled`() {
        val settings = FnrhSettings.initial()
        settings.update(FnrhEnvironment.PRODUCTION, "u", "velha", cpf, true)
        settings.clearCredentials()

        settings.update(FnrhEnvironment.PRODUCTION, "novo", null, cpf, false)

        settings.password shouldBe null
        settings.username shouldBe "novo"
    }

    @Test
    fun `changing environment after clearCredentials without a new password is refused`() {
        val settings = FnrhSettings.initial()
        settings.update(FnrhEnvironment.PRODUCTION, "u", "p", cpf, true)
        settings.clearCredentials()

        val error =
            shouldThrow<BusinessRuleException> {
                settings.update(FnrhEnvironment.HOMOLOGATION, "u", null, cpf, false)
            }

        error.code shouldBe FnrhErrorCodes.FNRH_PASSWORD_REQUIRED_FOR_ENVIRONMENT_CHANGE
        assertCleared(settings, FnrhEnvironment.PRODUCTION)
    }

    @Test
    fun `enabling after clearCredentials with the username only is incomplete`() {
        val settings = FnrhSettings.initial()
        settings.update(FnrhEnvironment.PRODUCTION, "u", "p", cpf, true)
        settings.clearCredentials()

        val error =
            shouldThrow<BusinessRuleException> {
                settings.update(FnrhEnvironment.PRODUCTION, "u", "nova", null, true)
            }

        error.code shouldBe FnrhErrorCodes.FNRH_SETTINGS_INCOMPLETE
        settings.enabled shouldBe false
    }

    @Test
    fun `toString after clearCredentials and after update carries no secret`() {
        val settings = FnrhSettings.initial()
        settings.update(FnrhEnvironment.PRODUCTION, "hotel-user", "s3cr3t", cpf, true)
        val before = settings.toString()
        settings.clearCredentials()
        val after = settings.toString()

        listOf(before, after).forEach {
            it shouldNotContain "hotel-user"
            it shouldNotContain "s3cr3t"
            it shouldNotContain cpf.digits
        }
        after shouldBe "FnrhSettings(environment=PRODUCTION, enabled=false)"
    }
}
