package com.zeny.hotel.guests

import com.zeny.hotel.guests.GuestsTestSupport.Companion.definition
import com.zeny.hotel.guests.GuestsTestSupport.Companion.formattedCpf
import com.zeny.hotel.guests.GuestsTestSupport.Companion.newCpf
import com.zeny.hotel.guests.application.GuestMergeService
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.modulith.test.EnableScenarios
import org.springframework.modulith.test.Scenario
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.function.Supplier
import kotlin.concurrent.thread

/** Os eventos `GuestsMerged` recebidos pelo listener de teste, como as reservas (M4) os receberiam. */
object GuestsMergedEvents {
    val received: MutableList<GuestsMerged> = CopyOnWriteArrayList()
}

/** Listener assíncrono e transacional de teste: com ele, a publicação passa pelo outbox. */
open class GuestsMergedProbeListener {
    @ApplicationModuleListener
    open fun on(event: GuestsMerged) {
        GuestsMergedEvents.received += event
    }
}

@TestConfiguration(proxyBeanMethods = false)
class GuestsMergedListenerConfiguration {
    @Bean
    fun guestsMergedProbeListener() = GuestsMergedProbeListener()
}

/**
 * Detecção e mesclagem de duplicados pela API (M3 E02, RF-HOS-05, H6, H8, H10): candidatos por documento (índice
 * cego) e por e-mail; mesclagem com a permissão própria e `If-Match`, com as regras decididas pelo usuário; o absorvido
 * fica mesclado, com a referência ao sobrevivente, e nenhuma linha é apagada; recusas `409` com código estável (o
 * mesmo perfil, já mesclado, documento em conflito, duas mesclagens simultâneas); trilha com o estado anterior dos dois
 * sem dado pessoal; `GuestsMerged` pelo outbox na transação da mesclagem (e nada com ela desfeita).
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, GuestsMergedListenerConfiguration::class)
@Suppress("LargeClass") // cenários de ponta a ponta da detecção, da mesclagem e do evento
class GuestMergeApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val guestsApi: GuestsApi,
    @Autowired private val merges: GuestMergeService,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val jsonMapper: JsonMapper,
) {
    private val guests = GuestsTestSupport(iam)
    private val read = TestGrant(definition(GuestsPermissions.PROFILES_READ), ALL)
    private val manage = TestGrant(definition(GuestsPermissions.PROFILES_MANAGE), ALL)
    private val merge = TestGrant(definition(GuestsPermissions.PROFILES_MERGE), ALL)

    // --- Candidatos -----------------------------------------------------------------------------------------

    @Test
    fun `duplicate candidates are the pairs with the same document or e-mail, each pair once, page by page`(): Unit =
        with(iam) {
            val reader = createUserWith(read)
            val cpf = newCpf()
            val email = guests.uniqueEmail()
            val a = create(email = email, documents = listOf(guests.document("CPF", cpf)))
            val b = create(documents = listOf(guests.document("CPF", formattedCpf(cpf))))
            val c = create(email = email.uppercase())
            val unrelated = create()
            // O mesmo número noutro país não é o mesmo documento.
            val passport = "AB${guests.suffix(7)}"
            val d = create(documents = listOf(guests.document("PASSPORT", passport, "AR")))
            val e = create(documents = listOf(guests.document("PASSPORT", passport, "PY")))

            val pairs = candidates(a, asTestUser(reader))
            pairs.map { pairOf(it) } shouldContainExactlyInAnyOrder listOf(sorted(a, b), sorted(a, c))
            val byDocument = pairs.single { pairOf(it) == sorted(a, b) }
            byDocument["reasons"].values().map { it.asString() } shouldContainExactly listOf("DOCUMENT")
            byDocument["documentTypes"].values().map { it.asString() } shouldContainExactly listOf("CPF")
            byDocument["first"]["documents"][0]["maskedNumber"].asString() shouldBe "***" + cpf.takeLast(4)
            byDocument.toString() shouldNotContain cpf
            val byEmail = pairs.single { pairOf(it) == sorted(a, c) }
            byEmail["reasons"].values().map { it.asString() } shouldContainExactly listOf("EMAIL")
            byEmail["documentTypes"].values().shouldBeEmpty()
            candidates(unrelated, asTestUser(reader)).shouldBeEmpty()
            candidates(d, asTestUser(reader)).shouldBeEmpty()
            candidates(e, asTestUser(reader)).shouldBeEmpty()

            // Página a página, em ordem dos ids.
            val first = call(GET, "/api/v1/guests/duplicate-candidates?guestId=$a&limit=1", asTestUser(reader)).ok()
            first["items"].size() shouldBe 1
            val second =
                call(
                    GET,
                    "/api/v1/guests/duplicate-candidates?guestId=$a&limit=1&cursor=${first["nextCursor"].asString()}",
                    asTestUser(reader),
                ).ok()
            second["items"].size() shouldBe 1
            second["nextCursor"].isNull shouldBe true
            listOf(pairOf(first["items"][0]), pairOf(second["items"][0])) shouldBe
                listOf(sorted(a, b), sorted(a, c)).sortedBy { it.first.toString() + it.second.toString() }

            // A listagem geral também traz os pares (o banco é compartilhado: confere só os deste teste).
            val all = allCandidates(asTestUser(reader))
            all.filter { a in it.toList() } shouldContainExactlyInAnyOrder listOf(sorted(a, b), sorted(a, c))

            call(GET, "/api/v1/guests/duplicate-candidates?guestId=$a", asTestUser(createUser()))
                .problem(403, "PERMISSION_DENIED")
            call(GET, "/api/v1/guests/duplicate-candidates?guestId=${UUID.randomUUID()}", asTestUser(reader))
                .problem(404, "GUEST_NOT_FOUND")
        }

    // --- Mesclagem ------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // a mesclagem inteira: permissão, regras, absorvido, linhas e leituras
    fun `a merge keeps the survivor, marks the merged profile with the survivor and deletes nothing`(): Unit =
        with(iam) {
            val editor = createUserWith(read, manage)
            val merger = createUserWith(read, merge)
            val cpf = newCpf()
            val email = guests.uniqueEmail()
            val passport = "AB${guests.suffix(7)}"
            val survivor =
                create(
                    email = null,
                    name = guests.uniqueName("Survivor"),
                    phone = SURVIVOR_PHONE,
                    documents = listOf(guests.document("CPF", cpf)),
                )
            val merged =
                create(
                    email = email,
                    vip = true,
                    phone = MERGED_PHONE,
                    documents =
                        listOf(guests.document("CPF", formattedCpf(cpf)), guests.document("PASSPORT", passport, "AR")),
                )
            call(POST, "/api/v1/guests/$merged/restrict", asAdmin(), mapOf("reason" to "Danos ao quarto")).ok()
            val passportId = documentIds(merged).getValue("PASSPORT")
            val mergedCpfId = documentIds(merged).getValue("CPF")
            val survivorName = name(survivor)
            val body = mapOf("mergedGuestId" to merged, "takeFromMerged" to listOf("PHONE"))
            val path = "/api/v1/guests/$survivor/merge"
            val etag = guests.etagOf("/api/v1/guests/$survivor")

            call(POST, path, asTestUser(editor), body, ifMatch = etag).problem(403, "PERMISSION_DENIED")
            call(POST, path, asTestUser(merger), body).problem(428, "PRECONDITION_REQUIRED")
            call(POST, path, asTestUser(merger), body, ifMatch = "\"9\"").problem(412, "PRECONDITION_FAILED")
            call(POST, path, asTestUser(merger), mapOf("takeFromMerged" to emptyList<String>()), ifMatch = etag)
                .problem(400, "VALIDATION_FAILED")
            call(
                POST,
                path,
                asTestUser(merger),
                mapOf("mergedGuestId" to UUID.randomUUID()),
                ifMatch = etag,
            ).problem(404, "GUEST_NOT_FOUND")

            val response = call(POST, path, asTestUser(merger), body, ifMatch = etag)
            val result = response.ok()
            response.getHeader(HttpHeaders.ETAG) shouldBe "\"1\""
            result["id"].asString() shouldBe survivor.toString()
            result["status"].asString() shouldBe "ACTIVE"
            result["mergedInto"].isNull shouldBe true
            result["fullName"].asString() shouldBe survivorName
            // Listado em takeFromMerged: o telefone do absorvido substitui o do sobrevivente.
            result["phone"].asString() shouldBe MERGED_PHONE
            // Vazio no sobrevivente: vem do absorvido; VIP e restrição de qualquer um ficam.
            result["email"].asString() shouldBe email
            result["vip"].asBoolean() shouldBe true
            result["restriction"]["reason"].asString() shouldBe "Danos ao quarto"
            // O CPF igual fica uma vez; o passaporte passa, com o mesmo id.
            result["documents"].values().map {
                it["type"].asString() to it["id"].asString()
            } shouldContainExactlyInAnyOrder
                listOf("CPF" to documentIds(survivor).getValue("CPF").toString(), "PASSPORT" to passportId.toString())

            // O absorvido continua legível, mesclado, com a referência ao sobrevivente e o CPF igual.
            val absorbed = call(GET, "/api/v1/guests/$merged", asTestUser(merger)).ok()
            absorbed["status"].asString() shouldBe "MERGED"
            absorbed["mergedInto"].asString() shouldBe survivor.toString()
            absorbed["mergedAt"].isNull shouldBe false
            absorbed["documents"].values().map { it["id"].asString() } shouldContainExactly
                listOf(mergedCpfId.toString())

            // Nenhuma linha apagada: os dois perfis e os três documentos continuam no banco.
            countRows("SELECT count(*) FROM guests.guest WHERE id IN (:ids)", listOf(survivor, merged)) shouldBe 2
            countRows(
                "SELECT count(*) FROM guests.guest_document WHERE guest_id IN (:ids)",
                listOf(survivor, merged),
            ) shouldBe 3
            jdbc
                .sql("SELECT status, merged_into, merged_by FROM guests.guest WHERE id = :id")
                .param("id", merged)
                .query()
                .singleRow() shouldBe
                mapOf("status" to "MERGED", "merged_into" to survivor, "merged_by" to merger.id.value)

            // O mesclado não volta nas buscas nem nos candidatos, e não é mais editado.
            call(GET, "/api/v1/guests?email=$email", asTestUser(merger)).ok()["items"].values().map {
                it["id"].asString()
            } shouldContainExactly listOf(survivor.toString())
            call(
                POST,
                "/api/v1/guests/search-by-document",
                asTestUser(merger),
                guests.document("CPF", cpf),
            ).ok()["items"].values().map { it["id"].asString() } shouldContainExactly listOf(survivor.toString())
            candidates(survivor, asTestUser(merger)).shouldBeEmpty()
            call(
                PUT,
                "/api/v1/guests/$merged",
                asAdmin(),
                guests.profileBody(),
                ifMatch = guests.etagOf("/api/v1/guests/$merged"),
            ).problem(409, "GUEST_ALREADY_MERGED")
            // Com o ETag de antes da mesclagem: 409 (o motivo), nunca 412.
            call(PUT, "/api/v1/guests/$merged", asAdmin(), guests.profileBody(), ifMatch = "\"0\"")
                .problem(409, "GUEST_ALREADY_MERGED")
            call(POST, "/api/v1/guests/$merged/documents", asAdmin(), guests.document("CPF", newCpf()))
                .problem(409, "GUEST_ALREADY_MERGED")

            // A API pública: o resumo do absorvido aponta o sobrevivente; os dados correntes são os dele.
            val summary = guestsApi.guest(GuestId(merged)).shouldNotBeNull()
            summary.status shouldBe GuestStatus.MERGED
            summary.mergedInto shouldBe GuestId(survivor)
            guestsApi.restrictionAlert(GuestId(merged)).shouldNotBeNull().guestId shouldBe GuestId(survivor)
            guestsApi.fnrhData(GuestId(merged)).shouldNotBeNull().id shouldBe GuestId(survivor)
            guestsApi.findByDocument(DocumentType.CPF, null, cpf).map { it.id } shouldContainExactly
                listOf(GuestId(survivor))
        }

    @Test
    fun `a profile is not merged with itself, twice, into a merged one or with a conflicting document`(): Unit =
        with(iam) {
            val survivor = create()
            val merged = create()
            val other = create()

            mergeCall(survivor, survivor).problem(409, "GUEST_MERGE_SAME_PROFILE")
            mergeCall(survivor, merged).ok()
            // De novo, noutro sobrevivente, ou o mesclado como sobrevivente: 409, sem efeito.
            mergeCall(survivor, merged).problem(409, "GUEST_ALREADY_MERGED")
            mergeCall(other, merged).problem(409, "GUEST_ALREADY_MERGED")
            mergeCall(merged, other).problem(409, "GUEST_ALREADY_MERGED")
            status(other) shouldBe "ACTIVE"
            mergedInto(merged) shouldBe survivor

            // Mesmo tipo e país com números diferentes: recusado, nada muda.
            val first = create(documents = listOf(guests.document("CPF", newCpf())))
            val second = create(documents = listOf(guests.document("CPF", newCpf())))
            val before = guests.etagOf("/api/v1/guests/$first")
            mergeCall(first, second).problem(409, "GUEST_MERGE_DOCUMENT_CONFLICT")
            status(second) shouldBe "ACTIVE"
            guests.etagOf("/api/v1/guests/$first") shouldBe before
            documentIds(second).keys shouldBe setOf("CPF")
        }

    @Test
    fun `two simultaneous merges of the same profile - one is applied, the other gets 409`(): Unit =
        with(iam) {
            val merged = create()
            val survivors = List(2) { create() }
            val start = CountDownLatch(1)
            val statuses = CopyOnWriteArrayList<Int>()
            val codes = CopyOnWriteArrayList<String>()
            val threads =
                survivors.map { survivor ->
                    thread {
                        start.await()
                        val response =
                            call(
                                POST,
                                "/api/v1/guests/$survivor/merge",
                                asAdmin(),
                                mapOf("mergedGuestId" to merged),
                                ifMatch = "\"0\"",
                            )
                        statuses += response.status
                        if (response.status == 409) codes += json(response)["code"].asString()
                    }
                }
            start.countDown()
            threads.forEach { it.join() }

            statuses shouldContainExactlyInAnyOrder listOf(200, 409)
            codes shouldContainExactly listOf("GUEST_ALREADY_MERGED")
            survivors shouldContain mergedInto(merged).shouldNotBeNull()
            survivors.map(::status) shouldBe listOf("ACTIVE", "ACTIVE")
        }

    // --- Trilha ---------------------------------------------------------------------------------------------

    @Test
    fun `the merge is audited with the previous state of both profiles and no personal data`(): Unit =
        with(iam) {
            val cpf = newCpf()
            val email = guests.uniqueEmail()
            val name = guests.uniqueName("Auditada")
            val survivor = create(email = null)
            val merged =
                create(
                    name = name,
                    email = email,
                    documents = listOf(guests.document("PASSPORT", "ZX${guests.suffix(7)}", "AR")),
                )
            call(POST, "/api/v1/guests/$survivor/documents", asAdmin(), guests.document("CPF", cpf)).ok()
            call(POST, "/api/v1/guests/$merged/restrict", asAdmin(), mapOf("reason" to "Motivo sigiloso")).ok()
            val passportId = documentIds(merged).getValue("PASSPORT")

            mergeCall(survivor, merged, take = listOf("FULL_NAME")).ok()

            val entries = auditEntries(listOf(survivor, merged, passportId))
            entries.map { it["entity_id"] }.toSet() shouldBe
                setOf(survivor.toString(), merged.toString(), passportId.toString())
            entries.all { it["user_id"] == iam.adminId() } shouldBe true
            val survivorEntry = entries.single { it["entity_id"] == survivor.toString() }
            val survivorBefore = tree(survivorEntry["before_state"])
            val survivorAfter = tree(survivorEntry["after_state"])
            survivorBefore["restricted"].asBoolean() shouldBe false
            survivorAfter["restricted"].asBoolean() shouldBe true
            survivorAfter["absorbed"].asString() shouldBe merged.toString()
            survivorAfter["changedFields"].values().map { it.asString() } shouldContainExactlyInAnyOrder
                listOf("fullName", "email", "restricted", "restrictionReason", "documents")
            val mergedEntry = entries.single { it["entity_id"] == merged.toString() }
            tree(mergedEntry["before_state"])["status"].asString() shouldBe "ACTIVE"
            tree(mergedEntry["before_state"])["documents"].size() shouldBe 1
            tree(mergedEntry["after_state"])["status"].asString() shouldBe "MERGED"
            tree(mergedEntry["after_state"])["mergedInto"].asString() shouldBe survivor.toString()
            val documentEntry = entries.single { it["entity_id"] == passportId.toString() }
            tree(documentEntry["before_state"])["guestId"].asString() shouldBe merged.toString()
            tree(documentEntry["after_state"])["guestId"].asString() shouldBe survivor.toString()

            val trail = entries.joinToString("\n") { "${it["before_state"]} ${it["after_state"]}" }
            listOf(name, email, cpf, "Motivo sigiloso", "Conceição").forEach { trail shouldNotContain it }
        }

    // --- Evento ---------------------------------------------------------------------------------------------

    @Test
    fun `GuestsMerged goes through the outbox in the transaction of the merge, with only ids`(scenario: Scenario) {
        val survivor = create()
        val merged = create()
        val admin = iam.adminId()

        scenario
            .stimulate(
                Supplier {
                    authenticatedAs(admin) { merges.merge(GuestId(survivor), GuestId(merged), 0, emptySet()) }
                },
            ).andWaitForEventOfType(GuestsMerged::class.java)
            .matching { it.mergedId == merged }
            .toArriveAndVerify { event, view ->
                event.survivorId shouldBe survivor
                event.mergedAt shouldBe view.updatedAt
            }
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until {
            GuestsMergedEvents.received.any { it.mergedId == merged }
        }
        val serialized = publications(merged)
        serialized.size shouldBe 1
        jsonMapper.readTree(serialized.single()).propertyNames().toSet() shouldBe
            setOf("survivorId", "mergedId", "mergedAt")
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until { publicationStatus(merged) == "COMPLETED" }
    }

    @Test
    fun `a merge rolled back leaves no publication, no trail entry and both profiles active`() {
        val survivor = create()
        val merged = create()
        val admin = iam.adminId()

        authenticatedAs(admin) {
            transactions.executeWithoutResult { status ->
                merges.merge(GuestId(survivor), GuestId(merged), 0, emptySet())
                // Dentro da transação o absorvido já está mesclado e a publicação já está registrada.
                publications(merged).size shouldBe 1
                status.setRollbackOnly()
            }
        }

        publications(merged).shouldBeEmpty()
        status(merged) shouldBe "ACTIVE"
        mergedInto(merged).shouldBeNull()
        auditEntries(listOf(survivor, merged)).shouldBeEmpty()
    }

    @Test
    fun `only the manager template gets the merge permission`() {
        GuestsPermissions.permissions
            .single { it.code == GuestsPermissions.PROFILES_MERGE }
            .templates
            .keys shouldBe setOf(RoleTemplate.MANAGER)
    }

    // --- Apoio ----------------------------------------------------------------------------------------------

    private fun create(
        email: String? = guests.uniqueEmail(),
        name: String = guests.uniqueName(),
        vip: Boolean = false,
        phone: String? = null,
        documents: List<Map<String, Any?>> = emptyList(),
    ): UUID {
        val profile = guests.profileBody(fullName = name, email = email, extra = mapOf("vip" to vip, "phone" to phone))
        return UUID.fromString(
            guests.createGuest(guests.createBody(profile = profile, documents = documents))["id"].asString(),
        )
    }

    private fun mergeCall(
        survivor: UUID,
        merged: UUID,
        take: List<String> = emptyList(),
    ) = iam.call(
        POST,
        "/api/v1/guests/$survivor/merge",
        iam.asAdmin(),
        mapOf("mergedGuestId" to merged, "takeFromMerged" to take),
        ifMatch = guests.etagOf("/api/v1/guests/$survivor"),
    )

    private fun candidates(
        guestId: UUID,
        auth: RequestPostProcessor,
    ): List<JsonNode> =
        with(iam) {
            call(GET, "/api/v1/guests/duplicate-candidates?guestId=$guestId", auth).ok()["items"].values().toList()
        }

    /** Todos os pares da listagem geral, página a página. */
    private fun allCandidates(auth: RequestPostProcessor): List<Pair<UUID, UUID>> =
        with(iam) {
            val pairs = mutableListOf<Pair<UUID, UUID>>()
            var cursor: String? = null
            do {
                val page =
                    call(
                        GET,
                        "/api/v1/guests/duplicate-candidates?limit=200" + cursor?.let { "&cursor=$it" }.orEmpty(),
                        auth,
                    ).ok()
                pairs += page["items"].values().map { pairOf(it) }
                cursor = page["nextCursor"].takeUnless { it.isNull }?.asString()
            } while (cursor != null)
            pairs
        }

    private fun pairOf(item: JsonNode): Pair<UUID, UUID> =
        UUID.fromString(item["first"]["id"].asString()) to UUID.fromString(item["second"]["id"].asString())

    private fun sorted(
        a: UUID,
        b: UUID,
    ): Pair<UUID, UUID> = if (a.toString() < b.toString()) a to b else b to a

    private fun documentIds(guest: UUID): Map<String, UUID> =
        with(iam) {
            call(GET, "/api/v1/guests/$guest", asAdmin()).ok()["documents"].values().associate {
                it["type"].asString() to UUID.fromString(it["id"].asString())
            }
        }

    private fun name(guest: UUID): String =
        with(iam) { call(GET, "/api/v1/guests/$guest", asAdmin()).ok()["fullName"].asString() }

    private fun status(guest: UUID): String =
        jdbc
            .sql("SELECT status FROM guests.guest WHERE id = :id")
            .param("id", guest)
            .query(String::class.java)
            .single()

    private fun mergedInto(guest: UUID): UUID? =
        jdbc
            .sql("SELECT merged_into FROM guests.guest WHERE id = :id")
            .param("id", guest)
            .query(UUID::class.java)
            .optional()
            .orElse(null)

    private fun countRows(
        sql: String,
        ids: List<UUID>,
    ): Int =
        jdbc
            .sql(sql)
            .param("ids", ids)
            .query(Int::class.java)
            .single()

    private fun auditEntries(ids: List<UUID>): List<Map<String, Any?>> =
        jdbc
            .sql(
                "SELECT entity_id, user_id, CAST(before_state AS text) AS before_state, " +
                    "CAST(after_state AS text) AS after_state FROM audit.audit_entry " +
                    "WHERE action = 'guests.guest.merge' AND entity_id IN (:ids) ORDER BY occurred_at",
            ).param("ids", ids.map { it.toString() })
            .query()
            .listOfRows()

    private fun tree(json: Any?): JsonNode = jsonMapper.readTree(json as String)

    private fun publications(merged: UUID): List<String> =
        jdbc
            .sql(
                "SELECT serialized_event FROM shared.event_publication " +
                    "WHERE event_type = :type AND serialized_event LIKE :merged AND listener_id LIKE :listener",
            ).param("type", GuestsMerged::class.java.name)
            .param("merged", "%\"mergedId\":\"$merged\"%")
            .param("listener", "%GuestsMergedProbeListener%")
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun publicationStatus(merged: UUID): String? =
        jdbc
            .sql(
                "SELECT status FROM shared.event_publication " +
                    "WHERE event_type = :type AND serialized_event LIKE :merged AND listener_id LIKE :listener",
            ).param("type", GuestsMerged::class.java.name)
            .param("merged", "%\"mergedId\":\"$merged\"%")
            .param("listener", "%GuestsMergedProbeListener%")
            .query(String::class.java)
            .optional()
            .orElse(null)

    private fun <T> authenticatedAs(
        userId: UUID,
        block: () -> T,
    ): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(userId.toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 15L
        const val SURVIVOR_PHONE = "+55 11 3333-1111"
        const val MERGED_PHONE = "+55 21 4444-2222"
    }
}
