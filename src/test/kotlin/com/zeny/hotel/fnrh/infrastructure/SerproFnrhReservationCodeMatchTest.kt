package com.zeny.hotel.fnrh.infrastructure

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper

class SerproFnrhReservationCodeMatchTest {
    private val mapper = JsonMapper.builder().build()

    private fun item(json: String) = mapper.readTree(json)

    @ParameterizedTest
    @ValueSource(strings = ["""{"reserva_id":"a"}""", """{"reserva_id":"a","numero_reserva":null}"""])
    fun `an item without numero_reserva, or with JSON null, matches any code`(json: String) {
        matchesCode(item(json), "RES-1") shouldBe true
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"numero_reserva":100}""",
            """{"numero_reserva":10}""",
            """{"numero_reserva":true}""",
            """{"numero_reserva":{"v":"10"}}""",
            """{"numero_reserva":["10"]}""",
        ],
    )
    fun `an item whose numero_reserva is present but not text never matches`(json: String) {
        matchesCode(item(json), "10") shouldBe false
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["""{"numero_reserva":"RES-10"}""", """{"numero_reserva":""}""", """{"numero_reserva":" "}"""],
    )
    fun `an item whose numero_reserva text differs from the code does not match`(json: String) {
        matchesCode(item(json), "RES-1") shouldBe false
    }

    @ParameterizedTest
    @ValueSource(strings = ["""{"numero_reserva":"RES-1"}"""])
    fun `an item whose numero_reserva equals the code matches`(json: String) {
        matchesCode(item(json), "RES-1") shouldBe true
    }
}
