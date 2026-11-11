package com.zeny.hotel.guests

import com.zeny.hotel.files.FilesTestConfiguration
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Ataques da E15 (Task 4) à descrição de gênero na exportação do titular e na anonimização: cadeia de três perfis
 * (A absorve B, que absorveu C) cada um com a sua descrição, sem mistura; a anonimização apaga a cadeia inteira e a
 * reanonimização não mexe em nada; a trilha, inclusive a da aprovação de superior, nunca leva o valor; e o detector
 * de vazamento do marcador (eventos e notificações) é provado capaz de falhar.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, FilesTestConfiguration::class, GuestPrivacyTestConfiguration::class)
@Suppress("LongParameterList")
class GuestGenderDescriptionPrivacyTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val jsonMapper: JsonMapper,
) {
    private val guests = GuestsTestSupport(iam)
    private val read = TestGrant(GuestsTestSupport.definition(GuestsPermissions.PROFILES_READ), ALL)
    private val export = TestGrant(GuestsTestSupport.definition(GuestsPermissions.DATA_EXPORT), ALL)
    private val anonymize = TestGrant(GuestsTestSupport.definition(GuestsPermissions.DATA_ANONYMIZE), ALL)

    @Test
    fun `the export of a three-profile chain gives each profile its own gender description without mixing`(): Unit =
        with(iam) {
            val chain = chain()
            val requester = Requester(createUserWith(read))
            val path = "/api/v1/guests/${chain.a}/export"
            val required = call(POST, path, requester.auth).problem(403, "APPROVAL_REQUIRED")
            val approval = approve(requester, required, approverWith(export)).expectStatus(201)!!
            val response = call(POST, path, requester.auth, approvalToken = approval["approvalToken"].asString())
            val body = response.ok()

            response.getHeader(HttpHeaders.CACHE_CONTROL) shouldBe "no-store"
            body["guestIds"].values().map { it.asString() } shouldContainExactlyInAnyOrder
                listOf(chain.a, chain.b, chain.c).map { it.toString() }
            val profiles = body["sections"][0]["data"]["profiles"].values().associateBy { it["id"].asString() }
            profiles.getValue(chain.a.toString())["genderDescription"].asString() shouldBe DESC_A
            profiles.getValue(chain.b.toString())["genderDescription"].asString() shouldBe DESC_B
            profiles.getValue(chain.c.toString())["genderDescription"].asString() shouldBe DESC_C

            val trailText =
                auditByApproval("guests.guest.export", UUID.fromString(approval["approvalId"].asString()))
                    .joinToString(" ") { "${it["after_state"]}" }
            trailText.isNotBlank() shouldBe true
            listOf(DESC_A, DESC_B, DESC_C).forEach { withClue(it) { trailText shouldNotContain it } }
        }

    @Test
    fun `the export of a profile without a gender description gives a null one`(): Unit =
        with(iam) {
            val id = guestId(guests.createGuest(guests.createBody(profile = guests.profileBody())))
            val body = call(POST, "/api/v1/guests/$id/export", asAdmin()).ok()
            val profile = body["sections"][0]["data"]["profiles"][0]
            profile["id"].asString() shouldBe id.toString()
            profile["genderDescription"].let { it == null || it.isNull } shouldBe true
        }

    @Test
    fun `the anonymization erases the description of the whole chain, lists it in the trail without the value`(): Unit =
        with(iam) {
            val chain = chain()
            val all = listOf(chain.a, chain.b, chain.c)
            val requester = Requester(createUserWith(read))
            val path = "/api/v1/guests/${chain.a}/anonymize"
            val required = call(POST, path, requester.auth).problem(403, "APPROVAL_REQUIRED")
            val approval = approve(requester, required, approverWith(anonymize)).expectStatus(201)!!
            val approvalId = UUID.fromString(approval["approvalId"].asString())
            call(POST, path, requester.auth, approvalToken = approval["approvalToken"].asString()).ok()

            all.forEach { column(it, "gender_description").shouldBeNull() }
            all.forEach { column(it, "gender").shouldBeNull() }
            val entries = auditByApproval("guests.guest.anonymize", approvalId)
            entries
                .filter {
                    it["entity_type"] == "guests.guest"
                }.map { it["entity_id"] } shouldContainExactlyInAnyOrder
                all.map { it.toString() }
            entries.filter { it["entity_type"] == "guests.guest" }.forEach {
                tree(it["after_state"])["changedFields"].values().map { f -> f.asString() } shouldContain
                    "genderDescription"
            }
            val everything = entries.joinToString(" ") { "${it["after_state"]}" } + allAuditText(all)
            listOf(DESC_A, DESC_B, DESC_C).forEach { withClue(it) { everything shouldNotContain it } }

            // Reanonimizar a cadeia, e exportar depois: nada volta e nada se perde.
            call(POST, path, asAdmin()).problem(409, "GUEST_ANONYMIZED")
            all.forEach { column(it, "gender_description").shouldBeNull() }
            val exported = call(POST, path.replace("anonymize", "export"), asAdmin()).ok().toString()
            listOf(DESC_A, DESC_B, DESC_C).forEach { withClue(it) { exported shouldNotContain it } }
        }

    @Test
    fun `the leak detector of the marker test can fail, and the events it scans do exist`(): Unit =
        with(iam) {
            val survivor = guestId(guests.createGuest(guests.createBody(profile = markerBody())))
            val merged = guestId(guests.createGuest(guests.createBody(profile = markerBody(), documents = emptyList())))
            // A entrada chega ao perfil (leitura decifrada) e é legível pela API, logo o marcador está no sistema.
            call(GET, "/api/v1/guests/$survivor", asAdmin()).ok()["genderDescription"].asString() shouldBe MARKER
            call(
                POST,
                "/api/v1/guests/$survivor/merge",
                asAdmin(),
                mapOf("mergedGuestId" to merged),
                ifMatch = guests.etagOf("/api/v1/guests/$survivor"),
            ).ok()

            // A tabela varrida não está vazia: o evento da mesclagem do teste está nela, com os ids e sem o marcador.
            jdbc
                .sql("SELECT count(*) FROM shared.event_publication WHERE serialized_event LIKE :v")
                .param("v", "%$merged%")
                .query(Int::class.java)
                .single() shouldBe 1
            leaks("SELECT count(*) FROM shared.event_publication WHERE serialized_event LIKE :v") shouldBe 0

            // O detector enxerga um vazamento de verdade: uma publicação com o marcador faz a contagem subir.
            val planted = UUID.randomUUID()
            jdbc
                .sql(
                    "INSERT INTO shared.event_publication (id, listener_id, event_type, serialized_event, " +
                        "publication_date) VALUES (:id, 'probe', 'probe', :event, now())",
                ).param("id", planted)
                .param("event", "{\"x\":\"$MARKER\"}")
                .update()
            try {
                leaks("SELECT count(*) FROM shared.event_publication WHERE serialized_event LIKE :v") shouldBe 1
            } finally {
                jdbc.sql("DELETE FROM shared.event_publication WHERE id = :id").param("id", planted).update()
            }
            leaks("SELECT count(*) FROM shared.event_publication WHERE serialized_event LIKE :v") shouldBe 0
        }

    // --- Apoio ----------------------------------------------------------------------------------------------

    private data class Chain(
        val a: UUID,
        val b: UUID,
        val c: UUID,
    )

    private fun markerBody(): Map<String, Any?> =
        guests.profileBody(extra = mapOf("gender" to "OTHER", "genderDescription" to MARKER))

    private fun described(description: String): Map<String, Any?> =
        guests.profileBody(extra = mapOf("gender" to "OTHER", "genderDescription" to description))

    private fun chain(): Chain {
        val a = guestId(guests.createGuest(guests.createBody(profile = described(DESC_A))))
        val b = guestId(guests.createGuest(guests.createBody(profile = described(DESC_B), documents = emptyList())))
        val c = guestId(guests.createGuest(guests.createBody(profile = described(DESC_C), documents = emptyList())))
        with(iam) {
            mergeInto(b, c).ok()
            mergeInto(a, b).ok()
        }
        return Chain(a, b, c)
    }

    private fun mergeInto(
        survivor: UUID,
        merged: UUID,
    ): MockHttpServletResponse =
        iam.call(
            POST,
            "/api/v1/guests/$survivor/merge",
            iam.asAdmin(),
            mapOf("mergedGuestId" to merged),
            ifMatch = guests.etagOf("/api/v1/guests/$survivor"),
        )

    private fun guestId(node: JsonNode): UUID = UUID.fromString(node["id"].asString())

    private fun column(
        id: UUID,
        column: String,
    ): String? =
        jdbc
            .sql("SELECT CAST($column AS text) FROM guests.guest WHERE id = :id")
            .param("id", id)
            .query(String::class.java)
            .optional()
            .orElse(null)

    private fun leaks(sql: String): Int =
        jdbc
            .sql(sql)
            .param("v", "%$MARKER%")
            .query(Int::class.java)
            .single()

    private fun allAuditText(ids: List<UUID>): String =
        jdbc
            .sql(
                "SELECT coalesce(CAST(before_state AS text), '') || ' ' || coalesce(CAST(after_state AS text), '') " +
                    "FROM audit.audit_entry WHERE entity_id IN (:ids)",
            ).param("ids", ids.map { it.toString() })
            .query(String::class.java)
            .list()
            .joinToString(" ")

    private fun auditByApproval(
        action: String,
        approvalId: UUID,
    ): List<Map<String, Any?>> =
        jdbc
            .sql(
                "SELECT entity_type, entity_id, user_id, CAST(after_state AS text) AS after_state " +
                    "FROM audit.audit_entry WHERE action = :action AND approval_id = :id",
            ).param("action", action)
            .param("id", approvalId)
            .query()
            .listOfRows()

    private fun tree(json: Any?): JsonNode = jsonMapper.readTree(json as String)

    private inner class Requester(
        val user: TestUser,
        val sessionId: UUID = UuidV7.generate(),
    ) {
        val auth: RequestPostProcessor get() = asUser(user.id.value, sessionId)
    }

    private data class Approver(
        val user: TestUser,
        val pin: String,
    )

    private fun approverWith(vararg grants: TestGrant): Approver {
        val user = iam.createUserWith(*grants)
        val pin = (PIN_MIN..PIN_MAX).random().toString()
        iam.setPin(user, pin)
        return Approver(user, pin)
    }

    private fun approve(
        requester: Requester,
        required: JsonNode,
        approver: Approver,
    ): MockHttpServletResponse =
        iam.call(
            POST,
            "/api/v1/auth/approvals",
            requester.auth,
            mapOf(
                "action" to required["action"].asString(),
                "permission" to required["permission"].asString(),
                "resourceRef" to required["resourceRef"].asString(),
                "approverLogin" to approver.user.login,
                "approverPin" to approver.pin,
                "reason" to "Pedido do titular",
            ),
        )

    private companion object {
        const val DESC_A = "Descricao-A-pessoa-fluida"
        const val DESC_B = "Descricao-B-agenero-absorvido"
        const val DESC_C = "Descricao-C-neutro-absorvido-depois"
        const val MARKER = "MARCADOR-GENERO-PROVA-5c2e"
        const val PIN_MIN = 1000
        const val PIN_MAX = 9999
    }
}
