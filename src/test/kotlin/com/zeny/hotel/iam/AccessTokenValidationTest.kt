package com.zeny.hotel.iam

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.TestJwtKeys
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import java.security.KeyPair
import java.security.PrivateKey
import java.time.Clock
import java.time.Duration
import java.util.Date

/**
 * Validação do access token pelo Resource Server (H3): só RS256, só `kid` conhecido, assinatura,
 * emissor, validade e os claims `sub`/`sid`. Os tokens são forjados aqui com o Nimbus; o
 * endpoint de prova é `GET /api/v1/auth/sessions`, que responde 200 a qualquer token válido.
 * Os claims são conferidos também direto no [JwtDecoder], porque o endpoint de prova já recusa
 * um token sem ator válido pelo `CurrentActor` e esconderia um validador ausente.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class AccessTokenValidationTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val clock: Clock,
    @Autowired private val jwtDecoder: JwtDecoder,
) {
    @Test
    fun `a token signed by the active key is accepted`() {
        accepted(sign(TestJwtKeys.active, TestJwtKeys.ACTIVE_KEY_ID)) shouldBe true
    }

    @Test
    fun `a token signed by a previous key still configured is accepted (rotation by kid)`() {
        accepted(sign(TestJwtKeys.previous, TestJwtKeys.PREVIOUS_KEY_ID)) shouldBe true
    }

    @Test
    fun `tokens with a bad signature, unknown or missing kid, or another alg are rejected`() {
        val rogue = TestJwtKeys.generate()
        val valid = sign(TestJwtKeys.active, TestJwtKeys.ACTIVE_KEY_ID)
        val parts = valid.split('.')
        val tampered =
            listOf(
                parts[0],
                Base64URL.encode(claims().subject(UuidV7.generate().toString()).build().toString()),
                parts[2],
            ).joinToString(".")
        val hmacKey = ByteArray(HMAC_KEY_BYTES) { 7 }
        val cases =
            mapOf(
                "claims alterados" to tampered,
                "assinado por chave desconhecida com kid conhecido" to sign(rogue, TestJwtKeys.ACTIVE_KEY_ID),
                "kid desconhecido" to sign(rogue, "rogue"),
                "kid desconhecido com chave conhecida" to sign(TestJwtKeys.active, "rogue"),
                "sem kid" to sign(TestJwtKeys.active, null),
                "kid da chave anterior com a chave ativa" to sign(TestJwtKeys.active, TestJwtKeys.PREVIOUS_KEY_ID),
                "RS512" to sign(TestJwtKeys.active, TestJwtKeys.ACTIVE_KEY_ID, JWSAlgorithm.RS512),
                "PS256" to sign(TestJwtKeys.active, TestJwtKeys.ACTIVE_KEY_ID, JWSAlgorithm.PS256),
                "HS256" to
                    SignedJWT(
                        JWSHeader.Builder(JWSAlgorithm.HS256).keyID(TestJwtKeys.ACTIVE_KEY_ID).build(),
                        claims().build(),
                    ).apply { sign(MACSigner(hmacKey)) }
                        .serialize(),
                "alg none" to PlainJWT(claims().build()).serialize(),
            )

        assertSoftly {
            cases.forEach { (case, token) -> withClue(case) { accepted(token) shouldBe false } }
        }
    }

    @Test
    fun `tokens that are expired, not yet valid, from another issuer or without the session are rejected`() {
        val now = clock.instant()
        val cases =
            mapOf(
                "vencido" to claims().expirationTime(Date.from(now.minusSeconds(1))),
                "sem exp" to claims().expirationTime(null),
                "ainda não vale" to claims().notBeforeTime(Date.from(now.plusSeconds(60))),
                "outro emissor" to claims().issuer("outro-servidor"),
                "sem sid" to claims().claim("sid", null),
                "sid que não é UUID" to claims().claim("sid", "abc"),
                "sem sub" to claims().subject(null),
                "sub que não é UUID" to claims().subject("admin"),
            )

        assertSoftly {
            cases.forEach { (case, builder) ->
                withClue(case) {
                    val token = sign(TestJwtKeys.active, TestJwtKeys.ACTIVE_KEY_ID, claims = builder.build())
                    accepted(token) shouldBe false
                    shouldThrow<JwtException> { jwtDecoder.decode(token) }
                }
            }
        }
    }

    @Test
    fun `the decoder itself accepts a valid token`() {
        val claims = claims().build()

        jwtDecoder.decode(sign(TestJwtKeys.active, TestJwtKeys.ACTIVE_KEY_ID, claims = claims)).subject shouldBe
            claims.subject
    }

    @Test
    fun `a token with a pending password change only reaches the password change and the logout`() {
        val token =
            sign(TestJwtKeys.active, TestJwtKeys.ACTIVE_KEY_ID, claims = claims().claim("pwd_chg", true).build())

        with(iam) {
            sessions(token).problem(403, "PASSWORD_CHANGE_REQUIRED")
            postWithToken(
                "/api/v1/auth/sessions/${UuidV7.generate()}/revoke",
                token,
            ).problem(403, "PASSWORD_CHANGE_REQUIRED")
            // A troca de senha passa pela segurança e chega à validação do corpo.
            postWithToken("/api/v1/auth/password", token, mapOf("currentPassword" to "", "newPassword" to ""))
                .problem(400, "VALIDATION_FAILED")
            postWithToken("/api/v1/auth/logout", token).status shouldBe 204
        }
    }

    private fun accepted(token: String): Boolean {
        val response = iam.sessions(token)
        if (response.status == 401) {
            iam.json(response)["code"].asString() shouldBe "UNAUTHENTICATED"
        }
        return response.status == 200
    }

    private fun claims(): JWTClaimsSet.Builder {
        val now = clock.instant()
        return JWTClaimsSet
            .Builder()
            .issuer("zeny-hotel-manager")
            .subject(UuidV7.generate().toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(Duration.ofMinutes(15))))
            .jwtID(UuidV7.generate().toString())
            .claim("sid", UuidV7.generate().toString())
    }

    private fun sign(
        keys: KeyPair,
        keyId: String?,
        algorithm: JWSAlgorithm = JWSAlgorithm.RS256,
        claims: JWTClaimsSet = claims().build(),
    ): String = sign(keys.private, keyId, algorithm, claims)

    private fun sign(
        key: PrivateKey,
        keyId: String?,
        algorithm: JWSAlgorithm,
        claims: JWTClaimsSet,
    ): String {
        val header =
            JWSHeader
                .Builder(algorithm)
                .type(JOSEObjectType.JWT)
                .apply {
                    if (keyId !=
                        null
                    ) {
                        keyID(keyId)
                    }
                }.build()
        return SignedJWT(header, claims).apply { sign(RSASSASigner(key)) }.serialize()
    }

    private companion object {
        const val HMAC_KEY_BYTES = 32
    }
}
