package com.zeny.hotel.audit

import auditexample.AuditProbeConfiguration
import auditexample.AuditProbeService
import auditexample.AuditProbeService.Companion.PROBE
import auditexample.SecretSnapshot
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.FieldCipher
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * A trilha de auditoria (M1 E04, RF-ACS-11, RNF-11): o aspecto de `@Audited` grava na mesma
 * transação do caso de uso (rollback não deixa entrada, falha ao gravar desfaz a escrita), com
 * ator, sessão, instante, ação, entidade, antes e depois, mascara os campos sensíveis e não roda
 * fora de uma transação de escrita; o papel da aplicação não altera nem apaga a trilha.
 */
@IntegrationTest
@Import(AuditProbeConfiguration::class)
class AuditTrailTest(
    @Autowired private val probe: AuditProbeService,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val cipher: FieldCipher,
    @Autowired private val jsonMapper: JsonMapper,
) {
    @Test
    fun `an audited write records who, when, from which session, the action, the entity and the values`() {
        val id = UuidV7.generate()
        val userId = UuidV7.generate()
        val sessionId = UuidV7.generate()
        probe.write(id, "primeira")
        val start = Instant.now()

        authenticatedAs(userId, sessionId) { probe.rename(id, "segunda") }

        val entry = entriesOf(id).single { it.action == "test.probe.rename" }
        entry.userId shouldBe userId
        entry.deviceSessionId shouldBe sessionId
        entry.outcome shouldBe "SUCCEEDED"
        entry.errorCode.shouldBeNull()
        entry.entityType shouldBe PROBE
        entry.entityId shouldBe id.toString()
        entry.before!!["title"].stringValue() shouldBe "primeira"
        entry.after!!["title"].stringValue() shouldBe "segunda"
        (entry.occurredAt >= start.minusMillis(1)) shouldBe true
        (entry.occurredAt <= Instant.now()) shouldBe true
        // Gravado como instante (timestamptz), lido em UTC.
        entry.occurredAtOffset shouldBe ZoneOffset.UTC
        // A criação: sem "antes", e do sistema (sem token).
        val created = entriesOf(id).single { it.action == "test.probe.write" }
        created.before.shouldBeNull()
        created.after!!["title"].stringValue() shouldBe "primeira"
        created.userId.shouldBeNull()
    }

    @Test
    fun `a rollback leaves no entry, and a failure to build the entry undoes the write`() {
        val rolledBack = UuidV7.generate()
        shouldThrow<IllegalStateException> { probe.write(rolledBack, "desfeita", failAfterWriting = true) }

        taskExists(rolledBack) shouldBe false
        entriesOf(rolledBack).shouldBeEmpty()

        val failing = UuidV7.generate()
        shouldThrow<IllegalStateException> { probe.failingSnapshot(failing) }

        taskExists(failing) shouldBe false
        entriesOf(failing).shouldBeEmpty()
    }

    @Test
    fun `the entry joins the caller transaction and disappears with its rollback`() {
        val id = UuidV7.generate()

        transactions.executeWithoutResult { status ->
            probe.write(id, "na transação de fora")
            entriesOf(id) shouldHaveSize 1 // visível dentro da mesma transação
            status.setRollbackOnly()
        }

        taskExists(id) shouldBe false
        entriesOf(id).shouldBeEmpty()
    }

    @Test
    fun `a rejection is recorded only when asked and only if the transaction commits`() {
        val committed = UuidV7.generate()
        shouldThrow<ConflictException> { probe.reject(committed) }

        val entry = entriesOf(committed).single()
        entry.outcome shouldBe "REJECTED"
        entry.errorCode shouldBe "PROBE_REJECTED"
        entry.action shouldBe "test.probe.reject"

        val rolledBack = UuidV7.generate()
        shouldThrow<ConflictException> { probe.rejectRolledBack(rolledBack) }
        entriesOf(rolledBack).shouldBeEmpty()

        val unrecorded = UuidV7.generate()
        shouldThrow<ConflictException> { probe.rejectUnrecorded(unrecorded) }
        taskExists(unrecorded) shouldBe true
        entriesOf(unrecorded).shouldBeEmpty()
    }

    @Test
    fun `an unchanged entity or a declared no-op records nothing - an undescribed write records the action`() {
        val id = UuidV7.generate()
        probe.write(id, "igual")

        probe.rename(id, "igual")
        entriesOf(id).map { it.action } shouldBe listOf("test.probe.write")

        val before = countOf("test.probe.undescribed")
        probe.undescribed()
        countOf("test.probe.undescribed") shouldBe before + 1

        val noChanges = countOf("test.probe.no-changes")
        probe.noChanges()
        countOf("test.probe.no-changes") shouldBe noChanges
    }

    @Test
    fun `the use case can attribute the entry to a user and reference an approval`() {
        val id = UuidV7.generate()
        val userId = UuidV7.generate()
        val approvalId = UuidV7.generate()

        probe.attributed(id, UserId(userId), approvalId)

        val entry = entriesOf(id).single()
        entry.userId shouldBe userId
        entry.deviceSessionId.shouldBeNull()
        entry.approvalId shouldBe approvalId
    }

    @Test
    fun `audited methods refuse to run outside a write transaction`() {
        val id = UuidV7.generate()

        shouldThrow<IllegalStateException> { probe.withoutTransaction(id) }.message!! shouldContain "transação"
        shouldThrow<IllegalStateException> { probe.readOnly() }.message!! shouldContain "transação"
        taskExists(id) shouldBe false
    }

    @Test
    fun `passwords, PINs, tokens, hashes, secrets and encrypted values never reach the trail`() {
        val id = UuidV7.generate()
        val ciphertext = cipher.encrypt("123.456.789-09")
        val argon2 = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c2FsdHNhbHQ\$aGFzaGhhc2hoYXNo"
        val jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl"
        val secrets =
            listOf(
                "Senha-Muito-Secreta-1",
                "4821",
                "refresh-token-opaco-123",
                "pin-hash-valor",
                "segredo-da-api",
                "98765432100",
                ciphertext,
                argon2,
                jwt,
                "outra-senha-aninhada",
                "credencial-aninhada",
            )
        val snapshot =
            SecretSnapshot(
                name = "Visível",
                password = secrets[0],
                pin = secrets[1],
                refreshToken = secrets[2],
                pinHash = secrets[3],
                apiSecret = secrets[4],
                documentNumber = secrets[5],
                note = ciphertext,
                hasPin = true,
                passwordChangeRequired = false,
                nested =
                    mapOf(
                        "accessToken" to jwt,
                        "hashes" to listOf(argon2),
                        "free_text" to argon2,
                        "new_password" to secrets[9],
                        "credentials" to mapOf("user" to secrets[10]),
                        "comment" to "texto comum",
                    ),
            )

        probe.write(id, "com segredos", snapshot)

        val raw = rawValuesOf(id)
        secrets.forEach { raw shouldNotContain it }
        val after = entriesOf(id).single().after!!
        after["name"].stringValue() shouldBe "Visível"
        after["hasPin"].booleanValue() shouldBe true
        after["passwordChangeRequired"].booleanValue() shouldBe false
        listOf("password", "pin", "refreshToken", "pinHash", "apiSecret", "documentNumber", "note").forEach {
            after[it].stringValue() shouldBe MASK
        }
        val nested = after["nested"]
        nested["accessToken"].stringValue() shouldBe MASK
        nested["hashes"][0].stringValue() shouldBe MASK
        nested["free_text"].stringValue() shouldBe MASK
        nested["new_password"].stringValue() shouldBe MASK
        nested["credentials"].stringValue() shouldBe MASK
        nested["comment"].stringValue() shouldBe "texto comum"
    }

    @Test
    fun `the application role can only read and insert audit entries - UPDATE, DELETE, TRUNCATE and DDL fail`() {
        val id = UuidV7.generate()
        probe.write(id, "imutável")
        val statements =
            listOf(
                "UPDATE audit.audit_entry SET action = 'x' WHERE entity_id = '$id'",
                "UPDATE audit.audit_entry SET after_state = NULL",
                "DELETE FROM audit.audit_entry WHERE entity_id = '$id'",
                "DELETE FROM audit.audit_entry",
                "TRUNCATE audit.audit_entry",
                "ALTER TABLE audit.audit_entry DISABLE TRIGGER ALL",
                "DROP TABLE audit.audit_entry",
                "CREATE TABLE audit.audit_entry_copy (id uuid)",
                "CREATE TRIGGER t BEFORE INSERT ON audit.audit_entry FOR EACH ROW " +
                    "EXECUTE FUNCTION suppress_redundant_updates_trigger()",
            )

        statements.forEach { sql ->
            val error =
                shouldThrow<DataAccessException> { transactions.executeWithoutResult { jdbc.sql(sql).update() } }
            (error.mostSpecificCause as SQLException).sqlState shouldBe INSUFFICIENT_PRIVILEGE
        }
        entriesOf(id).single().action shouldBe "test.probe.write"
    }

    private fun <T> authenticatedAs(
        userId: UUID,
        sessionId: UUID,
        block: () -> T,
    ): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(userId.toString())
                .claim("sid", sessionId.toString())
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun taskExists(id: UUID): Boolean =
        jdbc
            .sql("SELECT count(*) FROM test_support.example_task WHERE id = :id")
            .param("id", id)
            .query(Int::class.java)
            .single() > 0

    private fun countOf(action: String): Int =
        jdbc
            .sql("SELECT count(*) FROM audit.audit_entry WHERE action = :action")
            .param("action", action)
            .query(Int::class.java)
            .single()

    private fun rawValuesOf(id: UUID): String =
        jdbc
            .sql(
                "SELECT coalesce(CAST(before_state AS text), '') || coalesce(CAST(after_state AS text), '') " +
                    "FROM audit.audit_entry WHERE entity_id = :id",
            ).param("id", id.toString())
            .query(String::class.java)
            .single()

    private fun entriesOf(id: UUID): List<StoredEntry> =
        jdbc
            .sql(
                """
                SELECT occurred_at, user_id, device_session_id, action, outcome, error_code, entity_type, entity_id,
                    CAST(before_state AS text) AS before_state, CAST(after_state AS text) AS after_state, approval_id
                FROM audit.audit_entry WHERE entity_id = :id ORDER BY occurred_at, id
                """.trimIndent(),
            ).param("id", id.toString())
            .query { rs, _ ->
                val at = rs.getObject("occurred_at", OffsetDateTime::class.java)
                StoredEntry(
                    occurredAt = at.toInstant(),
                    occurredAtOffset = at.offset,
                    userId = rs.getObject("user_id", UUID::class.java),
                    deviceSessionId = rs.getObject("device_session_id", UUID::class.java),
                    action = rs.getString("action"),
                    outcome = rs.getString("outcome"),
                    errorCode = rs.getString("error_code"),
                    entityType = rs.getString("entity_type"),
                    entityId = rs.getString("entity_id"),
                    before = rs.getString("before_state")?.let(jsonMapper::readTree),
                    after = rs.getString("after_state")?.let(jsonMapper::readTree),
                    approvalId = rs.getObject("approval_id", UUID::class.java),
                )
            }.list()

    private data class StoredEntry(
        val occurredAt: Instant,
        val occurredAtOffset: ZoneOffset,
        val userId: UUID?,
        val deviceSessionId: UUID?,
        val action: String,
        val outcome: String,
        val errorCode: String?,
        val entityType: String?,
        val entityId: String?,
        val before: JsonNode?,
        val after: JsonNode?,
        val approvalId: UUID?,
    )

    private companion object {
        const val MASK = "***"
        const val INSUFFICIENT_PRIVILEGE = "42501"
    }
}
