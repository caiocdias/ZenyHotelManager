package com.zeny.hotel.shared

import org.springframework.core.annotation.AliasFor
import org.springframework.web.bind.annotation.RestController

/** Prefixo de todos os endpoints da API versão 1 (ARCHITECTURE §7). */
const val API_V1 = "/api/v1"

/**
 * Controller REST da API v1: o caminho do `@RequestMapping` fica sob [API_V1]. Use no lugar de
 * `@RestController` em todo controller de módulo:
 *
 * ```kotlin
 * @ApiV1Controller
 * @RequestMapping("/reservations")      // → /api/v1/reservations
 * class ReservationController(...)
 * ```
 *
 * O prefixo é aplicado pela configuração web do `shared`; um teste garante que nenhum
 * controller da aplicação fica fora dele.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@RestController
annotation class ApiV1Controller(
    @get:AliasFor(annotation = RestController::class)
    val value: String = "",
)
