package com.zeny.hotel.fnrh.domain

import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.Cpf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** Ataques às regras de `FnrhSettings.update` (M5 E07, testador): ordem das regras, bordas e atomicidade. */
class FnrhSettingsRulesTest {
    private val cpf = Cpf.of("52998224725")
    private val otherCpf = Cpf.of("11144477735")

    private fun configured(): FnrhSettings =
        FnrhSettings.initial().also {
            it.update(FnrhEnvironment.HOMOLOGATION, "hotel-user", "secret-1", cpf, enabled = true)
        }

    @Test
    fun `a refused update changes no field at all`() {
        val settings = configured()

        shouldThrow<BusinessRuleException> {
            settings.update(FnrhEnvironment.PRODUCTION, "intruder", null, otherCpf, enabled = false)
        }

        settings.environment shouldBe FnrhEnvironment.HOMOLOGATION
        settings.username shouldBe "hotel-user"
        settings.password shouldBe "secret-1"
        settings.defaultCpf shouldBe cpf
        settings.enabled shouldBe true
    }

    @Test
    fun `an incomplete enabling is refused and keeps the previous state`() {
        val settings = configured()

        shouldThrow<BusinessRuleException> {
            settings.update(FnrhEnvironment.HOMOLOGATION, "hotel-user", null, null, enabled = true)
        }.code.value shouldBe "FNRH_SETTINGS_INCOMPLETE"

        settings.defaultCpf shouldBe cpf
        settings.enabled shouldBe true
    }

    @Test
    fun `changing the environment without password is refused even when switching off`() {
        val settings = configured()

        shouldThrow<BusinessRuleException> {
            settings.update(FnrhEnvironment.PRODUCTION, "hotel-user", null, cpf, enabled = false)
        }.code.value shouldBe "FNRH_PASSWORD_REQUIRED_FOR_ENVIRONMENT_CHANGE"
    }

    @Test
    fun `changing the environment without password is refused before the incompleteness rule`() {
        val settings = configured()

        shouldThrow<BusinessRuleException> {
            settings.update(FnrhEnvironment.PRODUCTION, null, null, null, enabled = true)
        }.code.value shouldBe "FNRH_PASSWORD_REQUIRED_FOR_ENVIRONMENT_CHANGE"
    }

    @Test
    fun `changing the environment with a new password while off is accepted`() {
        val settings = configured()

        settings.update(FnrhEnvironment.PRODUCTION, "hotel-user", "secret-2", cpf, enabled = false)

        settings.environment shouldBe FnrhEnvironment.PRODUCTION
        settings.enabled shouldBe false
        settings.password shouldBe "secret-2"
    }

    @Test
    fun `switching off keeps the stored credentials`() {
        val settings = configured()

        settings.update(FnrhEnvironment.HOMOLOGATION, "hotel-user", null, cpf, enabled = false)

        settings.enabled shouldBe false
        settings.password shouldBe "secret-1"
        settings.defaultCpf shouldBe cpf
    }

    @Test
    fun `an empty password cannot enable the integration`() {
        shouldThrow<BusinessRuleException> {
            FnrhSettings.initial().update(FnrhEnvironment.HOMOLOGATION, "user", "", cpf, enabled = true)
        }.code.value shouldBe "FNRH_SETTINGS_INCOMPLETE"
    }

    @Test
    fun `an empty new password cannot replace a good one while enabled`() {
        val settings = configured()

        shouldThrow<BusinessRuleException> {
            settings.update(FnrhEnvironment.HOMOLOGATION, "hotel-user", "", cpf, enabled = true)
        }.code.value shouldBe "FNRH_SETTINGS_INCOMPLETE"

        settings.password shouldBe "secret-1"
    }

    @Test
    fun `the user name is trimmed and a blank one is stored as absent`() {
        val settings = FnrhSettings.initial()

        settings.update(FnrhEnvironment.HOMOLOGATION, "  hotel-user  ", "secret", cpf, enabled = true)
        settings.username shouldBe "hotel-user"

        settings.update(FnrhEnvironment.HOMOLOGATION, "   ", "secret", cpf, enabled = false)
        settings.username shouldBe null
    }

    @Test
    fun `the default CPF can be replaced and cleared while off`() {
        val settings = configured()

        settings.update(FnrhEnvironment.HOMOLOGATION, "hotel-user", null, otherCpf, enabled = true)
        settings.defaultCpf shouldBe otherCpf

        settings.update(FnrhEnvironment.HOMOLOGATION, "hotel-user", null, null, enabled = false)
        settings.defaultCpf shouldBe null
    }

    @Test
    fun `toString shows only environment and enabled, even after a new password`() {
        val settings = configured()
        settings.update(FnrhEnvironment.PRODUCTION, "hotel-user", "secret-2", otherCpf, enabled = true)

        val text = settings.toString()

        text shouldBe "FnrhSettings(environment=PRODUCTION, enabled=true)"
        text shouldNotContain "secret-2"
        text shouldNotContain "11144477735"
    }
}
