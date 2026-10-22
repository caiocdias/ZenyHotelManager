package com.zeny.hotel.reservations.infrastructure

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.rates.RatePlanCategory
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.SalesChannel
import com.zeny.hotel.reservations.BookingId
import com.zeny.hotel.reservations.MarketSegment
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationOrigin
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.RoomAssignmentId
import com.zeny.hotel.reservations.RoomBlockId
import com.zeny.hotel.reservations.application.DepositGuaranteedRow
import com.zeny.hotel.reservations.application.ListedAssignment
import com.zeny.hotel.reservations.application.OperationalList
import com.zeny.hotel.reservations.application.ReservationListRow
import com.zeny.hotel.reservations.application.ReservationQueryStore
import com.zeny.hotel.reservations.application.ReservationSearch
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Uma consulta pronta: o SQL (só com parâmetros nomeados) e os valores. */
data class ReservationQuery(
    val sql: String,
    val params: Map<String, Any?>,
)

/**
 * O SQL da busca e das listas de reservas (M4 E12), montado só com as condições pedidas (um filtro ausente não entra
 * na consulta, para o PostgreSQL escolher o índice dele em qualquer plano), sempre parametrizado, só no schema
 * `reservations`. Os índices de cada filtro: número (`reservation_code_uk`), número da agrupadora (`booking_code_uk`),
 * número da OTA (`booking_external_idx`), hóspedes (`reservation_guest_guest_idx` e `booking_holder_idx`), período
 * (`reservation_stay_period_idx`, gist sobre `daterange(arrival, departure, '[]')`), chegada
 * (`reservation_arrival_idx`, também a ordem do cursor), saída (`reservation_departure_idx`) e hospedadas
 * (`reservation_in_house_idx`).
 */
object ReservationQuerySql {
    private val SELECT =
        """
        SELECT r.id, r.code, r.booking_id, b.code AS booking_code, b.holder_guest_id, b.origin, b.channel,
            b.external_id, r.status, r.provisional_until, r.room_type_id, r.rate_plan_id, r.rate_plan_category,
            r.arrival, r.departure, r.adults, cardinality(r.child_ages) AS children, r.segment, r.total, r.folio_id,
            r.block_id, r.version,
            (SELECT g.guest_id FROM reservations.reservation_guest g
             WHERE g.reservation_id = r.id AND g.is_primary) AS primary_guest_id,
            a.id AS assignment_id, a.room_id, a.do_not_move, a.reallocation_required_at
        """.trimIndent()

    private val FROM =
        """
        FROM reservations.reservation r
        JOIN reservations.booking b ON b.id = r.booking_id
        LEFT JOIN reservations.room_assignment a ON a.reservation_id = r.id AND a.released_at IS NULL
        """.trimIndent()

    /** As situações que as listas de chegadas e saídas mostram: todas menos a cancelada e o no-show. */
    private val LISTED = ReservationStatus.entries - setOf(ReservationStatus.CANCELLED, ReservationStatus.NO_SHOW)

    fun search(
        search: ReservationSearch,
        after: Pair<LocalDate, ReservationId>?,
        limit: Int,
    ): ReservationQuery {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any?>()
        search.code?.let {
            conditions += "r.code = :code"
            params["code"] = it
        }
        search.bookingCode?.let {
            conditions += "b.code = :bookingCode"
            params["bookingCode"] = it
        }
        search.externalId?.let {
            conditions += "b.external_id = :externalId"
            params["externalId"] = it
        }
        search.guestIds?.let {
            conditions +=
                """
                r.id IN (SELECT g.reservation_id FROM reservations.reservation_guest g
                         WHERE g.guest_id = ANY (CAST(:guestIds AS uuid[]))
                         UNION
                         SELECT h.id FROM reservations.reservation h
                         JOIN reservations.booking hb ON hb.id = h.booking_id
                         WHERE hb.holder_guest_id = ANY (CAST(:guestIds AS uuid[])))
                """.trimIndent()
            params["guestIds"] = array(it.map(GuestId::value))
        }
        if (search.from != null || search.to != null) {
            conditions +=
                "daterange(r.arrival, r.departure, '[]') && daterange(CAST(:from AS date), CAST(:to AS date), '[]')"
            params["from"] = search.from
            params["to"] = search.to
        }
        if (search.statuses.isNotEmpty()) conditions += "r.status IN (${statusList(search.statuses)})"
        return query("arrival", conditions, params, after, limit)
    }

    fun operational(
        list: OperationalList,
        date: LocalDate,
        businessDate: LocalDate,
        after: Pair<LocalDate, ReservationId>?,
        limit: Int,
    ): ReservationQuery {
        val current = date == businessDate
        val params = mapOf<String, Any?>("date" to date)
        val (sortColumn, condition) =
            when (list) {
                OperationalList.ARRIVALS -> {
                    "arrival" to "r.arrival = :date AND r.status IN (${statusList(LISTED)})"
                }

                OperationalList.DEPARTURES -> {
                    val due = "r.departure = :date AND r.status IN (${statusList(LISTED)})"
                    // Saída pendente: a hospedada com a saída já passada (só na lista da data operacional corrente).
                    "departure" to if (current) "($due OR (r.status = 'IN_HOUSE' AND r.departure < :date))" else due
                }

                OperationalList.IN_HOUSE -> {
                    val departure = if (current) "" else " AND r.departure >= :date"
                    "arrival" to "r.status = 'IN_HOUSE' AND r.arrival <= :date$departure"
                }

                OperationalList.EXPECTED_NO_SHOWS -> {
                    val arrival = if (current) "r.arrival <= :date" else "r.arrival = :date"
                    "arrival" to "r.status = 'CONFIRMED' AND $arrival"
                }
            }
        return query(sortColumn, listOf(condition), params, after, limit)
    }

    fun depositGuaranteed(limit: Int): ReservationQuery =
        ReservationQuery(
            """
            $SELECT, r.guarantee_deposit_percent, r.guaranteed_at, r.arrival AS sort_date
            $FROM
            WHERE r.status = 'CONFIRMED' AND r.guarantee_type = 'DEPOSIT' AND r.guarantee_method = 'DEPOSIT'
            ORDER BY r.arrival, r.id
            LIMIT :limit
            """.trimIndent(),
            mapOf("limit" to limit),
        )

    private fun query(
        sortColumn: String,
        conditions: List<String>,
        params: Map<String, Any?>,
        after: Pair<LocalDate, ReservationId>?,
        limit: Int,
    ): ReservationQuery {
        val where = conditions.toMutableList()
        val all = params.toMutableMap()
        after?.let { (date, id) ->
            where += "(r.$sortColumn, r.id) > (CAST(:afterDate AS date), CAST(:afterId AS uuid))"
            all["afterDate"] = date
            all["afterId"] = id.value
        }
        all["limit"] = limit
        val whereClause = if (where.isEmpty()) "" else where.joinToString("\n  AND ", "WHERE ")
        return ReservationQuery(
            """
            |$SELECT, r.$sortColumn AS sort_date
            |$FROM
            |$whereClause
            |ORDER BY r.$sortColumn, r.id
            |LIMIT :limit
            """.trimMargin(),
            all,
        )
    }

    private fun array(values: Collection<Any>): String = values.joinToString(",", "{", "}")

    /**
     * As situações como literais no SQL (`'CONFIRMED', 'IN_HOUSE'`): vêm só do `enum` (nunca de texto do usuário), e
     * o literal deixa o PostgreSQL usar os índices parciais por situação em qualquer plano (um parâmetro não deixaria).
     */
    private fun statusList(statuses: Collection<ReservationStatus>): String =
        statuses.sorted().joinToString(", ") { "'${it.name}'" }
}

/** A busca e as listas de reservas pelo `JdbcClient` ([ReservationQuerySql]). */
@Component
class JdbcReservationQueries(
    private val jdbc: JdbcClient,
) : ReservationQueryStore {
    override fun search(
        search: ReservationSearch,
        after: Pair<LocalDate, ReservationId>?,
        limit: Int,
    ): List<ReservationListRow> = rows(ReservationQuerySql.search(search, after, limit))

    override fun operational(
        list: OperationalList,
        date: LocalDate,
        businessDate: LocalDate,
        after: Pair<LocalDate, ReservationId>?,
        limit: Int,
    ): List<ReservationListRow> = rows(ReservationQuerySql.operational(list, date, businessDate, after, limit))

    override fun depositGuaranteed(limit: Int): List<DepositGuaranteedRow> {
        val query = ReservationQuerySql.depositGuaranteed(limit)
        return jdbc
            .sql(query.sql)
            .params(query.params)
            .query { rs, _ ->
                DepositGuaranteedRow(
                    row = row(rs),
                    depositPercent = Percent.of(rs.getBigDecimal("guarantee_deposit_percent")),
                    guaranteedAt = rs.getObject("guaranteed_at", OffsetDateTime::class.java).toInstant(),
                )
            }.list()
    }

    private fun rows(query: ReservationQuery): List<ReservationListRow> =
        jdbc
            .sql(query.sql)
            .params(query.params)
            .query { rs, _ -> row(rs) }
            .list()

    private fun row(rs: ResultSet): ReservationListRow {
        val assignmentId = rs.getObject("assignment_id", UUID::class.java)
        return ReservationListRow(
            id = ReservationId(rs.uuid("id")),
            code = DocumentNumber.parse(rs.getString("code")),
            bookingId = BookingId(rs.uuid("booking_id")),
            bookingCode = DocumentNumber.parse(rs.getString("booking_code")),
            holderGuestId = GuestId(rs.uuid("holder_guest_id")),
            primaryGuestId = rs.getObject("primary_guest_id", UUID::class.java)?.let(::GuestId),
            origin = ReservationOrigin.valueOf(rs.getString("origin")),
            channel = SalesChannel.valueOf(rs.getString("channel")),
            externalId = rs.getString("external_id"),
            status = ReservationStatus.valueOf(rs.getString("status")),
            provisionalUntil = rs.getObject("provisional_until", OffsetDateTime::class.java)?.toInstant(),
            roomTypeId = RoomTypeId(rs.uuid("room_type_id")),
            ratePlanId = RatePlanId(rs.uuid("rate_plan_id")),
            ratePlanCategory = RatePlanCategory.valueOf(rs.getString("rate_plan_category")),
            arrival = rs.getObject("arrival", LocalDate::class.java),
            departure = rs.getObject("departure", LocalDate::class.java),
            adults = rs.getInt("adults"),
            children = rs.getInt("children"),
            segment = MarketSegment.valueOf(rs.getString("segment")),
            total = Money.of(rs.getBigDecimal("total")),
            folioId = FolioId(rs.uuid("folio_id")),
            blockId = rs.getObject("block_id", UUID::class.java)?.let(::RoomBlockId),
            assignment =
                assignmentId?.let {
                    ListedAssignment(
                        id = RoomAssignmentId(it),
                        roomId = RoomId(rs.uuid("room_id")),
                        roomNumber = null,
                        doNotMove = rs.getBoolean("do_not_move"),
                        reallocationRequired = rs.getObject("reallocation_required_at") != null,
                    )
                },
            version = rs.getLong("version"),
            sortDate = rs.getObject("sort_date", LocalDate::class.java),
        )
    }
}

private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)
