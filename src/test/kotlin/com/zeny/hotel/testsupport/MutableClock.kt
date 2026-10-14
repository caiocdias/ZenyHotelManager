package com.zeny.hotel.testsupport

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Relógio que o teste avança à mão (bloqueio de login, validade de token). Sempre em UTC. */
class MutableClock(
    @Volatile private var now: Instant,
) : Clock() {
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
}

/** Troca o `Clock` da aplicação por um [MutableClock] que começa na hora real. */
@TestConfiguration(proxyBeanMethods = false)
class MutableClockConfiguration {
    @Bean
    @Primary
    fun mutableClock(): MutableClock = MutableClock(Instant.now())
}
