package com.zeny.hotel.fnrh.domain

import com.zeny.hotel.shared.Cpf
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class RequesterCpfPolicyTest {
    private val userCpf = Cpf.of("52998224725")
    private val defaultCpf = Cpf.of("11144477735")
    private val user = UUID.randomUUID()

    @Test
    fun `chooses the CPF of the user who performed the operation`() {
        RequesterCpfPolicy.choose(user, { userCpf }, defaultCpf) shouldBe userCpf
    }

    @Test
    fun `falls back to the default when the user has none`() {
        RequesterCpfPolicy.choose(user, { null }, defaultCpf) shouldBe defaultCpf
    }

    @Test
    fun `is null when there is neither`() {
        RequesterCpfPolicy.choose(user, { null }, null) shouldBe null
    }

    @Test
    fun `uses the default without a performer and never looks anyone up`() {
        RequesterCpfPolicy.choose(null, { error("não deveria consultar") }, defaultCpf) shouldBe defaultCpf
        RequesterCpfPolicy.choose(null, { error("não deveria consultar") }, null) shouldBe null
    }
}
