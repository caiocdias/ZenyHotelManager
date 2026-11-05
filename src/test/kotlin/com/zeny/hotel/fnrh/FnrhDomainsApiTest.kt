package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.domain.FnrhDomain
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.FnrhTestConfiguration
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.enableFnrh
import com.zeny.hotel.testsupport.resetFnrhSettings
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.JsonNode

/**
 * Os domínios oficiais da FNRH com cache (M5 E07): a semente da migração, a leitura por `fnrh.domains.read`, a
 * atualização sob demanda por `fnrh.settings.manage` (tudo ou nada) e a entrada na trilha, que prova que a gravação
 * passou pelo proxy do `FnrhDomainValuesWriter`.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, FnrhTestConfiguration::class)
class FnrhDomainsApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
) {
    private data class Row(
        val domain: String,
        val code: String,
        val label: String,
    )

    private val refreshPath = "/api/v1/fnrh/domains/refresh"
    private lateinit var seed: List<Row>

    @BeforeEach
    fun setUp() {
        FnrhTestServer.reset()
        iam.enableFnrh()
        seed = rows()
    }

    @AfterEach
    fun tearDown() {
        jdbc.sql("DELETE FROM fnrh.domain_value").update()
        seed.forEach {
            jdbc
                .sql("INSERT INTO fnrh.domain_value (domain, code, label, refreshed_at) VALUES (:d, :c, :l, now())")
                .param("d", it.domain)
                .param("c", it.code)
                .param("l", it.label)
                .update()
        }
        jdbc.resetFnrhSettings()
        FnrhTestServer.reset()
    }

    @Test
    fun `without a refresh the seed answers with the documented codes and labels`(): Unit =
        with(iam) {
            val values = call(GET, "/api/v1/fnrh/domains/transport-modes", asAdmin()).ok()

            values.pairs() shouldContainExactly
                listOf(
                    "AUTOMOVEL" to "Automóvel",
                    "AVIAO" to "Avião",
                    "BICICLETA" to "Bicicleta",
                    "MOTO" to "Moto",
                    "NAVIO_BARCO" to "Navio/Barco",
                    "ONIBUS" to "Ônibus",
                    "PE" to "A pé",
                    "TREM" to "Trem",
                )
        }

    @Test
    fun `every one of the ten domains is readable by its kebab-case name and has a seed`(): Unit =
        with(iam) {
            val names =
                listOf(
                    "transport-modes" to 8,
                    "travel-reasons" to 8,
                    "guest-statuses" to 7,
                    "genders" to 4,
                    "disability-options" to 3,
                    "races" to 6,
                    "disability-types" to 5,
                    "document-types" to 2,
                    "booking-statuses" to 5,
                    "record-statuses" to 5,
                )
            for ((name, size) in names) {
                call(GET, "/api/v1/fnrh/domains/$name", asAdmin()).ok().size() shouldBe size
            }
        }

    @Test
    fun `an unknown domain is 404 FNRH_DOMAIN_NOT_FOUND`(): Unit =
        with(iam) {
            call(GET, "/api/v1/fnrh/domains/TRANSPORT_MODES", asAdmin()).problem(404, "FNRH_DOMAIN_NOT_FOUND")
            call(GET, "/api/v1/fnrh/domains/nada", asAdmin()).problem(404, "FNRH_DOMAIN_NOT_FOUND")
        }

    @Test
    fun `a refresh brings a new value and drops the one that disappeared`(): Unit =
        with(iam) {
            stubAllDomains(transport = arrayOf("ONIBUS" to "Ônibus", "TELEPORTE" to "Teletransporte"))

            val result = call(POST, refreshPath, asAdmin()).ok()

            result["domains"].first { it["domain"].asString() == "transport-modes" }["count"].asInt() shouldBe 2
            result["domains"].size() shouldBe FnrhDomain.entries.size
            call(GET, "/api/v1/fnrh/domains/transport-modes", asAdmin()).ok().pairs() shouldContainExactly
                listOf("ONIBUS" to "Ônibus", "TELEPORTE" to "Teletransporte")
            call(GET, "/api/v1/fnrh/domains/genders", asAdmin()).ok().pairs() shouldContainExactly
                listOf("HOMEM" to "Masculino")
        }

    @Test
    fun `a 503 on one domain leaves every domain as it was`(): Unit =
        with(iam) {
            stubAllDomains(transport = arrayOf("TELEPORTE" to "Teletransporte"))
            FnrhTestServer.stub(FnrhDomain.RACES.path, 503, "{}")

            call(POST, refreshPath, asAdmin()).problem(503, "FNRH_UNAVAILABLE")

            rows() shouldContainExactly seed
        }

    @Test
    fun `a refresh without configuration is 422 FNRH_NOT_CONFIGURED`(): Unit =
        with(iam) {
            jdbc.resetFnrhSettings()

            call(POST, refreshPath, asAdmin()).problem(422, "FNRH_NOT_CONFIGURED")

            rows() shouldContainExactly seed
        }

    @Test
    fun `rejected credentials are 422 FNRH_CREDENTIALS_REJECTED`(): Unit =
        with(iam) {
            FnrhTestServer.stubAny(401, "{}")

            call(POST, refreshPath, asAdmin()).problem(422, "FNRH_CREDENTIALS_REJECTED")

            rows() shouldContainExactly seed
        }

    @Test
    fun `a Serpro refusal is 502 FNRH_REJECTED`(): Unit =
        with(iam) {
            FnrhTestServer.stubAny(400, """{"mensagem":"Nome Fulano 12345678901"}""")

            val problem = call(POST, refreshPath, asAdmin()).problem(502, "FNRH_REJECTED")

            problem.toString() shouldNotContain "Fulano"
            rows() shouldContainExactly seed
        }

    @Test
    fun `an empty domain from the Serpro is refused and nothing changes`(): Unit =
        with(iam) {
            stubAllDomains(transport = arrayOf("TELEPORTE" to "Teletransporte"))
            FnrhTestServer.stubDomain_v2_4(FnrhDomain.GENDERS.path)

            call(POST, refreshPath, asAdmin()).problem(502, "FNRH_REJECTED")

            rows() shouldContainExactly seed
        }

    @Test
    fun `a successful refresh leaves an entry in the audit trail`(): Unit =
        with(iam) {
            val before = refreshEntries()
            stubAllDomains(transport = arrayOf("TELEPORTE" to "Teletransporte"))

            call(POST, refreshPath, asAdmin()).ok()

            refreshEntries() shouldBe before + 1
        }

    @Test
    fun `a failed refresh leaves no audit entry`(): Unit =
        with(iam) {
            val before = refreshEntries()
            FnrhTestServer.stubAny(503, "{}")

            call(POST, refreshPath, asAdmin()).problem(503, "FNRH_UNAVAILABLE")

            refreshEntries() shouldBe before
        }

    @Test
    fun `a receptionist reads but cannot refresh`(): Unit =
        with(iam) {
            val receptionist =
                iam.createUserWith(
                    TestGrant(FnrhPermissions.permissions.single { it.code == FnrhPermissions.DOMAINS_READ }, ALL),
                )
            stubAllDomains(transport = arrayOf("TELEPORTE" to "Teletransporte"))

            call(GET, "/api/v1/fnrh/domains/races", asTestUser(receptionist)).ok().size() shouldBe 6
            call(POST, refreshPath, asTestUser(receptionist)).problem(403, "PERMISSION_DENIED")

            rows() shouldContainExactly seed
        }

    @Test
    fun `a user without the read permission gets 403`(): Unit =
        with(iam) {
            val nobody = createUserWith()

            call(GET, "/api/v1/fnrh/domains/races", asTestUser(nobody)).problem(403, "PERMISSION_DENIED")
        }

    private fun stubAllDomains(vararg transport: Pair<String, String>) {
        for (domain in FnrhDomain.entries) {
            when (domain) {
                FnrhDomain.TRANSPORT_MODES -> FnrhTestServer.stubDomain_v2_4(domain.path, *transport)
                else -> FnrhTestServer.stubDomain_v2_4(domain.path, "HOMEM" to "Masculino")
            }
        }
    }

    private fun JsonNode.pairs(): List<Pair<String, String>> =
        this.toList().map {
            it["code"].asString() to
                it["label"].asString()
        }

    private fun rows(): List<Row> =
        jdbc
            .sql("SELECT domain, code, label FROM fnrh.domain_value ORDER BY domain, code")
            .query { rs, _ -> Row(rs.getString(1), rs.getString(2), rs.getString(3)) }
            .list()

    private fun refreshEntries(): Int =
        jdbc
            .sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'fnrh.domains.refresh'")
            .query(Int::class.java)
            .single()
}
