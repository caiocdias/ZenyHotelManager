package com.zeny.hotel.checklists

import com.zeny.hotel.checklists.application.ChecklistReportsStore
import com.zeny.hotel.checklists.application.ReportQuery
import com.zeny.hotel.checklists.infrastructure.JdbcChecklistReports
import com.zeny.hotel.files.FilesTestSupport
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Relatórios de checklists (M2 E08, RF-CHK-09): conformidade por modelo, itens mais reprovados e histórico por local,
 * sobre um conjunto de execuções conhecido (dois modelos, duas UHs, execuções avulsas, em andamento e uma pendente de
 * rotina). Os itens "não se aplica" ficam fora do denominador; as consultas leem só as views `checklists.report_*`
 * (conferido com um papel que só lê as views) e o papel da aplicação só lê as views; sem a permissão, `403`.
 *
 * O banco é compartilhado: toda asserção filtra pelo modelo ou pelo local criados aqui.
 */
@IntegrationTest
@Import(ChecklistExecutionsTestConfiguration::class)
@Suppress("LongParameterList") // os beans de que o teste precisa, injetados pelo contexto
class ChecklistReportsTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired files: FilesTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired private val flyway: Flyway,
) {
    private val support = ChecklistExecutionsTestSupport(iam, files, jdbc)

    // --- Conformidade por modelo ------------------------------------------------------------------------

    @Test
    fun `compliance by template counts only completed executions and leaves not applicable out of the denominator`() {
        val data = dataset()
        val auth = reader()

        // Modelo A, sem filtro de período: A1..A5 concluídas (a A6, em andamento, fica de fora).
        // OK 1+2+1+0+2 = 6, NÃO OK 1+1+1+0+0 = 3, N/A 1+0+1+3+1 = 6 → 6 / 9 (com N/A no denominador seria 6 / 15).
        compliance(auth, "templateId=${data.templateA}").single().let {
            assertCompliance(it, data.templateA, data.nameA, executions = 5, ok = 6, notOk = 3, na = 6)
            it["complianceRate"].asString() shouldBe "66.6667"
            it["context"].asString() shouldBe "HOUSEKEEPING"
        }

        // Pelo local R1: A1 e A2 do modelo A e B1 do modelo B, em ordem de nome do modelo.
        val byRoom = compliance(auth, "subjectType=rooms.room&subjectId=${data.room1}")
        byRoom.map { it["templateId"].asString() } shouldContainExactly
            listOf(data.templateA.toString(), data.templateB.toString())
        assertCompliance(byRoom[0], data.templateA, data.nameA, executions = 2, ok = 3, notOk = 2, na = 1)
        byRoom[0]["complianceRate"].asString() shouldBe "60.0000"
        assertCompliance(byRoom[1], data.templateB, data.nameB, executions = 1, ok = 2, notOk = 0, na = 0)
        byRoom[1]["complianceRate"].asString() shouldBe "100.0000"

        // Pelo local R2: A3 e A4 (a A4 só com "não se aplica", que não pesa na taxa).
        compliance(auth, "templateId=${data.templateA}&subjectId=${data.room2}").single().let {
            assertCompliance(it, data.templateA, data.nameA, executions = 2, ok = 1, notOk = 1, na = 4)
            it["complianceRate"].asString() shouldBe "50.0000"
        }
        // Com tudo "não se aplica" no local: nada avaliado, sem taxa (nunca zero nem divisão por zero).
        jdbc
            .sql("UPDATE checklists.checklist_answer SET check_value = 'NOT_APPLICABLE' WHERE execution_id = :id")
            .param("id", data.a3)
            .update()
        compliance(auth, "templateId=${data.templateA}&subjectId=${data.room2}").single().let {
            assertCompliance(it, data.templateA, data.nameA, executions = 2, ok = 0, notOk = 0, na = 6)
            it["evaluatedCount"].asLong() shouldBe 0
            it["complianceRate"].isNull shouldBe true
        }
    }

    @Test
    fun `compliance by template filters by the period in the hotel time zone`() {
        val data = dataset()
        val auth = reader()
        val today = LocalDate.now(hotel.timezone())

        // A1 e A2 foram concluídas uns dez dias atrás; A3, A4 e A5, agora.
        compliance(auth, "templateId=${data.templateA}&from=${today.minusDays(12)}&to=${today.minusDays(8)}")
            .single()
            .let {
                assertCompliance(it, data.templateA, data.nameA, executions = 2, ok = 3, notOk = 2, na = 1)
                it["complianceRate"].asString() shouldBe "60.0000"
            }
        compliance(auth, "templateId=${data.templateA}&from=${today.minusDays(1)}&to=${today.plusDays(1)}")
            .single()
            .let {
                assertCompliance(it, data.templateA, data.nameA, executions = 3, ok = 3, notOk = 1, na = 5)
                it["complianceRate"].asString() shouldBe "75.0000"
            }
        compliance(auth, "templateId=${data.templateA}&from=${today.minusDays(30)}&to=${today.minusDays(20)}") shouldBe
            emptyList()
        with(iam) {
            call(GET, "$REPORTS/compliance?from=$today&to=${today.minusDays(1)}", auth)
                .problem(400, "CHECKLIST_REPORT_PERIOD_INVALID")["errors"][0]["field"]
                .asString() shouldBe "to"
        }
    }

    // --- Itens mais reprovados --------------------------------------------------------------------------

    @Test
    fun `the most failed items rank the check items by failures, without not applicable in the rate`() {
        val data = dataset()
        val auth = reader()
        val today = LocalDate.now(hotel.timezone())

        // shower: NÃO OK em A1 e A2, OK em A3 e A5, N/A em A4 → 2 / 4; bed: NÃO OK em A3, OK em A1, A2 e A5, N/A
        // em A4 → 1 / 4. O minibar nunca foi reprovado e as toalhas não são de marcação: ficam de fora. A A6 (em
        // andamento, com shower NÃO OK) não conta. O rótulo é o da versão mais nova entre as execuções do filtro.
        val ranking = failedItems(auth, "templateId=${data.templateA}")
        ranking.map { it["itemKey"].asString() } shouldContainExactly listOf("shower", "bed")
        assertFailedItem(ranking[0], data, "shower", "Chuveiro e ducha", ok = 2, notOk = 2, na = 1)
        ranking[0]["failureRate"].asString() shouldBe "50.0000"
        assertFailedItem(ranking[1], data, "bed", "Cama feita", ok = 3, notOk = 1, na = 1)
        ranking[1]["failureRate"].asString() shouldBe "25.0000"

        failedItems(auth, "templateId=${data.templateA}&limit=1").map { it["itemKey"].asString() } shouldBe
            listOf("shower")

        // Só as antigas (A1 e A2, versão 1): o rótulo da versão 1.
        failedItems(auth, "templateId=${data.templateA}&from=${today.minusDays(12)}&to=${today.minusDays(8)}")
            .single()
            .let {
                assertFailedItem(it, data, "shower", "Chuveiro", ok = 0, notOk = 2, na = 0)
                it["failureRate"].asString() shouldBe "100.0000"
            }

        // Pelo local R2 (A3 e A4): só a cama foi reprovada; nenhum item do modelo B foi reprovado em R1.
        failedItems(auth, "subjectType=rooms.room&subjectId=${data.room2}").single().let {
            assertFailedItem(it, data, "bed", "Cama feita", ok = 0, notOk = 1, na = 1)
        }
        failedItems(auth, "templateId=${data.templateB}") shouldBe emptyList()
        with(iam) { call(GET, "$REPORTS/failed-items?limit=201", auth).problem(400, "VALIDATION_FAILED") }
    }

    // --- Histórico por local ----------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // o histórico inteiro de um local, com as páginas e os filtros
    fun `the history of a location lists every execution there, newest first, with the outcomes`() {
        val data = dataset()
        val auth = reader()
        val today = LocalDate.now(hotel.timezone())
        val r1 = "subjectType=rooms.room&subjectId=${data.room1}"

        val history = history(auth, r1)
        history.map { it["executionId"].asString() } shouldContainExactly
            listOf(data.a6, data.b1, data.pending, data.a2, data.a1).map(UUID::toString)

        assertSoftly {
            history[0].let {
                it["status"].asString() shouldBe "IN_PROGRESS"
                it["completedAt"].isNull shouldBe true
                it["notOkCount"].asLong() shouldBe 1
            }
            history[1].let {
                it["templateId"].asString() shouldBe data.templateB.toString()
                it["templateName"].asString() shouldBe data.nameB
                it["status"].asString() shouldBe "COMPLETED"
                it["okCount"].asLong() shouldBe 2
                it["complianceRate"].asString() shouldBe "100.0000"
            }
            history[2].let {
                it["status"].asString() shouldBe "PENDING"
                it["routineId"].asString() shouldBe data.routine.toString()
                it["businessDate"].asString() shouldBe data.pendingDate.toString()
                Instant.parse(it["dueAt"].asString()) shouldBe data.pendingDueAt
                Instant.parse(it["referenceAt"].asString()) shouldBe data.pendingDueAt
                it["executorId"].isNull shouldBe true
                it["versionNumber"].isNull shouldBe true
                it["evaluatedCount"].asLong() shouldBe 0
                it["complianceRate"].isNull shouldBe true
            }
            history[4].let {
                it["templateName"].asString() shouldBe data.nameA
                it["context"].asString() shouldBe "HOUSEKEEPING"
                it["versionNumber"].asInt() shouldBe 1
                it["status"].asString() shouldBe "COMPLETED"
                it["executorId"].asString() shouldBe
                    data.executor.id.value
                        .toString()
                it["okCount"].asLong() shouldBe 1
                it["notOkCount"].asLong() shouldBe 1
                it["notApplicableCount"].asLong() shouldBe 1
                it["evaluatedCount"].asLong() shouldBe 2
                it["complianceRate"].asString() shouldBe "50.0000"
                Instant.parse(it["referenceAt"].asString()) shouldBe Instant.parse(it["completedAt"].asString())
            }
        }

        // Páginas de duas, pelo cursor.
        val pages =
            generateSequence(page(auth, r1, null)) { previous ->
                previous["nextCursor"].takeUnless { it.isNull }?.let { page(auth, r1, it.asString()) }
            }.toList()
        pages.size shouldBe 3
        pages.flatMap { it["items"].values().map { item -> item["executionId"].asString() } } shouldContainExactly
            history.map { it["executionId"].asString() }

        // Filtros de período (pela referência: conclusão, início ou prazo) e de modelo; o outro local; o local
        // é obrigatório.
        history(auth, "$r1&from=${today.minusDays(12)}&to=${today.minusDays(8)}")
            .map { it["executionId"].asString() } shouldContainExactly listOf(data.a2, data.a1).map(UUID::toString)
        history(auth, "$r1&templateId=${data.templateB}").map { it["executionId"].asString() } shouldContainExactly
            listOf(data.b1.toString())
        history(auth, "subjectType=rooms.room&subjectId=${data.room2}").map { it["executionId"].asString() } shouldBe
            listOf(data.a4, data.a3).map(UUID::toString)
        history(auth, "subjectType=rooms.area&subjectId=${data.room1}") shouldBe emptyList()
        with(iam) {
            call(GET, "$REPORTS/location-history?subjectId=${data.room1}", auth).expectStatus(400)
            call(GET, "$REPORTS/location-history?subjectType=rooms.room", auth).expectStatus(400)
        }
    }

    // --- Permissão, views e privilégios -----------------------------------------------------------------

    @Test
    fun `without checklists_reports_read every report is refused with 403`(): Unit =
        with(iam) {
            // Ler e executar todas as execuções não basta: o relatório tem a permissão própria.
            val executionsReader = support.executor(ALL)
            val paths =
                listOf(
                    "$REPORTS/compliance",
                    "$REPORTS/failed-items",
                    "$REPORTS/location-history?subjectType=rooms.room&subjectId=${UuidV7.generate()}",
                )
            assertSoftly {
                paths.forEach { path ->
                    withClue(path) {
                        call(GET, path, asTestUser(executionsReader)).problem(403, "PERMISSION_DENIED")
                        call(GET, path, asTestUser(createUser())).problem(403, "PERMISSION_DENIED")
                        call(GET, path, reader()).ok()
                    }
                }
            }
        }

    @Test
    fun `the application role only reads each report view`() {
        val views =
            jdbc
                .sql(
                    "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                        "WHERE n.nspname = 'checklists' AND c.relkind = 'v' ORDER BY c.relname",
                ).query(String::class.java)
                .list()
        views shouldContainExactlyInAnyOrder REPORT_VIEWS

        assertSoftly {
            REPORT_VIEWS.forEach { view ->
                withClue(view) {
                    jdbc.sql("SELECT count(*) FROM checklists.$view").query(Long::class.java).single()
                    PRIVILEGES.filter { privilegeOn("checklists.$view", it) } shouldBe listOf("SELECT")
                }
            }
        }
    }

    @Test
    fun `the report queries read only the report views`() {
        val data = dataset()
        val query = ReportQuery(null, null, ChecklistTemplateId(data.templateA), "rooms.room", data.room1)

        // Um papel que só lê as views (sem nenhuma tabela do schema): as consultas dos endpoints rodam com ele.
        // Uma consulta que tocasse uma tabela interna falharia com "permission denied" (42501).
        withViewsOnlyRole { store ->
            store.compliance(query).single().executions shouldBe 2
            store.failedItems(query, LIMIT).map { it.itemKey } shouldBe listOf("shower")
            store.locationHistory(query, null, LIMIT).map { it.executionId.value } shouldContainExactly
                listOf(data.a6, data.pending, data.a2, data.a1)

            // Controle: o mesmo papel não lê as tabelas internas.
            val error =
                shouldThrow<DataAccessException> {
                    store.probe("SELECT count(*) FROM checklists.checklist_execution")
                }
            (error.mostSpecificCause as SQLException).sqlState shouldBe INSUFFICIENT_PRIVILEGE
        }
    }

    // --- Apoio ------------------------------------------------------------------------------------------

    /**
     * O conjunto de dados conhecido. Modelo A (marcações `bed`, `shower`, `minibar`; `towels` de quantidade), versão 1
     * e, depois de renomear o `shower`, versão 2; modelo B (marcações `floor`, `lights`). Locais R1 e R2.
     *
     * | Execução | Modelo/versão | Local | bed | shower | minibar | Situação |
     * |---|---|---|---|---|---|---|
     * | A1 | A v1 | R1 | OK | NÃO OK | N/A | concluída 10 dias e 2 h atrás |
     * | A2 | A v1 | R1 | OK | NÃO OK | OK | concluída 10 dias e 1 h atrás |
     * | A3 | A v2 | R2 | NÃO OK | OK | N/A | concluída agora |
     * | A4 | A v2 | R2 | N/A | N/A | N/A | concluída agora |
     * | A5 | A v2 | — | OK | OK | N/A | concluída agora |
     * | A6 | A v2 | R1 | — | NÃO OK | — | em andamento |
     *
     * B1 (modelo B, R1, `floor` e `lights` OK) concluída agora, antes da A6; e uma pendente de rotina do modelo A
     * em R1, com o prazo cinco dias atrás.
     */
    @Suppress("LongMethod") // o conjunto de dados inteiro, descrito na tabela acima
    private fun dataset(): Dataset {
        val nameA = support.templates.uniqueName("Relatório A")
        val nameB = support.templates.uniqueName("Relatório B")
        val templateA = publish(nameA, sectionsA(showerLabel = "Chuveiro"))
        val templateB =
            publish(
                nameB,
                listOf(support.templates.section("Área", checkItem("floor", "Piso"), checkItem("lights", "Luzes"))),
            )
        val executor = support.executor()
        val auth = support.auth(executor)
        val room1 = UuidV7.generate()
        val room2 = UuidV7.generate()

        val a1 = completed(auth, templateA, room1, "bed" to "OK", "shower" to "NOT_OK", "minibar" to "NOT_APPLICABLE")
        val a2 = completed(auth, templateA, room1, "bed" to "OK", "shower" to "NOT_OK", "minibar" to "OK")
        shiftBack(a1, Duration.ofDays(10).plusHours(2))
        shiftBack(a2, Duration.ofDays(10).plusHours(1))

        // A versão 2 renomeia o chuveiro.
        with(iam) {
            call(
                PUT,
                "/api/v1/checklist-templates/$templateA",
                asAdmin(),
                mapOf(
                    "name" to nameA,
                    "applicability" to emptyList<Any>(),
                    "sections" to sectionsA("Chuveiro e ducha"),
                ),
                support.templates.etagOf(templateA),
            ).ok()
        }
        support.templates.publish(templateA) shouldBe 2

        val a3 = completed(auth, templateA, room2, "bed" to "NOT_OK", "shower" to "OK", "minibar" to "NOT_APPLICABLE")
        val a4 =
            completed(
                auth,
                templateA,
                room2,
                "bed" to "NOT_APPLICABLE",
                "shower" to "NOT_APPLICABLE",
                "minibar" to "NOT_APPLICABLE",
            )
        val a5 = completed(auth, templateA, null, "bed" to "OK", "shower" to "OK", "minibar" to "NOT_APPLICABLE")
        val b1 = completed(auth, templateB, room1, "floor" to "OK", "lights" to "OK")
        val a6 = started(auth, templateA, room1)
        with(iam) { support.answer(auth, a6, check("shower", "NOT_OK")).expectStatus(200) }

        val today = LocalDate.now(hotel.timezone())
        val pendingDate = today.minusDays(5)
        val pendingDueAt = pendingDate.atTime(9, 0).atZone(hotel.timezone()).toInstant()
        val (routine, pending) = pendingRoutineExecution(templateA, room1, pendingDate, pendingDueAt)

        return Dataset(
            executor,
            templateA,
            templateB,
            nameA,
            nameB,
            room1,
            room2,
            a1,
            a2,
            a3,
            a4,
            a5,
            a6,
            b1,
            routine,
            pending,
            pendingDate,
            pendingDueAt,
        )
    }

    private data class Dataset(
        val executor: TestUser,
        val templateA: UUID,
        val templateB: UUID,
        val nameA: String,
        val nameB: String,
        val room1: UUID,
        val room2: UUID,
        val a1: UUID,
        val a2: UUID,
        val a3: UUID,
        val a4: UUID,
        val a5: UUID,
        val a6: UUID,
        val b1: UUID,
        val routine: UUID,
        val pending: UUID,
        val pendingDate: LocalDate,
        val pendingDueAt: Instant,
    )

    private fun sectionsA(showerLabel: String) =
        listOf(
            support.templates.section(
                "Quarto",
                checkItem("bed", "Cama feita"),
                checkItem("shower", showerLabel),
                checkItem("minibar", "Frigobar"),
                support.templates.item("towels", "Toalhas", ChecklistItemType.QUANTITY, mapOf("unit" to "peças")),
            ),
        )

    private fun checkItem(
        key: String,
        label: String,
    ) = support.templates.item(key, label)

    private fun publish(
        name: String,
        sections: List<Map<String, Any?>>,
    ): UUID {
        val id = support.templates.create(support.templates.templateBody(name = name, sections = sections))
        support.templates.publish(id) shouldBe 1
        return id
    }

    private fun started(
        auth: RequestPostProcessor,
        templateId: UUID,
        room: UUID?,
    ): UUID {
        val subject = room?.let { mapOf("type" to "rooms.room", "id" to it) }
        return UUID.fromString(support.start(auth, templateId, subject)["execution"]["id"].asString())
    }

    private fun completed(
        auth: RequestPostProcessor,
        templateId: UUID,
        room: UUID?,
        vararg checks: Pair<String, String>,
    ): UUID =
        with(iam) {
            val id = started(auth, templateId, room)
            val answers = checks.map { (key, value) -> check(key, value) }.toTypedArray()
            support.answer(auth, id, *answers).expectStatus(200)
            support.complete(auth, id).ok()["execution"]["status"].asString() shouldBe "COMPLETED"
            id
        }

    /** Recua o início e a conclusão da execução (a conclusão "aconteceu" [by] atrás). */
    private fun shiftBack(
        id: UUID,
        by: Duration,
    ) {
        jdbc
            .sql(
                "UPDATE checklists.checklist_execution SET started_at = started_at - CAST(:by AS interval), " +
                    "completed_at = completed_at - CAST(:by AS interval) WHERE id = :id",
            ).param("by", "${by.seconds} seconds")
            .param("id", id)
            .update() shouldBe 1
    }

    /** Uma rotina do modelo no local e a execução pendente dela na [date] (nunca iniciada). */
    private fun pendingRoutineExecution(
        templateId: UUID,
        room: UUID,
        date: LocalDate,
        dueAt: Instant,
    ): Pair<UUID, UUID> {
        val routine = UuidV7.generate()
        val execution = UuidV7.generate()
        jdbc
            .sql(
                """
                INSERT INTO checklists.checklist_routine
                    (id, name, template_id, department_id, frequency, weekdays, due_at, subject_type, subject_id,
                     active, created_at, updated_at, version)
                VALUES (:id, :name, :templateId, :department, 'DAILY', '', TIME '09:00', 'rooms.room', :room,
                        true, now(), now(), 0)
                """.trimIndent(),
            ).param("id", routine)
            .param("name", support.templates.uniqueName("Rotina"))
            .param("templateId", templateId)
            .param("department", UuidV7.generate())
            .param("room", room)
            .update()
        jdbc
            .sql(
                """
                INSERT INTO checklists.checklist_execution
                    (id, template_id, context, subject_type, subject_id, routine_id, business_date, due_at, status,
                     created_at, updated_at, version)
                VALUES (:id, :templateId, 'HOUSEKEEPING', 'rooms.room', :room, :routine, :date,
                        CAST(:dueAt AS timestamptz), 'PENDING', now(), now(), 0)
                """.trimIndent(),
            ).param("id", execution)
            .param("templateId", templateId)
            .param("room", room)
            .param("routine", routine)
            .param("date", date)
            .param("dueAt", dueAt.toString())
            .update()
        return routine to execution
    }

    private fun check(
        key: String,
        value: String,
    ) = mapOf("itemKey" to key, "check" to value)

    /** Um usuário só com `checklists.reports.read`. */
    private fun reader(): RequestPostProcessor =
        iam.asTestUser(
            iam.createUserWith(TestGrant(ChecklistsTestSupport.definition(ChecklistsPermissions.REPORTS_READ), ALL)),
        )

    private fun compliance(
        auth: RequestPostProcessor,
        query: String,
    ): List<JsonNode> = with(iam) { call(GET, "$REPORTS/compliance?$query", auth).ok()["items"].values().toList() }

    private fun failedItems(
        auth: RequestPostProcessor,
        query: String,
    ): List<JsonNode> = with(iam) { call(GET, "$REPORTS/failed-items?$query", auth).ok()["items"].values().toList() }

    private fun history(
        auth: RequestPostProcessor,
        query: String,
    ): List<JsonNode> =
        with(iam) { call(GET, "$REPORTS/location-history?$query", auth).ok()["items"].values().toList() }

    private fun page(
        auth: RequestPostProcessor,
        query: String,
        cursor: String?,
    ): JsonNode =
        with(iam) {
            val after = cursor?.let { "&cursor=$it" }.orEmpty()
            call(GET, "$REPORTS/location-history?$query&limit=2$after", auth).ok()
        }

    @Suppress("LongParameterList") // os campos da linha
    private fun assertCompliance(
        row: JsonNode,
        templateId: UUID,
        templateName: String,
        executions: Long,
        ok: Long,
        notOk: Long,
        na: Long,
    ) = assertSoftly {
        row["templateId"].asString() shouldBe templateId.toString()
        row["templateName"].asString() shouldBe templateName
        row["executions"].asLong() shouldBe executions
        row["okCount"].asLong() shouldBe ok
        row["notOkCount"].asLong() shouldBe notOk
        row["notApplicableCount"].asLong() shouldBe na
        row["evaluatedCount"].asLong() shouldBe ok + notOk
    }

    @Suppress("LongParameterList") // os campos da linha
    private fun assertFailedItem(
        row: JsonNode,
        data: Dataset,
        key: String,
        label: String,
        ok: Long,
        notOk: Long,
        na: Long,
    ) = assertSoftly {
        row["templateId"].asString() shouldBe data.templateA.toString()
        row["templateName"].asString() shouldBe data.nameA
        row["itemKey"].asString() shouldBe key
        row["itemLabel"].asString() shouldBe label
        row["okCount"].asLong() shouldBe ok
        row["notOkCount"].asLong() shouldBe notOk
        row["notApplicableCount"].asLong() shouldBe na
        row["evaluatedCount"].asLong() shouldBe ok + notOk
    }

    private fun privilegeOn(
        relation: String,
        privilege: String,
    ): Boolean =
        jdbc
            .sql("SELECT has_table_privilege(current_user, :relation, :privilege)")
            .param("relation", relation)
            .param("privilege", privilege)
            .query(Boolean::class.java)
            .single()

    /**
     * Roda [block] com o adaptador dos relatórios ligado a uma conexão do dono com `SET ROLE` para um papel novo, sem
     * login, que só tem `USAGE` no schema e `SELECT` nas views `report_*` (a view roda com os privilégios do dono
     * dela, então lê as tabelas por baixo). Removido no fim.
     */
    private fun withViewsOnlyRole(block: (ViewsOnlyStore) -> Unit) {
        val owner = JdbcClient.create(flyway.configuration.dataSource)
        val role = "report_probe_${UuidV7.generate().toString().takeLast(PROBE_SUFFIX).replace("-", "")}"
        owner.sql("CREATE ROLE $role NOLOGIN").update()
        try {
            owner.sql("GRANT USAGE ON SCHEMA checklists TO $role").update()
            REPORT_VIEWS.forEach { owner.sql("GRANT SELECT ON checklists.$it TO $role").update() }
            flyway.configuration.dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("SET ROLE $role") }
                val client = JdbcClient.create(SingleConnectionDataSource(connection, true))
                client.sql("SELECT current_user").query(String::class.java).single() shouldStartWith "report_probe_"
                block(ViewsOnlyStore(JdbcChecklistReports(client), client))
                connection.createStatement().use { it.execute("RESET ROLE") }
            }
        } finally {
            owner.sql("DROP OWNED BY $role").update()
            owner.sql("DROP ROLE $role").update()
        }
    }

    private class ViewsOnlyStore(
        store: ChecklistReportsStore,
        private val client: JdbcClient,
    ) : ChecklistReportsStore by store {
        fun probe(sql: String): Long = client.sql(sql).query(Long::class.java).single()
    }

    private companion object {
        const val REPORTS = "/api/v1/checklist-reports"
        const val LIMIT = 50
        const val PROBE_SUFFIX = 12
        const val INSUFFICIENT_PRIVILEGE = "42501"
        val REPORT_VIEWS =
            listOf("report_check_item_outcome", "report_execution_compliance", "report_location_history")
        val PRIVILEGES = listOf("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")
    }
}
