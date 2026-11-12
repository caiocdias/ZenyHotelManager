package com.zeny.hotel.printing.domain

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.roundToInt

/**
 * O logotipo do hotel reduzido para o cabeçalho dos documentos (D-E11-7): só PNG e JPEG (o JDK não
 * lê WEBP), até [MAX_BYTES], lado maior de no máximo [MAX_SIDE_PIXELS] (o menor não é ampliado),
 * regravado em PNG. O tipo vale pela assinatura dos bytes, não só pelo parâmetro. Como o conteúdo
 * é enviado por usuário, o cabeçalho é lido antes e imagem acima de [MAX_SOURCE_PIXELS] não é
 * decodificada. Qualquer problema é `null` (documento sem logotipo), nunca exceção.
 */
object DocumentLogo {
    const val MAX_BYTES = 2_097_152
    const val MAX_SIDE_PIXELS = 400
    const val MAX_SOURCE_PIXELS: Long = 50_000_000

    private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private val JPEG_SIGNATURE = byteArrayOf(-1, -40, -1)

    @Suppress("TooGenericExceptionCaught") // os leitores do JDK lançam exceções variadas em dado malformado
    fun prepare(
        content: ByteArray,
        contentType: String,
    ): ByteArray? {
        val format = if (content.size > MAX_BYTES) null else formatOf(content, contentType)
        return format?.let {
            try {
                decode(content, it)?.let { image -> png(shrink(image)) }
            } catch (_: IOException) {
                null
            } catch (_: RuntimeException) {
                null
            }
        }
    }

    /** O formato do `ImageIO` quando o tipo declarado e a assinatura dos bytes concordam; senão `null`. */
    private fun formatOf(
        content: ByteArray,
        contentType: String,
    ): String? {
        val mediaType = contentType.substringBefore(';').trim().lowercase()
        return when {
            mediaType == "image/png" && content.startsWith(PNG_SIGNATURE) -> "png"
            mediaType in JPEG_TYPES && content.startsWith(JPEG_SIGNATURE) -> "jpeg"
            else -> null
        }
    }

    private fun decode(
        content: ByteArray,
        format: String,
    ): BufferedImage? {
        val reader = ImageIO.getImageReadersByFormatName(format).asSequence().firstOrNull()
        return try {
            reader?.setInput(MemoryCacheImageInputStream(ByteArrayInputStream(content)), true, true)
            val width = reader?.getWidth(0) ?: 0
            val height = reader?.getHeight(0) ?: 0
            if (reader == null || width.toLong() * height !in 1..MAX_SOURCE_PIXELS) {
                null
            } else {
                val factor = maxOf(1, maxOf(width, height) / (2 * MAX_SIDE_PIXELS))
                val param = reader.defaultReadParam.apply { if (factor > 1) setSourceSubsampling(factor, factor, 0, 0) }
                reader.read(0, param)
            }
        } finally {
            reader?.dispose()
        }
    }

    private fun shrink(source: BufferedImage): BufferedImage {
        val scale = minOf(1.0, MAX_SIDE_PIXELS.toDouble() / maxOf(source.width, source.height))
        val width = maxOf(1, (source.width * scale).roundToInt())
        val height = maxOf(1, (source.height * scale).roundToInt())
        val type = if (source.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val image = BufferedImage(width, height, type)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun png(image: BufferedImage): ByteArray? {
        val writer = ImageIO.getImageWritersByFormatName("png").asSequence().firstOrNull() ?: return null
        val bytes = ByteArrayOutputStream()
        try {
            // Cache em memória: o cache padrão do ImageIO pode gravar arquivo temporário.
            MemoryCacheImageOutputStream(bytes).use { output ->
                writer.output = output
                writer.write(null, IIOImage(image, null, null), null)
            }
        } finally {
            writer.dispose()
        }
        return bytes.toByteArray()
    }

    private fun ByteArray.startsWith(prefix: ByteArray) =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private val JPEG_TYPES = setOf("image/jpeg", "image/jpg")
}
