package com.zeny.hotel.reservations

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * A leitura do plano de `EXPLAIN` pelo teste de índices da busca (M6 E16, D-E16-1): `reservation r` precisa ser lida
 * por um índice com condição útil, isto é, um `Index Cond` no próprio nó (ou no `Bitmap Index Scan` filho dele) ou um
 * índice parcial da situação; a condição de outra tabela não conta. Planos fixos, sem banco.
 */
class ReservationPlanAssertionsTest {
    private val noPartial: (String) -> Boolean = { false }

    @Test
    fun `an index scan with Index Cond on reservation r passes`() {
        val plan =
            """
            Limit  (cost=0.85..20.10 rows=1 width=200)
              ->  Nested Loop Left Join  (cost=0.85..20.10 rows=1 width=200)
                    ->  Nested Loop  (cost=0.56..16.62 rows=1 width=180)
                          ->  Index Scan using reservation_arrival_idx on reservation r  (cost=0.28..8.30 rows=1 width=150)
                                Index Cond: (arrival <= '2026-11-14'::date)
                                Filter: ((status)::text = 'IN_HOUSE'::text)
                          ->  Index Scan using booking_pkey on booking b  (cost=0.28..8.29 rows=1 width=60)
                                Index Cond: (id = r.booking_id)
                    ->  Index Scan using room_assignment_reservation_idx on room_assignment a  (cost=0.28..3.40 rows=1 width=40)
                          Index Cond: (reservation_id = r.id)
                          Filter: (released_at IS NULL)
                    SubPlan 1
                      ->  Index Scan using reservation_guest_primary_uk on reservation_guest g  (cost=0.15..8.17 rows=1 width=16)
                            Index Cond: (reservation_id = r.id)
            """.trimIndent()

        reservationIndexNode(plan) shouldBe ReservationIndexRead("reservation_arrival_idx", hasIndexCond = true)
        plan.shouldReadReservationsUsefully(noPartial)
    }

    @Test
    fun `a bitmap heap scan whose bitmap index scan has Index Cond passes`() {
        val plan =
            """
            Limit  (cost=12.60..12.61 rows=1 width=200)
              ->  Sort  (cost=12.60..12.61 rows=1 width=200)
                    Sort Key: r.arrival, r.id
                    ->  Nested Loop  (cost=4.57..12.59 rows=1 width=200)
                          ->  Bitmap Heap Scan on reservation r  (cost=4.29..8.30 rows=1 width=150)
                                Recheck Cond: (arrival = '2026-11-15'::date)
                                Filter: ((status)::text = 'CONFIRMED'::text)
                                ->  Bitmap Index Scan on reservation_arrival_idx  (cost=0.00..4.29 rows=1 width=0)
                                      Index Cond: (arrival = '2026-11-15'::date)
                          ->  Index Scan using booking_pkey on booking b  (cost=0.28..4.29 rows=1 width=60)
                                Index Cond: (id = r.booking_id)
            """.trimIndent()

        reservationIndexNode(plan) shouldBe ReservationIndexRead("reservation_arrival_idx", hasIndexCond = true)
        plan.shouldReadReservationsUsefully(noPartial)
    }

    @Test
    fun `a status partial index without Index Cond passes`() {
        val plan =
            """
            Limit  (cost=0.43..30.12 rows=51 width=200)
              ->  Nested Loop  (cost=0.43..30.12 rows=51 width=200)
                    ->  Index Scan using reservation_confirmed_arrival_idx on reservation r  (cost=0.14..12.40 rows=51 width=150)
                    ->  Index Scan using booking_pkey on booking b  (cost=0.28..0.34 rows=1 width=60)
                          Index Cond: (id = r.booking_id)
            """.trimIndent()

        reservationIndexNode(plan) shouldBe
            ReservationIndexRead("reservation_confirmed_arrival_idx", hasIndexCond = false)
        plan.shouldReadReservationsUsefully { it == "reservation_confirmed_arrival_idx" }
    }

    @Test
    fun `a non partial index on reservation r with only Filter fails`() {
        val plan =
            """
            Limit  (cost=0.56..90.20 rows=51 width=200)
              ->  Nested Loop  (cost=0.56..90.20 rows=51 width=200)
                    ->  Index Scan using reservation_arrival_idx on reservation r  (cost=0.28..60.40 rows=51 width=150)
                          Filter: (((status)::text = 'IN_HOUSE'::text) AND (arrival <= '2026-11-14'::date))
                    ->  Index Scan using booking_pkey on booking b  (cost=0.28..0.34 rows=1 width=60)
                          Index Cond: (id = r.booking_id)
            """.trimIndent()

        reservationIndexNode(plan) shouldBe ReservationIndexRead("reservation_arrival_idx", hasIndexCond = false)
        shouldThrow<AssertionError> { plan.shouldReadReservationsUsefully(noPartial) }
    }

    @Test
    fun `a seq scan on reservation r fails`() {
        val plan =
            """
            Limit  (cost=10000000020.10..10000000020.11 rows=1 width=200)
              ->  Sort  (cost=10000000020.10..10000000020.11 rows=1 width=200)
                    Sort Key: r.arrival, r.id
                    ->  Nested Loop  (cost=10000000000.28..10000000020.09 rows=1 width=200)
                          ->  Seq Scan on reservation r  (cost=10000000000.00..10000000011.75 rows=1 width=150)
                                Filter: (((status)::text = 'IN_HOUSE'::text) AND (arrival <= '2026-11-14'::date))
                          ->  Index Scan using booking_pkey on booking b  (cost=0.28..8.29 rows=1 width=60)
                                Index Cond: (id = r.booking_id)
            """.trimIndent()

        reservationIndexNode(plan) shouldBe null
        shouldThrow<AssertionError> { plan.shouldReadReservationsUsefully { true } }
    }

    @Test
    fun `an Index Cond on another table does not count for reservation r`() {
        val plan =
            """
            Limit  (cost=1.12..90.20 rows=51 width=200)
              ->  Nested Loop Semi Join  (cost=1.12..90.20 rows=51 width=200)
                    ->  Index Scan using reservation_pkey on reservation h  (cost=0.28..8.30 rows=1 width=16)
                          Index Cond: (id = '01900000-0000-7000-8000-000000000000'::uuid)
                    ->  Nested Loop  (cost=0.56..80.20 rows=51 width=200)
                          ->  Index Scan using reservation_departure_idx on reservation r  (cost=0.28..60.40 rows=51 width=150)
                                Filter: ((status)::text = 'IN_HOUSE'::text)
                          ->  Index Scan using booking_pkey on booking b  (cost=0.28..0.34 rows=1 width=60)
                                Index Cond: (id = r.booking_id)
                    SubPlan 1
                      ->  Index Scan using reservation_guest_primary_uk on reservation_guest g  (cost=0.15..8.17 rows=1 width=16)
                            Index Cond: (reservation_id = r.id)
            """.trimIndent()

        reservationIndexNode(plan) shouldBe ReservationIndexRead("reservation_departure_idx", hasIndexCond = false)
        shouldThrow<AssertionError> { plan.shouldReadReservationsUsefully(noPartial) }
    }

    @Test
    fun `a bitmap heap scan with only Recheck Cond on a non partial index fails`() {
        val plan =
            """
            Limit  (cost=12.60..12.61 rows=1 width=200)
              ->  Nested Loop  (cost=4.57..12.59 rows=1 width=200)
                    ->  Bitmap Heap Scan on reservation r  (cost=4.29..8.30 rows=1 width=150)
                          Recheck Cond: (arrival <= '2026-11-14'::date)
                          Filter: ((status)::text = 'IN_HOUSE'::text)
                          ->  Bitmap Index Scan on reservation_stay_idx  (cost=0.00..4.29 rows=1 width=0)
                    ->  Index Scan using booking_pkey on booking b  (cost=0.28..4.29 rows=1 width=60)
                          Index Cond: (id = r.booking_id)
            """.trimIndent()

        reservationIndexNode(plan) shouldBe ReservationIndexRead("reservation_stay_idx", hasIndexCond = false)
        shouldThrow<AssertionError> { plan.shouldReadReservationsUsefully(noPartial) }
    }
}
