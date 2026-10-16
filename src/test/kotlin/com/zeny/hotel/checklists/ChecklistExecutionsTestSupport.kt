package com.zeny.hotel.checklists

import com.zeny.hotel.files.FilesTestConfiguration
import com.zeny.hotel.files.FilesTestSupport
import com.zeny.hotel.files.TestImages
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Os `ChecklistCompleted` recebidos pelo listener de teste, como um módulo consumidor os receberia. */
object ChecklistCompletedEvents {
    val received: MutableList<ChecklistCompleted> = CopyOnWriteArrayList()
}

/** Listener assíncrono e transacional de teste: com ele, a publicação passa pelo outbox (e pode ser contada). */
open class ChecklistCompletedProbeListener {
    @ApplicationModuleListener
    open fun on(event: ChecklistCompleted) {
        ChecklistCompletedEvents.received += event
    }
}

/** O contexto dos testes de execução: apoio do `iam`, dos anexos e o listener de `ChecklistCompleted`. */
@TestConfiguration(proxyBeanMethods = false)
@Import(IamTestConfiguration::class, FilesTestConfiguration::class)
class ChecklistExecutionsTestConfiguration {
    @Bean
    fun checklistCompletedProbeListener() = ChecklistCompletedProbeListener()
}

/** Um comando offline do teste. */
data class SyncCommand(
    val id: UUID,
    val type: String,
    val payload: Map<String, Any?>,
    val occurredAt: Instant,
    val deviceClock: Instant,
) {
    fun body() =
        mapOf(
            "commandId" to id,
            "type" to type,
            "payload" to payload,
            "occurredAt" to occurredAt.toString(),
            "deviceClock" to deviceClock.toString(),
        )
}

/**
 * Apoio dos testes de execução: um modelo publicado com os sete tipos (obrigatórios, condicional e ações), os
 * usuários executores, as chamadas à API e à sincronização, e as fotos.
 */
class ChecklistExecutionsTestSupport(
    private val iam: IamTestSupport,
    private val files: FilesTestSupport,
    private val jdbc: JdbcClient,
) {
    val templates = ChecklistsTestSupport(iam)

    /**
     * As seções do modelo padrão: `bed` (marcação, obrigatório), `shower` (marcação, obrigatório, abre OS no "não
     * ok"), `shower-detail` (texto, obrigatório, só com `shower` = `NOT_OK`), `towels` (quantidade, baixa de estoque
     * e troca de enxoval), `temperature` (número), `minibar` (múltipla), `cleaning` (única) e `photo` (foto,
     * opcional).
     */
    @Suppress("LongMethod") // o modelo com os sete tipos, a condição e as três ações
    fun defaultSections(
        photoRequired: Boolean = false,
        stockItem: UUID = STOCK_ITEM,
        linenType: UUID = LINEN_TYPE,
    ): List<Map<String, Any?>> =
        with(templates) {
            listOf(
                section(
                    "Quarto",
                    item("bed", "Cama feita", extra = mapOf("required" to true)),
                    item(
                        "shower",
                        "Chuveiro funciona",
                        extra =
                            mapOf(
                                "required" to true,
                                "actions" to
                                    listOf(
                                        mapOf(
                                            "type" to "OPEN_OCCURRENCE",
                                            "occurrenceKind" to "MAINTENANCE",
                                            "category" to "PLUMBING",
                                        ),
                                    ),
                            ),
                    ),
                    item(
                        "shower-detail",
                        "O que há com o chuveiro",
                        ChecklistItemType.TEXT,
                        mapOf("required" to true, "visibleWhen" to mapOf("itemKey" to "shower", "answer" to "NOT_OK")),
                    ),
                ),
                section(
                    "Enxoval e frigobar",
                    item(
                        "towels",
                        "Toalhas trocadas",
                        ChecklistItemType.QUANTITY,
                        mapOf(
                            "unit" to "peças",
                            "actions" to
                                listOf(
                                    mapOf(
                                        "type" to "CONSUME_STOCK",
                                        "stockItemId" to stockItem,
                                        "defaultQuantity" to 2,
                                    ),
                                    mapOf(
                                        "type" to "LINEN_EXCHANGE",
                                        "linenTypeId" to linenType,
                                        "movement" to "PLACED",
                                    ),
                                ),
                        ),
                    ),
                    item("temperature", "Temperatura do frigobar", ChecklistItemType.NUMBER, mapOf("unit" to "°C")),
                    item(
                        "minibar",
                        "Itens repostos",
                        ChecklistItemType.MULTI_CHOICE,
                        mapOf("options" to listOf(option("water"), option("soda"), option("beer"))),
                    ),
                    item(
                        "cleaning",
                        "Tipo de limpeza",
                        ChecklistItemType.SINGLE_CHOICE,
                        mapOf("options" to listOf(option("light"), option("deep"))),
                    ),
                    item("photo", "Foto do quarto", ChecklistItemType.PHOTO, mapOf("required" to photoRequired)),
                ),
            )
        }

    /** Um modelo publicado (versão 1) com as [sections]; devolve o id do modelo e o da versão. */
    fun publishedTemplate(
        sections: List<Map<String, Any?>> = defaultSections(),
        context: ChecklistContext = ChecklistContext.HOUSEKEEPING,
    ): Pair<UUID, UUID> {
        val id = templates.create(templates.templateBody(context = context, sections = sections))
        templates.publish(id)
        return id to versionId(id, 1)
    }

    fun versionId(
        templateId: UUID,
        number: Int,
    ): UUID =
        jdbc
            .sql("SELECT id FROM checklists.checklist_version WHERE template_id = :id AND number = :number")
            .param("id", templateId)
            .param("number", number)
            .query(UUID::class.java)
            .single()

    /** Um usuário com executar e ler execuções no [scope] (e só isso). */
    fun executor(scope: PermissionScope = PermissionScope.OWN): TestUser =
        iam.createUserWith(
            TestGrant(ChecklistsTestSupport.definition(ChecklistsPermissions.EXECUTIONS_EXECUTE), scope),
            TestGrant(ChecklistsTestSupport.definition(ChecklistsPermissions.EXECUTIONS_READ), scope),
        )

    fun auth(user: TestUser): RequestPostProcessor = iam.asTestUser(user)

    fun start(
        auth: RequestPostProcessor,
        templateId: UUID,
        subject: Map<String, Any?>? = null,
    ): JsonNode =
        with(iam) {
            call(POST, EXECUTIONS, auth, mapOf("templateId" to templateId, "subject" to subject)).expectStatus(201)!!
        }

    fun get(
        auth: RequestPostProcessor,
        id: UUID,
    ): JsonNode = with(iam) { call(GET, "$EXECUTIONS/$id", auth).ok() }

    fun etag(
        auth: RequestPostProcessor,
        id: UUID,
    ): String = iam.call(GET, "$EXECUTIONS/$id", auth).getHeader(HttpHeaders.ETAG)!!

    fun answer(
        auth: RequestPostProcessor,
        id: UUID,
        vararg answers: Map<String, Any?>,
    ) = iam.call(PUT, "$EXECUTIONS/$id/answers", auth, mapOf("answers" to answers.toList()), etag(auth, id))

    fun complete(
        auth: RequestPostProcessor,
        id: UUID,
    ) = iam.call(POST, "$EXECUTIONS/$id/complete", auth)

    /** Uma foto JPEG de verdade, com EXIF e GPS, enviada como `checklists.photo` (com o `clientFileId`, se dado). */
    fun uploadPhoto(
        auth: RequestPostProcessor,
        clientFileId: UUID? = null,
        purpose: String = ChecklistsFilePurposes.PHOTO.value,
    ): UUID {
        val response =
            files.upload(
                purpose,
                auth,
                TestImages.jpeg(PHOTO_WIDTH, PHOTO_HEIGHT),
                "quarto.jpg",
                "image/jpeg",
                clientFileId?.toString(),
            )
        withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 201 }
        return UUID.fromString(files.json(response)["id"].asString())
    }

    fun fileRow(id: UUID): Map<String, Any?> = files.storedRow(id)

    fun storedObject(id: UUID): ByteArray = files.rawObject(fileRow(id)["object_key"] as String)

    fun sync(
        auth: RequestPostProcessor,
        vararg commands: SyncCommand,
    ): List<JsonNode> =
        with(iam) {
            call(POST, "/api/v1/sync/commands", auth, mapOf("commands" to commands.map { it.body() }))
                .ok()["results"]
                .values()
                .toList()
        }

    /** As publicações de `ChecklistCompleted` da execução no outbox (do listener de teste). */
    fun completedPublications(executionId: UUID): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM shared.event_publication WHERE event_type = :type " +
                    "AND serialized_event LIKE :execution AND listener_id LIKE '%ChecklistCompletedProbeListener%'",
            ).param("type", ChecklistCompleted::class.java.name)
            .param("execution", "%\"executionId\":\"$executionId\"%")
            .query(Int::class.java)
            .single()

    fun executionRow(id: UUID): Map<String, Any?> =
        jdbc
            .sql("SELECT * FROM checklists.checklist_execution WHERE id = :id")
            .param("id", id)
            .query()
            .singleRow()

    fun executionCount(id: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM checklists.checklist_execution WHERE id = :id OR duplicate_of = :id")
            .param("id", id)
            .query(Int::class.java)
            .single()

    companion object {
        const val EXECUTIONS = "/api/v1/checklist-executions"
        const val PHOTO_WIDTH = 3000
        const val PHOTO_HEIGHT = 1000
        val STOCK_ITEM: UUID = UuidV7.generate()
        val LINEN_TYPE: UUID = UuidV7.generate()

        fun option(value: String) = mapOf("value" to value, "label" to value.uppercase())
    }
}
