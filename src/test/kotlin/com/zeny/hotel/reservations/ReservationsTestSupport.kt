package com.zeny.hotel.reservations

import com.zeny.hotel.billing.BillingTestSupport
import com.zeny.hotel.guests.GuestsTestSupport
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.rates.RatePricesTestSupport
import com.zeny.hotel.rates.RatesTestSupport
import com.zeny.hotel.shared.UuidV7
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import com.zeny.hotel.testsupport.asUser as authenticatedAs

/** Os eventos `ReservationCreated` recebidos pelo listener de teste, como a distribuição (M8) os receberia. */
object ReservationCreatedEvents {
    val received: MutableList<ReservationCreated> = CopyOnWriteArrayList()

    fun of(bookingId: UUID): List<ReservationCreated> = received.filter { it.bookingId == bookingId }
}

/** Listener assíncrono e transacional de teste: com ele, a publicação passa pelo outbox. */
open class ReservationCreatedProbeListener {
    @ApplicationModuleListener
    open fun on(event: ReservationCreated) {
        ReservationCreatedEvents.received += event
    }
}

/** Os eventos do ciclo de vida (E08) recebidos pelo listener de teste. */
object ReservationLifecycleEvents {
    val changed: MutableList<ReservationChanged> = CopyOnWriteArrayList()
    val cancelled: MutableList<ReservationCancelled> = CopyOnWriteArrayList()
    val reactivated: MutableList<ReservationReactivated> = CopyOnWriteArrayList()
    val confirmed: MutableList<ReservationConfirmed> = CopyOnWriteArrayList()
}

/** Listener assíncrono e transacional de teste dos eventos do ciclo de vida: com ele, as publicações vão ao outbox. */
open class ReservationLifecycleProbeListener {
    @ApplicationModuleListener
    open fun on(event: ReservationChanged) {
        ReservationLifecycleEvents.changed += event
    }

    @ApplicationModuleListener
    open fun on(event: ReservationCancelled) {
        ReservationLifecycleEvents.cancelled += event
    }

    @ApplicationModuleListener
    open fun on(event: ReservationReactivated) {
        ReservationLifecycleEvents.reactivated += event
    }

    @ApplicationModuleListener
    open fun on(event: ReservationConfirmed) {
        ReservationLifecycleEvents.confirmed += event
    }
}

/** Os eventos de hospedagem (M5 E01) recebidos pelo listener de teste, como a FNRH (E08) os receberia. */
object StayTransitionsEvents {
    val checkedIn: MutableList<GuestCheckedIn> = CopyOnWriteArrayList()
    val undone: MutableList<GuestCheckInUndone> = CopyOnWriteArrayList()
    val checkedOut: MutableList<GuestCheckedOut> = CopyOnWriteArrayList()
    val moved: MutableList<RoomMoved> = CopyOnWriteArrayList()
}

/** Listener assíncrono e transacional de teste dos eventos de hospedagem: com ele, as publicações vão ao outbox. */
open class StayTransitionsProbeListener {
    @ApplicationModuleListener
    open fun on(event: GuestCheckedIn) {
        StayTransitionsEvents.checkedIn += event
    }

    @ApplicationModuleListener
    open fun on(event: GuestCheckInUndone) {
        StayTransitionsEvents.undone += event
    }

    @ApplicationModuleListener
    open fun on(event: GuestCheckedOut) {
        StayTransitionsEvents.checkedOut += event
    }

    @ApplicationModuleListener
    open fun on(event: RoomMoved) {
        StayTransitionsEvents.moved += event
    }
}

/**
 * Apoio dos testes das reservas (M4 E07): cria planos com políticas e preços, hóspedes e reservas pela API (com
 * `Idempotency-Key`, pelo `BillingTestSupport`) e lê as reservas do banco. Cada teste cria os próprios tipos de UH
 * ([InventoryTestSupport]): o banco é compartilhado.
 */
class ReservationsTestSupport(
    private val iam: IamTestSupport,
    mockMvc: MockMvc,
    jsonMapper: JsonMapper,
    private val jdbc: JdbcClient,
) {
    val inventory = InventoryTestSupport(iam, jdbc)
    val rates = RatesTestSupport(iam)
    val prices = RatePricesTestSupport(iam, jsonMapper)
    val guests = GuestsTestSupport(iam)
    val billing = BillingTestSupport(iam, mockMvc, jsonMapper)
    val admin: RequestPostProcessor get() = iam.asAdmin()

    /** Um usuário com sessão fixa (a aprovação de superior fica vinculada a ela). */
    class Operator(
        val user: TestUser,
        val sessionId: UUID = UuidV7.generate(),
    ) {
        val auth: RequestPostProcessor get() = authenticatedAs(user.id.value, sessionId)
    }

    /**
     * Um plano com os tipos, políticas novas (a garantia [guarantee]: `NONE`, `CARD` ou `DEPOSIT`, este de 30% até 7
     * dias antes) e os canais.
     */
    data class Plan(
        val id: UUID,
        val cancellationPolicy: UUID,
        val guaranteePolicy: UUID,
    )

    fun plan(
        types: List<UUID>,
        guarantee: String = "NONE",
        channels: List<String> = listOf("DIRECT", "WEBSITE"),
        packages: List<UUID> = emptyList(),
        cancellationBody: Map<String, Any?> = rates.cancellationBody(),
    ): Plan {
        val cancellation = rates.createCancellationPolicy(cancellationBody)
        val guaranteePolicy =
            rates.createGuaranteePolicy(
                when (guarantee) {
                    "NONE", "CARD" -> rates.guaranteeBody(type = guarantee, depositPercent = null, dueDaysBefore = null)
                    else -> rates.guaranteeBody()
                },
            )
        val body = rates.planBody(types, cancellation, guaranteePolicy, packages, channels = channels)
        val id =
            with(
                iam,
            ) { UUID.fromString(call(POST, "/api/v1/rate-plans", admin, body).expectStatus(201)!!["id"].asString()) }
        return Plan(id, cancellation, guaranteePolicy)
    }

    /** 300 (um adulto), 400 (dois), 120 (adulto extra) por noite, de [from] a [to]. */
    @Suppress("LongParameterList") // a seleção e os preços, com padrões
    fun setPrices(
        plan: UUID,
        types: List<UUID>,
        from: LocalDate,
        to: LocalDate,
        oneAdult: String = "300.00",
        twoAdults: String = "400.00",
    ) = with(iam) {
        prices.update(plan, prices.selection(types, from, to), prices.prices(oneAdult, twoAdults, "120.00")).ok()
    }

    /** Um hóspede novo, sem documentos (dois perfis com CPFs diferentes não se mesclam). */
    fun guest(): UUID = UUID.fromString(guests.createGuest(guests.createBody(documents = emptyList()))["id"].asString())

    @Suppress("LongParameterList") // a reserva inteira, com padrões
    fun reservation(
        type: UUID,
        plan: UUID,
        arrival: LocalDate,
        departure: LocalDate,
        adults: Int = 2,
        childAges: List<Int> = emptyList(),
        segment: String = "LEISURE",
        specialRequests: String? = null,
        guests: List<Pair<UUID, Boolean>> = emptyList(),
        nightlyRates: List<Pair<LocalDate, String>> = emptyList(),
    ): Map<String, Any?> =
        mapOf(
            "roomTypeId" to type,
            "ratePlanId" to plan,
            "arrival" to arrival,
            "departure" to departure,
            "adults" to adults,
            "childAges" to childAges,
            "segment" to segment,
            "specialRequests" to specialRequests,
            "guests" to guests.map { (id, primary) -> mapOf("guestId" to id, "primary" to primary) },
            "nightlyRates" to nightlyRates.map { (date, amount) -> mapOf("date" to date, "roomAmount" to amount) },
        )

    fun booking(
        holder: UUID,
        vararg reservations: Map<String, Any?>,
        origin: String = "PHONE",
        externalId: String? = null,
    ): Map<String, Any?> =
        mapOf(
            "holderGuestId" to holder,
            "origin" to origin,
            "externalId" to externalId,
            "reservations" to reservations.toList(),
        )

    fun create(
        body: Map<String, Any?>,
        auth: RequestPostProcessor = admin,
        idempotencyKey: String = UuidV7.generate().toString(),
        approvalToken: String? = null,
    ): MockHttpServletResponse =
        billing.call(
            POST,
            "/api/v1/bookings",
            auth,
            body,
            idempotencyKey = idempotencyKey,
            approvalToken = approvalToken,
        )

    fun created(body: Map<String, Any?>): JsonNode = with(billing) { create(body).expect(201) }

    fun reservation(id: UUID): JsonNode = with(iam) { call(GET, "/api/v1/reservations/$id", admin).ok() }

    // --- Ciclo de vida (E08) -----------------------------------------------------------------------------------

    /** O corpo da alteração a partir da reserva [current] (a resposta da API), com as trocas pedidas. */
    @Suppress("LongParameterList") // os campos da alteração, com padrões
    fun changeBody(
        current: JsonNode,
        type: UUID = UUID.fromString(current["roomTypeId"].asString()),
        plan: UUID = UUID.fromString(current["ratePlanId"].asString()),
        arrival: LocalDate = LocalDate.parse(current["arrival"].asString()),
        departure: LocalDate = LocalDate.parse(current["departure"].asString()),
        adults: Int = current["adults"].asInt(),
        childAges: List<Int> = current["childAges"].values().map { it.asInt() },
        segment: String = current["segment"].asString(),
        specialRequests: String? = current["specialRequests"].takeUnless { it.isNull }?.asString(),
        nightlyRates: List<Pair<LocalDate, String>> = emptyList(),
    ): Map<String, Any?> =
        mapOf(
            "roomTypeId" to type,
            "ratePlanId" to plan,
            "arrival" to arrival,
            "departure" to departure,
            "adults" to adults,
            "childAges" to childAges,
            "segment" to segment,
            "specialRequests" to specialRequests,
            "nightlyRates" to nightlyRates.map { (date, amount) -> mapOf("date" to date, "roomAmount" to amount) },
        )

    fun change(
        id: UUID,
        body: Map<String, Any?>,
        ifMatch: String? = etag(id),
        auth: RequestPostProcessor = admin,
        approvalToken: String? = null,
    ): MockHttpServletResponse =
        billing.call(PUT, "/api/v1/reservations/$id", auth, body, approvalToken = approvalToken, ifMatch = ifMatch)

    @Suppress("LongParameterList") // o pedido e os cabeçalhos, com padrões
    fun cancel(
        id: UUID,
        reason: String = "GUEST_REQUEST",
        notes: String? = null,
        waivePenalty: Boolean = false,
        auth: RequestPostProcessor = admin,
        idempotencyKey: String = UuidV7.generate().toString(),
        approvalToken: String? = null,
    ): MockHttpServletResponse =
        billing.call(
            POST,
            "/api/v1/reservations/$id/cancel",
            auth,
            mapOf("reason" to reason, "notes" to notes, "waivePenalty" to waivePenalty),
            idempotencyKey = idempotencyKey,
            approvalToken = approvalToken,
        )

    fun reactivate(
        id: UUID,
        auth: RequestPostProcessor = admin,
        idempotencyKey: String = UuidV7.generate().toString(),
        approvalToken: String? = null,
    ): MockHttpServletResponse =
        billing.call(
            POST,
            "/api/v1/reservations/$id/reactivate",
            auth,
            idempotencyKey = idempotencyKey,
            approvalToken = approvalToken,
        )

    /** Confirma a pré-reserva com a garantia [guarantee] (`CARD` ou `DEPOSIT`). */
    fun confirm(
        id: UUID,
        guarantee: String,
        auth: RequestPostProcessor = admin,
        idempotencyKey: String = UuidV7.generate().toString(),
    ): MockHttpServletResponse =
        billing.call(
            POST,
            "/api/v1/reservations/$id/confirm",
            auth,
            mapOf("guarantee" to guarantee),
            idempotencyKey = idempotencyKey,
        )

    fun etag(id: UUID): String =
        checkNotNull(with(iam) { call(GET, "/api/v1/reservations/$id", admin) }.getHeader("ETag"))

    // --- Grupos e bloqueios (E11) ---------------------------------------------------------------------------------

    /** Uma faixa de unidades do bloqueio: o tipo, as noites de [from] a [to] (inclusive), a quantidade e a tarifa. */
    fun allocation(
        type: UUID,
        from: LocalDate,
        to: LocalDate,
        quantity: Int,
        rate: String = "250.00",
    ): Map<String, Any?> =
        mapOf("roomTypeId" to type, "from" to from, "to" to to, "quantity" to quantity, "rate" to rate)

    @Suppress("LongParameterList") // o bloqueio inteiro, com padrões
    fun blockBody(
        plan: UUID,
        cutoff: LocalDate,
        vararg allocations: Map<String, Any?>,
        holderGuest: UUID? = null,
        holderCompany: UUID? = null,
        name: String = "Casamento ${UuidV7.generate().toString().takeLast(SUFFIX)}",
    ): Map<String, Any?> =
        mapOf(
            "name" to name,
            "holderGuestId" to holderGuest,
            "holderCompanyId" to holderCompany,
            "ratePlanId" to plan,
            "cutoffDate" to cutoff,
            "allocations" to allocations.toList(),
        )

    fun createBlock(
        body: Map<String, Any?>,
        auth: RequestPostProcessor = admin,
        idempotencyKey: String = UuidV7.generate().toString(),
    ): MockHttpServletResponse = billing.call(POST, "/api/v1/room-blocks", auth, body, idempotencyKey = idempotencyKey)

    fun createdBlock(body: Map<String, Any?>): JsonNode = with(billing) { createBlock(body).expect(201) }

    fun block(id: UUID): JsonNode = with(iam) { call(GET, "/api/v1/room-blocks/$id", admin).ok() }

    /** Uma reserva do pick-up (tipo, chegada, saída, ocupação e hóspedes). */
    fun blockReservation(
        type: UUID,
        arrival: LocalDate,
        departure: LocalDate,
        adults: Int = 2,
        guests: List<Pair<UUID, Boolean>> = emptyList(),
    ): Map<String, Any?> =
        mapOf(
            "roomTypeId" to type,
            "arrival" to arrival,
            "departure" to departure,
            "adults" to adults,
            "guests" to guests.map { (id, primary) -> mapOf("guestId" to id, "primary" to primary) },
        )

    fun pickUp(
        block: UUID,
        reservation: Map<String, Any?>,
        holder: UUID? = null,
        auth: RequestPostProcessor = admin,
        idempotencyKey: String = UuidV7.generate().toString(),
    ): MockHttpServletResponse =
        billing.call(
            POST,
            "/api/v1/room-blocks/$block/pick-up",
            auth,
            mapOf("holderGuestId" to holder, "origin" to "EMAIL", "reservation" to reservation),
            idempotencyKey = idempotencyKey,
        )

    fun roomingList(
        block: UUID,
        reservations: List<Map<String, Any?>>,
        holder: UUID? = null,
        auth: RequestPostProcessor = admin,
        idempotencyKey: String = UuidV7.generate().toString(),
    ): MockHttpServletResponse =
        billing.call(
            POST,
            "/api/v1/room-blocks/$block/rooming-list",
            auth,
            mapOf("holderGuestId" to holder, "origin" to "EMAIL", "reservations" to reservations),
            idempotencyKey = idempotencyKey,
        )

    fun blockEtag(id: UUID): String =
        checkNotNull(with(iam) { call(GET, "/api/v1/room-blocks/$id", admin) }.getHeader("ETag"))

    fun changeBlock(
        id: UUID,
        body: Map<String, Any?>,
        ifMatch: String? = blockEtag(id),
        auth: RequestPostProcessor = admin,
    ): MockHttpServletResponse = billing.call(PUT, "/api/v1/room-blocks/$id", auth, body, ifMatch = ifMatch)

    fun blocksNamed(name: String): Int =
        jdbc
            .sql("SELECT count(*) FROM reservations.room_block WHERE name = :name")
            .param("name", name)
            .query(Int::class.java)
            .single()

    /** As reservas criadas pelo pick-up do bloqueio (todas as situações). */
    fun blockReservations(block: UUID): List<UUID> =
        jdbc
            .sql("SELECT id FROM reservations.reservation WHERE block_id = :block ORDER BY id")
            .param("block", block)
            .query(UUID::class.java)
            .list()
            .filterNotNull()

    /** As unidades do bloqueio numa noite: (quantidade, convertidas, liberadas). */
    fun allocationRow(
        block: UUID,
        type: UUID,
        date: LocalDate,
    ): Triple<Int, Int, Int>? =
        jdbc
            .sql(
                "SELECT quantity, picked_up, released FROM reservations.block_allocation " +
                    "WHERE block_id = :block AND room_type_id = :type AND stay_date = :date",
            ).param("block", block)
            .param("type", type)
            .param("date", date)
            .query { rs, _ -> Triple(rs.getInt("quantity"), rs.getInt("picked_up"), rs.getInt("released")) }
            .optional()
            .orElse(null)

    /** Os lançamentos da conta: o tipo, o código do sistema (`system_key`), o valor e a descrição. */
    fun postings(folio: UUID): List<Map<String, Any?>> =
        jdbc
            .sql(
                "SELECT p.posting_type, c.system_key, p.amount, p.source, p.description, p.business_date " +
                    "FROM billing.posting p JOIN billing.transaction_code c ON c.id = p.transaction_code_id " +
                    "WHERE p.folio_id = :folio ORDER BY p.posted_at, p.id",
            ).param("folio", folio)
            .query { rs, _ ->
                mapOf(
                    "type" to rs.getString("posting_type"),
                    "key" to rs.getString("system_key"),
                    "amount" to rs.getBigDecimal("amount").toPlainString(),
                    "source" to rs.getString("source"),
                    "description" to rs.getString("description"),
                    "businessDate" to rs.getObject("business_date", LocalDate::class.java),
                )
            }.list()

    // --- Hospedagem (M5 E01) ---------------------------------------------------------------------------------------

    /**
     * Grava a reserva como hospedada direto no banco (para os testes que precisam de um hospedado no passado, que a
     * API não cria): a alocação ativa na UH [room], do início [startsOn] à saída, e o check-in na chegada, num só
     * comando (os gatilhos adiados conferem no commit que a hospedada tem UH).
     */
    fun forceInHouse(
        id: UUID,
        room: UUID,
    ) = jdbc
        .sql(
            """
            WITH assigned AS (
                INSERT INTO reservations.room_assignment (id, reservation_id, room_id, starts_on, ends_on, reason,
                    assigned_at)
                SELECT CAST(:assignment AS uuid), r.id, CAST(:room AS uuid), r.arrival, r.departure, 'INITIAL', now()
                FROM reservations.reservation r WHERE r.id = :id
                RETURNING reservation_id
            )
            UPDATE reservations.reservation r SET status = 'IN_HOUSE', provisional_until = NULL,
                checked_in_on = r.arrival, checked_in_at = now()
            FROM assigned a WHERE r.id = a.reservation_id
            """.trimIndent(),
        ).param("assignment", UuidV7.generate())
        .param("room", room)
        .param("id", id)
        .update()

    /** Grava a reserva (sem UH alocada) como encerrada direto no banco. */
    fun forceCheckedOut(id: UUID) =
        jdbc
            .sql(
                """
                UPDATE reservations.reservation SET status = 'CHECKED_OUT', provisional_until = NULL,
                    checked_in_on = arrival, checked_in_at = now(), checked_out_on = departure, checked_out_at = now()
                WHERE id = :id
                """.trimIndent(),
            ).param("id", id)
            .update()

    fun status(id: UUID): String =
        jdbc
            .sql("SELECT status FROM reservations.reservation WHERE id = :id")
            .param("id", id)
            .query(String::class.java)
            .single()

    fun historyKinds(id: UUID): List<String> =
        jdbc
            .sql(
                "SELECT kind FROM reservations.reservation_history WHERE reservation_id = :id ORDER BY occurred_at, id",
            ).param("id", id)
            .query(String::class.java)
            .list()
            .filterNotNull()

    /** As publicações de um evento do ciclo de vida da reserva pelo outbox (as do listener de teste). */
    fun lifecyclePublications(
        type: Class<*>,
        reservationId: UUID,
        listener: String = "ReservationLifecycleProbeListener",
    ): List<String> =
        jdbc
            .sql(
                "SELECT serialized_event FROM shared.event_publication WHERE event_type = :type " +
                    "AND serialized_event LIKE :reservation AND listener_id LIKE :listener",
            ).param("type", type.name)
            .param("reservation", "%\"reservationId\":\"$reservationId\"%")
            .param("listener", "%$listener%")
            .query(String::class.java)
            .list()
            .filterNotNull()

    fun bookingCount(type: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM reservations.reservation WHERE room_type_id = :type")
            .param("type", type)
            .query(Int::class.java)
            .single()

    fun reservationFolioCount(): Int =
        jdbc
            .sql("SELECT count(*) FROM billing.folio WHERE owner_type = 'RESERVATION'")
            .query(Int::class.java)
            .single()

    fun auditEntries(entityId: UUID): List<Map<String, Any?>> =
        jdbc
            .sql(
                "SELECT action, user_id, approval_id, before_state::text AS before, after_state::text AS after " +
                    "FROM audit.audit_entry WHERE entity_id = :id ORDER BY occurred_at, id",
            ).param("id", entityId.toString())
            .query { rs, _ ->
                mapOf(
                    "action" to rs.getString("action"),
                    "userId" to rs.getObject("user_id", UUID::class.java),
                    "approvalId" to rs.getObject("approval_id", UUID::class.java),
                    "before" to rs.getString("before"),
                    "after" to rs.getString("after"),
                )
            }.list()

    /** As publicações de `ReservationCreated` da reserva agrupadora pelo outbox (as do listener de teste). */
    fun createdPublications(bookingId: UUID): List<String> =
        jdbc
            .sql(
                "SELECT serialized_event FROM shared.event_publication WHERE event_type = :type " +
                    "AND serialized_event LIKE :booking AND listener_id LIKE :listener",
            ).param("type", ReservationCreated::class.java.name)
            .param("booking", "%\"bookingId\":\"$bookingId\"%")
            .param("listener", "%ReservationCreatedProbeListener%")
            .query(String::class.java)
            .list()
            .filterNotNull()

    private companion object {
        const val SUFFIX = 6
    }
}
