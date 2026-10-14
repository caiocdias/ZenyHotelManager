package com.zeny.hotel.testsupport

import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import java.security.SecureRandom
import java.util.Base64

/**
 * Chave de cifragem dos testes de integração: sorteada uma vez por JVM, nunca escrita no
 * código (ARCHITECTURE §8.3). Todos os contextos da JVM usam a mesma, para que um segundo
 * contexto sobre o mesmo banco decifre o que o primeiro gravou.
 */
object TestEncryptionKeys {
    const val ACTIVE_KEY_ID = "test"

    val keys: String by lazy {
        val key = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        "$ACTIVE_KEY_ID:${Base64.getEncoder().encodeToString(key)}"
    }

    private const val KEY_BYTES = 32
}

/** Entrega a chave dos testes ao contexto, acima do `application.yaml`. */
class TestEncryptionKeysInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(context: ConfigurableApplicationContext) {
        TestPropertyValues
            .of(
                "zeny.encryption.keys=${TestEncryptionKeys.keys}",
                "zeny.encryption.active-key-id=${TestEncryptionKeys.ACTIVE_KEY_ID}",
            ).applyTo(context)
    }
}
