package com.zeny.hotel.shared.infrastructure.crypto

import com.zeny.hotel.shared.FieldDecryptionException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.security.SecureRandom

/**
 * ARCHITECTURE §8.3: anexos sensíveis cifrados com AES-GCM sobre o mesmo anel de chaves da cifragem
 * de campo, com o id da chave no conteúdo (rotação) e o dado associado amarrando o conteúdo ao anexo.
 */
class AesGcmContentCipherTest {
    private val oldKey = randomKey()
    private val newKey = randomKey()
    private val document = "CPF 123.456.789-09 — documento de hóspede".toByteArray() + ByteArray(4096) { it.toByte() }

    private fun cipher(
        vararg keys: Pair<String, ByteArray>,
        active: String,
    ) = AesGcmContentCipher(EncryptionKeyRing.of(keys.toMap(), active))

    @Test
    fun `round-trips bytes without leaving the original content in the stored bytes`() {
        val cipher = cipher("k2026a" to oldKey, active = "k2026a")

        val stored = cipher.encrypt(document, "file-1".toByteArray())

        stored.size shouldBe document.size + 4 + 1 + "k2026a".length + 12 + 16
        String(stored.copyOfRange(0, 4), Charsets.US_ASCII) shouldBe "ZNC1"
        stored.containsSequence("123.456.789-09".toByteArray()) shouldBe false
        stored.containsSequence(document.copyOfRange(60, 120)) shouldBe false
        cipher.keyIdOf(stored) shouldBe "k2026a"
        cipher.decrypt(stored, "file-1".toByteArray()) shouldBe document
        cipher.decrypt(cipher.encrypt(ByteArray(0))) shouldBe ByteArray(0)
    }

    @Test
    fun `uses a fresh random nonce for every content`() {
        val cipher = cipher("k" to oldKey, active = "k")

        val stored = List(2_000) { cipher.encrypt(document) }

        stored.map { it.toList() }.toSet() shouldHaveSize stored.size
        stored.map { it.copyOfRange(6, 18).toList() }.toSet() shouldHaveSize stored.size
    }

    @Test
    fun `the content of one file does not decrypt as another file`() {
        val cipher = cipher("k" to oldKey, active = "k")
        val stored = cipher.encrypt(document, "file-1".toByteArray())

        shouldThrow<FieldDecryptionException> { cipher.decrypt(stored, "file-2".toByteArray()) }
        shouldThrow<FieldDecryptionException> { cipher.decrypt(stored) }
    }

    @Test
    fun `decrypts content written with the previous key after rotation`() {
        val written = cipher("k2026a" to oldKey, active = "k2026a").encrypt(document)

        val rotated = cipher("k2026a" to oldKey, "k2026b" to newKey, active = "k2026b")

        rotated.decrypt(written) shouldBe document
        rotated.keyIdOf(written) shouldBe "k2026a"
        rotated.keyIdOf(rotated.encrypt(document)) shouldBe "k2026b"
        rotated.activeKeyId shouldBe "k2026b"
    }

    @Test
    fun `tampered, truncated, foreign or unknown-key content fails without revealing the key`() {
        val cipher = cipher("k" to oldKey, active = "k")
        val stored = cipher.encrypt(document)

        val tampered = stored.copyOf().also { it[it.size - 20] = (it[it.size - 20].toInt() xor 1).toByte() }
        shouldThrow<FieldDecryptionException> { cipher.decrypt(tampered) }
        shouldThrow<FieldDecryptionException> { cipher.decrypt(stored.copyOfRange(0, 20)) }
        shouldThrow<FieldDecryptionException> { cipher.decrypt(document) }
        shouldThrow<FieldDecryptionException> { cipher.decrypt(ByteArray(0)) }
        // O id da chave trocado no cabeçalho (mesmo tamanho) também falha: o cabeçalho é AAD.
        val otherHeader = stored.copyOf().also { it[5] = 'x'.code.toByte() }
        val withBoth = cipher("k" to oldKey, "x" to oldKey, active = "k")
        shouldThrow<FieldDecryptionException> { withBoth.decrypt(otherHeader) }

        val unknown = shouldThrow<FieldDecryptionException> { cipher("j" to newKey, active = "j").decrypt(stored) }
        unknown.message shouldContain "'k'"
        unknown.message!!.contains(oldKey.toString()) shouldBe false
    }

    @Test
    fun `different keys give different content`() {
        val first = cipher("k" to oldKey, active = "k").encrypt(document)
        val second = cipher("k" to newKey, active = "k").encrypt(document)

        first.copyOfRange(18, first.size).toList() shouldNotBe second.copyOfRange(18, second.size).toList()
    }

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

    private fun randomKey() = ByteArray(EncryptionKeyRing.KEY_BYTES).also(SecureRandom()::nextBytes)
}
