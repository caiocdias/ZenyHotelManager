package com.zeny.hotel.guests

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * Ataque ao endereço em `GuestsApi.identification` (M5 E11, Task 6): a leitura relê o perfil corrente, o endereço
 * some quando removido, segue a cadeia de mesclagens, não sobrevive à anonimização e não vaza em log nem na trilha.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class GuestIdentificationAddressAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val guestsApi: GuestsApi,
    @Autowired private val jdbc: JdbcClient,
) {
    private val guests = GuestsTestSupport(iam)

    private fun create(address: Map<String, Any?>?): UUID {
        val profile = guests.profileBody(address = address)
        return UUID.fromString(
            guests.createGuest(guests.createBody(profile = profile, documents = emptyList()))["id"].asString(),
        )
    }

    private fun identification(id: UUID) = guestsApi.identification(GuestId(id)).shouldNotBeNull()

    private fun merge(
        survivor: UUID,
        absorbed: UUID,
        version: Int,
    ) = with(iam) {
        call(
            POST,
            "/api/v1/guests/$survivor/merge",
            asAdmin(),
            mapOf("mergedGuestId" to absorbed),
            ifMatch = "\"$version\"",
        ).expectStatus(200)
    }

    private fun street(street: String) = guests.address() + mapOf("street" to street)

    private fun updateProfile(
        id: UUID,
        address: Map<String, Any?>?,
        version: Int,
    ) = with(iam) {
        call(PUT, "/api/v1/guests/$id", asAdmin(), guests.profileBody(address = address), ifMatch = "\"$version\"")
            .expectStatus(200)
    }

    @Test
    fun `identification reflects an address changed after creation`() {
        val id = create(street("Rua Antiga"))
        identification(id).address.shouldNotBeNull().street shouldBe "Rua Antiga"

        updateProfile(id, street("Rua Nova"), version = 0)

        identification(id).address.shouldNotBeNull().street shouldBe "Rua Nova"
    }

    @Test
    fun `identification has no address after the address is removed from the profile`() {
        val id = create(guests.address())
        identification(id).address.shouldNotBeNull()

        updateProfile(id, address = null, version = 0)

        identification(id).address.shouldBeNull()
    }

    @Test
    fun `identification of a chain of merges returns the address of the last survivor`() {
        val a = create(street("Rua do A"))
        val b = create(street("Rua do B"))
        val c = create(street("Rua do C"))
        merge(survivor = b, absorbed = a, version = 0)
        merge(survivor = c, absorbed = b, version = 0)

        val fromA = identification(a)

        fromA.id shouldBe GuestId(c)
        fromA.address.shouldNotBeNull().street shouldBe "Rua do C"
        identification(b).address.shouldNotBeNull().street shouldBe "Rua do C"
    }

    @Test
    fun `identification of a merged profile returns no address when the survivor was anonymized afterwards`() {
        val survivor = create(guests.address())
        val absorbed = create(street("Rua do Absorvido"))
        merge(survivor, absorbed, version = 0)

        with(iam) { call(POST, "/api/v1/guests/$survivor/anonymize", asAdmin()).expectStatus(200) }

        val identification = identification(absorbed)
        identification.id shouldBe GuestId(survivor)
        identification.status shouldBe GuestStatus.ANONYMIZED
        identification.address.shouldBeNull()
    }

    @Test
    fun `identification of a restricted profile still returns the address`() {
        val id = create(guests.address())

        with(iam) {
            call(POST, "/api/v1/guests/$id/restrict", asAdmin(), mapOf("reason" to "Danos ao quarto")).expectStatus(200)
        }

        identification(id).address.shouldNotBeNull().street shouldBe "Rua das Acácias"
    }

    @Test
    fun `identification keeps an address without complement and with special characters intact`() {
        val special = "Av. \"São João\" & Cia, nº 5/7 - Ç'ã <b>x</b> 'ü'; DROP TABLE"
        val id = create(guests.address() + mapOf("street" to special, "complement" to null, "number" to "S/N"))

        val address = identification(id).address.shouldNotBeNull()

        address.street shouldBe special
        address.number shouldBe "S/N"
        address.complement.shouldBeNull()
        address.postalCode shouldBe "01407000"
    }

    @Test
    fun `reading the identification writes the address neither to the logs nor to the audit trail`(
        output: CapturedOutput,
    ) {
        val marker = "Rua Marcador ${guests.suffix()}"
        val id = create(street(marker))
        val before = auditCount(id)
        val logStart = output.all.length

        val identification = identification(id)
        identification.toString()
        identification(id)

        auditCount(id) shouldBe before
        jdbc
            .sql("SELECT count(*) FROM audit.audit_entry WHERE entity_id = :id AND action NOT LIKE '%create%'")
            .param("id", id.toString())
            .query(Long::class.java)
            .single() shouldBe 0L
        output.all.substring(logStart) shouldNotContain marker
    }

    private fun auditCount(id: UUID): Long =
        jdbc
            .sql("SELECT count(*) FROM audit.audit_entry WHERE entity_id = :id")
            .param("id", id.toString())
            .query(Long::class.java)
            .single()
}
