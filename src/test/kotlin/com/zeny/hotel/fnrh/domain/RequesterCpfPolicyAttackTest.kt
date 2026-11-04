package com.zeny.hotel.fnrh.domain

import com.zeny.hotel.shared.Cpf
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/** Ataques do testador à política do CPF solicitante (M5 E07, Task 3): consulta, ordem e vazamento entre usuários. */
class RequesterCpfPolicyAttackTest {
    private val userCpf = Cpf.of("52998224725")
    private val otherCpf = Cpf.of("11144477735")
    private val defaultCpf = Cpf.of("39053344705")

    @Test
    fun `asks for the CPF of exactly the user who performed the operation, once`() {
        val user = UUID.randomUUID()
        val asked = mutableListOf<UUID>()

        val chosen =
            RequesterCpfPolicy.choose(
                user,
                {
                    asked += it
                    if (it == user) userCpf else otherCpf
                },
                defaultCpf,
            )

        chosen shouldBe userCpf
        asked shouldContainExactly listOf(user)
    }

    @Test
    fun `the user CPF wins even when there is no default`() {
        RequesterCpfPolicy.choose(UUID.randomUUID(), { userCpf }, null) shouldBe userCpf
    }

    @Test
    fun `never borrows the CPF of another user when the performer has none`() {
        val performer = UUID.randomUUID()
        val registry = mapOf(UUID.randomUUID() to otherCpf)

        RequesterCpfPolicy.choose(performer, { registry[it] }, defaultCpf) shouldBe defaultCpf
        RequesterCpfPolicy.choose(performer, { registry[it] }, null) shouldBe null
    }

    @Test
    fun `a null performer does not call the lookup with any argument`() {
        var calls = 0

        RequesterCpfPolicy.choose(
            null,
            {
                calls++
                userCpf
            },
            defaultCpf,
        ) shouldBe defaultCpf

        calls shouldBe 0
    }
}
