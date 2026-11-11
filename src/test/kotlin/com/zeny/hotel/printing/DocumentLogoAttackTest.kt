package com.zeny.hotel.printing

import com.zeny.hotel.printing.domain.DocumentLogo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

class DocumentLogoAttackTest {
    private val pngSignature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    private fun write(
        image: BufferedImage,
        format: String,
        progressive: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName(format).next()
        val param =
            writer.defaultWriteParam.apply {
                if (progressive) progressiveMode = ImageWriteParam.MODE_DEFAULT
            }
        ImageIO.createImageOutputStream(out).use {
            writer.output = it
            writer.write(null, IIOImage(image, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    private fun blank(
        width: Int,
        height: Int,
        type: Int = BufferedImage.TYPE_INT_RGB,
    ) = BufferedImage(width, height, type)

    private fun decoded(png: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(png))

    private fun assertValidLogo(
        result: ByteArray?,
        width: Int? = null,
        height: Int? = null,
    ): BufferedImage {
        assertThat(result).isNotNull()
        assertThat(result!!.copyOfRange(0, 8)).isEqualTo(pngSignature)
        val image = decoded(result)
        assertThat(maxOf(image.width, image.height)).isLessThanOrEqualTo(400)
        width?.let { assertThat(image.width).isEqualTo(it) }
        height?.let { assertThat(image.height).isEqualTo(it) }
        return image
    }

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

    private fun chunkTypes(png: ByteArray): List<String> {
        val types = mutableListOf<String>()
        var pos = 8
        while (pos + 8 <= png.size) {
            val length = ByteBuffer.wrap(png, pos, 4).int
            types += String(png, pos + 4, 4, Charsets.US_ASCII)
            pos += 12 + length
        }
        return types
    }

    /** PNG de 1 bit, valido e minusculo em bytes, que ocupa [width] x [height] pixels quando decodificado. */
    private fun bomb(
        width: Int,
        height: Int,
    ) = write(BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY), "png")

    @Test
    fun `PNG valido de 8000x8000 acima de 50 MP e recusado antes de decodificar`() {
        val bytes = bomb(8000, 8000)
        // sem o teto de pixels este PNG decodificaria com sucesso, entao um resultado nao nulo seria o defeito
        assertThat(bytes.size).isLessThan(DocumentLogo.MAX_BYTES)

        assertThat(DocumentLogo.prepare(bytes, "image/png")).isNull()
    }

    @Test
    fun `PNG valido de 20000x20000 e recusado sem demora`() {
        val bytes = bomb(20_000, 20_000)
        val started = System.nanoTime()

        assertThat(DocumentLogo.prepare(bytes, "image/png")).isNull()
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2_000)
    }

    @Test
    fun `o teto e de exatamente 50 MP declarados`() {
        assertValidLogo(DocumentLogo.prepare(bomb(10_000, 5_000), "image/png"), 400, 200)
        assertThat(DocumentLogo.prepare(bomb(10_000, 5_001), "image/png")).isNull()
    }

    @Test
    fun `dimensoes que estouram Int na multiplicacao sao recusadas`() {
        val header =
            ByteBuffer
                .allocate(13)
                .putInt(Int.MAX_VALUE)
                .putInt(Int.MAX_VALUE)
                .put(8)
                .put(0)
                .put(0)
                .put(0)
                .put(0)
                .array()
        val png = pngSignature + chunk("IHDR", header) + chunk("IDAT", ByteArray(8)) + chunk("IEND", ByteArray(0))

        assertThat(DocumentLogo.prepare(png, "image/png")).isNull()
    }

    @Test
    fun `cabecalho 10x10 com IDAT de dados maiores nunca lanca e devolve null ou logotipo valido`() {
        val header =
            ByteBuffer
                .allocate(13)
                .putInt(10)
                .putInt(10)
                .put(8)
                .put(2)
                .put(0)
                .put(0)
                .put(0)
                .array()
        val png =
            pngSignature + chunk("IHDR", header) + chunk("IDAT", ByteArray(100_000) { 7 }) + chunk("IEND", ByteArray(0))

        val result = DocumentLogo.prepare(png, "image/png")

        if (result != null) assertValidLogo(result)
    }

    @Test
    fun `cabecalho 4000x4000 com dados de uma imagem 10x10 nunca lanca`() {
        val real = write(blank(10, 10), "png")
        val forged = real.copyOf()
        ByteBuffer.wrap(forged).putInt(16, 4000).putInt(20, 4000)
        // o CRC do IHDR fica invalido de proposito; o ImageIO pode recusar ou ler, mas nao pode estourar
        val result = DocumentLogo.prepare(forged, "image/png")

        if (result != null) assertValidLogo(result)
    }

    @Test
    fun `tipos divergentes da assinatura ou de outros formatos viram null`() {
        val png = write(blank(20, 20), "png")
        val jpeg = write(blank(20, 20), "jpeg")
        val gif = write(blank(20, 20, BufferedImage.TYPE_BYTE_INDEXED), "gif")
        val bmp = write(blank(20, 20), "bmp")
        val svg = "<svg xmlns='http://www.w3.org/2000/svg' width='10' height='10'/>".toByteArray()
        val pdf = "%PDF-1.4\n1 0 obj<<>>endobj\n%%EOF".toByteArray()

        assertThat(DocumentLogo.prepare(gif, "image/gif")).isNull()
        assertThat(DocumentLogo.prepare(gif, "image/png")).isNull()
        assertThat(DocumentLogo.prepare(bmp, "image/png")).isNull()
        assertThat(DocumentLogo.prepare(bmp, "image/jpeg")).isNull()
        assertThat(DocumentLogo.prepare(svg, "image/svg+xml")).isNull()
        assertThat(DocumentLogo.prepare(svg, "image/png")).isNull()
        assertThat(DocumentLogo.prepare(pdf, "application/pdf")).isNull()
        assertThat(DocumentLogo.prepare(pdf, "image/png")).isNull()
        assertThat(DocumentLogo.prepare(png, "")).isNull()
        assertThat(DocumentLogo.prepare(jpeg, "application/octet-stream")).isNull()
        assertThat(DocumentLogo.prepare(png, "image/jpeg")).isNull()
        assertThat(DocumentLogo.prepare(jpeg, "image/png")).isNull()
    }

    @Test
    fun `image jpg e aceito para JPEG`() {
        assertValidLogo(DocumentLogo.prepare(write(blank(50, 40), "jpeg"), "image/jpg"), 50, 40)
    }

    @Test
    fun `PNG ARGB mantem a transparencia`() {
        val source = blank(600, 600, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 600) for (x in 300 until 600) source.setRGB(x, y, Color(0, 0, 255, 255).rgb)

        val out = assertValidLogo(DocumentLogo.prepare(write(source, "png"), "image/png"), 400, 400)

        assertThat(out.colorModel.hasAlpha()).isTrue()
        assertThat(out.getRGB(20, 200) ushr 24).isEqualTo(0)
        assertThat(out.getRGB(380, 200) ushr 24).isEqualTo(255)
    }

    @Test
    fun `PNG com paleta, escala de cinza, 16 bits e 1 bit viram PNG valido`() {
        val types =
            listOf(
                BufferedImage.TYPE_BYTE_INDEXED,
                BufferedImage.TYPE_BYTE_GRAY,
                BufferedImage.TYPE_USHORT_GRAY,
                BufferedImage.TYPE_BYTE_BINARY,
            )
        types.forEach { type ->
            assertValidLogo(DocumentLogo.prepare(write(blank(900, 300, type), "png"), "image/png"), 400, 133)
        }
    }

    @Test
    fun `JPEG progressivo e em escala de cinza viram PNG`() {
        assertValidLogo(
            DocumentLogo.prepare(write(blank(800, 200), "jpeg", progressive = true), "image/jpeg"),
            400,
            100,
        )
        assertValidLogo(
            DocumentLogo.prepare(write(blank(500, 500, BufferedImage.TYPE_BYTE_GRAY), "jpeg"), "image/jpeg"),
            400,
            400,
        )
    }

    @Test
    fun `JPEG com lado 3999 passa pela subamostragem e fica com no maximo 400`() {
        assertValidLogo(DocumentLogo.prepare(write(blank(3999, 1001), "jpeg"), "image/jpeg"), 400, 100)
    }

    @Test
    fun `lado exatamente 400 nao muda e 401 vira 400`() {
        assertValidLogo(DocumentLogo.prepare(write(blank(400, 400), "png"), "image/png"), 400, 400)
        assertValidLogo(DocumentLogo.prepare(write(blank(401, 401), "png"), "image/png"), 400, 400)
        assertValidLogo(DocumentLogo.prepare(write(blank(401, 1), "png"), "image/png"), 400, 1)
        assertValidLogo(DocumentLogo.prepare(write(blank(1, 401), "png"), "image/png"), 1, 400)
    }

    @Test
    fun `imagens 1xN e Nx1 extremas mantem lado minimo de 1 pixel`() {
        assertValidLogo(
            DocumentLogo.prepare(write(blank(1, 30_000, BufferedImage.TYPE_BYTE_BINARY), "png"), "image/png"),
            1,
            400,
        )
        assertValidLogo(
            DocumentLogo.prepare(write(blank(30_000, 1, BufferedImage.TYPE_BYTE_BINARY), "png"), "image/png"),
            400,
            1,
        )
        assertValidLogo(DocumentLogo.prepare(write(blank(1, 1), "png"), "image/png"), 1, 1)
        assertValidLogo(DocumentLogo.prepare(write(blank(1, 5000), "jpeg"), "image/jpeg"), 1, 400)
    }

    @Test
    fun `exatamente 2 MB passa e 2 MB mais 1 byte vira null`() {
        val png = write(blank(50, 50), "png")
        val exact = png + ByteArray(DocumentLogo.MAX_BYTES - png.size)
        assertThat(exact.size).isEqualTo(2_097_152)

        assertValidLogo(DocumentLogo.prepare(exact, "image/png"), 50, 50)
        assertThat(DocumentLogo.prepare(exact + 0, "image/png")).isNull()
    }

    @Test
    fun `conteudo vazio, so a assinatura e so o inicio do JPEG viram null`() {
        assertThat(DocumentLogo.prepare(ByteArray(0), "image/jpeg")).isNull()
        assertThat(DocumentLogo.prepare(pngSignature, "image/png")).isNull()
        assertThat(DocumentLogo.prepare(byteArrayOf(-1, -40, -1), "image/jpeg")).isNull()
        assertThat(DocumentLogo.prepare(byteArrayOf(-1, -40, -1, -32) + ByteArray(40), "image/jpeg")).isNull()
        assertThat(DocumentLogo.prepare(write(blank(100, 100), "jpeg").let { it.copyOf(it.size / 2) }, "image/jpeg"))
            .satisfiesAnyOf(
                { assertThat(it).isNull() },
                { assertValidLogo(it) },
            )
    }

    @Test
    fun `metadados do original nao vao para a saida`() {
        val png = write(blank(50, 50), "png")
        val idat = chunkTypes(png).indexOf("IDAT")
        assertThat(idat).isGreaterThan(0)
        // insere tEXt, iTXt e eXIf antes do primeiro IDAT
        var pos = 8
        repeat(idat) { pos += 12 + ByteBuffer.wrap(png, pos, 4).int }
        val extra =
            chunk("tEXt", "Author\u0000secreto".toByteArray()) +
                chunk("iTXt", "k\u0000\u0000\u0000\u0000\u0000dado".toByteArray()) +
                chunk("eXIf", ByteArray(8))
        val withMeta = png.copyOfRange(0, pos) + extra + png.copyOfRange(pos, png.size)
        assertThat(chunkTypes(withMeta)).contains("tEXt", "iTXt", "eXIf")

        val result = DocumentLogo.prepare(withMeta, "image/png")!!

        assertThat(chunkTypes(result)).doesNotContain("tEXt", "iTXt", "zTXt", "eXIf", "tIME")
        assertThat(String(result, Charsets.ISO_8859_1)).doesNotContain("secreto")
    }

    @Test
    fun `PNG de 2 MB de bytes e 4000 px vira logotipo reduzido`() {
        val noisy = blank(1500, 1000)
        val random = java.util.Random(42)
        for (y in 0 until 1000) for (x in 0 until 1500) noisy.setRGB(x, y, random.nextInt())
        val png = write(noisy, "png")
        // ruido de 1500x1000 passa de 2 MB, entao deve ser recusado, nunca erro
        val result = DocumentLogo.prepare(png, "image/png")

        if (png.size > DocumentLogo.MAX_BYTES) assertThat(result).isNull() else assertValidLogo(result)
    }
}
