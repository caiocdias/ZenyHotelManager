package com.zeny.hotel.fnrh.application

import com.zeny.hotel.fnrh.FnrhEnvironment

/**
 * Uma chamada HTTP ao Serpro, como entra no registro de integração (D-E07-8): sem texto livre. [outcome] é `SUCCESS`
 * ou o nome de um [FnrhFailure]; [correlationRef] e [remoteId] são referências opacas.
 */
data class FnrhCall(
    val operation: String,
    val environment: FnrhEnvironment,
    val outcome: String,
    val httpStatus: Int?,
    val durationMs: Long,
    val correlationRef: String?,
    val remoteId: String?,
)

/** A porta do registro de integração: o adaptador a chama uma vez por chamada HTTP. */
fun interface FnrhIntegrationLog {
    fun record(call: FnrhCall)
}
