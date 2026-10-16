package com.zeny.hotel.files

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import javax.imageio.ImageIO

/**
 * Imagens de verdade para os testes do reencode (H11 do roadmap do M2): JPEG com um APP1 `Exif`
 * (orientação, descrição e um IFD de GPS), PNG com chunks de texto e `eXIf`, poliglotas e o
 * cabeçalho de uma imagem gigante.
 */
object TestImages {
    /** Texto gravado nos metadados das imagens de teste: não pode sobrar depois do reencode. */
    const val METADATA_MARKER = "ZENY-GPS-MARKER-23S46W"

    /** Um trecho de HTML com script, colado nos poliglotas. */
    const val POLYGLOT_PAYLOAD = "<html><script>alert('zeny')</script></html>"

    /**
     * Uma imagem RGB: a metade esquerda vermelha e a direita azul, para conferir giros e espelhos.
     */
    fun image(
        width: Int,
        height: Int,
        alpha: Boolean = false,
    ): BufferedImage {
        val image = BufferedImage(width, height, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = if (alpha) Color(255, 0, 0, 128) else Color.RED
        graphics.fillRect(0, 0, width / 2, height)
        graphics.color = Color.BLUE
        graphics.fillRect(width / 2, 0, width - width / 2, height)
        graphics.dispose()
        return image
    }

    /** Um JPEG de [width] × [height] e, se [exif], com o APP1 `Exif` (orientação e GPS) logo depois do SOI. */
    fun jpeg(
        width: Int,
        height: Int,
        exif: Boolean = true,
        orientation: Int = 1,
    ): ByteArray {
        val plain = write(image(width, height), "jpeg")
        if (!exif) return plain
        return plain.copyOfRange(0, 2) + segment(APP1, exifBody(orientation)) + plain.copyOfRange(2, plain.size)
    }

    /** Um JPEG com um segmento de comentário (COM) com HTML e o mesmo HTML depois do fim da imagem. */
    fun polyglotJpeg(): ByteArray {
        val plain = write(image(64, 48), "jpeg")
        val comment = segment(COM, POLYGLOT_PAYLOAD.toByteArray(Charsets.US_ASCII))
        return plain.copyOfRange(0, 2) + comment + plain.copyOfRange(2, plain.size) +
            POLYGLOT_PAYLOAD.toByteArray(Charsets.US_ASCII)
    }

    /**
     * Um PNG com chunks `tEXt` e `eXIf` (com o [METADATA_MARKER]) logo depois do IHDR e, se
     * [trailing], HTML depois do IEND (poliglota).
     */
    fun png(
        width: Int,
        height: Int,
        alpha: Boolean = false,
        trailing: Boolean = false,
    ): ByteArray {
        val plain = write(image(width, height, alpha), "png")
        val afterHeader = PNG_SIGNATURE_SIZE + CHUNK_OVERHEAD + IHDR_SIZE
        val text = chunk("tEXt", "Comment".toByteArray() + byteArrayOf(0) + "GPS $METADATA_MARKER".toByteArray())
        val exif = chunk("eXIf", tiff(orientation = 1))
        val tail = if (trailing) POLYGLOT_PAYLOAD.toByteArray(Charsets.US_ASCII) else ByteArray(0)
        return plain.copyOfRange(0, afterHeader) + text + exif + plain.copyOfRange(afterHeader, plain.size) + tail
    }

    /** Só a assinatura e o IHDR de um PNG de [width] × [height] (sem dados): o teto antes de decodificar. */
    fun pngHeader(
        width: Int,
        height: Int,
    ): ByteArray {
        val ihdr =
            ByteBuffer
                .allocate(IHDR_SIZE)
                .putInt(width)
                .putInt(height)
                .put(8) // profundidade
                .put(2) // RGB
                .put(0)
                .put(0)
                .put(0)
                .array()
        return PNG_SIGNATURE + chunk("IHDR", ihdr) + chunk("IDAT", ByteArray(16)) + chunk("IEND", ByteArray(0))
    }

    fun read(content: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(content))

    /** Os marcadores dos segmentos de um JPEG, do SOI até o SOS. */
    fun jpegMarkers(content: ByteArray): List<Int> {
        val markers = mutableListOf<Int>()
        var position = 2
        while (position + 4 <= content.size) {
            val marker = content[position + 1].toInt() and 0xFF
            markers += marker
            if (marker == SOS) break
            position +=
                2 + (((content[position + 2].toInt() and 0xFF) shl 8) or (content[position + 3].toInt() and 0xFF))
        }
        return markers
    }

    /** Os tipos dos chunks de um PNG, em ordem, e se sobra algo depois do IEND. */
    fun pngChunks(content: ByteArray): Pair<List<String>, Boolean> {
        val chunks = mutableListOf<String>()
        var position = PNG_SIGNATURE_SIZE
        while (position + CHUNK_OVERHEAD <= content.size) {
            val length = ByteBuffer.wrap(content, position, 4).int
            val type = String(content, position + 4, 4, Charsets.US_ASCII)
            chunks += type
            position += CHUNK_OVERHEAD + length
            if (type == "IEND") break
        }
        return chunks to (position < content.size)
    }

    private fun write(
        image: BufferedImage,
        format: String,
    ): ByteArray = ByteArrayOutputStream().also { check(ImageIO.write(image, format, it)) }.toByteArray()

    private fun segment(
        marker: Int,
        body: ByteArray,
    ): ByteArray {
        val length = body.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + body
    }

    private fun exifBody(orientation: Int): ByteArray = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff(orientation)

    /**
     * Um bloco TIFF big-endian com o IFD0 (Orientation, ImageDescription com o [METADATA_MARKER] e o
     * ponteiro do GPS) e um IFD de GPS (latitude 23° 32' S).
     */
    private fun tiff(orientation: Int): ByteArray {
        val description = (METADATA_MARKER + "\u0000").toByteArray(Charsets.US_ASCII)
        val ifd0Offset = 8
        val ifd0Size = 2 + 3 * 12 + 4
        val descriptionOffset = ifd0Offset + ifd0Size
        val gpsOffset = descriptionOffset + description.size + (description.size % 2)
        val gpsSize = 2 + 2 * 12 + 4
        val rationalsOffset = gpsOffset + gpsSize
        val buffer = ByteBuffer.allocate(rationalsOffset + 3 * 8)
        buffer.put("MM".toByteArray()).putShort(42).putInt(ifd0Offset)
        buffer.putShort(3)
        entry(buffer, 0x010E, 2, description.size, descriptionOffset) // ImageDescription
        buffer
            .putShort(0x0112.toShort())
            .putShort(3)
            .putInt(1)
            .putShort(orientation.toShort())
            .putShort(0)
        entry(buffer, 0x8825, 4, 1, gpsOffset) // GPSInfo
        buffer.putInt(0)
        buffer.put(description)
        if (description.size % 2 != 0) buffer.put(0)
        buffer.putShort(2)
        buffer
            .putShort(0x0001)
            .putShort(2)
            .putInt(2)
            .put('S'.code.toByte())
            .put(0)
            .putShort(0) // GPSLatitudeRef
        entry(buffer, 0x0002, 5, 3, rationalsOffset) // GPSLatitude
        buffer.putInt(0)
        listOf(23, 32, 0).forEach { buffer.putInt(it).putInt(1) }
        return buffer.array()
    }

    private fun entry(
        buffer: ByteBuffer,
        tag: Int,
        type: Int,
        count: Int,
        value: Int,
    ) {
        buffer
            .putShort(tag.toShort())
            .putShort(type.toShort())
            .putInt(count)
            .putInt(value)
    }

    private fun chunk(
        type: String,
        data: ByteArray,
    ): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(typeBytes + data) }.value
        return ByteBuffer
            .allocate(CHUNK_OVERHEAD + data.size)
            .putInt(data.size)
            .put(typeBytes)
            .put(data)
            .putInt(crc.toInt())
            .array()
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val PNG_SIGNATURE_SIZE = 8
    private const val CHUNK_OVERHEAD = 12
    private const val IHDR_SIZE = 13
    private const val APP1 = 0xE1
    private const val COM = 0xFE
    private const val SOS = 0xDA
}
