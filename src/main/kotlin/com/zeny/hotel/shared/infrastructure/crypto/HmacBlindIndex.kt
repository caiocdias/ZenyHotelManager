package com.zeny.hotel.shared.infrastructure.crypto

import com.zeny.hotel.shared.BlindIndex
import com.zeny.hotel.shared.BlindIndexValue
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * [BlindIndex] com HMAC-SHA256 sobre chaves derivadas do [EncryptionKeyRing] por HKDF-SHA256 (RFC 5869),
 * como o [HkdfTokenSealer], mas com sal e prefixo de `info` próprios: a chave de um índice nunca é a de
 * uma coluna cifrada nem a de um token, e cada finalidade tem a sua. Sem estado nem segredo novo: a
 * rotação segue a do anel.
 */
class HmacBlindIndex(
    private val keyRing: EncryptionKeyRing,
) : BlindIndex {
    private val derived = ConcurrentHashMap<Pair<String, String>, SecretKey>()

    override val activeKeyId: String get() = keyRing.activeKeyId

    override fun compute(
        purpose: String,
        normalizedValue: String,
    ): BlindIndexValue = indexWith(keyRing.activeKeyId, purpose, normalizedValue)

    override fun candidates(
        purpose: String,
        normalizedValue: String,
    ): List<BlindIndexValue> =
        (listOf(keyRing.activeKeyId) + (keyRing.keyIds - keyRing.activeKeyId).sorted())
            .map { indexWith(it, purpose, normalizedValue) }

    private fun indexWith(
        keyId: String,
        purpose: String,
        normalizedValue: String,
    ): BlindIndexValue {
        require(purpose.isNotBlank()) { "Índice cego sem finalidade" }
        require(normalizedValue.isNotEmpty()) { "Índice cego de um valor vazio" }
        val mac = Mac.getInstance(HMAC).apply { init(keyFor(keyId, purpose)) }
        val hash = mac.doFinal(normalizedValue.toByteArray(Charsets.UTF_8))
        return BlindIndexValue(keyId, ENCODER.encodeToString(hash))
    }

    private fun keyFor(
        keyId: String,
        purpose: String,
    ): SecretKey =
        derived.computeIfAbsent(keyId to purpose) {
            val master = checkNotNull(keyRing.keyOrNull(keyId)) { "Chave '$keyId' não configurada" }
            val info = "$INFO_PREFIX$purpose".toByteArray(Charsets.UTF_8)
            SecretKeySpec(HkdfTokenSealer.hkdfSha256(master.encoded, info, SALT), HMAC)
        }

    /** Nunca mostra o material das chaves. */
    override fun toString(): String = "HmacBlindIndex(keyIds=${keyRing.keyIds}, activeKeyId=${keyRing.activeKeyId})"

    private companion object {
        const val HMAC = "HmacSHA256"
        const val INFO_PREFIX = "zeny.blind-index:"
        val SALT = "zeny-blind-index-v1".toByteArray(Charsets.UTF_8)
        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}
