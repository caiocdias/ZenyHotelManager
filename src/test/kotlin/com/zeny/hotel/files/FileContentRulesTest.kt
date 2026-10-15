package com.zeny.hotel.files

import com.zeny.hotel.files.FilesTestSupport.Companion.jpeg
import com.zeny.hotel.files.FilesTestSupport.Companion.pdf
import com.zeny.hotel.files.FilesTestSupport.Companion.png
import com.zeny.hotel.files.application.FilePurposeCatalog
import com.zeny.hotel.files.application.FilesProperties
import com.zeny.hotel.files.domain.FileContentRules
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.boot.servlet.autoconfigure.MultipartProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

/** Regras do upload e do catálogo de finalidades (ARCHITECTURE §14), sem Spring nem banco. */
class FileContentRulesTest {
    private val upload = PermissionCode("examples.files.upload")
    private val read = PermissionCode("examples.files.read")
    private val images =
        FilePurpose(
            FilePurposeCode("examples.room-photo"),
            "Foto",
            100,
            setOf(FileType.PNG, FileType.JPEG, FileType.WEBP),
            sensitive = false,
            uploadPermission = upload,
            downloadPermission = read,
        )

    @Test
    fun `the type comes from the content signature, never from the name`() {
        val webp = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBPVP8 ".toByteArray() + ByteArray(8)

        FileType.detect(png()) shouldBe FileType.PNG
        FileType.detect(jpeg()) shouldBe FileType.JPEG
        FileType.detect(webp) shouldBe FileType.WEBP
        FileType.detect(pdf("x")) shouldBe FileType.PDF
        FileType.detect("<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray()) shouldBe null
        FileType.detect("RIFF0000WAVEfmt ".toByteArray()) shouldBe null
        FileType.detect(byteArrayOf(0x89.toByte(), 0x50)) shouldBe null

        val validated = FileContentRules.validate(images, png(size = 100), "C:\\fotos\\uh 12.png")
        validated.type shouldBe FileType.PNG
        validated.sizeBytes shouldBe 100
        validated.sha256 shouldBe FileContentRules.sha256(png(size = 100))
        validated.originalName shouldBe "uh 12.png"
        FileContentRules.validate(images, jpeg(), "FOTO.JPEG").type shouldBe FileType.JPEG
        FileContentRules.validate(images, jpeg(), null).originalName shouldBe null
    }

    @Test
    fun `size, type and extension outside the purpose are refused with the field`() {
        fun code(
            content: ByteArray,
            name: String? = "a.png",
        ) = shouldThrow<InvalidInputException> { FileContentRules.validate(images, content, name) }
            .also { it.violations.single().field shouldBe "file" }
            .code.value

        code(ByteArray(0)) shouldBe "FILE_EMPTY"
        code(png(size = 101)) shouldBe "FILE_TOO_LARGE"
        code(pdf("x"), "a.png") shouldBe "FILE_TYPE_NOT_ACCEPTED"
        code("texto".toByteArray(), "a.png") shouldBe "FILE_TYPE_NOT_ACCEPTED"
        code(png(), "a.jpg") shouldBe "FILE_EXTENSION_MISMATCH"
        code(png(), "a.pdf") shouldBe "FILE_EXTENSION_MISMATCH"
    }

    @Test
    fun `names lose directories and control characters`() {
        FileContentRules.sanitizeName("../../etc/passwd") shouldBe "passwd"
        FileContentRules.sanitizeName("a\u0000b\nc.png") shouldBe "abc.png"
        FileContentRules.sanitizeName("..") shouldBe null
        FileContentRules.sanitizeName("   ") shouldBe null
        FileContentRules.sanitizeName("x".repeat(300) + ".png")!!.length shouldBe FileContentRules.MAX_NAME_LENGTH
    }

    @Test
    fun `purpose codes follow module dot name`() {
        FilePurposeCode("guests.identity-document").module shouldBe "guests"
        FilePurposeCode("guests.identity-document").name shouldBe "identity-document"
        listOf("guests", "Guests.doc", "guests.Doc", "guests.doc_x", "guests.doc.x", ".doc").forEach {
            shouldThrow<IllegalArgumentException> { FilePurposeCode(it) }
        }
    }

    @Test
    fun `the catalog refuses purposes of another module, repeated, too large or with unknown permissions`() {
        val permissions = permissionsOf(listOf(upload, read))
        val properties = FilesProperties(Duration.ofMinutes(5), DataSize.ofBytes(1000))

        fun catalog(vararg purposes: FilePurpose) =
            FilePurposeCatalog(listOf(purposesOf("examples", *purposes)), listOf(permissions), properties, multipart())

        catalog(images).find(images.code) shouldBe images
        shouldThrow<IllegalArgumentException> {
            catalog(images.copy(code = FilePurposeCode("other.photo")))
        }.message shouldContain "não é do módulo"
        shouldThrow<IllegalArgumentException> { catalog(images, images) }.message shouldContain "repetida"
        shouldThrow<IllegalArgumentException> {
            catalog(images.copy(maxSizeBytes = 1001))
        }.message shouldContain "max-upload-size"
        shouldThrow<IllegalArgumentException> {
            catalog(images.copy(downloadPermission = PermissionCode("examples.files.unknown")))
        }.message shouldContain "examples.files.unknown"
        shouldThrow<IllegalArgumentException> {
            FilePurposeCatalog(
                listOf(purposesOf("examples", images), purposesOf("examples")),
                listOf(permissions),
                properties,
                multipart(),
            )
        }
    }

    @Test
    fun `the boot refuses multipart limits below the largest accepted upload`() {
        val permissions = permissionsOf(listOf(upload, read))
        val properties = FilesProperties(Duration.ofMinutes(5), DataSize.ofBytes(1000))

        fun catalog(
            multipart: MultipartProperties,
            purposes: List<FilePurpose> = listOf(images),
        ) = FilePurposeCatalog(
            listOf(purposesOf("examples", *purposes.toTypedArray())),
            listOf(permissions),
            properties,
            multipart,
        )

        // O arquivo pode ir até o max-upload-size, e o pedido inteiro precisa de folga acima dele.
        catalog(multipart(fileBytes = 1000, requestBytes = 1001)).find(images.code) shouldBe images
        catalog(multipart(fileBytes = -1, requestBytes = -1)).find(images.code) shouldBe images
        catalog(multipart(fileBytes = 1000, requestBytes = 2000), listOf(images.copy(maxSizeBytes = 1000)))
            .find(images.code)!!
            .maxSizeBytes shouldBe 1000

        shouldThrow<IllegalArgumentException> { catalog(multipart(fileBytes = 999, requestBytes = 2000)) }
            .message shouldContain
            "spring.servlet.multipart.max-file-size (999B) é menor que zeny.files.max-upload-size"
        shouldThrow<IllegalArgumentException> { catalog(multipart(fileBytes = 2000, requestBytes = 1000)) }
            .message shouldContain
            "spring.servlet.multipart.max-request-size (1000B) não supera zeny.files.max-upload-size"
        // Sem nenhuma finalidade, o limite ainda é o max-upload-size.
        shouldThrow<IllegalArgumentException> { catalog(multipart(fileBytes = 999, requestBytes = 2000), emptyList()) }
            .message shouldContain "max-file-size"
        // Os padrões do Boot (1 MB e 10 MB) não servem aos 20 MB da configuração da aplicação.
        shouldThrow<IllegalArgumentException> {
            FilePurposeCatalog(
                listOf(purposesOf("examples", images)),
                listOf(permissions),
                FilesProperties(Duration.ofMinutes(5), DataSize.ofMegabytes(20)),
                MultipartProperties(),
            )
        }.message shouldContain "max-file-size (1048576B)"
    }

    @Test
    fun `download urls are short-lived`() {
        shouldThrow<IllegalArgumentException> { FilesProperties(Duration.ofMinutes(16), DataSize.ofMegabytes(1)) }
        shouldThrow<IllegalArgumentException> { FilesProperties(Duration.ZERO, DataSize.ofMegabytes(1)) }
        FilesProperties(Duration.ofMinutes(15), DataSize.ofMegabytes(1)).downloadUrlTtl shouldBe Duration.ofMinutes(15)
    }

    private fun multipart(
        fileBytes: Long = 1000,
        requestBytes: Long = 1001,
    ) = MultipartProperties().apply {
        maxFileSize = DataSize.ofBytes(fileBytes)
        maxRequestSize = DataSize.ofBytes(requestBytes)
    }

    private fun purposesOf(
        module: String,
        vararg purposes: FilePurpose,
    ) = object : ModuleFilePurposes {
        override val module = module
        override val purposes = purposes.toList()
    }

    private fun permissionsOf(codes: List<PermissionCode>) =
        object : ModulePermissions {
            override val module = "examples"
            override val permissions = codes.map { PermissionDefinition(it, "Exemplo", setOf(ALL)) }
        }
}
