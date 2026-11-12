package com.zeny.hotel.hotel

import com.zeny.hotel.hotel.application.HotelApiService
import com.zeny.hotel.hotel.application.HotelStore
import com.zeny.hotel.hotel.domain.Hotel
import com.zeny.hotel.shared.Address
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.BrazilianState
import com.zeny.hotel.shared.Cnpj
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** `HotelApi.profile()` (M5 E11, D-E11-8): nulo enquanto o hotel não está configurado, com o id do hotel depois. */
class HotelApiServiceProfileTest {
    private val now = Instant.parse("2026-11-11T12:00:00Z")
    private val store = mockk<HotelStore>()
    private val service =
        HotelApiService(
            store,
            mockk<ApplicationEventPublisher>(),
            mockk<AuditContext>(),
            Clock.fixed(now, ZoneId.of("UTC")),
        )

    private val profile =
        HotelProfile(
            legalName = "Pousada Exemplo Ltda",
            tradeName = "Pousada Exemplo",
            cnpj = Cnpj.of("11.222.333/0001-81"),
            municipalRegistration = null,
            cadastur = null,
            address = Address("Rua A", "10", null, "Centro", "São Paulo", BrazilianState.SP, "01310-100", "3550308"),
            timezone = ZoneId.of("America/Sao_Paulo"),
            taxRegime = TaxRegime.SIMPLES_NACIONAL,
            logoFileId = UUID.randomUUID(),
        )

    @Test
    fun `profile is null while the hotel is not configured`() {
        every { store.hotel() } returns Hotel.create(Hotel.DEFAULT_TIMEZONE, now)

        service.profile().shouldBeNull()
    }

    @Test
    fun `profile carries the hotel id and the configured data`() {
        val hotel = Hotel.create(Hotel.DEFAULT_TIMEZONE, now).also { it.configure(profile, now) }
        every { store.hotel() } returns hotel

        service.profile() shouldBe CurrentHotelProfile(hotel.id.value, profile)
    }
}
