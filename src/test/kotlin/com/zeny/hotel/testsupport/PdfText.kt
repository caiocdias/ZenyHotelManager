package com.zeny.hotel.testsupport

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper

/** O texto de um PDF, para os testes dos documentos do `printing` e dos donos (E11). */
fun pdfText(bytes: ByteArray): String = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }

/** O número de páginas de um PDF. */
fun pdfPageCount(bytes: ByteArray): Int = Loader.loadPDF(bytes).use { it.numberOfPages }

/** O número de imagens de um PDF (XObjects de imagem, inclusive dentro de formulários). */
fun pdfImageCount(bytes: ByteArray): Int =
    Loader.loadPDF(bytes).use { document -> document.pages.sumOf { imageCount(it.resources) } }

private fun imageCount(resources: PDResources?): Int =
    resources
        ?.xObjectNames
        ?.sumOf { name ->
            when (val xObject = resources.getXObject(name)) {
                is PDImageXObject -> 1
                is PDFormXObject -> imageCount(xObject.resources)
                else -> 0
            }
        } ?: 0
