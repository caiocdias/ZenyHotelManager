package com.zeny.hotel.reservations.infrastructure

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.PostingId
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
import com.zeny.hotel.rates.RatePlanCategory
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.SalesChannel
import com.zeny.hotel.reservations.BookingId
import com.zeny.hotel.reservations.BookingSummary
import com.zeny.hotel.reservations.CancellationPenalty
import com.zeny.hotel.reservations.CancellationReason
import com.zeny.hotel.reservations.CancellationTerms
import com.zeny.hotel.reservations.GuaranteeMethod
import com.zeny.hotel.reservations.GuaranteeTerms
import com.zeny.hotel.reservations.HistoryCancellation
import com.zeny.hotel.reservations.HistoryNight
import com.zeny.hotel.reservations.HistoryStayGuest
import com.zeny.hotel.reservations.MarketSegment
import com.zeny.hotel.reservations.MinorAuthorization
import com.zeny.hotel.reservations.MinorProofType
import com.zeny.hotel.reservations.NightlyRateSummary
import com.zeny.hotel.reservations.PendingGuaranteeKind
import com.zeny.hotel.reservations.ReservationCancellation
import com.zeny.hotel.reservations.ReservationGuarantee
import com.zeny.hotel.reservations.ReservationHistoryEntry
import com.zeny.hotel.reservations.ReservationHistoryId
import com.zeny.hotel.reservations.ReservationHistoryKind
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationOrigin
import com.zeny.hotel.reservations.ReservationState
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.RoomBlockId
import com.zeny.hotel.reservations.StayGuestSummary
import com.zeny.hotel.reservations.StayTransitionRecord
import com.zeny.hotel.reservations.application.BookingStore
import com.zeny.hotel.reservations.application.GuestRepointing
import com.zeny.hotel.reservations.application.PendingGuaranteeRow
import com.zeny.hotel.reservations.application.RoomAssignmentStore
import com.zeny.hotel.reservations.domain.InventoryKey
import com.zeny.hotel.reservations.domain.InventoryUsage
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.AppendOnlyTables
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.UserId
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * As reservas agrupadoras e as reservas pelo `JdbcClient`, só no schema `reservations` e sempre parametrizado. Os
 * hóspedes e as noites de uma reserva entram num comando cada (`jsonb_to_recordset` sobre o JSON das linhas); os
 * vetores (`unnest`/`ANY`) vão em texto (`{a,b}`) convertido pelo `CAST`.
 */
@Component
@Suppress("TooManyFunctions", "LargeClass") // gravação, leituras, alteração, histórico, reapontamento e inventário
class JdbcBookingStore(
    private val jdbc: JdbcClient,
    private val jsonMapper: JsonMapper,
    private val assignments: RoomAssignmentStore,
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
                    created_at, created_by, updated_at, block_id, rate_plan_category)
                VALUES (:id, :code, :bookingId, :status, :provisionalUntil, :roomTypeId, :ratePlanId, :arrival,
                    :departure, :adults, CAST(:childAges AS integer[]), :segment, :specialRequests, :cancellationId,
                    :cancellationName, :cancellationVersion, :deadlineHours, :penaltyType, :penaltyPercent,
                    :penaltyNights, :penaltyAmount, :guaranteeId, :guaranteeName, :guaranteeVersion, :guaranteeType,
                    :depositPercent, :dueDaysBefore, :roomTotal, :additionalTotal, :total, :folioId, :approvalId,
                    :approvedBy, :version, :createdAt, :createdBy, :createdAt, :blockId, :ratePlanCategory)
                """.trimIndent(),
            ).param("id", reservation.id.value)
            .param("ratePlanCategory", reservation.ratePlanCategory.name)
            .param("blockId", reservation.blockId?.value)
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
                INSERT INTO reservations.reservation_guest (reservation_id, guest_id, is_primary, position,
                    travel_reason, transport_mode, checked_in_at, checked_out_at, minor_proof_type,
                    minor_responsible_guest_id, minor_relationship, minor_attachment_id, minor_valid_until)
                SELECT :id, g.guest_id, g.is_primary, g.position, g.travel_reason, g.transport_mode, g.checked_in_at,
                    g.checked_out_at, g.minor_proof_type, g.minor_responsible_guest_id, g.minor_relationship,
                    g.minor_attachment_id, g.minor_valid_until
                FROM jsonb_to_recordset(CAST(:guests AS jsonb))
                    AS g(guest_id uuid, is_primary boolean, position smallint, travel_reason varchar,
                        transport_mode varchar, checked_in_at timestamptz, checked_out_at timestamptz,
                        minor_proof_type varchar, minor_responsible_guest_id uuid, minor_relationship varchar,
                        minor_attachment_id uuid, minor_valid_until date)
                """.trimIndent(),
            ).param("id", reservation.id.value)
            .param(
                "guests",
                jsonMapper.writeValueAsString(
                    reservation.guests.mapIndexed { index, guest ->
                        val minor = guest.minorAuthorization
                        mapOf(
                            "guest_id" to guest.guestId.toString(),
                            "is_primary" to guest.primary,
                            "position" to index,
                            "travel_reason" to guest.travelReason,
                            "transport_mode" to guest.transportMode,
                            "checked_in_at" to guest.checkedInAt?.toString(),
                            "checked_out_at" to guest.checkedOutAt?.toString(),
                            "minor_proof_type" to minor?.proofType?.name,
                            "minor_responsible_guest_id" to minor?.responsibleGuestId?.toString(),
                            "minor_relationship" to minor?.relationship,
                            "minor_attachment_id" to minor?.attachmentId?.toString(),
                            "minor_valid_until" to minor?.validUntil?.toString(),
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
            ?.copy(roomAssignment = assignments.active(id))
    }

    override fun reservationForUpdate(id: ReservationId): ReservationSummary? {
        val exists =
            jdbc
                .sql("SELECT id FROM reservations.reservation WHERE id = :id FOR UPDATE")
                .param("id", id.value)
                .query { rs, _ -> rs.uuid("id") }
                .optional()
                .isPresent
        return if (exists) reservation(id) else null
    }

    @Suppress("LongMethod") // um UPDATE com a estadia, os termos copiados, os totais, o cancelamento e a garantia
    override fun update(
        reservation: ReservationSummary,
        now: Instant,
        replaceNights: Boolean,
        replaceGuests: Boolean,
    ) {
        val cancellation = reservation.cancellationTerms
        val guarantee = reservation.guaranteeTerms
        val cancelled = reservation.cancellation
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE reservations.reservation SET status = :status, provisional_until = :provisionalUntil,
                        room_type_id = :roomTypeId, rate_plan_id = :ratePlanId,
                        rate_plan_category = :ratePlanCategory, arrival = :arrival,
                        departure = :departure, adults = :adults, child_ages = CAST(:childAges AS integer[]),
                        segment = :segment, special_requests = :specialRequests,
                        cancellation_policy_id = :cancellationId, cancellation_policy_name = :cancellationName,
                        cancellation_policy_version = :cancellationVersion,
                        cancellation_deadline_hours = :deadlineHours, cancellation_penalty_type = :penaltyType,
                        cancellation_penalty_percent = :penaltyPercent, cancellation_penalty_nights = :penaltyNights,
                        cancellation_penalty_amount = :penaltyAmount, guarantee_policy_id = :guaranteeId,
                        guarantee_policy_name = :guaranteeName, guarantee_policy_version = :guaranteeVersion,
                        guarantee_type = :guaranteeType, guarantee_deposit_percent = :depositPercent,
                        guarantee_due_days_before = :dueDaysBefore, room_total = :roomTotal,
                        additional_packages_total = :additionalTotal, total = :total, approval_id = :approvalId,
                        approved_by = :approvedBy, cancellation_reason = :reason, cancellation_notes = :notes,
                        cancellation_previous_status = :previousStatus, cancelled_at = :cancelledAt,
                        cancelled_by = :cancelledBy, penalty_deadline = :penaltyDeadline,
                        penalty_amount = :penaltyDue, penalty_waived = :penaltyWaived,
                        penalty_posting_id = :penaltyPostingId, penalty_waiver_approval_id = :waiverApprovalId,
                        penalty_waiver_approved_by = :waiverApprovedBy, penalties_charged = :penaltiesCharged,
                        guarantee_method = :guaranteeMethod, guaranteed_at = :guaranteedAt,
                        guaranteed_by = :guaranteedBy, guarantee_deposit_required = :depositRequired,
                        guarantee_deposit_received = :depositReceived,
                        checked_in_on = :checkedInOn, checked_in_at = :checkedInAt, checked_in_by = :checkedInBy,
                        checked_out_on = :checkedOutOn, checked_out_at = :checkedOutAt,
                        checked_out_by = :checkedOutBy,
                        version = :version, updated_at = :now
                    WHERE id = :id AND version = :version - 1
                    """.trimIndent(),
                ).param("id", reservation.id.value)
                .param("status", reservation.status.name)
                .param("provisionalUntil", reservation.provisionalUntil?.utc())
                .param("roomTypeId", reservation.roomTypeId.value)
                .param("ratePlanId", reservation.ratePlanId.value)
                .param("ratePlanCategory", reservation.ratePlanCategory.name)
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
                .param("approvalId", reservation.approvalId)
                .param("approvedBy", reservation.approvedBy?.value)
                .param("reason", cancelled?.reason?.name)
                .param("notes", cancelled?.notes)
                .param("previousStatus", cancelled?.previousStatus?.name)
                .param("cancelledAt", cancelled?.cancelledAt?.utc())
                .param("cancelledBy", cancelled?.cancelledBy?.value)
                .param("penaltyDeadline", cancelled?.penalty?.deadline?.utc())
                .param("penaltyDue", cancelled?.penalty?.amount?.amount)
                .param("penaltyWaived", cancelled?.penalty?.waived)
                .param("penaltyPostingId", cancelled?.penalty?.postingId?.value)
                .param("waiverApprovalId", cancelled?.penalty?.waiverApprovalId)
                .param("waiverApprovedBy", cancelled?.penalty?.waiverApprovedBy?.value)
                .param("penaltiesCharged", reservation.penaltiesCharged.amount)
                .param("guaranteeMethod", reservation.guarantee?.method?.name)
                .param("guaranteedAt", reservation.guarantee?.confirmedAt?.utc())
                .param("guaranteedBy", reservation.guarantee?.confirmedBy?.value)
                .param("depositRequired", reservation.guarantee?.requiredDeposit?.amount)
                .param("depositReceived", reservation.guarantee?.depositReceived?.amount)
                .param("checkedInOn", reservation.checkIn?.businessDate)
                .param("checkedInAt", reservation.checkIn?.at?.utc())
                .param("checkedInBy", reservation.checkIn?.by?.value)
                .param("checkedOutOn", reservation.checkOut?.businessDate)
                .param("checkedOutAt", reservation.checkOut?.at?.utc())
                .param("checkedOutBy", reservation.checkOut?.by?.value)
                .param("version", reservation.version)
                .param("now", now.utc())
                .update()
        check(updated == 1) { "Reserva ${reservation.id} não gravada: versão ${reservation.version - 1} não confere" }
        if (replaceNights) {
            jdbc
                .sql("DELETE FROM reservations.nightly_rate WHERE reservation_id = :id")
                .param("id", reservation.id.value)
                .update()
            insertNights(reservation)
        }
        if (replaceGuests) {
            jdbc
                .sql("DELETE FROM reservations.reservation_guest WHERE reservation_id = :id")
                .param("id", reservation.id.value)
                .update()
            insertGuests(reservation)
        }
    }

    override fun dueProvisionals(
        now: Instant,
        after: Pair<Instant, ReservationId>?,
        limit: Int,
    ): List<Pair<Instant, ReservationId>> =
        jdbc
            .sql(
                """
                SELECT id, provisional_until FROM reservations.reservation
                WHERE status = 'PROVISIONAL' AND provisional_until <= :now
                  AND (CAST(:afterAt AS timestamptz) IS NULL
                       OR (provisional_until, id) > (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
                ORDER BY provisional_until, id
                LIMIT :limit
                """.trimIndent(),
            ).param("now", now.utc())
            .param("afterAt", after?.first?.utc())
            .param("afterId", after?.second?.value)
            .param("limit", limit)
            .query { rs, _ -> rs.requiredInstant("provisional_until") to ReservationId(rs.uuid("id")) }
            .list()

    override fun pendingGuarantees(
        from: Instant?,
        to: Instant,
        checkInTime: LocalTime,
        zone: ZoneId,
        after: Pair<Instant, ReservationId>?,
        limit: Int,
    ): List<PendingGuaranteeRow> =
        jdbc
            .sql(
                """
                SELECT * FROM (
                    SELECT r.*, b.holder_guest_id,
                        CASE WHEN r.status = 'PROVISIONAL' THEN r.provisional_until
                             ELSE ((CASE WHEN r.guarantee_type = 'DEPOSIT'
                                         THEN r.arrival - r.guarantee_due_days_before ELSE r.arrival END)
                                   + CAST(:checkIn AS time)) AT TIME ZONE :zone
                        END AS due_at
                    FROM reservations.reservation r
                    JOIN reservations.booking b ON b.id = r.booking_id
                    WHERE r.status = 'PROVISIONAL'
                       OR (r.status = 'CONFIRMED' AND r.guarantee_type <> 'NONE'
                           -- sem garantia, ou com a de outro meio (CARD/DEPOSIT têm o mesmo nome nos dois campos)
                           AND (r.guarantee_method IS NULL OR r.guarantee_method <> r.guarantee_type))
                ) p
                WHERE p.due_at < :to
                  AND (CAST(:from AS timestamptz) IS NULL OR p.due_at >= CAST(:from AS timestamptz))
                  AND (CAST(:afterAt AS timestamptz) IS NULL
                       OR (p.due_at, p.id) > (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
                ORDER BY p.due_at, p.id
                LIMIT :limit
                """.trimIndent(),
            ).param("from", from?.utc())
            .param("to", to.utc())
            .param("checkIn", checkInTime)
            .param("zone", zone.id)
            .param("afterAt", after?.first?.utc())
            .param("afterId", after?.second?.value)
            .param("limit", limit)
            .query { rs, _ ->
                val status = ReservationStatus.valueOf(rs.getString("status"))
                PendingGuaranteeRow(
                    id = ReservationId(rs.uuid("id")),
                    code = DocumentNumber.parse(rs.getString("code")),
                    bookingId = BookingId(rs.uuid("booking_id")),
                    holderGuestId = GuestId(rs.uuid("holder_guest_id")),
                    kind =
                        if (status == ReservationStatus.PROVISIONAL) {
                            PendingGuaranteeKind.PROVISIONAL
                        } else {
                            PendingGuaranteeKind.UNGUARANTEED
                        },
                    status = status,
                    dueAt = rs.requiredInstant("due_at"),
                    provisionalUntil = rs.instant("provisional_until"),
                    roomTypeId = RoomTypeId(rs.uuid("room_type_id")),
                    ratePlanId = RatePlanId(rs.uuid("rate_plan_id")),
                    arrival = rs.getObject("arrival", LocalDate::class.java),
                    departure = rs.getObject("departure", LocalDate::class.java),
                    total = rs.requiredMoney("total"),
                    guaranteeTerms = guaranteeTerms(rs),
                    folioId = FolioId(rs.uuid("folio_id")),
                )
            }.list()

    override fun insertHistory(entry: ReservationHistoryEntry) {
        jdbc
            .sql(
                """
                INSERT INTO reservations.reservation_history (id, reservation_id, kind, business_date, occurred_at,
                    actor_id, approval_id, approved_by, changed_fields, before_state, after_state, version)
                VALUES (:id, :reservationId, :kind, :businessDate, :occurredAt, :actorId, :approvalId, :approvedBy,
                    CAST(:changedFields AS text[]), CAST(:before AS jsonb), CAST(:after AS jsonb), :version)
                """.trimIndent(),
            ).param("id", entry.id.value)
            .param("reservationId", entry.reservationId.value)
            .param("kind", entry.kind.name)
            .param("businessDate", entry.businessDate)
            .param("occurredAt", entry.occurredAt.utc())
            .param("actorId", entry.actorId?.value)
            .param("approvalId", entry.approvalId)
            .param("approvedBy", entry.approvedBy?.value)
            .param("changedFields", entry.changedFields.joinToString(",", "{", "}"))
            .param("before", jsonMapper.writeValueAsString(StoredState.of(entry.before)))
            .param("after", jsonMapper.writeValueAsString(StoredState.of(entry.after)))
            .param("version", entry.version)
            .update()
    }

    override fun history(
        id: ReservationId,
        after: Pair<Instant, ReservationHistoryId>?,
        limit: Int,
    ): List<ReservationHistoryEntry> =
        jdbc
            .sql(
                """
                SELECT * FROM reservations.reservation_history
                WHERE reservation_id = :id
                  AND (CAST(:afterAt AS timestamptz) IS NULL
                       OR (occurred_at, id) < (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
                ORDER BY occurred_at DESC, id DESC
                LIMIT :limit
                """.trimIndent(),
            ).param("id", id.value)
            .param("afterAt", after?.first?.utc())
            .param("afterId", after?.second?.value)
            .param("limit", limit)
            .query { rs, _ ->
                ReservationHistoryEntry(
                    id = ReservationHistoryId(rs.uuid("id")),
                    reservationId = ReservationId(rs.uuid("reservation_id")),
                    kind = ReservationHistoryKind.valueOf(rs.getString("kind")),
                    businessDate = rs.getObject("business_date", LocalDate::class.java),
                    occurredAt = rs.requiredInstant("occurred_at"),
                    actorId = rs.getObject("actor_id", UUID::class.java)?.let(::UserId),
                    approvalId = rs.getObject("approval_id", UUID::class.java),
                    approvedBy = rs.getObject("approved_by", UUID::class.java)?.let(::UserId),
                    changedFields = (rs.getArray("changed_fields").array as Array<*>).map { it.toString() },
                    before = jsonMapper.readValue<StoredState>(rs.getString("before_state")).toState(),
                    after = jsonMapper.readValue<StoredState>(rs.getString("after_state")).toState(),
                    version = rs.getLong("version"),
                )
            }.list()

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
        // O responsável pela comprovação de um menor (M5 E01) é um hóspede da mesma estadia: a reserva já está nas
        // afetadas (a versão dela já avançou). Se o responsável foi mesclado no próprio menor (os dois perfis eram a
        // mesma pessoa), a comprovação perde o sentido e sai (o responsável nunca é o próprio hóspede; o retrato da
        // trilha e o histórico anteriores a guardam).
        jdbc
            .sql(
                """
                UPDATE reservations.reservation_guest SET minor_proof_type = NULL, minor_responsible_guest_id = NULL,
                    minor_relationship = NULL, minor_attachment_id = NULL, minor_valid_until = NULL
                WHERE minor_responsible_guest_id = :merged AND guest_id = :target
                """.trimIndent(),
            ).param("merged", merged.value)
            .param("target", target.value)
            .update()
        jdbc
            .sql(
                "UPDATE reservations.reservation_guest SET minor_responsible_guest_id = :target " +
                    "WHERE minor_responsible_guest_id = :merged",
            ).param("target", target.value)
            .param("merged", merged.value)
            .update()
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
                "SELECT * FROM reservations.reservation_guest " +
                    "WHERE reservation_id = ANY (CAST(:ids AS uuid[])) ORDER BY reservation_id, position, guest_id",
            ).param("ids", array(ids.map { it.value }))
            .query { rs, _ -> ReservationId(rs.uuid("reservation_id")) to stayGuest(rs) }
            .list()
            .groupBy({ it.first }, { it.second })

    private fun stayGuest(rs: ResultSet): StayGuestSummary {
        val proof = rs.getString("minor_proof_type")
        return StayGuestSummary(
            guestId = GuestId(rs.uuid("guest_id")),
            primary = rs.getBoolean("is_primary"),
            travelReason = rs.getString("travel_reason"),
            transportMode = rs.getString("transport_mode"),
            checkedInAt = rs.instant("checked_in_at"),
            checkedOutAt = rs.instant("checked_out_at"),
            minorAuthorization =
                proof?.let {
                    MinorAuthorization(
                        proofType = MinorProofType.valueOf(it),
                        responsibleGuestId = GuestId(rs.uuid("minor_responsible_guest_id")),
                        relationship = rs.getString("minor_relationship"),
                        attachmentId = rs.getObject("minor_attachment_id", UUID::class.java),
                        validUntil = rs.getObject("minor_valid_until", LocalDate::class.java),
                    )
                },
        )
    }

    private fun stamp(
        rs: ResultSet,
        prefix: String,
    ): StayTransitionRecord? {
        val at = rs.instant("${prefix}_at") ?: return null
        return StayTransitionRecord(
            businessDate = rs.getObject("${prefix}_on", LocalDate::class.java),
            at = at,
            by = rs.getObject("${prefix}_by", UUID::class.java)?.let(::UserId),
        )
    }

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
        ratePlanCategory = RatePlanCategory.valueOf(rs.getString("rate_plan_category")),
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
        guaranteeTerms = guaranteeTerms(rs),
        folioId = FolioId(rs.uuid("folio_id")),
        approvalId = rs.getObject("approval_id", UUID::class.java),
        approvedBy = rs.getObject("approved_by", UUID::class.java)?.let(::UserId),
        version = rs.getLong("version"),
        createdAt = rs.requiredInstant("created_at"),
        createdBy = rs.getObject("created_by", UUID::class.java)?.let(::UserId),
        cancellation = cancellation(rs),
        penaltiesCharged = rs.requiredMoney("penalties_charged"),
        guarantee = guarantee(rs),
        blockId = rs.getObject("block_id", UUID::class.java)?.let(::RoomBlockId),
        checkIn = stamp(rs, "checked_in"),
        checkOut = stamp(rs, "checked_out"),
    )

    private fun guarantee(rs: ResultSet): ReservationGuarantee? {
        val method = rs.getString("guarantee_method") ?: return null
        return ReservationGuarantee(
            method = GuaranteeMethod.valueOf(method),
            confirmedAt = rs.requiredInstant("guaranteed_at"),
            confirmedBy = rs.getObject("guaranteed_by", UUID::class.java)?.let(::UserId),
            requiredDeposit = rs.money("guarantee_deposit_required"),
            depositReceived = rs.money("guarantee_deposit_received"),
        )
    }

    private fun guaranteeTerms(rs: ResultSet) =
        GuaranteeTerms(
            GuaranteePolicyId(rs.uuid("guarantee_policy_id")),
            rs.getString("guarantee_policy_name"),
            rs.getLong("guarantee_policy_version"),
            GuaranteeType.valueOf(rs.getString("guarantee_type")),
            rs.percent("guarantee_deposit_percent"),
            rs.intOrNull("guarantee_due_days_before"),
        )

    private fun cancellation(rs: ResultSet): ReservationCancellation? {
        val reason = rs.getString("cancellation_reason") ?: return null
        return ReservationCancellation(
            reason = CancellationReason.valueOf(reason),
            notes = rs.getString("cancellation_notes"),
            previousStatus = ReservationStatus.valueOf(rs.getString("cancellation_previous_status")),
            cancelledAt = rs.requiredInstant("cancelled_at"),
            cancelledBy = rs.getObject("cancelled_by", UUID::class.java)?.let(::UserId),
            penalty =
                CancellationPenalty(
                    deadline = rs.requiredInstant("penalty_deadline"),
                    amount = rs.requiredMoney("penalty_amount"),
                    waived = rs.getBoolean("penalty_waived"),
                    postingId = rs.getObject("penalty_posting_id", UUID::class.java)?.let(::PostingId),
                    waiverApprovalId = rs.getObject("penalty_waiver_approval_id", UUID::class.java),
                    waiverApprovedBy = rs.getObject("penalty_waiver_approved_by", UUID::class.java)?.let(::UserId),
                ),
        )
    }

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

/** As tabelas *append-only* do módulo, que a partida confere ([AppendOnlyTables]): o histórico das reservas (E08). */
@Configuration(proxyBeanMethods = false)
class ReservationsTablesConfiguration {
    @Bean
    fun reservationsAppendOnlyTables(): AppendOnlyTables = AppendOnlyTables { setOf(HISTORY_TABLE) }

    companion object {
        const val HISTORY_TABLE = "reservations.reservation_history"
    }
}

/**
 * O retrato de uma reserva no `jsonb` do histórico (`reservation_history.before_state`/`after_state`): separado do
 * tipo público, para renomear uma propriedade lá não mudar o que já está gravado.
 */
private data class StoredState(
    val status: String,
    val roomTypeId: UUID,
    val ratePlanId: UUID,
    val arrival: String,
    val departure: String,
    val adults: Int,
    val childAges: List<Int>,
    val segment: String,
    val hasSpecialRequests: Boolean,
    val nights: List<StoredNight>,
    val roomTotal: String,
    val additionalPackagesTotal: String,
    val total: String,
    val cancellationPolicyId: UUID,
    val cancellationPolicyVersion: Long,
    val guaranteePolicyId: UUID,
    val guaranteePolicyVersion: Long,
    val cancellation: StoredCancellation?,
    val guaranteeMethod: String? = null,
    val checkIn: StoredStamp? = null,
    val checkOut: StoredStamp? = null,
    val roomId: UUID? = null,
    val stayGuests: List<StoredStayGuest> = emptyList(),
) {
    fun toState() =
        ReservationState(
            status = ReservationStatus.valueOf(status),
            roomTypeId = RoomTypeId(roomTypeId),
            ratePlanId = RatePlanId(ratePlanId),
            arrival = LocalDate.parse(arrival),
            departure = LocalDate.parse(departure),
            adults = adults,
            childAges = childAges,
            segment = MarketSegment.valueOf(segment),
            hasSpecialRequests = hasSpecialRequests,
            nights =
                nights.map {
                    HistoryNight(
                        LocalDate.parse(it.date),
                        RatePlanId(it.ratePlanId),
                        Money.of(it.roomAmount),
                        Money.of(it.amount),
                        it.overridden,
                    )
                },
            roomTotal = Money.of(roomTotal),
            additionalPackagesTotal = Money.of(additionalPackagesTotal),
            total = Money.of(total),
            cancellationPolicyId = CancellationPolicyId(cancellationPolicyId),
            cancellationPolicyVersion = cancellationPolicyVersion,
            guaranteePolicyId = GuaranteePolicyId(guaranteePolicyId),
            guaranteePolicyVersion = guaranteePolicyVersion,
            cancellation =
                cancellation?.let {
                    HistoryCancellation(
                        CancellationReason.valueOf(it.reason),
                        Money.of(it.penaltyAmount),
                        it.penaltyWaived,
                        it.penaltyPostingId?.let(::PostingId),
                    )
                },
            guaranteeMethod = guaranteeMethod?.let(GuaranteeMethod::valueOf),
            checkIn = checkIn?.toRecord(),
            checkOut = checkOut?.toRecord(),
            roomId = roomId?.let(::RoomId),
            stayGuests = stayGuests.map { it.toGuest() },
        )

    data class StoredStamp(
        val businessDate: String,
        val at: String,
        val by: UUID?,
    ) {
        fun toRecord() = StayTransitionRecord(LocalDate.parse(businessDate), Instant.parse(at), by?.let(::UserId))

        companion object {
            fun of(record: StayTransitionRecord) =
                StoredStamp(record.businessDate.toString(), record.at.toString(), record.by?.value)
        }
    }

    data class StoredStayGuest(
        val guestId: UUID,
        val primary: Boolean,
        val travelReason: String?,
        val transportMode: String?,
        val minorProofType: String?,
        val minorResponsibleGuestId: UUID?,
        val minorRelationship: String?,
        val minorAttachmentId: UUID?,
        val minorValidUntil: String?,
    ) {
        fun toGuest() =
            HistoryStayGuest(
                guestId = GuestId(guestId),
                primary = primary,
                travelReason = travelReason,
                transportMode = transportMode,
                minorAuthorization =
                    minorProofType?.let {
                        MinorAuthorization(
                            MinorProofType.valueOf(it),
                            GuestId(checkNotNull(minorResponsibleGuestId)),
                            checkNotNull(minorRelationship),
                            minorAttachmentId,
                            minorValidUntil?.let(LocalDate::parse),
                        )
                    },
            )

        companion object {
            fun of(guest: HistoryStayGuest) =
                StoredStayGuest(
                    guestId = guest.guestId.value,
                    primary = guest.primary,
                    travelReason = guest.travelReason,
                    transportMode = guest.transportMode,
                    minorProofType = guest.minorAuthorization?.proofType?.name,
                    minorResponsibleGuestId = guest.minorAuthorization?.responsibleGuestId?.value,
                    minorRelationship = guest.minorAuthorization?.relationship,
                    minorAttachmentId = guest.minorAuthorization?.attachmentId,
                    minorValidUntil = guest.minorAuthorization?.validUntil?.toString(),
                )
        }
    }

    data class StoredNight(
        val date: String,
        val ratePlanId: UUID,
        val roomAmount: String,
        val amount: String,
        val overridden: Boolean,
    )

    data class StoredCancellation(
        val reason: String,
        val penaltyAmount: String,
        val penaltyWaived: Boolean,
        val penaltyPostingId: UUID?,
    )

    companion object {
        fun of(state: ReservationState) =
            StoredState(
                status = state.status.name,
                roomTypeId = state.roomTypeId.value,
                ratePlanId = state.ratePlanId.value,
                arrival = state.arrival.toString(),
                departure = state.departure.toString(),
                adults = state.adults,
                childAges = state.childAges,
                segment = state.segment.name,
                hasSpecialRequests = state.hasSpecialRequests,
                nights =
                    state.nights.map {
                        StoredNight(
                            it.date.toString(),
                            it.ratePlanId.value,
                            it.roomAmount.amount.toPlainString(),
                            it.amount.amount.toPlainString(),
                            it.overridden,
                        )
                    },
                roomTotal = state.roomTotal.amount.toPlainString(),
                additionalPackagesTotal = state.additionalPackagesTotal.amount.toPlainString(),
                total = state.total.amount.toPlainString(),
                cancellationPolicyId = state.cancellationPolicyId.value,
                cancellationPolicyVersion = state.cancellationPolicyVersion,
                guaranteePolicyId = state.guaranteePolicyId.value,
                guaranteePolicyVersion = state.guaranteePolicyVersion,
                cancellation =
                    state.cancellation?.let {
                        StoredCancellation(
                            it.reason.name,
                            it.penaltyAmount.amount.toPlainString(),
                            it.penaltyWaived,
                            it.penaltyPostingId?.value,
                        )
                    },
                guaranteeMethod = state.guaranteeMethod?.name,
                checkIn = state.checkIn?.let(StoredStamp::of),
                checkOut = state.checkOut?.let(StoredStamp::of),
                roomId = state.roomId?.value,
                stayGuests = state.stayGuests.map(StoredStayGuest::of),
            )
    }
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
