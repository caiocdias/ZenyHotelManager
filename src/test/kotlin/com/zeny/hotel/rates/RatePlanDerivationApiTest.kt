package com.zeny.hotel.rates

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.rates.RatesTestSupport.Companion.definition
import com.zeny.hotel.rates.application.PriceInput
import com.zeny.hotel.rates.application.PriceTarget
import com.zeny.hotel.rates.application.RatePriceService
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.modulith.test.EnableScenarios
import org.springframework.modulith.test.Scenario
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.function.Supplier
import kotlin.concurrent.thread

/**
 * Planos derivados e tarifa publicada pela API (M4 E02, RF-TAR-03, RF-ACS-08, H1 e H9): o preço derivado (−10% e
 * + R$ 40/pessoa) calculado na leitura acompanha o base sem nenhuma linha no derivado; ciclo e profundidade recusados
 * com código estável (também em duas derivações opostas simultâneas); alterar ou retirar tarifa publicada e trocar a
 * derivação de um plano publicado pedem a aprovação de um superior (auditada com solicitante e aprovador), gravar
 * preço novo não; e `RatesChanged` pelo outbox com os planos derivados afetados.
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, RatesChangedListenerConfiguration::class)
@Suppress("LargeClass") // cenários de ponta a ponta da derivação, do step-up e do evento
class RatePlanDerivationApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val priceService: RatePriceService,
    @Autowired jsonMapper: JsonMapper,
) {
    private val rates = RatesTestSupport(iam)
    private val prices = RatePricesTestSupport(iam, jsonMapper)
    private val today: LocalDate get() = hotelApi.currentBusinessDate()
    private val manage = TestGrant(definition(RatesPermissions.PRICES_MANAGE), ALL)
    private val read = TestGrant(definition(RatesPermissions.PLANS_READ), ALL)
    private val override = TestGrant(definition(RatesPermissions.PRICES_OVERRIDE), ALL)

    // --- Derivação ------------------------------------------------------------------------------------------------

    @Test
    fun `derived prices are computed on read and follow the base, with no rows in the derived plans`(): Unit =
        with(iam) {
            val roomType = rates.rooms.createRoomType()
            val base = rates.createPlanFor(listOf(roomType))
            val nonRefundable = rates.createPlanFor(listOf(roomType))
            val withBreakfast = rates.createPlanFor(listOf(roomType))
            val day = today.plusDays(4)
            prices.update(base, sel(roomType, day), prices.prices("333.33", "400.00", "120.00")).ok()

            prices.derive(nonRefundable, prices.derivation("PERCENT", base, percent = "-10")).ok().let {
                it["derivation"]["basePlanId"].asString() shouldBe base.toString()
                it["derivation"]["percent"].asString() shouldBe "-10.0000"
            }
            prices.derive(withBreakfast, prices.derivation("AMOUNT", base, amount = "40.00", perPerson = true)).ok()

            val nr = prices.byDate(nonRefundable, day, day).getValue(day)
            nr["oneAdult"].asString() shouldBe "300.00"
            nr["twoAdults"].asString() shouldBe "360.00"
            nr["extraAdult"].asString() shouldBe "108.00"
            val bb = prices.byDate(withBreakfast, day, day).getValue(day)
            bb["oneAdult"].asString() shouldBe "373.33"
            bb["twoAdults"].asString() shouldBe "480.00"
            bb["extraAdult"].asString() shouldBe "160.00"
            read(nonRefundable, day).let {
                it["derived"].asBoolean() shouldBe true
                it["pricedBy"].asString() shouldBe base.toString()
            }

            // O base muda: o derivado acompanha na leitura seguinte, sem nenhuma gravação nele.
            prices.update(base, sel(roomType, day), prices.prices("500.00", "600.00")).ok()
            prices.byDate(nonRefundable, day, day).getValue(day)["oneAdult"].asString() shouldBe "450.00"
            prices.byDate(withBreakfast, day, day).getValue(day)["twoAdults"].asString() shouldBe "680.00"
            prices.byDate(nonRefundable, day, day).getValue(day)["extraAdult"].isNull shouldBe true
            listOf(nonRefundable, withBreakfast).forEach { rowCount(it) shouldBe 0 }

            // Em cadeia: o derivado de um derivado aplica as duas regras, a partir do base.
            val chained = rates.createPlanFor(listOf(roomType))
            prices.derive(chained, prices.derivation("AMOUNT", nonRefundable, amount = "40.00", perPerson = true)).ok()
            prices.byDate(chained, day, day).getValue(day)["oneAdult"].asString() shouldBe "490.00"
            read(chained, day)["pricedBy"].asString() shouldBe base.toString()

            // O derivado não grava preços próprios.
            prices.update(nonRefundable, sel(roomType, day), prices.prices("1.00")).problem(422, "RATE_PLAN_DERIVED")
            prices.remove(nonRefundable, sel(roomType, day)).problem(422, "RATE_PLAN_DERIVED")
        }

    @Test
    fun `a derived rate of zero or less leaves that occupancy and date without price, flagged on read`(): Unit =
        with(iam) {
            val roomType = rates.rooms.createRoomType()
            val base = rates.createPlanFor(listOf(roomType))
            val discounted = rates.createPlanFor(listOf(roomType))
            val low = today.plusDays(3)
            val high = low.plusDays(1)
            prices.update(base, sel(roomType, low), prices.prices("100.00", "180.00")).ok()
            prices.update(base, sel(roomType, high), prices.prices("300.00", "380.00")).ok()
            prices.derive(discounted, prices.derivation("AMOUNT", base, amount = "-100.00", perPerson = false)).ok()

            val read = prices.byDate(discounted, low, high)
            // 100,00 − 100,00 = 0: nunca uma diária grátis; a ocupação fica sem preço e sinalizada.
            val zeroed = read.getValue(low)
            zeroed["oneAdult"].isNull shouldBe true
            zeroed["twoAdults"].asString() shouldBe "80.00"
            zeroed["unpricedByDerivation"]["oneAdult"].asBoolean() shouldBe true
            zeroed["unpricedByDerivation"]["twoAdults"].asBoolean() shouldBe false
            zeroed["unpricedByDerivation"]["children"].values().shouldBeEmpty()
            // A regra continua valendo nas outras datas.
            val normal = read.getValue(high)
            normal["oneAdult"].asString() shouldBe "200.00"
            normal["unpricedByDerivation"].isNull shouldBe true
            // O próprio base nunca tem a marca.
            prices.byDate(base, low, low).getValue(low)["unpricedByDerivation"].isNull shouldBe true
        }

    @Test
    fun `a derivation that would form a cycle is refused with a stable code, and so are other inconsistencies`(): Unit =
        with(iam) {
            val roomType = rates.rooms.createRoomType()
            val a = rates.createPlanFor(listOf(roomType))
            val b = rates.createPlanFor(listOf(roomType))
            val c = rates.createPlanFor(listOf(roomType))
            prices.derive(b, prices.derivation("PERCENT", a, percent = "5")).ok()
            prices.derive(c, prices.derivation("PERCENT", b, percent = "5")).ok()

            prices.derive(a, prices.derivation("PERCENT", c, percent = "5")).problem(422, "RATE_PLAN_DERIVATION_CYCLE")
            prices.derive(a, prices.derivation("PERCENT", b, percent = "5")).problem(422, "RATE_PLAN_DERIVATION_CYCLE")
            prices.derive(a, prices.derivation("PERCENT", a, percent = "5")).problem(422, "RATE_PLAN_DERIVATION_CYCLE")
            withClue("nada mudou no plano recusado") { planOf(a)["derivation"].isNull shouldBe true }

            // Profundidade: a → b → c → d → e → f são 5 níveis abaixo de a; o sexto é recusado.
            val chain = mutableListOf(c)
            repeat(3) {
                val next = rates.createPlanFor(listOf(roomType))
                prices.derive(next, prices.derivation("PERCENT", chain.last(), percent = "1")).ok()
                chain += next
            }
            val tooDeep = rates.createPlanFor(listOf(roomType))
            prices
                .derive(tooDeep, prices.derivation("PERCENT", chain.last(), percent = "1"))
                .problem(422, "RATE_PLAN_DERIVATION_TOO_DEEP")

            fieldOf(
                prices
                    .derive(c, prices.derivation("PERCENT", UUID.randomUUID(), percent = "5"))
                    .problem(400, "UNKNOWN_RATE_PLAN"),
            ) shouldBe "basePlanId"
            fieldOf(
                prices
                    .derive(c, prices.derivation("PERCENT", a, percent = "5", amount = "1.00"))
                    .problem(400, "RATE_PLAN_DERIVATION_INVALID"),
            ) shouldBe "amount"
            prices
                .derive(
                    c,
                    prices.derivation("PERCENT", a, percent = "-100"),
                ).problem(400, "RATE_PLAN_DERIVATION_INVALID")
            prices
                .derive(c, prices.derivation("PERCENT", a, percent = "5"), ifMatch = null)
                .problem(428, "PRECONDITION_REQUIRED")
            prices
                .derive(c, prices.derivation("PERCENT", a, percent = "5"), ifMatch = "\"999\"")
                .problem(412, "PRECONDITION_FAILED")

            // Plano com preços próprios a partir da data operacional não vira derivado sem retirá-los.
            val priced = rates.createPlanFor(listOf(roomType))
            prices.update(priced, sel(roomType, today.plusDays(1)), prices.prices("100.00")).ok()
            prices.derive(priced, prices.derivation("PERCENT", a, percent = "5")).problem(422, "RATE_PLAN_HAS_PRICES")
            prices.remove(priced, sel(roomType, today.plusDays(1))).ok()
            prices.derive(priced, prices.derivation("PERCENT", a, percent = "5")).ok()

            // Retirar a derivação: o plano fica sem preços até ganhar os próprios.
            prices.removeDerivation(priced).ok()["derivation"].isNull shouldBe true
            read(priced, today.plusDays(1))["items"].values().shouldBeEmpty()
        }

    @Test
    fun `two opposite derivations at the same time never form a cycle`() {
        repeat(ROUNDS) { _ ->
            val roomType = rates.rooms.createRoomType()
            val a = rates.createPlanFor(listOf(roomType))
            val b = rates.createPlanFor(listOf(roomType))
            val etagA = prices.etag(a)
            val etagB = prices.etag(b)
            val start = CountDownLatch(1)
            val results = arrayOfNulls<MockHttpServletResponse>(2)
            val threads =
                listOf(a to b, b to a).mapIndexed { index, (plan, base) ->
                    thread {
                        start.await()
                        val etag = if (plan == a) etagA else etagB
                        results[index] =
                            prices.derive(plan, prices.derivation("PERCENT", base, percent = "1"), ifMatch = etag)
                    }
                }
            start.countDown()
            threads.forEach { it.join() }

            results.map { it!!.status }.sorted() shouldContainExactly listOf(200, 422)
            val bases = listOf(a, b).mapNotNull { plan -> planOf(plan)["derivation"].takeUnless { it.isNull } }
            bases.size shouldBe 1
        }
    }

    // --- Tarifa publicada (H9) ------------------------------------------------------------------------------------

    @Test
    fun `changing or removing a published price asks for approval, recording a new price does not`(): Unit =
        with(iam) {
            val roomType = rates.rooms.createRoomType()
            val plan = rates.createPlanFor(listOf(roomType))
            val day = today.plusDays(6)
            val operator = prices.Operator(createUserWith(manage, read))

            // Preço novo num plano ativo: só a permissão comum; os mesmos valores: nada muda, nada a aprovar.
            setPrice(plan, roomType, day, "200.00", operator).ok()["created"].asInt() shouldBe 1
            setPrice(plan, roomType, day, "200.00", operator).ok()["changed"].asInt() shouldBe 0

            val required = setPrice(plan, roomType, day, "220.00", operator).problem(403, "APPROVAL_REQUIRED")
            required["action"].asString() shouldBe "rates.rate-price.bulk-update"
            required["permission"].asString() shouldBe "rates.prices.override"
            required["resourceRef"].asString() shouldBe "rates.rate-plan:$plan"
            prices.byDate(plan, day, day).getValue(day)["oneAdult"].asString() shouldBe "200.00"
            val removal = prices.remove(plan, sel(roomType, day), operator.auth).problem(403, "APPROVAL_REQUIRED")
            removal["action"].asString() shouldBe "rates.rate-price.bulk-remove"

            withClue("quem tem a permissão sensível altera e retira sem aprovação") {
                val manager = prices.Operator(createUserWith(manage, override))
                setPrice(plan, roomType, day, "230.00", manager).ok()["changed"].asInt() shouldBe 1
                prices.remove(plan, sel(roomType, day), manager.auth).ok()["removed"].asInt() shouldBe 1
            }
        }

    @Test
    fun `a published price changed with a superior approval goes to the trail with requester and approver`(): Unit =
        with(iam) {
            val roomType = rates.rooms.createRoomType()
            val plan = rates.createPlanFor(listOf(roomType))
            val day = today.plusDays(6)
            val operator = prices.Operator(createUserWith(manage, read))
            setPrice(plan, roomType, day, "200.00", operator).ok()
            val required = setPrice(plan, roomType, day, "220.00", operator).problem(403, "APPROVAL_REQUIRED")

            val approver = prices.approver(createUserWith(manage, override))
            val (token, approvalId) = prices.approve(operator, required, approver)
            setPrice(plan, roomType, day, "220.00", operator, token).ok()["changed"].asInt() shouldBe 1
            prices.byDate(plan, day, day).getValue(day)["oneAdult"].asString() shouldBe "220.00"

            val entries =
                jdbc
                    .sql(
                        "SELECT entity_type, user_id FROM audit.audit_entry " +
                            "WHERE approval_id = :id AND action = 'rates.rate-price.bulk-update'",
                    ).param("id", approvalId)
                    .query { rs, _ -> rs.getString(1) to rs.getObject(2, UUID::class.java) }
                    .list()
            entries.map { it.first } shouldContainExactlyInAnyOrder listOf("rates.rate-price", "iam.approval")
            entries.forEach { it.second shouldBe operator.user.id.value }
            val approvedBy =
                jdbc
                    .sql(
                        "SELECT after_state ->> 'approverId' FROM audit.audit_entry " +
                            "WHERE action = 'iam.approval.grant' AND entity_id = :id",
                    ).param("id", approvalId.toString())
                    .query(String::class.java)
                    .list()
            approvedBy shouldContainExactly listOf(approver.user.id.toString())
        }

    @Test
    fun `an inactive plan has no published price, unless an active plan derives from it`(): Unit =
        with(iam) {
            val roomType = rates.rooms.createRoomType()
            val base = rates.createPlanFor(listOf(roomType))
            val day = today.plusDays(2)
            val operator = prices.Operator(createUserWith(manage, read))
            prices.update(base, sel(roomType, day), prices.prices("150.00")).ok()
            prices.deactivatePlan(base)

            prices
                .update(
                    base,
                    sel(roomType, day),
                    prices.prices("160.00"),
                    operator.auth,
                ).ok()["changed"]
                .asInt() shouldBe
                1

            // Um plano ativo deriva dele: os preços dele aparecem no derivado, então são tarifa publicada.
            val derived = rates.createPlanFor(listOf(roomType))
            prices.derive(derived, prices.derivation("PERCENT", base, percent = "-10")).ok()
            prices
                .update(
                    base,
                    sel(roomType, day),
                    prices.prices("170.00"),
                    operator.auth,
                ).problem(403, "APPROVAL_REQUIRED")
            prices.remove(base, sel(roomType, day), operator.auth).problem(403, "APPROVAL_REQUIRED")
        }

    @Test
    fun `changing the derivation of a published plan asks for approval, a first derivation does not`(): Unit =
        with(iam) {
            val roomType = rates.rooms.createRoomType()
            val base = rates.createPlanFor(listOf(roomType))
            val plan = rates.createPlanFor(listOf(roomType))
            val operator = prices.Operator(createUserWith(manage, read))

            prices.derive(plan, prices.derivation("PERCENT", base, percent = "-10"), operator.auth).ok()
            val required =
                prices
                    .derive(plan, prices.derivation("PERCENT", base, percent = "-15"), operator.auth)
                    .problem(403, "APPROVAL_REQUIRED")
            required["action"].asString() shouldBe "rates.rate-plan.set-derivation"
            required["permission"].asString() shouldBe "rates.prices.override"
            prices.removeDerivation(plan, operator.auth).problem(403, "APPROVAL_REQUIRED")["action"].asString() shouldBe
                "rates.rate-plan.remove-derivation"
            planOf(plan)["derivation"]["percent"].asString() shouldBe "-10.0000"

            val approver = prices.approver(createUserWith(manage, override))
            val (token, _) = prices.approve(operator, required, approver)
            prices.derive(plan, prices.derivation("PERCENT", base, percent = "-15"), operator.auth, token).ok()
            planOf(plan)["derivation"]["percent"].asString() shouldBe "-15.0000"

            // Sem a permissão de preços, nem a derivação nem a aprovação valem.
            prices
                .derive(plan, prices.derivation("PERCENT", base, percent = "-5"), asTestUser(createUserWith(read)))
                .problem(403, "PERMISSION_DENIED")
        }

    @Test
    fun `only the manager template gets the price permissions, and override is sensitive`() {
        val manage = RatesPermissions.permissions.single { it.code == RatesPermissions.PRICES_MANAGE }
        val override = RatesPermissions.permissions.single { it.code == RatesPermissions.PRICES_OVERRIDE }
        manage.sensitive shouldBe false
        override.sensitive shouldBe true
        manage.templates.keys shouldBe setOf(RoleTemplate.MANAGER)
        override.templates.keys shouldBe setOf(RoleTemplate.MANAGER)
    }

    // --- Evento ---------------------------------------------------------------------------------------------------

    @Test
    fun `RatesChanged goes through the outbox with the plan, room types, interval and the derived plans`(
        scenario: Scenario,
    ) {
        val roomType = rates.rooms.createRoomType()
        val base = rates.createPlanFor(listOf(roomType))
        val derived = rates.createPlanFor(listOf(roomType))
        val grandchild = rates.createPlanFor(listOf(roomType))
        prices.derive(derived, prices.derivation("PERCENT", base, percent = "-10"))
        prices.derive(grandchild, prices.derivation("AMOUNT", derived, amount = "40.00", perPerson = true))
        val from = today.plusDays(3)

        scenario
            .stimulate(
                Supplier {
                    with(iam) {
                        prices
                            .update(
                                base,
                                prices.selection(listOf(roomType), from, from.plusDays(2)),
                                prices.prices("210.00"),
                            ).ok()
                    }
                },
            ).andWaitForEventOfType(RatesChanged::class.java)
            .matching { it.ratePlanId == base && it.change == RatesChangeKind.PRICES_SET }
            .toArriveAndVerify { event ->
                event.roomTypeIds shouldContainExactly listOf(roomType)
                event.from shouldBe from
                event.to shouldBe from.plusDays(2)
                event.derivedRatePlanIds shouldContainExactlyInAnyOrder listOf(derived, grandchild)
            }
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until {
            RatesChangedEvents.received.any { it.ratePlanId == base && it.change == RatesChangeKind.PRICES_SET }
        }
        publications(base, "PRICES_SET").size shouldBe 1

        // A derivação trocada também publica, da data operacional em diante, sem fim.
        with(iam) { prices.derive(derived, prices.derivation("PERCENT", base, percent = "-12")).ok() }

        // A primeira derivação (ainda sem derivados) também publicou; a troca leva o neto.
        fun changedDerivation() =
            RatesChangedEvents.received.filter {
                it.ratePlanId == derived &&
                    it.change == RatesChangeKind.DERIVATION_CHANGED &&
                    it.derivedRatePlanIds.isNotEmpty()
            }
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until { changedDerivation().isNotEmpty() }
        val derivationChanged = changedDerivation().single()
        derivationChanged.from shouldBe today
        derivationChanged.to shouldBe null
        derivationChanged.derivedRatePlanIds shouldContainExactly listOf(grandchild)
    }

    @Test
    fun `an edit that changes nothing, or is rolled back, publishes nothing`() {
        val roomType = rates.rooms.createRoomType()
        val plan = rates.createPlanFor(listOf(roomType))
        val day = today.plusDays(8)
        with(iam) {
            prices.update(plan, sel(roomType, day), prices.prices("99.00")).ok()
            prices.update(plan, sel(roomType, day), prices.prices("99.00")).ok()
            prices.remove(plan, sel(roomType, day.plusDays(1))).ok()
        }
        publications(plan, "PRICES_SET").size shouldBe 1
        publications(plan, "PRICES_REMOVED").shouldBeEmpty()

        val target = PriceTarget(setOf(RoomTypeId(roomType)), day, day, null, emptySet())
        authenticatedAs(iam.adminId()) {
            transactions.executeWithoutResult { status ->
                priceService.update(
                    RatePlanId(plan),
                    target,
                    PriceInput(Money.of("120.00"), null, null, emptyList()),
                    null,
                )
                // Dentro da transação a publicação já está registrada; desfeita, some com o preço.
                publications(plan, "PRICES_SET").size shouldBe 2
                status.setRollbackOnly()
            }
        }
        publications(plan, "PRICES_SET").size shouldBe 1
        prices.byDate(plan, day, day).getValue(day)["oneAdult"].asString() shouldBe "99.00"
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private fun sel(
        roomType: UUID,
        day: LocalDate,
    ) = prices.selection(listOf(roomType), day, day)

    private fun read(
        plan: UUID,
        day: LocalDate,
    ) = with(iam) { prices.read(plan, day, day).ok() }

    /** Grava o preço de 1 adulto [value] na data, como [operator], com o token de aprovação opcional. */
    @Suppress("LongParameterList") // o plano, a linha, o valor, o autor e o token
    private fun setPrice(
        plan: UUID,
        roomType: UUID,
        day: LocalDate,
        value: String,
        operator: RatePricesTestSupport.Operator,
        approvalToken: String? = null,
    ) = prices.update(plan, sel(roomType, day), prices.prices(value), operator.auth, approvalToken)

    private fun planOf(plan: UUID) = with(iam) { call(HttpMethod.GET, "/api/v1/rate-plans/$plan", asAdmin()).ok() }

    private fun <T> authenticatedAs(
        userId: UUID,
        block: () -> T,
    ): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(userId.toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun fieldOf(problem: JsonNode): String = problem["errors"].values().single()["field"].asString()

    private fun rowCount(plan: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM rates.rate_price WHERE rate_plan_id = :plan")
            .param("plan", plan)
            .query(Int::class.java)
            .single()

    private fun publications(
        plan: UUID,
        change: String,
    ): List<String> =
        jdbc
            .sql(
                "SELECT serialized_event FROM shared.event_publication WHERE event_type = :type " +
                    "AND serialized_event LIKE :plan AND serialized_event LIKE :change AND listener_id LIKE :listener",
            ).param("type", RatesChanged::class.java.name)
            .param("plan", "%\"ratePlanId\":\"$plan\"%")
            .param("change", "%\"change\":\"$change\"%")
            .param("listener", "%RatesChangedProbeListener%")
            .query(String::class.java)
            .list()
            .filterNotNull()

    private companion object {
        const val ROUNDS = 5
        const val TIMEOUT_SECONDS = 10L
    }
}
