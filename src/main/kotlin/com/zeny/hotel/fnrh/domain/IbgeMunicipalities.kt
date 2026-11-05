package com.zeny.hotel.fnrh.domain

/**
 * Os municípios do IBGE e a UF de cada um, lidos uma vez do recurso `fnrh/ibge-municipios.csv` (D-E07-3). O recurso é
 * uma cópia versionada: um município criado depois do download é desconhecido até a próxima atualização do arquivo.
 */
class IbgeMunicipalities {
    private val ufByCode: Map<Int, String> = load()

    /** A sigla da UF do município de código [code], ou `null` se o código não consta da lista. */
    fun ufOf(code: Int): String? = ufByCode[code]

    private fun load(): Map<Int, String> {
        val stream =
            requireNotNull(IbgeMunicipalities::class.java.getResourceAsStream(RESOURCE)) {
                "Recurso $RESOURCE ausente"
            }
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { line ->
                    val (code, uf) = line.split(";")
                    code.trim().toInt() to uf.trim()
                }
        }
    }

    private companion object {
        const val RESOURCE = "/fnrh/ibge-municipios.csv"
    }
}
