package com.zeny.hotel.frontdesk

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * A permissão `frontdesk.stays.checkin` passa a dizer que abre a ficha de registro (M6 E15, D-E15-5) e o contrato do
 * PDF da ficha descreve os rótulos do catálogo (D-E15-7): só texto, sem mudar código, escopo, sensibilidade, concessões
 * nem sessões abertas.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList")
class FrontdeskCheckInPermissionDescriptionTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired hotelApi: HotelApi,
    @Autowired roomsApi: RoomsApi,
    @Autowired private val mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val modules: List<ModulePermissions>,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val frontdesk = FrontdeskTestSupport(iam, support, hotelApi, roomsApi, jsonMapper)
    private val code = FrontdeskPermissions.STAYS_CHECK_IN.value

    @BeforeEach
    fun synchronizeThisContextCatalog() {
        synchronizer.synchronize()
    }

    @Test
    fun `the contract describes the card with the labels`() {
        val block =
            java.io
                .File("api/openapi.yaml")
                .readText()
                .substringBefore("operationId: getRegistrationCardPdf")
                .substringAfterLast("/registration-card/pdf:")
        val description = block.replace(Regex("\\\\?\\n\\s*\\\\?"), "")
        description shouldContain "rótulo do catálogo de domínios da FNRH"
        description shouldContain "(código FNRH)"
        description shouldNotContain "códigos FNRH guardados"
    }

    @Test
    fun `the permission catalog describes the registration card`() {
        val item = permissionsEndpoint().single { it["code"].asString() == code }

        item["description"].asString() shouldBe NEW_DESCRIPTION
        item["sensitive"].asBoolean() shouldBe false
        item["scopes"].values().map { it.asString() } shouldBe listOf("ALL")
    }

    @Test
    fun `the role templates and grants do not change`() {
        withOldDescription {
            val before = grants()
            synchronizer.synchronize()
            grants() shouldBe before
        }
        val grants = grants()
        grants shouldContainAll listOf("MANAGER:ALL", "RECEPTIONIST:ALL")
        grants shouldContain "ADMINISTRATOR:ALL"
    }

    @Test
    fun `a description change keeps open sessions and grants`() {
        val receptionist = iam.createUser()
        iam.assignRole(receptionist, templateRole("RECEPTIONIST"))
        val token = iam.loginOk(receptionist)["accessToken"].asString()
        val stay = frontdesk.stay()
        val booked = stay.book(frontdesk.guest())
        val path = "/api/v1/reservations/${booked.id}/registration-card/pdf"
        iam.getWithToken(path, token).status shouldBe 200

        withOldDescription {
            val before = grants()
            val result = synchronizer.synchronize()

            result.updated shouldContain FrontdeskPermissions.STAYS_CHECK_IN
            grants() shouldBe before
            iam.getWithToken(path, token).status shouldBe 200
        }
    }

    // --- apoio ---

    /** Põe a descrição antiga (lista inteira do contexto, só o `frontdesk` trocado) e devolve a real no fim. */
    private fun withOldDescription(block: () -> Unit) {
        val real = modules.single { it.module == "frontdesk" }
        val old =
            object : ModulePermissions {
                override val module = real.module
                override val permissions =
                    real.permissions.map {
                        if (it.code.value == code) it.copy(description = OLD_DESCRIPTION) else it
                    }
            }
        try {
            synchronizer.synchronize(modules.map { if (it === real) old else it })
            block()
        } finally {
            synchronizer.synchronize()
        }
    }

    private fun permissionsEndpoint() =
        iam
            .json(
                mockMvc
                    .get("/api/v1/permissions") {
                        with(iam.asAdmin())
                        param("limit", "200")
                    }.andReturn()
                    .response,
            )["items"]
            .values()

    private fun templateRole(template: String): RoleId =
        RoleId(
            jdbc
                .sql("SELECT id FROM iam.role WHERE template_key = :template")
                .param("template", template)
                .query(UUID::class.java)
                .single(),
        )

    /** As concessões da permissão por role (`<template ou nome>:<escopo>`), em ordem estável. */
    private fun grants(): List<String> =
        jdbc
            .sql(
                "SELECT COALESCE(r.template_key, r.name) || ':' || g.scope FROM iam.role_grant g " +
                    "JOIN iam.role r ON r.id = g.role_id WHERE g.permission_code = :code ORDER BY 1",
            ).param("code", code)
            .query { rs, _ -> rs.getString(1) }
            .list()

    private companion object {
        const val OLD_DESCRIPTION = "Fazer o check-in de uma reserva confirmada (com a caução opcional)"
        const val NEW_DESCRIPTION =
            "Fazer o check-in de uma reserva confirmada (com a caução opcional) e abrir a ficha de registro " +
                "(PDF e impressão)"
    }
}
