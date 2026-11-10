package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhConnectionSource
import com.zeny.hotel.fnrh.application.FnrhSettingsStore
import com.zeny.hotel.fnrh.infrastructure.FnrhProperties
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

/** Ataques à persistência, à porta de conexão, às propriedades e às permissões do `fnrh` (M5 E07, testador). */
@IntegrationTest
class FnrhPersistenceAttackTest(
    @Autowired private val source: FnrhConnectionSource,
    @Autowired private val store: FnrhSettingsStore,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val properties: FnrhProperties,
) {
    private val cpf = Cpf.of("52998224725")

    @AfterEach
    fun resetSeed() {
        jdbc.update(
            "UPDATE fnrh.settings SET environment = 'HOMOLOGATION', username = NULL, password = NULL, " +
                "default_cpf = NULL, enabled = FALSE",
        )
    }

    private fun configure(
        environment: FnrhEnvironment = FnrhEnvironment.HOMOLOGATION,
        password: String? = "s3cr3t-pass",
        enabled: Boolean = true,
    ) {
        tx.executeWithoutResult { store.settings().update(environment, "hotel-user", password, cpf, enabled) }
    }

    private fun version(): Long = jdbc.queryForObject("SELECT version FROM fnrh.settings", Long::class.java)!!

    private fun storedPassword(): String? =
        jdbc.queryForObject("SELECT password FROM fnrh.settings", String::class.java)

    @Test
    fun `a saved update bumps the version so the ETag changes`() {
        val before = version()

        configure()

        version() shouldBeGreaterThan before
    }

    @Test
    fun `a null password on a later update keeps the stored one readable through the port`() {
        configure(password = "s3cr3t-pass")

        configure(password = null)

        source.current().shouldNotBeNull().password shouldBe "s3cr3t-pass"
    }

    @Test
    fun `switching off then on again gives the connection back with the kept credentials`() {
        configure()
        configure(password = null, enabled = false)
        storedPassword().shouldNotBeNull()

        configure(password = null, enabled = true)

        source.current().shouldNotBeNull().password shouldBe "s3cr3t-pass"
    }

    @Test
    fun `the table refuses a second row`() {
        shouldThrow<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO fnrh.settings (id, environment, enabled, version) " +
                    "VALUES ('00000000-0000-0000-0000-00000000f002', 'HOMOLOGATION', FALSE, 0)",
            )
        }
    }

    @Test
    fun `the table refuses an unknown environment`() {
        shouldThrow<DataIntegrityViolationException> {
            jdbc.update("UPDATE fnrh.settings SET environment = 'STAGING'")
        }
    }

    @Test
    fun `the table refuses to enable an incomplete configuration`() {
        shouldThrow<DataIntegrityViolationException> {
            jdbc.update("UPDATE fnrh.settings SET enabled = TRUE")
        }
        shouldThrow<DataIntegrityViolationException> {
            jdbc.update("UPDATE fnrh.settings SET enabled = TRUE, username = 'u', password = 'p'")
        }
    }

    @Test
    fun `the application role can write the settings`() {
        // A conexão do teste é a da aplicação (papel sem DDL): o DML da migração de grant precisa valer.
        jdbc.update("UPDATE fnrh.settings SET environment = 'HOMOLOGATION'") shouldBe 1
    }

    @Test
    fun `the default URLs and timeouts are the ones of the levantamento`() {
        properties.homologationUrl.toString() shouldBe "https://hom-lowcode.serpro.gov.br/FNRH_API/rest/v2"
        properties.productionUrl.toString() shouldBe "https://fnrh.turismo.serpro.gov.br/FNRH_API/rest/v2"
        properties.connectTimeout shouldBe Duration.ofSeconds(5)
        properties.readTimeout shouldBe Duration.ofSeconds(20)
    }

    @Test
    fun `the production environment gives the production URL, never the homologation one`() {
        (properties.homologationUrl == properties.productionUrl) shouldBe false
        configure(FnrhEnvironment.PRODUCTION, password = "p")

        source.current().shouldNotBeNull().baseUrl shouldBe properties.productionUrl
    }

    @Test
    fun `properties toString lists no credential`() {
        val text = FnrhProperties().toString()

        text shouldNotContain "password"
        text shouldNotContain "username"
        text shouldNotContain "cpf"
    }

    @Test
    fun `connection toString shows only the environment`() {
        configure(FnrhEnvironment.PRODUCTION, password = "s3cr3t-pass")

        source.current().shouldNotBeNull().toString() shouldBe "FnrhConnection(environment=PRODUCTION)"
    }

    private fun templatesGranting(code: String): List<String> =
        jdbc
            .queryForList(
                "SELECT r.template_key FROM iam.role_grant g JOIN iam.role r ON r.id = g.role_id " +
                    "WHERE g.permission_code = ? AND r.template_key IS NOT NULL",
                String::class.java,
                code,
            ).filterNotNull()

    @Test
    fun `read permissions go to manager and receptionist, and manage goes only to the administrator`() {
        val readers =
            listOf(RoleTemplate.MANAGER, RoleTemplate.RECEPTIONIST, RoleTemplate.ADMINISTRATOR).map { it.name }
        val adminOnly = listOf(RoleTemplate.ADMINISTRATOR.name)

        templatesGranting("fnrh.domains.read") shouldContainExactlyInAnyOrder readers
        templatesGranting("fnrh.submissions.read") shouldContainExactlyInAnyOrder readers
        templatesGranting("fnrh.settings.manage") shouldContainExactlyInAnyOrder adminOnly
        templatesGranting("fnrh.submissions.manage") shouldContainExactlyInAnyOrder
            listOf(RoleTemplate.MANAGER, RoleTemplate.ADMINISTRATOR).map { it.name }
    }

    @Test
    fun `the fnrh permissions are in the catalog with ALL scope only and none is sensitive`() {
        val rows =
            jdbc.queryForList(
                "SELECT code, sensitive, scopes::text AS scopes FROM iam.permission WHERE code LIKE 'fnrh.%'",
            )

        rows.map { it["code"] } shouldContainExactlyInAnyOrder
            listOf("fnrh.settings.manage", "fnrh.domains.read", "fnrh.submissions.read", "fnrh.submissions.manage")
        rows.all { it["sensitive"] == false } shouldBe true
        rows.all { it["scopes"] == "{ALL}" } shouldBe true
    }
}
