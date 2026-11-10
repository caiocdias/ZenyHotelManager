package com.zeny.hotel.fnrh.infrastructure

import com.zeny.hotel.fnrh.domain.FnrhDomainCatalog
import com.zeny.hotel.fnrh.domain.FnrhLocalValidator
import com.zeny.hotel.fnrh.domain.FnrhRetryPolicy
import com.zeny.hotel.fnrh.domain.IbgeMunicipalities
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Monta os objetos de domínio do envio à FNRH (M5 E08): a política de retry, os municípios e o validador local. */
@Configuration(proxyBeanMethods = false)
class FnrhSubmissionConfiguration {
    @Bean
    fun fnrhRetryPolicy(properties: FnrhProperties): FnrhRetryPolicy =
        with(properties.submissions) { FnrhRetryPolicy(maxAttempts, initialBackoff, maxBackoff) }

    @Bean
    fun ibgeMunicipalities(): IbgeMunicipalities = IbgeMunicipalities()

    @Bean
    fun fnrhLocalValidator(
        catalog: FnrhDomainCatalog,
        municipalities: IbgeMunicipalities,
    ): FnrhLocalValidator = FnrhLocalValidator(catalog, municipalities)
}
