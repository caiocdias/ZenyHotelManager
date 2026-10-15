package com.zeny.hotel.shared.infrastructure.crypto

import com.zeny.hotel.shared.FieldDecryptionException
import com.zeny.hotel.shared.TokenSealer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * [TokenSealer] com AES-256-GCM sobre chaves derivadas do [EncryptionKeyRing] por HKDF-SHA256
 * (RFC 5869): sal fixo do domínio dos tokens e `info` = finalidade, uma chave por (id da chave,
 * finalidade). O cabeçalho `t1:<id da chave>` e a finalidade entram como AAD. A chave das colunas
 * cifradas nunca sela um token, e a derivada nunca cifra uma coluna.
 */
class HkdfTokenSealer(
    private val keyRing: EncryptionKeyRing,
    private val random: SecureRandom = SecureRandom(),
) : TokenSealer {
    private val derived = ConcurrentHashMap<Pair<String, String>, SecretKey>()

    override fun seal(
        purpose: String,
        payload: String,
    ): String {
        val keyId = keyRing.activeKeyId
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyFor(keyId, purpose), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData(keyId, purpose))
        val sealed = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        return "$VERSION$SEPARATOR$keyId$SEPARATOR${ENCODER.encodeToString(nonce + sealed)}"
    }

    override fun open(
        purpose: String,
        token: String,
    ): String {
        val parts = token.split(SEPARATOR)
        val keyId = parts.getOrNull(1).orEmpty()
        val payload =
            parts
                .takeIf {
                    it.size == PARTS && it[0] == VERSION && keyId.isNotEmpty() && keyRing.keyOrNull(
                        keyId,
                    ) != null
                }?.let { runCatching { DECODER.decode(it[2]) }.getOrNull() }
                ?.takeIf { it.size >= NONCE_BYTES + TAG_BITS / Byte.SIZE_BITS }
                ?: throw FieldDecryptionException(
                    "Token fora do formato $VERSION:<chave>:<dados>, curto demais ou com uma chave não configurada",
                )
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keyFor(keyId, purpose),
                GCMParameterSpec(TAG_BITS, payload, 0, NONCE_BYTES),
            )
            cipher.updateAAD(associatedData(keyId, purpose))
            String(cipher.doFinal(payload, NONCE_BYTES, payload.size - NONCE_BYTES), Charsets.UTF_8)
        } catch (error: GeneralSecurityException) {
            throw FieldDecryptionException("Token não confere com a chave '$keyId' e a finalidade", error)
        }
    }

    private fun keyFor(
        keyId: String,
        purpose: String,
    ): SecretKey =
        derived.computeIfAbsent(keyId to purpose) {
            val master = checkNotNull(keyRing.keyOrNull(keyId)) { "Chave '$keyId' não configurada" }
            SecretKeySpec(hkdfSha256(master.encoded, "$INFO_PREFIX$purpose".toByteArray(Charsets.UTF_8)), "AES")
        }

    private fun associatedData(
        keyId: String,
        purpose: String,
    ) = "$VERSION$SEPARATOR$keyId$SEPARATOR$purpose".toByteArray(Charsets.UTF_8)

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION = "t1"
        private const val SEPARATOR = ":"
        private const val PARTS = 3
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val HMAC = "HmacSHA256"
        private const val INFO_PREFIX = "zeny.token:"
        private val SALT = "zeny-token-sealer-v1".toByteArray(Charsets.UTF_8)
        private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        private val DECODER: Base64.Decoder = Base64.getUrlDecoder()

        /** HKDF-SHA256 (RFC 5869) com saída de 32 bytes (um bloco do expand). */
        internal fun hkdfSha256(
            inputKey: ByteArray,
            info: ByteArray,
            salt: ByteArray = SALT,
        ): ByteArray {
            val extract = Mac.getInstance(HMAC).apply { init(SecretKeySpec(salt, HMAC)) }
            val pseudoRandomKey = extract.doFinal(inputKey)
            val expand = Mac.getInstance(HMAC).apply { init(SecretKeySpec(pseudoRandomKey, HMAC)) }
            expand.update(info)
            expand.update(1.toByte())
            return expand.doFinal()
        }
    }
}
