package com.zeny.hotel.shared.web

import com.zeny.hotel.shared.API_V1
import com.zeny.hotel.shared.ApiV1Controller
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.HandlerTypePredicate
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** Põe todo controller anotado com [ApiV1Controller] sob `/api/v1` (ARCHITECTURE §7). */
@Configuration(proxyBeanMethods = false)
class ApiWebConfiguration : WebMvcConfigurer {
    override fun configurePathMatch(configurer: PathMatchConfigurer) {
        configurer.addPathPrefix(API_V1, HandlerTypePredicate.forAnnotation(ApiV1Controller::class.java))
    }
}
