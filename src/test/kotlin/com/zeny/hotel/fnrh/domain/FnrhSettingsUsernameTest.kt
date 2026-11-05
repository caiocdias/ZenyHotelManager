package com.zeny.hotel.fnrh.domain

import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.shared.Cpf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** O domínio recusa `:` no usuário (RFC 7617), mesmo que a API não o tenha barrado. */
class FnrhSettingsUsernameTest {
    @Test
    fun `a username with a colon is refused and nothing changes`() {
        val settings = FnrhSettings.initial()

        shouldThrow<IllegalArgumentException> {
            settings.update(FnrhEnvironment.HOMOLOGATION, "dom:user", "secret", Cpf.of("52998224725"), enabled = true)
        }

        settings.username shouldBe null
        settings.enabled shouldBe false
    }
}
