package com.zeny.hotel.iam.application

import com.zeny.hotel.iam.domain.LockoutPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * Configuração da autenticação (`zeny.iam.*` no `application.yaml`, ARCHITECTURE §8.1). As
 * chaves de assinatura do JWT ficam à parte (`zeny.iam.jwt.*`, na infraestrutura).
 */
@ConfigurationProperties("zeny.iam")
class IamProperties(
    val accessToken: AccessToken,
    val refreshToken: RefreshToken,
    val login: Login,
    val passwordHashing: PasswordHashing,
    val initialAdmin: InitialAdmin,
) {
    /** Access token JWT: validade (15 min) e emissor (`iss`), conferido na validação. */
    class AccessToken(
        val ttl: Duration,
        val issuer: String,
    ) {
        init {
            require(!ttl.isNegative && !ttl.isZero) { "zeny.iam.access-token.ttl deve ser positivo" }
            require(issuer.isNotBlank()) { "zeny.iam.access-token.issuer é obrigatório" }
        }
    }

    /** Refresh token opaco: validade deslizante (30 dias depois do último uso). */
    class RefreshToken(
        val ttl: Duration,
    ) {
        init {
            require(!ttl.isNegative && !ttl.isZero) { "zeny.iam.refresh-token.ttl deve ser positivo" }
        }
    }

    /** Bloqueio progressivo do login ([LockoutPolicy]). */
    class Login(
        val maxFailures: Int,
        val lockoutBase: Duration,
        val lockoutMax: Duration,
    ) {
        val lockoutPolicy: LockoutPolicy get() = LockoutPolicy(maxFailures, lockoutBase, lockoutMax)

        init {
            lockoutPolicy // valida a combinação na partida
        }
    }

    /** Parâmetros do Argon2id para hashes novos (os antigos são conferidos com os parâmetros que guardam). */
    class PasswordHashing(
        val memoryKib: Int,
        val iterations: Int,
        val parallelism: Int,
    ) {
        init {
            require(parallelism >= 1 && iterations >= 1) { "Argon2: iterações e paralelismo devem ser positivos" }
            require(memoryKib >= MIN_MEMORY_PER_LANE_KIB * parallelism) { "Argon2: memória abaixo do mínimo" }
        }

        private companion object {
            const val MIN_MEMORY_PER_LANE_KIB = 8
        }
    }

    /**
     * Administrador inicial (H2): criado no primeiro boot, só quando não existe nenhum usuário,
     * a partir de `ZENY_ADMIN_LOGIN` e `ZENY_ADMIN_PASSWORD`, com troca de senha obrigatória.
     * Vazios depois do primeiro boot.
     */
    class InitialAdmin(
        val login: String = "",
        val password: String = "",
    ) {
        /** Nunca mostra a senha. */
        override fun toString(): String = "InitialAdmin(login=$login)"
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IamProperties::class)
class IamPropertiesConfiguration
