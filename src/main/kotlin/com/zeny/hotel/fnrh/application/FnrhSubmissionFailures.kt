package com.zeny.hotel.fnrh.application

import com.zeny.hotel.fnrh.domain.FnrhSubmissionFailure

/**
 * Converte a falha do Serpro na falha gravada na submissão. [FnrhFailure.NOT_CONFIGURED] não vira falha: sem
 * configuração o job não envia nada (D-E08-5), então chegar aqui é erro de programação.
 */
fun FnrhFailure.toSubmissionFailure(): FnrhSubmissionFailure =
    when (this) {
        FnrhFailure.NOT_CONFIGURED -> throw IllegalArgumentException("NOT_CONFIGURED não é uma falha de submissão")
        FnrhFailure.VALIDATION -> FnrhSubmissionFailure.VALIDATION
        FnrhFailure.UNAUTHORIZED -> FnrhSubmissionFailure.UNAUTHORIZED
        FnrhFailure.FORBIDDEN -> FnrhSubmissionFailure.FORBIDDEN
        FnrhFailure.NOT_FOUND -> FnrhSubmissionFailure.NOT_FOUND
        FnrhFailure.SERVER_ERROR -> FnrhSubmissionFailure.SERVER_ERROR
        FnrhFailure.UNAVAILABLE -> FnrhSubmissionFailure.UNAVAILABLE
        FnrhFailure.UNKNOWN_OUTCOME -> FnrhSubmissionFailure.UNKNOWN_OUTCOME
    }
