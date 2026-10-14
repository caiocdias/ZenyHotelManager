package com.zeny.hotel.iam

import com.zeny.hotel.ZenyHotelApplication
import com.zeny.hotel.iam.infrastructure.JwtKeyProperties
import com.zeny.hotel.iam.infrastructure.JwtKeyRing
import com.zeny.hotel.testsupport.PostgresInitializer
import com.zeny.hotel.testsupport.TEST_MIGRATIONS
import com.zeny.hotel.testsupport.TestEncryptionKeysInitializer
import com.zeny.hotel.testsupport.TestJwtKeys
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * Chaves de assinatura do access token (H3): sem valor padrão (sem elas a aplicação não sobe),
 * só chaves privadas RSA PKCS#8 de 2048 bits ou mais, com `kid` válido e a ativa entre as
 * configuradas. As mensagens de erro citam o `kid`, nunca o material da chave.
 */
class JwtKeysTest {
    private val activeBase64 = Base64.getEncoder().encodeToString(TestJwtKeys.active.private.encoded)

    @Test
    fun `parses several keys and picks the active one by kid`() {
        val ring = JwtKeyRing.parse(TestJwtKeys.keys, TestJwtKeys.PREVIOUS_KEY_ID)

        ring.keys.map { it.keyId } shouldBe listOf(TestJwtKeys.ACTIVE_KEY_ID, TestJwtKeys.PREVIOUS_KEY_ID)
        ring.activeKey.keyId shouldBe TestJwtKeys.PREVIOUS_KEY_ID
        ring.activeKey.publicKey shouldBe TestJwtKeys.previous.public
        ring.toString() shouldNotContain activeBase64.take(SAMPLE)
    }

    @Test
    fun `missing variables are refused with the variable name`() {
        shouldThrow<IllegalArgumentException> { JwtKeyProperties("\${ZENY_JWT_KEYS}", "dev") }.message!! shouldContain
            "ZENY_JWT_KEYS"
        shouldThrow<IllegalArgumentException> { JwtKeyProperties("", "dev") }.message!! shouldContain "ZENY_JWT_KEYS"
        shouldThrow<IllegalArgumentException> { JwtKeyProperties("dev:x", "\${ZENY_JWT_ACTIVE_KEY_ID}") }
            .message!! shouldContain "ZENY_JWT_ACTIVE_KEY_ID"
    }

    @Test
    fun `invalid keys are refused without showing the key`() {
        val pkcs1 = pkcs1Of(activeBase64)
        val small =
            Base64.getEncoder().encodeToString(
                KeyPairGenerator
                    .getInstance(
                        "RSA",
                    ).apply { initialize(SMALL_KEY_BITS) }
                    .generateKeyPair()
                    .private.encoded,
            )
        val cases =
            mapOf(
                "dev:não-é-base64" to "dev",
                "dev:${Base64.getEncoder().encodeToString("lixo".toByteArray())}" to "dev",
                "dev:$pkcs1" to "dev",
                "dev:$small" to "dev",
                "dev:$activeBase64" to "outra",
                "dev:$activeBase64,dev:$activeBase64" to "dev",
                "kid com espaço:$activeBase64" to "kid com espaço",
                activeBase64 to "dev",
            )

        cases.forEach { (keys, active) ->
            val error = shouldThrow<IllegalArgumentException> { JwtKeyRing.parse(keys, active) }
            error.message!! shouldNotContain activeBase64.take(SAMPLE)
        }
    }

    @Test
    fun `the application does not start without the signing key`() {
        assumeTrue(System.getenv("ZENY_JWT_KEYS") == null, "ZENY_JWT_KEYS definida no ambiente do teste")

        val error =
            shouldThrow<Exception> {
                SpringApplicationBuilder(ZenyHotelApplication::class.java)
                    .profiles("local")
                    .initializers(PostgresInitializer(), TestEncryptionKeysInitializer())
                    .run("--$TEST_MIGRATIONS", "--server.port=0")
                    .close()
            }

        generateSequence<Throwable>(error) { it.cause }.joinToString(" | ") { it.message.orEmpty() } shouldContain
            "ZENY_JWT_KEYS"
    }

    /** A mesma chave em PKCS#1 (o que o `openssl genpkey -outform DER` grava): o `PrivateKeyInfo` sem o envelope. */
    private fun pkcs1Of(pkcs8Base64: String): String {
        val der = Base64.getDecoder().decode(pkcs8Base64)
        // PKCS#8 = SEQUENCE { versão, AlgorithmIdentifier(rsaEncryption), OCTET STRING { RSAPrivateKey } }.
        val octetString = der.indexOfSequence(byteArrayOf(0x04, 0x82.toByte())) // OCTET STRING com tamanho em 2 bytes
        return Base64.getEncoder().encodeToString(der.copyOfRange(octetString + 4, der.size))
    }

    private fun ByteArray.indexOfSequence(sequence: ByteArray): Int =
        (0..size - sequence.size).first { start -> sequence.indices.all { this[start + it] == sequence[it] } }

    private companion object {
        const val SAMPLE = 40
        const val SMALL_KEY_BITS = 1024
    }
}
