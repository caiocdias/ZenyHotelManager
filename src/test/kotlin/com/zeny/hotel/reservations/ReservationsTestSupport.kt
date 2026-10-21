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

    /** Um plano com os tipos, políticas novas (a garantia [guarantee]: `NONE` ou `DEPOSIT`) e os canais. */
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
    ): Plan {
        val cancellation = rates.createCancellationPolicy()
        val guaranteePolicy =
            rates.createGuaranteePolicy(
                if (guarantee == "NONE") {
                    rates.guaranteeBody(type = "NONE", depositPercent = null, dueDaysBefore = null)
                } else {
                    rates.guaranteeBody()
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
}
