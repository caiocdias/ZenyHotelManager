package com.zeny.hotel.reporting

import com.zeny.hotel.reporting.application.DayPanel
import com.zeny.hotel.reporting.application.DayPanelRooms
import com.zeny.hotel.reporting.application.DayReportModel
import com.zeny.hotel.reporting.application.EndOfDayPending
import com.zeny.hotel.reporting.application.PendingCashierShift
import com.zeny.hotel.reporting.application.PendingFnrhSubmission
import com.zeny.hotel.reporting.application.PendingFolio
import com.zeny.hotel.shared.Money
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** O modelo do PDF da data (M6 E23, D3): sem campo de hóspede e só com as folhas que o `printing` aceita. */
class DayReportModelTest {
    private val date = LocalDate.of(2056, 7, 1)

    private val model =
        DayReportModel.of(
            DailyStatisticsSnapshot(
                date,
                1,
                2,
                3,
                4,
                5,
                6,
                7,
                8,
                9,
                Money.of("10.00"),
                Money.of("11.00"),
                Money.of("0.00"),
                Money.of("12.00"),
                null,
                null,
                null,
            ),
            DayPanel(date, emptyList(), DayPanelRooms(1, 2, 3, 4, 5, 6, 7, 8, 9)),
            EndOfDayPending(
                listOf(PendingFolio(UUID.randomUUID(), "C-1", UUID.randomUUID(), "R-1", date, Money.of("5.00"))),
                listOf(PendingCashierShift(UUID.randomUUID(), "T-1", "Caixa", UUID.randomUUID(), date, Instant.EPOCH)),
                listOf(PendingFnrhSubmission("CHECK_IN", "FAILED", UUID.randomUUID(), "R-1", 2, null, Instant.EPOCH)),
            ),
        )

    private fun keys(value: Any?): List<String> =
        when (value) {
            is Map<*, *> -> value.flatMap { (key, inner) -> listOf(key.toString()) + keys(inner) }
            is List<*> -> value.flatMap(::keys)
            else -> emptyList()
        }

    private fun leaves(value: Any?): List<Any?> =
        when (value) {
            is Map<*, *> -> value.values.flatMap(::leaves)
            is List<*> -> value.flatMap(::leaves)
            else -> listOf(value)
        }

    @Test
    fun `the model has no guest field`() {
        keys(model)
            .filter { Regex("guest|holder|hospede|name", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            .shouldBeEmpty()
    }

    @Test
    fun `the leaves are only the types the printing accepts, with the ids as text`() {
        leaves(model).forEach {
            (
                it == null || it is String || it is Number || it is Boolean || it is LocalDate || it is Instant ||
                    it is Money || it is com.zeny.hotel.shared.Percent
            ) shouldBe true
        }
    }
}
