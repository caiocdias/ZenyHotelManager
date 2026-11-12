package com.zeny.hotel.printing

import com.zeny.hotel.printing.domain.DocumentLogo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import javax.imageio.ImageIO

class DocumentLogoTest {
    private fun image(
        width: Int,
        height: Int,
        format: String,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream()
        ImageIO.write(image, format, bytes)
        return bytes.toByteArray()
    }

    private fun decoded(png: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(png))

    private fun chunk(
        type: String,
        data: ByteArray,
    ): ByteArray {
        val body = type.toByteArray(Charsets.US_ASCII) + data
        val crc = CRC32().apply { update(body) }.value
        return ByteBuffer
            .allocate(8 + data.size + 4)
            .putInt(data.size)
            .put(body)
            .putInt(crc.toInt())
            .array()
    }

    /** Um PNG cujo cabeçalho declara [width] × [height], com um IDAT inútil: decodificar não é possível. */
    private fun pngDeclaring(
        width: Int,
        height: Int,
    ): ByteArray {
        val header =
            ByteBuffer
                .allocate(13)
                .putInt(width)
                .putInt(height)
                .put(8)
                .put(2)
                .put(0)
                .put(0)
                .put(0)
                .array()
        val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        return signature + chunk("IHDR", header) + chunk("IDAT", ByteArray(16)) + chunk("IEND", ByteArray(0))
    }

    @Test
    fun `PNG 4000x1000 vira PNG de 400x100`() {
        val result = DocumentLogo.prepare(image(4000, 1000, "png"), "image/png")!!

        assertThat(result.copyOfRange(1, 4)).isEqualTo("PNG".toByteArray())
        val out = decoded(result)
        assertThat(out.width).isEqualTo(400)
        assertThat(out.height).isEqualTo(100)
    }

    @Test
    fun `JPEG 300x300 vira PNG 300x300 sem ampliar`() {
        val result = DocumentLogo.prepare(image(300, 300, "jpeg"), "image/jpeg")!!

        assertThat(result.copyOfRange(1, 4)).isEqualTo("PNG".toByteArray())
        val out = decoded(result)
        assertThat(out.width).isEqualTo(300)
        assertThat(out.height).isEqualTo(300)
    }

    @Test
    fun `o tipo de midia aceita parametros e caixa alta`() {
        assertThat(DocumentLogo.prepare(image(10, 10, "png"), "IMAGE/PNG; charset=binary")).isNotNull()
    }

    @Test
    fun `WEBP e outro tipo viram null`() {
        val webp = "RIFF".toByteArray() + ByteArray(4) + "WEBPVP8 ".toByteArray() + ByteArray(32)

        assertThat(DocumentLogo.prepare(webp, "image/webp")).isNull()
        assertThat(DocumentLogo.prepare(image(10, 10, "png"), "image/gif")).isNull()
    }

    @Test
    fun `o tipo declarado diferente da assinatura vira null`() {
        assertThat(DocumentLogo.prepare(image(10, 10, "png"), "image/jpeg")).isNull()
        assertThat(DocumentLogo.prepare(image(10, 10, "jpeg"), "image/png")).isNull()
    }

    @Test
    fun `bytes aleatorios e PNG truncado viram null`() {
        val png = image(200, 200, "png")

        assertThat(DocumentLogo.prepare(ByteArray(500) { (it * 31).toByte() }, "image/png")).isNull()
        assertThat(DocumentLogo.prepare(png.copyOf(png.size / 2), "image/png")).isNull()
        assertThat(DocumentLogo.prepare(ByteArray(0), "image/png")).isNull()
    }

    @Test
    fun `cabecalho que declara 20000x20000 vira null sem decodificar`() {
        assertThat(DocumentLogo.prepare(pngDeclaring(20_000, 20_000), "image/png")).isNull()
    }

    @Test
    fun `acima de 2 MB vira null`() {
        val big = image(10, 10, "png") + ByteArray(2_097_152)

        assertThat(DocumentLogo.prepare(big, "image/png")).isNull()
    }
}
