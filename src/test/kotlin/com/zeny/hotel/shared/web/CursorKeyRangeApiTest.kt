package com.zeny.hotel.shared.web

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * A faixa das chaves do cursor (M6 E18): em cada consulta paginada por uma chave de instante ou de data, um cursor bem
 * formado com a chave fora do `StorableRange` (logo abaixo do piso `-4712-01-01` do driver ou logo acima do teto do
 * banco) é `400 INVALID_CURSOR`, e nunca um 500 nem uma página errada. A recusa vem do `PageCursor`, sem código local.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
class CursorKeyRangeApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val hotelApi: HotelApi,
    @Autowired jdbc: JdbcClient,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    @Test
    fun `an out of range instant key is INVALID_CURSOR on each of the 9 queries by instant`() {
        val (type, rooms) = support.inventory.roomTypeWithRooms(1, today)
        val reservationId = reservation(type)
        val folioId = support.billing.openFolio()
        val paths =
            listOf(
                "/api/v1/audit-entries",
                "/api/v1/checklist-reports/location-history?subjectType=rooms.room&subjectId=${rooms.single()}",
                "/api/v1/checklist-executions",
                "/api/v1/reservations/$reservationId/history",
                "/api/v1/reservations/pending-guarantees",
                "/api/v1/card-pre-authorizations/expiring?to=$today",
                "/api/v1/folios/$folioId/payments",
                "/api/v1/folios/$folioId/postings",
                "/api/v1/rooms/${rooms.single()}/status-history",
            )

        shouldAllBeInvalidCursor(paths, OUT_OF_RANGE_INSTANTS)
    }

    @Test
    fun `an out of range date key is INVALID_CURSOR on each of the 6 queries by date`() {
        val paths =
            listOf(
                "/api/v1/end-of-day/runs",
                "/api/v1/reservations/arrivals",
                "/api/v1/room-assignments/reallocation-required",
                "/api/v1/traces",
                "/api/v1/out-of-order",
                "/api/v1/schedule",
            )

        shouldAllBeInvalidCursor(paths, OUT_OF_RANGE_DATES)
    }

    @Test
    fun `the end of day issues keep refusing an out of range instant key without a local check`() {
        shouldAllBeInvalidCursor(listOf("/api/v1/end-of-day/runs/${UuidV7.generate()}/issues"), OUT_OF_RANGE_INSTANTS)
    }

    private fun shouldAllBeInvalidCursor(
        paths: List<String>,
        keys: List<String>,
    ) = assertSoftly {
        paths.forEach { path ->
            keys.forEach { key ->
                val cursor = PageCursor.of(key, UuidV7.generate().toString()).encode()
                val separator = if ('?' in path) '&' else '?'
                val response = iam.call(GET, "$path${separator}cursor=$cursor", support.admin)
                val body = response.getContentAsString(Charsets.UTF_8)
                withClue("$path com a chave $key -> ${response.status} $body") {
                    response.status shouldBe 400
                    iam.json(response)["code"]?.asString() shouldBe "INVALID_CURSOR"
                }
            }
        }
    }

    private fun reservation(type: UUID): UUID {
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(10))
        val arrival = today.plusDays(2)
        val created =
            support.created(
                support.booking(support.guest(), support.reservation(type, plan.id, arrival, arrival.plusDays(1))),
            )
        return UUID.fromString(created["reservations"][0]["id"].asString())
    }

    private companion object {
        /** Logo abaixo do piso, logo acima do teto e o teto mais meio microssegundo (o banco arredonda para cima). */
        val OUT_OF_RANGE_INSTANTS =
            listOf("-4713-12-31T23:59:59.999999Z", "+294277-01-01T00:00:00Z", "+294276-12-31T23:59:59.9999995Z")

        /** Logo abaixo do piso e logo acima do teto. */
        val OUT_OF_RANGE_DATES = listOf("-4713-12-31", "+5874898-01-01")
    }
}
