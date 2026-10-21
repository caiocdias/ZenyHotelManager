package com.zeny.hotel.rooms

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.modulith.test.EnableScenarios
import org.springframework.modulith.test.Scenario
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier

/** Os eventos `RoomRegistryChanged` recebidos pelo listener de teste, como o inventário (M4) os recebe. */
object RoomRegistryEvents {
    val received: MutableList<RoomRegistryChanged> = CopyOnWriteArrayList()
}

/** Listener assíncrono e transacional de teste: com ele, a publicação passa pelo outbox. */
open class RoomRegistryProbeListener {
    @ApplicationModuleListener
    open fun on(event: RoomRegistryChanged) {
        RoomRegistryEvents.received += event
    }
}

@TestConfiguration(proxyBeanMethods = false)
class RoomRegistryListenerConfiguration {
    @Bean
    fun roomRegistryProbeListener() = RoomRegistryProbeListener()
}

/**
 * `RoomRegistryChanged` (H4 do M4, M4 E05): o cadastro de UH publica pelo outbox, na transação, a criação, a troca de
 * tipo, a desativação e a reativação, com o tipo anterior na troca; nenhuma outra edição publica, repetir uma ação sem
 * efeito não publica, e o contrato HTTP do `rooms` não muda.
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, RoomRegistryListenerConfiguration::class)
class RoomRegistryEventsTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val jsonMapper: JsonMapper,
) {
    private val rooms = RoomsTestSupport(iam)

    @Test
    fun `creating a room publishes RoomRegistryChanged with the type and the version`(scenario: Scenario) {
        val type = rooms.createRoomType()

        scenario
            .stimulate(Supplier { rooms.createRoom(type) })
            .andWaitForEventOfType(RoomRegistryChanged::class.java)
            .matching { it.roomTypeId == type }
            .toArriveAndVerify { event, room ->
                event.roomId shouldBe room
                event.change shouldBe RoomRegistryChange.CREATED
                event.previousRoomTypeId.shouldBeNull()
                event.active shouldBe true
                event.version shouldBe 0
            }
    }

    @Test
    @Suppress("LongMethod") // o ciclo inteiro do cadastro e o que vai para o outbox
    fun `type change, deactivation and reactivation publish, other edits and repeated actions do not`() {
        val single = rooms.createRoomType()
        val double = rooms.createRoomType()
        val room = rooms.createRoom(single)
        val path = "/api/v1/rooms/$room"
        val number = rooms.uniqueNumber()
        with(iam) {
            // Outra edição (o número e as observações) não publica.
            call(PUT, path, asAdmin(), rooms.roomBody(single, number), ifMatch = rooms.etagOf(path)).ok()
            call(PUT, path, asAdmin(), rooms.roomBody(double, number), ifMatch = rooms.etagOf(path)).ok()
            call(POST, "$path/deactivate", asAdmin()).ok()
            call(POST, "$path/deactivate", asAdmin()).ok()
            call(POST, "$path/reactivate", asAdmin()).ok()
            call(POST, "$path/reactivate", asAdmin()).ok()
        }

        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until { events(room).size == 4 }
        Thread.sleep(SETTLE_MILLIS)
        val received = events(room).sortedBy { it.version }
        received.map { it.change } shouldContainExactly
            listOf(
                RoomRegistryChange.CREATED,
                RoomRegistryChange.ROOM_TYPE_CHANGED,
                RoomRegistryChange.DEACTIVATED,
                RoomRegistryChange.REACTIVATED,
            )
        val created = received[0]
        val changed = received[1]
        val deactivated = received[2]
        val reactivated = received[3]
        created.roomTypeId shouldBe single
        changed.roomTypeId shouldBe double
        changed.previousRoomTypeId shouldBe single
        changed.active shouldBe true
        deactivated.roomTypeId shouldBe double
        deactivated.active shouldBe false
        deactivated.previousRoomTypeId.shouldBeNull()
        reactivated.active shouldBe true
        received.map { it.version }.zipWithNext().all { (a, b) -> b > a } shouldBe true

        // O que vai para o outbox: ids, situação, versão e instante; nada de número, andar ou observações.
        val serialized =
            jdbc
                .sql(
                    "SELECT serialized_event FROM shared.event_publication WHERE event_type = :type " +
                        "AND serialized_event LIKE :room AND listener_id LIKE :listener",
                ).param("type", RoomRegistryChanged::class.java.name)
                .param("room", "%$room%")
                .param("listener", "%RoomRegistryProbeListener%")
                .query(String::class.java)
                .list()
                .filterNotNull()
        serialized.size shouldBe 4
        jsonMapper.readTree(serialized.first()).propertyNames().toSet() shouldBe
            setOf("roomId", "roomTypeId", "previousRoomTypeId", "active", "change", "version", "changedAt")
        serialized.none { number in it || "elevador" in it } shouldBe true
    }

    private fun events(room: UUID): List<RoomRegistryChanged> = RoomRegistryEvents.received.filter { it.roomId == room }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        const val SETTLE_MILLIS = 300L
    }
}
