package com.zeny.hotel.printing.infrastructure

import com.zeny.hotel.printing.application.PrintAgentStore
import com.zeny.hotel.printing.application.PrinterStore
import com.zeny.hotel.printing.domain.PrintAgent
import com.zeny.hotel.printing.domain.Printer
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Component
import java.util.UUID

interface PrintAgentJpaRepository : JpaRepository<PrintAgent, UUID> {
    @Query("SELECT a FROM PrintAgent a WHERE a.tokenHash = :hash AND a.active = true")
    fun findActiveByTokenHash(hash: String): PrintAgent?
}

/** Os agentes por JPA, na transação corrente. */
@Component
class JpaPrintAgentStore(
    private val agents: PrintAgentJpaRepository,
) : PrintAgentStore {
    override fun byTokenHash(hash: String): PrintAgent? = agents.findActiveByTokenHash(hash)

    override fun byId(id: UUID): PrintAgent? = agents.findById(id).orElse(null)

    override fun all(): List<PrintAgent> = agents.findAll(Sort.by("createdAt", "id"))

    override fun save(agent: PrintAgent) {
        agents.save(agent)
    }

    override fun flush() = agents.flush()
}

interface PrinterJpaRepository : JpaRepository<Printer, UUID>

/** As impressoras por JPA, na transação corrente. */
@Component
class JpaPrinterStore(
    private val printers: PrinterJpaRepository,
) : PrinterStore {
    override fun byId(id: UUID): Printer? = printers.findById(id).orElse(null)

    override fun all(): List<Printer> = printers.findAll(Sort.by("name", "id"))

    override fun save(printer: Printer) {
        printers.save(printer)
    }

    override fun flush() = printers.flush()
}
