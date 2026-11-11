package com.zeny.hotel.fnrh.domain

import java.time.Instant

/**
 * A forma antiga de `dismiss`, com o instante (M5 E08, revisão final M-5): o encerramento não usa o instante, e a
 * produção perdeu o parâmetro. Fica só nos testes, para os que ainda a chamam; é o mesmo que [FnrhSubmission.dismiss].
 */
@Suppress("UnusedParameter", "UNUSED_PARAMETER") // compatibilidade de chamada; o instante nunca foi usado
fun FnrhSubmission.dismiss(now: Instant) = dismiss()
