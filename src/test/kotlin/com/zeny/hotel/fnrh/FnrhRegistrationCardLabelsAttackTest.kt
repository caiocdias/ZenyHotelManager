package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.domain.FnrhDomain
import com.zeny.hotel.frontdesk.RegistrationCardLabels
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import kotlin.io.path.extension
import kotlin.io.path.readText

/** Ataques à porta dos rótulos da ficha (M6 E15): domínio trocado, entradas hostis, catálogo alterado, só leitura. */
@IntegrationTest
class FnrhRegistrationCardLabelsAttackTest(
    @Autowired private val labels: RegistrationCardLabels,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private fun seeded(domain: FnrhDomain): List<Pair<String, String>> =
        jdbc
            .query(
                "SELECT code, label FROM fnrh.domain_value WHERE domain = ? ORDER BY code",
                { rs, _ -> rs.getString("code") to rs.getString("label") },
                domain.name,
            )

    @Test
    fun `a code of the other domain has no label`() {
        labels.travelReason("AUTOMOVEL").shouldBeNull()
        labels.transportMode("NEGOCIOS").shouldBeNull()
        labels.travelReason("CANCELADO").shouldBeNull()
        labels.transportMode("HOMEM").shouldBeNull()
    }

    @Test
    fun `hostile codes are matched literally and yield no label`() {
        val hostile =
            listOf(
                "%",
                "_",
                "NEGOC%",
                "NEGOCIO_",
                "N%",
                "' OR '1'='1",
                "NEGOCIOS'; DROP TABLE fnrh.domain_value; --",
                "\"NEGOCIOS\"",
                "NEGOCIOS\u0000",
                "NEGOCIOS\n",
                "\tNEGOCIOS",
                "NEGOCIOS​",
                " NEGOCIOS",
                "NEGÓCIOS",
            )
        for (code in hostile) {
            labels.travelReason(code).shouldBeNull()
            labels.transportMode(code).shouldBeNull()
        }
        seeded(FnrhDomain.TRAVEL_REASONS).size shouldBe 8
    }

    @Test
    fun `canonically equivalent unicode forms of a code are not matched`() {
        val code = "CAFÉ_TESTE"
        val nfc = Normalizer.normalize(code, Normalizer.Form.NFC)
        val nfd = Normalizer.normalize(code, Normalizer.Form.NFD)
        (nfc == nfd) shouldBe false
        val insert =
            "INSERT INTO fnrh.domain_value (domain, code, label, refreshed_at) " +
                "VALUES ('TRAVEL_REASONS', ?, ?, now())"
        try {
            jdbc.update(insert, nfc, "Café")

            labels.travelReason(nfc) shouldBe "Café"
            labels.travelReason(nfd).shouldBeNull()
        } finally {
            jdbc.update("DELETE FROM fnrh.domain_value WHERE domain = 'TRAVEL_REASONS' AND code IN (?, ?)", nfc, nfd)
        }
        labels.travelReason(nfc).shouldBeNull()
    }

    @Test
    fun `every seeded code of both domains has its label`() {
        val reasons = seeded(FnrhDomain.TRAVEL_REASONS)
        val modes = seeded(FnrhDomain.TRANSPORT_MODES)
        (reasons.size >= 8) shouldBe true
        (modes.size >= 8) shouldBe true
        for ((code, label) in reasons) {
            labels.travelReason(code) shouldBe label
            label.isNotBlank() shouldBe true
        }
        for ((code, label) in modes) {
            labels.transportMode(code) shouldBe label
            label.isNotBlank() shouldBe true
        }
    }

    @Test
    fun `a code removed from the catalog has no label and comes back with it`() {
        val where = "WHERE domain = 'TRANSPORT_MODES' AND code = 'TREM'"
        val row =
            jdbc.queryForMap("SELECT label, refreshed_at FROM fnrh.domain_value $where")
        labels.transportMode("TREM").shouldNotBeNull()
        try {
            jdbc.update("DELETE FROM fnrh.domain_value $where")

            labels.transportMode("TREM").shouldBeNull()
        } finally {
            jdbc.update(
                "INSERT INTO fnrh.domain_value (domain, code, label, refreshed_at) " +
                    "VALUES ('TRANSPORT_MODES', 'TREM', ?, ?)",
                row["label"],
                row["refreshed_at"],
            )
        }
        labels.transportMode("TREM") shouldBe row["label"]
    }

    @Test
    fun `the port writes nothing and never calls Serpro`() {
        fun calls() = jdbc.queryForObject("SELECT count(*) FROM fnrh.integration_call", Long::class.java)!!

        fun domainRows() = jdbc.queryForObject("SELECT count(*) FROM fnrh.domain_value", Long::class.java)!!

        fun snapshot() =
            jdbc.queryForList(
                "SELECT domain, code, label, refreshed_at::text AS at FROM fnrh.domain_value ORDER BY 1, 2",
            )

        val callsBefore = calls()
        val rowsBefore = snapshot()
        val domainBefore = domainRows()
        val requestsBefore = FnrhTestServer.requests().size

        repeat(3) {
            labels.travelReason("NEGOCIOS")
            labels.transportMode("AVIAO")
            labels.travelReason("NAO_EXISTE")
            labels.transportMode("")
        }

        calls() shouldBe callsBefore
        domainRows() shouldBe domainBefore
        snapshot() shouldBe rowsBefore
        FnrhTestServer.requests().size shouldBe requestsBefore
    }

    @Test
    fun `frontdesk sources never reference the fnrh module`() {
        val root = Path.of("src/main/kotlin/com/zeny/hotel/frontdesk")
        val offenders =
            Files.walk(root).use { paths ->
                paths
                    .filter { it.extension == "kt" }
                    .filter { it.readText().contains("com.zeny.hotel.fnrh") }
                    .map { it.toString() }
                    .toList()
            }

        offenders.shouldBeEmpty()
    }
}
