package com.zeny.hotel.checklists

import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.FilesApi
import com.zeny.hotel.files.FilesTestSupport
import com.zeny.hotel.files.TestImages
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.test.EnableScenarios
import org.springframework.modulith.test.Scenario
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.function.Supplier

/**
 * Execução de checklists offline (M2 E07, RF-CHK-07, RN-22, H10, ARCHITECTURE §6.2 e §9): os comandos
 * `checklists.start-execution` e `checklists.complete-execution` pela sincronização, com o `commandId` como id da
 * execução, idempotentes (o lote repetido aplica uma vez e publica um `ChecklistCompleted`), com os horários do
 * dispositivo corrigidos pela defasagem, as fotos enviadas antes pelo `clientFileId` (a que não chegou deixa o
 * comando aplicado com alerta), a versão usada guardada e as fontes do download.
 */
@IntegrationTest
@EnableScenarios
@Import(ChecklistExecutionsTestConfiguration::class)
class ChecklistOfflineTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired files: FilesTestSupport,
    @Autowired private val filesApi: FilesApi,
    @Autowired jdbc: JdbcClient,
) {
    private val support = ChecklistExecutionsTestSupport(iam, files, jdbc)

    @BeforeEach
    fun clearEvents() = ChecklistCompletedEvents.received.clear()

    @Test
    @Suppress("LongMethod") // o cenário de ponta a ponta: envio, repetição, horários, foto e evento
    fun `a checklist done offline and synchronized twice is applied once, with the device times corrected`(
        scenario: Scenario,
    ) {
        val (_, versionId) = support.publishedTemplate()
        val user = support.executor()
        val auth = support.auth(user)
        // O app envia a foto antes do comando, com o id gerado no aparelho (H10).
        val photoId = UuidV7.generate()
        support.uploadPhoto(auth, photoId) shouldBe photoId

        // Aparelho com o relógio 2 h atrasado: tudo o que ele informa é corrigido em +2 h (RN-22).
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val deviceClock = now.minus(Duration.ofHours(2))
        val deviceStart = deviceClock.minus(Duration.ofMinutes(40))
        val deviceAnswer = deviceClock.minus(Duration.ofMinutes(20))
        val deviceEnd = deviceClock.minus(Duration.ofMinutes(5))
        val start = startCommand(versionId, deviceStart, deviceClock)
        val complete =
            completeCommand(
                start.id,
                deviceEnd,
                deviceClock,
                answer("bed", "check" to "OK", answeredAt = deviceAnswer),
                answer("shower", "check" to "NOT_OK", "note" to "Pingando", answeredAt = deviceAnswer),
                answer("shower-detail", "text" to "Registro solto", answeredAt = deviceAnswer),
                answer("towels", "quantity" to "2", answeredAt = deviceAnswer),
                answer("photo", "photoFileId" to photoId, answeredAt = deviceAnswer),
            )

        lateinit var first: List<JsonNode>
        scenario
            .stimulate(Supplier { support.sync(auth, start, complete).also { first = it } })
            .andWaitForEventOfType(ChecklistCompleted::class.java)
            .matching { it.executionId == start.id }
            .toArriveAndVerify { event ->
                event.actions.map { it.type } shouldContainExactly
                    listOf(ItemActionType.OPEN_OCCURRENCE, ItemActionType.CONSUME_STOCK, ItemActionType.LINEN_EXCHANGE)
                event.startedAt shouldBe Instant.parse(first[0]["occurredAt"].asString())
                event.completedAt shouldBe Instant.parse(first[1]["occurredAt"].asString())
            }
        first.map { it["status"].asString() } shouldContainExactly
            listOf("APPLIED_WITH_WARNINGS", "APPLIED_WITH_WARNINGS")
        first.flatMap { result -> result["warnings"].values().map { it["code"].asString() } } shouldContainExactly
            listOf("CLOCK_SKEW_EXCEEDED", "CLOCK_SKEW_EXCEEDED")

        // O lote inteiro de novo: nada é reaplicado.
        val second = support.sync(auth, start, complete)
        second.map { it["replayed"].asBoolean() } shouldContainExactly listOf(true, true)
        await().atMost(Duration.ofSeconds(TIMEOUT)).until {
            ChecklistCompletedEvents.received.any { it.executionId == start.id }
        }
        Thread.sleep(SETTLE_MILLIS)
        support.executionCount(start.id) shouldBe 1
        ChecklistCompletedEvents.received.count { it.executionId == start.id } shouldBe 1
        support.completedPublications(start.id) shouldBe 1

        // Os horários são os do aparelho corrigidos, não o do servidor (que recebeu agora).
        val row = support.executionRow(start.id)
        val startedAt = (row["started_at"] as Timestamp).toInstant()
        val completedAt = (row["completed_at"] as Timestamp).toInstant()
        val skew = Duration.between(deviceStart, startedAt)
        assertSoftly {
            row["status"] shouldBe "COMPLETED"
            row["executor_id"] shouldBe user.id.value
            row["start_command_id"] shouldBe start.id
            row["complete_command_id"] shouldBe complete.id
            startedAt shouldBe Instant.parse(first[0]["occurredAt"].asString())
            completedAt shouldBe Instant.parse(first[1]["occurredAt"].asString())
            withClue("defasagem de cerca de 2 h") { (skew.toMinutes() in 119L..121L) shouldBe true }
            withClue("o fim é o do aparelho corrigido, uns 5 min antes do recebimento, não a hora do servidor") {
                (Duration.between(completedAt, now).seconds in FIVE_MINUTES - SLACK..FIVE_MINUTES) shouldBe true
            }
            Duration.between(startedAt, completedAt) shouldBe Duration.between(deviceStart, deviceEnd)
        }
        val detail = support.get(auth, start.id)
        detail["execution"]["offline"].asBoolean() shouldBe true
        val answers = detail["answers"].values().associateBy { it["itemKey"].asString() }
        Instant.parse(answers.getValue("bed")["answeredAt"].asString()) shouldBe
            deviceAnswer.plus(Duration.between(deviceEnd, completedAt))

        // A foto ficou vinculada à execução e foi gravada sem metadados.
        filesApi.find(FileId(photoId))!!.ownerEntityId shouldBe start.id.toString()
        TestImages.jpegMarkers(support.storedObject(photoId)) shouldNotContain APP1
        answers.getValue("photo")["photoFileId"].asString() shouldBe photoId.toString()
    }

    @Test
    fun `a photo not yet uploaded leaves the command applied with a warning, without the photo`() {
        val (_, versionId) = support.publishedTemplate(support.defaultSections(photoRequired = true))
        val auth = support.auth(support.executor())
        val missing = UuidV7.generate()
        val start = startCommand(versionId)
        val complete =
            completeCommand(
                start.id,
                answer("bed", "check" to "OK"),
                answer("shower", "check" to "OK"),
                answer("photo", "photoFileId" to missing),
            )

        val results = support.sync(auth, start, complete)

        results[1]["status"].asString() shouldBe "APPLIED_WITH_WARNINGS"
        results[1]["warnings"].values().map { it["code"].asString() } shouldContainExactly
            listOf("CHECKLIST_PHOTO_MISSING")
        val photo = support.get(auth, start.id)["answers"].values().single { it["itemKey"].asString() == "photo" }
        photo["photoMissing"].asBoolean() shouldBe true
        photo["photoFileId"].isNull shouldBe true
        support.executionRow(start.id)["status"] shouldBe "COMPLETED"
    }

    @Test
    fun `offline refusals carry stable codes and never publish twice`() {
        val (_, versionId) = support.publishedTemplate()
        val user = support.executor()
        val auth = support.auth(user)
        val start = startCommand(versionId)
        support.sync(auth, start).single()["status"].asString() shouldBe "APPLIED"

        val results =
            support.sync(
                auth,
                completeCommand(start.id, answer("bed", "check" to "OK")),
                completeCommand(UuidV7.generate(), answer("bed", "check" to "OK")),
                completeCommand(start.id, answer("nope", "check" to "OK")),
                completeCommand(start.id, answer("bed", "check" to "OK"), answer("shower", "check" to "OK")),
                completeCommand(start.id, answer("bed", "check" to "OK"), answer("shower", "check" to "OK")),
                startCommand(UuidV7.generate()),
            )

        results.map {
            it["status"].asString() + ":" + (it["code"]?.takeUnless { code -> code.isNull }?.asString().orEmpty())
        } shouldContainExactly
            listOf(
                "REJECTED:CHECKLIST_REQUIRED_ITEMS_MISSING",
                "REJECTED:CHECKLIST_EXECUTION_NOT_FOUND",
                "REJECTED:CHECKLIST_ANSWER_UNKNOWN_ITEM",
                "APPLIED:",
                "REJECTED:CHECKLIST_EXECUTION_ALREADY_COMPLETED",
                "REJECTED:CHECKLIST_VERSION_NOT_FOUND",
            )
        await().atMost(Duration.ofSeconds(TIMEOUT)).until {
            ChecklistCompletedEvents.received.any { it.executionId == start.id }
        }
        Thread.sleep(SETTLE_MILLIS)
        support.completedPublications(start.id) shouldBe 1
    }

    @Test
    fun `an invalid photo leaves the offline command applied with a warning, without the photo`() {
        // Foto obrigatória: a inválida fica de fora como a que não chegou (photoMissing) e conta como respondida.
        val (_, versionId) = support.publishedTemplate(support.defaultSections(photoRequired = true))
        val auth = support.auth(support.executor())
        val logo = support.uploadPhoto(iam.asAdmin(), purpose = "hotel.logo")
        val othersPhoto = support.uploadPhoto(support.auth(support.executor()))

        for (photo in listOf(logo, othersPhoto)) {
            val start = startCommand(versionId)
            val results =
                support.sync(
                    auth,
                    start,
                    completeCommand(
                        start.id,
                        answer("bed", "check" to "OK"),
                        answer("shower", "check" to "OK"),
                        answer("photo", "photoFileId" to photo),
                    ),
                )

            results[1]["status"].asString() shouldBe "APPLIED_WITH_WARNINGS"
            results[1]["warnings"].values().map { it["code"].asString() } shouldContainExactly
                listOf("CHECKLIST_PHOTO_INVALID")
            val answer = support.get(auth, start.id)["answers"].values().single { it["itemKey"].asString() == "photo" }
            answer["photoMissing"].asBoolean() shouldBe true
            answer["photoFileId"].isNull shouldBe true
            support.executionRow(start.id)["status"] shouldBe "COMPLETED"
            // Nada foi vinculado: a foto continua sem dono.
            filesApi.find(FileId(photo))!!.ownerEntityId shouldBe null
        }
    }

    @Test
    fun `an offline execution on a replaced version keeps the version used, with a warning`() {
        val (templateId, v1) = support.publishedTemplate()
        // Uma versão 2 publicada enquanto o aparelho estava offline com a 1.
        val body = support.templates.templateBody(sections = support.defaultSections(photoRequired = true))
        iam
            .call(
                PUT,
                "/api/v1/checklist-templates/$templateId",
                iam.asAdmin(),
                body - "context",
                support.templates.etagOf(templateId),
            ).status shouldBe 200
        support.templates.publish(templateId) shouldBe 2
        val auth = support.auth(support.executor())
        val start = startCommand(v1)

        val results =
            support.sync(
                auth,
                start,
                // Na versão 1 a foto não é obrigatória: concluir sem ela vale.
                completeCommand(start.id, answer("bed", "check" to "OK"), answer("shower", "check" to "OK")),
            )

        results[0]["status"].asString() shouldBe "APPLIED_WITH_WARNINGS"
        results[0]["warnings"].values().map { it["code"].asString() } shouldContainExactly
            listOf("CHECKLIST_VERSION_OUTDATED")
        results[1]["status"].asString() shouldBe "APPLIED"
        val row = support.executionRow(start.id)
        row["version_id"] shouldBe v1
        row["version_number"] shouldBe 1
    }

    @Test
    fun `the download brings the published templates and the user's open executions, never the answers`() {
        val (templateId, versionId) = support.publishedTemplate()
        val user = support.executor()
        val other = support.executor()
        val auth = support.auth(user)
        val mine = startCommand(versionId)
        val theirs = startCommand(versionId)
        support.sync(auth, mine).single()["status"].asString() shouldBe "APPLIED"
        support.sync(support.auth(other), theirs).single()["status"].asString() shouldBe "APPLIED"

        val (changes, cursor) = download(auth, null)
        val template =
            changes.single {
                it["source"].asString() == "checklist-templates" &&
                    it["id"].asString() == templateId.toString()
            }
        assertSoftly {
            template["data"]["versionId"].asString() shouldBe versionId.toString()
            template["data"]["content"]["sections"].size() shouldBe 2
        }
        val executions =
            changes
                .filter {
                    it["source"].asString() == "checklist-executions"
                }.map { it["id"].asString() }
        (mine.id.toString() in executions) shouldBe true
        (theirs.id.toString() in executions) shouldBe false
        val execution = changes.single { it["id"].asString() == mine.id.toString() }
        execution["data"]["status"].asString() shouldBe "IN_PROGRESS"
        execution["data"].has("answers") shouldBe false

        // Concluída, sai do aparelho (tombstone).
        support
            .sync(
                auth,
                completeCommand(mine.id, answer("bed", "check" to "OK"), answer("shower", "check" to "OK")),
            ).single()["status"]
            .asString() shouldBe "APPLIED"
        val (later, _) = download(auth, cursor)
        later.single { it["id"].asString() == mine.id.toString() }["deleted"].asBoolean() shouldBe true
    }

    private fun download(
        auth: RequestPostProcessor,
        from: String?,
    ): Pair<List<JsonNode>, String> =
        with(iam) {
            val changes = mutableListOf<JsonNode>()
            var cursor = from
            var pages = 0
            do {
                val page =
                    call(GET, "/api/v1/sync/changes?limit=200" + cursor?.let { "&cursor=$it" }.orEmpty(), auth).ok()
                changes += page["changes"].values()
                cursor = page["cursor"].asString()
                pages++
                withClue("download sem fim") { (pages < MAX_PAGES) shouldBe true }
            } while (page["hasMore"].asBoolean())
            changes to checkNotNull(cursor)
        }

    private fun startCommand(
        versionId: UUID,
        occurredAt: Instant = Instant.now(),
        deviceClock: Instant = Instant.now(),
        executionId: UUID? = null,
    ) = SyncCommand(
        UuidV7.generate(),
        ChecklistsOffline.START.value,
        mapOf("versionId" to versionId, "executionId" to executionId),
        occurredAt,
        deviceClock,
    )

    private fun completeCommand(
        executionId: UUID,
        vararg answers: Map<String, Any?>,
    ) = completeCommand(executionId, Instant.now(), Instant.now(), *answers)

    private fun completeCommand(
        executionId: UUID,
        occurredAt: Instant,
        deviceClock: Instant,
        vararg answers: Map<String, Any?>,
    ) = SyncCommand(
        UuidV7.generate(),
        ChecklistsOffline.COMPLETE.value,
        mapOf("executionId" to executionId, "answers" to answers.toList()),
        occurredAt,
        deviceClock,
    )

    private fun answer(
        key: String,
        vararg values: Pair<String, Any?>,
        answeredAt: Instant? = null,
    ): Map<String, Any?> = mapOf("itemKey" to key, "answeredAt" to answeredAt?.toString()) + values

    private companion object {
        const val TIMEOUT = 15L
        const val SETTLE_MILLIS = 500L
        const val APP1 = 0xE1
        const val MAX_PAGES = 200
        const val FIVE_MINUTES = 300L
        const val SLACK = 60L
    }
}
