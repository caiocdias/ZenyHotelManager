package com.zeny.hotel.guests.domain

import com.zeny.hotel.guests.GuestsErrorCodes
import com.zeny.hotel.guests.RaceColor
import java.util.Objects

/**
 * Os dados sensíveis da FNRH (RN-19, SPEC §8.2.4): raça/cor e deficiência. Coletados para envio, gravados
 * cifrados e lidos só pela integração FNRH; nunca voltam pela API, pela trilha nem pelo log ([toString] não
 * os mostra). [disability] é o código do domínio oficial da FNRH (letras maiúsculas, dígitos e `_`, até
 * 60), conferido contra o domínio no envio (RF-FNRH-06, M5).
 *
 * Os dois vão juntos num único texto de tamanho fixo ([encode]) antes da cifragem: o AES-GCM preserva o
 * tamanho, e um valor curto cifrado à parte revelaria a resposta pelo tamanho do texto cifrado.
 */
class SensitiveGuestData(
    val raceColor: RaceColor?,
    disability: String?,
) {
    val disability: String? = disability?.trim()?.ifEmpty { null }

    init {
        if (this.disability != null && !DISABILITY.matches(this.disability)) {
            throw GuestsRules.invalid(
                GuestsErrorCodes.SENSITIVE_DATA_INVALID,
                "disability",
                "Informe a deficiência pelo código do domínio da FNRH (letras maiúsculas, dígitos e _, até 60).",
            )
        }
    }

    val isEmpty: Boolean get() = raceColor == null && disability == null

    /** O texto que se cifra: `s1|<raça/cor>|<deficiência>` completado com espaços até [ENCODED_LENGTH]. */
    fun encode(): String = "$VERSION|${raceColor?.name.orEmpty()}|${disability.orEmpty()}".padEnd(ENCODED_LENGTH)

    override fun equals(other: Any?): Boolean =
        other is SensitiveGuestData && raceColor == other.raceColor && disability == other.disability

    override fun hashCode(): Int = Objects.hash(raceColor, disability)

    /** Nunca mostra os dados. */
    override fun toString(): String = "SensitiveGuestData(***)"

    companion object {
        /** O tamanho fixo do texto antes da cifragem (cabe a versão, a maior raça/cor e 60 de deficiência). */
        const val ENCODED_LENGTH = 96
        private const val VERSION = "s1"
        private const val PARTS = 3
        private val DISABILITY = Regex("[A-Z0-9_]{1,60}")

        fun decode(encoded: String): SensitiveGuestData {
            val parts = encoded.trimEnd().split('|')
            check(parts.size == PARTS && parts[0] == VERSION) { "Dados sensíveis gravados fora do formato $VERSION" }
            val (_, raceColor, disability) = parts
            return SensitiveGuestData(raceColor.ifEmpty { null }?.let(RaceColor::valueOf), disability.ifEmpty { null })
        }
    }
}
