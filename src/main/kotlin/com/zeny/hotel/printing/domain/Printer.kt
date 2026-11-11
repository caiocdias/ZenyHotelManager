package com.zeny.hotel.printing.domain

import com.zeny.hotel.printing.PrinterKind
import com.zeny.hotel.shared.UuidV7
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.util.UUID

/**
 * Uma impressora, atendida por um agente (SPEC §8.2.18). O [kind] é fixo depois da criação, porque os jobs pendentes
 * têm o tipo de mídia dele; nome, [address] e agente mudam por [update]. O [address] (endereço no agente) fica fora
 * do `toString`.
 */
@Entity
@Table(name = "printer", schema = "printing")
class Printer private constructor(
    @Id
    val id: UUID,
    name: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val kind: PrinterKind,
    address: String,
    agentId: UUID,
) {
    @Column(nullable = false)
    var name: String = name
        protected set

    @Column(nullable = false)
    var address: String = address
        protected set

    @Column(name = "agent_id", nullable = false)
    var agentId: UUID = agentId
        protected set

    @Column(nullable = false)
    var active: Boolean = true
        protected set

    @Version
    var version: Long? = null
        protected set

    /** Troca nome, endereço e agente; nada muda se um valor é inválido. */
    fun update(
        name: String,
        address: String,
        agentId: UUID,
    ) {
        val validName = requireValidName(name)
        val validAddress = requireValidAddress(address)
        this.name = validName
        this.address = validAddress
        this.agentId = agentId
    }

    fun deactivate() {
        active = false
    }

    override fun toString(): String = "Printer(id=$id, name=$name, kind=$kind, active=$active)"

    companion object {
        fun create(
            name: String,
            kind: PrinterKind,
            address: String,
            agentId: UUID,
        ): Printer = Printer(UuidV7.generate(), requireValidName(name), kind, requireValidAddress(address), agentId)
    }
}

private const val MAX_ADDRESS_LENGTH = 255

/** Endereço de 1 a 255 caracteres, sem controle nem espaço (D-E10-8). */
private fun requireValidAddress(address: String): String {
    require(address.length in 1..MAX_ADDRESS_LENGTH) { "O endereço deve ter de 1 a $MAX_ADDRESS_LENGTH caracteres." }
    require(!address.hasHiddenCharacter() && address.none { it.isWhitespace() }) {
        "O endereço não pode ter espaço nem caracteres de controle."
    }
    return address
}
