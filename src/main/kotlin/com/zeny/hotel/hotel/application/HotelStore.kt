package com.zeny.hotel.hotel.application

import com.zeny.hotel.hotel.domain.BusinessDay
import com.zeny.hotel.hotel.domain.Hotel
import com.zeny.hotel.hotel.domain.HotelOperations
import com.zeny.hotel.hotel.domain.HotelPolicy
import com.zeny.hotel.hotel.domain.HotelTaxes

/**
 * As partes da configuração do hotel único, cada uma uma linha só (o banco garante): na transação
 * corrente.
 */
interface HotelStore {
    fun hotel(): Hotel?

    fun policy(): HotelPolicy?

    fun taxes(): HotelTaxes?

    fun operations(): HotelOperations?

    fun businessDay(): BusinessDay?

    /** A data operacional travada para escrita (`SELECT … FOR UPDATE`) até o fim da transação. */
    fun businessDayForUpdate(): BusinessDay?

    /** Grava o hotel novo e as partes da configuração dele. */
    fun add(
        hotel: Hotel,
        policy: HotelPolicy,
        taxes: HotelTaxes,
        operations: HotelOperations,
        businessDay: BusinessDay,
    )

    /** Grava já as mudanças pendentes (conferindo a versão: lock otimista), para a versão nova sair na resposta. */
    fun flush()
}

/** O hotel ainda não foi criado (o boot o cria): erro de implantação, não do usuário. */
internal fun notInitialized(): Nothing = error("O hotel ainda não foi inicializado (HotelInitializer no boot)")
