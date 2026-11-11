package com.zeny.hotel.fnrh.application

import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** O resultado do `POST /reservas` (M5 E08): o link de pré-check-in dá acesso a dados pessoais e não sai no log. */
class FnrhReservationRegisteredTest {
    @Test
    fun `toString shows the remote id and never the precheck-in link`() {
        val registered = FnrhReservationRegistered(FnrhRemoteId("r-42"), "https://precheckin.example/r/s3cr3t-token")

        val text = registered.toString()

        text shouldContain "r-42"
        text shouldNotContain "precheckin"
        text shouldNotContain "s3cr3t-token"
    }
}
