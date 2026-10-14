package com.zeny.hotel.iam

import com.zeny.hotel.iam.domain.EffectivePermissions
import com.zeny.hotel.iam.domain.Grant
import com.zeny.hotel.iam.domain.PermissionCatalog
import com.zeny.hotel.iam.domain.RoleGrants
import com.zeny.hotel.shared.LimitDefinition
import com.zeny.hotel.shared.LimitUnit
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** União efetiva das roles (RF-ACS-04): escopo mais amplo, maior limite, só roles ativas e catálogo vigente. */
class EffectivePermissionsTest {
    private val read = PermissionCode("rooms.rooms.read")
    private val discount = PermissionCode("rooms.discounts.apply")
    private val reverse = PermissionCode("rooms.postings.reverse")

    private val catalog =
        PermissionCatalog.of(
            listOf(
                object : ModulePermissions {
                    override val module = "rooms"
                    override val permissions =
                        listOf(
                            PermissionDefinition(read, "Ler UHs", setOf(OWN, ALL)),
                            PermissionDefinition(
                                discount,
                                "Desconto",
                                setOf(ALL),
                                limits =
                                    listOf(
                                        LimitDefinition("maxPercent", LimitUnit.PERCENT, "Desconto máximo"),
                                        LimitDefinition("maxAmount", LimitUnit.MONEY, "Valor máximo"),
                                    ),
                            ),
                            PermissionDefinition(reverse, "Estorno", setOf(OWN)),
                        )
                },
            ),
        )

    private fun active(vararg grants: Grant) = RoleGrants(roleActive = true, grants = grants.toList())

    private fun max(value: String) = LimitValue.Max(BigDecimal(value))

    @Test
    fun `the broadest scope prevails`() {
        val effective =
            EffectivePermissions.union(true, listOf(active(Grant(read, OWN)), active(Grant(read, ALL))), catalog)

        effective.of(read)!!.scope shouldBe ALL
        // A ordem das roles não importa.
        EffectivePermissions
            .union(true, listOf(active(Grant(read, ALL)), active(Grant(read, OWN))), catalog)
            .of(read)!!
            .scope shouldBe ALL
    }

    @Test
    fun `OWN alone stays OWN`() {
        EffectivePermissions.union(true, listOf(active(Grant(read, OWN))), catalog).of(read)!!.scope shouldBe OWN
    }

    @Test
    fun `the largest limit prevails, key by key, and unlimited beats any ceiling`() {
        val effective =
            EffectivePermissions.union(
                true,
                listOf(
                    active(Grant(discount, ALL, mapOf("maxPercent" to max("5"), "maxAmount" to max("300.00")))),
                    active(Grant(discount, ALL, mapOf("maxPercent" to max("12.5")))),
                    active(Grant(discount, ALL, mapOf("maxAmount" to max("150")))),
                ),
                catalog,
            )

        val granted = effective.of(discount)!!
        granted.limit("maxPercent") shouldBe max("12.5")
        granted.limit("maxAmount") shouldBe max("300")

        val withUnlimited =
            EffectivePermissions.union(
                true,
                listOf(
                    active(Grant(discount, ALL, mapOf("maxPercent" to LimitValue.Unlimited))),
                    active(Grant(discount, ALL, mapOf("maxPercent" to max("100")))),
                ),
                catalog,
            )
        withUnlimited.of(discount)!!.limit("maxPercent") shouldBe LimitValue.Unlimited
    }

    @Test
    fun `a limit without value in every role is a zero ceiling`() {
        val granted = EffectivePermissions.union(true, listOf(active(Grant(discount, ALL))), catalog).of(discount)!!

        granted.limits.keys.shouldBeEmpty()
        granted.limit("maxPercent") shouldBe LimitValue.NONE
        granted.limit("maxPercent").allows(BigDecimal("0.01")) shouldBe false
    }

    @Test
    fun `inactive roles are ignored`() {
        val effective =
            EffectivePermissions.union(
                true,
                listOf(
                    RoleGrants(roleActive = false, grants = listOf(Grant(read, ALL), Grant(reverse, OWN))),
                    active(Grant(read, OWN)),
                ),
                catalog,
            )

        effective.of(read)!!.scope shouldBe OWN
        effective.of(reverse).shouldBeNull()
    }

    @Test
    fun `an inactive user has no permission`() {
        EffectivePermissions.union(false, listOf(active(Grant(read, ALL))), catalog).all.shouldBeEmpty()
    }

    @Test
    fun `obsolete permissions, unsupported scopes and undeclared limits are ignored`() {
        val obsolete = PermissionCode("rooms.rooms.delete")
        val effective =
            EffectivePermissions.union(
                true,
                listOf(
                    active(
                        Grant(obsolete, ALL),
                        // O estorno passou a aceitar só OWN: uma concessão ALL antiga não vale.
                        Grant(reverse, ALL),
                        Grant(discount, ALL, mapOf("maxPercent" to max("10"), "removedKey" to max("99"))),
                    ),
                ),
                catalog,
            )

        effective.of(obsolete).shouldBeNull()
        effective.of(reverse).shouldBeNull()
        effective.of(discount)!!.limits shouldBe mapOf("maxPercent" to max("10"))
    }

    @Test
    fun `a limit value that no longer fits the unit is ignored`() {
        val effective =
            EffectivePermissions.union(
                true,
                listOf(active(Grant(discount, ALL, mapOf("maxPercent" to max("150"), "maxAmount" to max("1.234"))))),
                catalog,
            )

        effective
            .of(discount)!!
            .limits.keys
            .shouldBeEmpty()
    }
}
