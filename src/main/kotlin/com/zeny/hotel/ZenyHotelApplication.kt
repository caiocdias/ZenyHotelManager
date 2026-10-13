package com.zeny.hotel

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.modulith.Modulithic

/**
 * Ponto de entrada do servidor. Cada pacote de primeiro nível abaixo de `com.zeny.hotel`
 * é um módulo do Spring Modulith (ARCHITECTURE §3 e §4); `shared` é o kernel compartilhado,
 * visível a todos os módulos.
 */
@SpringBootApplication
@Modulithic(systemName = "Zeny Hotel Manager", sharedModules = ["shared"])
class ZenyHotelApplication

@Suppress("SpreadOperator") // idioma padrão do Spring Boot; roda uma vez, na partida
fun main(args: Array<String>) {
    runApplication<ZenyHotelApplication>(*args)
}
