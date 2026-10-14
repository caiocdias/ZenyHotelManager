package com.zeny.hotel.testsupport

import com.zeny.hotel.iam.infrastructure.AccessTokenClaims
import com.zeny.hotel.shared.UuidV7
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Chaves RSA de assinatura do access token nos testes: sorteadas uma vez por JVM, nunca escritas
 * no código (ARCHITECTURE §8.1). Há duas: a ativa, que assina, e uma anterior, ainda aceita na
 * validação (rotação por `kid`).
 */
object TestJwtKeys {
    const val ACTIVE_KEY_ID = "test"
    const val PREVIOUS_KEY_ID = "test-previous"

    val active: KeyPair by lazy(::generate)
    val previous: KeyPair by lazy(::generate)

    /** O valor de `ZENY_JWT_KEYS`: `kid:base64(PKCS#8)` das duas chaves. */
    val keys: String by lazy {
        listOf(ACTIVE_KEY_ID to active, PREVIOUS_KEY_ID to previous).joinToString(",") { (kid, pair) ->
            "$kid:${Base64.getEncoder().encodeToString(pair.private.encoded)}"
        }
    }

    /** Um par RSA novo, fora da configuração (para tokens forjados). */
    fun generate(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()

    private const val KEY_BITS = 2048
}

/** O administrador inicial dos testes (H2): login fixo e senha sorteada por JVM. */
object TestAdmin {
    const val LOGIN = "admin"

    val password: String by lazy {
        "Adm-" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteArray(PASSWORD_BYTES).also(SecureRandom()::nextBytes),
            )
    }

    private const val PASSWORD_BYTES = 18
}

/**
 * Entrega as chaves JWT e o administrador inicial dos testes ao contexto, acima do
 * `application.yaml`, e baixa o custo do Argon2id (mínimo de memória, uma iteração) para os
 * logins dos testes não pesarem; o padrão de produção (m=19 MiB, t=2) fica no `application.yaml`.
 */
class TestAuthInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(context: ConfigurableApplicationContext) {
        TestPropertyValues
            .of(
                "zeny.iam.jwt.keys=${TestJwtKeys.keys}",
                "zeny.iam.jwt.active-key-id=${TestJwtKeys.ACTIVE_KEY_ID}",
                "zeny.iam.initial-admin.login=${TestAdmin.LOGIN}",
                "zeny.iam.initial-admin.password=${TestAdmin.password}",
                "zeny.iam.password-hashing.memory-kib=1024",
                "zeny.iam.password-hashing.iterations=1",
            ).applyTo(context)
    }
}

/**
 * Autentica a requisição do `MockMvc` como um usuário com id, numa sessão de dispositivo: um
 * access token já validado com `sub` e `sid`, como o Resource Server entrega à aplicação. Não
 * precisa de usuário no banco; para o fluxo real (login, refresh), use a API de `iam`.
 */
fun asUser(
    userId: UUID = UuidV7.generate(),
    sessionId: UUID = UuidV7.generate(),
): RequestPostProcessor =
    jwt().jwt { it.subject(userId.toString()).claim(AccessTokenClaims.SESSION_ID, sessionId.toString()) }
