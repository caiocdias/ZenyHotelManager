package com.zeny.hotel.fnrh.infrastructure

import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.application.FnrhConnection
import com.zeny.hotel.fnrh.application.FnrhFailure
import com.zeny.hotel.fnrh.application.FnrhGatewayException
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.testsupport.FnrhTestServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.time.Duration

/** Ataques às correções da revisão final da E07: `findReservationByCode` fechado (I2) e o saneador (M2). */
class FnrhFinalFixAttackTest {
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

    private fun find(body: String): FnrhRemoteId? {
        FnrhTestServer.stubAny(200, body)
        return gateway.findReservationByCode("RES-1", "c")
    }

    private fun unknownOutcome(body: String) {
        FnrhTestServer.stubAny(200, body)
        shouldThrow<FnrhGatewayException> { gateway.findReservationByCode("RES-1", "c") }.failure shouldBe
            FnrhFailure.UNKNOWN_OUTCOME
    }

    @Test
    fun `zero items, with or without pagination, find nothing`() {
        find("""{"dados":[]}""").shouldBeNull()
        find("""{"dados":[],"pagination":{"TotalRegistros":0}}""").shouldBeNull()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"TotalRegistros":0}""",
            """{"TotalRegistros":2}""",
            """{"TotalRegistros":-1}""",
            """{"TotalRegistros":"1"}""",
            """{"TotalRegistros":1.5}""",
            """{"TotalRegistros":true}""",
            """{"TotalRegistros":[1]}""",
        ],
    )
    fun `one item without numero_reserva with an untrustworthy total is an unknown outcome`(pagination: String) {
        unknownOutcome("""{"dados":[{"reserva_id":"a"}],"pagination":$pagination}""")
    }

    @Test
    fun `one item without numero_reserva and no pagination, or no total, is accepted`() {
        find("""{"dados":[{"reserva_id":"a"}]}""") shouldBe FnrhRemoteId("a")
        find("""{"dados":[{"reserva_id":"a"}],"pagination":{}}""") shouldBe FnrhRemoteId("a")
    }

    @Test
    fun `several items with a single one matching the code pick that one, wherever it is`() {
        find(
            """{"dados":[{"reserva_id":"a","numero_reserva":"RES-2"},{"reserva_id":"b","numero_reserva":"RES-3"},""" +
                """{"reserva_id":"c","numero_reserva":"RES-1"}],"pagination":{"TotalRegistros":3}}""",
        ) shouldBe FnrhRemoteId("c")
    }

    @Test
    fun `several items with numero_reserva and none matching find nothing`() {
        find(
            """{"dados":[{"reserva_id":"a","numero_reserva":"RES-2"},{"reserva_id":"b","numero_reserva":"RES-3"}]}""",
        ).shouldBeNull()
    }

    @Test
    fun `several items without numero_reserva and no pagination are an unknown outcome`() {
        unknownOutcome("""{"dados":[{"reserva_id":"a"},{"reserva_id":"b"},{"reserva_id":"c"}]}""")
    }

    @Test
    fun `an ambiguous page leaks no reserva_id in the exception`() {
        FnrhTestServer.stubAny(200, """{"dados":[{"reserva_id":"leak-a"},{"reserva_id":"leak-b"}]}""")

        val failure = shouldThrow<FnrhGatewayException> { gateway.findReservationByCode("RES-1", "c") }

        failure.failure shouldBe FnrhFailure.UNKNOWN_OUTCOME
        failure.message.orEmpty().contains("leak-") shouldBe false
    }

    @ParameterizedTest
    @ValueSource(strings = ["\r", "\n", "\u0000", "\u0085", "\u001b", "\u007f"])
    fun `a control character in the message never survives`(control: String) {
        val out = FnrhMessageSanitizer.sanitize("antes${control}depois", emptyList())!!

        out.any { it.isISOControl() } shouldBe false
        out shouldBe "antes depois"
    }

    @Test
    fun `a forged log line made with CRLF stays on one line`() {
        val out = FnrhMessageSanitizer.sanitize("erro\r\n2026-11-05 INFO fake outcome=SUCCESS", emptyList())!!

        out.contains('\n') shouldBe false
        out.contains('\r') shouldBe false
    }
}
