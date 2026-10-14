package com.zeny.hotel.iam.infrastructure

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.JWSKeySelector
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import com.zeny.hotel.iam.application.AccessTokenIssuer
import com.zeny.hotel.iam.application.IamProperties
import com.zeny.hotel.iam.application.IssuedAccessToken
import com.zeny.hotel.iam.domain.DeviceSession
import com.zeny.hotel.iam.domain.User
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.PASSWORD_CHANGE_PENDING_AUTHORITY
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Claims do access token. Além dos padrão (`iss`, `sub` = id do usuário, `iat`, `exp`, `jti`),
 * só o id da sessão e a pendência de troca de senha. Nunca o login, o nome nem as permissões
 * (ARCHITECTURE §8.2: elas ficam fora do token para que a mudança valha na hora).
 */
object AccessTokenClaims {
    /** Id da sessão de dispositivo (`DeviceSessionId`). */
    const val SESSION_ID = "sid"

    /** `true` enquanto o usuário precisa trocar a senha; ausente no resto do tempo. */
    const val PASSWORD_CHANGE_PENDING = "pwd_chg"
}

/**
 * Emissão e validação do access token (ARCHITECTURE §8.1, H3): JWT assinado em RS256 com `kid`,
 * pelas chaves de [JwtKeyRing]. A validação aceita só RS256 (nem `none`, nem HMAC, nem outro
 * algoritmo assimétrico) e só um `kid` conhecido; confere assinatura, emissor, `exp` e `nbf`
 * pelo relógio da aplicação, sem tolerância (quem emite e quem valida é o mesmo servidor), e
 * exige `sub` e `sid` em UUID.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtKeyProperties::class)
class AccessTokenConfiguration {
    @Bean
    fun jwtKeyRing(properties: JwtKeyProperties): JwtKeyRing = JwtKeyRing.parse(properties.keys, properties.activeKeyId)

    @Bean
    fun jwtEncoder(keyRing: JwtKeyRing): JwtEncoder =
        NimbusJwtEncoder(ImmutableJWKSet(JWKSet(rsaJwk(keyRing.activeKey, includePrivate = true))))

    @Bean
    fun jwtDecoder(
        keyRing: JwtKeyRing,
        properties: IamProperties,
        clock: Clock,
    ): JwtDecoder {
        val verificationKeys = ImmutableJWKSet<SecurityContext>(JWKSet(keyRing.keys.map { rsaJwk(it, false) }))
        val rs256Only = JWSVerificationKeySelector(JWSAlgorithm.RS256, verificationKeys)
        val processor =
            DefaultJWTProcessor<SecurityContext>().apply {
                // Sem `kid`, nenhuma chave: o token é recusado em vez de ser testado contra todas.
                jwsKeySelector =
                    JWSKeySelector { header, context ->
                        if (header.keyID.isNullOrBlank()) emptyList() else rs256Only.selectJWSKeys(header, context)
                    }
                // Datas e claims ficam com os validadores do Spring abaixo, que usam o relógio injetado.
                setJWTClaimsSetVerifier { _, _ -> }
            }
        return NimbusJwtDecoder(processor).apply { setJwtValidator(validator(properties, clock)) }
    }

    /** Autoridades do token: só a da troca de senha pendente; permissões não vêm do token. */
    @Bean
    fun jwtAuthenticationConverter(): JwtAuthenticationConverter =
        JwtAuthenticationConverter().apply {
            setJwtGrantedAuthoritiesConverter(
                Converter<Jwt, Collection<GrantedAuthority>> { jwt ->
                    if (jwt.getClaimAsBoolean(AccessTokenClaims.PASSWORD_CHANGE_PENDING) == true) {
                        listOf(SimpleGrantedAuthority(PASSWORD_CHANGE_PENDING_AUTHORITY))
                    } else {
                        emptyList()
                    }
                },
            )
        }

    private fun validator(
        properties: IamProperties,
        clock: Clock,
    ): OAuth2TokenValidator<Jwt> =
        DelegatingOAuth2TokenValidator(
            listOf(
                JwtTimestampValidator(Duration.ZERO).apply { setClock(clock) },
                requiredClaim(JwtClaimNames.EXP) { it is Instant },
                JwtIssuerValidator(properties.accessToken.issuer),
                requiredClaim(JwtClaimNames.SUB, ::isUuid),
                requiredClaim(AccessTokenClaims.SESSION_ID, ::isUuid),
            ),
        )

    private fun rsaJwk(
        key: JwtSigningKey,
        includePrivate: Boolean,
    ): RSAKey =
        RSAKey
            .Builder(key.publicKey)
            .keyID(key.keyId)
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .apply { if (includePrivate) privateKey(key.privateKey) }
            .build()

    /** Claim obrigatório e válido; o token que falha é recusado com 401, sem dizer o motivo ao cliente. */
    private fun requiredClaim(
        name: String,
        valid: (Any?) -> Boolean,
    ) = OAuth2TokenValidator<Jwt> { jwt ->
        if (valid(jwt.claims[name])) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(
                OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "Claim $name inválido", null),
            )
        }
    }

    private fun isUuid(value: Any?): Boolean = value is String && parseUuid(value) != null
}

/** Emite o access token assinado pela chave ativa. */
@Component
class JwtAccessTokenIssuer(
    private val encoder: JwtEncoder,
    private val keyRing: JwtKeyRing,
    private val properties: IamProperties,
) : AccessTokenIssuer {
    override fun issue(
        user: User,
        session: DeviceSession,
        now: Instant,
    ): IssuedAccessToken {
        // `iat` e `exp` têm resolução de segundos no JWT; a resposta devolve o mesmo instante.
        val issuedAt = now.truncatedTo(ChronoUnit.SECONDS)
        val expiresAt = issuedAt.plus(properties.accessToken.ttl)
        val claims =
            JwtClaimsSet
                .builder()
                .issuer(properties.accessToken.issuer)
                .subject(user.id.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UuidV7.generate().toString())
                .claim(AccessTokenClaims.SESSION_ID, session.id.toString())
                .apply { if (user.passwordChangeRequired) claim(AccessTokenClaims.PASSWORD_CHANGE_PENDING, true) }
                .build()
        val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyRing.activeKey.keyId).build()
        val token = encoder.encode(JwtEncoderParameters.from(header, claims))
        return IssuedAccessToken(token.tokenValue, expiresAt)
    }
}

/** O ator corrente a partir do access token validado pelo Resource Server (`sub` e `sid`). */
@Component
class JwtCurrentActor : CurrentActor {
    override fun currentOrNull(): Actor? {
        val token = (SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken)?.token
        val userId = token?.subject?.let(::parseUuid)
        val sessionId = token?.getClaimAsString(AccessTokenClaims.SESSION_ID)?.let(::parseUuid)
        return if (userId != null && sessionId != null) Actor(UserId(userId), DeviceSessionId(sessionId)) else null
    }
}

/** UUID na forma canônica, ou `null`. */
private fun parseUuid(value: String): UUID? =
    try {
        UUID.fromString(value).takeIf { it.toString() == value.lowercase() }
    } catch (_: IllegalArgumentException) {
        null
    }
