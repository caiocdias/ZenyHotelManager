package com.zeny.hotel.reservations

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Ataque ao apoio de leitura do plano (M6 E16, D-E16-1): formas de plano que os testes de unidade do apoio ainda não
 * cobriam. A maior parte dos planos foi capturada de um PostgreSQL 18 real (só os nomes de tabela e índice seguem os
 * do schema `reservations`); os montados à mão partem dessas capturas e dizem o que mudou. A regra atacada: só vale a
 * condição do nó que lê `reservation r` (ou do `Bitmap Index Scan` que alimenta o `Bitmap Heap Scan` dela); o
 * `Index Cond` de qualquer outro nó, mais ou menos indentado, de um `InitPlan` ou de um `SubPlan`, não vale para `r`.
 */
class ReservationPlanAssertionsAttackTest {
    private val noPartial: (String) -> Boolean = { false }
    private val statusPartials = setOf("reservation_in_house_idx", "reservation_confirmed_arrival_idx")
    private val isStatusPartial: (String) -> Boolean = { it in statusPartials }

    // --- Index Only Scan e Index Scan Backward --------------------------------------------------------------------

    @Test
    fun `an index only scan with Index Cond on reservation r passes`() {
        val plan =
            """
            Index Only Scan using reservation_arrival_idx on reservation r  (cost=0.29..47.81 rows=2030 width=4)
              Index Cond: (arrival < '2026-02-01'::date)
            """.trimIndent()

        reservationIndexNode(plan) shouldBe ReservationIndexRead("reservation_arrival_idx", hasIndexCond = true)
        plan.shouldReadReservationsUsefully(noPartial)
    }

    @Test
    fun `an index only scan without Index Cond passes only when the index is a status partial`() {
        val plan =
            """
            Index Only Scan using reservation_in_house_idx on reservation r  (cost=0.28..50.28 rows=2000 width=4)
              Heap Fetches: 0
            """.trimIndent()

        reservationIndexNode(plan) shouldBe ReservationIndexRead("reservation_in_house_idx", hasIndexCond = false)
        plan.shouldReadReservationsUsefully(isStatusPartial)
        shouldThrow<AssertionError> { plan.shouldReadReservationsUsefully(noPartial) }
    }

    @Test
    fun `an index scan backward with Index Cond on reservation r passes`() {
        val plan =
            """
            Limit  (cost=0.29..1.86 rows=5 width=27)
              ->  Index Scan Backward using reservation_arrival_idx on reservation r  (cost=0.29..639.77 rows=2030 width=27)
                    Index Cond: (arrival < '2026-02-01'::date)
            """.trimIndent()

        reservationIndexNode(plan) shouldBe ReservationIndexRead("reservation_arrival_idx", hasIndexCond = true)
        plan.shouldReadReservationsUsefully(noPartial)
    }

    @Test
    fun `an index scan backward with only Filter on reservation r fails`() {
        // Montado da captura acima: o Index Cond virou Filter.
        val plan =
            """
            Limit  (cost=0.29..1.86 rows=5 width=27)
              ->  Index Scan Backward using reservation_arrival_idx on reservation r  (cost=0.29..639.77 rows=2030 width=27)
                    Filter: ((status)::text = 'IN_HOUSE'::text)
            """.trimIndent()

        reservationIndexNode(plan) shouldBe ReservationIndexRead("reservation_arrival_idx", hasIndexCond = false)
        shouldThrow<AssertionError> { plan.shouldReadReservationsUsefully(noPartial) }
    }

    // --- Parallel e Gather acima de r -----------------------------------------------------------------------------

    @Test
    fun `a parallel bitmap heap scan under Gather takes the Index Cond of its own bitmap index scan`() {
        val plan =
            """
            Finalize Aggregate  (cost=468.11..468.12 rows=1 width=8)
              ->  Gather  (cost=468.09..468.10 rows=2 width=8)
                    Workers Planned: 2
                    ->  Partial Aggregate  (cost=468.09..468.10 rows=1 width=8)
                          ->  Parallel Bitmap Heap Scan on reservation r  (cost=201.91..451.21 rows=6752 width=0)
                                Recheck Cond: (arrival < '2026-09-01'::date)
                                Filter: (booking_id > 5)
                                ->  Bitmap Index Scan on reservation_arrival_idx  (cost=0.00..197.85 rows=16209 width=0)
                                      Index Cond: (arrival < '2026-09-01'::date)
            """.trimIndent()

        reservationIndexNode(plan) shouldBe ReservationIndexRead("reservation_arrival_idx", hasIndexCond = true)
        plan.shouldReadReservationsUsefully(noPartial)
    }

    @Test
    fun `a parallel index scan under Gather Merge with only Filter fails even with Index Cond around it`() {
        // Montado: Gather Merge acima de r, que só tem Filter; o booking ao lado tem Index Cond.
        val plan =
            """
            Gather Merge  (cost=1000.59..2100.20 rows=51 width=200)
              Workers Planned: 2
              ->  Nested Loop  (cost=0.57..1090.10 rows=21 width=200)
                    ->  Parallel Index Scan using reservation_departure_idx on reservation r  (cost=0.29..700.40 rows=21 width=150)
                          Filter: ((status)::text = 'IN_HOUSE'::text)
                    ->  Index Scan using booking_pk on booking b  (cost=0.28..0.34 rows=1 width=60)
                          Index Cond: (id = r.booking_id)
            """.trimIndent()

        reservationIndexNode(plan) shouldBe ReservationIndexRead("reservation_departure_idx", hasIndexCond = false)
        shouldThrow<AssertionError> { plan.shouldReadReservationsUsefully(noPartial) }
    }

    @Test
    fun `a parallel seq scan on reservation r under Gather fails`() {
        val plan =
            """
            Gather  (cost=0.00..252.17 rows=19995 width=27)
              Workers Planned: 2
              ->  Parallel Seq Scan on reservation r  (cost=0.00..252.17 rows=8331 width=27)
                    Disabled: true
                    Filter: (booking_id > 5)
            """.trimIndent()

        reservationIndexNode(plan) shouldBe null
        shouldThrow<AssertionError> { plan.shouldReadReservationsUsefully { true } }
    }

    // --- InitPlan e SubPlan: a condição de outro nó, mais indentado, não vale para r ------------------------------

    @Test
    fun `an Index Cond inside an InitPlan of the index scan on reservation r does not count for it`() {
        // Captura real: r lida por reservation_arrival_idx só com Filter; o Index Cond é do booking, no InitPlan.
        val plan =
            """
            Index Scan using reservation_arrival_idx on reservation r  (cost=2.60..1036.57 rows=1 width=27)
              Filter: (booking_id = (InitPlan 2).col1)
              InitPlan 2
                ->  Result  (cost=2.31..2.31 rows=1 width=4)
                      InitPlan 1
                        ->  Limit  (cost=0.29..2.31 rows=1 width=4)
                              ->  Index Only Scan Backward using booking_pk on booking b  (cost=0.29..8.36 rows=4 width=4)
                                    Index Cond: (id < 5)
            """.trimIndent()

        reservationIndexNode(plan) shouldBe ReservationIndexRead("reservation_arrival_idx", hasIndexCond = false)
        shouldThrow<AssertionError> { plan.shouldReadReservationsUsefully(noPartial) }
    }

    @Test
    fun `an Index Cond inside a SubPlan of the filter of reservation r does not count for it`() {
        // Captura real: o SubPlan correlacionado do Filter de r lê reservation_guest com Index Cond; r só tem Filter.
        val plan =
            """
            Limit  (cost=0.29..8356.99 rows=5 width=27)
              ->  Index Scan using reservation_arrival_idx on reservation r  (cost=0.29..167134.26 rows=100 width=27)
                    Filter: ((SubPlan 1) = 3)
                    SubPlan 1
                      ->  Limit  (cost=0.29..8.30 rows=1 width=4)
                            ->  Index Scan using reservation_guest_primary_uk on reservation_guest g  (cost=0.29..8.30 rows=1 width=4)
                                  Index Cond: (reservation_id = r.id)
            """.trimIndent()

        reservationIndexNode(plan) shouldBe ReservationIndexRead("reservation_arrival_idx", hasIndexCond = false)
        shouldThrow<AssertionError> { plan.shouldReadReservationsUsefully(noPartial) }
    }

    @Test
    fun `an Index Cond inside an InitPlan above reservation r does not count for it`() {
        // Captura real: o InitPlan pende do Limit (menos indentado que r) e vem antes dela no texto.
        val plan =
            """
            Limit  (cost=2.60..1036.57 rows=1 width=27)
              InitPlan 2
                ->  Result  (cost=2.31..2.31 rows=1 width=4)
                      InitPlan 1
                        ->  Limit  (cost=0.29..2.31 rows=1 width=4)
                              ->  Index Only Scan Backward using booking_pk on booking b  (cost=0.29..8.36 rows=4 width=4)
                                    Index Cond: (id < 5)
              ->  Index Scan using reservation_arrival_idx on reservation r  (cost=0.29..1034.26 rows=1 width=27)
                    Filter: (booking_id = (InitPlan 2).col1)
            """.trimIndent()

        reservationIndexNode(plan) shouldBe ReservationIndexRead("reservation_arrival_idx", hasIndexCond = false)
        shouldThrow<AssertionError> { plan.shouldReadReservationsUsefully(noPartial) }
    }

    @Test
    fun `the bitmap index scan of an InitPlan under the bitmap heap scan of reservation r is not its own`() {
        // Captura real: o InitPlan do Bitmap Heap Scan de r vem antes do Bitmap Index Scan dela e tem o próprio
        // Bitmap Index Scan (booking_pk, com Index Cond); o de r é o parcial da hospedagem, sem Index Cond.
        val plan =
            """
            Bitmap Heap Scan on reservation r  (cost=48.12..226.12 rows=1 width=27)
              Recheck Cond: ((status)::text = 'IN_HOUSE'::text)
              Filter: (booking_id = (InitPlan 1).col1)
              InitPlan 1
                ->  Aggregate  (cost=17.83..17.84 rows=1 width=4)
                      ->  Bitmap Heap Scan on booking b  (cost=4.32..17.82 rows=4 width=4)
                            Recheck Cond: (id < 5)
                            ->  Bitmap Index Scan on booking_pk  (cost=0.00..4.32 rows=4 width=0)
                                  Index Cond: (id < 5)
              ->  Bitmap Index Scan on reservation_in_house_idx  (cost=0.00..30.28 rows=2000 width=0)
            """.trimIndent()

        reservationIndexNode(plan) shouldBe ReservationIndexRead("reservation_in_house_idx", hasIndexCond = false)
        shouldThrow<AssertionError> { plan.shouldReadReservationsUsefully(noPartial) }
    }

    // --- Nested Loop com reservation_guest e booking, e Memoize ---------------------------------------------------

    @Test
    fun `reservation r inside a nested loop passes with its own parameterized Index Cond`() {
        val plan =
            """
            Nested Loop  (cost=0.86..1013.61 rows=49 width=43)
              ->  Nested Loop  (cost=0.57..997.23 rows=49 width=35)
                    ->  Index Scan using reservation_guest_primary_uk on reservation_guest g  (cost=0.29..670.29 rows=49 width=8)
                          Filter: (guest_id < 50)
                    ->  Index Scan using reservation_pk on reservation r  (cost=0.29..6.67 rows=1 width=27)
                          Index Cond: (id = g.reservation_id)
              ->  Index Scan using booking_pk on booking b  (cost=0.29..0.33 rows=1 width=8)
                    Index Cond: (id = r.booking_id)
            """.trimIndent()

        reservationIndexNode(plan) shouldBe ReservationIndexRead("reservation_pk", hasIndexCond = true)
        plan.shouldReadReservationsUsefully(noPartial)
    }

    @Test
    fun `reservation r in a nested loop takes no Index Cond from reservation_guest before it nor booking after it`() {
        // Montado da captura acima: g (antes, mesma indentação) e b (depois, menos indentado) têm Index Cond; r, só
        // Filter.
        val plan =
            """
            Nested Loop  (cost=0.86..1013.61 rows=49 width=43)
              ->  Nested Loop  (cost=0.57..997.23 rows=49 width=35)
                    ->  Index Scan using reservation_guest_primary_uk on reservation_guest g  (cost=0.29..670.29 rows=49 width=8)
                          Index Cond: (guest_id = '01900000-0000-7000-8000-000000000000'::uuid)
                    ->  Index Scan using reservation_departure_idx on reservation r  (cost=0.29..6.67 rows=1 width=27)
                          Filter: ((id = g.reservation_id) AND ((status)::text = 'IN_HOUSE'::text))
              ->  Index Scan using booking_pk on booking b  (cost=0.29..0.33 rows=1 width=8)
                    Index Cond: (id = r.booking_id)
            """.trimIndent()

        reservationIndexNode(plan) shouldBe ReservationIndexRead("reservation_departure_idx", hasIndexCond = false)
        shouldThrow<AssertionError> { plan.shouldReadReservationsUsefully(noPartial) }
    }

    @Test
    fun `reservation r under Memoize passes with its own Index Cond`() {
        val plan =
            """
            Nested Loop  (cost=0.58..216.30 rows=3900 width=31)
              ->  Index Only Scan using reservation_guest_primary_uk on reservation_guest g  (cost=0.28..92.53 rows=3900 width=4)
                    Index Cond: (reservation_id < 40)
              ->  Memoize  (cost=0.30..0.53 rows=1 width=27)
                    Cache Key: g.reservation_id
                    Cache Mode: logical
                    ->  Index Scan using reservation_pk on reservation r  (cost=0.29..0.52 rows=1 width=27)
                          Index Cond: (id = g.reservation_id)
            """.trimIndent()

        reservationIndexNode(plan) shouldBe ReservationIndexRead("reservation_pk", hasIndexCond = true)
        plan.shouldReadReservationsUsefully(noPartial)
    }

    @Test
    fun `reservation r under Memoize with only Filter fails even with Index Cond on the other side of the loop`() {
        // Montado da captura acima: o Index Cond de r virou Filter; o de g (antes, menos indentado) fica.
        val plan =
            """
            Nested Loop  (cost=0.58..216.30 rows=3900 width=31)
              ->  Index Only Scan using reservation_guest_primary_uk on reservation_guest g  (cost=0.28..92.53 rows=3900 width=4)
                    Index Cond: (reservation_id < 40)
              ->  Memoize  (cost=0.30..0.53 rows=1 width=27)
                    Cache Key: g.reservation_id
                    Cache Mode: logical
                    ->  Index Scan using reservation_arrival_idx on reservation r  (cost=0.29..0.52 rows=1 width=27)
                          Filter: (id = g.reservation_id)
            """.trimIndent()

        reservationIndexNode(plan) shouldBe ReservationIndexRead("reservation_arrival_idx", hasIndexCond = false)
        shouldThrow<AssertionError> { plan.shouldReadReservationsUsefully(noPartial) }
    }

    // --- BitmapAnd e BitmapOr -------------------------------------------------------------------------------------

    @Test
    fun `a BitmapAnd of two bitmap index scans with Index Cond passes`() {
        val plan =
            """
            Bitmap Heap Scan on reservation r  (cost=9.83..13.85 rows=1 width=27)
              Recheck Cond: ((departure = '2026-02-03'::date) AND (arrival = '2026-02-01'::date))
              ->  BitmapAnd  (cost=9.83..9.83 rows=1 width=0)
                    ->  Bitmap Index Scan on reservation_departure_idx  (cost=0.00..4.79 rows=67 width=0)
                          Index Cond: (departure = '2026-02-03'::date)
                    ->  Bitmap Index Scan on reservation_arrival_idx  (cost=0.00..4.79 rows=67 width=0)
                          Index Cond: (arrival = '2026-02-01'::date)
            """.trimIndent()

        plan.shouldReadReservationsUsefully(noPartial)
    }

    @Test
    fun `a BitmapAnd whose second bitmap index scan has Index Cond restricts reservation r and passes`() {
        // Montado da captura acima: o primeiro ramo virou o parcial do bloqueio (não é da situação), sem Index Cond.
        // A interseção já fica restrita pelo Index Cond do segundo ramo, que é filho do Bitmap Heap Scan de r.
        val plan =
            """
            Bitmap Heap Scan on reservation r  (cost=9.83..13.85 rows=1 width=27)
              Recheck Cond: ((block_id IS NOT NULL) AND (arrival = '2026-02-01'::date))
              ->  BitmapAnd  (cost=9.83..9.83 rows=1 width=0)
                    ->  Bitmap Index Scan on reservation_block_idx  (cost=0.00..4.79 rows=67 width=0)
                    ->  Bitmap Index Scan on reservation_arrival_idx  (cost=0.00..4.79 rows=67 width=0)
                          Index Cond: (arrival = '2026-02-01'::date)
            """.trimIndent()

        plan.shouldReadReservationsUsefully(noPartial)
    }

    @Test
    fun `a BitmapOr of an Index Cond and a status partial passes`() {
        val plan =
            """
            Bitmap Heap Scan on reservation r  (cost=36.10..215.10 rows=2060 width=27)
              Recheck Cond: ((arrival = '2026-02-01'::date) OR ((status)::text = 'IN_HOUSE'::text))
              ->  BitmapOr  (cost=36.10..36.10 rows=2067 width=0)
                    ->  Bitmap Index Scan on reservation_arrival_idx  (cost=0.00..4.79 rows=67 width=0)
                          Index Cond: (arrival = '2026-02-01'::date)
                    ->  Bitmap Index Scan on reservation_in_house_idx  (cost=0.00..30.28 rows=2000 width=0)
            """.trimIndent()

        plan.shouldReadReservationsUsefully(isStatusPartial)
    }

    @Test
    fun `a BitmapOr with a branch that has neither Index Cond nor a status partial fails`() {
        // Montado da captura acima: o segundo ramo virou o parcial do bloqueio (não é da situação), sem Index Cond.
        // Na união, esse ramo devolve todas as reservas do bloqueio sem condição sobre a busca: não há condição útil.
        val plan =
            """
            Bitmap Heap Scan on reservation r  (cost=36.10..215.10 rows=2060 width=27)
              Recheck Cond: ((arrival = '2026-02-01'::date) OR (block_id IS NOT NULL))
              ->  BitmapOr  (cost=36.10..36.10 rows=2067 width=0)
                    ->  Bitmap Index Scan on reservation_arrival_idx  (cost=0.00..4.79 rows=67 width=0)
                          Index Cond: (arrival = '2026-02-01'::date)
                    ->  Bitmap Index Scan on reservation_block_idx  (cost=0.00..30.28 rows=2000 width=0)
            """.trimIndent()

        shouldThrow<AssertionError> { plan.shouldReadReservationsUsefully(isStatusPartial) }
    }

    // --- Alias e Recheck Cond -------------------------------------------------------------------------------------

    @Test
    fun `an index scan on reservation under another alias is not a read of reservation r`() {
        val plan =
            """
            Limit  (cost=0.43..30.12 rows=51 width=200)
              ->  Index Scan using reservation_arrival_idx on reservation res  (cost=0.14..12.40 rows=51 width=150)
                    Index Cond: (arrival <= '2026-11-14'::date)
              ->  Bitmap Heap Scan on reservation r2  (cost=4.29..8.30 rows=1 width=150)
                    Recheck Cond: (arrival = '2026-11-15'::date)
                    ->  Bitmap Index Scan on reservation_departure_idx  (cost=0.00..4.29 rows=1 width=0)
                          Index Cond: (arrival = '2026-11-15'::date)
            """.trimIndent()

        reservationIndexNode(plan) shouldBe null
        shouldThrow<AssertionError> { plan.shouldReadReservationsUsefully { true } }
    }

    @Test
    fun `the Recheck Cond of the heap and the Index Cond of a sibling bitmap scan do not count for reservation r`() {
        val plan =
            """
            Nested Loop  (cost=8.60..20.61 rows=1 width=200)
              ->  Bitmap Heap Scan on reservation r  (cost=4.29..8.30 rows=1 width=150)
                    Recheck Cond: (arrival <= '2026-11-14'::date)
                    Filter: ((status)::text = 'IN_HOUSE'::text)
                    ->  Bitmap Index Scan on reservation_stay_idx  (cost=0.00..4.29 rows=1 width=0)
              ->  Bitmap Heap Scan on booking b  (cost=4.29..8.30 rows=1 width=60)
                    Recheck Cond: (id = r.booking_id)
                    ->  Bitmap Index Scan on booking_pk  (cost=0.00..4.29 rows=1 width=0)
                          Index Cond: (id = r.booking_id)
            """.trimIndent()

        reservationIndexNode(plan) shouldBe ReservationIndexRead("reservation_stay_idx", hasIndexCond = false)
        shouldThrow<AssertionError> { plan.shouldReadReservationsUsefully(noPartial) }
    }
}
