package com.zeny.hotel.hotel

import com.zeny.hotel.files.FILES_TEST_URL_TTL
import com.zeny.hotel.files.FilesTestConfiguration
import com.zeny.hotel.files.FilesTestSupport
import com.zeny.hotel.files.FilesTestSupport.Companion.png
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import filesexample.FileExamplesConfiguration
import io.kotest.matchers.nulls.shouldBeNull
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

/** Ataque a `HotelApi.profile()` (M5 E11, D-E11-7/8): o que o `printing` lê para o cabeçalho e o dono do logotipo. */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    FilesTestConfiguration::class,
    FileExamplesConfiguration::class,
    MutableClockConfiguration::class,
)
@TestPropertySource(properties = [FILES_TEST_URL_TTL])
class HotelApiProfileAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val files: FilesTestSupport,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
) {
    private fun body(
        logo: UUID?,
        legalName: String = "Pousada Atque Ltda",
        municipal: String? = null,
        cadastur: String? = null,
    ) = mapOf(
        "legalName" to legalName,
        "tradeName" to "Pousada Atque",
        "cnpj" to "11.222.333/0001-81",
        "municipalRegistration" to municipal,
        "cadastur" to cadastur,
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
        "logoFileId" to logo,
    )

    @BeforeEach
    fun synchronizeThisContextCatalog() {
        synchronizer.synchronize()
    }

    private fun withHotel(block: IamTestSupport.(save: (Map<String, Any?>) -> Unit) -> Unit) =
        with(iam) {
            val original = json(call(GET, "/api/v1/hotel", asAdmin()))

            fun save(payload: Map<String, Any?>) {
                val etag = call(GET, "/api/v1/hotel", asAdmin()).getHeader(HttpHeaders.ETAG).shouldNotBeNull()
                call(PUT, "/api/v1/hotel", asAdmin(), payload, ifMatch = etag).ok()
            }
            try {
                block { save(it) }
            } finally {
                val etag = call(GET, "/api/v1/hotel", asAdmin()).getHeader(HttpHeaders.ETAG)
                call(PUT, "/api/v1/hotel", asAdmin(), original, ifMatch = etag)
            }
        }

    @Test
    fun `profile has no logo id when the hotel is configured without a logo`() =
        withHotel { save ->
            save(body(logo = null))

            hotelApi
                .profile()
                .shouldNotBeNull()
                .profile.logoFileId
                .shouldBeNull()
        }

    @Test
    fun `profile shows the new logo id after the logo is replaced`() =
        withHotel { save ->
            val first = files.uploadOk(HotelFilePurposes.LOGO.value, asAdmin(), png(seed = 41), name = "a.png")
            val second = files.uploadOk(HotelFilePurposes.LOGO.value, asAdmin(), png(seed = 42), name = "b.png")
            save(body(logo = first))
            hotelApi
                .profile()
                .shouldNotBeNull()
                .profile.logoFileId shouldBe first

            save(body(logo = second))

            hotelApi
                .profile()
                .shouldNotBeNull()
                .profile.logoFileId shouldBe second
        }

    @Test
    fun `profile keeps special characters and optional fields as stored`() =
        withHotel { save ->
            val name = "Pousada <São João> & Cia \"Ltda\" ção ñ 🏨"
            save(body(logo = null, legalName = name, municipal = "123.456-7", cadastur = "26.123456.10.0001-8"))

            val profile = hotelApi.profile().shouldNotBeNull().profile

            profile.legalName shouldBe name
            profile.municipalRegistration shouldBe "123.456-7"
            profile.cadastur shouldBe "26.123456.10.0001-8"
        }

    @Test
    fun `profile hotel id is the owner of the logo attachment in files`() =
        withHotel { save ->
            val logo = files.uploadOk(HotelFilePurposes.LOGO.value, asAdmin(), png(seed = 43), name = "c.png")
            save(body(logo = logo))

            val current = hotelApi.profile().shouldNotBeNull()

            files.storedRow(logo)["owner_entity_id"] shouldBe current.hotelId.toString()
            current.hotelId shouldBe jdbc.sql("SELECT id FROM hotel.hotel").query(UUID::class.java).single()
        }

    @Test
    fun `profile is read straight from the api without any authenticated caller`() =
        withHotel { save ->
            save(body(logo = null))

            // chamada direta, fora de requisição: sem ator nem permissão (D2), e não lança
            hotelApi
                .profile()
                .shouldNotBeNull()
                .profile.tradeName shouldBe "Pousada Atque"
        }
}
