package com.zeny.hotel.hotel

import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * O contrato de `HotelApi.lockBusinessDate` (RF-FEC-05, M6 E11): a trava `FOR SHARE` da data operacional só vale até o
 * commit de quem a toma, então só existe dentro da transação de escrita de quem chama; fora dela, ou numa só de
 * leitura, é erro de programação. Não avança a data (banco compartilhado).
 */
@IntegrationTest
class BusinessDateLockContractTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val transactionManager: PlatformTransactionManager,
) {
    @Test
    fun `outside a transaction the lock is refused`() {
        shouldThrow<IllegalStateException> { hotel.lockBusinessDate() }
    }

    @Test
    fun `in a read-only transaction the lock is refused`() {
        val readOnly = TransactionTemplate(transactionManager).apply { isReadOnly = true }

        shouldThrow<IllegalStateException> { readOnly.execute { hotel.lockBusinessDate() } }
    }

    @Test
    fun `in a write transaction the lock returns the current business date`() {
        val current = hotel.currentBusinessDate()

        transactions.execute { hotel.lockBusinessDate() } shouldBe current
    }
}
