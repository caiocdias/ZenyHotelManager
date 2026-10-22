package com.zeny.hotel.frontdesk

import com.zeny.hotel.shared.Money
import java.time.Instant
import java.time.LocalTime

/**
 * Uma oferta de early check-in ou de late check-out (RN-11): se o hotel oferece, o horário limite e o valor da política
 * vigente e a janela em instantes no fuso do hotel — no early, de [from] (a chegada no horário limite) a [until] (a
 * chegada no horário de check-in); no late, de [from] (a saída no horário de check-out) a [until] (a saída no limite).
 */
data class EarlyLateOffer(
    val offered: Boolean,
    val limitTime: LocalTime?,
    val fee: Money?,
    val from: Instant?,
    val until: Instant?,
)
