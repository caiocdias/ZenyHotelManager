package com.zeny.hotel.shared.web

import com.zeny.hotel.shared.StorableRange
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.http.MockHttpInputMessage
import tools.jackson.databind.DatabindException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import java.time.LocalDate

/**
 * A leitura estrita de datas e instantes (M6 E18; ARCHITECTURE §7, "Datas e valores") no mapeador único da aplicação,
 * o injetado do contexto, e não um montado aqui: numa data só vale o texto `ISO_LOCAL_DATE`, e num instante só o texto
 * ISO-8601 com `T`. Todo o resto é recusado com `INVALID_FORMAT` no campo, com o caminho completo, inclusive em objeto
 * aninhado e em lista; `""` é recusado e não vira `null`. `null` e o campo ausente não mudam, e o texto que o próprio
 * servidor escreve nos extremos do `StorableRange` (ano com sinal e mais de 4 dígitos) continua legível.
 */
@IntegrationTest
class StrictDateTimeJsonMapperTest(
    @Autowired private val jsonMapper: JsonMapper,
) {
    @Test
    fun `a date that is not ISO text is refused on the field, also nested and in a list`() {
        val bodies =
            REFUSED_DATES.flatMap { value ->
                listOf(
                    """{"on":$value}""" to "on",
                    """{"stay":{"arrival":$value}}""" to "stay.arrival",
                    """{"guests":[{"birthDate":"2000-01-01"},{"birthDate":$value}]}""" to "guests[1].birthDate",
                    """{"days":["2026-11-13",$value]}""" to "days[1]",
                )
            }

        bodies.associate { (json, _) -> json to outcome(json) } shouldBe
            bodies.associate { (json, field) -> json to refused(field) }
    }

    @Test
    fun `an instant that is not ISO text with T is refused on the field, also nested and in a list`() {
        val bodies =
            REFUSED_INSTANTS.flatMap { value ->
                listOf(
                    """{"at":$value}""" to "at",
                    """{"stay":{"checkedInAt":$value}}""" to "stay.checkedInAt",
                    """{"guests":[{"seenAt":"2026-11-13T10:00:00Z"},{"seenAt":$value}]}""" to "guests[1].seenAt",
                    """{"moments":["2026-11-13T10:00:00Z",$value]}""" to "moments[1]",
                )
            }

        bodies.associate { (json, _) -> json to outcome(json) } shouldBe
            bodies.associate { (json, field) -> json to refused(field) }
    }

    @Test
    fun `an empty text is refused and does not become null, in a date and in an instant`() {
        outcome("""{"on":""}""") shouldBe refused("on")
        outcome("""{"at":""}""") shouldBe refused("at")
        outcome("""{"on":null,"at":null}""") shouldBe "lido: ${Payload()}"
    }

    @Test
    fun `ISO text is read, with a signed year of more than 4 digits at the extremes of the storable range`() {
        read("""{"on":"2026-11-13","at":"2026-11-13T10:00:00Z"}""") shouldBe
            Payload(on = LocalDate.of(2026, 11, 13), at = Instant.parse("2026-11-13T10:00:00Z"))
        read("""{"on":"-4712-01-01","at":"-4712-01-01T00:00:00Z"}""") shouldBe
            Payload(on = StorableRange.MIN_DATE, at = StorableRange.MIN_INSTANT)
        read("""{"on":"+5874897-12-31","at":"+294276-12-31T23:59:59.999999Z"}""") shouldBe
            Payload(on = StorableRange.MAX_DATE, at = StorableRange.MAX_INSTANT)
    }

    @Test
    fun `what the mapper writes at the extremes it reads back`() {
        listOf(
            Payload(on = StorableRange.MIN_DATE, at = StorableRange.MIN_INSTANT),
            Payload(on = StorableRange.MAX_DATE, at = StorableRange.MAX_INSTANT),
            Payload(
                stay = Stay(StorableRange.MAX_DATE, StorableRange.MIN_INSTANT),
                guests = listOf(Guest(StorableRange.MIN_DATE, StorableRange.MAX_INSTANT)),
                days = listOf(StorableRange.MIN_DATE, StorableRange.MAX_DATE),
                moments = listOf(StorableRange.MIN_INSTANT, StorableRange.MAX_INSTANT),
            ),
        ).forEach { payload ->
            val written = jsonMapper.writeValueAsString(payload)
            withClue(written) { read(written) shouldBe payload }
        }
    }

    @Test
    fun `null and an absent field are read as before`() {
        read("""{"on":null,"at":null,"stay":{"arrival":null},"days":[null]}""") shouldBe
            Payload(stay = Stay(), days = listOf(null))
        read("{}") shouldBe Payload()
        read("""{"stay":{}}""") shouldBe Payload(stay = Stay())
    }

    data class Payload(
        val on: LocalDate? = null,
        val at: Instant? = null,
        val stay: Stay? = null,
        val guests: List<Guest>? = null,
        val days: List<LocalDate?>? = null,
        val moments: List<Instant?>? = null,
    )

    data class Stay(
        val arrival: LocalDate? = null,
        val checkedInAt: Instant? = null,
    )

    data class Guest(
        val birthDate: LocalDate? = null,
        val seenAt: Instant? = null,
    )

    private fun read(json: String): Payload = jsonMapper.readValue<Payload>(json)

    /**
     * O que a leitura de [json] dá: o objeto lido (inclusive com `null` no campo) ou a violação que a API devolveria
     * para o mesmo corpo, pelo [FieldViolations] do `ApiExceptionHandler`.
     */
    private fun outcome(json: String): String =
        try {
            "lido: ${read(json)}"
        } catch (error: DatabindException) {
            val violation =
                FieldViolations.of(
                    HttpMessageNotReadableException("corpo ilegível", error, MockHttpInputMessage(json.toByteArray())),
                )
            "recusado: $violation"
        }

    private fun refused(field: String) = "recusado: ${FieldViolations.invalidFormat(field)}"

    private companion object {
        /** Como literais JSON: número (dia da época), texto da época, data-hora, array, objeto, vazio e espaços. */
        val REFUSED_DATES =
            listOf(
                "20405",
                "20405.5",
                "\"20405\"",
                "\"2026-11-13T00:00:00\"",
                "\"2026-11-13T00:00:00Z\"",
                "[2026,11,13]",
                "{}",
                "true",
                "\"\"",
                "\" \"",
                "\" 2026-11-13\"",
                "\"2026-11-13 \"",
                "\"2026-1-3\"",
                "\"13/11/2026\"",
                "\"20261113\"",
            )

        /** Época em segundos e milissegundos (número e texto), sem `T`, só a data, array, objeto, vazio e espaços. */
        val REFUSED_INSTANTS =
            listOf(
                "1763028000",
                "1763028000000",
                "1763028000.5",
                "\"1763028000\"",
                "\"1763028000000\"",
                "\"1763028000.5\"",
                "\"2026-11-13 10:00:00Z\"",
                "\"2026-11-13\"",
                "[2026,11,13,10,0]",
                "{}",
                "true",
                "\"\"",
                "\" \"",
                "\" 2026-11-13T10:00:00Z\"",
                "\"2026-11-13T10:00:00Z \"",
            )
    }
}
