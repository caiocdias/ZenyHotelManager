package com.zeny.hotel.shared.infrastructure.crypto

import com.zeny.hotel.shared.FieldCipher
import com.zeny.hotel.shared.FieldDecryptionException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * [FieldCipher] com AES-256-GCM (ARCHITECTURE §8.3).
 *
 * - Nonce de 96 bits sorteado do [SecureRandom] a cada valor, nunca reaproveitado nem
 *   derivado do texto. Com nonce aleatório, o NIST (SP 800-38D) limita uma chave a 2³²
 *   cifragens; muito acima do volume de um hotel, e a rotação reinicia a conta.
 * - Tag de 128 bits. O cabeçalho `v1:<id da chave>` entra como dado autenticado (AAD), então
 *   trocar o identificador de um valor gravado faz a decifragem falhar.
 */
class AesGcmFieldCipher(
    private val keyRing: EncryptionKeyRing,
    private val random: SecureRandom = SecureRandom(),
) : FieldCipher {
    override fun encrypt(plaintext: String): String {
        val keyId = keyRing.activeKeyId
        val header = header(keyId)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyRing.activeKey, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(header.toByteArray(Charsets.UTF_8))
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return "$header$SEPARATOR${ENCODER.encodeToString(nonce + sealed)}"
    }

    override fun decrypt(ciphertext: String): String {
        val (keyId, payload) = parse(ciphertext)
        val key =
            keyRing.keyOrNull(keyId)
                ?: throw FieldDecryptionException("Chave de cifragem '$keyId' não está configurada")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, payload, 0, NONCE_BYTES))
            cipher.updateAAD(header(keyId).toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(payload, NONCE_BYTES, payload.size - NONCE_BYTES), Charsets.UTF_8)
        } catch (error: GeneralSecurityException) {
            throw FieldDecryptionException(
                "Valor cifrado não confere com a chave '$keyId' (adulterado ou chave errada)",
                error,
            )
        }
    }

    override fun keyIdOf(ciphertext: String): String = parse(ciphertext).first

    override fun isEncryptedWithActiveKey(ciphertext: String): Boolean = keyIdOf(ciphertext) == keyRing.activeKeyId

    private fun parse(ciphertext: String): Pair<String, ByteArray> {
        val parts = ciphertext.split(SEPARATOR)
        if (parts.size != PARTS || parts[0] != VERSION || parts[1].isEmpty()) {
            throw FieldDecryptionException("Valor não está no formato de campo cifrado ($VERSION:<chave>:<dados>)")
        }
        // Nonce e tag são obrigatórios; o texto cifrado pode ser vazio.
        val payload =
            runCatching { DECODER.decode(parts[2]) }
                .getOrNull()
                ?.takeIf { it.size >= NONCE_BYTES + TAG_BITS / Byte.SIZE_BITS }
                ?: throw FieldDecryptionException("Valor cifrado com base64 inválido ou curto demais")
        return parts[1] to payload
    }

    private fun header(keyId: String) = "$VERSION$SEPARATOR$keyId"

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION = "v1"
        const val SEPARATOR = ":"
        const val PARTS = 3
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val DECODER: Base64.Decoder = Base64.getUrlDecoder()
    }
}
