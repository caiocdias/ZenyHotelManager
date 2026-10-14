package com.zeny.hotel.shared

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Endereço brasileiro com o código IBGE do município (7 dígitos, prefixo da UF). */
class AddressTest {
    private fun address(
        state: BrazilianState = BrazilianState.SP,
        ibgeCityCode: String = "3550308",
        postalCode: String = "01310-100",
        street: String = "Avenida Paulista",
        city: String = "São Paulo",
    ) = Address(
        street = street,
        number = "1000",
        complement = "Conjunto 12",
        district = "Bela Vista",
        city = city,
        state = state,
        postalCode = postalCode,
        ibgeCityCode = ibgeCityCode,
    )

    @Test
    fun `normalizes the postal code and keeps the fields`() {
        val address = address()

        address.postalCode shouldBe "01310100"
        address.formattedPostalCode() shouldBe "01310-100"
        address.state.ibgeCode shouldBe "35"
        address.ibgeCityCode shouldBe "3550308"
        address.complement shouldBe "Conjunto 12"
    }

    @Test
    fun `accepts a city in another state`() {
        address(state = BrazilianState.RJ, ibgeCityCode = "3304557", city = "Rio de Janeiro").state shouldBe
            BrazilianState.RJ
        address(state = BrazilianState.DF, ibgeCityCode = "5300108", city = "Brasília").ibgeCityCode shouldBe "5300108"
    }

    @Test
    fun `rejects an IBGE code with the wrong shape or from another state`() {
        shouldThrow<IllegalArgumentException> { address(ibgeCityCode = "355030") }
        shouldThrow<IllegalArgumentException> { address(ibgeCityCode = "35503080") }
        shouldThrow<IllegalArgumentException> { address(ibgeCityCode = "35503A8") }
        shouldThrow<IllegalArgumentException> { address(state = BrazilianState.RJ, ibgeCityCode = "3550308") }
    }

    @Test
    fun `rejects an invalid postal code and blank required fields`() {
        shouldThrow<IllegalArgumentException> { address(postalCode = "0131010") }
        shouldThrow<IllegalArgumentException> { address(postalCode = "01310-10A") }
        shouldThrow<IllegalArgumentException> { address(street = " ") }
        shouldThrow<IllegalArgumentException> { address(city = "") }
    }

    @Test
    fun `knows the 27 federative units by abbreviation`() {
        BrazilianState.entries.size shouldBe 27
        BrazilianState.entries
            .map { it.ibgeCode }
            .toSet()
            .size shouldBe 27
        BrazilianState.valueOf("MG").ibgeCode shouldBe "31"
    }
}
