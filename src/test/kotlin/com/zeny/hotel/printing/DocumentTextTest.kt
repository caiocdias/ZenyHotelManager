package com.zeny.hotel.printing

import com.zeny.hotel.printing.domain.DocumentText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DocumentTextTest {
    private val rlo = 0x202E.toChar()
    private val zeroWidth = 0x200B.toChar()
    private val esc = 0x1B.toChar()
    private val gs = 0x1D.toChar()

    @Test
    fun `remove controle e formatacao e colapsa espacos`() {
        val dirty = "Jo${rlo}ão$zeroWidth\nSilva\t$esc$gs"

        assertThat(DocumentText.clean(dirty)).isEqualTo("João Silva")
    }

    @Test
    fun `colapsa espacos repetidos e apara as pontas`() {
        assertThat(DocumentText.clean("  a   b   c  ")).isEqualTo("a b c")
    }

    @Test
    fun `winansi mantem o que o windows 1252 codifica e troca o resto por interrogacao`() {
        assertThat(DocumentText.toWinAnsi("Łukasz Ñandú 李")).isEqualTo("?ukasz Ñandú ?")
    }

    @Test
    fun `winansi troca um par substituto por uma so interrogacao`() {
        assertThat(DocumentText.toWinAnsi("a😀b")).isEqualTo("a?b")
    }
}
