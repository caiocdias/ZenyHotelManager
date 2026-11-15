package com.zeny.hotel.guests

import com.zeny.hotel.guests.GuestsTestSupport.Companion.definition
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * O corpo mínimo das escritas de restrição e mesclagem (M6 E17): sem `guests.profiles.read`, `restrict`,
 * `lift-restriction` e `merge` gravam e devolvem só `id` e `restricted`; com ela, o perfil inteiro. A trilha é a mesma
 * nos dois casos, e as recusas seguem problemas sem dado de perfil.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LargeClass") // os cenários das três escritas e o contrato
class GuestWriteBodyApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val guestsApi: GuestsApi,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
) {
    private val guests = GuestsTestSupport(iam)
    private val read = TestGrant(definition(GuestsPermissions.PROFILES_READ), ALL)
    private val restrictions = TestGrant(definition(GuestsPermissions.RESTRICTIONS_MANAGE), ALL)
    private val merge = TestGrant(definition(GuestsPermissions.PROFILES_MERGE), ALL)
    private val summaryKeys = setOf("id", "restricted")

    /** Um perfil com todos os textos pessoais marcados por letras únicas, para procurar no corpo cru. */
    private inner class Profile(
        withDocument: Boolean = true,
    ) {
        private val tag = letters()
        val fullName = "Nome${tag}Completo"
        val email = "mail$tag@exemplo.com.br"
        val phone = "+55 11 97777-4321"
        val genderDescription = "Genero${tag}Descrito"
        val dietary = "Dieta${tag}Especial"
        val reason = "Motivo${tag}Restrito"
        val id: UUID = create(withDocument)
        val texts get() = listOf(fullName, email, genderDescription, dietary, reason)

        private fun create(withDocument: Boolean): UUID {
            val profile =
                guests.profileBody(
                    fullName = fullName,
                    email = email,
                    extra =
                        mapOf(
                            "gender" to "OTHER",
                            "genderDescription" to genderDescription,
                            "phone" to phone,
                            "dietaryRestrictions" to dietary,
                        ),
                )
            val body =
                guests.createBody(
                    profile = profile,
                    documents =
                        if (withDocument) {
                            listOf(
                                guests.document("CPF", GuestsTestSupport.newCpf()),
                            )
                        } else {
                            emptyList()
                        },
                ) +
                    ("consents" to listOf(mapOf("purpose" to "DIETARY_RESTRICTIONS", "source" to "IN_PERSON")))
            return UUID.fromString(guests.createGuest(body)["id"].asString())
        }
    }

    private class Reply(
        val response: MockHttpServletResponse,
        val mapper: JsonMapper,
    ) {
        val status get() = response.status
        val raw: String get() = response.getContentAsString(Charsets.UTF_8)
        val json: JsonNode get() = mapper.readTree(raw)

        fun header(name: String): String? = response.getHeader(name)
    }

    @Test
    fun `restrict and lift without profiles read write the change and return only the minimal body`() {
        val profile = Profile()
        val user = iam.createUserWith(restrictions)

        val restricted = write("/api/v1/guests/${profile.id}/restrict", user, mapOf("reason" to profile.reason))
        restricted.status shouldBe 200
        restricted.json.propertyNames().toSet() shouldBe summaryKeys
        restricted.json["id"].asString() shouldBe profile.id.toString()
        restricted.json["restricted"].asBoolean() shouldBe true
        restricted.header(HttpHeaders.ETAG).shouldNotBeNull()
        for (secret in profile.texts) restricted.raw shouldNotContain secret
        restricted.raw shouldNotContain profile.phone
        guestsApi.restrictionAlert(GuestId(profile.id)).shouldNotBeNull()

        val lifted = write("/api/v1/guests/${profile.id}/lift-restriction", user)
        lifted.status shouldBe 200
        lifted.json.propertyNames().toSet() shouldBe summaryKeys
        lifted.json["restricted"].asBoolean() shouldBe false
        for (secret in profile.texts) lifted.raw shouldNotContain secret
        guestsApi.restrictionAlert(GuestId(profile.id)).shouldBeNull()
    }

    @Test
    fun `merge without profiles read merges and returns only the minimal body`() {
        val survivor = Profile()
        val absorbed = Profile(withDocument = false)
        iam.call(POST, "/api/v1/guests/${absorbed.id}/restrict", iam.asAdmin(), mapOf("reason" to absorbed.reason))
        val etag = guests.etagOf("/api/v1/guests/${survivor.id}")
        val merger = iam.createUserWith(merge)

        val reply = write("/api/v1/guests/${survivor.id}/merge", merger, mergeBody(absorbed.id), etag)

        reply.status shouldBe 200
        reply.json.propertyNames().toSet() shouldBe summaryKeys
        reply.json["id"].asString() shouldBe survivor.id.toString()
        reply.json["restricted"].asBoolean() shouldBe true
        reply.header(HttpHeaders.ETAG).shouldNotBeNull() shouldNotBe etag
        for (secret in survivor.texts + absorbed.texts + listOf(survivor.phone)) reply.raw shouldNotContain secret
        val merged = iam.call(GET, "/api/v1/guests/${absorbed.id}", iam.asAdmin())
        with(iam) { merged.ok()["status"].asString() shouldBe "MERGED" }
        with(iam) { merged.ok()["mergedInto"].asString() shouldBe survivor.id.toString() }
    }

    @Test
    fun `with the write permission and profiles read the three routes return the full profile`() {
        val profile = Profile()
        val manager = iam.createUserWith(restrictions, read)

        val restricted = write("/api/v1/guests/${profile.id}/restrict", manager, mapOf("reason" to profile.reason))
        restricted.status shouldBe 200
        restricted.json["fullName"].asString() shouldBe profile.fullName
        restricted.json["genderDescription"].asString() shouldBe profile.genderDescription
        restricted.json["documents"].isArray shouldBe true
        restricted.json["restriction"]["reason"].asString() shouldBe profile.reason
        restricted.header(HttpHeaders.ETAG).shouldNotBeNull()

        val lifted = write("/api/v1/guests/${profile.id}/lift-restriction", manager)
        lifted.json["fullName"].asString() shouldBe profile.fullName
        lifted.json["genderDescription"].asString() shouldBe profile.genderDescription
        lifted.json["documents"].isArray shouldBe true
        lifted.json["restriction"].isNull shouldBe true

        val survivor = Profile()
        val absorbed = Profile(withDocument = false)
        val etag = guests.etagOf("/api/v1/guests/${survivor.id}")
        val merger = iam.createUserWith(merge, read)
        val merged = write("/api/v1/guests/${survivor.id}/merge", merger, mergeBody(absorbed.id), etag)
        merged.status shouldBe 200
        merged.json["fullName"].asString() shouldBe survivor.fullName
        merged.json["documents"].isArray shouldBe true
    }

    @Test
    fun `the trail of the three writes is the same with either body`() {
        val minimalUser = iam.createUserWith(restrictions, merge)
        val fullUser = iam.createUserWith(restrictions, merge, read)
        val minimal = Profile()
        val full = Profile()
        for ((user, profile) in listOf(minimalUser to minimal, fullUser to full)) {
            write("/api/v1/guests/${profile.id}/restrict", user, mapOf("reason" to profile.reason)).status shouldBe 200
            write("/api/v1/guests/${profile.id}/lift-restriction", user).status shouldBe 200
        }
        val minimalAbsorbed = Profile(withDocument = false)
        val fullAbsorbed = Profile(withDocument = false)
        for ((user, pair) in listOf(minimalUser to (minimal to minimalAbsorbed), fullUser to (full to fullAbsorbed))) {
            val (survivor, absorbed) = pair
            val etag = guests.etagOf("/api/v1/guests/${survivor.id}")
            write("/api/v1/guests/${survivor.id}/merge", user, mergeBody(absorbed.id), etag).status shouldBe 200
        }

        for (action in listOf("restrict", "lift-restriction", "merge")) {
            val a = trail("guests.guest.$action", minimal.id).single()
            val b = trail("guests.guest.$action", full.id).single()
            withClue(action) {
                a["user_id"] shouldBe minimalUser.id.value.toString()
                b["user_id"] shouldBe fullUser.id.value.toString()
                keys(a["before_state"]) shouldBe keys(b["before_state"])
                keys(a["after_state"]) shouldBe keys(b["after_state"])
                changed(a["after_state"]) shouldBe changed(b["after_state"])
            }
        }
    }

    @Test
    fun `a lift on a profile outside the list returns the minimal body and writes no trail entry`() {
        val profile = Profile()
        val user = iam.createUserWith(restrictions)

        val reply = write("/api/v1/guests/${profile.id}/lift-restriction", user)

        reply.status shouldBe 200
        reply.json.propertyNames().toSet() shouldBe summaryKeys
        reply.json["restricted"].asBoolean() shouldBe false
        trail("guests.guest.lift-restriction", profile.id).shouldBeEmpty()
    }

    @Test
    fun `profiles read without the write permission is refused with a problem and no profile`() {
        val profile = Profile()
        val absorbed = Profile(withDocument = false)
        val reader = iam.createUserWith(read)
        val etag = guests.etagOf("/api/v1/guests/${profile.id}")

        val replies =
            listOf(
                write("/api/v1/guests/${profile.id}/restrict", reader, mapOf("reason" to profile.reason)),
                write("/api/v1/guests/${profile.id}/lift-restriction", reader),
                write("/api/v1/guests/${profile.id}/merge", reader, mergeBody(absorbed.id), etag),
            )
        for (reply in replies) {
            reply.status shouldBe 403
            reply.json["code"].asString() shouldBe "PERMISSION_DENIED"
            MediaType
                .parseMediaType(
                    reply.response.contentType!!,
                ).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) shouldBe
                true
            reply.raw shouldNotContain profile.fullName
            reply.raw shouldNotContain absorbed.fullName
        }
        guestsApi.restrictionAlert(GuestId(profile.id)).shouldBeNull()
        status(absorbed.id) shouldBe "ACTIVE"
    }

    @Test
    fun `refusals of the writes stay problems without profile data`() {
        val merged = Profile(withDocument = false)
        val survivor = Profile()
        val restrictor = iam.createUserWith(restrictions)
        val merger = iam.createUserWith(merge)
        val etag = guests.etagOf("/api/v1/guests/${survivor.id}")
        write("/api/v1/guests/${survivor.id}/merge", merger, mergeBody(merged.id), etag).status shouldBe 200

        val cases =
            listOf(
                write("/api/v1/guests/${merged.id}/restrict", restrictor, mapOf("reason" to "x")) to
                    (409 to "GUEST_ALREADY_MERGED"),
                write("/api/v1/guests/${survivor.id}/merge", merger, mergeBody(Profile(withDocument = false).id)) to
                    (428 to "PRECONDITION_REQUIRED"),
                write(
                    "/api/v1/guests/${survivor.id}/merge",
                    merger,
                    mergeBody(Profile(withDocument = false).id),
                    "\"99\"",
                ) to
                    (412 to "PRECONDITION_FAILED"),
                write(
                    "/api/v1/guests/${survivor.id}/merge",
                    merger,
                    mergeBody(survivor.id),
                    guests.etagOf("/api/v1/guests/${survivor.id}"),
                ) to (409 to "GUEST_MERGE_SAME_PROFILE"),
            )
        for ((reply, expected) in cases) {
            withClue(reply.raw) {
                reply.status shouldBe expected.first
                reply.json["code"].asString() shouldBe expected.second
                MediaType
                    .parseMediaType(reply.response.contentType!!)
                    .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) shouldBe true
                reply.raw shouldNotContain survivor.fullName
                reply.raw shouldNotContain merged.fullName
            }
        }
    }

    @Test
    fun `the contract describes both bodies only on the three writes and keeps them disjoint`() {
        val doc =
            jsonMapper.readTree(
                mockMvc
                    .get("/v3/api-docs")
                    .andReturn()
                    .response.contentAsString,
            )
        for (path in listOf(
            "/api/v1/guests/{id}/restrict",
            "/api/v1/guests/{id}/lift-restriction",
            "/api/v1/guests/{id}/merge",
        )) {
            val content = doc["paths"][path]["post"]["responses"]["200"]["content"]
            content.propertyNames().toSet() shouldBe setOf("application/json")
            content["application/json"]["schema"]["oneOf"].values().map {
                it["\$ref"].asString()
            } shouldContainExactlyInAnyOrder
                listOf("#/components/schemas/GuestResponse", "#/components/schemas/GuestWriteSummaryResponse")
        }
        val summary = doc["components"]["schemas"]["GuestWriteSummaryResponse"]
        summary["properties"].propertyNames().toSet() shouldBe setOf("id", "restricted")
        summary["required"].values().map { it.asString() } shouldContain "restricted"
        val full = doc["components"]["schemas"]["GuestResponse"]
        full["properties"].has("restricted") shouldBe false
        full["required"].values().map { it.asString() } shouldContain "fullName"
        full.has("allOf") shouldBe false
        doc["paths"]["/api/v1/guests/{id}"]["get"]["responses"]["200"]["content"]["application/json"]["schema"]["\$ref"]
            .asString() shouldBe "#/components/schemas/GuestResponse"
    }

    // --- Apoio ----------------------------------------------------------------------------------------------

    private fun write(
        path: String,
        user: com.zeny.hotel.iam.TestUser,
        body: Any? = null,
        ifMatch: String? = null,
    ) = Reply(iam.call(POST, path, iam.asTestUser(user), body, ifMatch), jsonMapper)

    private fun mergeBody(absorbed: UUID) = mapOf("mergedGuestId" to absorbed, "takeFromMerged" to emptyList<String>())

    private fun letters(): String =
        UUID
            .randomUUID()
            .toString()
            .filter { it.isLetterOrDigit() }
            .map {
                'a' +
                    (it.code % 26)
            }.joinToString("")
            .take(12)

    private fun status(guest: UUID): String =
        jdbc
            .sql("SELECT status FROM guests.guest WHERE id = :id")
            .param("id", guest)
            .query(String::class.java)
            .single()

    private fun trail(
        action: String,
        entity: UUID,
    ): List<Map<String, Any?>> =
        jdbc
            .sql(
                "SELECT user_id::text AS user_id, CAST(before_state AS text) AS before_state, " +
                    "CAST(after_state AS text) AS after_state FROM audit.audit_entry " +
                    "WHERE action = :action AND entity_id = :id ORDER BY occurred_at",
            ).param("action", action)
            .param("id", entity.toString())
            .query()
            .listOfRows()

    private fun keys(json: Any?): Set<String> =
        jsonMapper.readTree(json as String).propertyNames().toSet() - "changedFields"

    private fun changed(json: Any?): List<String> =
        jsonMapper
            .readTree(json as String)["changedFields"]
            ?.values()
            ?.map { it.asString() }
            .orEmpty()
}
