package com.zeny.hotel.housekeeping.domain

import com.zeny.hotel.housekeeping.LostAndFoundItemId
import com.zeny.hotel.housekeeping.OccurrenceSource
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.UserId
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * O vencimento do item guardado (M6 E06, D2-E06) usa a data local no fuso do **hotel**, não o da JVM (a dos testes
 * roda em America/Sao_Paulo): as duas bordas em fusos diferentes dele, e `expiryCutoff` coerente com `isExpired`.
 */
class LostAndFoundItemExpiryTest {
    private val now = Instant.parse("2026-11-01T00:00:00Z")
    private val retention = 5

    private fun itemFoundAt(foundAt: Instant) =
        LostAndFoundItem.register(
            LostAndFoundItemId.new(),
            RoomId(UUID.randomUUID()),
            null,
            "Carteira",
            null,
            UserId(UUID.randomUUID()),
            foundAt,
            OccurrenceSource.MANUAL,
            now,
        )

    @Test
    fun `in Tokyo the local date of the find decides, on the day it expires and the day before`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        // 01/11 15:30Z = 02/11 00:30 em Tóquio (mas 01/11 12:30 em São Paulo): vence em 07/11, não em 06/11.
        val item = itemFoundAt(Instant.parse("2026-11-01T15:30:00Z"))

        withClue("06/11 ainda dentro do prazo") { item.isExpired(LocalDate.parse("2026-11-06"), tokyo, retention) shouldBe false }
        withClue("07/11 vence") { item.isExpired(LocalDate.parse("2026-11-07"), tokyo, retention) shouldBe true }
        // No fuso da JVM (São Paulo) venceria um dia antes: prova que o fuso do hotel é o usado.
        withClue("fuso de São Paulo diverge") {
            item.isExpired(LocalDate.parse("2026-11-06"), ZoneId.of("America/Sao_Paulo"), retention) shouldBe true
        }
    }

    @Test
    fun `in Manaus a find at night belongs to its local date, on both borders`() {
        val manaus = ZoneId.of("America/Manaus")
        // 02/11 02:00Z = 01/11 22:00 em Manaus (UTC-4): data local 01/11, vence em 06/11.
        val item = itemFoundAt(Instant.parse("2026-11-02T02:00:00Z"))

        item.isExpired(LocalDate.parse("2026-11-05"), manaus, retention) shouldBe false
        item.isExpired(LocalDate.parse("2026-11-06"), manaus, retention) shouldBe true
        // Em UTC a data seria 02/11 e venceria em 07/11.
        item.isExpired(LocalDate.parse("2026-11-06"), ZoneId.of("UTC"), retention) shouldBe false
    }

    @Test
    fun `the expiry cutoff agrees with isExpired around the local midnight in zones other than the JVM one`() {
        val businessDate = LocalDate.parse("2026-11-07")
        for (zoneId in listOf("Asia/Tokyo", "America/Manaus", "UTC", "Pacific/Kiritimati")) {
            val zone = ZoneId.of(zoneId)
            val cutoff = LostAndFoundItem.expiryCutoff(businessDate, zone, retention)
            for (offsetSeconds in listOf(-1L, 0L, 1L)) {
                val foundAt = cutoff.plusSeconds(offsetSeconds)
                withClue("$zoneId, cutoff ${offsetSeconds}s") {
                    itemFoundAt(foundAt).isExpired(businessDate, zone, retention) shouldBe (foundAt < cutoff)
                }
            }
        }
    }

    @Test
    fun `an item no longer stored never expires`() {
        val item = itemFoundAt(Instant.parse("2026-10-01T12:00:00Z"))
        item.returnTo("Fulano", UserId(UUID.randomUUID()), now)

        item.isExpired(LocalDate.parse("2026-12-01"), ZoneId.of("Asia/Tokyo"), retention) shouldBe false
    }
}
