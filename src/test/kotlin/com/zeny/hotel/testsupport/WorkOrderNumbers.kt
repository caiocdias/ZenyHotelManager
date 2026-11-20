package com.zeny.hotel.testsupport

import kotlin.random.Random

/**
 * Números legíveis válidos (`OS-<ano>-<sequência>`) para as ordens de serviço que os testes gravam por SQL: o ano e a
 * faixa da sequência não colidem com os da sequence real, e a listagem de OS lê o formato.
 */
object WorkOrderNumbers {
    const val PREFIX = "OS-2099-"

    fun next(): String = PREFIX + Random.nextLong(MIN_SEQUENCE, MAX_SEQUENCE)

    private const val MIN_SEQUENCE = 100_000_000L
    private const val MAX_SEQUENCE = 999_999_999L
}
