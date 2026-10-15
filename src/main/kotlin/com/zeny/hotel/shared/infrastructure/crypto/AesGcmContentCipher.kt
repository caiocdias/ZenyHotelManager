package com.zeny.hotel.shared.infrastructure.crypto

import com.zeny.hotel.shared.ContentCipher
import com.zeny.hotel.shared.FieldDecryptionException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * [ContentCipher] com AES-256-GCM sobre o mesmo [EncryptionKeyRing] da cifragem de campo
 * (ARCHITECTURE §8.3): nonce de 96 bits sorteado a cada conteúdo, tag de 128 bits e o cabeçalho
 * (versão e id da chave) mais o dado associado de quem chama como AAD.
 */
class AesGcmContentCipher(
    private val keyRing: EncryptionKeyRing,
    private val random: SecureRandom = SecureRandom(),
) : ContentCipher {
    override val activeKeyId: String get() = keyRing.activeKeyId

    override fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val keyId = keyRing.activeKeyId
        val header = header(keyId)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyRing.activeKey, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(header)
        cipher.updateAAD(associatedData)
        val sealed = cipher.doFinal(plaintext)
        return ByteBuffer
            .allocate(header.size + NONCE_BYTES + sealed.size)
            .put(header)
            .put(nonce)
            .put(sealed)
            .array()
    }

    override fun decrypt(
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val keyId = keyIdOf(ciphertext)
        val header = header(keyId)
        val payloadStart = header.size
        val key =
            keyRing
                .keyOrNull(keyId)
                ?.takeIf { ciphertext.size >= payloadStart + NONCE_BYTES + TAG_BITS / Byte.SIZE_BITS }
                ?: throw FieldDecryptionException(
                    "Conteúdo cifrado curto demais ou com a chave '$keyId', que não está configurada",
                )
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, ciphertext, payloadStart, NONCE_BYTES))
            cipher.updateAAD(header)
            cipher.updateAAD(associatedData)
            cipher.doFinal(ciphertext, payloadStart + NONCE_BYTES, ciphertext.size - payloadStart - NONCE_BYTES)
        } catch (error: GeneralSecurityException) {
            throw FieldDecryptionException(
                "Conteúdo cifrado não confere com a chave '$keyId' (adulterado, chave ou dado associado errados)",
                error,
            )
        }
    }

    override fun keyIdOf(ciphertext: ByteArray): String {
        if (ciphertext.size <= MAGIC.size || !ciphertext.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw FieldDecryptionException("Conteúdo não está no formato de conteúdo cifrado (ZNC1)")
        }
        val length = ciphertext[MAGIC.size].toInt() and BYTE_MASK
        val start = MAGIC.size + 1
        if (length == 0 || ciphertext.size < start + length) {
            throw FieldDecryptionException("Conteúdo cifrado com identificador de chave inválido")
        }
        return String(ciphertext, start, length, Charsets.US_ASCII)
    }

    private fun header(keyId: String): ByteArray {
        val id = keyId.toByteArray(Charsets.US_ASCII)
        return MAGIC + byteArrayOf(id.size.toByte()) + id
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val BYTE_MASK = 0xFF
        val MAGIC = byteArrayOf('Z'.code.toByte(), 'N'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())
    }
}
