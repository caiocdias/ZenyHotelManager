package com.zeny.hotel.reporting

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.InventoryListenersQuiescence
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `ReportingApi.snapshotDailyStatistics` (M6 E23, D1) contra o banco. As reservas são pelo inventário e pela API numa
 * data operacional futura própria da classe (a API só cria reservas dentro do horizonte de 730 dias, então não cabe
 * uma faixa de anos à parte: 640 dias adiante), e os lançamentos, que a API só faz na data corrente, são gravados por
 * SQL válido nas linhas só de inserção do `billing`, com `business_date` = essa data, em códigos de transação
 * existentes e numa conta aberta pela API. O dia sem movimento usa uma data de 2052, fora do horizonte (sem
 * inventário nem reservas). O banco é compartilhado: a disponibilidade, que vem do inventário de todos os tipos de UH
 * dos outros testes, é lida das views na hora de afirmar.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
class DailyStatisticsSnapshotTest(
    @Autowired iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired private val reporting: ReportingApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val seed = ReportingTestSupport(iam, support)
    private val billing = support.billing

    private fun records(date: LocalDate): Long =
        jdbc
            .sql("SELECT count(*) FROM reporting.daily_statistics WHERE business_date = :date")
            .param("date", date)
            .query(Long::class.java)
            .single()

    private fun inventory(
        date: LocalDate,
        expression: String,
    ): Long =
        jdbc
            .sql("SELECT coalesce(sum($expression), 0) FROM reservations.report_inventory WHERE stay_date = :date")
            .param("date", date)
            .query(Long::class.java)
            .single()

    /** Grava um lançamento válido (normal, na conta [folio], no código [key]) com a data de negócio [date]. */
    private fun post(
        folio: UUID,
        key: String,
        amount: String,
        date: LocalDate,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO billing.posting (id, folio_id, transaction_code_id, posting_type, quantity, unit_price,
                    amount, description, source, business_date, posted_at)
                VALUES (:id, :folio, :code, 'REGULAR', 1, :amount, :amount, 'Lançamento da estatística diária',
                    'MANUAL', :date, now())
                """.trimIndent(),
            ).param("id", UuidV7.generate())
            .param("folio", folio)
            .param("code", billing.codeId(key))
            .param("amount", BigDecimal(amount))
            .param("date", date)
            .update()
    }

    @Test
    @Suppress("LongMethod") // o cenário inteiro e as conferências do D1
    fun `the snapshot has the disjoint counts, the revenues and the derived figures of D1, and is not recalculated`() {
        val base = hotel.currentBusinessDate()
        val day = base.plusDays(SCENARIO_OFFSET)
        val (type, _) = support.inventory.roomTypeWithRooms(ROOMS, day)
        val normal = seed.plan(type, "NORMAL", day.minusDays(1))
        val courtesy = seed.plan(type, "COURTESY", day.minusDays(1))
        val houseUse = seed.plan(type, "HOUSE_USE", day.minusDays(1))
        val holder = support.guest()
        InventoryListenersQuiescence.await(jdbc)

        // Duas noites normais, uma cortesia e uma de uso da casa na data (as quatro chegam e saem no dia seguinte).
        repeat(2) { seed.book(holder, support.reservation(type, normal, day, day.plusDays(1), adults = 1)) }
        seed.book(holder, support.reservation(type, courtesy, day, day.plusDays(1), adults = 1))
        seed.book(holder, support.reservation(type, houseUse, day, day.plusDays(1), adults = 1))
        // Day use: chega e sai na data, sem noite. A que saiu na data teve a noite na véspera, não a de hoje.
        seed.book(holder, support.reservation(type, normal, day, day, adults = 1))
        seed.book(holder, support.reservation(type, normal, day.minusDays(1), day, adults = 1))
        // Cancelada pela API, com o instante do cancelamento levado para o meio-dia da data, no fuso do hotel.
        val cancelled = seed.book(holder, support.reservation(type, normal, day, day.plusDays(1), adults = 1))
        with(billing) { support.cancel(cancelled, notes = "Desistiu da estatística").expect(200) }
        jdbc
            .sql("UPDATE reservations.reservation SET cancelled_at = :at WHERE id = :id AND status = 'CANCELLED'")
            .param("at", day.atTime(LocalTime.NOON).atZone(hotel.timezone()).toOffsetDateTime())
            .param("id", cancelled)
            .update() shouldBe 1
        // No-show: a reserva da data marcada como NO_SHOW (sem alocação, como o fechamento a deixa).
        val noShow = seed.book(holder, support.reservation(type, normal, day, day.plusDays(1), adults = 1))
        jdbc
            .sql("UPDATE reservations.reservation SET status = 'NO_SHOW' WHERE id = :id")
            .param("id", noShow)
            .update() shouldBe 1
        InventoryListenersQuiescence.await(jdbc)

        // Lançamentos da data: hospedagem (200,01), A&B (42,50), outras receitas (15,25); fora: imposto, taxa de
        // serviço (repasse) e o que é de outra data.
        val folio = billing.openFolio()
        post(folio, "ROOM_CHARGE", "150.00", day)
        post(folio, "ROOM_CHARGE", "50.01", day)
        post(folio, "FOOD_BEVERAGE", "30.00", day)
        post(folio, "FOOD_BEVERAGE", "12.50", day)
        post(folio, "LAUNDRY", "10.00", day)
        post(folio, "MISCELLANEOUS", "5.25", day)
        post(folio, "TOURISM_FEE", "7.00", day)
        post(folio, "SERVICE_CHARGE", "4.00", day)
        post(folio, "ROOM_CHARGE", "999.00", day.plusDays(1))

        val available = inventory(day, "total - out_of_order")
        val outOfOrder = inventory(day, "out_of_order")
        val snapshot = reporting.snapshotDailyStatistics(day)

        snapshot.businessDate shouldBe day
        // Só as duas normais: o day use, a que saiu na data, a cancelada e o no-show ficam fora das noites.
        snapshot.roomsSold shouldBe 2
        snapshot.roomsComplimentary shouldBe 1
        snapshot.roomsHouseUse shouldBe 1
        snapshot.roomsAvailable shouldBe available
        snapshot.roomsOutOfOrder shouldBe outOfOrder
        // Chegam na data: duas normais, cortesia, uso da casa e day use (o no-show e a cancelada não contam).
        snapshot.arrivals shouldBe 5
        // Saem na data: o day use e a da véspera.
        snapshot.departures shouldBe 2
        snapshot.noShows shouldBe 1
        snapshot.cancellations shouldBe 1
        snapshot.roomRevenue shouldBe Money.of("200.01")
        snapshot.foodBeverageRevenue shouldBe Money.of("42.50")
        snapshot.eventsRevenue shouldBe Money.ZERO
        snapshot.otherRevenue shouldBe Money.of("15.25")
        snapshot.occupancy shouldBe
            Percent.of(BigDecimal((2 + 1) * HUNDRED).divide(BigDecimal(available), 2, RoundingMode.HALF_UP))
        snapshot.adr shouldBe Money.of("100.00") // 200,01 / 2 = 100,005 -> HALF_EVEN
        snapshot.revpar shouldBe
            Money.of(BigDecimal("200.01").divide(BigDecimal(available), 2, RoundingMode.HALF_EVEN))

        // Gravado uma vez e devolvido como está, mesmo com um lançamento novo na data: não recalcula.
        post(folio, "ROOM_CHARGE", "500.00", day)
        reporting.snapshotDailyStatistics(day) shouldBe snapshot
        records(day) shouldBe 1
    }

    @Test
    fun `called twice and at the same time, the date keeps one record equal to the first`() {
        val day = hotel.currentBusinessDate().plusDays(PARALLEL_OFFSET)
        val pool = Executors.newFixedThreadPool(THREADS)
        val snapshots: List<DailyStatisticsSnapshot>
        try {
            val results =
                (1..THREADS).map { pool.submit<DailyStatisticsSnapshot> { reporting.snapshotDailyStatistics(day) } }
            snapshots = results.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            snapshots.toSet().size shouldBe 1
        } finally {
            pool.shutdownNow()
        }

        records(day) shouldBe 1
        reporting.snapshotDailyStatistics(day) shouldBe snapshots.first()
        records(day) shouldBe 1
    }

    @Test
    fun `a date without any movement has zeros and null derived figures`() {
        val day = LocalDate.of(EMPTY_YEAR, 3, 4)

        val snapshot = reporting.snapshotDailyStatistics(day)

        snapshot.roomsSold shouldBe 0
        snapshot.roomsComplimentary shouldBe 0
        snapshot.roomsHouseUse shouldBe 0
        snapshot.roomsAvailable shouldBe 0
        snapshot.arrivals shouldBe 0
        snapshot.noShows shouldBe 0
        snapshot.cancellations shouldBe 0
        snapshot.roomRevenue shouldBe Money.ZERO
        snapshot.otherRevenue shouldBe Money.ZERO
        snapshot.occupancy shouldBe null
        snapshot.adr shouldBe null
        snapshot.revpar shouldBe null
        records(day) shouldBe 1
    }

    private companion object {
        const val ROOMS = 8
        const val SCENARIO_OFFSET = 640L
        const val PARALLEL_OFFSET = 650L
        const val EMPTY_YEAR = 2052
        const val HUNDRED = 100L
        const val THREADS = 8
        const val TIMEOUT_SECONDS = 60L
    }
}
