package com.zeny.hotel.checklists

import com.zeny.hotel.checklists.ChecklistItemType.CHECK
import com.zeny.hotel.checklists.ChecklistItemType.MULTI_CHOICE
import com.zeny.hotel.checklists.ChecklistItemType.NUMBER
import com.zeny.hotel.checklists.ChecklistItemType.PHOTO
import com.zeny.hotel.checklists.ChecklistItemType.QUANTITY
import com.zeny.hotel.checklists.ChecklistItemType.SINGLE_CHOICE
import com.zeny.hotel.checklists.ChecklistItemType.TEXT
import com.zeny.hotel.checklists.ChecklistsTestSupport.Companion.definition
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.JsonNode
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Modelos de checklist pela API (M2 E06, RF-CHK-01 a 06): rascunho com `If-Match`, publicação que congela a
 * versão (a anterior continua idêntica pela API e no banco), os sete tipos de item, condições e ações recusadas
 * com código estável, permissões, auditoria, desativação, nenhuma exclusão nem alteração de versão publicada
 * (nem pelo SQL do papel da aplicação nem pelo do dono) e a escolha do modelo pela API pública.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class ChecklistTemplatesApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val flyway: Flyway,
    @Autowired private val checklistsApi: ChecklistsApi,
    @Autowired @Qualifier("requestMappingHandlerMapping") private val handlerMapping: RequestMappingHandlerMapping,
) {
    private val checklists = ChecklistsTestSupport(iam)

    // --- Rascunho e versões --------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // um cenário de ponta a ponta, com cada recusa no seu passo
    fun `a draft is published as version 1, an edit as version 2, and version 1 stays identical`(): Unit =
        with(iam) {
            val reader = createUserWith(TestGrant(definition(ChecklistsPermissions.TEMPLATES_READ), ALL))
            val body = checklists.templateBody()
            call(POST, BASE, asTestUser(reader), body).problem(403, "PERMISSION_DENIED")

            val created = call(POST, BASE, asAdmin(), body)
            val createdBody = created.expectStatus(201).shouldNotBeNull()
            val id = UUID.fromString(createdBody["id"].asString())
            val path = "$BASE/$id"
            assertSoftly {
                created.getHeader(HttpHeaders.LOCATION) shouldBe path
                created.getHeader(HttpHeaders.ETAG) shouldBe "\"0\""
                createdBody["publishedVersion"].isNull shouldBe true
                createdBody["hasUnpublishedChanges"].asBoolean() shouldBe true
                createdBody["context"].asString() shouldBe "HOUSEKEEPING"
                keys(createdBody) shouldContainExactly listOf("bed", "towels")
            }
            call(GET, "$path/versions/1", asTestUser(reader)).problem(404, "CHECKLIST_VERSION_NOT_FOUND")

            // Publicar exige o If-Match do rascunho visto.
            call(POST, "$path/publish", asAdmin()).problem(428, "PRECONDITION_REQUIRED")
            call(POST, "$path/publish", asAdmin(), ifMatch = "\"7\"").problem(412, "PRECONDITION_FAILED")
            call(POST, "$path/publish", asTestUser(reader), ifMatch = "\"0\"").problem(403, "PERMISSION_DENIED")
            val published = call(POST, "$path/publish", asAdmin(), ifMatch = "\"0\"")
            val v1 = published.expectStatus(201).shouldNotBeNull()
            published.getHeader(HttpHeaders.LOCATION) shouldBe "$path/versions/1"
            v1["number"].asInt() shouldBe 1
            v1["publishedBy"].asString() shouldBe adminId().toString()
            call(POST, "$path/publish", asAdmin(), ifMatch = checklists.etagOf(id))
                .problem(422, "CHECKLIST_NOTHING_TO_PUBLISH")

            val v1Json = call(GET, "$path/versions/1", asTestUser(reader)).ok().toString()
            val v1Row = versionRow(id, 1)
            call(GET, path, asTestUser(reader)).ok().let {
                it["publishedVersion"].asInt() shouldBe 1
                it["hasUnpublishedChanges"].asBoolean() shouldBe false
            }

            // Editar o modelo publicado muda só o rascunho.
            val edited =
                mapOf(
                    "name" to body["name"],
                    "applicability" to emptyList<Any>(),
                    "sections" to
                        listOf(
                            checklists.section(
                                "Quarto",
                                checklists.item("bed", "Cama feita e travesseiros"),
                                checklists.item("towels", "Toalhas"),
                                checklists.item("minibar", "Minibar", QUANTITY, mapOf("unit" to "un")),
                            ),
                        ),
                )
            val etag = checklists.etagOf(id)
            call(PUT, path, asAdmin(), edited).problem(428, "PRECONDITION_REQUIRED")
            call(PUT, path, asAdmin(), edited, ifMatch = "\"0\"").problem(412, "PRECONDITION_FAILED")
            call(PUT, path, asTestUser(reader), edited, ifMatch = etag).problem(403, "PERMISSION_DENIED")
            call(PUT, path, asAdmin(), edited, ifMatch = etag).ok().let {
                it["publishedVersion"].asInt() shouldBe 1
                it["hasUnpublishedChanges"].asBoolean() shouldBe true
                keys(it) shouldContainExactly listOf("bed", "towels", "minibar")
            }
            call(GET, "$path/versions/1", asAdmin()).ok().toString() shouldBe v1Json
            versionRow(id, 1) shouldBe v1Row

            checklists.publish(id) shouldBe 2
            val v2 = call(GET, "$path/versions/2", asTestUser(reader)).ok()
            keys(v2) shouldContainExactly listOf("bed", "towels", "minibar")
            v2["sections"][0]["items"][0]["label"].asString() shouldBe "Cama feita e travesseiros"
            call(GET, "$path/versions/1", asAdmin()).ok().toString() shouldBe v1Json
            versionRow(id, 1) shouldBe v1Row
            keys(call(GET, "$path/versions/1", asAdmin()).ok()) shouldContainExactly listOf("bed", "towels")

            call(
                GET,
                "$path/versions",
                asTestUser(reader),
            ).ok()["items"].values().map { it["number"].asInt() } shouldContainExactly
                listOf(2, 1)
            call(GET, "$path/versions?limit=1", asAdmin()).ok().let { page ->
                page["items"].values().map { it["number"].asInt() } shouldContainExactly listOf(2)
                val next = page["nextCursor"].asString()
                call(GET, "$path/versions?limit=1&cursor=$next", asAdmin()).ok()["items"].values().map {
                    it["number"].asInt()
                } shouldContainExactly listOf(1)
            }
            call(GET, "$BASE/${UUID.randomUUID()}", asAdmin()).problem(404, "CHECKLIST_TEMPLATE_NOT_FOUND")
            call(GET, "$BASE/${UUID.randomUUID()}/versions/1", asAdmin()).problem(404, "CHECKLIST_TEMPLATE_NOT_FOUND")
            call(GET, BASE, asTestUser(createUser())).problem(403, "PERMISSION_DENIED")

            auditActions("checklists.template", id) shouldContainExactly
                listOf(
                    "checklists.template.create",
                    "checklists.template.publish",
                    "checklists.template.update",
                    "checklists.template.publish",
                )
            jdbc
                .sql(
                    "SELECT count(*) FROM audit.audit_entry WHERE entity_type = 'checklists.version' AND " +
                        "action = 'checklists.template.publish' AND entity_id IN (:ids)",
                ).param("ids", listOf(v1["id"].asString(), v2["id"].asString()))
                .query(Int::class.java)
                .single() shouldBe 2
        }

    @Test
    fun `two simultaneous publishes of the same draft create a single version`(): Unit =
        with(iam) {
            val id = checklists.create()
            val etag = checklists.etagOf(id)
            val start = CountDownLatch(1)
            val statuses = mutableListOf<Int>()
            val workers =
                (1..2).map {
                    thread {
                        start.await()
                        val status = call(POST, "$BASE/$id/publish", asAdmin(), ifMatch = etag).status
                        synchronized(statuses) { statuses += status }
                    }
                }
            start.countDown()
            workers.forEach { it.join() }
            statuses shouldContainExactlyInAnyOrder listOf(201, 412)
            jdbc
                .sql("SELECT count(*) FROM checklists.checklist_version WHERE template_id = :id")
                .param("id", id)
                .query(Int::class.java)
                .single() shouldBe 1
        }

    @Test
    fun `published versions are never changed nor deleted by the app role or the owner, and templates never deleted`() {
        val id = checklists.create()
        checklists.publish(id)
        val before = versionRow(id, 1)

        // O papel da aplicação não tem UPDATE nem DELETE nas versões (append-only).
        listOf(
            "UPDATE checklists.checklist_version SET name = 'alterado' WHERE template_id = :id",
            "UPDATE checklists.checklist_version SET content = '{}'::jsonb WHERE template_id = :id",
            "DELETE FROM checklists.checklist_version WHERE template_id = :id",
        ).forEach { sql ->
            val error =
                shouldThrow<DataAccessException> {
                    transactions.executeWithoutResult { jdbc.sql(sql).param("id", id).update() }
                }
            withClue(error.toString()) { causes(error).any { "permission denied" in it } shouldBe true }
        }
        // Nem o dono do schema, por SQL comum: o trigger recusa.
        val owner = JdbcClient.create(flyway.configuration.dataSource)
        listOf(
            "UPDATE checklists.checklist_version SET name = 'alterado' WHERE template_id = :id",
            "DELETE FROM checklists.checklist_version WHERE template_id = :id",
        ).forEach { sql ->
            val error = shouldThrow<DataAccessException> { owner.sql(sql).param("id", id).update() }
            withClue(error.toString()) { causes(error).any { "imutáveis" in it } shouldBe true }
        }
        versionRow(id, 1) shouldBe before

        // O modelo nunca é apagado (o trigger recusa ao papel da aplicação).
        val error =
            shouldThrow<DataAccessException> {
                transactions.executeWithoutResult {
                    jdbc.sql("DELETE FROM checklists.checklist_template WHERE id = :id").param("id", id).update()
                }
            }
        withClue(error.toString()) { causes(error).any { "nunca são apagados" in it } shouldBe true }
    }

    @Test
    fun `no checklists endpoint deletes anything`() {
        val mappings =
            handlerMapping.handlerMethods.filter { (_, method) ->
                method.beanType.packageName.startsWith("com.zeny.hotel.checklists")
            }
        mappings.keys
            .flatMap { it.methodsCondition.methods }
            .map { it.name }
            .toSet() shouldBe setOf("GET", "POST", "PUT")
        mappings.keys.flatMap { it.patternValues }.toSet() shouldContainExactlyInAnyOrder
            listOf(
                BASE,
                "$BASE/{id}",
                "$BASE/{id}/publish",
                "$BASE/{id}/deactivate",
                "$BASE/{id}/reactivate",
                "$BASE/{id}/versions",
                "$BASE/{id}/versions/{number}",
                // M2 E07: execuções e rotinas; nenhum endpoint gera rotinas (H14: só o listener da data).
                "/api/v1/checklist-executions",
                "/api/v1/checklist-executions/{id}",
                "/api/v1/checklist-executions/{id}/start",
                "/api/v1/checklist-executions/{id}/answers",
                "/api/v1/checklist-executions/{id}/complete",
                "/api/v1/checklist-routines",
                "/api/v1/checklist-routines/{id}",
                "/api/v1/checklist-routines/{id}/deactivate",
                "/api/v1/checklist-routines/{id}/reactivate",
            )
    }

    @Test
    fun `a deactivated template is listed as inactive and is not edited nor published until reactivated`(): Unit =
        with(iam) {
            val id = checklists.create()
            val path = "$BASE/$id"
            call(POST, "$path/deactivate", asAdmin()).ok()["active"].asBoolean() shouldBe false
            call(PUT, path, asAdmin(), checklists.templateBody(context = null), ifMatch = checklists.etagOf(id))
                .problem(422, "CHECKLIST_TEMPLATE_INACTIVE")
            call(
                POST,
                "$path/publish",
                asAdmin(),
                ifMatch = checklists.etagOf(id),
            ).problem(422, "CHECKLIST_TEMPLATE_INACTIVE")
            listIds("$BASE?active=false&context=HOUSEKEEPING") shouldContain id.toString()
            listIds("$BASE?active=true") shouldNotContain id.toString()
            call(POST, "$path/reactivate", asAdmin()).ok()["active"].asBoolean() shouldBe true
            checklists.publish(id) shouldBe 1
            auditActions("checklists.template", id) shouldContainExactly
                listOf(
                    "checklists.template.create",
                    "checklists.template.deactivate",
                    "checklists.template.reactivate",
                    "checklists.template.publish",
                )
        }

    // --- Itens, condições e ações --------------------------------------------------------------------

    @Test
    fun `the seven item types are accepted, and nothing (photo included) is required by default`(): Unit =
        with(iam) {
            val options = listOf(mapOf("value" to "bom", "label" to "Bom"), mapOf("value" to "ruim", "label" to "Ruim"))
            val body =
                checklists.templateBody(
                    sections =
                        listOf(
                            checklists.section(
                                "Tudo",
                                checklists.item("ok", "Cama feita", CHECK),
                                checklists.item("qty", "Toalhas", QUANTITY, mapOf("unit" to "un")),
                                checklists.item(
                                    "temp",
                                    "Temperatura",
                                    NUMBER,
                                    mapOf("unit" to "°C", "required" to true),
                                ),
                                checklists.item("note", "Observações", TEXT),
                                checklists.item("smell", "Cheiro", SINGLE_CHOICE, mapOf("options" to options)),
                                checklists.item("issues", "Problemas", MULTI_CHOICE, mapOf("options" to options)),
                                checklists.item("photo", "Foto do banheiro", PHOTO),
                            ),
                        ),
                )
            val created = call(POST, BASE, asAdmin(), body).expectStatus(201).shouldNotBeNull()
            val items = created["sections"][0]["items"].values()
            assertSoftly {
                items.map { it["type"].asString() } shouldContainExactly ChecklistItemType.entries.map { it.name }
                items.filter { it["required"].asBoolean() }.map { it["key"].asString() } shouldContainExactly
                    listOf("temp")
                items.single { it["key"].asString() == "photo" }["required"].asBoolean() shouldBe false
                items
                    .single {
                        it["key"].asString() == "smell"
                    }["options"]
                    .values()
                    .map { it["value"].asString() } shouldContainExactly
                    listOf("bom", "ruim")
            }
        }

    @Test
    @Suppress("LongMethod") // cada recusa da condição, e a edição recusada que não muda o rascunho
    fun `a condition on a later, unknown or incompatible item is refused with a stable code and the field`(): Unit =
        with(iam) {
            fun refused(
                items: List<Map<String, Any?>>,
                code: String,
                field: String,
            ) {
                val problem =
                    call(
                        POST,
                        BASE,
                        asAdmin(),
                        checklists.templateBody(
                            sections =
                                listOf(
                                    mapOf(
                                        "title" to "S",
                                        "items" to items,
                                    ),
                                ),
                        ),
                    ).problem(400, code)
                problem["errors"].values().map { it["field"].asString() } shouldContainExactly listOf(field)
            }
            val bed = checklists.item("bed")
            refused(
                listOf(checklists.item("why", "Por quê?", TEXT, condition("bed", "NOT_OK")), bed),
                "CHECKLIST_CONDITION_FORWARD_REFERENCE",
                "sections[0].items[0].visibleWhen.itemKey",
            )
            refused(
                listOf(bed, checklists.item("why", "Por quê?", TEXT, condition("why", "OK"))),
                "CHECKLIST_CONDITION_FORWARD_REFERENCE",
                "sections[0].items[1].visibleWhen.itemKey",
            )
            refused(
                listOf(bed, checklists.item("why", "Por quê?", TEXT, condition("nope", "NOT_OK"))),
                "CHECKLIST_CONDITION_UNKNOWN_ITEM",
                "sections[0].items[1].visibleWhen.itemKey",
            )
            refused(
                listOf(bed, checklists.item("why", "Por quê?", TEXT, condition("bed", "TALVEZ"))),
                "CHECKLIST_CONDITION_INCOMPATIBLE",
                "sections[0].items[1].visibleWhen.answer",
            )
            refused(
                listOf(
                    checklists.item("n", "Nota", TEXT),
                    checklists.item("why", "Por quê?", TEXT, condition("n", "x")),
                ),
                "CHECKLIST_CONDITION_INCOMPATIBLE",
                "sections[0].items[1].visibleWhen.answer",
            )

            // A condição válida é aceita; e uma edição inválida não muda o rascunho.
            val id =
                checklists.create(
                    checklists.templateBody(
                        sections =
                            listOf(
                                mapOf(
                                    "title" to "S",
                                    "items" to
                                        listOf(
                                            bed,
                                            checklists.item("why", "Por quê?", TEXT, condition("bed", "NOT_OK")),
                                        ),
                                ),
                            ),
                    ),
                )
            val etag = checklists.etagOf(id)
            val invalidEdit =
                checklists.templateBody(
                    context = null,
                    sections =
                        listOf(
                            mapOf(
                                "title" to "S",
                                "items" to
                                    listOf(checklists.item("why", "Por quê?", TEXT, condition("bed", "NOT_OK")), bed),
                            ),
                        ),
                )
            call(
                PUT,
                "$BASE/$id",
                asAdmin(),
                invalidEdit,
                ifMatch = etag,
            ).problem(400, "CHECKLIST_CONDITION_FORWARD_REFERENCE")
            call(GET, "$BASE/$id", asAdmin()).ok()["sections"][0]["items"][1]["visibleWhen"].let {
                it["itemKey"].asString() shouldBe "bed"
                it["answer"].asString() shouldBe "NOT_OK"
            }
            checklists.etagOf(id) shouldBe etag
        }

    @Test
    @Suppress("LongMethod") // cada recusa das ações, e as ações aceitas
    fun `a stock action outside a quantity item, or with wrong parameters, is refused with a code`(): Unit =
        with(iam) {
            fun refused(
                item: Map<String, Any?>,
                code: String,
                field: String,
            ) {
                val problem =
                    call(
                        POST,
                        BASE,
                        asAdmin(),
                        checklists.templateBody(sections = listOf(checklists.section("S", item))),
                    ).problem(400, code)
                problem["errors"].values().map { it["field"].asString() } shouldContainExactly listOf(field)
            }
            val stock = mapOf("type" to "CONSUME_STOCK", "stockItemId" to UUID.randomUUID(), "defaultQuantity" to 2)
            val linen = mapOf("type" to "LINEN_EXCHANGE", "linenTypeId" to UUID.randomUUID(), "movement" to "PLACED")
            val occurrence = mapOf("type" to "OPEN_OCCURRENCE", "occurrenceKind" to "DAMAGE", "category" to "furniture")
            listOf(CHECK, NUMBER, TEXT, PHOTO).forEach { type ->
                refused(
                    checklists.item("x", "X", type, mapOf("actions" to listOf(stock))),
                    "CHECKLIST_ACTION_ITEM_TYPE_MISMATCH",
                    "sections[0].items[0].actions[0].type",
                )
            }
            refused(
                checklists.item("x", "X", TEXT, mapOf("actions" to listOf(linen))),
                "CHECKLIST_ACTION_ITEM_TYPE_MISMATCH",
                "sections[0].items[0].actions[0].type",
            )
            refused(
                checklists.item("x", "X", QUANTITY, mapOf("actions" to listOf(occurrence))),
                "CHECKLIST_ACTION_ITEM_TYPE_MISMATCH",
                "sections[0].items[0].actions[0].type",
            )
            refused(
                checklists.item("x", "X", QUANTITY, mapOf("actions" to listOf(mapOf("type" to "CONSUME_STOCK")))),
                "CHECKLIST_ACTION_INVALID",
                "sections[0].items[0].actions[0].stockItemId",
            )
            refused(
                checklists.item("x", "X", QUANTITY, mapOf("actions" to listOf(stock + ("movement" to "PLACED")))),
                "CHECKLIST_ACTION_INVALID",
                "sections[0].items[0].actions[0].movement",
            )
            refused(
                checklists.item("x", "X", QUANTITY, mapOf("actions" to listOf(stock + ("defaultQuantity" to -1)))),
                "CHECKLIST_ACTION_INVALID",
                "sections[0].items[0].actions[0].defaultQuantity",
            )

            val created =
                call(
                    POST,
                    BASE,
                    asAdmin(),
                    checklists.templateBody(
                        sections =
                            listOf(
                                checklists.section(
                                    "S",
                                    checklists.item(
                                        "soap",
                                        "Sabonetes",
                                        QUANTITY,
                                        mapOf("actions" to listOf(stock, linen)),
                                    ),
                                    checklists.item("chair", "Cadeira", CHECK, mapOf("actions" to listOf(occurrence))),
                                ),
                            ),
                    ),
                ).expectStatus(201).shouldNotBeNull()
            val items = created["sections"][0]["items"].values().toList()
            items[0]["actions"].values().map { it["type"].asString() } shouldContainExactly
                listOf("CONSUME_STOCK", "LINEN_EXCHANGE")
            items[0]["actions"][0]["defaultQuantity"].decimalValue().compareTo(2.toBigDecimal()) shouldBe 0
            items[0]["actions"][0]["linenTypeId"].isNull shouldBe true
            items[1]["actions"][0]["category"].asString() shouldBe "FURNITURE"
        }

    // --- Aplicabilidade e API pública ----------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // a escolha em cada combinação de atributos e depois de uma nova versão
    fun `resolveTemplate picks the published template whose applicability matches, otherwise the general one`(): Unit =
        with(iam) {
            // O contexto EVENT é só deste teste; os modelos de outras execuções no mesmo banco saem do caminho.
            jdbc.sql("UPDATE checklists.checklist_template SET active = false WHERE context = 'EVENT'").update()
            val suite = UUID.randomUUID()
            val buffet = UUID.randomUUID()
            val hall = UUID.randomUUID()
            val subjectSuite = SubjectAttributes(roomTypeId = suite)

            checklistsApi.resolveTemplate(ChecklistContext.EVENT, subjectSuite).shouldBeNull()

            val general = checklists.create(checklists.templateBody(context = ChecklistContext.EVENT))
            val suites = checklists.create(eventTemplate(listOf(mapOf("roomTypeIds" to listOf(suite)))))
            val suiteBuffet =
                checklists.create(
                    eventTemplate(
                        listOf(
                            mapOf(
                                "roomTypeIds" to listOf(suite),
                                "cleaningTypeIds" to listOf(buffet),
                            ),
                        ),
                    ),
                )
            // Só rascunho (nunca publicado) e desativado: não valem.
            checklists.create(eventTemplate(listOf(mapOf("areaIds" to listOf(hall)))))
            val inactive = checklists.create(eventTemplate(listOf(mapOf("assetIds" to listOf(hall)))))
            // Antes de publicar, nenhum vale.
            checklistsApi.resolveTemplate(ChecklistContext.EVENT, subjectSuite).shouldBeNull()
            listOf(general, suites, suiteBuffet, inactive).forEach { checklists.publish(it) }
            call(POST, "$BASE/$inactive/deactivate", asAdmin()).ok()

            fun resolved(subject: SubjectAttributes) =
                checklistsApi.resolveTemplate(ChecklistContext.EVENT, subject)?.templateId?.value

            assertSoftly {
                resolved(subjectSuite) shouldBe suites
                resolved(SubjectAttributes(roomTypeId = suite, cleaningTypeId = buffet)) shouldBe suiteBuffet
                resolved(SubjectAttributes(roomTypeId = UUID.randomUUID())) shouldBe general
                resolved(SubjectAttributes(areaId = hall)) shouldBe general
                resolved(SubjectAttributes(assetId = hall)) shouldBe general
                resolved(SubjectAttributes()) shouldBe general
            }

            // Uma nova versão publicada passa a ser a escolhida; a anterior continua legível.
            val etag = checklists.etagOf(suites)
            val edited =
                eventTemplate(
                    listOf(mapOf("roomTypeIds" to listOf(suite))),
                    sections =
                        listOf(
                            checklists.section(
                                "Montagem",
                                checklists.item("chairs", "Cadeiras"),
                                checklists.item("stage", "Palco"),
                            ),
                        ),
                ) - "context"
            call(PUT, "$BASE/$suites", asAdmin(), edited, ifMatch = etag).ok()
            checklistsApi.resolveTemplate(ChecklistContext.EVENT, subjectSuite).shouldNotBeNull().number shouldBe 1
            checklists.publish(suites) shouldBe 2
            val current = checklistsApi.resolveTemplate(ChecklistContext.EVENT, subjectSuite).shouldNotBeNull()
            assertSoftly {
                current.number shouldBe 2
                current.context shouldBe ChecklistContext.EVENT
                current.content.items.map { it.key } shouldContainExactly listOf("chairs", "stage")
                checklistsApi.version(ChecklistTemplateId(suites), 1).shouldNotBeNull().content.items.map {
                    it.key
                } shouldContainExactly
                    listOf("bed", "towels")
                checklistsApi.version(current.id) shouldBe current
                checklistsApi.version(ChecklistTemplateId(suites), 3).shouldBeNull()
            }
        }

    @Test
    fun `the role templates of SPEC 3_1 get the checklist template permissions`() {
        val housekeeping = listOf(RoleTemplate.MANAGER, RoleTemplate.HOUSEKEEPER)
        val executors =
            housekeeping + listOf(RoleTemplate.ROOM_ATTENDANT, RoleTemplate.MAINTENANCE, RoleTemplate.WAITER)
        val executions = setOf(ChecklistsPermissions.EXECUTIONS_READ, ChecklistsPermissions.EXECUTIONS_EXECUTE)
        assertSoftly {
            ChecklistsPermissions.permissions.map { it.code } shouldContainExactly
                listOf(
                    ChecklistsPermissions.TEMPLATES_READ,
                    ChecklistsPermissions.TEMPLATES_MANAGE,
                    ChecklistsPermissions.EXECUTIONS_READ,
                    ChecklistsPermissions.EXECUTIONS_EXECUTE,
                    ChecklistsPermissions.ROUTINES_READ,
                    ChecklistsPermissions.ROUTINES_MANAGE,
                )
            ChecklistsPermissions.permissions.forEach {
                it.sensitive shouldBe false
                if (it.code in executions) {
                    // M2 E07: o executor (e o candidato da rotina) com OWN; governanta e gerente com ALL.
                    it.templates.keys shouldContainExactlyInAnyOrder executors
                    it.scopes shouldBe setOf(OWN, ALL)
                    it.templates
                        .filterKeys { key -> key !in housekeeping }
                        .values
                        .map { grant -> grant.scope }
                        .toSet() shouldBe setOf(OWN)
                } else {
                    it.templates.keys shouldContainExactlyInAnyOrder housekeeping
                    it.scopes shouldBe setOf(ALL)
                }
            }
        }
    }

    // --- Apoio ---------------------------------------------------------------------------------------

    private fun eventTemplate(
        applicability: List<Map<String, Any?>>,
        sections: List<Map<String, Any?>>? = null,
    ): Map<String, Any?> =
        if (sections == null) {
            checklists.templateBody(context = ChecklistContext.EVENT, applicability = applicability)
        } else {
            checklists.templateBody(
                context = ChecklistContext.EVENT,
                applicability = applicability,
                sections = sections,
            )
        }

    private fun condition(
        itemKey: String,
        answer: String,
    ) = mapOf("visibleWhen" to mapOf("itemKey" to itemKey, "answer" to answer))

    private fun keys(body: JsonNode): List<String> =
        body["sections"].values().flatMap { section -> section["items"].values().map { it["key"].asString() } }

    private fun listIds(path: String): List<String> =
        with(iam) { call(GET, "$path&limit=200", asAdmin()).ok()["items"].values().map { it["id"].asString() } }

    /** A linha inteira da versão, como o banco a guarda. */
    private fun versionRow(
        templateId: UUID,
        number: Int,
    ): String =
        jdbc
            .sql(
                "SELECT to_jsonb(v)::text FROM checklists.checklist_version v " +
                    "WHERE template_id = :id AND number = :number",
            ).param("id", templateId)
            .param("number", number)
            .query(String::class.java)
            .single()

    private fun auditActions(
        entityType: String,
        entityId: UUID,
    ): List<String> =
        jdbc
            .sql(
                "SELECT action FROM audit.audit_entry WHERE entity_type = :type AND entity_id = :id " +
                    "ORDER BY occurred_at, id",
            ).param("type", entityType)
            .param("id", entityId.toString())
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun causes(error: Throwable): List<String> =
        generateSequence(error) {
            it.cause
        }.map { it.message.orEmpty() }.toList()

    private companion object {
        const val BASE = "/api/v1/checklist-templates"
    }
}
