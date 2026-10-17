package com.zeny.hotel.shared.infrastructure.crypto

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Índice cego (H8 do M3): determinístico por chave e finalidade, com chave derivada (nunca a chave do anel
 * nem a dos tokens), e a busca com todas as chaves do anel encontra o que foi indexado antes da rotação.
 */
class HmacBlindIndexTest {
    private val oldKey = randomKey()
    private val newKey = randomKey()

    private fun ring(
        vararg keys: Pair<String, ByteArray>,
        active: String,
    ) = EncryptionKeyRing.of(keys.toMap(), active)

    @Test
    fun `the same value gives the same index, and another value or purpose another one`() {
        val index = HmacBlindIndex(ring("k1" to oldKey, active = "k1"))

        val first = index.compute("guests.document", "CPF:BR:52998224725")
        val second = index.compute("guests.document", "CPF:BR:52998224725")

        first shouldBe second
        first.keyId shouldBe "k1"
        first.hash.length shouldBe 43
        first.hash shouldNotContain "52998224725"
        index.compute("guests.document", "CPF:BR:11144477735").hash shouldNotBe first.hash
        index.compute("other.purpose", "CPF:BR:52998224725").hash shouldNotBe first.hash
    }

    @Test
    fun `the HMAC key is derived, not the key of the ring itself`() {
        val index = HmacBlindIndex(ring("k1" to oldKey, active = "k1"))
        val plainHmac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(oldKey, "HmacSHA256")) }
        val withRingKey =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(plainHmac.doFinal("CPF:BR:52998224725".toByteArray()))

        index.compute("guests.document", "CPF:BR:52998224725").hash shouldNotBe withRingKey
    }

    @Test
    fun `after a rotation the search candidates still include the index computed with the old key`() {
        val before = HmacBlindIndex(ring("k1" to oldKey, active = "k1"))
        val stored = before.compute("guests.document", "PASSPORT:AR:AB123456")

        val after = HmacBlindIndex(ring("k1" to oldKey, "k2" to newKey, active = "k2"))
        val candidates = after.candidates("guests.document", "PASSPORT:AR:AB123456")

        candidates.map { it.keyId } shouldContainExactly listOf("k2", "k1") // a ativa primeiro
        candidates shouldContain stored
        after.compute("guests.document", "PASSPORT:AR:AB123456").keyId shouldBe "k2"
        after.compute("guests.document", "PASSPORT:AR:AB123456") shouldNotBe stored
    }

    @Test
    fun `toString never shows key material`() {
        HmacBlindIndex(ring("k1" to oldKey, active = "k1")).toString() shouldBe
            "HmacBlindIndex(keyIds=[k1], activeKeyId=k1)"
    }

    private fun randomKey() = ByteArray(32).also(SecureRandom()::nextBytes)
}
