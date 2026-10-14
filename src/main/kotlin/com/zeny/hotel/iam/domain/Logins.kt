package com.zeny.hotel.iam.domain

import java.util.Locale

/**
 * Normalização do login (RF-ACS-01: login ou e-mail). O login é comparado sem diferenciar
 * maiúsculas: guarda-se e busca-se sempre a forma normalizada (sem espaços nas pontas e em
 * minúsculas). Só ASCII, de 3 a 100 caracteres (letras, dígitos e `. _ @ + -`, começando por
 * letra ou dígito), para não haver duas grafias Unicode do mesmo login.
 */
object Logins {
    private val VALID = Regex("^[a-z0-9][a-z0-9._@+-]{2,99}$")

    /** A forma normalizada de [raw], ou `null` se ele não for um login válido. */
    fun normalize(raw: String): String? = raw.trim().lowercase(Locale.ROOT).takeIf(VALID::matches)
}
