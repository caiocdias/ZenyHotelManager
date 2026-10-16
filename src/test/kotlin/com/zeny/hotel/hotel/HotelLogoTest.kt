package com.zeny.hotel.hotel

import com.zeny.hotel.files.FILES_TEST_URL_TTL
import com.zeny.hotel.files.FilesTestConfiguration
import com.zeny.hotel.files.FilesTestSupport
import com.zeny.hotel.files.FilesTestSupport.Companion.jpeg
import com.zeny.hotel.files.FilesTestSupport.Companion.png
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import filesexample.FileExamplePurposes
import filesexample.FileExamplesConfiguration
import io.kotest.matchers.collections.shouldContain
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

/**
 * O logotipo do hotel (RF-CFG-01, H8, M1 E08): um anexo do `files` com a finalidade `hotel.logo`,
 * conferido ao salvar os dados do hotel (aresta `hotel` → `files`) e vinculado ao hotel.
 */
@IntegrationTest
// O mesmo contexto dos testes de anexos (FilesApiTest), com o relógio controlado.
@Import(
    IamTestConfiguration::class,
    FilesTestConfiguration::class,
    FileExamplesConfiguration::class,
    MutableClockConfiguration::class,
)
@TestPropertySource(properties = [FILES_TEST_URL_TTL])
class HotelLogoTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val files: FilesTestSupport,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val jdbc: JdbcClient,
) {
    private val hotelBody =
        mapOf(
            "legalName" to "Pousada Exemplo Ltda",
            "tradeName" to "Pousada Exemplo",
            "cnpj" to "11.222.333/0001-81",
            "municipalRegistration" to null,
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
    fun `saving the hotel with a missing logo or a file of another purpose is refused with 422`(): Unit =
        with(iam) {
            val etag = etagOf()
            val photo = files.uploadOk(FileExamplePurposes.PHOTO.value, asAdmin(), png(seed = 10))

            call(PUT, "/api/v1/hotel", asAdmin(), hotelBody + ("logoFileId" to UUID.randomUUID()), ifMatch = etag)
                .problem(422, "LOGO_FILE_INVALID")
            call(PUT, "/api/v1/hotel", asAdmin(), hotelBody + ("logoFileId" to photo), ifMatch = etag)
                .problem(422, "LOGO_FILE_INVALID")
            etagOf() shouldBe etag
            files.storedRow(photo)["owner_entity_id"] shouldBe null
        }

    @Test
    fun `a logo of the logo purpose is saved and attached to the hotel`(): Unit =
        with(iam) {
            val logo = files.uploadOk(HotelFilePurposes.LOGO.value, asAdmin(), png(seed = 11), name = "logo.png")
            val hotelId = jdbc.sql("SELECT id FROM hotel.hotel").query(UUID::class.java).single()

            val response = call(PUT, "/api/v1/hotel", asAdmin(), hotelBody + ("logoFileId" to logo), ifMatch = etagOf())

            response.ok()["logoFileId"].asString() shouldBe logo.toString()
            val row = files.storedRow(logo)
            row["owner_entity_id"] shouldBe hotelId.toString()
            row["attached_at"].shouldNotBeNull()
            files.auditOf(logo).map { it.action } shouldContain "files.file.attach"
            // Salvar de novo com o mesmo logotipo não confere nem vincula outra vez.
            call(
                PUT,
                "/api/v1/hotel",
                asAdmin(),
                hotelBody + mapOf("logoFileId" to logo, "tradeName" to "Pousada Logo"),
                ifMatch = response.getHeader(HttpHeaders.ETAG),
            ).ok()["tradeName"]
                .asString() shouldBe "Pousada Logo"
            files.auditOf(logo).count { it.action == "files.file.attach" } shouldBe 1
        }

    @Test
    fun `the logo is uploaded with hotel settings manage and downloaded with hotel settings read`(): Unit =
        with(iam) {
            val reader =
                createUserWith(
                    TestGrant(
                        HotelPermissions.permissions.single {
                            it.code ==
                                HotelPermissions.SETTINGS_READ
                        },
                        ALL,
                    ),
                )

            files.upload(HotelFilePurposes.LOGO.value, asTestUser(reader), png()).problem(403, "PERMISSION_DENIED")
            // Só imagem: um PDF não é logotipo.
            files
                .upload(HotelFilePurposes.LOGO.value, asAdmin(), FilesTestSupport.pdf("x"), name = "logo.pdf")
                .problem(400, "FILE_TYPE_NOT_ACCEPTED")
            val logo = files.uploadOk(HotelFilePurposes.LOGO.value, asAdmin(), jpeg(), name = "logo.jpg")

            val (url, _) = files.downloadUrlOk(logo, asTestUser(reader))

            files.httpGet(url).statusCode() shouldBe 200
        }

    private fun etagOf(): String =
        with(iam) {
            call(GET, "/api/v1/hotel", asAdmin()).getHeader(HttpHeaders.ETAG).shouldNotBeNull()
        }
}
