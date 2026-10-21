package com.zeny.hotel.reservations.infrastructure

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.rates.CancellationPenaltyType
import com.zeny.hotel.rates.CancellationPolicyId
import com.zeny.hotel.rates.GuaranteePolicyId
import com.zeny.hotel.rates.GuaranteeType
import com.zeny.hotel.rates.PackageCharge
import com.zeny.hotel.rates.PackageId
import com.zeny.hotel.rates.PackagePostingRhythm
import com.zeny.hotel.rates.PackagePricing
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.SalesChannel
import com.zeny.hotel.reservations.BookingId
import com.zeny.hotel.reservations.BookingSummary
import com.zeny.hotel.reservations.CancellationTerms
import com.zeny.hotel.reservations.GuaranteeTerms
import com.zeny.hotel.reservations.MarketSegment
import com.zeny.hotel.reservations.NightlyRateSummary
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationOrigin
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.StayGuestSummary
import com.zeny.hotel.reservations.application.BookingStore
import com.zeny.hotel.reservations.application.GuestRepointing
import com.zeny.hotel.reservations.domain.InventoryKey
import com.zeny.hotel.reservations.domain.InventoryUsage
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.UserId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * As reservas agrupadoras e as reservas pelo `JdbcClient`, só no schema `reservations` e sempre parametrizado. Os
 * hóspedes e as noites de uma reserva entram num comando cada (`jsonb_to_recordset` sobre o JSON das linhas); os
 * vetores (`unnest`/`ANY`) vão em texto (`{a,b}`) convertido pelo `CAST`.
 */
@Component
@Suppress("TooManyFunctions") // gravação, leituras, reapontamento, estadia ativa e o uso do inventário
class JdbcBookingStore(
    private val jdbc: JdbcClient,
    private val jsonMapper: JsonMapper,
) : BookingStore {
    override fun lockGuests(
        guestIds: Collection<GuestId>,
        exclusive: Boolean,
    ) {
        val function = if (exclusive) "pg_advisory_xact_lock" else "pg_advisory_xact_lock_shared"
        guestIds.map { it.value.toString() }.distinct().sorted().forEach { id ->
            jdbc
                .sql("SELECT $function(hashtext('reservations.guest'), hashtext(:id))")
                .param("id", id)
                .query { _, _ -> 0 }
                .list()
        }
    }

    override fun insert(booking: BookingSummary) {
        jdbc
            .sql(
                """
                INSERT INTO reservations.booking (id, code, holder_guest_id, origin, channel, external_id, version,
                    created_at, created_by, updated_at)
                VALUES (:id, :code, :holder, :origin, :channel, :externalId, :version, :createdAt, :createdBy,
                    :createdAt)
                """.trimIndent(),
            ).param("id", booking.id.value)
            .param("code", booking.code.toString())
            .param("holder", booking.holderGuestId.value)
            .param("origin", booking.origin.name)
            .param("channel", booking.channel.name)
            .param("externalId", booking.externalId)
            .param("version", booking.version)
            .param("createdAt", booking.createdAt.utc())
            .param("createdBy", booking.createdBy?.value)
            .update()
        booking.reservations.forEach(::insertReservation)
    }

    @Suppress("LongMethod") // um INSERT com as colunas da reserva e os termos copiados
    private fun insertReservation(reservation: ReservationSummary) {
        val cancellation = reservation.cancellationTerms
        val guarantee = reservation.guaranteeTerms
        jdbc
            .sql(
                """
                INSERT INTO reservations.reservation (id, code, booking_id, status, provisional_until, room_type_id,
                    rate_plan_id, arrival, departure, adults, child_ages, segment, special_requests,
                    cancellation_policy_id, cancellation_policy_name, cancellation_policy_version,
                    cancellation_deadline_hours, cancellation_penalty_type, cancellation_penalty_percent,
                    cancellation_penalty_nights, cancellation_penalty_amount, guarantee_policy_id,
                    guarantee_policy_name,
                    guarantee_policy_version, guarantee_type, guarantee_deposit_percent, guarantee_due_days_before,
                    room_total, additional_packages_total, total, folio_id, approval_id, approved_by, version,
                    created_at, created_by, updated_at)
                VALUES (:id, :code, :bookingId, :status, :provisionalUntil, :roomTypeId, :ratePlanId, :arrival,
                    :departure, :adults, CAST(:childAges AS integer[]), :segment, :specialRequests, :cancellationId,
                    :cancellationName, :cancellationVersion, :deadlineHours, :penaltyType, :penaltyPercent,
                    :penaltyNights, :penaltyAmount, :guaranteeId, :guaranteeName, :guaranteeVersion, :guaranteeType,
                    :depositPercent, :dueDaysBefore, :roomTotal, :additionalTotal, :total, :folioId, :approvalId,
                    :approvedBy, :version, :createdAt, :createdBy, :createdAt)
                """.trimIndent(),
            ).param("id", reservation.id.value)
            .param("code", reservation.code.toString())
            .param("bookingId", reservation.bookingId.value)
            .param("status", reservation.status.name)
            .param("provisionalUntil", reservation.provisionalUntil?.utc())
            .param("roomTypeId", reservation.roomTypeId.value)
            .param("ratePlanId", reservation.ratePlanId.value)
            .param("arrival", reservation.arrival)
            .param("departure", reservation.departure)
            .param("adults", reservation.adults)
            .param("childAges", reservation.childAges.joinToString(",", "{", "}"))
            .param("segment", reservation.segment.name)
            .param("specialRequests", reservation.specialRequests)
            .param("cancellationId", cancellation.policyId.value)
            .param("cancellationName", cancellation.name)
            .param("cancellationVersion", cancellation.version)
            .param("deadlineHours", cancellation.deadlineHours)
            .param("penaltyType", cancellation.penaltyType.name)
            .param("penaltyPercent", cancellation.penaltyPercent?.value)
            .param("penaltyNights", cancellation.penaltyNights)
            .param("penaltyAmount", cancellation.penaltyAmount?.amount)
            .param("guaranteeId", guarantee.policyId.value)
            .param("guaranteeName", guarantee.name)
            .param("guaranteeVersion", guarantee.version)
            .param("guaranteeType", guarantee.type.name)
            .param("depositPercent", guarantee.depositPercent?.value)
            .param("dueDaysBefore", guarantee.dueDaysBefore)
            .param("roomTotal", reservation.roomTotal.amount)
            .param("additionalTotal", reservation.additionalPackagesTotal.amount)
            .param("total", reservation.total.amount)
            .param("folioId", reservation.folioId.value)
            .param("approvalId", reservation.approvalId)
            .param("approvedBy", reservation.approvedBy?.value)
            .param("version", reservation.version)
            .param("createdAt", reservation.createdAt.utc())
            .param("createdBy", reservation.createdBy?.value)
            .update()
        insertGuests(reservation)
        insertNights(reservation)
    }

    private fun insertGuests(reservation: ReservationSummary) {
        jdbc
            .sql(
                """
                INSERT INTO reservations.reservation_guest (reservation_id, guest_id, is_primary, position)
                SELECT :id, g.guest_id, g.is_primary, g.position
                FROM jsonb_to_recordset(CAST(:guests AS jsonb))
                    AS g(guest_id uuid, is_primary boolean, position smallint)
                """.trimIndent(),
            ).param("id", reservation.id.value)
            .param(
                "guests",
                jsonMapper.writeValueAsString(
                    reservation.guests.mapIndexed { index, guest ->
                        mapOf(
                            "guest_id" to guest.guestId.toString(),
                            "is_primary" to guest.primary,
                            "position" to index,
                        )
                    },
                ),
            ).update()
    }

    private fun insertNights(reservation: ReservationSummary) {
        jdbc
            .sql(
                """
                INSERT INTO reservations.nightly_rate (reservation_id, stay_date, rate_plan_id, calculated_room_amount,
                    room_amount, included_packages_amount, additional_packages_amount, amount, overridden,
                    discount_percent, packages)
                SELECT :id, n.stay_date, n.rate_plan_id, n.calculated_room_amount, n.room_amount,
                    n.included_packages_amount, n.additional_packages_amount, n.amount, n.overridden,
                    n.discount_percent, n.packages
                FROM jsonb_to_recordset(CAST(:nights AS jsonb)) AS n(stay_date date, rate_plan_id uuid,
                    calculated_room_amount numeric, room_amount numeric, included_packages_amount numeric,
                    additional_packages_amount numeric, amount numeric, overridden boolean, discount_percent numeric,
                    packages jsonb)
                """.trimIndent(),
            ).param("id", reservation.id.value)
            .param("nights", jsonMapper.writeValueAsString(reservation.nightlyRates.map(::storedNight)))
            .update()
    }

    override fun booking(id: BookingId): BookingSummary? {
        val row =
            jdbc
                .sql("SELECT * FROM reservations.booking WHERE id = :id")
                .param("id", id.value)
                .query { rs, _ -> BookingRow.of(rs) }
                .optional()
                .orElse(null) ?: return null
        val reservations =
            jdbc
                .sql("SELECT id FROM reservations.reservation WHERE booking_id = :id ORDER BY id")
                .param("id", id.value)
                .query { rs, _ -> ReservationId(rs.uuid("id")) }
                .list()
                .map { checkNotNull(reservation(it)) }
        return BookingSummary(
            id = id,
            code = DocumentNumber.parse(row.code),
            holderGuestId = row.holder,
            origin = row.origin,
            channel = row.channel,
            externalId = row.externalId,
            reservations = reservations,
            version = row.version,
            createdAt = row.createdAt,
            createdBy = row.createdBy,
        )
    }

    override fun reservation(id: ReservationId): ReservationSummary? {
        val guests = guestsOf(listOf(id))[id].orEmpty()
        val nights =
            jdbc
                .sql("SELECT * FROM reservations.nightly_rate WHERE reservation_id = :id ORDER BY stay_date")
                .param("id", id.value)
                .query { rs, _ -> night(rs) }
                .list()
        return jdbc
            .sql("SELECT * FROM reservations.reservation WHERE id = :id")
            .param("id", id.value)
            .query { rs, _ -> reservation(rs, guests, nights) }
            .optional()
            .orElse(null)
    }

    override fun repointGuest(
        merged: GuestId,
        target: GuestId,
        now: Instant,
    ): GuestRepointing {
        if (merged == target) return GuestRepointing(emptyList(), emptyMap())
        val bookings =
            jdbc
                .sql(
                    """
                    UPDATE reservations.booking SET holder_guest_id = :target, version = version + 1, updated_at = :now
                    WHERE holder_guest_id = :merged
                    RETURNING id
                    """.trimIndent(),
                ).param("target", target.value)
                .param("now", now.utc())
                .param("merged", merged.value)
                .query { rs, _ -> BookingId(rs.uuid("id")) }
                .list()
                .sortedBy { it.value.toString() }
        val affected =
            jdbc
                .sql(
                    "SELECT DISTINCT reservation_id FROM reservations.reservation_guest WHERE guest_id = :merged " +
                        "ORDER BY reservation_id",
                ).param("merged", merged.value)
                .query { rs, _ -> ReservationId(rs.uuid("reservation_id")) }
                .list()
        val changes = if (affected.isEmpty()) emptyMap() else repointStayGuests(merged, target, affected, now)
        return GuestRepointing(bookings, changes)
    }

    /** Reponta os hóspedes das reservas [affected]: quem já tem os dois fica com um só, principal se algum era. */
    private fun repointStayGuests(
        merged: GuestId,
        target: GuestId,
        affected: List<ReservationId>,
        now: Instant,
    ): Map<ReservationId, Pair<List<GuestId>, List<GuestId>>> {
        val before = guestsOf(affected)
        val primaryRemoved =
            jdbc
                .sql(
                    """
                    DELETE FROM reservations.reservation_guest m USING reservations.reservation_guest s
                    WHERE m.guest_id = :merged AND s.reservation_id = m.reservation_id AND s.guest_id = :target
                    RETURNING m.reservation_id, m.is_primary
                    """.trimIndent(),
                ).param("merged", merged.value)
                .param("target", target.value)
                .query { rs, _ -> rs.uuid("reservation_id") to rs.getBoolean("is_primary") }
                .list()
                .filter { it.second }
                .map { it.first }
        if (primaryRemoved.isNotEmpty()) {
            jdbc
                .sql(
                    "UPDATE reservations.reservation_guest SET is_primary = true " +
                        "WHERE reservation_id = ANY (CAST(:ids AS uuid[])) AND guest_id = :target",
                ).param("ids", array(primaryRemoved))
                .param("target", target.value)
                .update()
        }
        jdbc
            .sql("UPDATE reservations.reservation_guest SET guest_id = :target WHERE guest_id = :merged")
            .param("target", target.value)
            .param("merged", merged.value)
            .update()
        jdbc
            .sql(
                "UPDATE reservations.reservation SET version = version + 1, updated_at = :now " +
                    "WHERE id = ANY (CAST(:ids AS uuid[]))",
            ).param("now", now.utc())
            .param("ids", array(affected.map { it.value }))
            .update()
        val after = guestsOf(affected)
        return affected.associateWith { id ->
            before[id].orEmpty().map { it.guestId } to after[id].orEmpty().map { it.guestId }
        }
    }

    override fun hasActiveStay(guestIds: Collection<GuestId>): Boolean =
        jdbc
            .sql(
                """
                SELECT EXISTS (
                    SELECT 1 FROM reservations.reservation r
                    JOIN reservations.booking b ON b.id = r.booking_id
                    WHERE r.status IN ('PROVISIONAL', 'CONFIRMED', 'IN_HOUSE')
                      AND (b.holder_guest_id = ANY (CAST(:ids AS uuid[]))
                           OR EXISTS (SELECT 1 FROM reservations.reservation_guest g
                                      WHERE g.reservation_id = r.id AND g.guest_id = ANY (CAST(:ids AS uuid[]))))
                )
                """.trimIndent(),
            ).param("ids", array(guestIds.map { it.value }))
            .query(Boolean::class.java)
            .single()

    override fun reservationsOfGuests(guestIds: Collection<GuestId>): List<ReservationId> =
        jdbc
            .sql(
                """
                SELECT r.id FROM reservations.reservation r
                JOIN reservations.booking b ON b.id = r.booking_id
                WHERE b.holder_guest_id = ANY (CAST(:ids AS uuid[]))
                   OR EXISTS (SELECT 1 FROM reservations.reservation_guest g
                              WHERE g.reservation_id = r.id AND g.guest_id = ANY (CAST(:ids AS uuid[])))
                ORDER BY r.id
                """.trimIndent(),
            ).param("ids", array(guestIds.map { it.value }))
            .query { rs, _ -> ReservationId(rs.uuid("id")) }
            .list()

    override fun soldUnits(
        roomTypeIds: Collection<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
    ): Map<InventoryKey, InventoryUsage> {
        if (roomTypeIds.isEmpty()) return emptyMap()
        return jdbc
            .sql(
                """
                SELECT r.room_type_id, CAST(d AS date) AS stay_date, count(*) AS sold
                FROM reservations.reservation r
                CROSS JOIN generate_series(CAST(r.arrival AS timestamp),
                                           CAST(r.departure AS timestamp) - interval '1 day',
                                           interval '1 day') AS d
                WHERE r.room_type_id = ANY (CAST(:types AS uuid[]))
                  AND r.status IN ('PROVISIONAL', 'CONFIRMED', 'IN_HOUSE')
                  AND r.departure > r.arrival
                  AND r.arrival <= :to AND r.departure > :from
                  AND CAST(d AS date) BETWEEN :from AND :to
                GROUP BY r.room_type_id, CAST(d AS date)
                """.trimIndent(),
            ).param("types", array(roomTypeIds.map { it.value }))
            .param("from", from)
            .param("to", to)
            .query { rs, _ ->
                InventoryKey(RoomTypeId(rs.uuid("room_type_id")), rs.getObject("stay_date", LocalDate::class.java)) to
                    InventoryUsage(rs.getInt("sold"), 0)
            }.list()
            .toMap()
    }

    private fun guestsOf(ids: Collection<ReservationId>): Map<ReservationId, List<StayGuestSummary>> =
        jdbc
            .sql(
                "SELECT reservation_id, guest_id, is_primary FROM reservations.reservation_guest " +
                    "WHERE reservation_id = ANY (CAST(:ids AS uuid[])) ORDER BY reservation_id, position, guest_id",
            ).param("ids", array(ids.map { it.value }))
            .query { rs, _ ->
                ReservationId(rs.uuid("reservation_id")) to
                    StayGuestSummary(GuestId(rs.uuid("guest_id")), rs.getBoolean("is_primary"))
            }.list()
            .groupBy({ it.first }, { it.second })

    private fun reservation(
        rs: ResultSet,
        guests: List<StayGuestSummary>,
        nights: List<NightlyRateSummary>,
    ) = ReservationSummary(
        id = ReservationId(rs.uuid("id")),
        code = DocumentNumber.parse(rs.getString("code")),
        bookingId = BookingId(rs.uuid("booking_id")),
        status = ReservationStatus.valueOf(rs.getString("status")),
        provisionalUntil = rs.instant("provisional_until"),
        roomTypeId = RoomTypeId(rs.uuid("room_type_id")),
        ratePlanId = RatePlanId(rs.uuid("rate_plan_id")),
        arrival = rs.getObject("arrival", LocalDate::class.java),
        departure = rs.getObject("departure", LocalDate::class.java),
        adults = rs.getInt("adults"),
        childAges = intArray(rs, "child_ages"),
        segment = MarketSegment.valueOf(rs.getString("segment")),
        specialRequests = rs.getString("special_requests"),
        guests = guests,
        nightlyRates = nights,
        roomTotal = rs.requiredMoney("room_total"),
        additionalPackagesTotal = rs.requiredMoney("additional_packages_total"),
        total = rs.requiredMoney("total"),
        cancellationTerms =
            CancellationTerms(
                CancellationPolicyId(rs.uuid("cancellation_policy_id")),
                rs.getString("cancellation_policy_name"),
                rs.getLong("cancellation_policy_version"),
                rs.getInt("cancellation_deadline_hours"),
                CancellationPenaltyType.valueOf(rs.getString("cancellation_penalty_type")),
                rs.percent("cancellation_penalty_percent"),
                rs.intOrNull("cancellation_penalty_nights"),
                rs.money("cancellation_penalty_amount"),
            ),
        guaranteeTerms =
            GuaranteeTerms(
                GuaranteePolicyId(rs.uuid("guarantee_policy_id")),
                rs.getString("guarantee_policy_name"),
                rs.getLong("guarantee_policy_version"),
                GuaranteeType.valueOf(rs.getString("guarantee_type")),
                rs.percent("guarantee_deposit_percent"),
                rs.intOrNull("guarantee_due_days_before"),
            ),
        folioId = FolioId(rs.uuid("folio_id")),
        approvalId = rs.getObject("approval_id", UUID::class.java),
        approvedBy = rs.getObject("approved_by", UUID::class.java)?.let(::UserId),
        version = rs.getLong("version"),
        createdAt = rs.requiredInstant("created_at"),
        createdBy = rs.getObject("created_by", UUID::class.java)?.let(::UserId),
    )

    private fun night(rs: ResultSet) =
        NightlyRateSummary(
            date = rs.getObject("stay_date", LocalDate::class.java),
            ratePlanId = RatePlanId(rs.uuid("rate_plan_id")),
            calculatedRoomAmount = rs.requiredMoney("calculated_room_amount"),
            roomAmount = rs.requiredMoney("room_amount"),
            includedPackagesAmount = rs.requiredMoney("included_packages_amount"),
            additionalPackagesAmount = rs.requiredMoney("additional_packages_amount"),
            amount = rs.requiredMoney("amount"),
            overridden = rs.getBoolean("overridden"),
            discountPercent = rs.percent("discount_percent"),
            packages = jsonMapper.readValue<List<StoredPackage>>(rs.getString("packages")).map(StoredPackage::toCharge),
        )

    private fun storedNight(night: NightlyRateSummary) =
        mapOf(
            "stay_date" to night.date.toString(),
            "rate_plan_id" to night.ratePlanId.toString(),
            "calculated_room_amount" to night.calculatedRoomAmount.amount.toPlainString(),
            "room_amount" to night.roomAmount.amount.toPlainString(),
            "included_packages_amount" to night.includedPackagesAmount.amount.toPlainString(),
            "additional_packages_amount" to night.additionalPackagesAmount.amount.toPlainString(),
            "amount" to night.amount.amount.toPlainString(),
            "overridden" to night.overridden,
            "discount_percent" to night.discountPercent?.value?.toPlainString(),
            "packages" to night.packages.map(StoredPackage::of),
        )

    private data class BookingRow(
        val code: String,
        val holder: GuestId,
        val origin: ReservationOrigin,
        val channel: SalesChannel,
        val externalId: String?,
        val version: Long,
        val createdAt: Instant,
        val createdBy: UserId?,
    ) {
        companion object {
            fun of(rs: ResultSet) =
                BookingRow(
                    rs.getString("code"),
                    GuestId(rs.uuid("holder_guest_id")),
                    ReservationOrigin.valueOf(rs.getString("origin")),
                    SalesChannel.valueOf(rs.getString("channel")),
                    rs.getString("external_id"),
                    rs.getLong("version"),
                    rs.requiredInstant("created_at"),
                    rs.getObject("created_by", UUID::class.java)?.let(::UserId),
                )
        }
    }

    private fun array(values: Collection<Any>): String = values.joinToString(",", "{", "}")
}

/**
 * Um pacote de uma noite no `jsonb` (`nightly_rate.packages`): separado do tipo público, para renomear uma propriedade
 * lá não mudar o que já está gravado.
 */
private data class StoredPackage(
    val packageId: UUID,
    val code: String,
    val name: String,
    val transactionCodeId: UUID,
    val pricing: String,
    val postingRhythm: String,
    val includedInRate: Boolean,
    val quantity: Int,
    val unitPrice: String,
    val amount: String,
) {
    fun toCharge() =
        PackageCharge(
            PackageId(packageId),
            code,
            name,
            TransactionCodeId(transactionCodeId),
            PackagePricing.valueOf(pricing),
            PackagePostingRhythm.valueOf(postingRhythm),
            includedInRate,
            quantity,
            Money.of(unitPrice),
            Money.of(amount),
        )

    companion object {
        fun of(charge: PackageCharge) =
            StoredPackage(
                charge.packageId.value,
                charge.code,
                charge.name,
                charge.transactionCodeId.value,
                charge.pricing.name,
                charge.postingRhythm.name,
                charge.includedInRate,
                charge.quantity,
                charge.unitPrice.amount.toPlainString(),
                charge.amount.amount.toPlainString(),
            )
    }
}

private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

private fun ResultSet.money(column: String): Money? = getBigDecimal(column)?.let(Money::of)

private fun ResultSet.requiredMoney(column: String): Money = Money.of(getBigDecimal(column))

private fun ResultSet.requiredInstant(column: String): Instant =
    getObject(column, OffsetDateTime::class.java).toInstant()

private fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

private fun ResultSet.percent(column: String): Percent? = getBigDecimal(column)?.let { Percent.of(it.normalized()) }

private fun BigDecimal.normalized(): BigDecimal =
    stripTrailingZeros().let {
        if (it.scale() <
            0
        ) {
            it.setScale(0)
        } else {
            it
        }
    }

private fun intArray(
    rs: ResultSet,
    column: String,
): List<Int> {
    val array = rs.getArray(column) ?: return emptyList()
    return (array.array as Array<*>).map { (it as Number).toInt() }
}
