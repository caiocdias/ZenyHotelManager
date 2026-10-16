package com.zeny.hotel.files.domain

import com.zeny.hotel.files.FileType
import com.zeny.hotel.files.FilesErrorCodes
import com.zeny.hotel.files.ImageReencoding
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Reencode de imagens das finalidades marcadas com [ImageReencoding] (H11 do roadmap do M2), só com
 * o `javax.imageio` do JDK:
 *
 * 1. lê só o cabeçalho e recusa, antes de decodificar, imagem acima de [MAX_SOURCE_PIXELS]
 *    (`400 FILE_IMAGE_TOO_LARGE`): o teto da memória. Conteúdo que o leitor do tipo detectado não
 *    lê (corrompido, JPEG CMYK) é `400 FILE_IMAGE_UNREADABLE`;
 * 2. decodifica ignorando os metadados e, se a imagem tem o lado maior que o dobro do máximo ou
 *    mais, já subamostrada (`setSourceSubsampling`), para o raster decodificado ficar abaixo de
 *    `(2 × lado máximo)²` pixels;
 * 3. redesenha numa imagem nova (RGB, ou ARGB num PNG com transparência), reduzida até o lado
 *    máximo e com a orientação EXIF do JPEG aplicada aos pixels;
 * 4. grava no mesmo tipo (JPEG com qualidade [JPEG_QUALITY], PNG sem perda) **sem metadado
 *    nenhum**: o arquivo de saída só tem o que o codificador do JDK escreve (no JPEG, o cabeçalho
 *    JFIF; nenhum APP1/EXIF/XMP, comentário ou miniatura; no PNG, nenhum chunk de texto, `eXIf`
 *    ou `iCCP`). Bytes depois do fim da imagem (poliglotas) não sobrevivem.
 *
 * Os fluxos são em memória (`MemoryCache…`), sem arquivo temporário. Memória: além do conteúdo
 * recebido (até o tamanho máximo da finalidade), o raster decodificado (3 a 4 bytes por pixel;
 * uma foto de 12 MP, 4000 × 3000, ocupa uns 36 MB) e a imagem final (lado máximo 2048: até 16 MB).
 */
object ImageReencoder {
    /** Teto de pixels da imagem recebida (50 MP), conferido no cabeçalho, antes de decodificar. */
    const val MAX_SOURCE_PIXELS: Long = 50_000_000

    /** Qualidade da regravação em JPEG (0 a 1). */
    const val JPEG_QUALITY = 0.85f

    private const val FIELD = "file"

    /**
     * O conteúdo [content], já detectado como [type] (JPEG ou PNG), regravado no mesmo tipo, sem
     * metadados e com o lado maior de no máximo [maxSidePixels].
     */
    fun reencode(
        content: ByteArray,
        type: FileType,
        maxSidePixels: Int,
    ): ByteArray {
        require(type in ImageReencoding.REENCODABLE_TYPES) { "O reencode não lê $type" }
        val decoded = decode(content, type, maxSidePixels)
        val orientation = if (type == FileType.JPEG) ExifOrientation.of(content) else ExifOrientation.NORMAL
        val keepAlpha = type == FileType.PNG && decoded.colorModel.hasAlpha()
        return encode(render(decoded, maxSidePixels, orientation, keepAlpha), type)
    }

    private fun decode(
        content: ByteArray,
        type: FileType,
        maxSidePixels: Int,
    ): BufferedImage {
        val reader = ImageIO.getImageReadersByFormatName(formatName(type)).asSequence().first()
        try {
            reader.setInput(MemoryCacheImageInputStream(ByteArrayInputStream(content)), true, true)
            val (width, height) = readable { reader.getWidth(0) to reader.getHeight(0) }
            if (width <= 0 || height <= 0) unreadable()
            if (width.toLong() * height > MAX_SOURCE_PIXELS) {
                reject(
                    FilesErrorCodes.FILE_IMAGE_TOO_LARGE,
                    "A imagem passa de ${MAX_SOURCE_PIXELS / MEGAPIXEL} megapixels.",
                )
            }
            val factor = maxOf(1, maxOf(width, height) / maxSidePixels)
            return readable { reader.read(0, subsampled(reader, factor)) }
        } finally {
            reader.dispose()
        }
    }

    private fun subsampled(
        reader: ImageReader,
        factor: Int,
    ) = reader.defaultReadParam.apply { if (factor > 1) setSourceSubsampling(factor, factor, 0, 0) }

    private fun render(
        source: BufferedImage,
        maxSidePixels: Int,
        orientation: Int,
        keepAlpha: Boolean,
    ): BufferedImage {
        val scale = minOf(1.0, maxSidePixels.toDouble() / maxOf(source.width, source.height))
        val width = maxOf(1, (source.width * scale).roundToInt())
        val height = maxOf(1, (source.height * scale).roundToInt())
        val swapped = orientation in ExifOrientation.SWAPPED
        val image =
            BufferedImage(
                if (swapped) height else width,
                if (swapped) width else height,
                if (keepAlpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB,
            )
        val graphics = image.createGraphics()
        try {
            if (!keepAlpha) {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, image.width, image.height)
            }
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.transform(ExifOrientation.transform(orientation, width, height))
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun encode(
        image: BufferedImage,
        type: FileType,
    ): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName(formatName(type)).next()
        val bytes = ByteArrayOutputStream()
        try {
            MemoryCacheImageOutputStream(bytes).use { output ->
                writer.output = output
                val param = writer.defaultWriteParam
                if (type == FileType.JPEG) {
                    param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    param.compressionQuality = JPEG_QUALITY
                }
                // Sem metadados de fluxo nem de imagem: o codificador escreve só o necessário.
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return bytes.toByteArray()
    }

    private fun formatName(type: FileType) = if (type == FileType.JPEG) "jpeg" else "png"

    /**
     * Executa uma leitura do `javax.imageio`; as falhas dele (`IIOException`, e as de tempo de
     * execução que os leitores lançam diante de dados malformados) viram `FILE_IMAGE_UNREADABLE`.
     */
    @Suppress("TooGenericExceptionCaught") // os leitores do JDK lançam exceções variadas em dado malformado
    private fun <T> readable(read: () -> T): T =
        try {
            read()
        } catch (_: IOException) {
            unreadable()
        } catch (_: RuntimeException) {
            unreadable()
        }

    private fun unreadable(): Nothing =
        reject(FilesErrorCodes.FILE_IMAGE_UNREADABLE, "O conteúdo do arquivo não pôde ser lido como imagem.")

    private fun reject(
        code: ErrorCode,
        message: String,
    ): Nothing = throw InvalidInputException(code, message, listOf(FieldViolation(FIELD, code.value, message)))

    private const val MEGAPIXEL = 1_000_000
}

/**
 * A orientação EXIF (tag `0x0112` do IFD0 no segmento APP1 `Exif`) de um JPEG, de 1 a 8, e a
 * transformação que a aplica aos pixels. Lida direto dos bytes, porque o reencode decodifica sem
 * metadados; qualquer coisa fora do formato esperado é a orientação normal (1).
 */
internal object ExifOrientation {
    const val NORMAL = 1

    /** As orientações que trocam largura e altura (5 a 8: com giro de 90°). */
    val SWAPPED = 5..8

    private const val ORIENTATIONS = 8
    private const val MARKER = 0xFF
    private const val APP1 = 0xE1
    private const val SOS = 0xDA
    private const val EOI = 0xD9
    private const val TEM = 0x01
    private val RESTART = 0xD0..0xD7
    private const val SEGMENT_HEADER = 4
    private const val ORIENTATION_TAG = 0x0112
    private const val SHORT_TYPE = 3
    private const val TIFF_MAGIC = 42
    private const val IFD_ENTRY_SIZE = 12
    private const val ENTRY_VALUE_OFFSET = 8
    private const val ENTRY_TYPE_OFFSET = 2
    private const val IFD_OFFSET_POSITION = 4
    private const val U16_BYTES = 2
    private const val U32_BYTES = 4
    private const val BYTE_MASK = 0xFF
    private const val BYTE_BITS = 8
    private val EXIF_HEADER =
        byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)

    fun of(jpeg: ByteArray): Int = runCatching { find(jpeg) }.getOrNull()?.takeIf { it in 1..ORIENTATIONS } ?: NORMAL

    /**
     * A transformação que leva a imagem armazenada ([width] × [height]) à exibida: espelhos e giros
     * de 90°, 180° e 270° (EXIF 2.32, tag Orientation).
     */
    @Suppress("MagicNumber") // os oito casos da tag Orientation
    fun transform(
        orientation: Int,
        width: Int,
        height: Int,
    ): AffineTransform {
        val w = width.toDouble()
        val h = height.toDouble()
        return AffineTransform().apply {
            when (orientation) {
                2 -> {
                    scale(-1.0, 1.0)
                    translate(-w, 0.0)
                }

                3 -> {
                    translate(w, h)
                    rotate(PI)
                }

                4 -> {
                    scale(1.0, -1.0)
                    translate(0.0, -h)
                }

                5 -> {
                    rotate(-PI / 2)
                    scale(-1.0, 1.0)
                }

                6 -> {
                    translate(h, 0.0)
                    rotate(PI / 2)
                }

                7 -> {
                    scale(-1.0, 1.0)
                    translate(-h, w)
                    rotate(3 * PI / 2)
                }

                8 -> {
                    translate(0.0, w)
                    rotate(3 * PI / 2)
                }
            }
        }
    }

    /** Percorre os segmentos do JPEG até o início da imagem (SOS), atrás do APP1 `Exif`. */
    private fun find(bytes: ByteArray): Int? {
        var position = 2 // depois do SOI
        var orientation: Int? = null
        while (orientation == null && position + SEGMENT_HEADER <= bytes.size) {
            require(bytes.u8(position) == MARKER) { "Fora de um marcador" }
            val marker = bytes.u8(position + 1)
            when {
                marker == MARKER -> {
                    position++
                }

                marker == SOS || marker == EOI -> {
                    return null
                }

                marker == TEM || marker in RESTART -> {
                    position += 2
                }

                else -> {
                    val end = position + 2 + bytes.u16(position + 2, littleEndian = false)
                    require(end <= bytes.size) { "Segmento além do fim" }
                    val start = position + SEGMENT_HEADER
                    if (marker == APP1 && bytes.startsWithAt(EXIF_HEADER, start)) {
                        orientation = tiffOrientation(bytes.copyOfRange(start + EXIF_HEADER.size, end))
                    }
                    position = end
                }
            }
        }
        return orientation
    }

    /** A orientação no IFD0 de um bloco TIFF (o corpo do APP1 depois de `Exif\0\0`). */
    private fun tiffOrientation(tiff: ByteArray): Int? {
        val littleEndian =
            when (String(tiff, 0, 2, Charsets.US_ASCII)) {
                "II" -> true
                "MM" -> false
                else -> return null
            }
        require(tiff.u16(2, littleEndian) == TIFF_MAGIC) { "TIFF sem a marca 42" }
        val ifd = tiff.u32(IFD_OFFSET_POSITION, littleEndian)
        val entries = tiff.u16(ifd, littleEndian)
        return (0 until entries)
            .map { ifd + U16_BYTES + it * IFD_ENTRY_SIZE }
            .firstOrNull { entry ->
                tiff.u16(entry, littleEndian) == ORIENTATION_TAG &&
                    tiff.u16(entry + ENTRY_TYPE_OFFSET, littleEndian) == SHORT_TYPE
            }?.let { tiff.u16(it + ENTRY_VALUE_OFFSET, littleEndian) }
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and BYTE_MASK

    private fun ByteArray.u16(
        index: Int,
        littleEndian: Boolean,
    ): Int {
        require(index >= 0 && index + U16_BYTES <= size) { "Fora do bloco" }
        val first = u8(index)
        val second = u8(index + 1)
        return if (littleEndian) (second shl BYTE_BITS) or first else (first shl BYTE_BITS) or second
    }

    private fun ByteArray.u32(
        index: Int,
        littleEndian: Boolean,
    ): Int {
        require(index >= 0 && index + U32_BYTES <= size) { "Fora do bloco" }
        val high = u16(if (littleEndian) index + U16_BYTES else index, littleEndian)
        val low = u16(if (littleEndian) index else index + U16_BYTES, littleEndian)
        val value = (high.toLong() shl (2 * BYTE_BITS)) or low.toLong()
        require(value <= Int.MAX_VALUE) { "Deslocamento grande demais" }
        return value.toInt()
    }

    private fun ByteArray.startsWithAt(
        prefix: ByteArray,
        offset: Int,
    ): Boolean = offset + prefix.size <= size && prefix.indices.all { this[offset + it] == prefix[it] }
}
