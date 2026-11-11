package com.zeny.hotel.fnrh.infrastructure

import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.application.FnrhConnection
import com.zeny.hotel.fnrh.application.FnrhFailure
import com.zeny.hotel.fnrh.application.FnrhGatewayException
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.testsupport.FnrhTestServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Duration

/**
 * A conciliação da reserva por código falha fechado com mais de um casamento (D-E08-6, ARCHITECTURE §12): dois itens
 * com o `numero_reserva` pedido e ids diferentes não dizem qual é a reserva, e escolher um ligaria a reserva ao id
 * errado.
 */
class SerproFnrhReservationAmbiguityTest {
    private val mapper = JsonMapper.builder().build()
    private val gateway =
        SerproFnrhGateway(
            FnrhProperties(connectTimeout = Duration.ofSeconds(2), readTimeout = Duration.ofSeconds(10)),
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

    @BeforeEach
    fun setUp() {
        FnrhTestServer.reset()
    }

    @Test
    fun `two items matching the code with different ids are ambiguous`() {
        val tree =
            mapper.readTree(
                """{"dados":[{"reserva_id":"a","numero_reserva":"RES-1"},""" +
                    """{"reserva_id":"b","numero_reserva":"RES-1"}],"pagination":{"TotalRegistros":2}}""",
            )

        shouldThrow<AmbiguousMatchException> { reservationIdsFor(tree, "RES-1") }
    }

    @Test
    fun `the same id listed twice for the code is still one match`() {
        val tree =
            mapper.readTree(
                """{"dados":[{"reserva_id":"a","numero_reserva":"RES-1"},""" +
                    """{"reserva_id":"a","numero_reserva":"RES-1"}]}""",
            )

        reservationIdsFor(tree, "RES-1") shouldContainExactly listOf("a")
    }

    @Test
    fun `one match among other codes is the reservation`() {
        val tree =
            mapper.readTree(
                """{"dados":[{"reserva_id":"a","numero_reserva":"RES-10"},""" +
                    """{"reserva_id":"b","numero_reserva":"RES-1"}]}""",
            )

        reservationIdsFor(tree, "RES-1") shouldContainExactly listOf("b")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", ""","reserva_id":null""", ""","reserva_id":""""", ""","reserva_id":42"""])
    fun `a matching item without a valid reserva_id is ambiguous, never a missing reservation`(idField: String) {
        val tree = mapper.readTree("""{"dados":[{"numero_reserva":"RES-1"$idField}]}""")

        shouldThrow<AmbiguousMatchException> { reservationIdsFor(tree, "RES-1") }
    }

    @Test
    fun `a single item without numero_reserva nor reserva_id is ambiguous`() {
        val tree = mapper.readTree("""{"dados":[{"situacao":"CRIADA"}],"pagination":{"TotalRegistros":1}}""")

        shouldThrow<AmbiguousMatchException> { reservationIdsFor(tree, "RES-1") }
    }

    @Test
    fun `a matching item with an id next to one without it is ambiguous`() {
        val tree =
            mapper.readTree(
                """{"dados":[{"reserva_id":"a","numero_reserva":"RES-1"},{"numero_reserva":"RES-1"}]}""",
            )

        shouldThrow<AmbiguousMatchException> { reservationIdsFor(tree, "RES-1") }
    }

    @Test
    fun `findReservationByCode with a match without reserva_id fails with UNKNOWN_OUTCOME`() {
        FnrhTestServer.stub(
            "/reservas?page_number=1&codigo_reserva=RES-1",
            body = """{"dados":[{"numero_reserva":"RES-1","reserva_id":""}]}""",
        )

        val error = shouldThrow<FnrhGatewayException> { gateway.findReservationByCode("RES-1", "ref-1") }

        error.failure shouldBe FnrhFailure.UNKNOWN_OUTCOME
    }

    @Test
    fun `findReservationByCode with two matches fails with UNKNOWN_OUTCOME and returns no id`() {
        FnrhTestServer.stubFindReservationByCode_v2_4("RES-1", "res-a", "res-b")

        val error = shouldThrow<FnrhGatewayException> { gateway.findReservationByCode("RES-1", "ref-1") }

        error.failure shouldBe FnrhFailure.UNKNOWN_OUTCOME
    }

    @Test
    fun `findReservationByCode with one match returns its id`() {
        FnrhTestServer.stubFindReservationByCode_v2_4("RES-1", "res-a")

        gateway.findReservationByCode("RES-1", "ref-1") shouldBe FnrhRemoteId("res-a")
    }
}
