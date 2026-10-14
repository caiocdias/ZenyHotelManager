package com.zeny.hotel.shared.infrastructure.crypto

import com.zeny.hotel.shared.FieldDecryptionException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.Base64

/** ARCHITECTURE §8.3: AES-GCM por campo, chave fora do banco, identificador de chave para rotação. */
class AesGcmFieldCipherTest {
    private val oldKey = randomKey()
    private val newKey = randomKey()

    private fun cipher(
        vararg keys: Pair<String, ByteArray>,
        active: String,
    ) = AesGcmFieldCipher(EncryptionKeyRing.of(keys.toMap(), active))

    @Test
    fun `round-trips text and records the key id in the stored value`() {
        val cipher = cipher("k2026a" to oldKey, active = "k2026a")

        val stored = cipher.encrypt("123.456.789-09 ação")

        stored shouldStartWith "v1:k2026a:"
        stored shouldNotContain "123.456.789-09"
        cipher.keyIdOf(stored) shouldBe "k2026a"
        cipher.decrypt(stored) shouldBe "123.456.789-09 ação"
        cipher.decrypt(cipher.encrypt("")) shouldBe ""
    }

    @Test
    fun `uses a fresh random nonce for every value`() {
        val cipher = cipher("k" to oldKey, active = "k")

        val stored = List(10_000) { cipher.encrypt("same plaintext") }

        stored.toSet() shouldHaveSize stored.size
        stored.map { nonceOf(it) }.toSet() shouldHaveSize stored.size
        stored.forEach { nonceOf(it).size shouldBe 12 }
    }

    @Test
    fun `decrypts values written with the previous key after rotation`() {
        val before = cipher("k2026a" to oldKey, active = "k2026a")
        val written = before.encrypt("segredo antigo")

        val rotated = cipher("k2026a" to oldKey, "k2026b" to newKey, active = "k2026b")

        rotated.decrypt(written) shouldBe "segredo antigo"
        rotated.isEncryptedWithActiveKey(written) shouldBe false
        val rewritten = rotated.encrypt(rotated.decrypt(written))
        rotated.keyIdOf(rewritten) shouldBe "k2026b"
        rotated.isEncryptedWithActiveKey(rewritten) shouldBe true
        rotated.decrypt(rewritten) shouldBe "segredo antigo"
    }

    @Test
    fun `refuses values whose key is no longer configured`() {
        val written = cipher("k2026a" to oldKey, active = "k2026a").encrypt("segredo")
        val withoutOldKey = cipher("k2026b" to newKey, active = "k2026b")

        val error = shouldThrow<FieldDecryptionException> { withoutOldKey.decrypt(written) }
        error.message shouldBe "Chave de cifragem 'k2026a' não está configurada"
    }

    @Test
    fun `detects tampering with the ciphertext or the key id`() {
        val cipher = cipher("a" to oldKey, "b" to newKey, active = "a")
        val stored = cipher.encrypt("segredo")

        val payload = Base64.getUrlDecoder().decode(stored.substringAfterLast(':'))
        payload[payload.size - 1] = (payload[payload.size - 1].toInt() xor 1).toByte()
        val tampered = "v1:a:" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        shouldThrow<FieldDecryptionException> { cipher.decrypt(tampered) }

        // O identificador da chave entra como dado autenticado: trocá-lo não decifra.
        val sameKeyRelabeled = cipher("a" to oldKey, "b" to oldKey, active = "a")
        shouldThrow<FieldDecryptionException> { sameKeyRelabeled.decrypt(stored.replaceFirst("v1:a:", "v1:b:")) }

        shouldThrow<FieldDecryptionException> { cipher.decrypt("plain text") }
        shouldThrow<FieldDecryptionException> { cipher.decrypt("v2:a:AAAA") }
        shouldThrow<FieldDecryptionException> { cipher.decrypt("v1:a:AAAA") }
        shouldThrow<FieldDecryptionException> { cipher.decrypt("v1:a:%%%") }
    }

    @Test
    fun `the same plaintext under different keys gives different values`() {
        val a = cipher("a" to oldKey, active = "a").encrypt("x")
        val b = cipher("a" to newKey, active = "a").encrypt("x")

        a shouldNotBe b
        shouldThrow<FieldDecryptionException> { cipher("a" to newKey, active = "a").decrypt(a) }
    }

    @Test
    fun `parses keys from configuration text`() {
        val text = "k2026a:${base64(oldKey)}, k2026b:${base64(newKey)}"
        val ring = EncryptionKeyRing.parse(text, "k2026b")

        ring.activeKeyId shouldBe "k2026b"
        ring.keyIds shouldBe setOf("k2026a", "k2026b")
        ring.toString() shouldNotContain base64(oldKey)
        ring.toString() shouldNotContain base64(newKey)
    }

    @Test
    fun `rejects invalid key configuration without echoing key material`() {
        val good = base64(oldKey)
        val short = base64(ByteArray(16) { 1 })

        shouldThrow<IllegalArgumentException> { EncryptionKeyRing.parse("", "a") }
        shouldThrow<IllegalArgumentException> { EncryptionKeyRing.parse("a:$good", "b") }
        shouldThrow<IllegalArgumentException> { EncryptionKeyRing.parse("a:$good,a:$good", "a") }
        shouldThrow<IllegalArgumentException> { EncryptionKeyRing.parse("a$good", "a") }
        shouldThrow<IllegalArgumentException> { EncryptionKeyRing.parse("bad id:$good", "bad id") }
        shouldThrow<IllegalArgumentException> { EncryptionKeyRing.parse("a:not-base64!", "a") }
        val error = shouldThrow<IllegalArgumentException> { EncryptionKeyRing.parse("a:$short", "a") }
        error.message!! shouldNotContain short
        error.message shouldBe "A chave de cifragem 'a' precisa ter 32 bytes (AES-256); tem 16"
    }

    @Test
    fun `configuration without the environment variables fails with a clear message`() {
        val missingKeys =
            shouldThrow<IllegalArgumentException> {
                FieldEncryptionProperties(keys = "\${ZENY_ENCRYPTION_KEYS}", activeKeyId = "a")
            }
        missingKeys.message!! shouldContain "ZENY_ENCRYPTION_KEYS"
        val missingActive =
            shouldThrow<IllegalArgumentException> {
                FieldEncryptionProperties(
                    keys = "a:${base64(oldKey)}",
                    activeKeyId = "\${ZENY_ENCRYPTION_ACTIVE_KEY_ID}",
                )
            }
        missingActive.message!! shouldContain "ZENY_ENCRYPTION_ACTIVE_KEY_ID"
        shouldThrow<IllegalArgumentException> { FieldEncryptionProperties(keys = " ", activeKeyId = "a") }
        FieldEncryptionProperties(keys = "a:${base64(oldKey)}", activeKeyId = "a").toString() shouldNotContain
            base64(oldKey)
    }

    private fun nonceOf(stored: String): ByteArray =
        Base64.getUrlDecoder().decode(stored.substringAfterLast(':')).copyOfRange(0, 12)

    private companion object {
        val random = SecureRandom()

        fun randomKey(): ByteArray = ByteArray(32).also(random::nextBytes)

        fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    }
}
