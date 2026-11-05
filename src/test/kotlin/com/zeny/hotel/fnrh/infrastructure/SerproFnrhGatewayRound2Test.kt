package com.zeny.hotel.fnrh.infrastructure

import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.application.FnrhCall
import com.zeny.hotel.fnrh.application.FnrhConnection
import com.zeny.hotel.fnrh.application.FnrhFailure
import com.zeny.hotel.fnrh.application.FnrhGatewayException
import com.zeny.hotel.fnrh.domain.FnrhDomain
import com.zeny.hotel.fnrh.domain.FnrhGuest
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.fnrh.domain.FnrhReservationData
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.testsupport.FnrhTestServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Segunda rodada do testador (E07, Task 4): catch amplo, usuário com `:`, `.`/`..` e grupos de dígitos. */
class SerproFnrhGatewayRound2Test {
    private val calls = mutableListOf<FnrhCall>()
    private var connection: FnrhConnection? = connectionTo(FnrhTestServer.baseUrl)
    private val gateway =
        SerproFnrhGateway(
            FnrhProperties(connectTimeout = Duration.ofSeconds(2), readTimeout = Duration.ofMillis(300)),
            { connection },
            { calls += it },
        )
    private val at = Instant.parse("2026-02-15T11:00:00Z")
    private val rid = FnrhRemoteId("remote-1")
    private val reservation =
        FnrhReservationData("RES-1", null, LocalDate.parse("2026-02-10"), LocalDate.parse("2026-02-15"), 2, 0)

    private fun connectionTo(
        baseUrl: String,
        username: String = FnrhTestServer.USERNAME,
    ) = FnrhConnection(
        FnrhEnvironment.HOMOLOGATION,
        URI.create(baseUrl),
        username,
        FnrhTestServer.PASSWORD,
        Cpf.of("11144477735"),
    )

    companion object {
        @JvmStatic
        @BeforeAll
        fun warmUp() {
            FnrhTestServer.reset()
            FnrhTestServer.stubAny(200, """{"dados":[]}""")
            val warm =
                SerproFnrhGateway(
                    FnrhProperties(readTimeout = Duration.ofSeconds(10)),
                    {
                        FnrhConnection(
                            FnrhEnvironment.HOMOLOGATION,
                            URI.create(FnrhTestServer.baseUrl),
                            FnrhTestServer.USERNAME,
                            FnrhTestServer.PASSWORD,
                            Cpf.of("11144477735"),
                        )
                    },
                    { },
                )
            repeat(2) { warm.fetchDomain(FnrhDomain.RACES) }
        }
    }

    @BeforeEach
    fun resetServer() {
        FnrhTestServer.reset()
    }

    @Test
    fun `a legacy username with a colon is UNAUTHORIZED on every operation, sends nothing and logs one call each`() {
        connection = connectionTo(FnrhTestServer.baseUrl, username = "dom:user")
        val ops: Map<String, () -> Unit> =
            mapOf(
                "registerReservation" to { gateway.registerReservation(reservation, "c").let { } },
                "cancelReservation" to { gateway.cancelReservation(rid, "c") },
                "checkOut" to { gateway.checkOut(rid, at, "c") },
                "fetchDomain" to { gateway.fetchDomain(FnrhDomain.RACES).let { } },
                "addGuest" to
                    { gateway.addGuestToReservation(rid, rid, FnrhGuest(true, at, null, null, null), "c").let { } },
            )
        for ((op, run) in ops) {
            calls.clear()
            val e = shouldThrow<FnrhGatewayException> { run() }
            withClue(op) {
                e.failure shouldBe FnrhFailure.UNAUTHORIZED
                e.cause.shouldBeNull()
                e.message.orEmpty() shouldNotContain "dom:user"
                calls.size shouldBe 1
                calls.single().outcome shouldBe "UNAUTHORIZED"
            }
        }
        FnrhTestServer.requests().shouldBeEmpty()
    }

    @Test
    fun `remote ids dot and dot-dot are encoded and never become path navigation`() {
        FnrhTestServer.stubAny(200, "")
        for ((id, encoded) in mapOf("." to "%2E", ".." to "%2E%2E")) {
            FnrhTestServer.server.resetRequests()
            gateway.cancelReservation(FnrhRemoteId(id), "c")
            FnrhTestServer.onlyRequest().url shouldBe "${FnrhTestServer.BASE_PATH}/reservas/$encoded/cancelar"
        }
    }

    @Test
    fun `a three-dot id and ids containing dots are untouched`() {
        FnrhTestServer.stubAny(200, "")

        gateway.updateReservation(FnrhRemoteId("a.b..c"), reservation, "c")

        FnrhTestServer.onlyRequest().url shouldBe "${FnrhTestServer.BASE_PATH}/reservas/a.b..c"
    }

    @Test
    fun `a request that cannot even be built (unsupported scheme) was never sent, so it is not UNKNOWN_OUTCOME`() {
        connection = connectionTo("ftp://localhost/FNRH_API/rest/v2")

        val e = shouldThrow<FnrhGatewayException> { gateway.registerReservation(reservation, "c") }

        e.failure shouldBe FnrhFailure.UNAVAILABLE
        e.cause.shouldBeNull()
        calls.single().outcome shouldBe "UNAVAILABLE"
    }

    @Test
    fun `a programming error while building the call is not hidden as an unknown outcome on a write`() {
        connection = connectionTo("mailto:someone@example.com")

        val e = shouldThrow<FnrhGatewayException> { gateway.cancelReservation(rid, "c") }

        e.failure shouldBe FnrhFailure.UNAVAILABLE
        e.cause.shouldBeNull()
    }

    @Test
    fun `the broad catch still keeps a real read timeout on a write as UNKNOWN_OUTCOME`() {
        FnrhTestServer.stubAny(200, """{"reserva":{"reserva_id":"r"}}""", delayMs = 800)

        val e = shouldThrow<FnrhGatewayException> { gateway.registerReservation(reservation, "c") }

        e.failure shouldBe FnrhFailure.UNKNOWN_OUTCOME
        e.message.orEmpty() shouldContain "UNKNOWN_OUTCOME"
    }

    // ---------- saneador: grupos de 4 dígitos

    @Test
    fun `a numeric error code with 4 digits that is not part of a sent value stays readable`() {
        val out =
            FnrhMessageSanitizer.sanitize(
                "codigo 4021: campo invalido",
                listOf("Maria da Luz", "(11) 9999-8888", "maria@x.com"),
            )!!

        out shouldBe "codigo 4021: campo invalido"
    }

    @Test
    fun `each 4-digit group of a formatted phone is masked when echoed alone`() {
        val out = FnrhMessageSanitizer.sanitize("telefone 9999-8888 invalido", listOf("(11) 9999-8888"))!!

        out shouldNotContain "9999"
        out shouldNotContain "8888"
        out shouldContain "telefone"
    }

    @Test
    fun `groups of 3 or fewer digits of a sent value are not masked`() {
        FnrhMessageSanitizer.sanitize("campo 123 invalido, 55 e 7", listOf("55 123 7"))!! shouldBe
            "campo 123 invalido, 55 e 7"
    }

    @Test
    fun `an echoed e-mail in non-Latin letters and one glued to punctuation are masked`() {
        FnrhMessageSanitizer.sanitize("a:用户@例子.广告,b", emptyList())!! shouldBe "a:***,b"
        FnrhMessageSanitizer.sanitize("(joão@x.com.br)", emptyList())!! shouldBe "(***)"
    }
}
