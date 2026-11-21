package com.zeny.hotel.reporting

import com.zeny.hotel.endofday.EndOfDayRealStepsTestConfiguration
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper

/**
 * `ReportingApi.endOfDayPendingItems()` (M6 E13, D3): um item por conta com saldo, caixa aberto e submissão FNRH
 * pendente, com a referência opaca e um `detail` só com número, código e status. O banco é o isolado do fechamento
 * (o que o teste deixa vira pendência nos fechamentos seguintes): afirma só pelos ids dos próprios dados.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class)
class EndOfDayPendingItemsTest(
    @Autowired private val reporting: ReportingApi,
    @Autowired iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
    @Autowired hotel: HotelApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val seeds = EndOfDayPendingSeed(iam, ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc), jdbc, hotel)

    @Test
    fun `one item per kind carries the opaque reference and a detail without personal data or balance`() {
        val seeded = seeds.seed()

        val items = reporting.endOfDayPendingItems()

        val mine = items.filter { it.reference in setOf(seeded.folioId, seeded.shiftId, seeded.reservationId) }

        fun detailsOf(kind: EndOfDayPendingKind) = mine.filter { it.kind == kind }.map { it.reference to it.detail }
        detailsOf(EndOfDayPendingKind.FOLIO_OPEN_BALANCE) shouldContainExactly
            listOf(seeded.folioId to "conta ${seeded.folioNumber} da reserva ${seeded.reservationCode}")
        detailsOf(EndOfDayPendingKind.CASHIER_SHIFT_OPEN) shouldContainExactly
            listOf(seeded.shiftId to "caixa ${seeded.shiftNumber} aberto em ${seeded.openedOn}")
        // O cadastro automático da reserva também está pendente: o item da falha plantada é um deles.
        detailsOf(EndOfDayPendingKind.FNRH_SUBMISSION_PENDING) shouldContain
            (seeded.reservationId to "FNRH UPDATE_RESERVATION FAILED")
        val text = mine.joinToString { it.detail }
        text shouldNotContain seeded.marker
        text shouldNotContain seeded.balance
        text shouldNotContain EndOfDayPendingSeed.LAST_FAILURE
    }
}
