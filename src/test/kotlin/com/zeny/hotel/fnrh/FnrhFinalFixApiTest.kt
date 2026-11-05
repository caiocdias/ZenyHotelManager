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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * Correções da revisão final da E07 que passam pelo banco ou pela API: a trilha do CPF solicitante (I1), o id remoto
 * do registro de chamadas (M4), os rótulos com caracteres de formatação (M3) e a senha com quebra de linha (M5).
 */
@IntegrationTest
@Import(IamTestConfiguration::class, FnrhTestConfiguration::class)
class FnrhFinalFixApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val gateway: FnrhGateway,
) {
    private val mapper = JsonMapper.builder().build()
    private val settingsPath = "/api/v1/fnrh/settings"
    private val userId = UUID.randomUUID()

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

    // ---- I1

    @Test
    fun `swapping the requester CPF for another with the same ending leaves an audit entry, with no clear CPF`(): Unit =
        with(iam) {
            val first = "529.982.247-25"
            val second = "123.456.880-25" // outro CPF válido, com os mesmos 2 últimos dígitos
            call(PUT, "/api/v1/fnrh/requesters/$userId", asAdmin(), mapOf("cpf" to first)).ok()
            call(PUT, "/api/v1/fnrh/requesters/$userId", asAdmin(), mapOf("cpf" to second)).ok()

            val entries =
                jdbc
                    .sql(
                        "SELECT CAST(before_state AS text) AS b, CAST(after_state AS text) AS a " +
                            "FROM audit.audit_entry WHERE entity_type = 'fnrh.requester' AND entity_id = :id " +
                            "ORDER BY occurred_at, id",
                    ).param("id", userId.toString())
                    .query { rs, _ -> rs.getString("b").orEmpty() + rs.getString("a").orEmpty() }
                    .list()

            entries.size shouldBe 2
            for (text in entries) {
                for (secret in listOf(first, "52998224725", second, "12345688025")) {
                    text shouldNotContain secret
                }
            }
        }

    // ---- M4

    @Test
    fun `a remote id outside the allowed format is recorded as NULL and the call keeps its result`() {
        val person =
            FnrhPersonData(
                name = "MARIA DA LUZ",
                race = "PARDA",
                disabilityOption = "NAO",
                birthDate = LocalDate.parse("1990-05-17"),
                document = FnrhDocument(FnrhDocumentType.CPF, "52998224725"),
            )
        for ((id, expectNull) in listOf("com espaco" to true, "a/b" to true, "id-ok.1_2" to false)) {
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
            if (expectNull) stored.single().shouldBeNull() else stored.single() shouldBe id
        }
    }

    // ---- M3

    @Test
    fun `a label with a Unicode formatting character is refused whole with 502 and the cache is untouched`(): Unit =
        with(iam) {
            val before = labels()
            for (bad in listOf("Valor‮reverso", "Valor​oculto")) {
                for (domain in FnrhDomain.entries) FnrhTestServer.stubDomain_v2_4(domain.path, "NOVO" to "Valor novo")
                val body = mapper.writeValueAsString(mapOf("dados" to listOf(mapOf("id" to "NOVO", "label" to bad))))
                FnrhTestServer.stub(FnrhDomain.GENDERS.path, 200, body)

                call(POST, "/api/v1/fnrh/domains/refresh", asAdmin()).problem(502, "FNRH_REJECTED")

                labels() shouldBe before
            }
        }

    private fun labels(): List<String> =
        jdbc
            .sql("SELECT domain || ':' || code || ':' || label FROM fnrh.domain_value ORDER BY 1")
            .query(String::class.java)
            .list()
            .filterNotNull()

    // ---- M5

    @Test
    fun `a password with a line break is accepted, a password of only line breaks is not`(): Unit =
        with(iam) {
            val body =
                mapOf(
                    "environment" to "HOMOLOGATION",
                    "username" to "hotel-user",
                    "defaultRequesterCpf" to "529.982.247-25",
                    "enabled" to true,
                )

            fun etag() = call(GET, settingsPath, asAdmin()).getHeader(HttpHeaders.ETAG)

            call(PUT, settingsPath, asAdmin(), body + ("password" to "senha\ncom\nquebra"), ifMatch = etag()).ok()
            call(PUT, settingsPath, asAdmin(), body + ("password" to "\n\r\n"), ifMatch = etag())
                .problem(400, "VALIDATION_FAILED")
        }
}
