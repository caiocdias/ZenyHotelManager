package com.zeny.hotel.audit

import auditexample.AuditProbeConfiguration
import auditexample.AuditProbeService
import auditexample.AuditProbeService.Companion.PROBE
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.asUser
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * `GET /api/v1/audit-entries` (RF-ACS-11): filtros por usuário, entidade e período, ordem das
 * mais recentes para as mais antigas com cursor, e a permissão própria `audit.read`.
 */
@IntegrationTest
@Import(AuditProbeConfiguration::class, IamTestConfiguration::class, MutableClockConfiguration::class)
class AuditEntriesApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val probe: AuditProbeService,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val clock: MutableClock,
    @Autowired private val jsonMapper: JsonMapper,
) {
    private val auditRead = AuditPermissions.permissions.single { it.code == AuditPermissions.READ }

    @Test
    fun `filters by user, by entity and by period, newest first`() {
        val auditor = iam.createUserWith(TestGrant(auditRead, ALL))
        val alice = UserId(UuidV7.generate())
        val bob = UserId(UuidV7.generate())
        val first = UuidV7.generate()
        val second = UuidV7.generate()
        // Uma janela de tempo só deste teste: o relógio é do contexto, e outros testes gravam entradas nele.
        clock.advance(Duration.ofDays(1))
        val start = clock.instant()
        probe.attributed(first, alice, UuidV7.generate())
        clock.advance(Duration.ofMinutes(1))
        val middle = clock.instant()
        probe.attributed(second, bob, UuidV7.generate())
        clock.advance(Duration.ofMinutes(1))
        probe.attributed(UuidV7.generate(), alice, UuidV7.generate())
        clock.advance(Duration.ofMinutes(1))
        val end = clock.instant()

        val byAlice = list(iam.asTestUser(auditor), "userId" to alice.value.toString())
        byAlice.map { it["userId"].stringValue() }.toSet() shouldBe setOf(alice.value.toString())
        byAlice shouldHaveSize 2
        byAlice.map { Instant.parse(it["occurredAt"].stringValue()) }.let { it shouldBe it.sortedDescending() }

        val byEntity = list(iam.asTestUser(auditor), "entityType" to PROBE, "entityId" to second.toString())
        byEntity.map { it["entityId"].stringValue() } shouldContainExactly listOf(second.toString())
        byEntity.single().let {
            it["userId"].stringValue() shouldBe bob.value.toString()
            it["action"].stringValue() shouldBe "test.probe.attributed"
            it["outcome"].stringValue() shouldBe "SUCCEEDED"
            it["before"].isNull shouldBe true
            it["after"]["title"].stringValue() shouldBe "atribuída"
            it["approvalId"].isString shouldBe true
        }

        val byType =
            list(iam.asTestUser(auditor), "entityType" to PROBE, "from" to start.toString(), "to" to end.toString())
        byType shouldHaveSize 3

        val firstMinute =
            list(iam.asTestUser(auditor), "entityType" to PROBE, "from" to start.toString(), "to" to middle.toString())
        firstMinute.map { it["entityId"].stringValue() } shouldContainExactly listOf(first.toString())

        val fromMiddle =
            list(iam.asTestUser(auditor), "userId" to alice.value.toString(), "from" to middle.toString())
        fromMiddle shouldHaveSize 1
        fromMiddle.single()["entityId"].stringValue() shouldNotBe first.toString()

        list(iam.asTestUser(auditor), "userId" to UuidV7.generate().toString()).shouldBeEmpty()
    }

    @Test
    fun `pages with a cursor through entries in a stable order`() {
        val auditor = iam.createUserWith(TestGrant(auditRead, ALL))
        val actor = UserId(UuidV7.generate())
        val ids = (1..5).map { UuidV7.generate().also { id -> probe.attributed(id, actor, UuidV7.generate()) } }

        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val params =
                listOfNotNull("userId" to actor.value.toString(), "limit" to "2", cursor?.let { "cursor" to it })
            val body = page(iam.asTestUser(auditor), params)
            body["items"].forEach { seen += it["entityId"].stringValue() }
            cursor = body["nextCursor"].takeUnless { it.isNull }?.stringValue()
        } while (cursor != null)

        seen shouldContainExactly ids.reversed().map(UUID::toString)
    }

    @Test
    fun `reading the trail requires audit read`() {
        val withoutPermission = iam.createUser()

        val response =
            mockMvc
                .get("/api/v1/audit-entries") { with(iam.asTestUser(withoutPermission)) }
                .andReturn()
                .response
        response.status shouldBe 403
        jsonMapper.readTree(response.contentAsString)["code"].stringValue() shouldBe "PERMISSION_DENIED"

        mockMvc
            .get("/api/v1/audit-entries")
            .andReturn()
            .response.status shouldBe 401
        // O administrador recebe todas as permissões, inclusive esta.
        mockMvc
            .get("/api/v1/audit-entries") { with(iam.asAdmin()) }
            .andReturn()
            .response.status shouldBe 200
    }

    @Test
    fun `rejects an entity id without its type, an inverted period and a malformed filter`() {
        val auditor = iam.createUserWith(TestGrant(auditRead, ALL))
        val now = clock.instant()

        status(iam.asTestUser(auditor), "entityId" to UuidV7.generate().toString()) shouldBe 400
        status(iam.asTestUser(auditor), "from" to now.toString(), "to" to now.minusSeconds(1).toString()) shouldBe 400
        status(iam.asTestUser(auditor), "userId" to "não-é-uuid") shouldBe 400
        status(iam.asTestUser(auditor), "from" to "ontem") shouldBe 400
        status(iam.asTestUser(auditor), "cursor" to "lixo") shouldBe 400
    }

    @Test
    fun `an entry without an approval or a session comes back with nulls`() {
        val auditor = iam.createUserWith(TestGrant(auditRead, ALL))
        val id = UuidV7.generate()
        probe.write(id, "do sistema")

        val entry = list(iam.asTestUser(auditor), "entityType" to PROBE, "entityId" to id.toString()).single()

        entry["userId"].isNull shouldBe true
        entry["deviceSessionId"].isNull shouldBe true
        entry["approvalId"].isNull shouldBe true
        entry["errorCode"].isNull shouldBe true
        entry["after"].shouldNotBeNull()
        entry["before"].isNull shouldBe true
        entry["id"].stringValue().shouldNotBeNull()
        entry["entityType"].stringValue() shouldBe PROBE
        entry["missing"].shouldBeNull()
    }

    private fun list(
        auth: RequestPostProcessor,
        vararg params: Pair<String, String>,
    ): List<JsonNode> = page(auth, params.toList() + ("limit" to "200"))["items"].toList()

    private fun page(
        auth: RequestPostProcessor,
        params: List<Pair<String, String>>,
    ): JsonNode {
        val response =
            mockMvc
                .get("/api/v1/audit-entries") {
                    with(auth)
                    params.forEach { (name, value) -> param(name, value) }
                }.andReturn()
                .response
        response.status shouldBe 200
        return jsonMapper.readTree(response.contentAsString)
    }

    private fun status(
        auth: RequestPostProcessor,
        vararg params: Pair<String, String>,
    ): Int =
        mockMvc
            .get("/api/v1/audit-entries") {
                with(auth)
                params.forEach { (name, value) -> param(name, value) }
            }.andReturn()
            .response.status
}
