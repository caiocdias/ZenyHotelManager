package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhRequesterDirectory
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldContainExactly
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
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.UUID

/** O CPF solicitante por usuário da FNRH pela API (M5 E07): cifrado, mascarado, só com `fnrh.settings.manage`. */
@IntegrationTest
@ExtendWith(OutputCaptureExtension::class)
@Import(IamTestConfiguration::class)
class FnrhRequestersApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val directory: FnrhRequesterDirectory,
) {
    private val base = "/api/v1/fnrh/requesters"
    private val cpf = "529.982.247-25"
    private val userId = UUID.randomUUID()

    @BeforeEach
    @AfterEach
    fun clean() {
        jdbc.sql("DELETE FROM fnrh.requester").update()
    }

    private fun rows(): Long = jdbc.sql("SELECT count(*) FROM fnrh.requester").query(Long::class.java).single()

    @Test
    fun `a PUT then a GET shows only the masked CPF`(): Unit =
        with(iam) {
            val put = call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to cpf))

            val written = put.ok()
            written["userId"].asString() shouldBe userId.toString()
            written["cpf"].asString() shouldBe "***.***.***-25"
            val read = call(GET, base, asAdmin())
            val items = read.ok().values().toList()
            items.map { it["userId"].asString() to it["cpf"].asString() } shouldContainExactly
                listOf(userId.toString() to "***.***.***-25")
            for (text in listOf(put, read).map { it.getContentAsString(Charsets.UTF_8) }) {
                text shouldNotContain cpf
                text shouldNotContain "52998224725"
            }
        }

    @Test
    fun `a second PUT replaces the CPF of the same user`(): Unit =
        with(iam) {
            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to cpf)).ok()

            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to "111.444.777-35"))
                .ok()["cpf"]
                .asString() shouldBe "***.***.***-35"

            rows() shouldBe 1L
            directory.cpfOf(userId) shouldBe Cpf.of("11144477735")
        }

    @Test
    fun `the column does not hold the clear text`(): Unit =
        with(iam) {
            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to cpf)).ok()

            val stored = jdbc.sql("SELECT cpf FROM fnrh.requester").query(String::class.java).single()

            stored shouldNotContain "52998224725"
            stored shouldNotContain cpf
            directory.cpfOf(userId) shouldBe Cpf.of("52998224725")
            directory.cpfOf(UUID.randomUUID()) shouldBe null
        }

    @Test
    fun `an invalid CPF is a 400 on the field without echoing the value`(): Unit =
        with(iam) {
            val response = call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to "111.111.111-11"))

            val problem = response.problem(400, "VALIDATION_FAILED")
            problem["errors"].values().map { it["field"].asString() to it["code"].asString() } shouldContainExactly
                listOf("cpf" to "INVALID_FORMAT")
            val text = response.getContentAsString(Charsets.UTF_8)
            text shouldNotContain "111.111.111-11"
            text shouldNotContain "11111111111"
            rows() shouldBe 0L
        }

    @Test
    fun `no request, accepted or refused, writes the CPF to the logs`(output: CapturedOutput): Unit =
        with(iam) {
            val logStart = output.all.length

            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to cpf)).ok()
            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to "111.111.111-11")).status shouldBe 400
            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to "lixo-529.982.247-25")).status shouldBe 400
            call(GET, base, asAdmin()).ok()
            call(POST, "$base/$userId/deactivate", asAdmin()).status shouldBe 204
            call(POST, "$base/$userId/deactivate", asAdmin()).status shouldBe 404

            val logs = output.all.substring(logStart)
            for (secret in listOf(cpf, "52998224725", "111.111.111-11", "11111111111")) logs shouldNotContain secret
        }

    @Test
    fun `a deactivation erases the CPF but keeps the row, and a second one is 404`(): Unit =
        with(iam) {
            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to cpf)).ok()

            call(POST, "$base/$userId/deactivate", asAdmin()).status shouldBe 204
            call(GET, base, asAdmin())
                .ok()
                .values()
                .toList()
                .size shouldBe 0
            call(POST, "$base/$userId/deactivate", asAdmin()).problem(404, "FNRH_REQUESTER_NOT_FOUND")
        }

    @Test
    fun `a PUT reactivates a deactivated user`(): Unit =
        with(iam) {
            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to cpf)).ok()
            call(POST, "$base/$userId/deactivate", asAdmin()).status shouldBe 204

            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to "111.444.777-35")).ok()["cpf"].asString() shouldBe
                "***.***.***-35"

            rows() shouldBe 1L
            directory.cpfOf(userId) shouldBe Cpf.of("11144477735")
            call(GET, base, asAdmin())
                .ok()
                .values()
                .toList()
                .size shouldBe 1
        }

    @Test
    fun `an unknown user cannot be deactivated`(): Unit =
        with(iam) {
            call(POST, "$base/$userId/deactivate", asAdmin()).problem(404, "FNRH_REQUESTER_NOT_FOUND")
            rows() shouldBe 0L
        }

    @Test
    fun `a receptionist is forbidden everywhere and an anonymous caller is unauthenticated`(): Unit =
        with(iam) {
            val receptionist =
                iam.createUserWith(
                    TestGrant(FnrhPermissions.permissions.single { it.code == FnrhPermissions.DOMAINS_READ }, ALL),
                )
            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to cpf)).ok()

            call(GET, base, asTestUser(receptionist)).problem(403, "PERMISSION_DENIED")
            call(PUT, "$base/$userId", asTestUser(receptionist), mapOf("cpf" to cpf)).problem(403, "PERMISSION_DENIED")
            call(POST, "$base/$userId/deactivate", asTestUser(receptionist)).problem(403, "PERMISSION_DENIED")
            mockMvc
                .get(base)
                .andReturn()
                .response.status shouldBe 401
            rows() shouldBe 1L
        }

    @Test
    fun `the audit trail records the changes with the masked CPF only`(): Unit =
        with(iam) {
            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to cpf)).ok()
            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to "111.444.777-35")).ok()
            call(POST, "$base/$userId/deactivate", asAdmin())

            val entries =
                jdbc
                    .sql(
                        "SELECT CAST(before_state AS text) AS b, CAST(after_state AS text) AS a " +
                            "FROM audit.audit_entry WHERE entity_type = 'fnrh.requester' AND entity_id = :id " +
                            "ORDER BY occurred_at, id",
                    ).param("id", userId.toString())
                    .query { rs, _ -> rs.getString("b") to rs.getString("a") }
                    .list()

            entries.size shouldBe 3
            for ((before, after) in entries) {
                val text = before.orEmpty() + after.orEmpty()
                for (secret in listOf(cpf, "52998224725", "111.444.777-35", "11144477735")) text shouldNotContain secret
            }
            entries[1].second.contains("***.***.***-35") shouldBe true
            entries[2].first.contains("***.***.***-35") shouldBe true
            entries[2].second.replace(" ", "").contains("\"active\":false") shouldBe true
            entries[2].second.contains("***") shouldBe false
        }
}
