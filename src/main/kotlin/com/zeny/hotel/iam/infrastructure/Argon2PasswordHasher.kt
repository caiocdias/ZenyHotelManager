package com.zeny.hotel.iam.infrastructure

import com.zeny.hotel.iam.application.IamProperties
import com.zeny.hotel.iam.application.PasswordHasher
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Component

/**
 * Argon2id (ARCHITECTURE §8.1) pelo `Argon2PasswordEncoder` do Spring Security, com sal
 * aleatório de 16 bytes e hash de 32 bytes. O resultado é o formato PHC
 * (`$argon2id$v=19$m=…,t=…,p=…$sal$hash`), que guarda os parâmetros: hashes antigos continuam
 * conferindo depois de mudar os parâmetros, e o login refaz os mais fracos ([needsRehash]).
 */
@Component
class Argon2PasswordHasher(
    properties: IamProperties,
) : PasswordHasher {
    private val encoder =
        properties.passwordHashing.let {
            Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, it.parallelism, it.memoryKib, it.iterations)
        }

    override fun hash(raw: CharSequence): String = checkNotNull(encoder.encode(raw)) { "Argon2 sem resultado" }

    override fun matches(
        raw: CharSequence,
        hash: String,
    ): Boolean = encoder.matches(raw, hash)

    override fun needsRehash(hash: String): Boolean = encoder.upgradeEncoding(hash)

    private companion object {
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32
    }
}
