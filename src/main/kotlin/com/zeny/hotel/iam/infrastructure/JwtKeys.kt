package com.zeny.hotel.iam.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * Chaves de assinatura do access token, sem valor padrão em nenhum profile: no
 * `application.yaml`, `zeny.iam.jwt.keys` vem de `ZENY_JWT_KEYS` (`kid:base64[,kid:base64…]`,
 * cada uma uma chave privada RSA em PKCS#8 DER, em base64) e `zeny.iam.jwt.active-key-id` de
 * `ZENY_JWT_ACTIVE_KEY_ID`. Sem elas a aplicação não sobe.
 */
@ConfigurationProperties("zeny.iam.jwt")
class JwtKeyProperties(
    val keys: String,
    val activeKeyId: String,
) {
    init {
        // Variável ausente chega aqui como o próprio texto `${...}` do application.yaml.
        require(keys.isNotBlank() && !keys.contains("\${")) {
            "Access token sem chave de assinatura: defina ZENY_JWT_KEYS (kid:base64 de uma chave privada RSA PKCS#8)"
        }
        require(activeKeyId.isNotBlank() && !activeKeyId.contains("\${")) {
            "Access token sem chave ativa: defina ZENY_JWT_ACTIVE_KEY_ID"
        }
    }

    /** Nunca mostra as chaves. */
    override fun toString(): String = "JwtKeyProperties(activeKeyId=$activeKeyId)"
}

/** Um par de chaves RSA com o seu identificador (`kid`). */
class JwtSigningKey(
    val keyId: String,
    val privateKey: RSAPrivateCrtKey,
    val publicKey: RSAPublicKey,
) {
    override fun toString(): String = "JwtSigningKey(keyId=$keyId)"
}

/**
 * As chaves RSA do access token (H3): todas valem na validação, pelo `kid` do cabeçalho, e só a
 * ativa assina. Rotação: acrescente a nova à lista, aponte a ativa para ela e retire a antiga
 * depois de 15 min (a validade do access token).
 */
class JwtKeyRing private constructor(
    val keys: List<JwtSigningKey>,
    val activeKey: JwtSigningKey,
) {
    override fun toString(): String = "JwtKeyRing(keyIds=${keys.map { it.keyId }}, activeKeyId=${activeKey.keyId})"

    companion object {
        /** RSA abaixo disso não é aceito (NIST SP 800-131A). */
        const val MIN_KEY_BITS = 2048
        private val KEY_ID = Regex("[A-Za-z0-9._-]{1,64}")

        /** Lê `kid:base64,kid:base64,…`. As mensagens de erro citam o `kid`, nunca a chave. */
        fun parse(
            keys: String,
            activeKeyId: String,
        ): JwtKeyRing {
            val entries = keys.split(',').map(String::trim).filter(String::isNotEmpty)
            require(entries.isNotEmpty()) { "Nenhuma chave de assinatura configurada (ZENY_JWT_KEYS)" }
            val parsed =
                entries.mapIndexed { index, entry ->
                    val separator = entry.indexOf(':')
                    require(separator > 0) { "Chave de assinatura fora do formato kid:base64 (entrada ${index + 1})" }
                    val keyId = entry.substring(0, separator).trim()
                    require(KEY_ID.matches(keyId)) { "Identificador de chave de assinatura inválido: '$keyId'" }
                    rsaKey(keyId, entry.substring(separator + 1).trim())
                }
            val duplicated = parsed.groupBy { it.keyId }.filterValues { it.size > 1 }.keys
            require(duplicated.isEmpty()) { "Chave de assinatura repetida: $duplicated" }
            val active = parsed.firstOrNull { it.keyId == activeKeyId.trim() }
            requireNotNull(active) { "A chave de assinatura ativa '$activeKeyId' não está entre as configuradas" }
            return JwtKeyRing(parsed, active)
        }

        private fun rsaKey(
            keyId: String,
            base64: String,
        ): JwtSigningKey {
            val privateKey =
                try {
                    val der = Base64.getMimeDecoder().decode(base64)
                    KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)) as? RSAPrivateCrtKey
                } catch (_: IllegalArgumentException) {
                    null
                } catch (_: InvalidKeySpecException) {
                    null
                }
            requireNotNull(privateKey) {
                "A chave de assinatura '$keyId' não é uma chave privada RSA PKCS#8 (DER) em base64; gere com " +
                    "openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 | openssl pkcs8 -topk8 -nocrypt " +
                    "-outform DER | base64"
            }
            require(privateKey.modulus.bitLength() >= MIN_KEY_BITS) {
                "A chave de assinatura '$keyId' precisa ter pelo menos $MIN_KEY_BITS bits"
            }
            val publicKey =
                KeyFactory
                    .getInstance("RSA")
                    .generatePublic(RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)) as RSAPublicKey
            return JwtSigningKey(keyId, privateKey, publicKey)
        }
    }
}
