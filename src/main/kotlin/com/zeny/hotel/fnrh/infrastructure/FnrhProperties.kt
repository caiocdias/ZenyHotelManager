package com.zeny.hotel.fnrh.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.time.Duration

/**
 * Parâmetros da integração com a FNRH (`zeny.fnrh`, D-E07-1). As duas URLs ficam em propriedade, e não na
 * configuração gravada, para a credencial nunca ir a um host escolhido pelo usuário.
 */
@ConfigurationProperties("zeny.fnrh")
data class FnrhProperties(
    val homologationUrl: URI = URI.create("https://hom-lowcode.serpro.gov.br/FNRH_API/rest/v2"),
    val productionUrl: URI = URI.create("https://fnrh.turismo.serpro.gov.br/FNRH_API/rest/v2"),
    val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
    val readTimeout: Duration = DEFAULT_READ_TIMEOUT,
    val submissions: Submissions = Submissions(),
) {
    /**
     * O job de envio e a política de retry (`zeny.fnrh.submissions`, D-E08-4). Os campos são `var` para o bean de teste
     * `@Primary` de [FnrhProperties], ligado como JavaBean, poder trocar um valor por propriedade (`initial-delay`).
     */
    data class Submissions(
        var interval: Duration = Duration.ofMinutes(1),
        var initialDelay: Duration = Duration.ofMinutes(1),
        var batchSize: Int = 50,
        var maxAttempts: Int = 8,
        var initialBackoff: Duration = Duration.ofMinutes(1),
        var maxBackoff: Duration = Duration.ofHours(1),
    )

    companion object {
        private val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val DEFAULT_READ_TIMEOUT: Duration = Duration.ofSeconds(20)
    }

    // Sem segredo hoje; sobrescrito para nunca passar a ter se um campo for acrescentado sem pensar.
    override fun toString(): String =
        "FnrhProperties(homologationUrl=$homologationUrl, productionUrl=$productionUrl, " +
            "connectTimeout=$connectTimeout, readTimeout=$readTimeout, submissions=$submissions)"
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FnrhProperties::class)
class FnrhConfiguration
