package com.zeny.hotel.iam.application

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

/**
 * Refresh tokens opacos: 256 bits aleatórios em base64url. No banco fica só o SHA-256 em
 * hexadecimal; com essa entropia, um hash rápido basta (não há dicionário a atacar).
 */
internal object RefreshTokens {
    private const val TOKEN_BYTES = 32
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun generate(): String = encoder.encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))

    fun hash(token: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))
}
