package com.zeny.hotel.guests

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.util.UUID

/** `GuestsApi.fnrhData` e a descrição de gênero (M5 E15, Task 5): decifrada para a FNRH, nunca no `toString`. */
@IntegrationTest
@Import(IamTestConfiguration::class)
class GuestsApiFnrhDataTest(
    @Autowired iam: IamTestSupport,
    @Autowired private val guestsApi: GuestsApi,
) {
    private val guests = GuestsTestSupport(iam)

    private fun create(
        gender: String,
        description: String?,
    ): GuestId {
        val profile = guests.profileBody(extra = mapOf("gender" to gender, "genderDescription" to description))
        val body = guests.createGuest(guests.createBody(profile = profile))
        return GuestId(UUID.fromString(body["id"].asString()))
    }

    @Test
    fun `fnrhData returns the decrypted gender description`() {
        val data = guestsApi.fnrhData(create("OTHER", "Pessoa não binária")).shouldNotBeNull()

        data.gender shouldBe Gender.OTHER
        data.genderDescription shouldBe "Pessoa não binária"
    }

    @Test
    fun `fnrhData has no description when the gender is not OTHER`() {
        guestsApi
            .fnrhData(create("MALE", null))
            .shouldNotBeNull()
            .genderDescription
            .shouldBeNull()
    }

    @Test
    fun `toString never shows the gender description`() {
        val data = guestsApi.fnrhData(create("OTHER", "Pessoa não binária")).shouldNotBeNull()

        data.toString() shouldNotContain "binária"
        data.toString() shouldBe "FnrhGuestData(id=${data.id})"
    }
}
