package com.zeny.hotel.shared.web

import com.zeny.hotel.shared.StorableRange
import com.zeny.hotel.shared.UnboundedInstant
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
import java.time.LocalTime
import java.util.UUID

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
    fun `a well-formed date that does not exist in the calendar is refused on the field`() {
        val bodies =
            listOf("\"2025-02-29\"", "\"2026-02-30\"", "\"2026-13-01\"", "\"2026-11-31\"", "\"2026-00-10\"")
                .flatMap { value ->
                    listOf(
                        """{"on":$value}""" to "on",
                        """{"at":${value.dropLast(1)}T10:00:00Z"}""" to "at",
                    )
                }

        bodies.associate { (json, _) -> json to outcome(json) } shouldBe
            bodies.associate { (json, field) -> json to refused(field) }
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

    // --- M6 E20: faixa do StorableRange, LocalTime, inteiro e UUID --------------------------------------------

    @Test
    fun `a date or an instant outside the storable range is refused on the field, also nested and in a list`() {
        val bodies =
            listOf("\"+5874898-01-01\"", "\"-4713-12-31\"").flatMap { value ->
                listOf(
                    """{"on":$value}""" to "on",
                    """{"stay":{"arrival":$value}}""" to "stay.arrival",
                    """{"days":["2026-11-13",$value]}""" to "days[1]",
                )
            } +
                listOf("\"+294277-01-01T00:00:00Z\"", "\"-4713-12-31T23:59:59Z\"").flatMap { value ->
                    listOf(
                        """{"at":$value}""" to "at",
                        """{"guests":[{"seenAt":"2026-11-13T10:00:00Z"},{"seenAt":$value}]}""" to "guests[1].seenAt",
                        """{"moments":[$value]}""" to "moments[0]",
                    )
                }

        bodies.associate { (json, _) -> json to outcome(json) } shouldBe
            bodies.associate { (json, field) -> json to refused(field) }
    }

    @Test
    fun `a time is read only as HH-mm or HH-mm-ss, with the path of the field`() {
        val values =
            listOf(
                "\"9:05\"",
                "\"09:05:30.5\"",
                "\"24:00\"",
                "\"09:60\"",
                "\"09:05:00Z\"",
                "\"09:5\"",
                "\"\"",
                "\" 09:05\"",
                "905",
                "9.5",
            )
        val bodies =
            values.flatMap { value ->
                listOf(
                    """{"at":$value}""" to "at",
                    """{"slot":{"opensAt":$value}}""" to "slot.opensAt",
                    """{"times":["09:05",$value]}""" to "times[1]",
                )
            }

        bodies.associate { (json, _) -> json to outcomeNumbers(json) } shouldBe
            bodies.associate { (json, field) -> json to refused(field) }
        readNumbers("""{"at":"09:05"}""") shouldBe Numbers(at = LocalTime.of(9, 5))
        readNumbers("""{"at":"00:00"}""") shouldBe Numbers(at = LocalTime.MIDNIGHT)
        readNumbers("""{"at":"23:59:59"}""") shouldBe Numbers(at = LocalTime.of(23, 59, 59))
        readNumbers("""{"times":["09:05:30"]}""") shouldBe Numbers(times = listOf(LocalTime.of(9, 5, 30)))
    }

    @Test
    fun `a fractional number in an Int or Long is refused, with 7_0 and scientific notation, nested and in a list`() {
        val bodies =
            listOf("7.5", "7.0", "7e0", "1E2", "-0.0").flatMap { value ->
                listOf(
                    """{"count":$value}""" to "count",
                    """{"total":$value}""" to "total",
                    """{"slot":{"count":$value}}""" to "slot.count",
                    """{"counts":[1,$value]}""" to "counts[1]",
                    """{"totals":[$value]}""" to "totals[0]",
                    """{"credits":[{"count":1},{"count":$value}]}""" to "credits[1].count",
                )
            }

        bodies.associate { (json, _) -> json to outcomeNumbers(json) } shouldBe
            bodies.associate { (json, field) -> json to refused(field) }
        readNumbers("""{"count":7,"total":-9000000000,"counts":[0,7],"totals":[1]}""") shouldBe
            Numbers(count = 7, total = -9_000_000_000, counts = listOf(0, 7), totals = listOf(1))
    }

    @Test
    fun `an empty text in a UUID is refused on the field, also nested and in a list, and a UUID is read`() {
        val id = UUID.randomUUID()
        val bodies =
            listOf(
                """{"id":""}""" to "id",
                """{"slot":{"roomId":""}}""" to "slot.roomId",
                """{"ids":["$id",""]}""" to "ids[1]",
                """{"items":[{"roomId":"$id"},{"roomId":""}]}""" to "items[1].roomId",
            )

        bodies.associate { (json, _) -> json to outcomeNumbers(json) } shouldBe
            bodies.associate { (json, field) -> json to refused(field) }
        readNumbers("""{"id":"$id","ids":["$id"]}""") shouldBe Numbers(id = id, ids = listOf(id))
        readNumbers("""{"id":null,"ids":[null]}""") shouldBe Numbers(ids = listOf(null))
    }

    @Test
    fun `null and an absent field are read as before in time, integer and UUID`() {
        readNumbers("""{"at":null,"count":null,"total":null,"id":null}""") shouldBe Numbers()
        readNumbers("{}") shouldBe Numbers()
    }

    /** D4 da E20: só o número fracionário é recusado; o inteiro em texto segue lido como antes. */
    @Test
    fun `an integer as text is still read in an Int or Long field`() {
        readNumbers("""{"count":"7","total":"8"}""") shouldBe Numbers(count = 7, total = 8)
    }

    /**
     * Uma propriedade `@UnboundedInstant` (o envelope offline) perde só a faixa, e a vizinha sem a anotação, no mesmo
     * objeto, a mantém: a anotação não vaza para o tipo nem para as outras propriedades.
     */
    @Test
    fun `an UnboundedInstant property reads outside the range while its neighbour without it refuses`() {
        val outside = listOf("+294277-01-01T00:00:00Z", "-4713-12-31T23:59:59Z")

        outside.forEach { value ->
            withClue(value) {
                readEnvelope("""{"free":"$value","bound":"2026-11-13T10:00:00Z"}""") shouldBe
                    Envelope(free = Instant.parse(value), bound = Instant.parse("2026-11-13T10:00:00Z"))
                outcomeOf<Envelope>("""{"free":"2026-11-13T10:00:00Z","bound":"$value"}""") shouldBe refused("bound")
            }
        }
    }

    @Test
    fun `an UnboundedInstant property keeps the strict ISO format`() {
        val bodies =
            listOf("1763028000", "1763028000000", "\"1763028000\"", "\"\"", "\"2026-11-13\"", "{}", "true")
                .map { """{"free":$it}""" }

        bodies.associateWith { outcomeOf<Envelope>(it) } shouldBe bodies.associateWith { refused("free") }
    }

    /**
     * Primitivo não anulável sem valor padrão, aninhado ou numa lista, ausente ou `null`: `REQUIRED` no campo com o
     * caminho completo; `""` nele segue `INVALID_FORMAT` (M6 E20).
     */
    @Test
    fun `a missing or null primitive, nested or in a list, is REQUIRED on the full path`() {
        val cases =
            listOf(
                """{"band":{"label":"x"}}""" to "band.min",
                """{"band":{"min":null,"label":"x"}}""" to "band.min",
                """{"bands":[{"min":1,"label":"x"},{"label":"x"}]}""" to "bands[1].min",
                """{"bands":[{"min":1,"label":"x"},{"min":null,"label":"x"}]}""" to "bands[1].min",
                """{"bands":[{"min":1,"label":"x","open":null}]}""" to "bands[0].open",
            )

        cases.associate { (json, _) -> json to outcomeOf<Primitives>(json) } shouldBe
            cases.associate { (json, field) -> json to "recusado: ${FieldViolations.required(field)}" }
        outcomeOf<Primitives>("""{"bands":[{"min":"","label":"x"}]}""") shouldBe refused("bands[0].min")
        outcomeOf<Primitives>("""{"bands":[{"min":1.5,"label":"x"}]}""") shouldBe refused("bands[0].min")
        readPrimitives("""{"bands":[{"min":1,"label":"x"}],"band":{"min":0,"label":"y","open":true}}""") shouldBe
            Primitives(bands = listOf(Band(1, "x")), band = Band(0, "y", true))
    }

    data class Envelope(
        @param:UnboundedInstant
        @field:UnboundedInstant
        val free: Instant? = null,
        val bound: Instant? = null,
    )

    data class Band(
        val min: Int,
        val label: String,
        val open: Boolean = false,
    )

    data class Primitives(
        val band: Band? = null,
        val bands: List<Band>? = null,
    )

    private fun readEnvelope(json: String): Envelope = jsonMapper.readValue<Envelope>(json)

    private fun readPrimitives(json: String): Primitives = jsonMapper.readValue<Primitives>(json)

    private inline fun <reified T : Any> outcomeOf(json: String): String =
        try {
            "lido: ${jsonMapper.readValue<T>(json)}"
        } catch (error: DatabindException) {
            val violation =
                FieldViolations.of(
                    HttpMessageNotReadableException("corpo ilegível", error, MockHttpInputMessage(json.toByteArray())),
                )
            "recusado: $violation"
        }

    /**
     * `""` num `Int` ou `Long` anulável: o Jackson o lê como `null` e o campo perde o valor em silêncio (o mesmo
     * defeito que a E20 fecha no UUID). O contrato da etapa não o cita, mas a regra "`""` não vira `null`" vale aqui.
     */
    @Test
    fun `an empty text in an Int or Long field is refused and does not become null`() {
        val bodies =
            listOf(
                """{"count":""}""" to "count",
                """{"total":""}""" to "total",
                """{"slot":{"count":""}}""" to "slot.count",
                """{"counts":[1,""]}""" to "counts[1]",
            )

        bodies.associate { (json, _) -> json to outcomeNumbers(json) } shouldBe
            bodies.associate { (json, field) -> json to refused(field) }
    }

    data class Numbers(
        val at: LocalTime? = null,
        val count: Int? = null,
        val total: Long? = null,
        val id: UUID? = null,
        val slot: Slot? = null,
        val times: List<LocalTime>? = null,
        val counts: List<Int>? = null,
        val totals: List<Long>? = null,
        val ids: List<UUID?>? = null,
        val credits: List<Slot>? = null,
        val items: List<Slot>? = null,
    )

    data class Slot(
        val opensAt: LocalTime? = null,
        val count: Int? = null,
        val roomId: UUID? = null,
    )

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

    private fun readNumbers(json: String): Numbers = jsonMapper.readValue<Numbers>(json)

    private fun outcomeNumbers(json: String): String =
        try {
            "lido: ${readNumbers(json)}"
        } catch (error: DatabindException) {
            val violation =
                FieldViolations.of(
                    HttpMessageNotReadableException("corpo ilegível", error, MockHttpInputMessage(json.toByteArray())),
                )
            "recusado: $violation"
        }

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
