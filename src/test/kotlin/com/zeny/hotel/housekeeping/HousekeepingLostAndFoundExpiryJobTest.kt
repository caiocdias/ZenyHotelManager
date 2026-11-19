package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.infrastructure.LostAndFoundExpiryJob
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * O prazo de guarda dos achados (M6 E06, Task 3, D3): o job, chamado direto (adiado no `@IntegrationTest`), avisa uma
 * vez cada item guardado e vencido a quem tem `housekeeping.lostfound.manage`, com o tipo
 * `housekeeping.lost-found-expired`, só com o id, a UH e a data em que foi achado (sem texto livre), sem dar destino ao
 * item; a segunda execução não repete o aviso. Banco compartilhado: afirma só sobre os próprios itens.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class HousekeepingLostAndFoundExpiryJobTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired private val job: LostAndFoundExpiryJob,
    @Autowired staffApi: StaffApi,
) {
    private val support = LostAndFoundTestSupport(iam, jdbc)
    private val execution = HousekeepingExecutionTestSupport(iam, jdbc, staffApi)

    @Test
    fun `the job notifies each expired stored item once to who manages lost and found, without free text`() {
        val reception = execution.userWithTemplate(RoleTemplate.RECEPTIONIST)
        val attendant = execution.userWithTemplate(RoleTemplate.ROOM_ATTENDANT)
        val secret = "Relógio dourado ${UUID.randomUUID().toString().takeLast(8)}"
        val place = "Cofre ${UUID.randomUUID().toString().takeLast(8)}"
        val room = support.housekeeping.createRoom()
        val expired = registered(room, secret, place)
        val fresh = registered(support.housekeeping.createRoom(), secret, place)
        val returned = registered(support.housekeeping.createRoom(), secret, place)
        val retention = hotel.operationalSettings().lostAndFoundRetentionDays.toLong()
        val zone = hotel.timezone()
        val foundDate = hotel.currentBusinessDate().minusDays(retention + 1)
        support.foundAt(expired, foundDate.atTime(LocalTime.NOON).atZone(zone).toInstant())
        support.foundAt(returned, foundDate.atTime(LocalTime.NOON).atZone(zone).toInstant())
        support.foundAt(
            fresh,
            hotel
                .currentBusinessDate()
                .atTime(LocalTime.NOON)
                .atZone(zone)
                .toInstant(),
        )
        with(iam) {
            support.act(returned, "return", asAdmin(), mapOf("returnedTo" to "Fulano")).ok()
        }

        job.notifyExpiredItems()

        val notices = notices(reception.id.value, expired)
        assertSoftly {
            notices shouldHaveSize 1
            val (title, body) = notices.single()
            title shouldContain "UH"
            body shouldContain expired.toString()
            body shouldContain DateTimeFormatter.ofPattern("dd/MM/yyyy").format(foundDate)
            (title + body) shouldNotContain secret
            (title + body) shouldNotContain place
            notices(reception.id.value, fresh).shouldBeEmpty()
            notices(reception.id.value, returned).shouldBeEmpty()
            notices(attendant.id.value, expired).shouldBeEmpty()
            support.item(expired)["status"].asString() shouldBe "STORED"
            support.item(expired)["expired"].asBoolean() shouldBe true
            notifiedAt(expired).shouldNotBeNull()
            notifiedAt(fresh).shouldBeNull()
            notifiedAt(returned).shouldBeNull()
        }

        job.notifyExpiredItems()

        notices(reception.id.value, expired) shouldHaveSize 1
        support.item(expired)["status"].asString() shouldBe "STORED"
    }

    private fun registered(
        room: UUID,
        description: String,
        place: String,
    ): UUID =
        with(iam) {
            UUID.fromString(
                support
                    .register(asAdmin(), support.body(roomId = room, description = description, storagePlace = place))
                    .expectStatus(201)!!["id"]
                    .asString(),
            )
        }

    /** Os avisos `housekeeping.lost-found-expired` de [recipient] que citam o item [item]: (título, corpo). */
    private fun notices(
        recipient: UUID,
        item: UUID,
    ): List<Pair<String, String>> =
        jdbc
            .sql(
                "SELECT title, body FROM notifications.notification WHERE recipient_id = :recipient " +
                    "AND type = 'housekeeping.lost-found-expired' AND body LIKE :item",
            ).param("recipient", recipient)
            .param("item", "%$item%")
            .query { rs, _ -> rs.getString("title") to rs.getString("body") }
            .list()

    private fun notifiedAt(item: UUID): Instant? =
        jdbc
            .sql("SELECT expiry_notified_at FROM housekeeping.lost_and_found_item WHERE id = :id")
            .param("id", item)
            .query { rs, _ -> rs.getTimestamp(1)?.toInstant() }
            .list()
            .single()
}
