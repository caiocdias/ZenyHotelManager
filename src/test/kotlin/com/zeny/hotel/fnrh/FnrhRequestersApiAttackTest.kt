package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhRequesterDirectory
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
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
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpMethod.DELETE
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.util.UUID

/**
 * Ataques do testador ao CPF solicitante por usuário (M5 E07, Task 3): papéis reais, id de usuário malformado, entradas
 * inválidas, diretório depois de PUT e desativação e CPF em claro em resposta, trilha, log e coluna.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension::class)
@Import(IamTestConfiguration::class)
class FnrhRequestersApiAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val directory: FnrhRequesterDirectory,
) {
    private val base = "/api/v1/fnrh/requesters"
    private val cpf = "529.982.247-25"
    private val digits = "52998224725"
    private val userId = UUID.randomUUID()

    @BeforeEach
    @AfterEach
    fun clean() {
        jdbc.sql("DELETE FROM fnrh.requester").update()
    }

    private fun rows(): Long = jdbc.sql("SELECT count(*) FROM fnrh.requester").query(Long::class.java).single()

    private fun activeRows(): Long =
        jdbc.sql("SELECT count(*) FROM fnrh.requester WHERE active").query(Long::class.java).single()

    private fun IamTestSupport.userWithTemplateRole(roleName: String): TestUser {
        val roleId =
            jdbc
                .sql("SELECT id FROM iam.role WHERE name = :name AND active")
                .param("name", roleName)
                .query(UUID::class.java)
                .single()
        return createUser().also { assignRole(it, RoleId(roleId)) }
    }

    @Test
    fun `an anonymous caller gets 401 on PUT and deactivate and nothing is written`() {
        mockMvc
            .put("$base/$userId") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"cpf":"$cpf"}"""
            }.andReturn()
            .response.status shouldBe 401
        mockMvc
            .post("$base/$userId/deactivate")
            .andReturn()
            .response.status shouldBe 401
        rows() shouldBe 0L
    }

    @Test
    fun `the real Manager and Receptionist roles are refused with 403 on GET, PUT and deactivate`(): Unit =
        with(iam) {
            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to cpf)).ok()
            for (roleName in listOf("Gerente", "Recepcionista")) {
                val user = userWithTemplateRole(roleName)

                val responses =
                    listOf(
                        call(GET, base, asTestUser(user)),
                        call(PUT, "$base/$userId", asTestUser(user), mapOf("cpf" to "111.444.777-35")),
                        call(POST, "$base/$userId/deactivate", asTestUser(user)),
                        call(POST, "$base/${UUID.randomUUID()}/deactivate", asTestUser(user)),
                    )
                for (response in responses) {
                    response.problem(403, "PERMISSION_DENIED")
                    response.getContentAsString(Charsets.UTF_8) shouldNotContain digits
                }
            }
            rows() shouldBe 1L
            directory.cpfOf(userId) shouldBe Cpf.of(digits)
        }

    @Test
    fun `a user id that is not a UUID is a 400 on PUT and deactivate and nothing is written`(): Unit =
        with(iam) {
            for (bad in listOf("abc", "123", "not-a-uuid", "00000000-0000-0000-0000-00000000000Z")) {
                call(PUT, "$base/$bad", asAdmin(), mapOf("cpf" to cpf)).expectStatus(400)
                call(POST, "$base/$bad/deactivate", asAdmin()).expectStatus(400)
            }
            rows() shouldBe 0L
        }

    @Test
    fun `invalid CPFs are 400 without echoing the value and without writing`(): Unit =
        with(iam) {
            val invalid =
                listOf(
                    "529.982.247-24",
                    "52998224724",
                    "",
                    "   ",
                    "529.982.24725",
                    "529982.247-25",
                    "5299822472",
                    "529982247255",
                    "000.000.000-00",
                    "abc",
                )
            for (value in invalid) {
                val response = call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to value))
                response.status shouldBe 400
                val text = response.getContentAsString(Charsets.UTF_8)
                text shouldNotContain digits
                if (value.isNotBlank()) text shouldNotContain value
            }
            rows() shouldBe 0L
        }

    @Test
    fun `a missing, null or non-string cpf and a malformed body are 400 and write nothing`(): Unit =
        with(iam) {
            call(PUT, "$base/$userId", asAdmin(), emptyMap<String, Any>()).expectStatus(400)
            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to null)).expectStatus(400)
            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to 52998224725L)).expectStatus(400)
            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to listOf(cpf))).expectStatus(400)
            call(PUT, "$base/$userId", asAdmin()).status shouldBe 400
            val broken =
                mockMvc
                    .put("$base/$userId") {
                        with(asAdmin())
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"cpf": "$cpf""""
                    }.andReturn()
                    .response
            broken.status shouldBe 400
            broken.getContentAsString(Charsets.UTF_8) shouldNotContain digits
            rows() shouldBe 0L
        }

    @Test
    fun `a punctuated CPF and a plain one are stored as the same digits`(): Unit =
        with(iam) {
            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to cpf)).ok()
            directory.cpfOf(userId) shouldBe Cpf.of(digits)

            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to digits))
                .ok()["cpf"]
                .asString() shouldBe "***.***.***-25"

            directory.cpfOf(userId) shouldBe Cpf.of(digits)
            rows() shouldBe 1L
        }

    @Test
    fun `the directory follows PUT and deactivate`(): Unit =
        with(iam) {
            directory.cpfOf(userId) shouldBe null

            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to cpf)).ok()
            directory.cpfOf(userId) shouldBe Cpf.of(digits)

            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to "111.444.777-35")).ok()
            directory.cpfOf(userId) shouldBe Cpf.of("11144477735")

            call(POST, "$base/$userId/deactivate", asAdmin()).status shouldBe 204
            directory.cpfOf(userId) shouldBe null
            activeRows() shouldBe 0L
        }

    @Test
    fun `users are independent and the list shows the remaining one masked`(): Unit =
        with(iam) {
            val other = UUID.randomUUID()
            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to cpf)).ok()
            call(PUT, "$base/$other", asAdmin(), mapOf("cpf" to "111.444.777-35")).ok()

            call(POST, "$base/$userId/deactivate", asAdmin()).status shouldBe 204

            directory.cpfOf(other) shouldBe Cpf.of("11144477735")
            val response = call(GET, base, asAdmin())
            response
                .ok()
                .values()
                .toList()
                .map { it["userId"].asString() to it["cpf"].asString() } shouldContainExactlyInAnyOrder
                listOf(other.toString() to "***.***.***-35")
            response.getContentAsString(Charsets.UTF_8) shouldNotContain "11144477735"
        }

    @Test
    fun `a 404 on deactivate and an empty GET never carry a CPF`(): Unit =
        with(iam) {
            val response = call(POST, "$base/$userId/deactivate", asAdmin())

            response.problem(404, "FNRH_REQUESTER_NOT_FOUND")
            response.getContentAsString(Charsets.UTF_8) shouldNotContain digits
            call(GET, base, asAdmin()).ok().size() shouldBe 0
        }

    @Test
    fun `the stored column holds neither the digits nor the formatted CPF for any user`(): Unit =
        with(iam) {
            val other = UUID.randomUUID()
            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to cpf)).ok()
            call(PUT, "$base/$other", asAdmin(), mapOf("cpf" to cpf)).ok()

            val stored = jdbc.sql("SELECT cpf FROM fnrh.requester").query(String::class.java).list()

            stored.size shouldBe 2
            for (value in stored) {
                value shouldNotContain digits
                value shouldNotContain cpf
            }
        }

    @Test
    fun `refused and failed requests never write the CPF to logs or leave audit entries`(output: CapturedOutput): Unit =
        with(iam) {
            val start = output.all.length
            val receptionist = userWithTemplateRole("Recepcionista")

            call(PUT, "$base/$userId", asTestUser(receptionist), mapOf("cpf" to cpf)).status shouldBe 403
            call(PUT, "$base/not-a-uuid", asAdmin(), mapOf("cpf" to cpf)).status shouldBe 400
            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to "529.982.247-24")).status shouldBe 400
            call(POST, "$base/$userId/deactivate", asAdmin()).status shouldBe 404

            val logs = output.all.substring(start)
            logs shouldNotContain digits
            logs shouldNotContain cpf
            logs shouldNotContain "529.982.247-24"
            val audited =
                jdbc
                    .sql(
                        "SELECT count(*) FROM audit.audit_entry WHERE entity_type = 'fnrh.requester' " +
                            "AND entity_id = :id",
                    ).param("id", userId.toString())
                    .query(Long::class.java)
                    .single()
            audited shouldBe 0L
        }

    @Test
    fun `deactivation leaves the row with a null cpf and inactive, with no trace of the CPF`(): Unit =
        with(iam) {
            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to cpf)).ok()

            call(POST, "$base/$userId/deactivate", asAdmin()).status shouldBe 204

            val row = jdbc.sql("SELECT cpf, active FROM fnrh.requester WHERE user_id = :id").param("id", userId)
            row.query { rs, _ -> rs.getString("cpf") to rs.getBoolean("active") }.single() shouldBe (null to false)
            rows() shouldBe 1L
            call(GET, base, asAdmin()).ok().size() shouldBe 0
        }

    @Test
    fun `the check constraint rejects an active row without CPF and an inactive row with one`() {
        val id = UUID.randomUUID()

        fun insert(
            cpfValue: String?,
            active: Boolean,
        ) = jdbc
            .sql("INSERT INTO fnrh.requester (user_id, cpf, active) VALUES (:id, :cpf, :active)")
            .param("id", id)
            .param("cpf", cpfValue)
            .param("active", active)
            .update()

        shouldThrow<DataIntegrityViolationException> { insert(null, true) }
        shouldThrow<DataIntegrityViolationException> { insert("x", false) }
        rows() shouldBe 0L
        insert("x", true)
        shouldThrow<DataIntegrityViolationException> {
            jdbc.sql("UPDATE fnrh.requester SET active = FALSE WHERE user_id = :id").param("id", id).update()
        }
        shouldThrow<DataIntegrityViolationException> {
            jdbc.sql("UPDATE fnrh.requester SET cpf = NULL WHERE user_id = :id").param("id", id).update()
        }
        jdbc
            .sql("UPDATE fnrh.requester SET active = FALSE, cpf = NULL WHERE user_id = :id")
            .param("id", id)
            .update() shouldBe 1
    }

    @Test
    fun `deactivating twice is 404 the second time and an unknown user is 404`(): Unit =
        with(iam) {
            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to cpf)).ok()

            call(POST, "$base/$userId/deactivate", asAdmin()).status shouldBe 204
            call(POST, "$base/$userId/deactivate", asAdmin()).problem(404, "FNRH_REQUESTER_NOT_FOUND")
            call(POST, "$base/${UUID.randomUUID()}/deactivate", asAdmin()).problem(404, "FNRH_REQUESTER_NOT_FOUND")
            rows() shouldBe 1L
            activeRows() shouldBe 0L
        }

    @Test
    fun `a PUT after the deactivation reactivates the same row`(): Unit =
        with(iam) {
            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to cpf)).ok()
            call(POST, "$base/$userId/deactivate", asAdmin()).status shouldBe 204

            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to "111.444.777-35"))
                .ok()["cpf"]
                .asString() shouldBe "***.***.***-35"

            rows() shouldBe 1L
            activeRows() shouldBe 1L
            directory.cpfOf(userId) shouldBe Cpf.of("11144477735")
            call(GET, base, asAdmin()).ok().size() shouldBe 1
            call(POST, "$base/$userId/deactivate", asAdmin()).status shouldBe 204
            directory.cpfOf(userId) shouldBe null
        }

    @Test
    fun `DELETE is no longer mapped and removes nothing`(): Unit =
        with(iam) {
            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to cpf)).ok()

            val status = call(DELETE, "$base/$userId", asAdmin()).status

            status shouldBe 405
            directory.cpfOf(userId) shouldBe Cpf.of(digits)
            rows() shouldBe 1L
        }

    @Test
    fun `the audit trail across PUT, deactivation and reactivation never holds the clear CPF`(): Unit =
        with(iam) {
            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to cpf)).ok()
            call(POST, "$base/$userId/deactivate", asAdmin()).status shouldBe 204
            call(PUT, "$base/$userId", asAdmin(), mapOf("cpf" to "111.444.777-35")).ok()

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
                for (secret in listOf(cpf, digits, "111.444.777-35", "11144477735")) text shouldNotContain secret
            }
            entries[1].first.contains("***.***.***-25") shouldBe true
            entries[1].second.contains("***") shouldBe false
        }
}
