package com.zeny.hotel.frontdesk

import com.zeny.hotel.billing.BillingPermissions
import com.zeny.hotel.billing.BillingTestSupport
import com.zeny.hotel.guests.GuestsTestSupport.Companion.newCpf
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.reservations.ReservationsPermissions
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope.ALL
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Apoio dos testes de API do `frontdesk` desde a E04 (o check-out): a estadia num tipo de UH novo (o banco é
 * compartilhado), o hóspede com os dados mínimos do check-in (H11), o check-in de uma reserva, a política do hotel, o
 * operador de caixa (turno aberto) que quita a conta e as cauções da conta. Os testes do check-in (E02) e do walk-in
 * (E03) mantêm as próprias cópias.
 */
class FrontdeskTestSupport(
    private val iam: IamTestSupport,
    private val support: ReservationsTestSupport,
    private val hotelApi: HotelApi,
    private val roomsApi: RoomsApi,
    private val jsonMapper: JsonMapper,
) {
    private val billing: BillingTestSupport = support.billing
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    /**
     * Uma estadia num tipo de UH novo, com as UHs [rooms] (a primeira é [room], a padrão) e um plano com preço (400,00
     * a noite para dois adultos).
     */
    inner class Stay(
        val type: UUID,
        val rooms: List<UUID>,
        val plan: UUID,
    ) {
        val room: UUID get() = rooms.first()

        /** Uma reserva confirmada de hoje a [departure] (igual a hoje = day use), com a UH [room] alocada. */
        fun book(
            holder: UUID,
            departure: LocalDate = today.plusDays(2),
            room: UUID = this.room,
        ): Booked {
            val created =
                support.created(
                    support.booking(holder, support.reservation(type, plan, today, departure)),
                )["reservations"][0]
            val id = UUID.fromString(created["id"].asString())
            with(iam) {
                call(POST, "/api/v1/reservations/$id/assign-room", iam.asAdmin(), mapOf("roomId" to room)).ok()
            }
            return Booked(id, UUID.fromString(created["folioId"].asString()))
        }

        /** A UH [room] limpa e inspecionada (nasce suja). */
        fun ready(room: UUID = this.room) {
            roomsApi.markClean(RoomId(room), false, RoomStatusSource.MANUAL)
            roomsApi.markInspected(RoomId(room), RoomStatusSource.MANUAL)
        }

        /** Reserva, deixa a UH [room] pronta e faz o check-in de verdade (E02), com a caução opcional. */
        fun checkedIn(
            departure: LocalDate = today.plusDays(2),
            preAuthorization: Map<String, Any?>? = null,
            room: UUID = this.room,
        ): Booked {
            val holder = guest()
            val booked = book(holder, departure, room)
            ready(room)
            checkIn(booked.id, holder, preAuthorization).expect(200)
            return booked
        }
    }

    data class Booked(
        val id: UUID,
        val folio: UUID,
    )

    /** A estadia com [rooms] UHs; com [packages], o plano tem esses pacotes (do `rates`). */
    fun stay(
        packages: List<UUID> = emptyList(),
        rooms: Int = 1,
    ): Stay {
        val (type, created) = support.inventory.roomTypeWithRooms(rooms, today)
        val plan = support.plan(listOf(type), packages = packages)
        support.setPrices(plan.id, listOf(type), today, today.plusDays(PRICED_DAYS))
        return Stay(type, created, plan.id)
    }

    /** Um hóspede brasileiro adulto com CPF (os dados mínimos do check-in, H11). */
    fun guest(): UUID =
        UUID.fromString(
            support.guests
                .createGuest(
                    support.guests.createBody(
                        profile =
                            support.guests.profileBody(
                                extra = mapOf("birthDate" to LocalDate.of(1988, 3, 14), "nationality" to "BR"),
                            ),
                        documents = listOf(support.guests.document("CPF", newCpf())),
                    ),
                )["id"]
                .asString(),
        )

    /** Uma caução de 500,00 válida até [expiresAt]. */
    fun preAuthorization(expiresAt: Instant = Instant.now().plus(5, ChronoUnit.DAYS)): Map<String, Any?> =
        mapOf(
            "amount" to "500.00",
            "expiresAt" to expiresAt.truncatedTo(ChronoUnit.SECONDS),
            "authorizationCode" to "A1B2C3",
            "cardBrand" to "VISA",
            "cardLast4" to "1234",
        )

    /** O check-in da reserva [id] com o hóspede principal [guest] como o administrador. */
    fun checkIn(
        id: UUID,
        guest: UUID,
        preAuthorization: Map<String, Any?>? = null,
    ) = Response(
        billing.call(
            POST,
            "/api/v1/reservations/$id/check-in",
            iam.asAdmin(),
            mapOf(
                "guests" to
                    listOf(
                        mapOf(
                            "guestId" to guest,
                            "primary" to true,
                            "travelReason" to "LAZER",
                            "transportMode" to "AUTOMOVEL",
                        ),
                    ),
                "preAuthorization" to preAuthorization,
            ),
        ),
    )

    /** A resposta com os atalhos do `BillingTestSupport`. */
    inner class Response(
        val raw: MockHttpServletResponse,
    ) {
        fun expect(status: Int): JsonNode = with(billing) { raw.expect(status) }

        fun problem(
            status: Int,
            code: String,
        ): JsonNode = with(billing) { raw.problem(status, code) }
    }

    /** As cauções da conta (todas as situações). */
    fun preAuthorizations(folio: UUID): List<JsonNode> =
        with(iam) { call(GET, "/api/v1/folios/$folio/card-pre-authorizations", asAdmin()).ok() }["items"]
            .values()
            .toList()

    // --- Caixa ----------------------------------------------------------------------------------------------------

    /** Um operador de caixa com o turno aberto (recebe e captura caução no próprio turno). */
    fun cashier(): ReservationsTestSupport.Operator {
        val operator =
            ReservationsTestSupport.Operator(
                iam.createUserWith(
                    billingGrant(BillingPermissions.PAYMENTS_RECEIVE),
                    billingGrant(BillingPermissions.FOLIOS_READ),
                    billingGrant(BillingPermissions.CASHIER_OPERATE),
                    billingGrant(BillingPermissions.SHIFTS_READ),
                ),
            )
        billing.openShift(operator.auth)
        return operator
    }

    /** Recebe [amount] em dinheiro na conta, no turno de [cashier]. */
    fun pay(
        folio: UUID,
        cashier: ReservationsTestSupport.Operator,
        amount: String,
    ) {
        with(billing) { pay(folio, cashier.auth, mapOf("method" to "CASH", "amount" to amount)).expect(201) }
    }

    // --- Política do hotel ----------------------------------------------------------------------------------------

    /** A política vigente do hotel como corpo do `PUT` (para devolvê-la no fim do teste). */
    @Suppress("UNCHECKED_CAST")
    fun policy(): Map<String, Any?> =
        jsonMapper.convertValue(
            with(iam) { call(GET, POLICIES, asAdmin()).ok() },
            Map::class.java,
        ) as Map<String, Any?>

    fun putPolicy(body: Map<String, Any?>) {
        with(iam) { call(PUT, POLICIES, asAdmin(), body, ifMatch = support.guests.etagOf(POLICIES)).ok() }
    }

    // --- Permissões -----------------------------------------------------------------------------------------------

    fun grant(code: PermissionCode): TestGrant =
        TestGrant(
            (FrontdeskPermissions.permissions + ReservationsPermissions.permissions).single { it.code == code },
            ALL,
        )

    private fun billingGrant(code: PermissionCode) = TestGrant(BillingTestSupport.definition(code), ALL)

    private companion object {
        const val PRICED_DAYS = 10L
        const val POLICIES = "/api/v1/hotel/policies"
    }
}
