package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhRemoteIds
import com.zeny.hotel.fnrh.application.FnrhSubmissionSender
import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhSubmission
import com.zeny.hotel.frontdesk.FrontdeskPermissions
import com.zeny.hotel.frontdesk.FrontdeskTestSupport
import com.zeny.hotel.guests.GuestsTestSupport.Companion.newCpf
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.MutableClock
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * O apoio dos testes de ataque ao alerta de falha no envio à FNRH (M5 E08, Task 9): uma reserva com check-in de um
 * hóspede com todos os campos preenchidos, submissões gravadas direto no store (uma por operação) e a leitura dos
 * alertas no banco.
 */
@Suppress("LongParameterList") // as portas e os apoios do fluxo real
open class FnrhAlertTestBase(
    iam: IamTestSupport,
    jdbc: JdbcClient,
    hotelApi: HotelApi,
    reservationsApi: ReservationsApi,
    sender: FnrhSubmissionSender,
    store: FnrhSubmissionStore,
    remoteIds: FnrhRemoteIds,
    clock: MutableClock,
    mockMvc: MockMvc,
    jsonMapper: JsonMapper,
    tx: TransactionTemplate,
    roomsApi: RoomsApi,
) : FnrhReservationSendTestBase(
        iam,
        jdbc,
        hotelApi,
        reservationsApi,
        sender,
        store,
        remoteIds,
        clock,
        mockMvc,
        jsonMapper,
        tx,
    ) {
    private val frontdesk = FrontdeskTestSupport(iam, support, hotelApi, roomsApi, jsonMapper)
    private var eventSequence = 0

    protected data class Alert(
        val recipient: UUID,
        val title: String,
        val body: String,
    )

    protected class FullGuest(
        val id: UUID,
        val leaks: List<String>,
    )

    protected fun user(permission: PermissionCode): TestUser =
        iam.createUserWith(TestGrant(FnrhPermissions.permissions.single { it.code == permission }, ALL))

    protected fun alertsOf(reservation: UUID): List<Alert> =
        jdbc
            .sql(
                "SELECT recipient_id, title, body FROM notifications.notification " +
                    "WHERE type = 'notifications.integration-failure' AND body LIKE :code ORDER BY id",
            ).param("code", "%${codeOf(reservation)}%")
            .query { rs, _ ->
                Alert(rs.getObject("recipient_id", UUID::class.java), rs.getString("title"), rs.getString("body"))
            }.list()

    /** Grava uma submissão nova da [operation] da reserva direto no store, sem chamar o Serpro. */
    protected fun submit(
        reservation: UUID,
        operation: FnrhOperation,
    ): UUID {
        val submission =
            FnrhSubmission.create(
                reservationId = reservation,
                reservationCode = codeOf(reservation),
                operation = operation,
                eventKey = "alert-attack:${operation.name}:${++eventSequence}",
                causalVersion = (CAUSAL_BASE + eventSequence),
                performedBy = null,
                occurredAt = clock.instant(),
                now = clock.instant(),
            )
        check(store.recordIfAbsent(submission)) { "a submissão não foi gravada" }
        return submission.id
    }

    /** Um hóspede com todos os campos preenchidos, inclusive nome social e descrição de gênero. */
    protected fun fullGuest(gender: String? = "OTHER"): FullGuest {
        val name = support.guests.uniqueName("Zuleica")
        val social = "Zu ${support.guests.suffix()}"
        val email = support.guests.uniqueEmail()
        val description = "Descrição não binária ${support.guests.suffix()}"
        val cpf = newCpf()
        val id =
            UUID.fromString(
                support.guests
                    .createGuest(
                        support.guests.createBody(
                            profile =
                                support.guests.profileBody(
                                    fullName = name,
                                    email = email,
                                    extra =
                                        mapOf(
                                            "socialName" to social,
                                            "gender" to gender,
                                            "genderDescription" to description.takeIf { gender != null },
                                        ),
                                ),
                            documents = listOf(support.guests.document("CPF", cpf)),
                            sensitiveData = mapOf("raceColor" to "BROWN"),
                        ),
                    )["id"]
                    .asString(),
            )
        return FullGuest(id, listOf(name, social, email, description, cpf, "98765-4321", "BROWN"))
    }

    /** Uma reserva com check-in feito do [guest]: o registro e o check-in já gravados pelo listener. */
    protected fun checkedIn(guest: UUID): UUID {
        val stay = frontdesk.stay()
        val booked = stay.book(guest)
        stay.ready()
        val receptionist = iam.createUserWith(frontdesk.grant(FrontdeskPermissions.STAYS_CHECK_IN))
        val guests =
            listOf(
                mapOf(
                    "guestId" to guest,
                    "primary" to true,
                    "travelReason" to "LAZER_FERIAS",
                    "transportMode" to "AUTOMOVEL",
                    "minorAuthorization" to null,
                ),
            )
        with(support.billing) {
            call(
                POST,
                "/api/v1/reservations/${booked.id}/check-in",
                iam.asTestUser(receptionist),
                mapOf("guests" to guests),
            ).expect(200)
        }
        awaitSubmission(booked.id, FnrhOperation.REGISTER_RESERVATION)
        awaitSubmission(booked.id, FnrhOperation.CHECK_IN)
        return booked.id
    }

    private companion object {
        const val CAUSAL_BASE = 1_000L
    }
}
