package com.zeny.hotel.guests

import com.zeny.hotel.guests.GuestsTestSupport.Companion.definition
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Ataques ao corpo mínimo das escritas de restrição e mesclagem (M6 E17): vazamento por cabeçalho e por corpo de erro,
 * permissão retirada ou dada entre chamadas, troca de motivo, mesclagem com dado sensível e igualdade com o `GET`.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class GuestWriteBodyAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jsonMapper: JsonMapper,
) {
    private val guests = GuestsTestSupport(iam)
    private val read = TestGrant(definition(GuestsPermissions.PROFILES_READ), ALL)
    private val restrictions = TestGrant(definition(GuestsPermissions.RESTRICTIONS_MANAGE), ALL)
    private val merge = TestGrant(definition(GuestsPermissions.PROFILES_MERGE), ALL)
    private val everything = listOf("FULL_NAME", "EMAIL", "GENDER", "SENSITIVE_DATA", "DIETARY_RESTRICTIONS", "NOTES")
    private val summaryKeys = setOf("id", "restricted")

    private inner class Profile(
        withDocument: Boolean = true,
    ) {
        private val tag =
            UUID
                .randomUUID()
                .toString()
                .filter { it.isLetterOrDigit() }
                .map { 'a' + (it.code % 26) }
                .joinToString("")
                .take(12)
        val fullName = "Nome${tag}Completo"
        val email = "mail$tag@exemplo.com.br"
        val genderDescription = "Genero${tag}Descrito"
        val dietary = "Dieta${tag}Especial"
        val reason = "Motivo${tag}Restrito"
        val newReason = "Outro${tag}Motivo"
        val texts get() = listOf(fullName, email, genderDescription, dietary, reason, newReason)
        val id: UUID =
            UUID.fromString(
                guests
                    .createGuest(
                        guests.createBody(
                            profile =
                                guests.profileBody(
                                    fullName = fullName,
                                    email = email,
                                    extra =
                                        mapOf(
                                            "gender" to "OTHER",
                                            "genderDescription" to genderDescription,
                                            "dietaryRestrictions" to dietary,
                                        ),
                                ),
                            documents =
                                if (withDocument) {
                                    listOf(
                                        guests.document("CPF", GuestsTestSupport.newCpf()),
                                    )
                                } else {
                                    emptyList()
                                },
                        ) + (
                            "consents" to
                                listOf(
                                    mapOf("purpose" to "DIETARY_RESTRICTIONS", "source" to "IN_PERSON"),
                                )
                        ),
                    )["id"]
                    .asString(),
            )
    }

    private class Reply(
        val response: MockHttpServletResponse,
        val mapper: JsonMapper,
    ) {
        val status get() = response.status
        val raw: String get() = response.getContentAsString(Charsets.UTF_8)
        val json: JsonNode get() = mapper.readTree(raw)
        val etag: String? get() = response.getHeader(HttpHeaders.ETAG)

        /** Todos os cabeçalhos juntos, em texto, para procurar vazamento. */
        val headersText: String
            get() =
                response.headerNames.joinToString("\n") { n -> "$n: ${response.getHeaders(n).joinToString("|")}" }
    }

    private fun write(
        path: String,
        user: TestUser,
        body: Any? = null,
        ifMatch: String? = null,
    ) = Reply(iam.call(POST, path, iam.asTestUser(user), body, ifMatch), jsonMapper)

    private fun getAsAdmin(id: UUID) = Reply(iam.call(GET, "/api/v1/guests/$id", iam.asAdmin()), jsonMapper)

    /**
     * Mesmo corpo do `GET`. Exceção documentada: `restriction.since` e `updatedAt` voltam da escrita com o instante
     * em memória (nanossegundos) e do `GET` na precisão do banco (microssegundos); como já era antes da E17,
     * comparam-se com tolerância de 1 microssegundo.
     */
    private fun sameJson(
        a: Reply,
        b: Reply,
    ) {
        val fields = a.json.propertyNames().toSet() + b.json.propertyNames().toSet()
        for (f in fields - "restriction") {
            withClue("campo $f") { sameValue(a.json[f], b.json[f]) }
        }
        val ra = a.json["restriction"]
        val rb = b.json["restriction"]
        val absent = { n: JsonNode? -> n == null || n.isNull }
        if (absent(ra) || absent(rb)) {
            absent(ra) shouldBe absent(rb)
            return
        }
        ra!!["reason"].asString() shouldBe rb!!["reason"].asString()
        ra["restrictedBy"].asString() shouldBe rb["restrictedBy"].asString()
        sameValue(ra["since"], rb["since"])
    }

    /** O mesmo valor, ou o mesmo instante com no máximo 1 microssegundo de diferença (o banco arredonda). */
    private fun sameValue(
        a: JsonNode?,
        b: JsonNode?,
    ) {
        val ta = a?.takeIf { it.isString }?.asString()?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val tb = b?.takeIf { it.isString }?.asString()?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (ta != null && tb != null) {
            (Duration.between(ta, tb).abs() <= Duration.ofNanos(1_000)) shouldBe true
        } else {
            a?.toString() shouldBe b?.toString()
        }
    }

    private fun mergeBody(
        absorbed: UUID,
        take: List<String> = emptyList(),
    ) = mapOf("mergedGuestId" to absorbed, "takeFromMerged" to take)

    @Test
    fun `no response header of a minimal write carries personal data and the ETag is opaque`() {
        val p = Profile()
        val user = iam.createUserWith(restrictions)
        val reply = write("/api/v1/guests/${p.id}/restrict", user, mapOf("reason" to p.reason))
        reply.status shouldBe 200
        for (t in p.texts) reply.headersText shouldNotContain t
        reply.headersText shouldNotContain p.id.toString().let { "Location: $it" }
        reply.etag.shouldNotBeNull() shouldMatch Regex("\"\\d+\"")
    }

    @Test
    fun `the ETag of a minimal write is the current version and works as If-Match for the next write`() {
        val survivor = Profile()
        val absorbed = Profile(withDocument = false)
        val restrictor = iam.createUserWith(restrictions)
        val merger = iam.createUserWith(merge)
        val reply = write("/api/v1/guests/${survivor.id}/restrict", restrictor, mapOf("reason" to survivor.reason))
        reply.etag shouldBe getAsAdmin(survivor.id).etag
        val merged =
            write("/api/v1/guests/${survivor.id}/merge", merger, mergeBody(absorbed.id), reply.etag)
        withClue(merged.raw) { merged.status shouldBe 200 }
        merged.etag shouldBe getAsAdmin(survivor.id).etag
        merged.etag shouldNotBe reply.etag
    }

    @Test
    fun `repeating restrict with another reason without profiles read stays minimal and bumps the version`() {
        val p = Profile()
        val user = iam.createUserWith(restrictions)
        val first = write("/api/v1/guests/${p.id}/restrict", user, mapOf("reason" to p.reason))
        val second = write("/api/v1/guests/${p.id}/restrict", user, mapOf("reason" to p.newReason))
        second.status shouldBe 200
        second.json.propertyNames().toSet() shouldBe summaryKeys
        second.json["restricted"].asBoolean() shouldBe true
        for (t in p.texts) second.raw shouldNotContain t
        for (t in p.texts) second.headersText shouldNotContain t
        first.etag shouldNotBe second.etag
        getAsAdmin(p.id).json["restriction"]["reason"].asString() shouldBe p.newReason
    }

    @Test
    fun `profiles read revoked between calls turns the next body minimal and granted turns it full`() {
        val p = Profile()
        val role = iam.createRole(restrictions, read)
        val user = iam.createUser().also { iam.assignRole(it, role) }

        val full = write("/api/v1/guests/${p.id}/restrict", user, mapOf("reason" to p.reason))
        full.json["fullName"].asString() shouldBe p.fullName

        iam.revokeGrant(role, GuestsPermissions.PROFILES_READ)
        val minimal = write("/api/v1/guests/${p.id}/lift-restriction", user)
        minimal.status shouldBe 200
        minimal.json.propertyNames().toSet() shouldBe summaryKeys
        for (t in p.texts) minimal.raw shouldNotContain t

        val readRole = iam.createRole(read)
        iam.assignRole(user, readRole)
        val again = write("/api/v1/guests/${p.id}/restrict", user, mapOf("reason" to p.newReason))
        again.json["fullName"].asString() shouldBe p.fullName
    }

    @Test
    fun `write permission revoked between calls refuses with a problem and no profile`() {
        val p = Profile()
        val role = iam.createRole(restrictions, read)
        val user = iam.createUser().also { iam.assignRole(it, role) }
        write("/api/v1/guests/${p.id}/restrict", user, mapOf("reason" to p.reason)).status shouldBe 200

        iam.revokeGrant(role, GuestsPermissions.RESTRICTIONS_MANAGE)
        val lift = write("/api/v1/guests/${p.id}/lift-restriction", user)
        lift.status shouldBe 403
        lift.response.contentType!! shouldMatch Regex("application/problem\\+json.*")
        lift.raw shouldNotContain p.fullName
        getAsAdmin(p.id).json["restriction"].isNull shouldBe false
    }

    @Test
    fun `merge taking sensitive data from a restricted absorbed profile does not leak it without profiles read`() {
        val survivor = Profile()
        val absorbed = Profile(withDocument = false)
        iam.call(POST, "/api/v1/guests/${absorbed.id}/restrict", iam.asAdmin(), mapOf("reason" to absorbed.reason))
        val etag = getAsAdmin(survivor.id).etag!!
        val merger = iam.createUserWith(merge)

        val reply =
            write(
                "/api/v1/guests/${survivor.id}/merge",
                merger,
                mergeBody(absorbed.id, everything),
                etag,
            )

        withClue(reply.raw) { reply.status shouldBe 200 }
        reply.json.propertyNames().toSet() shouldBe summaryKeys
        reply.json["restricted"].asBoolean() shouldBe true
        for (t in survivor.texts + absorbed.texts) {
            reply.raw shouldNotContain t
            reply.headersText shouldNotContain t
        }
    }

    @Test
    fun `a merge without profiles read where only the survivor was restricted reports restricted true`() {
        val survivor = Profile()
        val absorbed = Profile(withDocument = false)
        iam.call(POST, "/api/v1/guests/${survivor.id}/restrict", iam.asAdmin(), mapOf("reason" to survivor.reason))
        val etag = getAsAdmin(survivor.id).etag!!
        val reply =
            write("/api/v1/guests/${survivor.id}/merge", iam.createUserWith(merge), mergeBody(absorbed.id), etag)
        reply.status shouldBe 200
        reply.json["restricted"].asBoolean() shouldBe true
    }

    @Test
    fun `the full body with profiles read is identical to the GET of the same profile`() {
        val p = Profile()
        val user = iam.createUserWith(restrictions, merge, read)
        val restricted = write("/api/v1/guests/${p.id}/restrict", user, mapOf("reason" to p.reason))
        sameJson(restricted, getAsAdmin(p.id))
        restricted.etag shouldBe getAsAdmin(p.id).etag
        val lifted = write("/api/v1/guests/${p.id}/lift-restriction", user)
        sameJson(lifted, getAsAdmin(p.id))
        lifted.etag shouldBe getAsAdmin(p.id).etag

        val absorbed = Profile(withDocument = false)
        val merged = write("/api/v1/guests/${p.id}/merge", user, mergeBody(absorbed.id), lifted.etag)
        merged.status shouldBe 200
        sameJson(merged, getAsAdmin(p.id))
        merged.etag shouldBe getAsAdmin(p.id).etag
    }

    @Test
    fun `validation and not found refusals of restrict do not echo the reason or any profile data`() {
        val p = Profile()
        val user = iam.createUserWith(restrictions)
        val longReason = p.reason + "x".repeat(5000)
        val cases =
            listOf(
                write("/api/v1/guests/${p.id}/restrict", user, mapOf("reason" to "")),
                write("/api/v1/guests/${p.id}/restrict", user, mapOf("reason" to longReason)),
                write("/api/v1/guests/${p.id}/restrict", user, mapOf("other" to p.reason)),
                write("/api/v1/guests/${UUID.randomUUID()}/restrict", user, mapOf("reason" to p.reason)),
                write("/api/v1/guests/${UUID.randomUUID()}/lift-restriction", user),
            )
        for (reply in cases) {
            withClue(reply.raw) {
                (reply.status in 400..499) shouldBe true
                reply.response.contentType!! shouldMatch Regex("application/problem\\+json.*")
                reply.raw shouldNotContain p.reason
                reply.raw shouldNotContain p.fullName
                reply.raw shouldNotContain p.email
            }
        }
    }
}
