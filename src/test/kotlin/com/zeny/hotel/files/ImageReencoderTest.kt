package com.zeny.hotel.files

import com.zeny.hotel.files.TestImages.METADATA_MARKER
import com.zeny.hotel.files.TestImages.POLYGLOT_PAYLOAD
import com.zeny.hotel.files.domain.ImageReencoder
import com.zeny.hotel.shared.InvalidInputException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.awt.Color

/**
 * Reencode das imagens (H11 do roadmap do M2), sem Spring: metadados EXIF, GPS e de texto não
 * sobrevivem, poliglotas perdem o que não é imagem, o lado máximo é respeitado, a orientação EXIF
 * vai para os pixels e o teto de pixels é conferido antes de decodificar.
 */
class ImageReencoderTest {
    private val maxSide = 2048

    @Test
    fun `a jpeg with exif and gps comes out without any metadata segment and within the maximum side`() {
        val original = TestImages.jpeg(3000, 1000, exif = true)
        // A entrada tem mesmo o APP1 com o marcador (o teste não passaria por acaso).
        TestImages.jpegMarkers(original).take(2) shouldContainExactly listOf(APP1, APP0)
        String(original, Charsets.ISO_8859_1).contains(METADATA_MARKER) shouldBe true

        val reencoded = ImageReencoder.reencode(original, FileType.JPEG, maxSide)

        FileType.detect(reencoded) shouldBe FileType.JPEG
        TestImages.jpegMarkers(reencoded) shouldNotContain APP1
        TestImages.jpegMarkers(reencoded).filter { it in APPN } shouldContainExactly listOf(APP0) // só o JFIF
        val text = String(reencoded, Charsets.ISO_8859_1)
        text shouldNotContain METADATA_MARKER
        text shouldNotContain "Exif"
        val image = TestImages.read(reencoded)
        image.width shouldBe 2048
        image.height shouldBe 683
    }

    @Test
    fun `the exif orientation is applied to the pixels before the metadata goes away`() {
        // 40 × 20: a metade esquerda vermelha. Orientação 6 = girar 90° no sentido horário: a esquerda vira o topo.
        val rotated =
            TestImages.read(
                ImageReencoder.reencode(TestImages.jpeg(40, 20, orientation = 6), FileType.JPEG, 2048),
            )
        rotated.width shouldBe 20
        rotated.height shouldBe 40
        dominant(rotated.getRGB(10, 5)) shouldBe 'R'
        dominant(rotated.getRGB(10, 35)) shouldBe 'B'

        // Orientação 8 = girar 90° no sentido anti-horário: a esquerda vira a base.
        val counter =
            TestImages.read(
                ImageReencoder.reencode(TestImages.jpeg(40, 20, orientation = 8), FileType.JPEG, 2048),
            )
        counter.width shouldBe 20
        dominant(counter.getRGB(10, 35)) shouldBe 'R'

        // Orientação 3 = 180°: a esquerda vira a direita.
        val upsideDown =
            TestImages.read(
                ImageReencoder.reencode(TestImages.jpeg(40, 20, orientation = 3), FileType.JPEG, 2048),
            )
        upsideDown.width shouldBe 40
        dominant(upsideDown.getRGB(35, 10)) shouldBe 'R'

        // Sem EXIF (ou orientação 1), nada gira.
        val plain = TestImages.read(ImageReencoder.reencode(TestImages.jpeg(40, 20, exif = false), FileType.JPEG, 2048))
        plain.width shouldBe 40
        dominant(plain.getRGB(5, 10)) shouldBe 'R'
    }

    @Test
    fun `a png keeps its transparency but loses text and exif chunks and anything after the end`() {
        val original = TestImages.png(64, 48, alpha = true, trailing = true)
        TestImages.pngChunks(original).first.let { it.contains("tEXt") && it.contains("eXIf") } shouldBe true

        val reencoded = ImageReencoder.reencode(original, FileType.PNG, maxSide)

        val (chunks, trailing) = TestImages.pngChunks(reencoded)
        chunks.first() shouldBe "IHDR"
        chunks.last() shouldBe "IEND"
        chunks.filter { it in setOf("tEXt", "zTXt", "iTXt", "eXIf", "iCCP", "tIME") } shouldBe emptyList()
        trailing shouldBe false
        val text = String(reencoded, Charsets.ISO_8859_1)
        text shouldNotContain METADATA_MARKER
        text shouldNotContain "<script"
        val image = TestImages.read(reencoded)
        image.width shouldBe 64
        image.colorModel.hasAlpha() shouldBe true
        (image.getRGB(5, 5) ushr 24) shouldBe 128 // a metade esquerda é semitransparente
    }

    @Test
    fun `a polyglot jpeg loses the comment and the bytes after the image`() {
        val original = TestImages.polyglotJpeg()
        String(original, Charsets.ISO_8859_1).contains("<script") shouldBe true

        val reencoded = ImageReencoder.reencode(original, FileType.JPEG, maxSide)

        String(reencoded, Charsets.ISO_8859_1) shouldNotContain "<script"
        TestImages.jpegMarkers(reencoded) shouldNotContain COM
        // Termina no EOI: nada depois da imagem.
        reencoded.takeLast(2).map { it.toInt() and 0xFF } shouldBe listOf(0xFF, 0xD9)
        POLYGLOT_PAYLOAD.length shouldBeGreaterThan 0
    }

    @Test
    fun `a small image is not enlarged, and a much larger one is subsampled down to the maximum side`() {
        val small = TestImages.read(ImageReencoder.reencode(TestImages.png(100, 50), FileType.PNG, maxSide))
        small.width shouldBe 100
        small.height shouldBe 50

        // 5000 px: o dobro do lado máximo ou mais, então decodificado já subamostrado.
        val wide =
            TestImages.read(
                ImageReencoder.reencode(TestImages.jpeg(5000, 100, exif = false), FileType.JPEG, maxSide),
            )
        wide.width shouldBe 2048
        wide.height shouldBe 41
        dominant(wide.getRGB(10, 20)) shouldBe 'R'
        dominant(wide.getRGB(2040, 20)) shouldBe 'B'

        val custom =
            TestImages.read(
                ImageReencoder.reencode(TestImages.jpeg(300, 200, exif = false), FileType.JPEG, 150),
            )
        custom.width shouldBe 150
        custom.height shouldBe 100
    }

    @Test
    fun `an image above the pixel ceiling is refused from its header, and unreadable content is refused`() {
        val huge =
            shouldThrow<InvalidInputException> {
                ImageReencoder.reencode(TestImages.pngHeader(100_000, 100_000), FileType.PNG, maxSide)
            }
        huge.code.value shouldBe "FILE_IMAGE_TOO_LARGE"
        huge.violations.single().field shouldBe "file"

        // Logo acima do teto (50 MP) é recusada; no teto, só o cabeçalho não basta para ler: ilegível.
        shouldThrow<InvalidInputException> {
            ImageReencoder.reencode(TestImages.pngHeader(10_000, 5_001), FileType.PNG, maxSide)
        }.code.value shouldBe "FILE_IMAGE_TOO_LARGE"
        shouldThrow<InvalidInputException> {
            ImageReencoder.reencode(TestImages.pngHeader(10_000, 5_000), FileType.PNG, maxSide)
        }.code.value shouldBe "FILE_IMAGE_UNREADABLE"

        // A assinatura certa e o resto lixo.
        shouldThrow<InvalidInputException> {
            ImageReencoder.reencode(FilesTestSupport.png(size = 200), FileType.PNG, maxSide)
        }.code.value shouldBe "FILE_IMAGE_UNREADABLE"
        shouldThrow<InvalidInputException> {
            ImageReencoder.reencode(FilesTestSupport.jpeg(size = 200), FileType.JPEG, maxSide)
        }.code.value shouldBe "FILE_IMAGE_UNREADABLE"
        shouldThrow<IllegalArgumentException> {
            ImageReencoder.reencode(FilesTestSupport.pdf("x"), FileType.PDF, maxSide)
        }
    }

    private fun dominant(rgb: Int): Char {
        val color = Color(rgb, true)
        return when {
            color.red > 2 * color.blue && color.red > 2 * color.green -> 'R'
            color.blue > 2 * color.red && color.blue > 2 * color.green -> 'B'
            else -> '?'
        }
    }

    private companion object {
        const val APP0 = 0xE0
        const val APP1 = 0xE1
        const val COM = 0xFE
        val APPN = 0xE0..0xEF
    }
}
