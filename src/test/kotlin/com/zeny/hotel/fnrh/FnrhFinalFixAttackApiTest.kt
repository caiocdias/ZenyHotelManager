package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhGateway
import com.zeny.hotel.fnrh.domain.FnrhDocument
import com.zeny.hotel.fnrh.domain.FnrhDocumentType
import com.zeny.hotel.fnrh.domain.FnrhDomain
import com.zeny.hotel.fnrh.domain.FnrhPersonData
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.testsupport.FnrhTestConfiguration
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.enableFnrh
import com.zeny.hotel.testsupport.resetFnrhSettings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Ataques às correções da revisão final da E07 que passam pelo banco ou pela API (I1, M3, M4 e M5). */
@IntegrationTest
@Import(IamTestConfiguration::class, FnrhTestConfiguration::class)
class FnrhFinalFixAttackApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val gateway: FnrhGateway,
) {
    private val mapper = JsonMapper.builder().build()
    private val settingsPath = "/api/v1/fnrh/settings"
    private val userId = UUID.randomUUID()
    private val person =
        FnrhPersonData(
            name = "MARIA DA LUZ",
            race = "PARDA",
            disabilityOption = "NAO",
            birthDate = LocalDate.parse("1990-05-17"),
            document = FnrhDocument(FnrhDocumentType.CPF, "52998224725"),
        )

    @BeforeEach
    fun setUp() {
        FnrhTestServer.reset()
        iam.enableFnrh()
        jdbc.sql("DELETE FROM fnrh.requester").update()
    }

    @AfterEach
    fun tearDown() {
        jdbc.sql("DELETE FROM fnrh.requester").update()
        jdbc.resetFnrhSettings()
        FnrhTestServer.reset()
    }

    private companion object {
        const val X10 = "xxxxxxxxxx"
        const val LONG_101 = X10 + X10 + X10 + X10 + X10 + X10 + X10 + X10 + X10 + X10 + "x"
    }

    // ---- I1

    private fun versionOf(id: UUID) =
        jdbc
            .sql("SELECT version FROM fnrh.requester WHERE user_id = :id")
            .param("id", id)
            .query(Long::class.java)
            .single()

    private fun auditStates(id: UUID) =
        jdbc
            .sql(
                "SELECT CAST(before_state AS text) AS b, CAST(after_state AS text) AS a " +
                    "FROM audit.audit_entry WHERE entity_type = 'fnrh.requester' AND entity_id = :id " +
                    "ORDER BY occurred_at, id",
            ).param("id", id.toString())
            .query { rs, _ -> rs.getString("b") to rs.getString("a") }
            .list()

    @Test
    fun `the requester version rises with each swap and the trail snapshots carry it`(): Unit =
        with(iam) {
            val cpfs = listOf("529.982.247-25", "123.456.880-25", "111.444.777-35", "529.982.247-25")
            for (cpf in cpfs) call(PUT, "/api/v1/fnrh/requesters/$userId", asAdmin(), mapOf("cpf" to cpf)).ok()

            versionOf(userId) shouldBe 3L
            val states = auditStates(userId)
            states.size shouldBe 4
            // O retrato "antes" de cada troca traz a versão anterior, o "depois" da primeira entrada traz a inicial.
            states.drop(1).map { (before, _) -> before!!.contains("\"version\"") } shouldBe listOf(true, true, true)
        }

    @Test
    fun `swapping the CPF for another with the same ending produces two different snapshots`(): Unit =
        with(iam) {
            call(PUT, "/api/v1/fnrh/requesters/$userId", asAdmin(), mapOf("cpf" to "529.982.247-25")).ok()
            call(PUT, "/api/v1/fnrh/requesters/$userId", asAdmin(), mapOf("cpf" to "123.456.880-25")).ok()

            val (before, after) = auditStates(userId).last()

            (before != after) shouldBe true
        }

    @Test
    fun `concurrent PUTs on the same existing user never end in a server error`(): Unit =
        with(iam) {
            call(PUT, "/api/v1/fnrh/requesters/$userId", asAdmin(), mapOf("cpf" to "529.982.247-25")).ok()
            val cpfs = listOf("123.456.880-25", "111.444.777-35")
            val pool = Executors.newFixedThreadPool(cpfs.size)
            val start = CountDownLatch(1)
            val admin = asAdmin()
            val statuses =
                try {
                    cpfs
                        .map { cpf ->
                            pool.submit<Int> {
                                start.await()
                                call(PUT, "/api/v1/fnrh/requesters/$userId", admin, mapOf("cpf" to cpf)).status
                            }
                        }.also { start.countDown() }
                        .map { it.get(30, TimeUnit.SECONDS) }
                } finally {
                    pool.shutdownNow()
                }

            println("FNRH-CONCURRENT-PUT statuses=$statuses")
            statuses.none { it >= 500 } shouldBe true
            (200 in statuses) shouldBe true
            versionOf(userId) shouldBe statuses.count { it == 200 }.toLong()
        }

    // ---- M4

    @ParameterizedTest
    @ValueSource(strings = ["com espaco", "a/b", "ação", "id\nquebra", LONG_101])
    fun `a malformed remote id is recorded as NULL and the call result is untouched`(id: String) {
        FnrhTestServer.stubAny(200, mapper.writeValueAsString(mapOf("pessoa_id" to id)))
        val correlation = "corr-${UUID.randomUUID()}"

        gateway.registerPerson(person, Cpf.of("52998224725"), correlation).value shouldBe id

        val stored =
            jdbc
                .sql("SELECT remote_id FROM fnrh.integration_call WHERE correlation_ref = :ref")
                .param("ref", correlation)
                .query(String::class.java)
                .list()
        stored.size shouldBe 1
        stored.single().shouldBeNull()
    }

    @Test
    fun `a remote id of exactly 100 allowed characters is kept`() {
        val id = "a".repeat(98) + "._"
        FnrhTestServer.stubAny(200, mapper.writeValueAsString(mapOf("pessoa_id" to id)))
        val correlation = "corr-${UUID.randomUUID()}"

        gateway.registerPerson(person, Cpf.of("52998224725"), correlation)

        jdbc
            .sql("SELECT remote_id FROM fnrh.integration_call WHERE correlation_ref = :ref")
            .param("ref", correlation)
            .query(String::class.java)
            .single() shouldBe id
    }

    @ParameterizedTest
    @ValueSource(strings = ["com espaco", "a/b", "ação", "", LONG_101, "a\nb"])
    fun `the CHECK refuses a remote id outside the format straight in SQL`(id: String) {
        shouldThrow<DataIntegrityViolationException> {
            jdbc
                .sql(
                    "INSERT INTO fnrh.integration_call " +
                        "(operation, environment, outcome, duration_ms, remote_id, occurred_at) " +
                        "VALUES ('registerPerson', 'HOMOLOGATION', 'SUCCESS', 1, :id, now())",
                ).param("id", id)
                .update()
        }
    }

    @Test
    fun `the CHECK accepts a NULL and a well-formed remote id straight in SQL`() {
        for (id in listOf(null, "ok-1._", "x".repeat(100))) {
            jdbc
                .sql(
                    "INSERT INTO fnrh.integration_call " +
                        "(operation, environment, outcome, duration_ms, remote_id, occurred_at) " +
                        "VALUES ('registerPerson', 'HOMOLOGATION', 'SUCCESS', 1, :id, now())",
                ).param("id", id)
                .update() shouldBe 1
        }
    }

    // ---- M3

    @ParameterizedTest
    @ValueSource(strings = ["Valor\u202Ereverso", "Valor\u200Boculto", "Valor\u2066isolado", "Valor\u00ADhifen"])
    fun `a label with a Unicode formatting character is refused with 502, the cache untouched`(bad: String): Unit =
        with(iam) {
            val before = labels()
            for (domain in FnrhDomain.entries) FnrhTestServer.stubDomain_v2_4(domain.path, "NOVO" to "Valor novo")
            val body = mapper.writeValueAsString(mapOf("dados" to listOf(mapOf("id" to "NOVO", "label" to bad))))
            FnrhTestServer.stub(FnrhDomain.GENDERS.path, 200, body)

            call(POST, "/api/v1/fnrh/domains/refresh", asAdmin()).problem(502, "FNRH_REJECTED")

            labels() shouldBe before
        }

    private fun labels(): List<String> =
        jdbc
            .sql("SELECT domain || ':' || code || ':' || label FROM fnrh.domain_value ORDER BY 1")
            .query(String::class.java)
            .list()
            .filterNotNull()

    // ---- M5

    @ParameterizedTest
    @ValueSource(strings = ["\n", "\r\n", " \n ", "\t\n\r", "   "])
    fun `a password made only of whitespace and line breaks is refused with 400`(password: String): Unit =
        with(iam) {
            val body =
                mapOf(
                    "environment" to "HOMOLOGATION",
                    "username" to "hotel-user",
                    "defaultRequesterCpf" to "529.982.247-25",
                    "enabled" to true,
                    "password" to password,
                )
            val etag = call(GET, settingsPath, asAdmin()).getHeader(HttpHeaders.ETAG)

            call(PUT, settingsPath, asAdmin(), body, ifMatch = etag).problem(400, "VALIDATION_FAILED")
        }
}
