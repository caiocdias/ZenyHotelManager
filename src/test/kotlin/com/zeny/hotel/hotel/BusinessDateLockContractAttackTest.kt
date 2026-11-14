package com.zeny.hotel.hotel

import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException

/**
 * Ataque ao contrato de `HotelApi.lockBusinessDate` (RF-FEC-05, M6 E11, Task 7) no banco compartilhado, sem avançar a
 * data: a recusa olha a transação **real** e de escrita da chamada (não a sincronização nem a transação de fora de uma
 * suspensa), e a trava fica na linha de `hotel.business_day` até o fim da transação de quem chama, provada por uma
 * sondagem `NOWAIT` numa transação própria, que só trava e desfaz.
 */
@IntegrationTest
class BusinessDateLockContractAttackTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val transactionManager: PlatformTransactionManager,
    @Autowired private val jdbc: JdbcClient,
) {
    private val write = TransactionTemplate(transactionManager)
    private val readOnly = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val requiresNew = template(TransactionDefinition.PROPAGATION_REQUIRES_NEW)

    @Test
    fun `a SUPPORTS scope without an actual transaction is refused`() {
        val supports = template(TransactionDefinition.PROPAGATION_SUPPORTS)

        shouldThrow<IllegalStateException> { supports.execute { hotel.lockBusinessDate() } }
    }

    @Test
    fun `a NOT_SUPPORTED scope inside a write transaction is refused`() {
        val notSupported = template(TransactionDefinition.PROPAGATION_NOT_SUPPORTED)

        shouldThrow<IllegalStateException> { write.execute { notSupported.execute { hotel.lockBusinessDate() } } }
    }

    @Test
    fun `a write scope joined to a read-only transaction is refused`() {
        shouldThrow<IllegalStateException> { readOnly.execute { write.execute { hotel.lockBusinessDate() } } }
    }

    @Test
    fun `a read-only REQUIRES_NEW inside a write transaction is refused`() {
        val readOnlyNew =
            template(TransactionDefinition.PROPAGATION_REQUIRES_NEW).apply { isReadOnly = true }

        shouldThrow<IllegalStateException> { write.execute { readOnlyNew.execute { hotel.lockBusinessDate() } } }
    }

    @Test
    fun `a write REQUIRES_NEW inside a read-only transaction returns the current date`() {
        val current = hotel.currentBusinessDate()

        readOnly.execute { requiresNew.execute { hotel.lockBusinessDate() } } shouldBe current
    }

    @Test
    fun `locking twice in one transaction returns the same date`() {
        val current = hotel.currentBusinessDate()

        write.execute { hotel.lockBusinessDate() to hotel.lockBusinessDate() } shouldBe (current to current)
    }

    @Test
    fun `the row stays share locked after the call returns until the caller commits`() {
        write.execute {
            hotel.lockBusinessDate()

            sqlStateOf { probe("FOR UPDATE NOWAIT") } shouldBe LOCK_NOT_AVAILABLE
            probe("FOR SHARE NOWAIT") shouldBe 1
        }

        probe("FOR UPDATE NOWAIT") shouldBe 1
    }

    /** Trava a linha da data numa transação própria (outra conexão) e desfaz; devolve quantas linhas travou. */
    private fun probe(lock: String): Int? =
        requiresNew.execute { status ->
            status.setRollbackOnly()
            jdbc
                .sql("SELECT 1 FROM hotel.business_day $lock")
                .query(Int::class.java)
                .list()
                .size
        }

    private fun sqlStateOf(block: () -> Unit): String? {
        val failure = shouldThrow<DataAccessException> { block() }
        return (failure.mostSpecificCause as? SQLException)?.sqlState
    }

    private fun template(propagation: Int) =
        TransactionTemplate(transactionManager).apply { propagationBehavior = propagation }

    private companion object {
        const val LOCK_NOT_AVAILABLE = "55P03"
    }
}
