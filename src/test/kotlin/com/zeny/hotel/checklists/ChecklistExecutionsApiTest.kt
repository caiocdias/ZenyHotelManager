package com.zeny.hotel.checklists

import com.zeny.hotel.checklists.ChecklistExecutionsTestSupport.Companion.EXECUTIONS
import com.zeny.hotel.checklists.ChecklistExecutionsTestSupport.Companion.LINEN_TYPE
import com.zeny.hotel.checklists.ChecklistExecutionsTestSupport.Companion.STOCK_ITEM
import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.FilesApi
import com.zeny.hotel.files.FilesTestSupport
import com.zeny.hotel.files.TestImages
import com.zeny.hotel.hotel.HotelPermissions
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.test.EnableScenarios
import org.springframework.modulith.test.Scenario
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier

/**
 * Execução de checklists online (M2 E07, RF-CHK-02, 03, 05 e 06): iniciar pelo modelo ou pelo contexto,
 * responder com `If-Match`, os obrigatórios visíveis (o oculto pela condição não é exigido e a resposta dele
 * sai), as fotos (reencodadas, vinculadas, desvinculadas; outra finalidade, outro usuário ou inexistente
 * recusados), a conclusão que publica `ChecklistCompleted` uma vez com as ações e o escopo `OWN`.
 */
@IntegrationTest
@EnableScenarios
@Import(ChecklistExecutionsTestConfiguration::class)
class ChecklistExecutionsApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired files: FilesTestSupport,
    @Autowired private val filesApi: FilesApi,
    @Autowired private val checklistsApi: ChecklistsApi,
    @Autowired private val jdbc: JdbcClient,
) {
    private val support = ChecklistExecutionsTestSupport(iam, files, jdbc)

    @BeforeEach
    fun clearEvents() = ChecklistCompletedEvents.received.clear()

    @Test
    fun `a required visible item blocks the completion, and a hidden one is not required`(): Unit =
        with(iam) {
            val (templateId, _) = support.publishedTemplate()
            val user = support.executor()
            val auth = support.auth(user)
            val id = UUID.fromString(support.start(auth, templateId)["execution"]["id"].asString())

            // Sem `bed`: obrigatório visível. `shower-detail` está oculto (shower = OK) e não é exigido.
            support.answer(auth, id, check("shower", "OK")).expectStatus(200)
            val missing = support.complete(auth, id).problem(422, "CHECKLIST_REQUIRED_ITEMS_MISSING")
            missing["missingItems"].values().map { it.asString() } shouldContainExactly listOf("bed")
            support.executionRow(id)["status"] shouldBe "IN_PROGRESS"

            // Com `shower` = NOT_OK, o detalhe aparece e passa a ser exigido.
            support.answer(auth, id, check("bed", "OK"), check("shower", "NOT_OK")).expectStatus(200)
            support
                .complete(auth, id)
                .problem(422, "CHECKLIST_REQUIRED_ITEMS_MISSING")["missingItems"]
                .values()
                .map { it.asString() } shouldContainExactly listOf("shower-detail")

            // Volta a OK com o detalhe respondido: o detalhe fica oculto, não é exigido e sai na conclusão.
            support
                .answer(
                    auth,
                    id,
                    check("bed", "OK"),
                    check("shower", "OK"),
                    mapOf("itemKey" to "shower-detail", "text" to "vazando"),
                ).expectStatus(200)
            val completed = support.complete(auth, id).ok()
            completed["execution"]["status"].asString() shouldBe "COMPLETED"
            completed["answers"].values().map { it["itemKey"].asString() } shouldContainExactlyInAnyOrder
                listOf("bed", "shower")
            support.complete(auth, id).problem(409, "CHECKLIST_EXECUTION_ALREADY_COMPLETED")
        }

    @Test
    @Suppress("LongMethod") // as respostas dos sete tipos, o evento com as três ações e a conclusão repetida
    fun `completing publishes ChecklistCompleted once, with the answers that trigger actions`(
        scenario: Scenario,
    ): Unit =
        with(iam) {
            val (templateId, versionId) = support.publishedTemplate()
            val user = support.executor()
            val auth = support.auth(user)
            val subject = mapOf("type" to "rooms.room", "id" to UuidV7.generate())
            val started = support.start(auth, templateId, subject)
            val id = UUID.fromString(started["execution"]["id"].asString())
            started["execution"]["versionId"].asString() shouldBe versionId.toString()
            started["checklistVersion"]["number"].asInt() shouldBe 1
            support
                .answer(
                    auth,
                    id,
                    check("bed", "OK"),
                    mapOf("itemKey" to "shower", "check" to "NOT_OK", "note" to "Sem água quente"),
                    mapOf("itemKey" to "shower-detail", "text" to "Resistência queimada"),
                    mapOf("itemKey" to "towels", "quantity" to "3"),
                    mapOf("itemKey" to "temperature", "number" to "4.5"),
                    mapOf("itemKey" to "minibar", "choices" to listOf("soda", "water")),
                    mapOf("itemKey" to "cleaning", "choice" to "deep"),
                ).expectStatus(200)

            scenario
                .stimulate(Supplier { support.complete(auth, id) })
                .andWaitForEventOfType(ChecklistCompleted::class.java)
                .matching { it.executionId == id }
                .toArriveAndVerify { event, response ->
                    response.status shouldBe 200
                    assertSoftly {
                        event.templateId shouldBe templateId
                        event.versionId shouldBe versionId
                        event.versionNumber shouldBe 1
                        event.subjectType shouldBe "rooms.room"
                        event.subjectId shouldBe subject["id"]
                        event.executorId shouldBe user.id.value
                        event.actions shouldContainExactly
                            listOf(
                                ChecklistTriggeredAction(
                                    "shower",
                                    ItemActionType.OPEN_OCCURRENCE,
                                    occurrenceKind = OccurrenceKind.MAINTENANCE,
                                    category = "PLUMBING",
                                    note = "Sem água quente",
                                ),
                                ChecklistTriggeredAction(
                                    "towels",
                                    ItemActionType.CONSUME_STOCK,
                                    quantity = BigDecimal("3"),
                                    stockItemId = STOCK_ITEM,
                                ),
                                ChecklistTriggeredAction(
                                    "towels",
                                    ItemActionType.LINEN_EXCHANGE,
                                    quantity = BigDecimal("3"),
                                    linenTypeId = LINEN_TYPE,
                                    movement = LinenMovement.PLACED,
                                ),
                            )
                    }
                }

            // A conclusão repetida é recusada e não publica de novo.
            support.complete(auth, id).problem(409, "CHECKLIST_EXECUTION_ALREADY_COMPLETED")
            await().atMost(Duration.ofSeconds(TIMEOUT)).until {
                ChecklistCompletedEvents.received.count { it.executionId == id } == 1
            }
            support.completedPublications(id) shouldBe 1
            val row = support.executionRow(id)
            row["status"] shouldBe "COMPLETED"
            row["completed_at"].shouldNotBeNull()
            val answers = support.get(auth, id)["answers"].values().associateBy { it["itemKey"].asString() }
            answers.getValue("minibar")["choices"].values().map { it.asString() } shouldContainExactly
                listOf("water", "soda")
            answers.getValue("cleaning")["choice"].asString() shouldBe "deep"
            answers.getValue("temperature")["number"].asString() shouldBe "4.5"
        }

    @Test
    fun `nothing is triggered by a zero quantity or a passing check`(scenario: Scenario): Unit =
        with(iam) {
            val (templateId, _) = support.publishedTemplate()
            val auth = support.auth(support.executor())
            val id = UUID.fromString(support.start(auth, templateId)["execution"]["id"].asString())
            support
                .answer(
                    auth,
                    id,
                    check("bed", "OK"),
                    check("shower", "NOT_APPLICABLE"),
                    mapOf("itemKey" to "towels", "quantity" to "0"),
                ).expectStatus(200)
            scenario
                .stimulate(Supplier { support.complete(auth, id) })
                .andWaitForEventOfType(ChecklistCompleted::class.java)
                .matching { it.executionId == id }
                .toArriveAndVerify { event -> event.actions shouldBe emptyList() }
        }

    @Test
    fun `a photo is reencoded, attached to the execution and detached when replaced`(): Unit =
        with(iam) {
            val (templateId, _) = support.publishedTemplate()
            val user = support.executor()
            val auth = support.auth(user)
            val id = UUID.fromString(support.start(auth, templateId)["execution"]["id"].asString())
            val first = support.uploadPhoto(auth)
            val second = support.uploadPhoto(auth)

            support.answer(auth, id, photo(first)).expectStatus(200)
            filesApi.find(FileId(first))!!.ownerEntityId shouldBe id.toString()
            // Sem metadados: só o APP0 JFIF antes da imagem, sem APP1 (EXIF/GPS), e o lado maior em 2048 px.
            val stored = support.storedObject(first)
            TestImages.jpegMarkers(stored) shouldNotContain APP1
            String(stored, Charsets.ISO_8859_1).contains(TestImages.METADATA_MARKER) shouldBe false
            TestImages.read(stored).width shouldBe MAX_SIDE

            support.answer(auth, id, photo(second)).expectStatus(200)
            filesApi.find(FileId(first))!!.ownerEntityId.shouldBeNull()
            support.fileRow(first)["detached_at"].shouldNotBeNull()
            filesApi.find(FileId(second))!!.ownerEntityId shouldBe id.toString()

            // A foto obrigatória não existe por padrão: concluir sem ela vale; com ela, fica vinculada.
            support.answer(auth, id, check("bed", "OK"), check("shower", "OK"), photo(second)).expectStatus(200)
            support
                .complete(auth, id)
                .ok()["answers"]
                .values()
                .single { it["itemKey"].asString() == "photo" }["photoFileId"]
                .asString() shouldBe second.toString()
            filesApi.find(FileId(second))!!.ownerEntityId shouldBe id.toString()
        }

    @Test
    fun `a photo of another purpose, of another user or that does not exist is refused`(): Unit =
        with(iam) {
            val (templateId, _) = support.publishedTemplate()
            // O executor também pode enviar o logotipo: a foto de outra finalidade é dele mesmo, sem outro dono.
            val user =
                createUserWith(
                    TestGrant(ChecklistsTestSupport.definition(ChecklistsPermissions.EXECUTIONS_EXECUTE), OWN),
                    TestGrant(ChecklistsTestSupport.definition(ChecklistsPermissions.EXECUTIONS_READ), OWN),
                    TestGrant(HotelPermissions.permissions.single { it.code == HotelPermissions.SETTINGS_MANAGE }, ALL),
                )
            val auth = asTestUser(user)
            val other = support.auth(support.executor())
            val id = UUID.fromString(support.start(auth, templateId)["execution"]["id"].asString())
            val logo = support.uploadPhoto(auth, purpose = "hotel.logo")
            // Uma foto de checklist de outro usuário, ainda sem dono.
            val othersPhoto = support.uploadPhoto(other)
            // Uma foto do próprio executor, já vinculada a outra execução dele.
            val otherExecution = UUID.fromString(support.start(auth, templateId)["execution"]["id"].asString())
            val attachedElsewhere = support.uploadPhoto(auth)
            support.answer(auth, otherExecution, photo(attachedElsewhere)).expectStatus(200)
            val mine = support.uploadPhoto(auth)

            support.answer(auth, id, photo(logo)).problem(422, "CHECKLIST_PHOTO_INVALID")
            support.answer(auth, id, photo(othersPhoto)).problem(422, "CHECKLIST_PHOTO_INVALID")
            support.answer(auth, id, photo(attachedElsewhere)).problem(422, "CHECKLIST_PHOTO_INVALID")
            support.answer(auth, id, photo(UuidV7.generate())).problem(422, "CHECKLIST_PHOTO_NOT_FOUND")
            // Nada mudou: nenhuma foto recusada foi vinculada.
            filesApi.find(FileId(logo))!!.ownerEntityId.shouldBeNull()
            filesApi.find(FileId(othersPhoto))!!.ownerEntityId.shouldBeNull()
            filesApi.find(FileId(attachedElsewhere))!!.ownerEntityId shouldBe otherExecution.toString()
            support.get(auth, id)["answers"].values() shouldBe emptyList()
            support.answer(auth, id, photo(mine)).expectStatus(200)
        }

    @Test
    fun `answers are checked against the item types`(): Unit =
        with(iam) {
            val (templateId, _) = support.publishedTemplate()
            val auth = support.auth(support.executor())
            val id = UUID.fromString(support.start(auth, templateId)["execution"]["id"].asString())

            fun refused(
                answer: Map<String, Any?>,
                code: String,
                field: String,
            ) {
                val problem = support.answer(auth, id, answer).problem(400, code)
                problem["errors"][0]["field"].asString() shouldBe field
            }
            refused(mapOf("itemKey" to "nope", "check" to "OK"), "CHECKLIST_ANSWER_UNKNOWN_ITEM", "answers[0].itemKey")
            refused(mapOf("itemKey" to "bed", "quantity" to "1"), "CHECKLIST_ANSWER_INVALID", "answers[0].quantity")
            refused(mapOf("itemKey" to "towels", "quantity" to "-1"), "CHECKLIST_ANSWER_INVALID", "answers[0].quantity")
            refused(
                mapOf("itemKey" to "towels", "quantity" to "1.2345"),
                "CHECKLIST_ANSWER_INVALID",
                "answers[0].quantity",
            )
            refused(mapOf("itemKey" to "cleaning", "choice" to "fast"), "CHECKLIST_ANSWER_INVALID", "answers[0].choice")
            refused(
                mapOf("itemKey" to "minibar", "choices" to listOf("wine")),
                "CHECKLIST_ANSWER_INVALID",
                "answers[0].choices",
            )
            refused(mapOf("itemKey" to "bed"), "CHECKLIST_ANSWER_INVALID", "answers[0].check")
            val duplicated =
                call(
                    PUT,
                    "$EXECUTIONS/$id/answers",
                    auth,
                    mapOf("answers" to listOf(check("bed", "OK"), check("bed", "NOT_OK"))),
                    support.etag(auth, id),
                ).problem(400, "CHECKLIST_ANSWER_DUPLICATED")
            duplicated["errors"][0]["field"].asString() shouldBe "answers[1].itemKey"
            // Sem If-Match, 428; com outra versão, 412.
            call(
                PUT,
                "$EXECUTIONS/$id/answers",
                auth,
                mapOf("answers" to listOf(check("bed", "OK"))),
            ).problem(428, "PRECONDITION_REQUIRED")
            call(
                PUT,
                "$EXECUTIONS/$id/answers",
                auth,
                mapOf("answers" to listOf(check("bed", "OK"))),
                "\"99\"",
            ).problem(412, "PRECONDITION_FAILED")
        }

    @Test
    fun `with OWN scope the executor sees and changes only their own executions`(): Unit =
        with(iam) {
            val (templateId, _) = support.publishedTemplate()
            val owner = support.executor()
            val stranger = support.executor()
            val manager = support.executor(ALL)
            val id = UUID.fromString(support.start(support.auth(owner), templateId)["execution"]["id"].asString())
            val theirs =
                UUID.fromString(support.start(support.auth(stranger), templateId)["execution"]["id"].asString())

            call(GET, "$EXECUTIONS/$id", support.auth(stranger)).problem(404, "CHECKLIST_EXECUTION_NOT_FOUND")
            listed(support.auth(stranger)) shouldNotContain id.toString()
            listed(support.auth(owner)) shouldNotContain theirs.toString()
            call(
                PUT,
                "$EXECUTIONS/$id/answers",
                support.auth(stranger),
                mapOf("answers" to emptyList<Any>()),
                "\"0\"",
            ).problem(403, "RESOURCE_OUT_OF_SCOPE")
            call(POST, "$EXECUTIONS/$id/complete", support.auth(stranger)).problem(403, "RESOURCE_OUT_OF_SCOPE")

            // Com ALL vê as duas; mesmo assim, só o executor responde.
            val all = listed(support.auth(manager))
            (id.toString() in all && theirs.toString() in all) shouldBe true
            support.get(support.auth(manager), id)["execution"]["executorId"].asString() shouldBe
                owner.id.value.toString()
            call(POST, "$EXECUTIONS/$id/complete", support.auth(manager)).problem(403, "CHECKLIST_EXECUTOR_REQUIRED")
            // Sem a permissão, 403.
            val nobody = asTestUser(createUser())
            call(GET, EXECUTIONS, nobody).problem(403, "PERMISSION_DENIED")
            call(POST, EXECUTIONS, nobody, mapOf("templateId" to templateId)).problem(403, "PERMISSION_DENIED")
        }

    @Test
    fun `an execution starts from the template that applies to the subject, or is refused`(): Unit =
        with(iam) {
            val auth = support.auth(support.executor())
            val area = UuidV7.generate()
            val specific =
                support.templates.create(
                    support.templates.templateBody(
                        context = ChecklistContext.COMMON_AREA,
                        applicability = listOf(mapOf("areaIds" to listOf(area))),
                    ),
                )
            support.templates.publish(specific)
            val started =
                call(
                    POST,
                    EXECUTIONS,
                    auth,
                    mapOf("context" to "COMMON_AREA", "attributes" to mapOf("areaId" to area)),
                ).expectStatus(201)!!
            started["execution"]["templateId"].asString() shouldBe specific.toString()

            // O contexto RESTAURANT fica reservado a este teste: desativa os que houver (o banco é compartilhado).
            jdbc.sql("UPDATE checklists.checklist_template SET active = false WHERE context = 'RESTAURANT'").update()
            call(POST, EXECUTIONS, auth, mapOf("context" to "RESTAURANT", "attributes" to mapOf("areaId" to area)))
                .problem(422, "CHECKLIST_NO_APPLICABLE_TEMPLATE")
            call(POST, EXECUTIONS, auth, mapOf<String, Any?>()).problem(400, "CHECKLIST_EXECUTION_INVALID")
            val draftOnly = support.templates.create()
            call(
                POST,
                EXECUTIONS,
                auth,
                mapOf("templateId" to draftOnly),
            ).problem(422, "CHECKLIST_TEMPLATE_NOT_PUBLISHED")
            call(
                POST,
                EXECUTIONS,
                auth,
                mapOf("templateId" to specific, "subject" to mapOf("type" to "Rooms Room", "id" to area)),
            ).problem(400, "CHECKLIST_EXECUTION_INVALID")
        }

    @Test
    fun `the public API refuses an id already used with 409, and tolerates an invalid photo`(): Unit =
        with(iam) {
            val (_, versionId) = support.publishedTemplate(support.defaultSections(photoRequired = true))
            val user = support.executor()
            val id = ChecklistExecutionId.new()
            val subject = ChecklistSubject("housekeeping.task", UuidV7.generate())
            checklistsApi.startExecution(ChecklistVersionId(versionId), subject, user.id, Instant.now(), id)

            shouldThrow<ConflictException> {
                checklistsApi.startExecution(ChecklistVersionId(versionId), null, user.id, Instant.now(), id)
            }.code.value shouldBe "CHECKLIST_EXECUTION_ALREADY_EXISTS"
            checklistsApi.execution(id)!!.subject shouldBe subject
            support.executionCount(id.value) shouldBe 1

            val logo = support.uploadPhoto(asAdmin(), purpose = "hotel.logo")
            val completion =
                checklistsApi.completeExecution(
                    id,
                    listOf(
                        ChecklistAnswerInput("bed", check = CheckAnswer.OK),
                        ChecklistAnswerInput("shower", check = CheckAnswer.OK),
                        ChecklistAnswerInput("photo", photoFileId = FileId(logo)),
                    ),
                    Instant.now(),
                )
            completion.rejectedPhotos shouldBe listOf("photo")
            completion.missingPhotos shouldBe emptyList()
            completion.execution.status shouldBe ChecklistExecutionStatus.COMPLETED
            filesApi.find(FileId(logo))!!.ownerEntityId.shouldBeNull()
            // Uma entrada por chamada na trilha (ajuste da E07 no gate E10).
            jdbc
                .sql(
                    "SELECT action FROM audit.audit_entry WHERE entity_type = 'checklists.execution' " +
                        "AND entity_id = :id ORDER BY occurred_at, id",
                ).param("id", id.value.toString())
                .query(String::class.java)
                .list() shouldContainExactly listOf("checklists.execution.start", "checklists.execution.complete")
        }

    @Test
    fun `executions are audited and never deleted`(): Unit =
        with(iam) {
            val (templateId, _) = support.publishedTemplate()
            val auth = support.auth(support.executor())
            val id = UUID.fromString(support.start(auth, templateId)["execution"]["id"].asString())
            support.answer(auth, id, check("bed", "OK"), check("shower", "OK")).expectStatus(200)
            support.complete(auth, id).expectStatus(200)
            jdbc
                .sql("SELECT action FROM audit.audit_entry WHERE entity_id = :id ORDER BY occurred_at")
                .param("id", id.toString())
                .query(String::class.java)
                .list() shouldContainExactly
                listOf("checklists.execution.start", "checklists.execution.answer", "checklists.execution.complete")
            runCatching {
                jdbc.sql("DELETE FROM checklists.checklist_execution WHERE id = :id").param("id", id).update()
            }.isFailure shouldBe true
        }

    private fun listed(auth: RequestPostProcessor): List<String> =
        with(iam) {
            call(GET, "$EXECUTIONS?limit=200", auth).ok()["items"].values().map { it["id"].asString() }
        }

    private fun check(
        key: String,
        value: String,
    ) = mapOf("itemKey" to key, "check" to value)

    private fun photo(id: UUID) = mapOf("itemKey" to "photo", "photoFileId" to id)

    private companion object {
        const val TIMEOUT = 15L
        const val APP1 = 0xE1
        const val MAX_SIDE = 2048
    }
}
