package com.zeny.hotel.testsupport

import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import java.security.SecureRandom
import java.util.Base64

/**
 * Chaves de cifragem dos testes de integração: sorteadas uma vez por JVM, nunca escritas no
 * código (ARCHITECTURE §8.3). Todos os contextos da JVM usam as mesmas, para que um segundo
 * contexto sobre o mesmo banco decifre o que o primeiro gravou. O anel tem duas chaves, como
 * depois de uma rotação: a ativa ([ACTIVE_KEY_ID]) e a anterior ([PREVIOUS_KEY_ID]), que ainda
 * decifra e ainda encontra os índices cegos calculados com ela (M3 E01, H8).
 */
object TestEncryptionKeys {
    const val ACTIVE_KEY_ID = "test"
    const val PREVIOUS_KEY_ID = "previous"

    val keys: String by lazy {
        val encoder = Base64.getEncoder()
        val previous = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        val active = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        "$PREVIOUS_KEY_ID:${encoder.encodeToString(previous)},$ACTIVE_KEY_ID:${encoder.encodeToString(active)}"
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
