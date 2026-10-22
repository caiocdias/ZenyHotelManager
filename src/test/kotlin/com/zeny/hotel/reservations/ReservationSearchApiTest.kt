package com.zeny.hotel.reservations

import com.zeny.hotel.billing.BillingPermissions
import com.zeny.hotel.billing.BillingTestSupport.Companion.definition
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestsTestSupport.Companion.formattedCpf
import com.zeny.hotel.guests.GuestsTestSupport.Companion.newCpf
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.reservations.application.OperationalList
import com.zeny.hotel.reservations.application.ReservationSearch
import com.zeny.hotel.reservations.infrastructure.ReservationQuery
import com.zeny.hotel.reservations.infrastructure.ReservationQuerySql
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * A busca de reservas e as listas operacionais (M4 E12; RF-RES-11, RF-RES-13, H13, H15): cada filtro da busca
 * (número, número da agrupadora, número da OTA, nome do titular ou de um hóspede da estadia pela `GuestsApi`,
 * período, situação), a busca por documento no corpo, o cursor, as recusas e a permissão; as listas de chegadas,
 * saídas, hospedados e no-shows previstos pela **data operacional** (o relógio da aplicação 40 dias à frente não muda
 * nada), com as pendências da data corrente; as reservas com depósito abaixo do exigido; e os índices que servem cada
 * filtro (o plano de cada consulta sem varredura de `reservation`). Cada teste cria os próprios tipos de UH, hóspedes
 * e reservas (o banco é compartilhado) e olha só os próprios resultados.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class, MutableClockConfiguration::class)
@Suppress("LargeClass", "LongParameterList") // os filtros, as listas e os índices de ponta a ponta; as portas que usam
class ReservationSearchApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val clock: MutableClock,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    // --- Busca (RF-RES-11) ----------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // um cenário com três reservas e cada filtro sobre ele
    fun `each search filter finds exactly the reservations it should, the name only through the GuestsApi`() {
        val (type, _) = inventory.roomTypeWithRooms(3, today)
        val plan = planFor(type, today.plusDays(30))
        val marker = support.guests.suffix().uppercase()
        val ana = guestNamed("Ána Quintânilha $marker")
        val bruno = guestNamed("Bruno Ventura $marker")
        val external = "OTA-${support.guests.suffix()}"
        val first =
            support.created(
                support.booking(
                    ana,
                    support.reservation(type, plan, today.plusDays(5), today.plusDays(7), adults = 1),
                    externalId = external,
                ),
            )
        val second =
            support.created(
                support.booking(
                    bruno,
                    support.reservation(
                        type,
                        plan,
                        today.plusDays(10),
                        today.plusDays(12),
                        guests = listOf(bruno to true, ana to false),
                    ),
                ),
            )
        val third =
            support.created(
                support.booking(bruno, support.reservation(type, plan, today.plusDays(20), today.plusDays(22))),
            )
        val r1 = first.reservationId()
        val r2 = second.reservationId()
        val r3 = third.reservationId()
        with(support.billing) { support.cancel(r3).expect(200) }

        // Número da reserva (sem diferenciar maiúsculas) e da reserva agrupadora.
        val code = first["reservations"][0]["code"].asString()
        search("code=${code.lowercase()}").ids() shouldContainExactly listOf(r1)
        search("bookingCode=${second["code"].asString()}").ids() shouldContainExactly listOf(r2)
        // Número da OTA.
        search("externalId=$external").ids() shouldContainExactly listOf(r1)

        // Nome: o titular (r1) ou um hóspede da estadia (r2), sem acento nem maiúsculas, em ordem de chegada.
        search("name=ana quintanilha ${marker.lowercase()}").ids() shouldContainExactly listOf(r1, r2)
        search("name=Ventura $marker").ids() shouldContainExactly listOf(r2, r3)
        search("name=Ninguém Inexistente $marker").ids().shouldBeEmpty()

        // Situação, com o nome.
        search("name=Ventura $marker&status=CANCELLED").ids() shouldContainExactly listOf(r3)
        search("name=Ventura $marker&status=CONFIRMED,PROVISIONAL").ids() shouldContainExactly listOf(r2)

        // Período: as estadias que tocam o intervalo, inclusive o dia da saída.
        search(
            "name=Quintanilha $marker&from=${today.plusDays(7)}&to=${today.plusDays(7)}",
        ).ids() shouldContainExactly
            listOf(r1)
        search("name=Quintanilha $marker&from=${today.plusDays(8)}&to=${today.plusDays(9)}")
            .ids()
            .shouldBeEmpty()
        search("name=Ventura $marker&from=${today.plusDays(11)}").ids() shouldContainExactly
            listOf(r2, r3)
        search("name=Ventura $marker&to=${today.plusDays(10)}").ids() shouldContainExactly listOf(r2)
        // O período sozinho (outros testes também têm reservas nessas datas).
        val inPeriod = all("/api/v1/reservations?from=${today.plusDays(6)}&to=${today.plusDays(6)}").ids()
        (r1 in inPeriod) shouldBe true
        (r2 in inPeriod) shouldBe false
        // A situação sozinha.
        val cancelled = all("/api/v1/reservations?status=CANCELLED").ids()
        (r3 in cancelled) shouldBe true
        (r1 in cancelled) shouldBe false

        // O item da lista: o resumo, sem pedidos especiais nem noites, com a agrupadora e o hóspede principal.
        val item = search("code=$code")["items"][0]
        item["bookingCode"].asString() shouldBe first["code"].asString()
        item["holderGuestId"].asString() shouldBe ana.toString()
        item["primaryGuestId"].asString() shouldBe ana.toString()
        item["externalId"].asString() shouldBe external
        item["ratePlanCategory"].asString() shouldBe "NORMAL"
        item["arrival"].asString() shouldBe today.plusDays(5).toString()
        item["status"].asString() shouldBe "CONFIRMED"
        item.has("nightlyRates") shouldBe false
        item.has("specialRequests") shouldBe false

        // Cursor: duas páginas de uma.
        val page1 = search("name=Ventura $marker&limit=1")
        page1.ids() shouldContainExactly listOf(r2)
        val page2 = search("name=Ventura $marker&limit=1&cursor=${page1["nextCursor"].asString()}")
        page2.ids() shouldContainExactly listOf(r3)
        page2["nextCursor"].isNull shouldBe true
    }

    @Test
    fun `the search by name finds a reservation by its holder even when the holder is not a guest of the stay`() {
        val (type, _) = inventory.roomTypeWithRooms(1, today)
        val plan = planFor(type, today.plusDays(30))
        val marker = support.guests.suffix().uppercase()
        val holder = guestNamed("Cecília Titular $marker")
        val guest = guestNamed("Davi Hóspede $marker")
        val id =
            support
                .created(
                    support.booking(
                        holder,
                        support.reservation(
                            type,
                            plan,
                            today.plusDays(4),
                            today.plusDays(5),
                            guests =
                                listOf(guest to true),
                        ),
                    ),
                ).reservationId()
        search("name=cecilia titular $marker").ids() shouldContainExactly listOf(id)
        search("name=davi hospede $marker").ids() shouldContainExactly listOf(id)
        search("name=cecilia titular $marker")["items"][0]["primaryGuestId"].asString() shouldBe guest.toString()
    }

    @Test
    fun `the search by document goes in the body, through the blind index of the guests module`() {
        val (type, _) = inventory.roomTypeWithRooms(2, today)
        val plan = planFor(type, today.plusDays(30))
        val cpf = newCpf()
        val holder =
            UUID.fromString(
                support.guests
                    .createGuest(
                        support.guests.createBody(
                            documents = listOf(support.guests.document("CPF", formattedCpf(cpf))),
                        ),
                    )["id"]
                    .asString(),
            )
        val other = support.guest()
        val asHolder =
            support
                .created(
                    support.booking(holder, support.reservation(type, plan, today.plusDays(3), today.plusDays(4))),
                ).reservationId()
        val asGuest =
            support
                .created(
                    support.booking(
                        other,
                        support.reservation(
                            type,
                            plan,
                            today.plusDays(8),
                            today.plusDays(9),
                            guests = listOf(other to true, holder to false),
                        ),
                    ),
                ).reservationId()

        val found = byDocument(mapOf("type" to "CPF", "number" to formattedCpf(cpf)))
        found.ids() shouldContainExactly listOf(asHolder, asGuest)
        byDocument(mapOf("type" to "CPF", "number" to cpf, "statuses" to listOf("CANCELLED"))).ids().shouldBeEmpty()
        byDocument(mapOf("type" to "CPF", "number" to newCpf())).ids().shouldBeEmpty()
        with(iam) {
            call(
                POST,
                "/api/v1/reservations/search-by-document",
                support.admin,
                mapOf(
                    "type" to "CPF",
                    "number" to "123",
                ),
            ).problem(400, "DOCUMENT_NUMBER_INVALID")
        }
    }

    @Test
    fun `the search refuses a period ending before it starts, a one-letter name and a user without the permission`() {
        with(iam) {
            call(GET, "/api/v1/reservations?from=${today.plusDays(3)}&to=${today.plusDays(2)}", support.admin)
                .problem(400, "RESERVATION_PERIOD_INVALID")["errors"][0]["field"]
                .asString() shouldBe "to"
            call(GET, "/api/v1/reservations?name=a", support.admin).problem(400, "VALIDATION_FAILED")
            val outsider = iam.createUserWith(TestGrant(definition(BillingPermissions.FOLIOS_READ), ALL))
            for (path in listOf(
                "",
                "/arrivals",
                "/departures",
                "/in-house",
                "/expected-no-shows",
                "/deposit-shortfalls",
            )) {
                call(GET, "/api/v1/reservations$path", iam.asTestUser(outsider)).problem(403, "PERMISSION_DENIED")
            }
            call(
                POST,
                "/api/v1/reservations/search-by-document",
                iam.asTestUser(outsider),
                mapOf("type" to "CPF", "number" to newCpf()),
            ).problem(403, "PERMISSION_DENIED")
        }
    }

    // --- Listas operacionais (RF-RES-13) --------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // as quatro listas com as pendências e o relógio à frente da data operacional
    fun `the operational lists follow the business date of the hotel, never the clock`() {
        val (type, rooms) = inventory.roomTypeWithRooms(6, today)
        val plan = planFor(type, today.plusDays(45))
        val arriving = created(type, plan, today, today.plusDays(2))
        val dayUse = created(type, plan, today, today)
        val leavingTomorrow = created(type, plan, today, today.plusDays(1))
        val cancelled = created(type, plan, today, today.plusDays(1))
        with(support.billing) { support.cancel(cancelled).expect(200) }
        val inHouse = created(type, plan, today, today.plusDays(3))
        support.forceInHouse(inHouse, rooms[0])
        // As pendências da data corrente: um day use de ontem que não chegou e um hospedado que já devia ter saído.
        val pendingArrival = created(type, plan, today, today)
        moveTo(pendingArrival, today.minusDays(1))
        val pendingDeparture = created(type, plan, today, today)
        moveTo(pendingDeparture, today.minusDays(2))
        support.forceInHouse(pendingDeparture, rooms[1])
        // A chegada na data do relógio adiantado, que não é a data operacional.
        val clockDay = today.plusDays(CLOCK_AHEAD_DAYS)
        val onClockDay = created(type, plan, clockDay, clockDay.plusDays(1))
        val mine =
            setOf(arriving, dayUse, leavingTomorrow, cancelled, inHouse, pendingArrival, pendingDeparture, onClockDay)

        val start = clock.instant()
        try {
            // O relógio 40 dias à frente: a data operacional (padrão das listas) continua a mesma.
            clock.advance(Duration.ofDays(CLOCK_AHEAD_DAYS))
            hotelApi.currentBusinessDate() shouldBe today

            withClue("chegadas da data operacional: menos a cancelada; a que já chegou também") {
                list("arrivals", null, mine).ids() shouldContainExactly
                    listOf(arriving, dayUse, leavingTomorrow, inHouse)
                list("arrivals", clockDay, mine).ids() shouldContainExactly listOf(onClockDay)
                list("arrivals", today.minusDays(1), mine).ids() shouldContainExactly listOf(pendingArrival)
            }
            withClue("saídas: o day use de hoje e a saída pendente; amanhã, a que sai amanhã") {
                val departures = list("departures", null, mine)
                departures.ids() shouldContainExactly listOf(pendingDeparture, dayUse)
                departures.single { it.id() == pendingDeparture }["departureOverdue"].asBoolean() shouldBe true
                departures.single { it.id() == dayUse }["departureOverdue"].asBoolean() shouldBe false
                list("departures", today.plusDays(1), mine).ids() shouldContainExactly listOf(leavingTomorrow)
            }
            withClue("hospedados: a estadia que alcança a data; a pendente só na data corrente") {
                list("in-house", null, mine).ids() shouldContainExactly listOf(pendingDeparture, inHouse)
                list("in-house", today.plusDays(3), mine).ids() shouldContainExactly listOf(inHouse)
                list("in-house", today.plusDays(4), mine).ids().shouldBeEmpty()
            }
            withClue("no-shows previstos: as confirmadas que não chegaram; a chegada pendente na data corrente") {
                val noShows = list("expected-no-shows", null, mine)
                noShows.ids() shouldContainExactly listOf(pendingArrival, arriving, dayUse, leavingTomorrow)
                noShows.single { it.id() == pendingArrival }["arrivalOverdue"].asBoolean() shouldBe true
                noShows.single { it.id() == arriving }["arrivalOverdue"].asBoolean() shouldBe false
                list("expected-no-shows", today.minusDays(1), mine).ids() shouldContainExactly listOf(pendingArrival)
                list("expected-no-shows", clockDay, mine).ids() shouldContainExactly listOf(onClockDay)
            }
        } finally {
            clock.advance(Duration.between(clock.instant(), start))
        }
    }

    @Test
    fun `the arrivals list pages by cursor and brings the assigned room`() {
        val (type, rooms) = inventory.roomTypeWithRooms(3, today)
        val plan = planFor(type, today.plusDays(30))
        val date = today.plusDays(25)
        val ids = (1..3).map { created(type, plan, date, date.plusDays(1)) }
        with(iam) {
            call(POST, "/api/v1/reservations/${ids[1]}/assign-room", support.admin, mapOf("roomId" to rooms[0]))
                .ok()
        }
        val pages = mutableListOf<JsonNode>()
        var cursor: String? = null
        do {
            val page =
                with(iam) {
                    call(
                        GET,
                        "/api/v1/reservations/arrivals?date=$date&limit=1" + cursor?.let { "&cursor=$it" }.orEmpty(),
                        support.admin,
                    ).ok()
                }
            pages.add(page)
            cursor = page["nextCursor"].takeUnless(JsonNode::isNull)?.asString()
        } while (cursor != null)
        val items = pages.flatMap { it["items"].values() }.filter { it.id() in ids }
        items.map { it.id() } shouldContainExactly ids.sorted()
        val assigned = items.single { it.id() == ids[1] }["roomAssignment"]
        assigned["roomId"].asString() shouldBe rooms[0].toString()
        assigned["roomNumber"].asString().isNotBlank() shouldBe true
        items.single { it.id() == ids[0] }["roomAssignment"].isNull shouldBe true
    }

    // --- Depósito abaixo do exigido (decisão 8 da E09) ------------------------------------------------------------

    @Test
    fun `a confirmed deposit reservation whose deposit was refunded below the required is listed`() {
        val (type, _) = inventory.roomTypeWithRooms(2, today)
        val plan = support.plan(listOf(type), guarantee = "DEPOSIT")
        support.setPrices(plan.id, listOf(type), today, today.plusDays(30))
        val short =
            support.created(
                support.booking(
                    support.guest(),
                    support.reservation(type, plan.id, today.plusDays(10), today.plusDays(12)),
                ),
            )["reservations"][0]
        val full =
            support.created(
                support.booking(
                    support.guest(),
                    support.reservation(type, plan.id, today.plusDays(10), today.plusDays(12)),
                ),
            )["reservations"][0]
        val cashier = cashier()
        with(support.billing) {
            openShift(cashier)
            // 2 noites de 400 = 800; a política exige 30% = 240.
            val paid =
                pay(short.folio(), cashier, mapOf("method" to "PIX", "amount" to "240.00", "deposit" to true))
                    .expect(201)
            pay(full.folio(), cashier, mapOf("method" to "PIX", "amount" to "240.00", "deposit" to true)).expect(201)
            support.confirm(short.id(), "DEPOSIT").expect(200)
            support.confirm(full.id(), "DEPOSIT").expect(200)
            call(
                POST,
                "/api/v1/folios/${short.folio()}/payments/${paid.uuid("id")}/refund",
                cashier,
                mapOf("amount" to "40.00", "reason" to "Devolução parcial"),
            ).expect(201)
        }
        val listed =
            with(iam) { call(GET, "/api/v1/reservations/deposit-shortfalls", support.admin).ok() }["items"]
                .values()
                .filter { it["reservation"].id() in setOf(short.id(), full.id()) }
        listed shouldHaveSize 1
        val item = listed.single()
        item["reservation"].id() shouldBe short.id()
        item["requiredDeposit"].asString() shouldBe "240.00"
        item["depositReceived"].asString() shouldBe "200.00"
        item["missing"].asString() shouldBe "40.00"
        // A confirmação não foi desfeita.
        support.status(short.id()) shouldBe "CONFIRMED"
    }

    // --- Índices ---------------------------------------------------------------------------------------------------

    @Test
    fun `every search filter and every list is served by an index, never by a scan of the reservations`(
        @Autowired flyway: Flyway,
    ) {
        // Estatísticas atuais (o banco é compartilhado e muda com os outros testes): o planejador decide com elas.
        JdbcClient
            .create(flyway.configuration.dataSource)
            .sql("ANALYZE reservations.reservation, reservations.reservation_guest, reservations.booking")
            .update()
        val guestIds = listOf(GuestId(UuidV7.generate()))
        val queries =
            mapOf(
                "code" to ReservationQuerySql.search(ReservationSearch(code = "R-2026-000001"), null, LIMIT),
                "bookingCode" to ReservationQuerySql.search(ReservationSearch(bookingCode = "B-2026-1"), null, LIMIT),
                "externalId" to ReservationQuerySql.search(ReservationSearch(externalId = "OTA-1"), null, LIMIT),
                "guests" to ReservationQuerySql.search(ReservationSearch(guestIds = guestIds), null, LIMIT),
                "period" to
                    ReservationQuerySql.search(ReservationSearch(from = today, to = today.plusDays(3)), null, LIMIT),
                "cursor" to ReservationQuerySql.search(ReservationSearch(), today to ReservationId.new(), LIMIT),
                "status" to
                    ReservationQuerySql.search(
                        ReservationSearch(statuses = setOf(ReservationStatus.CONFIRMED)),
                        null,
                        LIMIT,
                    ),
            ) +
                OperationalList.entries.flatMap { list ->
                    listOf(today, today.plusDays(1)).map { date ->
                        "$list $date" to ReservationQuerySql.operational(list, date, today, null, LIMIT)
                    }
                }
        queries.forEach { (name, query) ->
            withClue(name) {
                val plan = explain(query)
                plan shouldNotContain "Seq Scan on reservation r"
                plan shouldNotContain "Seq Scan on reservation h"
                plan shouldNotContain "Seq Scan on reservation_guest"
                plan shouldNotContain "Seq Scan on booking"
            }
        }
        explain(queries.getValue("period")) shouldContain "reservation_stay_period_idx"
        explain(queries.getValue("code")) shouldContain "reservation_code_uk"
        explain(queries.getValue("externalId")) shouldContain "booking_external_idx"
        explain(queries.getValue("${OperationalList.DEPARTURES} ${today.plusDays(1)}")) shouldContain
            "reservation_departure_idx"
        // As listas por situação: o índice parcial da situação ou o da data (qual o planejador prefere depende das
        // estatísticas do banco compartilhado, com hospedadas reais desde o M5 E01); sempre um índice, nunca a tabela.
        for (date in listOf(today, today.plusDays(1))) {
            explain(queries.getValue("${OperationalList.IN_HOUSE} $date")).shouldUseOneOf(
                "reservation_in_house_idx",
                "reservation_arrival_idx",
                "reservation_departure_idx",
            )
            explain(queries.getValue("${OperationalList.EXPECTED_NO_SHOWS} $date")).shouldUseOneOf(
                "reservation_confirmed_arrival_idx",
                "reservation_arrival_idx",
            )
        }
        explain(queries.getValue("status")).shouldUseOneOf(
            "reservation_confirmed_arrival_idx",
            "reservation_arrival_idx",
        )
    }

    /** O plano lê `reservation r` por um dos índices [indexes] (e nunca a tabela inteira). */
    private fun String.shouldUseOneOf(vararg indexes: String) {
        withClue(this) {
            indexes.any {
                contains("using $it on reservation r", ignoreCase = true) ||
                    contains("Bitmap Index Scan on $it", ignoreCase = true)
            } shouldBe true
            this shouldNotContain "Seq Scan on reservation r"
        }
    }

    // --- Apoio -----------------------------------------------------------------------------------------------------

    private fun planFor(
        type: UUID,
        until: LocalDate,
    ): UUID {
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, until)
        return plan.id
    }

    private fun guestNamed(name: String): UUID =
        UUID.fromString(
            support.guests
                .createGuest(
                    support.guests.createBody(
                        profile = support.guests.profileBody(fullName = name),
                        documents = emptyList(),
                    ),
                )["id"]
                .asString(),
        )

    private fun created(
        type: UUID,
        plan: UUID,
        arrival: LocalDate,
        departure: LocalDate,
    ): UUID =
        support
            .created(
                support.booking(support.guest(), support.reservation(type, plan, arrival, departure, adults = 1)),
            ).reservationId()

    /** Leva um day use para [date] (antes da data operacional: a API não cria no passado); sem inventário. */
    private fun moveTo(
        id: UUID,
        date: LocalDate,
    ) {
        jdbc
            .sql("UPDATE reservations.reservation SET arrival = :date, departure = :date WHERE id = :id AND day_use")
            .param("date", date)
            .param("id", id)
            .update() shouldBe 1
    }

    private fun search(query: String): JsonNode =
        with(iam) { call(GET, "/api/v1/reservations?$query", support.admin).ok() }

    private fun all(path: String): List<JsonNode> = support.billing.listAll(path)

    private fun byDocument(body: Map<String, Any?>): JsonNode =
        with(iam) { call(POST, "/api/v1/reservations/search-by-document", support.admin, body).ok() }

    /** Todas as páginas da lista [name] na [date] (sem ela, o padrão), só com as reservas [mine]. */
    private fun list(
        name: String,
        date: LocalDate?,
        mine: Set<UUID>,
    ): List<JsonNode> =
        all("/api/v1/reservations/$name" + date?.let { "?date=$it" }.orEmpty()).filter { it.id() in mine }

    /** O plano da consulta, com a varredura sequencial desligada (sobra só a que nenhum índice evita). */
    private fun explain(query: ReservationQuery): String =
        checkNotNull(
            transactions.execute {
                jdbc.sql("SET LOCAL enable_seqscan = off").update()
                jdbc
                    .sql("EXPLAIN ${query.sql}")
                    .params(query.params)
                    .query(String::class.java)
                    .list()
                    .joinToString("\n")
            },
        )

    private fun cashier(): RequestPostProcessor {
        val user =
            iam.createUserWith(
                TestGrant(definition(BillingPermissions.PAYMENTS_RECEIVE), ALL),
                TestGrant(definition(BillingPermissions.FOLIOS_READ), ALL),
                TestGrant(definition(BillingPermissions.CASHIER_OPERATE), ALL),
                TestGrant(
                    definition(BillingPermissions.PAYMENTS_REFUND),
                    ALL,
                    mapOf(BillingPermissions.MAX_AMOUNT to LimitValue.Unlimited),
                ),
            )
        return asUser(user.id.value, UuidV7.generate())
    }

    private fun JsonNode.ids(): List<UUID> = this["items"].values().map { it.id() }

    private fun List<JsonNode>.ids(): List<UUID> = map { it.id() }

    private fun JsonNode.id(): UUID = UUID.fromString(this["id"].asString())

    private fun JsonNode.folio(): UUID = UUID.fromString(this["folioId"].asString())

    private fun JsonNode.reservationId(): UUID = UUID.fromString(this["reservations"][0]["id"].asString())

    private companion object {
        const val CLOCK_AHEAD_DAYS = 40L
        const val LIMIT = 51
    }
}
