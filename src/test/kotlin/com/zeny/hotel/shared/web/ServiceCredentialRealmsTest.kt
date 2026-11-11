package com.zeny.hotel.shared.web

import com.zeny.hotel.shared.ServiceCredentialRealm
import com.zeny.hotel.shared.ServicePrincipal
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * A partida recusa realms mal declarados (D-E10-1): prefixo fora de `/api/v1/` ou sem a barra final, nomes
 * repetidos e prefixos sobrepostos.
 */
class ServiceCredentialRealmsTest {
    @Test
    fun `distinct realms are accepted`() {
        ServiceCredentialRealms.validate(listOf(realm("a", "/api/v1/a/"), realm("b", "/api/v1/b/x/")))
        ServiceCredentialRealms.validate(emptyList())
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["/api/v1/a", "/api/v2/a/", "/api/v1/", "/other/a/", "api/v1/a/", "/api/v1/a/**", "/api/v1//a/"],
    )
    fun `a prefix outside api v1 or without the trailing slash is refused`(prefix: String) {
        shouldThrow<IllegalStateException> { ServiceCredentialRealms.validate(listOf(realm("a", prefix))) }
    }

    @Test
    fun `overlapping prefixes are refused`() {
        shouldThrow<IllegalStateException> {
            ServiceCredentialRealms.validate(listOf(realm("a", "/api/v1/a/"), realm("b", "/api/v1/a/b/")))
        }
        shouldThrow<IllegalStateException> {
            ServiceCredentialRealms.validate(listOf(realm("a", "/api/v1/a/"), realm("b", "/api/v1/a/")))
        }
    }

    @Test
    fun `duplicate names or schemes are refused`() {
        shouldThrow<IllegalStateException> {
            ServiceCredentialRealms.validate(listOf(realm("a", "/api/v1/a/"), realm("a", "/api/v1/b/")))
        }
        shouldThrow<IllegalStateException> {
            ServiceCredentialRealms.validate(
                listOf(realm("a", "/api/v1/a/", "same"), realm("b", "/api/v1/b/", "same")),
            )
        }
    }

    private fun realm(
        name: String,
        prefix: String,
        scheme: String = "${name}Token",
    ) = object : ServiceCredentialRealm {
        override val name = name
        override val pathPrefix = prefix
        override val openApiScheme = scheme

        override fun authenticate(token: String): ServicePrincipal? = null
    }
}
