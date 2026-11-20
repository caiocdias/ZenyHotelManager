package com.zeny.hotel.reporting

import com.zeny.hotel.files.FilesTestConfiguration
import com.zeny.hotel.files.FilesTestSupport
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reporting.application.ReportingQueries
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.InventoryListenersQuiescence
import com.zeny.hotel.testsupport.pdfText
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Ataques ao snapshot e ao PDF do arquivo (M6 E23): sinal da receita, grupos que entram em "outras", taxa de serviço
 * fora da receita, borda de meia-noite dos cancelamentos e ausência de nome de hóspede no PDF. Datas só desta classe:
 * 2059 a 2061 para os lançamentos (sem inventário, então os totais são exatos) e os deslocamentos 650, 660 e 661 da
 * data operacional para as reservas.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class, FilesTestConfiguration::class)
@Suppress("LongParameterList") // as portas e o apoio do cenário
class DailyStatisticsAttackTest(
    @Autowired iam: IamTestSupport,
    @Autowired private val iamSupport: IamTestSupport,
    @Autowired private val files: FilesTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired private val reporting: ReportingApi,
    @Autowired private val queries: ReportingQueries,
    @Autowired private val transactionManager: PlatformTransactionManager,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val seed = ReportingTestSupport(iam, support)
    private val billing = support.billing

    /** Lançamento normal; devolve o id. */
    private fun regular(
        folio: UUID,
        key: String,
        amount: String,
        date: LocalDate,
        serviceChargeFor: UUID? = null,
    ): UUID {
        val id = UuidV7.generate()
        jdbc
            .sql(
                """
                INSERT INTO billing.posting (id, folio_id, transaction_code_id, posting_type, quantity, unit_price,
                    amount, description, source, business_date, posted_at, service_charge_for_id, service_charge_rate)
                VALUES (:id, :folio, :code, :type, 1, :amount, :amount, 'Ataque da estatística diária', 'MANUAL',
                    :date, now(), :item, :rate)
                """.trimIndent(),
            ).param("id", id)
            .param("folio", folio)
            .param("code", billing.codeId(key))
            .param("type", if (serviceChargeFor == null) "REGULAR" else "SERVICE_CHARGE")
            .param("amount", BigDecimal(amount))
            .param("date", date)
            .param("item", serviceChargeFor)
            .param("rate", serviceChargeFor?.let { BigDecimal("10") })
            .update()
        return id
    }

    /** Correção (ESTORNO, ajuste ou saída de transferência) de [corrects], com o valor [amount] (negativo). */
    private fun correction(
        type: String,
        folio: UUID,
        corrects: UUID,
        key: String,
        amount: String,
        date: LocalDate,
    ): UUID {
        val id = UuidV7.generate()
        jdbc
            .sql(
                """
                INSERT INTO billing.posting (id, folio_id, transaction_code_id, posting_type, quantity, unit_price,
                    amount, description, source, business_date, posted_at, corrects_id, reason)
                VALUES (:id, :folio, :code, :type, 1, :amount, :amount, 'Correção da estatística diária', 'MANUAL',
                    :date, now(), :corrects, 'motivo do ataque')
                """.trimIndent(),
            ).param("id", id)
            .param("folio", folio)
            .param("code", billing.codeId(key))
            .param("type", type)
            .param("amount", BigDecimal(amount))
            .param("date", date)
            .param("corrects", corrects)
            .update()
        return id
    }

    @Test
    fun `negative postings of the date enter with their sign in the room revenue`() {
        val day = LocalDate.of(2059, 3, 5)
        val a = billing.openFolio()
        val b = billing.openFolio()
        val charge = regular(a, "ROOM_CHARGE", "100.00", day)
        correction("ADJUSTMENT", a, charge, "ROOM_CHARGE", "-30.00", day)
        val moved = regular(a, "ROOM_CHARGE", "60.00", day)
        // A saída e a entrada precisam estar na mesma transação (o banco confere o par no commit).
        TransactionTemplate(transactionManager).executeWithoutResult {
            val out = correction("TRANSFER_OUT", a, moved, "ROOM_CHARGE", "-60.00", day)
            jdbc
                .sql(
                    """
                    INSERT INTO billing.posting (id, folio_id, transaction_code_id, posting_type, quantity, unit_price,
                        amount, description, source, business_date, posted_at, transferred_from_id)
                    VALUES (:id, :folio, :code, 'TRANSFER_IN', 1, 60.00, 60.00, 'Entrada da estatística', 'MANUAL', :date,
                        now(), :out)
                    """.trimIndent(),
                ).param("id", UuidV7.generate())
                .param("folio", b)
                .param("code", billing.codeId("ROOM_CHARGE"))
                .param("date", day)
                .param("out", out)
                .update() shouldBe 1
        }
        val cancelled = regular(a, "ROOM_CHARGE", "25.00", day)
        correction("REVERSAL", a, cancelled, "ROOM_CHARGE", "-25.00", day)

        // 100 - 30 + (60 - 60 + 60) + (25 - 25) = 130; um erro de sinal ou um filtro de tipo muda o total.
        reporting.snapshotDailyStatistics(day).roomRevenue shouldBe Money.of("130.00")
    }

    @Test
    fun `fees go to other revenue, and a service charge linked to a food item stays out of every revenue`() {
        val day = LocalDate.of(2060, 4, 6)
        val folio = billing.openFolio()
        regular(folio, "NO_SHOW_FEE", "20.00", day)
        val item = regular(folio, "FOOD_BEVERAGE", "50.00", day)
        regular(folio, "SERVICE_CHARGE", "5.00", day, serviceChargeFor = item)

        val snapshot = reporting.snapshotDailyStatistics(day)

        snapshot.otherRevenue shouldBe Money.of("20.00")
        snapshot.foodBeverageRevenue shouldBe Money.of("50.00")
        snapshot.roomRevenue shouldBe Money.ZERO
    }

    @Test
    @Suppress("LongMethod") // as três reservas e as três datas
    fun `a cancellation at the hotel midnight counts in the day it starts, from inclusive and to exclusive`() {
        val day = hotel.currentBusinessDate().plusDays(MIDNIGHT_OFFSET)
        val zone = hotel.timezone()
        val (type, _) = support.inventory.roomTypeWithRooms(ROOMS, day)
        val plan = seed.plan(type, "NORMAL", day.minusDays(1))
        val holder = support.guest()
        InventoryListenersQuiescence.await(jdbc)

        fun cancellations(date: LocalDate) =
            queries
                .dailyStatisticsInput(
                    date,
                    date.atStartOfDay(zone).toInstant(),
                    date.plusDays(1).atStartOfDay(zone).toInstant(),
                ).cancellations
        val before = listOf(day.minusDays(1), day, day.plusDays(1)).map { cancellations(it) }

        fun cancelAt(at: java.time.OffsetDateTime) {
            val id = seed.book(holder, support.reservation(type, plan, day, day.plusDays(1), adults = 1))
            with(billing) { support.cancel(id, notes = "Meia-noite").expect(200) }
            jdbc
                .sql("UPDATE reservations.reservation SET cancelled_at = :at WHERE id = :id AND status = 'CANCELLED'")
                .param("at", at)
                .param("id", id)
                .update() shouldBe 1
        }
        cancelAt(day.atStartOfDay(zone).toOffsetDateTime())
        cancelAt(day.plusDays(1).atStartOfDay(zone).toOffsetDateTime())
        cancelAt(day.atStartOfDay(zone).toOffsetDateTime().minusNanos(NANOS_PER_MICRO))
        InventoryListenersQuiescence.await(jdbc)

        // Cada data conta exatamente a sua: a do 00:00 exato, a do 00:00 do dia seguinte e a da véspera 23:59:59.999999.
        reporting.snapshotDailyStatistics(day.minusDays(1)).cancellations shouldBe before[0] + 1
        reporting.snapshotDailyStatistics(day).cancellations shouldBe before[1] + 1
        reporting.snapshotDailyStatistics(day.plusDays(1)).cancellations shouldBe before[2] + 1
    }

    @Test
    fun `the archived pdf carries the open balance of a checked-out stay but never the guest name`() {
        val day = hotel.currentBusinessDate().plusDays(PDF_OFFSET)
        val (type, _) = support.inventory.roomTypeWithRooms(ROOMS, day)
        val plan = seed.plan(type, "NORMAL", day.minusDays(1))
        val marker = "Zzmarcadorpdf Ataque"
        val holder =
            UUID.fromString(
                support.guests
                    .createGuest(
                        support.guests.createBody(
                            profile = support.guests.profileBody(fullName = marker),
                            documents = emptyList(),
                        ),
                    )["id"]
                    .asString(),
            )
        InventoryListenersQuiescence.await(jdbc)
        val reservation = seed.book(holder, support.reservation(type, plan, day, day.plusDays(1), adults = 1))
        support.forceCheckedOut(reservation)
        val folio = billing.openFolio("RESERVATION", reservation)
        regular(folio, "ROOM_CHARGE", "77.77", hotel.currentBusinessDate())
        val code =
            jdbc
                .sql("SELECT reservation_code FROM reservations.report_reservations WHERE reservation_id = :id")
                .param("id", reservation)
                .query(String::class.java)
                .single()

        val archived = reporting.archiveDayReports(LocalDate.of(2056, 9, 9)).single()

        val (url, _) = files.downloadUrlOk(archived.fileId, iamSupport.asAdmin())
        val text = pdfText(files.serverGet(url).contentAsByteArray)
        // A conta aberta da reserva encerrada aparece (prova que o PDF tem a seção), sem o nome do titular.
        text shouldContain code
        text shouldNotContain "Zzmarcadorpdf"
        text shouldNotContain "Ataque"
    }

    private companion object {
        const val ROOMS = 8
        const val MIDNIGHT_OFFSET = 660L
        const val PDF_OFFSET = 650L
        const val NANOS_PER_MICRO = 1000L
    }
}
