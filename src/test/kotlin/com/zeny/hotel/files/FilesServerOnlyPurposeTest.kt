package com.zeny.hotel.files

import com.zeny.hotel.files.FilesTestSupport.Companion.pdf
import com.zeny.hotel.files.application.FilePurposeCatalog
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.reporting.ReportingFilePurposes
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import filesexample.FileExamplePermissions
import filesexample.FileExamplePermissions.definition
import filesexample.FileExamplePurposes
import filesexample.FileExamplesConfiguration
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

/** A finalidade só do servidor (E23 do M6, D2): o catálogo a aceita e o upload pela API a recusa. */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    FilesTestConfiguration::class,
    FileExamplesConfiguration::class,
    MutableClockConfiguration::class,
)
@TestPropertySource(properties = [FILES_TEST_URL_TTL])
class FilesServerOnlyPurposeTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val files: FilesTestSupport,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val catalog: FilePurposeCatalog,
) {
    @BeforeEach
    fun synchronizeThisContextCatalog() {
        synchronizer.synchronize()
    }

    @Test
    fun `the catalog accepts a server-only purpose and the others stay open to upload`() {
        catalog.find(FileExamplePurposes.SERVER_ONLY_DOCUMENT)!!.serverOnly shouldBe true
        // As finalidades só do servidor: a de teste e o PDF do fechamento do reporting (M6 E23).
        val serverOnly = setOf(FileExamplePurposes.SERVER_ONLY_DOCUMENT, ReportingFilePurposes.DAY_REPORT)
        catalog.all().filter { it.code !in serverOnly }.forEach { it.serverOnly shouldBe false }
        catalog.find(ReportingFilePurposes.DAY_REPORT)!!.serverOnly shouldBe true
    }

    @Test
    fun `upload to a server-only purpose is refused as unknown for a user with the permission and for the admin`() {
        val before = files.countRows(FileExamplePurposes.SERVER_ONLY_DOCUMENT.value)
        with(iam) {
            val user = createUserWith(TestGrant(definition(FileExamplePermissions.DOCUMENTS_UPLOAD), ALL))
            listOf(asTestUser(user), asAdmin()).forEach { auth ->
                files
                    .upload(FileExamplePurposes.SERVER_ONLY_DOCUMENT.value, auth, pdf("x"), "a.pdf", "application/pdf")
                    .problem(400, "UNKNOWN_FILE_PURPOSE")
                    .let { it["errors"][0]["field"].asString() shouldBe "purpose" }
            }
        }
        files.countRows(FileExamplePurposes.SERVER_ONLY_DOCUMENT.value) shouldBe before
    }
}
