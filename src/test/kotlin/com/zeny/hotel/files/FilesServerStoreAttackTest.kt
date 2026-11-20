package com.zeny.hotel.files

import com.zeny.hotel.files.FilesTestSupport.Companion.pdf
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.reporting.ReportingFilePurposes
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import filesexample.FileExamplePurposes
import filesexample.FileExamplesConfiguration
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.context.TestPropertySource

/**
 * Ataques à gravação pelo servidor e à finalidade só do servidor (E23 do M6, D2): a ordem da recusa antes da
 * autorização, o ator autenticado e a cifra do anexo sensível. Mesmo contexto do [FilesServerStoreTest].
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    FilesTestConfiguration::class,
    FileExamplesConfiguration::class,
    MutableClockConfiguration::class,
)
@TestPropertySource(properties = [FILES_TEST_URL_TTL])
class FilesServerStoreAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val files: FilesTestSupport,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val filesApi: FilesApi,
    @Autowired private val jdbc: JdbcClient,
) {
    @BeforeEach
    fun synchronizeThisContextCatalog() {
        synchronizer.synchronize()
    }

    @Test
    fun `a user with no upload permission gets 400 unknown purpose on a server-only purpose, not 403`() {
        val nobody = iam.asTestUser(iam.createUserWith())
        listOf(FileExamplePurposes.SERVER_ONLY_DOCUMENT.value, ReportingFilePurposes.DAY_REPORT.value).forEach {
            with(iam) {
                files
                    .upload(it, nobody, pdf("x"), "a.pdf", "application/pdf")
                    .problem(400, "UNKNOWN_FILE_PURPOSE")
            }
        }
        // Controle: numa finalidade comum a mesma pessoa é recusada por falta de permissão.
        files
            .upload(FileExamplePurposes.PHOTO.value, nobody, FilesTestSupport.png(seed = 1), "f.png", "image/png")
            .status shouldBe 403
    }

    @Test
    fun `store with an authenticated actor records the user as creator and in the audit trail`() {
        val user = iam.createUserWith()
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(user.id.toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        val summary =
            try {
                filesApi.store(FileExamplePurposes.SERVER_ONLY_DOCUMENT, "owner-atk-1", "n.pdf", pdf("conteudo"))
            } finally {
                SecurityContextHolder.clearContext()
            }

        summary.createdBy shouldBe user.id
        files.storedRow(summary.id.value)["created_by"] shouldBe user.id.value
        files.auditOf(summary.id.value).single().userId shouldBe user.id.value
    }

    @Test
    fun `a sensitive attachment stored by the server has an encryption key id and a common one has none`() {
        val sensitive = filesApi.store(FileExamplePurposes.SERVER_ONLY_DOCUMENT, "owner-atk-2", "n.pdf", pdf("c"))
        val common = filesApi.store(FileExamplePurposes.PHOTO, "owner-atk-3", "f.png", FilesTestSupport.png(seed = 2))

        fun keyOf(id: java.util.UUID): String? =
            jdbc
                .sql("SELECT encryption_key_id FROM files.stored_file WHERE id = :id")
                .param("id", id)
                .query(String::class.java)
                .optional()
                .orElse(null)
        (keyOf(sensitive.id.value) != null) shouldBe true
        keyOf(common.id.value) shouldBe null
    }
}
