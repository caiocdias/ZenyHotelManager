package com.zeny.hotel.frontdesk

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.json.JsonMapper

/**
 * Ataques à troca da descrição de `frontdesk.stays.checkin` (M6 E15, D-E15-5): a trilha da sincronização, a role
 * customizada, quem não tem a permissão, a repetição da sincronização e o resto da operação da ficha no contrato.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList")
class FrontdeskCheckInPermissionAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired hotelApi: HotelApi,
    @Autowired roomsApi: RoomsApi,
    @Autowired private val mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val modules: List<ModulePermissions>,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val frontdesk = FrontdeskTestSupport(iam, support, hotelApi, roomsApi, jsonMapper)
    private val code = FrontdeskPermissions.STAYS_CHECK_IN.value

    @BeforeEach
    fun synchronizeThisContextCatalog() {
        synchronizer.synchronize()
    }

    @Test
    fun `the sync trail records the description change without personal data`() {
        val user = iam.createUser()
        val before = auditCount()
        withOldDescription {
            synchronizer.synchronize()
        }
        val entries = auditEntries()
        (entries.size - before) shouldBe 2 // a troca para a antiga e a volta para a nova
        val last = entries.last()
        last.first shouldContain OLD_DESCRIPTION
        last.second shouldContain NEW_DESCRIPTION
        last.first shouldNotContain "abrir a ficha"
        val raw = entries.joinToString(" ") { it.first + it.second }
        raw shouldNotContain user.login
        raw shouldNotContain user.id.value.toString()
    }

    @Test
    fun `a custom role granting the check-in keeps the grant and the card access after the sync`() {
        val user =
            iam.createUserWith(frontdesk.grant(FrontdeskPermissions.STAYS_CHECK_IN))
        val token = iam.loginOk(user)["accessToken"].asString()
        val booked = frontdesk.stay().book(frontdesk.guest())
        val path = "/api/v1/reservations/${booked.id}/registration-card/pdf"
        iam.getWithToken(path, token).status shouldBe 200
        val before = customGrants()
        before shouldContain "ALL"

        withOldDescription {
            synchronizer.synchronize()
            customGrants() shouldBe before
            iam.getWithToken(path, token).status shouldBe 200
        }
        customGrants() shouldBe before
        iam.getWithToken(path, token).status shouldBe 200
    }

    @Test
    fun `a token without the permission still gets 403 on the card after the sync`() {
        val user = iam.createUserWith(frontdesk.grant(FrontdeskPermissions.STAYS_UNDO_CHECK_IN))
        val token = iam.loginOk(user)["accessToken"].asString()
        val booked = frontdesk.stay().book(frontdesk.guest())
        val path = "/api/v1/reservations/${booked.id}/registration-card/pdf"
        iam.getWithToken(path, token).status shouldBe 403
        withOldDescription {
            synchronizer.synchronize()
            iam.getWithToken(path, token).status shouldBe 403
        }
        iam.getWithToken(path, token).status shouldBe 403
    }

    @Test
    fun `synchronizing twice with the same description changes nothing`() {
        synchronizer.synchronize()
        val trailBefore = auditCount()
        val versionBefore = authorizationVersion()
        val second = synchronizer.synchronize()

        second.updated shouldNotContain FrontdeskPermissions.STAYS_CHECK_IN
        second.inserted.shouldBeEmpty()
        second.updated.shouldBeEmpty()
        auditCount() shouldBe trailBefore
        authorizationVersion() shouldBe versionBefore
    }

    @Test
    fun `the card operation keeps its parameters and responses in the contract`() {
        val yaml = java.io.File("api/openapi.yaml").readText()
        val operation =
            yaml
                .substringAfter("  /api/v1/reservations/{id}/registration-card/pdf:\n")
                .substringBefore("  /api/v1/reservations/{id}/registration-card/print:")
        val rest = operation.substringAfter("operationId: getRegistrationCardPdf")
        normalize(rest).trim() shouldBe normalize(EXPECTED_REST_OF_OPERATION).trim()
    }

    @Test
    fun `the catalog endpoint shows the same description the table stores`() {
        val item =
            iam
                .json(
                    mockMvc
                        .get("/api/v1/permissions") {
                            with(iam.asAdmin())
                            param("limit", "200")
                        }.andReturn()
                        .response,
                )["items"]
                .values()
                .single { it["code"].asString() == code }
        val stored =
            jdbc
                .sql("SELECT description FROM iam.permission WHERE code = :c")
                .param("c", code)
                .query(String::class.java)
                .single()
        item["description"].asString() shouldBe stored
        stored shouldBe NEW_DESCRIPTION
    }

    // --- apoio ---

    private fun normalize(text: String) = text.replace(Regex("\\s+"), " ")

    private fun withOldDescription(block: () -> Unit) {
        val real = modules.single { it.module == "frontdesk" }
        val old =
            object : ModulePermissions {
                override val module = real.module
                override val permissions =
                    real.permissions.map {
                        if (it.code.value == code) it.copy(description = OLD_DESCRIPTION) else it
                    }
            }
        try {
            synchronizer.synchronize(modules.map { if (it === real) old else it })
            block()
        } finally {
            synchronizer.synchronize()
        }
    }

    private fun auditCount(): Int = auditEntries().size

    /** Antes e depois (texto cru) das entradas de auditoria da permissão, em ordem. */
    private fun auditEntries(): List<Pair<String, String>> =
        jdbc
            .sql(
                "SELECT coalesce(CAST(before_state AS text), ''), coalesce(CAST(after_state AS text), '') " +
                    "FROM audit.audit_entry WHERE entity_id = :code ORDER BY occurred_at, id",
            ).param("code", code)
            .query { rs, _ -> rs.getString(1) to rs.getString(2) }
            .list()

    private fun authorizationVersion(): Long =
        jdbc.sql("SELECT value FROM iam.authorization_version WHERE id = 1").query(Long::class.java).single()

    /** Escopos concedidos da permissão em roles que não são modelos. */
    private fun customGrants(): List<String> =
        jdbc
            .sql(
                "SELECT g.scope FROM iam.role_grant g JOIN iam.role r ON r.id = g.role_id " +
                    "WHERE g.permission_code = :code AND r.template_key IS NULL ORDER BY r.created_at, g.scope",
            ).param("code", code)
            .query { rs, _ -> rs.getString(1) }
            .list()

    private companion object {
        val EXPECTED_REST_OF_OPERATION =
            """
            parameters:
            - in: path
              name: id
              required: true
              schema:
                type: string
                format: uuid
            responses:
              "200":
                content:
                  application/pdf:
                    schema:
                      type: string
                      format: binary
                description: A ficha em PDF.
              default:
                content:
                  application/problem+json:
                    schema:
                      ${'$'}ref: "#/components/schemas/Problem"
                description: "Erro no formato RFC 9457, com `code` estável."
            summary: Ficha de registro da hospedagem em PDF
            tags:
            - frontdesk
            """.trimIndent()

        const val OLD_DESCRIPTION = "Fazer o check-in de uma reserva confirmada (com a caução opcional)"
        const val NEW_DESCRIPTION =
            "Fazer o check-in de uma reserva confirmada (com a caução opcional) e abrir a ficha de registro " +
                "(PDF e impressão)"
    }
}
