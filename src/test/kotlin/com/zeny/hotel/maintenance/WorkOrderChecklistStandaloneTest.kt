package com.zeny.hotel.maintenance

import com.zeny.hotel.checklists.ChecklistExecutionsTestConfiguration
import com.zeny.hotel.checklists.ChecklistExecutionsTestSupport
import com.zeny.hotel.files.FilesTestSupport
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.util.UUID

/**
 * Ponta a ponta do Review Focus 2 (M6 E08): uma execução avulsa iniciada pelo endpoint do `checklists`, com sujeito
 * `rooms.room` de uma UH ativa e o item de manutenção "não ok", concluída, não abre ordem de serviço: o local não é
 * escolhido por quem executa.
 */
@IntegrationTest
@Import(ChecklistExecutionsTestConfiguration::class)
class WorkOrderChecklistStandaloneTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired files: FilesTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired staffApi: StaffApi,
) {
    private val checklists = ChecklistExecutionsTestSupport(iam, files, jdbc)
    private val maintenance = MaintenanceTestSupport(iam, jdbc, staffApi)

    @Test
    fun `a standalone execution on an active room with a failed maintenance item opens no work order`(): Unit =
        with(iam) {
            val (templateId, _) = checklists.publishedTemplate()
            val auth = checklists.auth(checklists.executor())
            val room = maintenance.room()
            val started = checklists.start(auth, templateId, mapOf("type" to "rooms.room", "id" to room))
            val id = UUID.fromString(started["execution"]["id"].asString())
            checklists
                .answer(
                    auth,
                    id,
                    mapOf("itemKey" to "bed", "check" to "OK"),
                    mapOf("itemKey" to "shower", "check" to "NOT_OK", "note" to "Sem agua"),
                    mapOf("itemKey" to "shower-detail", "text" to "Resistencia"),
                ).expectStatus(200)

            checklists.complete(auth, id).ok()

            await().atMost(TIMEOUT).until { completedDeliveries(id) == 1 }
            assertSoftly {
                orders(id) shouldBe 0
                orders(room) shouldBe 0
                checklists.executionRow(id)["location_room_id"] shouldBe null
            }
        }

    private fun orders(id: UUID): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM maintenance.work_order WHERE checklist_execution_id = :id OR room_id = :id",
            ).param("id", id)
            .query(Int::class.java)
            .single()

    private fun completedDeliveries(execution: UUID): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM shared.event_publication WHERE completion_date IS NOT NULL " +
                    "AND serialized_event LIKE :execution AND listener_id LIKE '%MaintenanceChecklistListener%'",
            ).param("execution", "%\"executionId\":\"$execution\"%")
            .query(Int::class.java)
            .single()

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
