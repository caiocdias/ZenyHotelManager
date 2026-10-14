package com.zeny.hotel.testsupport

import com.zeny.hotel.shared.Address
import com.zeny.hotel.shared.Cnpj
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.EncryptedStringConverter
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.TypedIdConverter
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import java.util.UUID

/** Id tipado de exemplo, do jeito que cada módulo declara os seus. */
@JvmInline
value class ProbeId(
    override val value: UUID,
) : TypedId

/** Conversor do id tipado para quando ele chega boxed ao Hibernate (coleções). */
class ProbeIdConverter : TypedIdConverter<ProbeId>(::ProbeId)

/**
 * Entidade só de teste com os tipos do kernel `shared` (M0 E03). A tabela vem de
 * `src/test/resources/db/testmigration` e fica no schema `test_support`, fora dos módulos.
 * `Money`, `Percent`, `Cpf`, `Cnpj` e `DocumentNumber` não precisam de `@Convert`: os
 * conversores deles são aplicados automaticamente.
 */
@Entity
@Suppress("LongParameterList") // uma coluna por tipo do kernel
@Table(name = "kernel_probe", schema = "test_support")
class KernelProbe(
    @Id
    val id: ProbeId,
    @Column(name = "doc_number", nullable = false)
    val number: DocumentNumber,
    @Column(nullable = false)
    val amount: Money,
    @Column(nullable = false)
    val discount: Percent,
    @Column(nullable = false)
    val cpf: Cpf,
    @Column(nullable = false)
    val cnpj: Cnpj,
    @Convert(converter = EncryptedStringConverter::class)
    @Column(nullable = false)
    val document: String,
    @Embedded
    val address: Address,
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "kernel_probe_related",
        schema = "test_support",
        joinColumns = [JoinColumn(name = "probe_id")],
    )
    @Column(name = "related_id", nullable = false)
    @Convert(converter = ProbeIdConverter::class)
    val related: MutableSet<ProbeId> = mutableSetOf(),
)
