package com.zeny.hotel.iam

import com.zeny.hotel.iam.domain.Grant
import com.zeny.hotel.iam.domain.PermissionCatalog
import com.zeny.hotel.iam.domain.Role
import com.zeny.hotel.shared.LimitDefinition
import com.zeny.hotel.shared.LimitUnit
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/** Regras do agregado [Role] e do catálogo declarado pelos módulos (RF-ACS-02, 03, 05 e 06, H5). */
class RoleTest {
    private val now = Instant.parse("2026-10-14T12:00:00Z")
    private val discount =
        PermissionDefinition(
            PermissionCode("rooms.discounts.apply"),
            "Desconto",
            setOf(ALL),
            limits =
                listOf(
                    LimitDefinition("maxPercent", LimitUnit.PERCENT, "Desconto máximo"),
                    LimitDefinition("maxAmount", LimitUnit.MONEY, "Valor máximo"),
                    LimitDefinition("maxNights", LimitUnit.QUANTITY, "Noites"),
                ),
            templates = mapOf(RoleTemplate.RECEPTIONIST to TemplateGrant(ALL, mapOf("maxPercent" to max("5")))),
        )
    private val read = PermissionDefinition(PermissionCode("rooms.rooms.read"), "Ler", setOf(OWN, ALL))

    private fun max(value: String) = LimitValue.Max(BigDecimal(value))

    @Test
    fun `a grant needs a supported scope and declared, valid limits`() {
        val role = Role.create("Recepção", "", now)

        shouldThrow<IllegalArgumentException> { role.grant(discount, OWN, emptyMap(), now) }
        shouldThrow<IllegalArgumentException> { role.grant(discount, ALL, mapOf("other" to max("1")), now) }
        shouldThrow<IllegalArgumentException> { role.grant(discount, ALL, mapOf("maxPercent" to max("100.5")), now) }
        shouldThrow<IllegalArgumentException> { role.grant(discount, ALL, mapOf("maxAmount" to max("10.001")), now) }
        shouldThrow<IllegalArgumentException> { role.grant(discount, ALL, mapOf("maxNights" to max("1.5")), now) }
        shouldThrow<IllegalArgumentException> { LimitValue.Max(BigDecimal("-1")) }
        role.grants.size shouldBe 0
        role.customized shouldBe false

        role.grant(
            discount,
            ALL,
            mapOf("maxPercent" to max("12.5"), "maxAmount" to max("300.00"), "maxNights" to LimitValue.Unlimited),
            now,
        )

        role.grantValues() shouldContainExactly
            listOf(
                Grant(
                    discount.code,
                    ALL,
                    mapOf("maxPercent" to max("12.5"), "maxAmount" to max("300"), "maxNights" to LimitValue.Unlimited),
                ),
            )
        role.customized shouldBe true
    }

    @Test
    fun `granting again redefines the grant, and revoke removes it`() {
        val role = Role.create("Recepção", "", now)
        role.grant(read, OWN, emptyMap(), now)
        role.grant(read, ALL, emptyMap(), now)

        role.grantValues() shouldContainExactly listOf(Grant(read.code, ALL))

        role.revoke(read.code, now)
        role.grants.size shouldBe 0
    }

    @Test
    fun `a template only receives new grants while it was not edited`() {
        val role = Role.fromTemplate(RoleTemplate.RECEPTIONIST, now)
        role.name shouldBe "Recepcionista"
        role.systemRole shouldBe false

        role.applyTemplateGrant(discount, discount.templates.getValue(RoleTemplate.RECEPTIONIST), now) shouldBe true
        role.customized shouldBe false
        // Uma concessão existente nunca é mudada pelo seed.
        role.applyTemplateGrant(discount, TemplateGrant(ALL, mapOf("maxPercent" to max("50"))), now) shouldBe false
        role.grantOf(discount.code)!!.limits shouldBe mapOf("maxPercent" to max("5"))

        role.revoke(discount.code, now)
        role.customized shouldBe true
        role.applyTemplateGrant(discount, discount.templates.getValue(RoleTemplate.RECEPTIONIST), now) shouldBe false
        role.grants.size shouldBe 0
    }

    @Test
    fun `the administrator system role holds every permission at the broadest scope and without ceilings`() {
        val role = Role.fromTemplate(RoleTemplate.ADMINISTRATOR, now)
        role.systemRole shouldBe true

        role.ensureFullGrant(read, now) shouldBe true
        role.ensureFullGrant(discount, now) shouldBe true
        role.ensureFullGrant(discount, now) shouldBe false

        role.grantOf(read.code)!!.scope shouldBe ALL
        role.grantOf(discount.code)!!.limits shouldBe
            mapOf(
                "maxPercent" to LimitValue.Unlimited,
                "maxAmount" to LimitValue.Unlimited,
                "maxNights" to LimitValue.Unlimited,
            )
        shouldThrow<IllegalStateException> { role.revoke(read.code, now) }
        shouldThrow<IllegalStateException> { role.deactivate(now) }
        shouldThrow<IllegalStateException> { role.grant(read, OWN, emptyMap(), now) }
        role.applyTemplateGrant(read, TemplateGrant(OWN), now) shouldBe false
    }

    @Test
    fun `a deactivated role stays, inactive`() {
        val role = Role.create("Temporária", "", now)
        role.deactivate(now)
        role.active shouldBe false
    }

    @Test
    fun `the catalog refuses codes outside the module, repeated codes and repeated modules`() {
        fun module(
            name: String,
            vararg codes: String,
        ) = object : ModulePermissions {
            override val module = name
            override val permissions = codes.map { PermissionDefinition(PermissionCode(it), "x", setOf(ALL)) }
        }

        shouldThrow<IllegalArgumentException> { PermissionCatalog.of(listOf(module("rooms", "rates.plans.read"))) }
            .message shouldContain "não começa pelo módulo"
        shouldThrow<IllegalArgumentException> {
            PermissionCatalog.of(listOf(module("rooms", "rooms.a.read", "rooms.a.read")))
        }.message shouldContain "mais de uma vez"
        shouldThrow<IllegalArgumentException> {
            PermissionCatalog.of(listOf(module("rooms", "rooms.a.read"), module("rooms", "rooms.b.read")))
        }
        PermissionCatalog
            .of(
                listOf(module("rooms", "rooms.a.read"), module("rates", "rates.a.read")),
            ).definitions.size shouldBe
            2
    }

    @Test
    fun `a permission definition validates itself`() {
        shouldThrow<IllegalArgumentException> { PermissionCode("Rooms.read") }
        shouldThrow<IllegalArgumentException> { PermissionCode("rooms") }
        shouldThrow<IllegalArgumentException> { PermissionDefinition(read.code, "x", emptySet()) }
        shouldThrow<IllegalArgumentException> {
            PermissionDefinition(
                read.code,
                "x",
                setOf(ALL),
                templates =
                    mapOf(RoleTemplate.MANAGER to TemplateGrant(OWN)),
            )
        }
        shouldThrow<IllegalArgumentException> {
            PermissionDefinition(
                read.code,
                "x",
                setOf(ALL),
                templates =
                    mapOf(RoleTemplate.ADMINISTRATOR to TemplateGrant(ALL)),
            )
        }
        shouldThrow<IllegalArgumentException> {
            PermissionDefinition(
                read.code,
                "x",
                setOf(ALL),
                limits = listOf(LimitDefinition("a", LimitUnit.MONEY, "a"), LimitDefinition("a", LimitUnit.MONEY, "b")),
            )
        }
        IamPermissions.permissions.map { it.code.module }.toSet() shouldBe setOf("iam")
    }
}
