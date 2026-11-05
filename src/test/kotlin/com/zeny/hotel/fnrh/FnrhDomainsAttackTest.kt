package com.zeny.hotel.fnrh

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.http.Fault
import com.zeny.hotel.fnrh.domain.FnrhDomain
import com.zeny.hotel.fnrh.domain.FnrhDomainCatalog
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.testsupport.FnrhTestConfiguration
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.enableFnrh
import com.zeny.hotel.testsupport.resetFnrhSettings
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ataques do testador aos domínios oficiais da FNRH (E07, Task 6): a semente contra o levantamento, o tudo-ou-nada do
 * `refresh`, o mapeamento de falhas e o problema RFC 9457 de cada uma, os nomes em kebab-case, as permissões por
 * role, o vazamento de dado do Serpro e valores hostis (rótulo enorme, caractere de controle).
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension::class)
@Import(IamTestConfiguration::class, FnrhTestConfiguration::class)
class FnrhDomainsAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val catalog: FnrhDomainCatalog,
) {
    private data class Row(
        val domain: String,
        val code: String,
        val label: String,
    )

    private val mapper = JsonMapper.builder().build()
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

    // ---- a semente

    @Test
    fun `the seed matches the survey for all ten domains, every id and label with accents`(): Unit =
        with(iam) {
            for ((name, expected) in SURVEY) {
                val actual = call(GET, "/api/v1/fnrh/domains/$name", asAdmin()).ok().pairs()
                withClue(name) { actual shouldContainExactlyInAnyOrder expected }
            }
            rows().size shouldBe SURVEY.values.sumOf { it.size }
        }

    @Test
    fun `the catalog contains exactly the seeded codes of the right domain`() {
        catalog.contains(FnrhDomain.TRANSPORT_MODES, "ONIBUS") shouldBe true
        catalog.contains(FnrhDomain.TRANSPORT_MODES, "onibus") shouldBe false
        catalog.contains(FnrhDomain.TRANSPORT_MODES, "SAUDE") shouldBe false
        catalog.contains(FnrhDomain.TRAVEL_REASONS, "SAUDE") shouldBe true
        catalog.contains(FnrhDomain.GENDERS, "") shouldBe false
        catalog.contains(FnrhDomain.GENDERS, "HOMEM' OR '1'='1") shouldBe false
        catalog.values(FnrhDomain.DOCUMENT_TYPES).map { it.code to it.label } shouldContainExactlyInAnyOrder
            listOf("CPF" to "CPF", "PASSAPORTE" to "Passaporte")
    }

    // ---- o tudo ou nada do refresh

    @Test
    fun `a 503 on the fifth domain changes nothing, leaves no audit entry and no partial write`(): Unit =
        with(iam) {
            val before = refreshEntries()
            stubAll("NOVO" to "Valor novo")
            FnrhTestServer.stub(FnrhDomain.entries[4].path, 503, "{}")

            call(POST, refreshPath, asAdmin()).problem(503, "FNRH_UNAVAILABLE")

            rows() shouldContainExactly seed
            refreshEntries() shouldBe before
        }

    @Test
    fun `a failure on the last domain also changes nothing`(): Unit =
        with(iam) {
            stubAll("NOVO" to "Valor novo")
            FnrhTestServer.stub(FnrhDomain.entries.last().path, 500, "{}")

            call(POST, refreshPath, asAdmin()).problem(503, "FNRH_UNAVAILABLE")

            rows() shouldContainExactly seed
        }

    @Test
    fun `a refresh replaces every domain, stamps every row alike and records counts in the trail`(): Unit =
        with(iam) {
            stubAll("NOVO" to "Valor novo", "OUTRO" to "Outro valor")

            val result = call(POST, refreshPath, asAdmin()).ok()

            result["domains"].toList().map { it["domain"].asString() to it["count"].asInt() } shouldContainExactly
                SURVEY.keys.map { it to 2 }
            rows().size shouldBe 20
            jdbc
                .sql("SELECT count(DISTINCT refreshed_at) FROM fnrh.domain_value")
                .query(Int::class.java)
                .single() shouldBe 1
            val trail =
                jdbc
                    .sql(
                        "SELECT outcome || '|' || entity_type || '|' || after_state::text FROM audit.audit_entry " +
                            "WHERE action = 'fnrh.domains.refresh' ORDER BY occurred_at DESC LIMIT 1",
                    ).query(String::class.java)
                    .single()
            trail.startsWith("SUCCEEDED|fnrh.domains|") shouldBe true
            trail shouldNotContain "Valor novo"
            trail shouldNotContain FnrhTestServer.PASSWORD
        }

    @Test
    fun `refreshing twice with the same data works and leaves two entries`(): Unit =
        with(iam) {
            val before = refreshEntries()
            stubAll("NOVO" to "Valor novo")

            call(POST, refreshPath, asAdmin()).ok()
            call(POST, refreshPath, asAdmin()).ok()

            rows().size shouldBe SURVEY.size
            refreshEntries() shouldBe before + 2
        }

    @Test
    fun `duplicate codes from the Serpro are collapsed, not a 500`(): Unit =
        with(iam) {
            stubAll("A" to "Primeiro", "A" to "Segundo", "B" to "Terceiro")

            val result = call(POST, refreshPath, asAdmin()).ok()

            result["domains"].toList().all { it["count"].asInt() == 2 } shouldBe true
        }

    @Test
    fun `concurrent refreshes never fail with a server error and leave a consistent cache`() {
        stubAll("NOVO" to "Valor novo", "OUTRO" to "Outro valor")
        val threads = 6
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        try {
            val futures =
                (1..threads).map {
                    pool.submit<MockHttpServletResponse> {
                        ready.countDown()
                        go.await()
                        iam.call(POST, refreshPath, iam.asAdmin())
                    }
                }
            ready.await()
            go.countDown()
            val statuses = futures.map { it.get(60, TimeUnit.SECONDS) }.map { it.status }
            withClue(statuses.toString()) { statuses.all { it == 200 } shouldBe true }
        } finally {
            pool.shutdownNow()
        }
        rows().size shouldBe SURVEY.size * 2
    }

    // ---- mapeamento de falhas e problema RFC 9457

    @Test
    fun `a 403 from the Serpro is 422 FNRH_CREDENTIALS_REJECTED with the title and no Serpro text`(): Unit =
        with(iam) {
            FnrhTestServer.stubAny(403, """{"mensagem":"MARCADOR-SERPRO 12345678901"}""")

            val problem = call(POST, refreshPath, asAdmin()).problem(422, "FNRH_CREDENTIALS_REJECTED")

            problem["status"].asInt() shouldBe 422
            problem.toString() shouldNotContain "MARCADOR-SERPRO"
            problem.toString() shouldNotContain "12345678901"
            rows() shouldContainExactly seed
        }

    @Test
    fun `a 404 from the Serpro is 502 FNRH_REJECTED with a 502 title`(): Unit =
        with(iam) {
            FnrhTestServer.stubAny(404, """{"mensagem":"MARCADOR-SERPRO"}""")

            val problem = call(POST, refreshPath, asAdmin()).problem(502, "FNRH_REJECTED")

            problem["status"].asInt() shouldBe 502
            problem["title"].asString() shouldBe "Falha no serviço externo"
            problem.toString() shouldNotContain "MARCADOR-SERPRO"
        }

    @Test
    fun `a 5xx is 503 FNRH_UNAVAILABLE with the 503 title and no Serpro text`(): Unit =
        with(iam) {
            FnrhTestServer.stubAny(500, """{"mensagem":"MARCADOR-SERPRO"}""")

            val problem = call(POST, refreshPath, asAdmin()).problem(503, "FNRH_UNAVAILABLE")

            problem["status"].asInt() shouldBe 503
            problem["title"].asString() shouldBe "Serviço indisponível"
            problem.toString() shouldNotContain "MARCADOR-SERPRO"
        }

    @Test
    fun `a 429 from the Serpro is temporary, 503`(): Unit =
        with(iam) {
            FnrhTestServer.stubAny(429, "{}")

            call(POST, refreshPath, asAdmin()).problem(503, "FNRH_UNAVAILABLE")

            rows() shouldContainExactly seed
        }

    @Test
    fun `a dropped connection is 503 and changes nothing`(): Unit =
        with(iam) {
            stubAll("NOVO" to "Valor novo")
            FnrhTestServer.server.stubFor(
                get(urlEqualTo("${FnrhTestServer.BASE_PATH}${FnrhDomain.GENDERS.path}"))
                    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
            )

            call(POST, refreshPath, asAdmin()).problem(503, "FNRH_UNAVAILABLE")

            rows() shouldContainExactly seed
        }

    @Test
    fun `a 200 that is not the expected JSON is refused and nothing changes`(): Unit =
        with(iam) {
            stubAll("NOVO" to "Valor novo")
            FnrhTestServer.stub(FnrhDomain.RACES.path, 200, "<html>ops</html>")

            val response = call(POST, refreshPath, asAdmin())

            withClue(
                response.getContentAsString(Charsets.UTF_8),
            ) { (response.status in listOf(502, 503)) shouldBe true }
            rows() shouldContainExactly seed
        }

    @Test
    fun `the first domain empty also keeps the whole cache`(): Unit =
        with(iam) {
            stubAll("NOVO" to "Valor novo")
            FnrhTestServer.stubDomain_v2_4(FnrhDomain.entries.first().path)

            call(POST, refreshPath, asAdmin()).problem(502, "FNRH_REJECTED")

            rows() shouldContainExactly seed
        }

    @Test
    fun `no long digit sequence from the Serpro body reaches the log`(output: CapturedOutput): Unit =
        with(iam) {
            FnrhTestServer.stubAny(400, """{"mensagem":"MARCADOR-LOG Fulano 12345678901"}""")

            call(POST, refreshPath, asAdmin()).problem(502, "FNRH_REJECTED")

            output.all shouldNotContain "12345678901"
        }

    // ---- nomes

    @Test
    fun `hostile or near-miss names are 404 FNRH_DOMAIN_NOT_FOUND`(): Unit =
        with(iam) {
            val names =
                listOf(
                    "Transport-Modes",
                    "TRANSPORT-MODES",
                    "transport_modes",
                    "transportmodes",
                    "transport-modes-",
                    "reservation-statuses",
                    "form-statuses",
                    "refresh",
                )
            for (name in names) {
                val response = call(GET, "/api/v1/fnrh/domains/$name", asAdmin())
                withClue("nome '$name': ${response.getContentAsString(Charsets.UTF_8)}") {
                    response.status shouldBe 404
                    json(response)["code"].asString() shouldBe "FNRH_DOMAIN_NOT_FOUND"
                }
            }
        }

    @Test
    fun `injection in the path is never served and never a 500`(): Unit =
        with(iam) {
            for (name in listOf("transport-modes;x", "x'%20OR%201=1--", "%25s%25n", "..", "transport-modes%00")) {
                val response = call(GET, "/api/v1/fnrh/domains/$name", asAdmin())
                withClue("nome '$name': ${response.getContentAsString(Charsets.UTF_8)}") {
                    (response.status in listOf(400, 404)) shouldBe true
                    response.getContentAsString(Charsets.UTF_8) shouldNotContain "ONIBUS"
                }
            }
        }

    @Test
    fun `an unknown domain name needs the read permission before it says 404`(): Unit =
        with(iam) {
            call(GET, "/api/v1/fnrh/domains/nada", asTestUser(createUserWith())).problem(403, "PERMISSION_DENIED")
        }

    // ---- permissões

    @Test
    fun `without a token both routes are 401`(): Unit =
        with(iam) {
            val anonymous = RequestPostProcessor { it }
            call(GET, "/api/v1/fnrh/domains/races", anonymous).status shouldBe 401
            call(POST, refreshPath, anonymous).status shouldBe 401
            rows() shouldContainExactly seed
        }

    @Test
    fun `manager and receptionist read, the room attendant gets 403, only the administrator refreshes`(): Unit =
        with(iam) {
            stubAll("NOVO" to "Valor novo")
            for (template in listOf(RoleTemplate.MANAGER, RoleTemplate.RECEPTIONIST)) {
                val user = userOf(template)
                withClue(template.name) {
                    call(GET, "/api/v1/fnrh/domains/races", asTestUser(user)).ok().size() shouldBe 6
                    call(POST, refreshPath, asTestUser(user)).problem(403, "PERMISSION_DENIED")
                }
            }
            val attendant = userOf(RoleTemplate.ROOM_ATTENDANT)
            call(GET, "/api/v1/fnrh/domains/races", asTestUser(attendant)).problem(403, "PERMISSION_DENIED")
            call(POST, refreshPath, asTestUser(attendant)).problem(403, "PERMISSION_DENIED")
            rows() shouldContainExactly seed

            call(POST, refreshPath, asAdmin()).ok()
        }

    @Test
    fun `a user with only the manage permission refreshes but cannot read`(): Unit =
        with(iam) {
            val manager = createUserWith(TestGrant(def(FnrhPermissions.SETTINGS_MANAGE.value), ALL))
            stubAll("NOVO" to "Valor novo")

            call(GET, "/api/v1/fnrh/domains/races", asTestUser(manager)).problem(403, "PERMISSION_DENIED")
            call(POST, refreshPath, asTestUser(manager)).ok()
        }

    @Test
    fun `the wrong verbs are not routed`(): Unit =
        with(iam) {
            call(POST, "/api/v1/fnrh/domains/races", asAdmin()).status shouldBe 405
            call(GET, refreshPath, asAdmin()).problem(404, "FNRH_DOMAIN_NOT_FOUND")
        }

    // ---- valores hostis

    @Test
    fun `a huge label is refused or bounded, never a 500 and never the whole cache lost`(): Unit =
        with(iam) {
            stubAll("NOVO" to "Valor novo")
            val huge = "X".repeat(1_000_000)
            stubRaw(FnrhDomain.GENDERS, "NOVO" to huge)

            val response = call(POST, refreshPath, asAdmin())

            withClue("status ${response.status}") { (response.status in listOf(200, 502)) shouldBe true }
            if (response.status == 200) {
                val label = call(GET, "/api/v1/fnrh/domains/genders", asAdmin()).ok().single()["label"].asString()
                withClue("tamanho do rótulo guardado: ${label.length}") { (label.length <= MAX_LABEL) shouldBe true }
            } else {
                rows() shouldContainExactly seed
            }
        }

    @Test
    fun `a label with control characters is refused or cleaned, never stored raw`(): Unit =
        with(iam) {
            stubAll("NOVO" to "Valor novo")
            stubRaw(FnrhDomain.GENDERS, "NOVO" to "Linha1\r\nLinha2\u0007\u001b[31mvermelho\u0085fim")

            val response = call(POST, refreshPath, asAdmin())

            withClue("status ${response.status}") { (response.status in listOf(200, 502)) shouldBe true }
            if (response.status == 200) {
                val label = call(GET, "/api/v1/fnrh/domains/genders", asAdmin()).ok().single()["label"].asString()
                label.any { it.isISOControl() } shouldBe false
            } else {
                rows() shouldContainExactly seed
            }
        }

    @Test
    fun `a NUL in a label is not a 500 and the failed write leaves the cache untouched`(): Unit =
        with(iam) {
            val before = refreshEntries()
            stubAll("NOVO" to "Valor novo")
            stubRaw(FnrhDomain.RACES, "NOVO" to "a\u0000b")

            val response = call(POST, refreshPath, asAdmin())

            withClue("status ${response.status}: ${response.getContentAsString(Charsets.UTF_8)}") {
                (response.status in listOf(200, 502)) shouldBe true
            }
            if (response.status != 200) {
                rows() shouldContainExactly seed
                refreshEntries() shouldBe before
            }
        }

    @Test
    fun `a code with control characters or a blank code is not stored`(): Unit =
        with(iam) {
            stubAll("NOVO" to "Valor novo")
            stubRaw(FnrhDomain.GENDERS, "CODIGO\nQUEBRADO" to "x", "  " to "branco", "OK" to "Ok")

            val response = call(POST, refreshPath, asAdmin())

            if (response.status == 200) {
                val codes =
                    call(
                        GET,
                        "/api/v1/fnrh/domains/genders",
                        asAdmin(),
                    ).ok().toList().map { it["code"].asString() }
                codes.none { c -> c.isBlank() || c.any { it.isISOControl() } } shouldBe true
            } else {
                rows() shouldContainExactly seed
            }
        }

    // ---- apoio

    private fun def(code: String) = FnrhPermissions.permissions.single { it.code.value == code }

    private fun userOf(template: RoleTemplate): TestUser =
        iam.createUserWith(
            *FnrhPermissions.permissions
                .filter { template in it.templates.keys }
                .map { TestGrant(it, ALL) }
                .toTypedArray(),
        )

    private fun stubAll(vararg values: Pair<String, String>) {
        for (domain in FnrhDomain.entries) FnrhTestServer.stubDomain_v2_4(domain.path, *values)
    }

    /** Como [stubAll], mas para um domínio, com o JSON escapado de verdade (caracteres de controle incluídos). */
    private fun stubRaw(
        domain: FnrhDomain,
        vararg values: Pair<String, String>,
    ) {
        val body =
            mapper.writeValueAsString(mapOf("dados" to values.map { mapOf("id" to it.first, "label" to it.second) }))
        FnrhTestServer.stub(domain.path, 200, body)
    }

    private fun JsonNode.pairs(): List<Pair<String, String>> =
        toList().map { it["code"].asString() to it["label"].asString() }

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

    private companion object {
        const val MAX_LABEL = 255

        /** O levantamento (docs/fnrh/api-fnrh-v2.4.md, tabela "Domínios"), digitado à parte da migração. */
        val SURVEY: Map<String, List<Pair<String, String>>> =
            linkedMapOf(
                "transport-modes" to
                    listOf(
                        "ONIBUS" to "Ônibus",
                        "AUTOMOVEL" to "Automóvel",
                        "AVIAO" to "Avião",
                        "MOTO" to "Moto",
                        "NAVIO_BARCO" to "Navio/Barco",
                        "PE" to "A pé",
                        "TREM" to "Trem",
                        "BICICLETA" to "Bicicleta",
                    ),
                "travel-reasons" to
                    listOf(
                        "COMPRAS" to "Compras",
                        "CONGRESSO_FEIRA" to "Congresso/Feira",
                        "ESTUDOS_CURSOS" to "Estudos/Cursos",
                        "LAZER_FERIAS" to "Lazer/Férias",
                        "NEGOCIOS" to "Negócios",
                        "PARENTES_AMIGOS" to "Parentes/Amigos",
                        "RELIGIAO" to "Religião",
                        "SAUDE" to "Saúde",
                    ),
                "guest-statuses" to
                    listOf(
                        "CANCELADO" to "Cancelado",
                        "CHECKIN_REALIZADO" to "Check-in realizado",
                        "CHECKOUT_REALIZADO" to "Check-out realizado",
                        "NOSHOW" to "No-Show",
                        "PRECHECKIN_NAOVINCULADO" to "Pré-check-in não vinculado",
                        "PRECHECKIN_PENDENTE" to "Pré-check-in pendente",
                        "PRECHECKIN_REALIZADO" to "Pré-check-in realizado",
                    ),
                "genders" to
                    listOf(
                        "HOMEM" to "Masculino",
                        "MULHER" to "Feminino",
                        "OUTRO" to "Outros",
                        "NAOINFORMADO" to "Prefiro não informar",
                    ),
                "disability-options" to
                    listOf("SIM" to "Sim", "NAO" to "Não", "NAOINFORMAR" to "Prefiro não informar"),
                "races" to
                    listOf(
                        "AMARELA" to "Amarelo",
                        "BRANCA" to "Branco",
                        "INDIGENA" to "Indígena",
                        "PARDA" to "Pardo",
                        "PRETA" to "Preto",
                        "NAOINFORMAR" to "Prefiro não informar",
                    ),
                "disability-types" to
                    listOf(
                        "FISICA" to "Deficiência Física",
                        "AUDITIVA_SURDEZ" to "Deficiência Auditiva/Surdez",
                        "VISUAL" to "Deficiência Visual",
                        "INTELECTUAL" to "Deficiência Intelectual",
                        "MULTIPLA" to "Deficiência Múltipla",
                    ),
                "document-types" to listOf("CPF" to "CPF", "PASSAPORTE" to "Passaporte"),
                "booking-statuses" to
                    listOf(
                        "CRIADA" to "Criada",
                        "EM_ANDAMENTO" to "Em Andamento",
                        "CONCLUIDA" to "Concluída",
                        "CANCELADA" to "Cancelada",
                        "NOSHOW" to "No-Show",
                    ),
                "record-statuses" to
                    listOf(
                        "AGUARDANDO_CHECKIN" to "Aguardando Check-in",
                        "AGUARDANDO_CHECKOUT" to "Aguardando Check-out",
                        "CHECK_OUT_CONFIRMADO" to "Check-out confirmado",
                        "INVALIDADA" to "Invalidada",
                        "NO_SHOW" to "No show",
                    ),
            )
    }
}
