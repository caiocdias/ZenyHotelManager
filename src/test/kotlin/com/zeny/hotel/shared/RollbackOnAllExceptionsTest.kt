package com.zeny.hotel.shared

import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.ProbeCheckedException
import com.zeny.hotel.testsupport.SwallowingCaller
import com.zeny.hotel.testsupport.TransactionAndLockProbesConfiguration
import com.zeny.hotel.testsupport.TransactionProbe
import com.zeny.hotel.testsupport.rowsWithMarker
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.UnexpectedRollbackException
import java.io.IOException
import java.util.UUID

/**
 * Rollback em toda exceção (M6 E18, ARCHITECTURE §5 item 6): o `@EnableTransactionManagement` do `shared` usa
 * `rollbackOn = ALL_EXCEPTIONS`, então uma *checked* que escapa de um caso de uso desfaz a escrita e a trilha; o
 * `noRollbackFor` explícito continua confirmando; e uma *checked* engolida numa transação unida faz o commit externo
 * falhar.
 */
@IntegrationTest
@Import(TransactionAndLockProbesConfiguration::class)
class RollbackOnAllExceptionsTest(
    @Autowired private val probe: TransactionProbe,
    @Autowired private val caller: SwallowingCaller,
    @Autowired private val jdbc: JdbcClient,
) {
    @Test
    fun `a checked exception from an audited use case leaves neither the write nor the trail entry`() {
        val marker = marker()

        shouldThrow<IOException> { probe.writeThenThrowChecked(marker) }

        rowsWithMarker(jdbc, marker) shouldBe 0
        trailEntriesWith(marker) shouldBe 0
    }

    @Test
    fun `an explicit noRollbackFor still commits with ALL_EXCEPTIONS`() {
        val marker = marker()

        shouldThrow<ProbeCheckedException> { probe.writeThenThrowListed(marker) }

        rowsWithMarker(jdbc, marker) shouldBe 1
    }

    @Test
    fun `a checked exception swallowed in a joined transaction makes the outer commit fail`() {
        val marker = marker()

        shouldThrow<UnexpectedRollbackException> { caller.writeAndSwallow(marker) }

        rowsWithMarker(jdbc, marker) shouldBe 0
    }

    private fun marker() = "rollback-${UUID.randomUUID()}"

    private fun trailEntriesWith(marker: String): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM audit.audit_entry " +
                    "WHERE action = :action AND after_state ->> 'title' = :marker",
            ).param("action", TransactionProbe.WRITE_THEN_CHECKED)
            .param("marker", marker)
            .query(Int::class.java)
            .single()
}
