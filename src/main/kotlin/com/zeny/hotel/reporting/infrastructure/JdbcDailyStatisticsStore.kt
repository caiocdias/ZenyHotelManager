package com.zeny.hotel.reporting.infrastructure

import com.zeny.hotel.reporting.DailyStatisticsSnapshot
import com.zeny.hotel.reporting.application.DailyStatisticsStore
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.UuidV7
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/** `reporting.daily_statistics` pelo `JdbcClient`: uma linha por data, só de inserção. */
@Component
class JdbcDailyStatisticsStore(
    private val jdbc: JdbcClient,
    private val clock: Clock,
) : DailyStatisticsStore {
    override fun find(date: LocalDate): DailyStatisticsSnapshot? =
        jdbc
            .sql("SELECT * FROM reporting.daily_statistics WHERE business_date = :date")
            .param("date", date)
            .query { rs, _ -> rs.snapshot() }
            .optional()
            .orElse(null)

    override fun insertIfAbsent(snapshot: DailyStatisticsSnapshot) {
        jdbc
            .sql(
                """
                INSERT INTO reporting.daily_statistics (id, business_date, rooms_sold, rooms_complimentary,
                    rooms_house_use, rooms_available, rooms_out_of_order, arrivals, departures, no_shows,
                    cancellations, room_revenue, food_beverage_revenue, events_revenue, other_revenue, occupancy, adr,
                    revpar, created_at)
                VALUES (:id, :date, :sold, :complimentary, :houseUse, :available, :outOfOrder, :arrivals, :departures,
                    :noShows, :cancellations, :room, :foodBeverage, :events, :other, :occupancy, :adr, :revpar,
                    :createdAt)
                ON CONFLICT (business_date) DO NOTHING
                """.trimIndent(),
            ).param("id", UuidV7.generate())
            .param("date", snapshot.businessDate)
            .param("sold", snapshot.roomsSold)
            .param("complimentary", snapshot.roomsComplimentary)
            .param("houseUse", snapshot.roomsHouseUse)
            .param("available", snapshot.roomsAvailable)
            .param("outOfOrder", snapshot.roomsOutOfOrder)
            .param("arrivals", snapshot.arrivals)
            .param("departures", snapshot.departures)
            .param("noShows", snapshot.noShows)
            .param("cancellations", snapshot.cancellations)
            .param("room", snapshot.roomRevenue.amount)
            .param("foodBeverage", snapshot.foodBeverageRevenue.amount)
            .param("events", snapshot.eventsRevenue.amount)
            .param("other", snapshot.otherRevenue.amount)
            .param("occupancy", snapshot.occupancy?.value)
            .param("adr", snapshot.adr?.amount)
            .param("revpar", snapshot.revpar?.amount)
            .param("createdAt", clock.instant().atOffset(ZoneOffset.UTC))
            .update()
    }

    private fun ResultSet.snapshot() =
        DailyStatisticsSnapshot(
            businessDate = getObject("business_date", LocalDate::class.java),
            roomsSold = getLong("rooms_sold"),
            roomsComplimentary = getLong("rooms_complimentary"),
            roomsHouseUse = getLong("rooms_house_use"),
            roomsAvailable = getLong("rooms_available"),
            roomsOutOfOrder = getLong("rooms_out_of_order"),
            arrivals = getLong("arrivals"),
            departures = getLong("departures"),
            noShows = getLong("no_shows"),
            cancellations = getLong("cancellations"),
            roomRevenue = Money.of(getBigDecimal("room_revenue")),
            foodBeverageRevenue = Money.of(getBigDecimal("food_beverage_revenue")),
            eventsRevenue = Money.of(getBigDecimal("events_revenue")),
            otherRevenue = Money.of(getBigDecimal("other_revenue")),
            occupancy = getBigDecimal("occupancy")?.let(Percent::of),
            adr = getBigDecimal("adr")?.let(Money::of),
            revpar = getBigDecimal("revpar")?.let(Money::of),
        )
}
