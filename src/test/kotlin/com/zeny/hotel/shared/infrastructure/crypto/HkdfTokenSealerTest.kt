package com.zeny.hotel.shared.infrastructure.crypto

import com.zeny.hotel.shared.FieldDecryptionException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.HexFormat

/**
 * Tokens selados com chave derivada por HKDF (ARCHITECTURE §8.3): domínio separado da cifragem de
 * colunas e de outras finalidades, com o id da chave para a rotação.
 */
class HkdfTokenSealerTest {
    private val oldKey = randomKey()
    private val newKey = randomKey()

    private fun ring(
        vararg keys: Pair<String, ByteArray>,
        active: String,
    ) = EncryptionKeyRing.of(keys.toMap(), active)

    @Test
    fun `round-trips a payload and records the key id`() {
        val sealer = HkdfTokenSealer(ring("k1" to oldKey, active = "k1"))

        val token = sealer.seal("files.download-url", "files.download.v1|a|1|b|")

        token shouldStartWith "t1:k1:"
        token shouldNotContain "files.download"
        sealer.open("files.download-url", token) shouldBe "files.download.v1|a|1|b|"
    }

    @Test
    fun `a value encrypted by the field cipher with the token format is not a token`() {
        val keys = ring("k1" to oldKey, active = "k1")
        val sealer = HkdfTokenSealer(keys)
        // Texto escolhido por quem grava uma coluna cifrada, com o formato do payload do token.
        val column = AesGcmFieldCipher(keys).encrypt("files.download.v1|a|99999999999999|b|")

        shouldThrow<FieldDecryptionException> { sealer.open("files.download-url", column) }
        shouldThrow<FieldDecryptionException> { sealer.open("files.download-url", "t1" + column.removePrefix("v1")) }
        // E o inverso: um token não decifra como coluna.
        val token = sealer.seal("files.download-url", "x")
        shouldThrow<FieldDecryptionException> { AesGcmFieldCipher(keys).decrypt("v1" + token.removePrefix("t1")) }
    }

    @Test
    fun `a token of one purpose does not open as another`() {
        val sealer = HkdfTokenSealer(ring("k1" to oldKey, active = "k1"))

        val token = sealer.seal("files.download-url", "payload")

        shouldThrow<FieldDecryptionException> { sealer.open("other.purpose", token) }
    }

    @Test
    fun `tokens sealed with the previous key still open after rotation, and new ones use the active key`() {
        val before = HkdfTokenSealer(ring("k1" to oldKey, active = "k1")).seal("p", "antigo")

        val rotated = HkdfTokenSealer(ring("k1" to oldKey, "k2" to newKey, active = "k2"))

        rotated.open("p", before) shouldBe "antigo"
        rotated.seal("p", "novo") shouldStartWith "t1:k2:"
        // Sem a chave antiga configurada, o token antigo deixa de valer.
        shouldThrow<FieldDecryptionException> { HkdfTokenSealer(ring("k2" to newKey, active = "k2")).open("p", before) }
    }

    @Test
    fun `tampered, relabeled or malformed tokens fail`() {
        val sealer = HkdfTokenSealer(ring("k1" to oldKey, "k2" to oldKey, active = "k1"))
        val token = sealer.seal("p", "payload")

        val last = token.last()
        shouldThrow<FieldDecryptionException> { sealer.open("p", token.dropLast(1) + if (last == 'A') 'B' else 'A') }
        // Trocar o id da chave (mesmo material) falha: o cabeçalho é AAD e a chave derivada depende do id.
        shouldThrow<FieldDecryptionException> { sealer.open("p", token.replaceFirst("t1:k1:", "t1:k2:")) }
        listOf("", "t1:k1", "t1::abc", "t1:k1:!!!", "t1:k1:AAAA", "v1:k1:${token.substringAfterLast(':')}").forEach {
            shouldThrow<FieldDecryptionException> { sealer.open("p", it) }
        }
    }

    @Test
    fun `hkdf follows RFC 5869 test case 1`() {
        val hex = HexFormat.of()
        val okm =
            HkdfTokenSealer.hkdfSha256(
                inputKey = ByteArray(22) { 0x0b },
                info = hex.parseHex("f0f1f2f3f4f5f6f7f8f9"),
                salt = hex.parseHex("000102030405060708090a0b0c"),
            )

        hex.formatHex(okm) shouldBe "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
    }

    private fun randomKey() = ByteArray(EncryptionKeyRing.KEY_BYTES).also(SecureRandom()::nextBytes)
}
