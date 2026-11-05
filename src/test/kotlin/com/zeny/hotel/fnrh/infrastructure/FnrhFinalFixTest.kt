package com.zeny.hotel.fnrh.infrastructure

import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.application.FnrhConnection
import com.zeny.hotel.fnrh.application.FnrhFailure
import com.zeny.hotel.fnrh.application.FnrhGatewayException
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.testsupport.FnrhTestServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration

/** Correções da revisão final da E07: `findReservationByCode` falha fechado (I2) e o saneador (M2). */
class FnrhFinalFixTest {
    private val gateway =
        SerproFnrhGateway(
            FnrhProperties(connectTimeout = Duration.ofSeconds(2), readTimeout = Duration.ofSeconds(5)),
            {
                FnrhConnection(
                    FnrhEnvironment.HOMOLOGATION,
                    URI.create(FnrhTestServer.baseUrl),
                    FnrhTestServer.USERNAME,
                    FnrhTestServer.PASSWORD,
                    Cpf.of("11144477735"),
                )
            },
            {},
        )

    @BeforeEach
    fun reset() = FnrhTestServer.reset()

    private fun unknownOutcome(body: String) {
        FnrhTestServer.stubAny(200, body)
        shouldThrow<FnrhGatewayException> { gateway.findReservationByCode("RES-1", "c") }.failure shouldBe
            FnrhFailure.UNKNOWN_OUTCOME
    }

    @Test
    fun `two items without numero_reserva are an unknown outcome, none is picked`() =
        unknownOutcome("""{"dados":[{"reserva_id":"a"},{"reserva_id":"b","numero_reserva":null}]}""")

    @Test
    fun `an item without numero_reserva while the total says more is an unknown outcome`() =
        unknownOutcome("""{"dados":[{"reserva_id":"a"}],"pagination":{"TotalRegistros":2}}""")

    @Test
    fun `an item without numero_reserva and a total of one is accepted`() {
        FnrhTestServer.stubAny(200, """{"dados":[{"reserva_id":"id-x"}],"pagination":{"TotalRegistros":1}}""")

        gateway.findReservationByCode("RES-1", "c") shouldBe FnrhRemoteId("id-x")
    }

    @Test
    fun `an item whose numero_reserva equals the code is still accepted among others`() {
        FnrhTestServer.stubAny(
            200,
            """{"dados":[{"reserva_id":"a","numero_reserva":"RES-10"},{"reserva_id":"b","numero_reserva":"RES-1"}]}""",
        )

        gateway.findReservationByCode("RES-1", "c") shouldBe FnrhRemoteId("b")
    }

    @Test
    fun `a message with CR and LF comes out on one line`() {
        val out = FnrhMessageSanitizer.sanitize("linha 1\r\nlinha 2\nlinha 3\u0007fim", emptyList())!!

        out.none { it.isISOControl() } shouldBe true
        out shouldBe "linha 1  linha 2 linha 3 fim"
    }

    @Test
    fun `the Unicode line and paragraph separators and NEL also become spaces`() {
        FnrhMessageSanitizer.sanitize("a\u2028b\u2029c\u0085d", emptyList()) shouldBe "a b c d"
    }
}
