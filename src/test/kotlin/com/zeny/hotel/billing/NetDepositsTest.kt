package com.zeny.hotel.billing

import com.zeny.hotel.billing.BillingTestSupport.Companion.definition
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.asUser
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * O total líquido dos depósitos de uma conta na `BillingApi` (H13 do M4, M4 E09): os recebimentos com `deposit` menos
 * os reembolsos deles, sem os pagamentos comuns nem os reembolsos deles, por conta e para várias contas. No contexto
 * dos outros testes do `billing`.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class)
class NetDepositsTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val billingApi: BillingApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val billing = BillingTestSupport(iam, mockMvc, jsonMapper)

    @Test
    fun `the net deposits are the deposits received minus their refunds, never the ordinary payments`(): Unit =
        with(billing) {
            val cashier =
                iam.createUserWith(
                    TestGrant(definition(BillingPermissions.PAYMENTS_RECEIVE), ALL),
                    TestGrant(definition(BillingPermissions.FOLIOS_READ), ALL),
                    TestGrant(definition(BillingPermissions.CASHIER_OPERATE), ALL),
                    TestGrant(
                        definition(BillingPermissions.PAYMENTS_REFUND),
                        ALL,
                        mapOf(BillingPermissions.MAX_AMOUNT to LimitValue.Unlimited),
                    ),
                )
            val auth = asUser(cashier.id.value, UuidV7.generate())
            openShift(auth)
            val folio = openFolio()
            val other = openFolio()
            val untouched = openFolio()
            billingApi.netDeposits(FolioId(folio)) shouldBe Money.ZERO

            val deposit =
                pay(folio, auth, mapOf("method" to "PIX", "amount" to "200.00", "deposit" to true)).expect(201)
            val ordinary = pay(folio, auth, mapOf("method" to "CASH", "amount" to "50.00")).expect(201)
            pay(other, auth, mapOf("method" to "PIX", "amount" to "35.50", "deposit" to true)).expect(201)
            billingApi.netDeposits(FolioId(folio)) shouldBe Money.of("200.00")

            // O reembolso do depósito sai do total; o do pagamento comum, não.
            refund(folio, deposit.uuid("id"), "80.00", auth)
            refund(folio, ordinary.uuid("id"), "20.00", auth)
            billingApi.netDeposits(FolioId(folio)) shouldBe Money.of("120.00")

            billingApi.netDeposits(listOf(FolioId(folio), FolioId(other), FolioId(untouched))) shouldBe
                mapOf(
                    FolioId(folio) to Money.of("120.00"),
                    FolioId(other) to Money.of("35.50"),
                    FolioId(untouched) to Money.ZERO,
                )
            billingApi.netDeposits(FolioId(UuidV7.generate())) shouldBe Money.ZERO
            billingApi.netDeposits(emptyList()) shouldBe emptyMap()

            // O depósito devolvido por inteiro zera o total.
            refund(folio, deposit.uuid("id"), "120.00", auth)
            billingApi.netDeposits(FolioId(folio)) shouldBe Money.ZERO
        }

    private fun BillingTestSupport.refund(
        folio: UUID,
        payment: UUID,
        amount: String,
        auth: RequestPostProcessor,
    ) {
        call(
            POST,
            "/api/v1/folios/$folio/payments/$payment/refund",
            auth,
            mapOf("amount" to amount, "reason" to "Devolução ao hóspede"),
        ).expect(201)
    }
}
