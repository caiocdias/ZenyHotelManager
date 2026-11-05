package com.zeny.hotel.fnrh.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class IbgeMunicipalitiesTest {
    private val municipalities = IbgeMunicipalities()

    @Test
    fun `resolves the state of known municipalities`() {
        municipalities.ufOf(3550308) shouldBe "SP"
        municipalities.ufOf(3304557) shouldBe "RJ"
        municipalities.ufOf(4303509) shouldBe "RS"
        municipalities.ufOf(5300108) shouldBe "DF"
    }

    @Test
    fun `is null for a code that does not exist`() {
        municipalities.ufOf(9999999) shouldBe null
    }
}
