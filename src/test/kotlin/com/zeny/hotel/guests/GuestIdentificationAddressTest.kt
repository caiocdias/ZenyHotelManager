package com.zeny.hotel.guests

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.BrazilianState
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import java.util.UUID

/** O endereço em `GuestsApi.identification` (M5 E11, Task 6): para a ficha de registro; nunca no `toString`. */
@IntegrationTest
@Import(IamTestConfiguration::class)
class GuestIdentificationAddressTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val guestsApi: GuestsApi,
) {
    private val guests = GuestsTestSupport(iam)

    private fun create(address: Map<String, Any?>?): UUID {
        val profile = guests.profileBody(address = address)
        return UUID.fromString(
            guests.createGuest(guests.createBody(profile = profile, documents = emptyList()))["id"].asString(),
        )
    }

    @Test
    fun `identification returns the address of the profile`() {
        val id = create(guests.address())

        val identification = guestsApi.identification(GuestId(id)).shouldNotBeNull()
        val address = identification.address.shouldNotBeNull()

        address.street shouldBe "Rua das Acácias"
        address.number shouldBe "742"
        address.complement shouldBe "Apto 12"
        address.district shouldBe "Jardim Paulista"
        address.city shouldBe "São Paulo"
        address.state shouldBe BrazilianState.SP
        address.postalCode shouldBe "01407000"
        address.ibgeCityCode shouldBe "3550308"
    }

    @Test
    fun `identification has no address when the profile has none`() {
        guestsApi
            .identification(GuestId(create(null)))
            .shouldNotBeNull()
            .address
            .shouldBeNull()
    }

    @Test
    fun `identification of a merged profile returns the address of the survivor`() {
        val survivorAddress = guests.address() + mapOf("street" to "Avenida do Sobrevivente")
        val survivor = create(survivorAddress)
        val absorbed = create(guests.address() + mapOf("street" to "Rua do Absorvido"))

        with(iam) {
            call(
                POST,
                "/api/v1/guests/$survivor/merge",
                asAdmin(),
                mapOf("mergedGuestId" to absorbed),
                ifMatch = "\"0\"",
            ).expectStatus(200)
        }

        val identification = guestsApi.identification(GuestId(absorbed)).shouldNotBeNull()

        identification.id shouldBe GuestId(survivor)
        identification.address.shouldNotBeNull().street shouldBe "Avenida do Sobrevivente"
    }

    @Test
    fun `identification of an anonymized profile has no address`() {
        val id = create(guests.address())

        with(iam) { call(POST, "/api/v1/guests/$id/anonymize", asAdmin()).expectStatus(200) }

        val identification = guestsApi.identification(GuestId(id)).shouldNotBeNull()
        identification.status shouldBe GuestStatus.ANONYMIZED
        identification.address.shouldBeNull()
    }

    @Test
    fun `toString never shows the address`() {
        val identification = guestsApi.identification(GuestId(create(guests.address()))).shouldNotBeNull()

        identification.toString() shouldNotContain "Acácias"
        identification.toString() shouldNotContain "01407"
        identification.toString() shouldBe
            "GuestIdentification(id=${identification.id}, status=${identification.status}, documents=0)"
    }
}
