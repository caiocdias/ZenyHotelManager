package com.zeny.hotel.fnrh.domain

import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.Cpf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** Regras da configuração da integração com a FNRH (M5 E07, D-E07-1). */
class FnrhSettingsTest {
    private val cpf = Cpf.of("52998224725")

    private fun configured(): FnrhSettings =
        FnrhSettings.initial().also {
            it.update(FnrhEnvironment.HOMOLOGATION, "hotel-user", "secret-1", cpf, enabled = true)
        }

    @Test
    fun `the initial settings are off, in homologation and empty`() {
        val settings = FnrhSettings.initial()

        settings.environment shouldBe FnrhEnvironment.HOMOLOGATION
        settings.enabled shouldBe false
        settings.username shouldBe null
        settings.password shouldBe null
        settings.defaultCpf shouldBe null
    }

    @Test
    fun `a null password keeps the current one`() {
        val settings = configured()

        settings.update(FnrhEnvironment.HOMOLOGATION, "other-user", null, cpf, enabled = true)

        settings.password shouldBe "secret-1"
        settings.username shouldBe "other-user"
    }

    @Test
    fun `changing the environment without a new password is refused and changes nothing`() {
        val settings = configured()

        val failure =
            shouldThrow<BusinessRuleException> {
                settings.update(FnrhEnvironment.PRODUCTION, "hotel-user", null, cpf, enabled = true)
            }

        failure.code.value shouldBe "FNRH_PASSWORD_REQUIRED_FOR_ENVIRONMENT_CHANGE"
        settings.environment shouldBe FnrhEnvironment.HOMOLOGATION
        settings.password shouldBe "secret-1"
    }

    @Test
    fun `changing the environment with a new password keeps the user`() {
        val settings = configured()

        settings.update(FnrhEnvironment.PRODUCTION, "hotel-user", "secret-2", cpf, enabled = true)

        settings.environment shouldBe FnrhEnvironment.PRODUCTION
        settings.username shouldBe "hotel-user"
        settings.password shouldBe "secret-2"
    }

    @Test
    fun `enabling without user, password or CPF is refused`() {
        shouldThrow<BusinessRuleException> {
            FnrhSettings.initial().update(FnrhEnvironment.HOMOLOGATION, null, "secret", cpf, enabled = true)
        }.code.value shouldBe "FNRH_SETTINGS_INCOMPLETE"

        shouldThrow<BusinessRuleException> {
            FnrhSettings.initial().update(FnrhEnvironment.HOMOLOGATION, "user", null, cpf, enabled = true)
        }.code.value shouldBe "FNRH_SETTINGS_INCOMPLETE"

        shouldThrow<BusinessRuleException> {
            FnrhSettings.initial().update(FnrhEnvironment.HOMOLOGATION, "user", "secret", null, enabled = true)
        }.code.value shouldBe "FNRH_SETTINGS_INCOMPLETE"

        shouldThrow<BusinessRuleException> {
            FnrhSettings.initial().update(FnrhEnvironment.HOMOLOGATION, "  ", "secret", cpf, enabled = true)
        }.code.value shouldBe "FNRH_SETTINGS_INCOMPLETE"
    }

    @Test
    fun `incomplete settings can be saved while off`() {
        val settings = FnrhSettings.initial()

        settings.update(FnrhEnvironment.HOMOLOGATION, "user", null, null, enabled = false)

        settings.username shouldBe "user"
        settings.enabled shouldBe false
    }

    @Test
    fun `toString never shows the secrets`() {
        val text = configured().toString()

        text shouldNotContain "secret-1"
        text shouldNotContain "hotel-user"
        text shouldNotContain "52998224725"
    }
}
