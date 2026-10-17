package com.zeny.hotel.guests

import com.zeny.hotel.guests.GuestsTestSupport.Companion.definition
import com.zeny.hotel.guests.GuestsTestSupport.Companion.newCnpj
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Empresas pela API (M3 E01, RF-HOS-06): cadastro com permissão, `If-Match`, cursor e desativação; CNPJ inválido
 * (400) e repetido (409, inclusive o de uma desativada e numa corrida, nunca 500); trilha sem nomes nem contatos.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class CompaniesApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val guestsApi: GuestsApi,
) {
    private val guests = GuestsTestSupport(iam)
    private val read = TestGrant(definition(GuestsPermissions.COMPANIES_READ), ALL)
    private val manage = TestGrant(definition(GuestsPermissions.COMPANIES_MANAGE), ALL)

    @Test
    fun `a company is created, read, edited with If-Match, deactivated and reactivated with permission`(): Unit =
        with(iam) {
            val reader = createUserWith(read)
            val editor = createUserWith(read, manage)
            val cnpj = newCnpj()
            val body = guests.companyBody(cnpj = formatted(cnpj))

            call(POST, "/api/v1/companies", asTestUser(reader), body).problem(403, "PERMISSION_DENIED")
            val created = call(POST, "/api/v1/companies", asTestUser(editor), body)
            val createdBody = created.expectStatus(201).shouldNotBeNull()
            val id = createdBody["id"].asString()
            created.getHeader(HttpHeaders.LOCATION) shouldBe "/api/v1/companies/$id"
            created.getHeader(HttpHeaders.ETAG) shouldBe "\"0\""
            createdBody["cnpj"].asString() shouldBe cnpj
            createdBody["creditLimit"].asString() shouldBe "5000.00"
            createdBody["address"]["ibgeCityCode"].asString() shouldBe "3550308"
            createdBody["active"].asBoolean() shouldBe true

            val path = "/api/v1/companies/$id"
            call(GET, path, asTestUser(reader)).getHeader(HttpHeaders.ETAG) shouldBe "\"0\""
            call(GET, path, asTestUser(createUser())).problem(403, "PERMISSION_DENIED")
            call(GET, "/api/v1/companies/${UUID.randomUUID()}", asTestUser(reader)).problem(404, "COMPANY_NOT_FOUND")

            val edited = body + mapOf("tradeName" to "Ômega Eventos", "creditLimit" to null, "address" to null)
            call(PUT, path, asTestUser(reader), edited, ifMatch = "\"0\"").problem(403, "PERMISSION_DENIED")
            call(PUT, path, asTestUser(editor), edited).problem(428, "PRECONDITION_REQUIRED")
            call(PUT, path, asTestUser(editor), edited, ifMatch = "\"3\"").problem(412, "PRECONDITION_FAILED")
            val updated = call(PUT, path, asTestUser(editor), edited, ifMatch = "\"0\"")
            updated.getHeader(HttpHeaders.ETAG) shouldBe "\"1\""
            updated.ok()["creditLimit"].isNull shouldBe true
            call(PUT, path, asTestUser(editor), body + ("creditLimit" to "-1.00"), ifMatch = "\"1\"")
                .problem(400, "GUESTS_DATA_INVALID")

            call(POST, "$path/deactivate", asTestUser(reader)).problem(403, "PERMISSION_DENIED")
            call(POST, "$path/deactivate", asTestUser(editor)).ok()["active"].asBoolean() shouldBe false
            guestsApi.company(CompanyId(UUID.fromString(id))).shouldNotBeNull().active shouldBe false
            call(POST, "$path/reactivate", asTestUser(editor)).ok()["active"].asBoolean() shouldBe true

            // A trilha: CNPJ, situação e campos alterados; nada de nomes, contatos nem endereço.
            val trail =
                jdbc
                    .sql(
                        "SELECT action, CAST(after_state AS text) AS after_state FROM audit.audit_entry " +
                            "WHERE entity_id = :id ORDER BY occurred_at",
                    ).param("id", id)
                    .query()
                    .listOfRows()
            trail.map { it["action"] } shouldContainExactly
                listOf(
                    "guests.company.create",
                    "guests.company.update",
                    "guests.company.deactivate",
                    "guests.company.reactivate",
                )
            val text = trail.joinToString { it["after_state"].toString() }
            listOf("Viagens", "Ômega", "Carla", "contato.", "financeiro@", "3333-4444", "Acácias").forEach {
                text shouldNotContain it
            }
        }

    @Test
    fun `an invalid CNPJ is 400 and a repeated one 409, even of a deactivated company and in a race`(): Unit =
        with(iam) {
            val invalid =
                call(POST, "/api/v1/companies", asAdmin(), guests.companyBody(cnpj = "11.222.333/0001-82"))
                    .problem(400, "VALIDATION_FAILED")
            invalid["errors"].values().map { it["field"].asString() to it["code"].asString() } shouldContainExactly
                listOf("cnpj" to "INVALID_FORMAT")

            val cnpj = newCnpj()
            val id = guests.createCompany(guests.companyBody(cnpj = cnpj))
            call(POST, "/api/v1/companies/$id/deactivate", asAdmin()).ok()
            call(POST, "/api/v1/companies", asAdmin(), guests.companyBody(cnpj = formatted(cnpj)))
                .problem(409, "CNPJ_ALREADY_IN_USE")
            val other = guests.createCompany()
            call(PUT, "/api/v1/companies/$other", asAdmin(), guests.companyBody(cnpj = cnpj), ifMatch = "\"0\"")
                .problem(409, "CNPJ_ALREADY_IN_USE")

            // Duas criações simultâneas com o mesmo CNPJ: uma 201 e uma 409.
            val raced = newCnpj()
            val start = CountDownLatch(1)
            val statuses = mutableListOf<Int>()
            val threads =
                List(2) {
                    thread {
                        start.await()
                        val status = call(POST, "/api/v1/companies", asAdmin(), guests.companyBody(cnpj = raced)).status
                        synchronized(statuses) { statuses += status }
                    }
                }
            start.countDown()
            threads.forEach { it.join() }
            statuses shouldContainExactlyInAnyOrder listOf(201, 409)
        }

    @Test
    fun `companies are found by name without accents and by CNPJ, page by page`(): Unit =
        with(iam) {
            val reader = createUserWith(read)
            val marker = guests.suffix(10)
            val cnpjs = List(3) { newCnpj() }
            val ids =
                listOf("Água Viva $marker", "Ágape $marker", "Zênite $marker").zip(cnpjs).map { (name, cnpj) ->
                    guests.createCompany(guests.companyBody(cnpj = cnpj, legalName = name)).toString()
                }

            val all = call(GET, "/api/v1/companies?name=$marker", asTestUser(reader)).ok()
            all["items"].values().map { it["id"].asString() } shouldContainExactly listOf(ids[1], ids[0], ids[2])
            call(GET, "/api/v1/companies?name=agua $marker", asTestUser(reader))
                .ok()["items"]
                .values()
                .map { it["id"].asString() } shouldContainExactly listOf(ids[0])
            val first = call(GET, "/api/v1/companies?name=$marker&limit=2", asTestUser(reader)).ok()
            first["items"].size() shouldBe 2
            call(
                GET,
                "/api/v1/companies?name=$marker&limit=2&cursor=${first["nextCursor"].asString()}",
                asTestUser(reader),
            ).ok()["items"]
                .values()
                .map { it["id"].asString() } shouldContainExactly listOf(ids[2])
            call(GET, "/api/v1/companies?cnpj=${formatted(cnpjs[2])}", asTestUser(reader))
                .ok()["items"]
                .values()
                .map { it["id"].asString() } shouldContainExactly listOf(ids[2])
            call(GET, "/api/v1/companies?name=$marker", asTestUser(createUser())).problem(403, "PERMISSION_DENIED")
        }

    private fun formatted(cnpj: String) =
        "${cnpj.substring(
            0,
            2,
        )}.${cnpj.substring(2, 5)}.${cnpj.substring(5, 8)}/${cnpj.substring(8, 12)}-${cnpj.substring(12)}"
}
