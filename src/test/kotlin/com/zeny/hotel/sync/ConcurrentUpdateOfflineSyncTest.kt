package com.zeny.hotel.sync

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import syncexample.SyncExample
import syncexample.SyncExampleConfiguration
import syncexample.SyncExamplePermissions
import java.util.UUID

/**
 * No protocolo offline, um deadlock (`40P01`) que escapa do handler de um comando continua `FAILED` com
 * `COMMAND_FAILED` (retentável: nada guardado, o aparelho reenvia), não `REJECTED` (M6 E21, Task 1; ARCHITECTURE §7,
 * linha "Concorrência"). O `409 CONCURRENT_UPDATE` é só da API; o lote de comandos trata a falha como hoje.
 * Mesma configuração do [OfflineSyncTest], para compartilhar o contexto.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, SyncExampleConfiguration::class, MutableClockConfiguration::class)
class ConcurrentUpdateOfflineSyncTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val clock: MutableClock,
) {
    @Test
    fun `a deadlock in a command handler is COMMAND_FAILED, not REJECTED, and nothing is stored`() {
        val writer =
            iam.createUserWith(
                grant(SyncExamplePermissions.NOTES_READ),
                grant(SyncExamplePermissions.NOTES_WRITE),
            )
        val commandId = UuidV7.generate()
        val command =
            mapOf(
                "commandId" to commandId,
                "type" to SyncExample.RECORD.value,
                "payload" to mapOf("text" to SyncExample.DEADLOCK),
                "occurredAt" to clock.instant().toString(),
                "deviceClock" to clock.instant().toString(),
            )

        val result =
            with(iam) {
                call(POST, "/api/v1/sync/commands", iam.asTestUser(writer), mapOf("commands" to listOf(command)))
                    .ok()["results"]
                    .single()
            }

        result["status"].asString() shouldBe "FAILED"
        result["code"].asString() shouldBe "COMMAND_FAILED"
        count("SELECT count(*) FROM test_support.sync_note WHERE command_id = :id", commandId) shouldBe 0
        count("SELECT count(*) FROM shared.offline_command WHERE command_id = :id", commandId) shouldBe 0
    }

    private fun grant(code: PermissionCode) =
        TestGrant(SyncExamplePermissions.permissions.single { it.code == code }, ALL)

    private fun count(
        sql: String,
        id: UUID,
    ): Int =
        jdbc
            .sql(sql)
            .param("id", id)
            .query(Int::class.java)
            .single()
}
