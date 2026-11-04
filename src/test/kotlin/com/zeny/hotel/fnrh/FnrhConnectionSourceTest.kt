package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhConnectionSource
import com.zeny.hotel.fnrh.application.FnrhSettingsStore
import com.zeny.hotel.fnrh.infrastructure.FnrhProperties
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/**
 * A configuração da FNRH cifrada no banco e a porta que a entrega ao adaptador (M5 E07). O banco é compartilhado:
 * cada teste devolve a linha semente ao estado inicial.
 */
@IntegrationTest
class FnrhConnectionSourceTest(
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
        environment: FnrhEnvironment,
        enabled: Boolean,
        password: String? = "s3cr3t-pass",
    ) {
        tx.executeWithoutResult {
            store.settings().update(environment, "hotel-user", password, cpf, enabled)
        }
    }

    @Test
    fun `the seed row exists, off and in homologation, with a version for the first ETag`() {
        val row = jdbc.queryForMap("SELECT environment, enabled, version FROM fnrh.settings")

        row["environment"] shouldBe "HOMOLOGATION"
        row["enabled"] shouldBe false
        (row["version"] as Number).toLong() shouldBeGreaterThanOrEqual 0L // banco compartilhado: 0 só no primeiro uso
        source.current().shouldBeNull()
    }

    @Test
    fun `a saved and enabled configuration gives the URL of the chosen environment`() {
        configure(FnrhEnvironment.HOMOLOGATION, enabled = true)
        val homologation = source.current().shouldNotBeNull()
        homologation.environment shouldBe FnrhEnvironment.HOMOLOGATION
        homologation.baseUrl.toString() shouldBe properties.homologationUrl.toString()
        homologation.username shouldBe "hotel-user"
        homologation.password shouldBe "s3cr3t-pass"
        homologation.defaultRequesterCpf shouldBe cpf
        homologation.toString() shouldNotContain "s3cr3t-pass"
        homologation.toString() shouldNotContain "hotel-user"
        homologation.toString() shouldNotContain "52998224725"

        configure(FnrhEnvironment.PRODUCTION, enabled = true, password = "new-pass")
        val production = source.current().shouldNotBeNull()
        production.environment shouldBe FnrhEnvironment.PRODUCTION
        production.baseUrl.toString() shouldBe properties.productionUrl.toString()
    }

    @Test
    fun `a disabled configuration gives no connection`() {
        configure(FnrhEnvironment.HOMOLOGATION, enabled = false)

        source.current().shouldBeNull()
    }

    @Test
    fun `user, password and CPF are not stored in clear text`() {
        configure(FnrhEnvironment.HOMOLOGATION, enabled = true)

        val row = jdbc.queryForMap("SELECT username, password, default_cpf FROM fnrh.settings")

        val clearTexts = mapOf("username" to "hotel-user", "password" to "s3cr3t-pass", "default_cpf" to "52998224725")
        for ((column, clear) in clearTexts) {
            val stored = row[column] as String
            stored shouldStartWith "v1:"
            stored shouldNotContain clear
        }
    }
}
