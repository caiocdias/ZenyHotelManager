package com.zeny.hotel.hotel

import com.zeny.hotel.files.FILES_TEST_URL_TTL
import com.zeny.hotel.files.FilesTestConfiguration
import com.zeny.hotel.files.FilesTestSupport
import com.zeny.hotel.files.FilesTestSupport.Companion.png
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.shared.Cnpj
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import filesexample.FileExamplesConfiguration
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import java.util.UUID

/** `HotelApi.profile()` (M5 E11, D-E11-8) com o banco real: o id do hotel, os dados normalizados e o logotipo. */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    FilesTestConfiguration::class,
    FileExamplesConfiguration::class,
    MutableClockConfiguration::class,
)
@TestPropertySource(properties = [FILES_TEST_URL_TTL])
class HotelApiProfileTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val files: FilesTestSupport,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
) {
    private val hotelBody =
        mapOf(
            "legalName" to "  Pousada Perfil Ltda ",
            "tradeName" to "Pousada Perfil",
            "cnpj" to "11.222.333/0001-81",
            "municipalRegistration" to "  ",
            "cadastur" to null,
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

    @BeforeEach
    fun synchronizeThisContextCatalog() {
        synchronizer.synchronize()
    }

    @Test
    fun `profile returns the hotel id, the normalized data and the attached logo`(): Unit =
        with(iam) {
            val original = call(GET, "/api/v1/hotel", asAdmin())
            val originalBody = json(original)
            val hotelId = jdbc.sql("SELECT id FROM hotel.hotel").query(UUID::class.java).single()
            val logo = files.uploadOk(HotelFilePurposes.LOGO.value, asAdmin(), png(seed = 31), name = "logo.png")
            try {
                call(
                    PUT,
                    "/api/v1/hotel",
                    asAdmin(),
                    hotelBody + ("logoFileId" to logo),
                    ifMatch = original.getHeader(HttpHeaders.ETAG).shouldNotBeNull(),
                ).ok()

                val current = hotelApi.profile().shouldNotBeNull()

                current.hotelId shouldBe hotelId
                current.profile.legalName shouldBe "Pousada Perfil Ltda"
                current.profile.municipalRegistration shouldBe null
                current.profile.cnpj shouldBe Cnpj.of("11.222.333/0001-81")
                current.profile.logoFileId shouldBe logo
            } finally {
                val etag = call(GET, "/api/v1/hotel", asAdmin()).getHeader(HttpHeaders.ETAG)
                call(PUT, "/api/v1/hotel", asAdmin(), originalBody, ifMatch = etag)
            }
        }
}
