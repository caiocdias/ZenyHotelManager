package com.zeny.hotel.testsupport

import com.zeny.hotel.fnrh.infrastructure.FnrhProperties
import com.zeny.hotel.iam.IamTestSupport
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.simple.JdbcClient
import java.net.URI

/**
 * Contexto dos testes do `fnrh` que falam com o Serpro: as duas URLs apontam para o [FnrhTestServer], num bean
 * `@Primary` para todas as classes que o importam compartilharem o mesmo contexto.
 */
@TestConfiguration(proxyBeanMethods = false)
class FnrhTestConfiguration {
    @Bean
    @Primary
    fun testFnrhProperties(): FnrhProperties {
        val url = URI.create(FnrhTestServer.baseUrl)
        return FnrhProperties(homologationUrl = url, productionUrl = url)
    }
}

/** Liga a integração (pela API, como o administrador) com o usuário e a senha do [FnrhTestServer]. */
fun IamTestSupport.enableFnrh(environment: String = "HOMOLOGATION") {
    val path = "/api/v1/fnrh/settings"
    val read = call(HttpMethod.GET, path, asAdmin())
    val etag = read.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
    val body =
        mapOf(
            "environment" to environment,
            "username" to FnrhTestServer.USERNAME,
            "password" to FnrhTestServer.PASSWORD,
            "defaultRequesterCpf" to "529.982.247-25",
            "enabled" to true,
        )
    call(HttpMethod.PUT, path, asAdmin(), body, ifMatch = etag).status shouldBe 200
}

/** Devolve a linha única da configuração da FNRH ao estado inicial (desligada, em homologação, sem segredos). */
fun JdbcClient.resetFnrhSettings() {
    sql(
        "UPDATE fnrh.settings SET environment = 'HOMOLOGATION', username = NULL, password = NULL, " +
            "default_cpf = NULL, enabled = FALSE",
    ).update()
}
