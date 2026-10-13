package com.zeny.hotel.testsupport

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Entidade só de teste para conferir como o Hibernate grava instantes. A tabela vem de
 * `src/test/resources/db/testmigration` e fica no schema `test_support`, fora dos módulos.
 */
@Entity
@Table(name = "time_probe", schema = "test_support")
class TimeProbe(
    @Id
    val id: UUID,
    @Column(name = "recorded_at", nullable = false)
    val recordedAt: Instant,
)
