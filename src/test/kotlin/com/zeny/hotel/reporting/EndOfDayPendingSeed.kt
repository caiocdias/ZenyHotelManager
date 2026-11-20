package com.zeny.hotel.reporting

import com.zeny.hotel.billing.BillingPermissions
import com.zeny.hotel.billing.BillingTestSupport.Companion.definition
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.InventoryListenersQuiescence
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.LocalDate
import java.util.UUID

/**
 * As três pendências da RF-FEC-04 que os testes do fechamento no banco isolado criam (M6 E13): uma conta aberta com
 * saldo de reserva `CHECKED_OUT`, um turno de caixa aberto e uma submissão FNRH `FAILED`, cada uma com um [marker] nos
 * dados que não podem aparecer na pendência (nome do hóspede, nome do ponto de caixa e saldo). A última falha da FNRH é
 * um código fixo do banco ([LAST_FAILURE]), conferido ausente.
 */
class EndOfDayPendingSeed(
    private val iam: IamTestSupport,
    private val support: ReservationsTestSupport,
    private val jdbc: JdbcClient,
    private val hotel: HotelApi,
) {
    /** O que o [seed] criou: as referências e os dados que a pendência mostra. */
    data class Seeded(
        val marker: String,
        val folioId: UUID,
        val folioNumber: String,
        val reservationId: UUID,
        val reservationCode: String,
        val shiftId: UUID,
        val shiftNumber: String,
        val openedOn: LocalDate,
        val balance: String,
    )

    /** Cria as três pendências. */
    @Suppress("LongMethod") // o cenário inteiro, em ordem
    fun seed(): Seeded {
        val seed = ReportingTestSupport(iam, support)
        val base = hotel.currentBusinessDate()
        val (type, _) = support.inventory.roomTypeWithRooms(1, base)
        val plan = seed.plan(type, "NORMAL", base)
        val marker = "MARK" + support.guests.suffix().uppercase()
        val holder =
            UUID.fromString(
                support.guests
                    .createGuest(
                        support.guests.createBody(
                            profile = support.guests.profileBody(fullName = "Hóspede Fechamento $marker"),
                            documents = emptyList(),
                        ),
                    )["id"]
                    .asString(),
            )
        val reservation = seed.book(holder, support.reservation(type, plan, base, base.plusDays(1), adults = 1))
        InventoryListenersQuiescence.await(jdbc)
        support.forceCheckedOut(reservation)
        val billing = support.billing
        val folio =
            jdbc
                .sql("SELECT folio_id FROM reservations.report_reservations WHERE reservation_id = :id")
                .param("id", reservation)
                .query(UUID::class.java)
                .single()
        billing.post(folio, billing.codeId("LAUNDRY"), "77.70")

        val operator =
            iam.createUserWith(
                TestGrant(definition(BillingPermissions.CASHIER_OPERATE), ALL),
                TestGrant(definition(BillingPermissions.FOLIOS_READ), ALL),
            )
        val shift = billing.openShift(iam.asTestUser(operator))
        jdbc
            .sql(
                "UPDATE billing.cash_point SET name = :name " +
                    "WHERE id = (SELECT cash_point_id FROM billing.cashier_shift WHERE id = :shift)",
            ).param("name", "Caixa $marker")
            .param("shift", shift)
            .update()
        submit(reservation)
        return Seeded(
            marker = marker,
            folioId = folio,
            folioNumber = billing.folio(folio)["number"].asString(),
            reservationId = reservation,
            reservationCode =
                text(
                    "SELECT reservation_code FROM reservations.report_reservations WHERE reservation_id = :id",
                    reservation,
                ),
            shiftId = shift,
            shiftNumber = text("SELECT shift_number FROM billing.report_cashier_shifts WHERE shift_id = :id", shift),
            openedOn =
                LocalDate.parse(
                    text("SELECT opened_on FROM billing.report_cashier_shifts WHERE shift_id = :id", shift),
                ),
            balance = billing.balance(folio),
        )
    }

    private fun text(
        sql: String,
        id: UUID,
    ): String =
        jdbc
            .sql(sql)
            .param("id", id)
            .query(String::class.java)
            .single()

    private fun submit(reservation: UUID) =
        jdbc
            .sql(
                """
                INSERT INTO fnrh.submission (id, reservation_id, reservation_code, operation, operation_rank, event_key,
                    causal_version, status, attempts, next_attempt_at, last_failure, in_flight, occurred_at, created_at,
                    sent_at, version)
                VALUES (:id, :reservation, 'R-PENDING', 'UPDATE_RESERVATION', 0, 'pending-seed', 1, 'FAILED', 3,
                    now() + interval '30 days', :failure, false, now(), now(), NULL, 0)
                """.trimIndent(),
            ).param("id", UuidV7.generate())
            .param("reservation", reservation)
            .param("failure", LAST_FAILURE)
            .update()

    companion object {
        /** O código da última falha da submissão plantada; a pendência não o mostra. */
        const val LAST_FAILURE = "SERVER_ERROR"
    }
}
