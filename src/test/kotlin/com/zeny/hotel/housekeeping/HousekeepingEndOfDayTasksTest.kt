package com.zeny.hotel.housekeeping

import com.zeny.hotel.endofday.EndOfDayRealStepsTestConfiguration
import com.zeny.hotel.endofday.EndOfDayTestRuns
import com.zeny.hotel.frontdesk.FrontdeskTestSupport
import com.zeny.hotel.hotel.BusinessDateAdvanced
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.GuestCheckedOut
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * As tarefas do dia com o fechamento real (M6 E02, Task 3), no banco isolado (H10) e no contexto das etapas reais dos
 * testes do fechamento (E11): o avanço da data publica `BusinessDateAdvanced`, e o listener da governança gera a
 * permanência da data nova. E a ordem A ponta a ponta (Review Focus 1): o check-out pela recepção (que deixa a UH
 * Suja), o listener da saída e só depois o avanço para a data seguinte, numa estadia de uma noite com uma chegada
 * alocada à mesma UH no dia seguinte; a ordem B fica no `HousekeepingDailyTasksTest`, por chamadas diretas.
 *
 * O banco acumula o que os outros testes do fechamento deixaram: o teste afirma só sobre as próprias UHs e termina com
 * [EndOfDayTestRuns.settle].
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class)
@Suppress("LongParameterList") // os apoios do contexto
class HousekeepingEndOfDayTasksTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val rooms: RoomsApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val frontdesk = FrontdeskTestSupport(iam, support, hotel, rooms, jsonMapper)

    @Test
    @Suppress("LongMethod") // os dois fechamentos e o check-out entre eles, com as conferências de cada passo
    fun `the real close generates the stayover, and a check-out before the next close leaves only its CHECKOUT`() {
        val start = hotel.currentBusinessDate()
        val stay = frontdesk.stay(rooms = 2)
        val staying = stay.rooms[0]
        val leavingRoom = stay.rooms[1]
        // A hospedada que fica (de S a S+3) e a de uma noite que sai em D = S+1; a chegada alocada à UH dela em D+1.
        stay.checkedIn(departure = start.plusDays(3), room = staying)
        val leaving = stay.checkedIn(departure = start.plusDays(1), room = leavingRoom)
        reservationsApi.assignRoom(arrival(stay, start.plusDays(2)), RoomId(leavingRoom))
        try {
            closeCurrentDate()
            val date = hotel.currentBusinessDate()
            date shouldBe start.plusDays(1)
            awaitDailyTasks(date)
            tasksOf(staying) shouldBe listOf(date to "STAYOVER")
            // A que sai em D não tem permanência em D (Review Focus 3).
            tasksOf(leavingRoom) shouldBe emptyList()

            checkOut(leaving)
            rooms.status(RoomId(leavingRoom))!!.housekeepingStatus shouldBe HousekeepingStatus.DIRTY
            awaitCheckoutTask(leaving.id)
            closeCurrentDate()
            val next = hotel.currentBusinessDate()
            next shouldBe date.plusDays(1)
            awaitDailyTasks(next)

            // Ordem A: a CHECKOUT em D e nada em D+1 (a UH ficou Suja no check-out), sem nada duplicado.
            tasksOf(leavingRoom) shouldBe listOf(date to "CHECKOUT")
            tasksOf(staying) shouldBe listOf(date to "STAYOVER", next to "STAYOVER")
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private fun closeCurrentDate() {
        val run = EndOfDayTestRuns.closeCurrentDate(context)
        jdbc
            .sql("SELECT status FROM endofday.run WHERE id = :id")
            .param("id", run.value)
            .query(String::class.java)
            .single() shouldBe "COMPLETED"
    }

    /** Uma reserva confirmada de [arrival] a uma noite depois, no tipo e no plano da estadia. */
    private fun arrival(
        stay: FrontdeskTestSupport.Stay,
        arrival: LocalDate,
    ): ReservationId {
        val created =
            support.created(
                support.booking(
                    frontdesk.guest(),
                    support.reservation(stay.type, stay.plan, arrival, arrival.plusDays(1)),
                ),
            )["reservations"][0]
        return ReservationId(UUID.fromString(created["id"].asString()))
    }

    /** O check-out pela recepção (`frontdesk`), com a conta quitada antes. */
    private fun checkOut(booked: FrontdeskTestSupport.Booked) {
        val balance = support.billing.balance(booked.folio)
        if (balance != "0.00") frontdesk.pay(booked.folio, frontdesk.cashier(), balance)
        frontdesk
            .Response(
                support.billing.call(
                    POST,
                    "/api/v1/reservations/${booked.id}/check-out",
                    iam.asAdmin(),
                    null,
                    UuidV7.generate().toString(),
                ),
            ).expect(200)
    }

    /** Espera o listener da governança concluir a virada para [date] (e só ele). */
    private fun awaitDailyTasks(date: LocalDate) {
        awaitListener(BusinessDateAdvanced::class.java, "\"current\":\"$date\"")
    }

    /** Espera o listener da governança concluir a saída da reserva [reservation]. */
    private fun awaitCheckoutTask(reservation: UUID) {
        awaitListener(GuestCheckedOut::class.java, "\"reservationId\":\"$reservation\"")
    }

    private fun awaitListener(
        type: Class<*>,
        fragment: String,
    ) {
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until {
            jdbc
                .sql(
                    "SELECT count(*) FROM shared.event_publication WHERE event_type = :type " +
                        "AND serialized_event LIKE :fragment AND listener_id LIKE :listener " +
                        "AND completion_date IS NOT NULL",
                ).param("type", type.name)
                .param("fragment", "%$fragment%")
                .param("listener", "%housekeeping.application.HousekeepingTaskListeners%")
                .query(Int::class.java)
                .single() == 1
        }
    }

    /** As tarefas da UH, como (data, origem), em ordem de data. */
    private fun tasksOf(room: UUID): List<Pair<LocalDate, String>> =
        jdbc
            .sql(
                "SELECT business_date, source FROM housekeeping.housekeeping_task WHERE room_id = :room " +
                    "ORDER BY business_date, id",
            ).param("room", room)
            .query { rs, _ -> rs.getObject("business_date", LocalDate::class.java) to rs.getString("source") }
            .list()

    private companion object {
        const val TIMEOUT_SECONDS = 30L
    }
}
