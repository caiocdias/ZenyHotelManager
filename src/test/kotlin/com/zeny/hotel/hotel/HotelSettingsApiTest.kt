package com.zeny.hotel.hotel

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.time.LocalDate
import java.util.UUID

/**
 * Configuração do hotel pela API (M1 E06, RF-CFG-01 a 05, RN-11): leitura e edição com permissão,
 * `If-Match` e auditoria; validação de CNPJ, endereço, fuso, políticas (Portaria MTur 28/2025),
 * taxas e parâmetros; a data operacional visível a qualquer usuário autenticado; e nenhum
 * endpoint que avance a data.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, BusinessDateListenerConfiguration::class)
class HotelSettingsApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val mockMvc: MockMvc,
    @Autowired @Qualifier("requestMappingHandlerMapping") private val handlerMapping: RequestMappingHandlerMapping,
) {
    private val read = TestGrant(definition(HotelPermissions.SETTINGS_READ), ALL)
    private val manage = TestGrant(definition(HotelPermissions.SETTINGS_MANAGE), ALL)

    private val hotelBody =
        mapOf(
            "legalName" to "Pousada Exemplo Ltda",
            "tradeName" to "Pousada Exemplo",
            "cnpj" to "11.222.333/0001-81",
            "municipalRegistration" to "12345",
            "cadastur" to "21.012345.10.0001-1",
            "address" to
                mapOf(
                    "street" to "Rua das Flores",
                    "number" to "100",
                    "complement" to null,
                    "district" to "Centro",
                    "city" to "São Paulo",
                    "state" to "SP",
                    "postalCode" to "01310-100",
                    "ibgeCityCode" to "3550308",
                ),
            "timezone" to "America/Sao_Paulo",
            "taxRegime" to "SIMPLES_NACIONAL",
            "logoFileId" to null,
        )

    private val policyBody =
        mapOf(
            "checkInTime" to "14:00",
            "checkOutTime" to "12:00",
            "cleaningWindowMinutes" to 120,
            "earlyCheckIn" to mapOf("limitTime" to "10:00", "fee" to "80.00"),
            "lateCheckOut" to mapOf("limitTime" to "16:00", "fee" to "100.00"),
            "childMaxAge" to 12,
            "childAgeBands" to
                listOf(
                    mapOf("minAge" to 0, "maxAge" to 5, "label" to "0 a 5 anos"),
                    mapOf("minAge" to 6, "maxAge" to 12, "label" to "6 a 12 anos"),
                ),
            "stayPolicyText" to "A diária tem 24 horas; até 2 horas são reservadas à arrumação da UH.",
            "additionalPolicyText" to "Animais de estimação mediante consulta.",
        )

    // --- Data operacional ------------------------------------------------------------------------

    @Test
    fun `the business date is visible to any authenticated user, without permission`() {
        val user = iam.createUser()

        val body = with(iam) { call(GET, "/api/v1/hotel/business-date", asTestUser(user)).ok() }

        body["businessDate"].asString() shouldMatch Regex("""\d{4}-\d{2}-\d{2}""")
        LocalDate.parse(body["businessDate"].asString()) shouldBe storedBusinessDate()
        body["timezone"].asString() shouldBe storedTimezone()
        mockMvc
            .get("/api/v1/hotel/business-date")
            .andReturn()
            .response.status shouldBe 401
    }

    @Test
    fun `no endpoint advances the business date - only reads and the settings edits exist under hotel`() {
        val endpoints =
            handlerMapping.handlerMethods.keys
                .filter { info -> info.patternValues.any { it.startsWith("/api/v1/hotel") } }
                .flatMap { info ->
                    info.methodsCondition.methods.flatMap { m -> info.patternValues.map { "${m.name} $it" } }
                }

        endpoints shouldContainExactlyInAnyOrder
            listOf(
                "GET /api/v1/hotel",
                "PUT /api/v1/hotel",
                "GET /api/v1/hotel/policies",
                "PUT /api/v1/hotel/policies",
                "GET /api/v1/hotel/tax-settings",
                "PUT /api/v1/hotel/tax-settings",
                "GET /api/v1/hotel/operational-settings",
                "PUT /api/v1/hotel/operational-settings",
                "GET /api/v1/hotel/business-date",
            )
    }

    @Test
    fun `the permissions are in the catalog - reading suggested to manager, receptionist and finance`() {
        val holders =
            jdbc
                .sql(
                    """
                    SELECT r.template_key, g.permission_code FROM iam.role_grant g JOIN iam.role r ON r.id = g.role_id
                    WHERE r.template_key IS NOT NULL AND g.permission_code LIKE 'hotel.%'
                    """.trimIndent(),
                ).query { rs, _ -> "${rs.getString(1)}:${rs.getString(2)}" }
                .list()

        holders shouldContainExactlyInAnyOrder
            listOf(
                "ADMINISTRATOR:hotel.settings.read",
                "ADMINISTRATOR:hotel.settings.manage",
                "MANAGER:hotel.settings.read",
                "RECEPTIONIST:hotel.settings.read",
                "FINANCE:hotel.settings.read",
            )
        HotelPermissions.permissions.none { it.sensitive } shouldBe true
    }

    @Test
    fun `the hotel is created once at boot, by the system, with every part audited`() {
        jdbc.sql("SELECT count(*) FROM hotel.hotel").query(Int::class.java).single() shouldBe 1
        val entries =
            jdbc
                .sql("SELECT entity_type, user_id FROM audit.audit_entry WHERE action = 'hotel.hotel.initialize'")
                .query { rs, _ -> rs.getString(1) to rs.getObject(2, UUID::class.java) }
                .list()

        entries shouldContainExactlyInAnyOrder
            listOf(
                "hotel.hotel" to null,
                "hotel.policy" to null,
                "hotel.tax-settings" to null,
                "hotel.operational-settings" to null,
                "hotel.business-day" to null,
            )
    }

    // --- Dados do hotel ----------------------------------------------------------------------------

    @Test
    fun `reading the settings requires hotel settings read`(): Unit =
        with(iam) {
            val nobody = iam.createUser()
            val reader = iam.createUserWith(read)

            for (path in SETTINGS_PATHS) {
                call(GET, path, asTestUser(nobody)).problem(403, "PERMISSION_DENIED")
                val response = call(GET, path, asTestUser(reader))
                withClue(path) {
                    response.status shouldBe 200
                    response.getHeader(HttpHeaders.ETAG).shouldNotBeNull() shouldMatch Regex("\"\\d+\"")
                }
            }
        }

    @Test
    fun `the hotel data is edited with permission, If-Match and a valid CNPJ, and audited`(): Unit =
        with(iam) {
            val reader = iam.createUserWith(read)
            val etag = etagOf("/api/v1/hotel")

            // Sem permissão (com o If-Match certo): 403.
            call(PUT, "/api/v1/hotel", asTestUser(reader), hotelBody, ifMatch = etag).problem(403, "PERMISSION_DENIED")
            // Sem If-Match: 428; If-Match de outra versão: 412.
            call(PUT, "/api/v1/hotel", asAdmin(), hotelBody).problem(428, "PRECONDITION_REQUIRED")
            call(PUT, "/api/v1/hotel", asAdmin(), hotelBody, ifMatch = "\"999999\"").problem(412, "PRECONDITION_FAILED")
            // CNPJ com dígito verificador errado: 400 com o campo apontado.
            val invalidCnpj =
                call(PUT, "/api/v1/hotel", asAdmin(), hotelBody + ("cnpj" to "11.222.333/0001-82"), ifMatch = etag)
                    .problem(400, "VALIDATION_FAILED")
            invalidCnpj["errors"].values().map { it["field"].asString() to it["code"].asString() } shouldContainExactly
                listOf("cnpj" to "INVALID_FORMAT")
            // Código IBGE de outra UF e fuso inexistente.
            val otherState = (hotelBody["address"] as Map<*, *>) + ("ibgeCityCode" to "3304557")
            call(PUT, "/api/v1/hotel", asAdmin(), hotelBody + ("address" to otherState), ifMatch = etag)
                .problem(400, "INVALID_ADDRESS")["errors"][0]["field"]
                .asString() shouldBe "address.ibgeCityCode"
            call(PUT, "/api/v1/hotel", asAdmin(), hotelBody + ("timezone" to "Lua/Base"), ifMatch = etag)
                .problem(400, "INVALID_TIMEZONE")
            etagOf("/api/v1/hotel") shouldBe etag

            val response = call(PUT, "/api/v1/hotel", asAdmin(), hotelBody, ifMatch = etag)

            val body = response.ok()
            body["configured"].asBoolean() shouldBe true
            body["cnpj"].asString() shouldBe "11222333000181"
            body["address"]["postalCode"].asString() shouldBe "01310100"
            body["timezone"].asString() shouldBe "America/Sao_Paulo"
            val newEtag = response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
            newEtag shouldNotBe etag
            etagOf("/api/v1/hotel") shouldBe newEtag
            // A versão antiga não serve mais.
            call(PUT, "/api/v1/hotel", asAdmin(), hotelBody + ("tradeName" to "Outro"), ifMatch = etag)
                .problem(412, "PRECONDITION_FAILED")
            // Um usuário com a permissão (fora da role de sistema) também edita.
            val editor = iam.createUserWith(read, manage)
            call(
                PUT,
                "/api/v1/hotel",
                asTestUser(editor),
                hotelBody + ("tradeName" to "Pousada Nova"),
                ifMatch = newEtag,
            ).ok()["tradeName"]
                .asString() shouldBe "Pousada Nova"
            auditActions("hotel.hotel") shouldContain "hotel.hotel.update"
            lastAudit("hotel.hotel.update").userId shouldBe editor.id.value
        }

    // --- Políticas ----------------------------------------------------------------------------------

    @Test
    fun `policies reject a cleaning window above 3 hours and incoherent times (RN-11)`(): Unit =
        with(iam) {
            val etag = etagOf("/api/v1/hotel/policies")

            call(
                PUT,
                "/api/v1/hotel/policies",
                asAdmin(),
                policyBody + mapOf("checkOutTime" to "11:00", "checkInTime" to "15:00", "cleaningWindowMinutes" to 240),
                ifMatch = etag,
            ).problem(400, "CLEANING_WINDOW_TOO_LONG")
            call(
                PUT,
                "/api/v1/hotel/policies",
                asAdmin(),
                policyBody + mapOf("checkOutTime" to "10:00", "checkInTime" to "14:00", "cleaningWindowMinutes" to 120),
                ifMatch = etag,
            ).problem(400, "CLEANING_WINDOW_TOO_LONG")
            call(PUT, "/api/v1/hotel/policies", asAdmin(), policyBody + ("checkInTime" to "11:00"), ifMatch = etag)
                .problem(400, "CHECK_TIMES_INCOHERENT")
            call(
                PUT,
                "/api/v1/hotel/policies",
                asAdmin(),
                policyBody + ("cleaningWindowMinutes" to 150),
                ifMatch = etag,
            ).problem(400, "CLEANING_WINDOW_INVALID")
            call(PUT, "/api/v1/hotel/policies", asAdmin(), policyBody + ("stayPolicyText" to " "), ifMatch = etag)
                .problem(400, "VALIDATION_FAILED")
            call(PUT, "/api/v1/hotel/policies", asAdmin(), policyBody + ("checkInTime" to "14:00:30"), ifMatch = etag)
                .problem(400, "TIME_NOT_WHOLE_MINUTE")["errors"][0]["field"]
                .asString() shouldBe "checkInTime"
            call(PUT, "/api/v1/hotel/policies", asAdmin(), policyBody).problem(428, "PRECONDITION_REQUIRED")
            call(PUT, "/api/v1/hotel/policies", asTestUser(iam.createUserWith(read)), policyBody, ifMatch = etag)
                .problem(403, "PERMISSION_DENIED")
            etagOf("/api/v1/hotel/policies") shouldBe etag

            val body = call(PUT, "/api/v1/hotel/policies", asAdmin(), policyBody, ifMatch = etag).ok()

            LocalTimeText(body["checkInTime"].asString()) shouldBe LocalTimeText("14:00")
            body["cleaningWindowMinutes"].asInt() shouldBe 120
            body["earlyCheckIn"]["fee"].asString() shouldBe "80.00"
            body["childAgeBands"].values().map { it["label"].asString() } shouldContainExactly
                listOf("0 a 5 anos", "6 a 12 anos")
            storedCheckInTime() shouldBe "14:00:00"
            lastAudit("hotel.policy.update").userId shouldBe adminId()
        }

    // --- Taxas e parâmetros ----------------------------------------------------------------------------

    @Test
    fun `tax settings use Percent and Money, with the tourism fee optional`(): Unit =
        with(iam) {
            val etag = etagOf("/api/v1/hotel/tax-settings")
            val taxes = mapOf("serviceChargeRate" to "10", "issRate" to "5.0000", "tourismFeePerPersonNight" to "3.50")

            call(PUT, "/api/v1/hotel/tax-settings", asAdmin(), taxes + ("issRate" to "7"), ifMatch = etag)
                .problem(400, "ISS_RATE_INVALID")
            call(PUT, "/api/v1/hotel/tax-settings", asAdmin(), taxes + ("serviceChargeRate" to "10.5%"), ifMatch = etag)
                .problem(400, "VALIDATION_FAILED")
            call(
                PUT,
                "/api/v1/hotel/tax-settings",
                asAdmin(),
                taxes + ("tourismFeePerPersonNight" to "3.505"),
                ifMatch = etag,
            ).problem(400, "VALIDATION_FAILED")
            // Sem permissão (com o If-Match certo): 403; sem If-Match: 428; outra versão: 412.
            call(PUT, "/api/v1/hotel/tax-settings", asTestUser(iam.createUserWith(read)), taxes, ifMatch = etag)
                .problem(403, "PERMISSION_DENIED")
            call(PUT, "/api/v1/hotel/tax-settings", asAdmin(), taxes).problem(428, "PRECONDITION_REQUIRED")
            call(PUT, "/api/v1/hotel/tax-settings", asAdmin(), taxes, ifMatch = "\"999999\"")
                .problem(412, "PRECONDITION_FAILED")
            etagOf("/api/v1/hotel/tax-settings") shouldBe etag

            val body = call(PUT, "/api/v1/hotel/tax-settings", asAdmin(), taxes, ifMatch = etag).ok()

            body["serviceChargeRate"].asString() shouldBe "10.0000"
            body["issRate"].asString() shouldBe "5.0000"
            body["tourismFeePerPersonNight"].asString() shouldBe "3.50"
            val off =
                call(
                    PUT,
                    "/api/v1/hotel/tax-settings",
                    asAdmin(),
                    taxes + ("tourismFeePerPersonNight" to null),
                    ifMatch = etagOf("/api/v1/hotel/tax-settings"),
                ).ok()
            off["tourismFeePerPersonNight"].isNull shouldBe true
            lastAudit("hotel.tax-settings.update").userId shouldBe adminId()
        }

    @Test
    fun `operational settings are validated, edited with If-Match and audited`(): Unit =
        with(iam) {
            val etag = etagOf("/api/v1/hotel/operational-settings")
            val settings =
                mapOf(
                    "endOfDayTime" to "03:00",
                    "preReservationExpiryHours" to 48,
                    "preReservationAlertLeadHours" to 12,
                    "lostAndFoundRetentionDays" to 90,
                )

            call(
                PUT,
                "/api/v1/hotel/operational-settings",
                asAdmin(),
                settings + ("lostAndFoundRetentionDays" to 0),
                ifMatch = etag,
            ).problem(400, "OPERATIONAL_SETTING_INVALID")["errors"][0]["field"]
                .asString() shouldBe "lostAndFoundRetentionDays"
            call(PUT, "/api/v1/hotel/operational-settings", asAdmin(), settings, ifMatch = "\"999999\"")
                .problem(412, "PRECONDITION_FAILED")
            call(
                PUT,
                "/api/v1/hotel/operational-settings",
                asAdmin(),
                settings + ("endOfDayTime" to "03:00:15"),
                ifMatch = etag,
            ).problem(400, "TIME_NOT_WHOLE_MINUTE")["errors"][0]["field"]
                .asString() shouldBe "endOfDayTime"
            // Sem permissão (com o If-Match certo): 403; sem If-Match: 428.
            call(
                PUT,
                "/api/v1/hotel/operational-settings",
                asTestUser(iam.createUserWith(read)),
                settings,
                ifMatch = etag,
            ).problem(403, "PERMISSION_DENIED")
            call(PUT, "/api/v1/hotel/operational-settings", asAdmin(), settings).problem(428, "PRECONDITION_REQUIRED")
            etagOf("/api/v1/hotel/operational-settings") shouldBe etag

            val body =
                call(
                    PUT,
                    "/api/v1/hotel/operational-settings",
                    asAdmin(),
                    settings + ("preReservationExpiryHours" to 72),
                    ifMatch = etag,
                ).ok()

            body["preReservationExpiryHours"].asInt() shouldBe 72
            lastAudit("hotel.operational-settings.update").userId shouldBe adminId()
        }

    // --- Apoio ------------------------------------------------------------------------------------------

    /** Compara horários sem depender de o JSON trazer os segundos. */
    private data class LocalTimeText(
        private val raw: String,
    ) {
        val value: java.time.LocalTime = java.time.LocalTime.parse(raw)

        override fun equals(other: Any?) = other is LocalTimeText && value == other.value

        override fun hashCode() = value.hashCode()
    }

    private fun IamTestSupport.etagOf(path: String): String {
        val response: MockHttpServletResponse = call(GET, path, asAdmin())
        withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 200 }
        return response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
    }

    private data class AuditRow(
        val userId: UUID?,
        val entityType: String?,
    )

    private fun lastAudit(action: String): AuditRow =
        jdbc
            .sql(
                "SELECT user_id, entity_type FROM audit.audit_entry WHERE action = :action " +
                    "ORDER BY occurred_at DESC, id DESC LIMIT 1",
            ).param("action", action)
            .query { rs, _ -> AuditRow(rs.getObject("user_id", UUID::class.java), rs.getString("entity_type")) }
            .single()

    private fun auditActions(entityType: String): List<String> =
        jdbc
            .sql("SELECT DISTINCT action FROM audit.audit_entry WHERE entity_type = :type")
            .param("type", entityType)
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun storedBusinessDate(): LocalDate =
        jdbc.sql("SELECT business_date FROM hotel.business_day").query(LocalDate::class.java).single()

    private fun storedTimezone(): String =
        jdbc.sql("SELECT time_zone_id FROM hotel.hotel").query(String::class.java).single()

    private fun storedCheckInTime(): String =
        jdbc.sql("SELECT CAST(check_in_time AS text) FROM hotel.policy").query(String::class.java).single()

    private companion object {
        val SETTINGS_PATHS =
            listOf(
                "/api/v1/hotel",
                "/api/v1/hotel/policies",
                "/api/v1/hotel/tax-settings",
                "/api/v1/hotel/operational-settings",
            )

        fun definition(code: PermissionCode) = HotelPermissions.permissions.single { it.code == code }
    }
}
