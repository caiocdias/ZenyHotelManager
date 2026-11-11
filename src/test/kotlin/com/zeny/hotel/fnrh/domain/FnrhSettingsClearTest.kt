package com.zeny.hotel.fnrh.domain

import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.Cpf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Limpeza da credencial da configuração da FNRH (M5 E16, D-E16-2). */
class FnrhSettingsClearTest {
    private val cpf = Cpf.of("52998224725")

    private fun configured(enabled: Boolean = true): FnrhSettings =
        FnrhSettings.initial().also {
            it.update(FnrhEnvironment.PRODUCTION, "hotel-user", "s3cr3t", cpf, enabled = enabled)
        }

    @Test
    fun `clearCredentials empties username, password and CPF and turns the integration off`() {
        val settings = configured()

        settings.clearCredentials()

        settings.username shouldBe null
        settings.password shouldBe null
        settings.defaultCpf shouldBe null
        settings.enabled shouldBe false
    }

    @Test
    fun `clearCredentials keeps the environment`() {
        val settings = configured()

        settings.clearCredentials()

        settings.environment shouldBe FnrhEnvironment.PRODUCTION
    }

    @Test
    fun `clearCredentials on a disabled configuration with credentials empties it too`() {
        val settings = configured(enabled = false)

        settings.clearCredentials()

        settings.username shouldBe null
        settings.password shouldBe null
        settings.defaultCpf shouldBe null
    }

    @Test
    fun `enabling after clearCredentials without new credentials is FNRH_SETTINGS_INCOMPLETE`() {
        val settings = configured()
        settings.clearCredentials()

        val failure =
            shouldThrow<BusinessRuleException> {
                settings.update(FnrhEnvironment.PRODUCTION, null, null, null, enabled = true)
            }

        failure.code.value shouldBe "FNRH_SETTINGS_INCOMPLETE"
        settings.username shouldBe null
        settings.password shouldBe null
        settings.defaultCpf shouldBe null
        settings.enabled shouldBe false
    }

    @Test
    fun `enabling after clearCredentials with new credentials works`() {
        val settings = configured()
        settings.clearCredentials()

        settings.update(FnrhEnvironment.PRODUCTION, "outro", "nova-senha", Cpf.of("11144477735"), enabled = true)

        settings.enabled shouldBe true
    }
}
