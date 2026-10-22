package com.zeny.hotel.reservations

import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.rooms.RoomsTestSupport
import com.zeny.hotel.testsupport.CheckInRendezvousProbe
import com.zeny.hotel.testsupport.InventoryListenersQuiescence
import com.zeny.hotel.testsupport.RoomStatusFailureProbe
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.events.ApplicationModuleListener
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Os eventos `AvailabilityChanged` recebidos pelo listener de teste, como o channel manager (M8) os receberia. */
object AvailabilityChangedEvents {
    val received: MutableList<AvailabilityChanged> = CopyOnWriteArrayList()

    fun of(roomTypeId: UUID): List<AvailabilityChanged> = received.filter { it.roomTypeId == roomTypeId }
}

/** Listener assíncrono e transacional de teste: com ele, a publicação passa pelo outbox. */
open class AvailabilityChangedProbeListener {
    @ApplicationModuleListener
    open fun on(event: AvailabilityChanged) {
        AvailabilityChangedEvents.received += event
    }
}

@TestConfiguration(proxyBeanMethods = false)
class InventoryTestConfiguration {
    @Bean
    fun availabilityChangedProbeListener() = AvailabilityChangedProbeListener()

    @Bean
    fun reservationCreatedProbeListener() = ReservationCreatedProbeListener()

    @Bean
    fun reservationLifecycleProbeListener() = ReservationLifecycleProbeListener()

    @Bean
    fun stayTransitionsProbeListener() = StayTransitionsProbeListener()

    /** A falha forçada do check-in do `frontdesk` (M5 E02), inerte para as UHs não armadas. */
    @Bean
    fun roomStatusFailureProbe() = RoomStatusFailureProbe()

    /** O encontro de dois check-ins na mesma UH (M5 E02, B1), inerte para as UHs não armadas. */
    @Bean
    fun checkInRendezvousProbe() = CheckInRendezvousProbe()
}

/** Uma linha do inventário lida direto do banco. */
data class InventoryRow(
    val total: Int,
    val sold: Int,
    val blocked: Int,
    val outOfOrder: Int,
    val overbookingLimit: Int,
) {
    val available: Int get() = total - sold - blocked - outOfOrder + overbookingLimit
}

/**
 * Apoio dos testes do inventário (M4 E05): cada teste cria os **próprios** tipos de UH (o banco é compartilhado, e os
 * contadores de um tipo de outro teste interfeririam) e espera os listeners assíncronos do cadastro de UH e do OOO.
 */
class InventoryTestSupport(
    iam: IamTestSupport,
    private val jdbc: JdbcClient,
) {
    val rooms = RoomsTestSupport(iam)

    /** Um tipo de UH novo com [count] UHs ativas, esperando o inventário refletir todas. */
    fun roomTypeWithRooms(
        count: Int,
        on: LocalDate,
    ): Pair<UUID, List<UUID>> {
        val type = rooms.createRoomType()
        val created = (1..count).map { rooms.createRoom(type) }
        awaitRow(type, on) { it.total == count }
        return type to created
    }

    fun row(
        type: UUID,
        date: LocalDate,
    ): InventoryRow? =
        jdbc
            .sql(
                "SELECT total, sold, blocked, out_of_order, overbooking_limit FROM reservations.room_type_inventory " +
                    "WHERE room_type_id = :type AND stay_date = :date",
            ).param("type", type)
            .param("date", date)
            .query { rs, _ ->
                InventoryRow(
                    rs.getInt("total"),
                    rs.getInt("sold"),
                    rs.getInt("blocked"),
                    rs.getInt("out_of_order"),
                    rs.getInt("overbooking_limit"),
                )
            }.optional()
            .orElse(null)

    fun rowCount(type: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM reservations.room_type_inventory WHERE room_type_id = :type")
            .param("type", type)
            .query(Int::class.java)
            .single()

    /** Espera a linha existir e satisfazer [condition] (os listeners são assíncronos). */
    fun awaitRow(
        type: UUID,
        date: LocalDate,
        condition: (InventoryRow) -> Boolean,
    ): InventoryRow {
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until { row(type, date)?.let(condition) == true }
        return row(type, date)!!
    }

    /** Confere que a linha continua como está depois de os listeners terem tido tempo de rodar. */
    fun settledRow(
        type: UUID,
        date: LocalDate,
    ): InventoryRow {
        Thread.sleep(SETTLE_MILLIS)
        return withClue("linha $type $date") { row(type, date)!! }
    }

    fun shouldHaveHorizon(
        type: UUID,
        today: LocalDate,
    ) {
        val (first, last, count) =
            jdbc
                .sql(
                    "SELECT min(stay_date) AS first, max(stay_date) AS last, count(*) AS rows " +
                        "FROM reservations.room_type_inventory WHERE room_type_id = :type AND stay_date >= :today",
                ).param("type", type)
                .param("today", today)
                .query { rs, _ ->
                    Triple(
                        rs.getObject("first", LocalDate::class.java),
                        rs.getObject("last", LocalDate::class.java),
                        rs.getInt("rows"),
                    )
                }.single()
        first shouldBe today
        last shouldBe today.plusDays(HORIZON_DAYS - 1)
        count shouldBe HORIZON_DAYS.toInt()
    }

    /**
     * Os intervalos (`from` a `to`) das publicações de `AvailabilityChanged` do tipo pelo outbox (as do listener de
     * teste), em ordem de publicação.
     */
    fun availabilityPublications(type: UUID): List<Pair<LocalDate, LocalDate>> =
        jdbc
            .sql(
                "SELECT serialized_event FROM shared.event_publication WHERE event_type = :type " +
                    "AND serialized_event LIKE :roomType AND listener_id LIKE :listener ORDER BY publication_date, id",
            ).param("type", AvailabilityChanged::class.java.name)
            .param("roomType", "%$type%")
            .param("listener", "%AvailabilityChangedProbeListener%")
            .query(String::class.java)
            .list()
            .filterNotNull()
            .map { event ->
                LocalDate.parse(DATE_FROM.find(event)!!.groupValues[1]) to
                    LocalDate.parse(DATE_TO.find(event)!!.groupValues[1])
            }

    /**
     * Roda [action] e devolve as publicações de `AvailabilityChanged` do tipo feitas por ela (e pelos listeners do
     * inventário que ela disparou): espera os listeners antes (as anteriores não contam) e depois.
     */
    fun <T> publishedBy(
        type: UUID,
        action: () -> T,
    ): Pair<T, List<Pair<LocalDate, LocalDate>>> {
        InventoryListenersQuiescence.await(jdbc)
        val before = availabilityPublications(type).size
        val result = action()
        InventoryListenersQuiescence.await(jdbc)
        return result to availabilityPublications(type).drop(before)
    }

    companion object {
        private val DATE_FROM = Regex("\"from\":\"(\\d{4}-\\d{2}-\\d{2})\"")
        private val DATE_TO = Regex("\"to\":\"(\\d{4}-\\d{2}-\\d{2})\"")
        const val TIMEOUT_SECONDS = 15L
        const val SETTLE_MILLIS = 500L
        const val HORIZON_DAYS = 730L
    }
}
