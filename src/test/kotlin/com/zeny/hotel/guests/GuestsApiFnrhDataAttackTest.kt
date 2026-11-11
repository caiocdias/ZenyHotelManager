package com.zeny.hotel.guests

import com.zeny.hotel.fnrh.domain.FnrhPersonMapper
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import java.util.UUID

/**
 * Ataque do testador a `GuestsApi.fnrhData` e ao mapeador (M5 E15, Task 5): do `Guest` ao `FnrhPersonData` por um
 * perfil mesclado (a descrição vem do sobrevivente, com o gênero dele) e pelos valores que o cadastro aceita.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class GuestsApiFnrhDataAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val guestsApi: GuestsApi,
) {
    private val guests = GuestsTestSupport(iam)

    private fun create(
        gender: String,
        description: String?,
    ): GuestId {
        val profile = guests.profileBody(extra = mapOf("gender" to gender, "genderDescription" to description))
        val body = guests.createGuest(guests.createBody(profile = profile, documents = emptyList()))
        return GuestId(UUID.fromString(body["id"].asString()))
    }

    private fun merge(
        survivor: GuestId,
        absorbed: GuestId,
        take: List<String> = emptyList(),
    ) {
        val response =
            iam.call(
                POST,
                "/api/v1/guests/${survivor.value}/merge",
                iam.asAdmin(),
                mapOf("mergedGuestId" to absorbed.value, "takeFromMerged" to take),
                ifMatch = guests.etagOf("/api/v1/guests/${survivor.value}"),
            )
        withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 200 }
    }

    private fun person(id: GuestId) = FnrhPersonMapper.toPerson(guestsApi.fnrhData(id).shouldNotBeNull())

    @Test
    fun `a merged profile reads the survivor description with the survivor gender, from either id`() {
        val survivor = create("OTHER", "Sobrevivente ${guests.suffix(6)}")
        val absorbed = create("OTHER", "Absorvido ${guests.suffix(6)}")
        val expected =
            guestsApi
                .fnrhData(survivor)
                .shouldNotBeNull()
                .genderDescription
                .shouldNotBeNull()

        merge(survivor, absorbed)

        for (id in listOf(survivor, absorbed)) {
            val person = person(id)
            person.gender shouldBe "OUTRO"
            person.genderDescription shouldBe expected
        }
    }

    @Test
    fun `a merge that takes the absorbed gender takes its description to the FNRH data`() {
        val survivor = create("OTHER", "Sobrevivente ${guests.suffix(6)}")
        val absorbedDescription = "Absorvido ${guests.suffix(6)}"
        val absorbed = create("OTHER", absorbedDescription)

        merge(survivor, absorbed, listOf("GENDER"))

        person(absorbed).genderDescription shouldBe absorbedDescription
        person(survivor).genderDescription shouldBe absorbedDescription
    }

    @Test
    fun `a merge that turns the survivor into another gender leaves no description in the FNRH data`() {
        val survivor = create("OTHER", "Sobrevivente ${guests.suffix(6)}")
        val absorbed = create("MALE", null)

        merge(survivor, absorbed, listOf("GENDER"))

        for (id in listOf(survivor, absorbed)) {
            guestsApi
                .fnrhData(id)
                .shouldNotBeNull()
                .genderDescription
                .shouldBeNull()
            val person = person(id)
            person.gender shouldBe "HOMEM"
            person.genderDescription.shouldBeNull()
        }
    }

    @Test
    fun `OTHER whose description was sent blank has no description in the FNRH data`() {
        val id = create("OTHER", "   ")

        guestsApi
            .fnrhData(id)
            .shouldNotBeNull()
            .genderDescription
            .shouldBeNull()
        person(id).genderDescription.shouldBeNull()
    }

    @Test
    fun `quotes, backslash and control characters stored by the profile reach the FNRH data unchanged`() {
        val description = "Ele \"dito\" C:\\x\u0001y\u0000z"
        val id = create("OTHER", description)

        guestsApi.fnrhData(id).shouldNotBeNull().genderDescription shouldBe description.trim()
        person(id).genderDescription shouldBe description.trim()
    }
}
