package com.zeny.hotel

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.billing.BillingTestSupport
import com.zeny.hotel.billing.FolioOwner
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.guests.GuestsTestSupport
import com.zeny.hotel.guests.GuestsTestSupport.Companion.formattedCpf
import com.zeny.hotel.guests.GuestsTestSupport.Companion.newCpf
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Cenário ponta a ponta do marco M3 (gate, E11), pela API HTTP e com os modelos de role da SPEC §3.1 que o sistema
 * entrega (Recepcionista e Gerente), com a taxa de serviço padrão do hotel em 10%: o Gerente cadastra a empresa; a
 * Recepcionista cadastra o hóspede com CPF e dados sensíveis da FNRH e o acha pelo documento sem ver o número;
 * abre a conta da reserva com a empresa como pagadora e a conta da empresa; roteia as diárias para a empresa;
 * lança a diária (roteada), o A&B (com a taxa de serviço separada) e uma lavanderia por engano, que estorna com a
 * aprovação do Gerente; tenta receber sem turno; abre o turno; registra a caução, captura o saldo e libera a
 * sobra; recebe a conta da empresa em dinheiro com troco; encerra as duas contas só com saldo zero e sem caução em
 * aberto; faz uma sangria e fecha o turno com diferença, que notifica o Gerente e não a Recepcionista. No fim, o
 * banco recusa mudar lançamentos, pagamentos e movimentos de caixa, o extrato do hóspede soma o saldo e nada do
 * titular aparece na trilha nem na notificação.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class)
@Suppress("LongParameterList") // os beans que o cenário usa
class MilestoneM3EndToEndTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val billingApi: BillingApi,
    @Autowired private val clock: Clock,
    @Autowired flyway: Flyway,
) {
    private val billing = BillingTestSupport(iam, mockMvc, jsonMapper)
    private val guests = GuestsTestSupport(iam)
    private val owner = JdbcClient.create(flyway.configuration.dataSource)

    @Test
    @Suppress("LongMethod", "CyclomaticComplexMethod") // o cenário inteiro do marco, passo a passo
    fun `guest, company payer, routing, service charge, shift, payments, hold, approved reversal and closing`(): Unit =
        withHotelServiceCharge(HOTEL_SERVICE_CHARGE) {
            with(billing) {
                val today = hotelApi.currentBusinessDate()
                val receptionist = Operator(userWithTemplate("RECEPTIONIST"))
                val manager = userWithTemplate("MANAGER")
                val managerPin = (PIN_MIN..PIN_MAX).random().toString()
                iam.setPin(manager, managerPin)
                val managerAuth = asUser(manager.id.value, UuidV7.generate())

                // --- Hóspede e empresa -------------------------------------------------------------------
                val company =
                    call(POST, "/api/v1/companies", managerAuth, guests.companyBody()).expect(201).uuid("id")
                val guestName = guests.uniqueName("Benedita")
                val cpf = newCpf()
                val guest =
                    call(
                        POST,
                        "/api/v1/guests",
                        receptionist.auth,
                        guests.createBody(
                            profile = guests.profileBody(fullName = guestName, companyId = company),
                            documents = listOf(guests.document("CPF", formattedCpf(cpf))),
                            sensitiveData = mapOf("raceColor" to "INDIGENOUS", "disability" to "VISUAL_LOW"),
                        ),
                    ).expect(201)
                val guestId = guest.uuid("id")
                withClue("o perfil devolve o documento mascarado e nunca os dados sensíveis da FNRH") {
                    guest["documents"].values().single()["maskedNumber"].asString() shouldBe "***" + cpf.takeLast(4)
                    guest["hasSensitiveData"].asBoolean() shouldBe true
                    guest.toString() shouldNotContain cpf
                    guest.toString() shouldNotContain "INDIGENOUS"
                    guest.toString() shouldNotContain "VISUAL_LOW"
                }
                val found =
                    call(
                        POST,
                        "/api/v1/guests/search-by-document",
                        receptionist.auth,
                        guests.document("CPF", cpf),
                    ).expect(200)
                found["items"].values().map { it.uuid("id") } shouldContainExactly listOf(guestId)
                found.toString() shouldNotContain cpf

                // --- Contas e roteamento ------------------------------------------------------------------
                val reservation = UuidV7.generate()
                val guestFolioBody = folioBody("RESERVATION", reservation, payerCompanyId = company)
                val guestFolioResponse = call(POST, "/api/v1/folios", receptionist.auth, guestFolioBody).expect(201)
                guestFolioResponse["payerCompanyId"].asString() shouldBe company.toString()
                val guestFolio = guestFolioResponse.uuid("id")
                val companyFolio =
                    call(
                        POST,
                        "/api/v1/folios",
                        receptionist.auth,
                        folioBody("COMPANY", company),
                    ).expect(201).uuid("id")
                createRule(guestFolio, companyFolio, today, group = "ROOM", auth = receptionist.auth).expect(201)

                // --- Lançamentos: diária roteada, A&B com taxa de serviço, lavanderia por engano -----------
                val room = post(guestFolio, codeId("ROOM_CHARGE"), "250.00", auth = receptionist.auth)
                withClue("a diária vai para a conta da empresa, com a origem registrada") {
                    room.uuid("folioId") shouldBe companyFolio
                    room.uuid("routedFromFolioId") shouldBe guestFolio
                    room["businessDate"].asString() shouldBe today.toString()
                }
                val food = post(guestFolio, codeId("FOOD_BEVERAGE"), "80.00", auth = receptionist.auth)
                food.uuid("folioId") shouldBe guestFolio
                val serviceCharge = postings(guestFolio).single { it["type"].asString() == "SERVICE_CHARGE" }
                withClue("a taxa de serviço é um lançamento separado, vinculado ao item, pela regra de Money") {
                    serviceCharge.uuid("id") shouldBe food.uuid("serviceChargePostingId")
                    serviceCharge.uuid("serviceChargeForPostingId") shouldBe food.uuid("id")
                    val rate = Percent.of(serviceCharge["serviceChargeRate"].asString())
                    serviceCharge["amount"].asString() shouldBe "8.00"
                    serviceCharge["amount"].asString() shouldBe rate.of(Money.of("80.00")).toString()
                    Money.of(serviceCharge["amount"].asString()) shouldNotBe Money.ZERO
                }
                val laundry = post(guestFolio, codeId("LAUNDRY"), "40.00", auth = receptionist.auth)

                // --- Sem turno, nada se recebe ----------------------------------------------------------------
                pay(guestFolio, receptionist.auth, mapOf("method" to "PIX", "amount" to "10.00"))
                    .problem(422, "CASHIER_SHIFT_REQUIRED")
                paymentCount(guestFolio) shouldBe 0L

                // --- Turno de caixa ---------------------------------------------------------------------------
                val pointName = "Recepção ${guests.suffix(8)}"
                val cashPoint =
                    call(POST, "/api/v1/cash-points", managerAuth, mapOf("name" to pointName)).expect(201).uuid("id")
                val shift =
                    call(
                        POST,
                        "/api/v1/cashier-shifts",
                        receptionist.auth,
                        mapOf("cashPointId" to cashPoint, "openingFloat" to "100.00"),
                    ).expect(201)
                val shiftId = shift.uuid("id")
                shift["expectedVisible"].asBoolean() shouldBe false

                // --- Estorno com aprovação do superior --------------------------------------------------------
                val laundryPath = "/api/v1/folios/$guestFolio/postings/${laundry["id"].asString()}/reverse"
                val required =
                    call(POST, laundryPath, receptionist.auth, mapOf("reason" to REASON))
                        .problem(403, "APPROVAL_REQUIRED")
                required["action"].asString() shouldBe "billing.posting.reverse"
                required["resourceRef"].asString() shouldBe "billing.posting:${laundry["id"].asString()}"
                required["limit"]["amount"].asString().toBigDecimal().compareTo(BigDecimal("40")) shouldBe 0
                val approval = approve(receptionist, required, manager, managerPin).expect(201)
                val reversal =
                    call(
                        POST,
                        laundryPath,
                        receptionist.auth,
                        mapOf("reason" to REASON),
                        approvalToken = approval["approvalToken"].asString(),
                    ).expect(201)
                withClue("o estorno é um lançamento novo, vinculado, com a aprovação; o original não muda") {
                    reversal["type"].asString() shouldBe "REVERSAL"
                    reversal["amount"].asString() shouldBe "-40.00"
                    reversal.uuid("correctsId") shouldBe laundry.uuid("id")
                    reversal.uuid("approvalId") shouldBe approval.uuid("approvalId")
                    reversal.uuid("approvedBy") shouldBe manager.id.value
                    val original = postings(guestFolio).single { it["id"] == laundry["id"] }
                    original["amount"].asString() shouldBe "40.00"
                    original.uuid("reversedBy") shouldBe reversal.uuid("id")
                }
                withClue("a trilha tem o solicitante como autor, a aprovação e o aprovador") {
                    val entries =
                        jdbc
                            .sql(
                                "SELECT user_id, approval_id FROM audit.audit_entry " +
                                    "WHERE action = 'billing.posting.reverse' AND approval_id = :approval",
                            ).param("approval", approval.uuid("approvalId"))
                            .query { rs, _ -> rs.getObject(1, UUID::class.java) }
                            .list()
                    entries.isEmpty() shouldBe false
                    entries.toSet() shouldBe setOf(receptionist.user.id.value)
                    jdbc
                        .sql("SELECT requester_id, approver_id FROM iam.approval WHERE id = :id")
                        .param("id", approval.uuid("approvalId"))
                        .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }
                        .single() shouldBe (receptionist.user.id.value to manager.id.value)
                }

                // --- Caução capturada e liberada; encerramento da conta do hóspede ----------------------------
                val guestBalance = Money.of(balance(guestFolio))
                guestBalance shouldBe Money.of("80.00") + Money.of(serviceCharge["amount"].asString())
                val guestEtag = etagOf("/api/v1/folios/$guestFolio")
                val hold =
                    call(
                        POST,
                        "/api/v1/folios/$guestFolio/card-pre-authorizations",
                        receptionist.auth,
                        mapOf(
                            "amount" to "300.00",
                            "expiresAt" to
                                clock
                                    .instant()
                                    .plus(Duration.ofDays(3))
                                    .truncatedTo(ChronoUnit.SECONDS)
                                    .toString(),
                            "authorizationCode" to "AUT778",
                            "cardBrand" to "VISA",
                            "cardLast4" to "4242",
                        ),
                    ).expect(201)
                withClue("a caução não entra no saldo nem muda a versão da conta") {
                    Money.of(balance(guestFolio)) shouldBe guestBalance
                    etagOf("/api/v1/folios/$guestFolio") shouldBe guestEtag
                }
                val holdPath = "/api/v1/folios/$guestFolio/card-pre-authorizations/${hold["id"].asString()}"
                val capture =
                    call(POST, "$holdPath/capture", receptionist.auth, mapOf("amount" to guestBalance.toString()))
                        .expect(201)
                capture["preAuthorization"]["status"].asString() shouldBe "PARTIALLY_CAPTURED"
                capture["payment"]["method"].asString() shouldBe "CREDIT_CARD"
                capture["payment"].uuid("cashierShiftId") shouldBe shiftId
                balance(guestFolio) shouldBe "0.00"
                call(POST, "/api/v1/folios/$guestFolio/close", receptionist.auth)
                    .problem(409, "FOLIO_HAS_ACTIVE_PRE_AUTHORIZATION")
                call(POST, "$holdPath/release", receptionist.auth).expect(200)["status"].asString() shouldBe "RELEASED"
                call(
                    POST,
                    "/api/v1/folios/$guestFolio/close",
                    receptionist.auth,
                ).expect(200)["status"].asString() shouldBe
                    "CLOSED"

                // --- Conta da empresa: saldo diferente de zero não fecha; pagamento em dinheiro com troco -----
                balance(companyFolio) shouldBe "250.00"
                call(POST, "/api/v1/folios/$companyFolio/close", receptionist.auth)
                    .problem(409, "FOLIO_BALANCE_NOT_ZERO")["balance"]
                    .asString() shouldBe "250.00"
                val cash =
                    pay(
                        companyFolio,
                        receptionist.auth,
                        mapOf("method" to "CASH", "amount" to "250.00", "tendered" to "300.00"),
                    ).expect(201)
                cash["change"].asString() shouldBe "50.00"
                balance(companyFolio) shouldBe "0.00"
                call(POST, "/api/v1/folios/$companyFolio/close", receptionist.auth).expect(200)

                withClue("a reserva está quitada (RN-06) e as duas contas encerradas") {
                    val settled = billingApi.balanceOf(FolioOwner(FolioOwnerType.RESERVATION, reservation))
                    settled.settled shouldBe true
                    settled.openFolios.shouldBeEmpty()
                    folio(companyFolio)["status"].asString() shouldBe "CLOSED"
                }

                // --- Sangria e fechamento do turno com diferença: a notificação --------------------------------
                call(
                    POST,
                    "/api/v1/cashier-shifts/$shiftId/movements",
                    receptionist.auth,
                    mapOf("type" to "WITHDRAWAL", "amount" to "30.00", "reason" to "Cofre"),
                ).expect(201)
                // Esperado em dinheiro: 100 + 250 − 30 = 320; declarado 310 (falta de 10). Cartão: o capturado.
                val report =
                    call(
                        POST,
                        "/api/v1/cashier-shifts/$shiftId/close",
                        receptionist.auth,
                        mapOf(
                            "counts" to
                                listOf(
                                    mapOf("method" to "CASH", "amount" to "310.00"),
                                    mapOf("method" to "CREDIT_CARD", "amount" to guestBalance.toString()),
                                ),
                        ),
                    ).expect(200)
                report["shift"]["hasDifference"].asBoolean() shouldBe true
                report["shift"]["counts"]
                    .values()
                    .single {
                        it["method"].asString() == "CASH"
                    }["difference"]
                    .asString() shouldBe
                    "-10.00"
                report["shift"]["counts"]
                    .values()
                    .single {
                        it["method"].asString() == "CREDIT_CARD"
                    }["difference"]
                    .asString() shouldBe
                    "0.00"
                val number = shift["number"].asString()
                withClue("o Gerente recebe a diferença no painel; a Recepcionista, que operou, não") {
                    val notice =
                        call(GET, "/api/v1/notifications", managerAuth)
                            .expect(200)["items"]
                            .values()
                            .single { it["title"].asString().endsWith(number) }
                    notice["type"].asString() shouldBe "billing.cashier-shift-difference"
                    notice["body"].asString() shouldContain pointName
                    notice["body"].asString() shouldContain "Dinheiro: falta de R$ 10,00."
                    for (text in listOf(notice["title"].asString(), notice["body"].asString())) {
                        text shouldNotContain guestName
                        text shouldNotContain cpf
                        text shouldNotContain receptionist.user.login
                    }
                    call(GET, "/api/v1/notifications", receptionist.auth)
                        .expect(200)["items"]
                        .values()
                        .filter { it["title"].asString().endsWith(number) }
                        .shouldBeEmpty()
                }

                // --- Extrato do hóspede -----------------------------------------------------------------------
                val statement =
                    call(
                        GET,
                        "/api/v1/folios/$guestFolio/statement?audience=GUEST",
                        receptionist.auth,
                    ).expect(200)
                withClue("a prévia do hóspede soma exatamente o saldo, sem dados internos") {
                    val lines = statement["days"].values().flatMap { it["lines"].values() }
                    lines
                        .map {
                            it["postingType"]
                                ?.takeUnless(
                                    JsonNode::isNull,
                                )?.asString() ?: it["paymentType"].asString()
                        }.shouldContainExactlyInAnyOrder(
                            listOf("REGULAR", "SERVICE_CHARGE", "REGULAR", "REVERSAL", "PAYMENT"),
                        )
                    lines.map { Money.of(it["amount"].asString()) }.fold(Money.ZERO, Money::plus) shouldBe Money.ZERO
                    statement["balance"].asString() shouldBe "0.00"
                    lines.forEach { it["internal"].isNull shouldBe true }
                    statement.toString() shouldNotContain REASON
                    statement.toString() shouldNotContain receptionist.user.id.toString()
                    statement.toString() shouldNotContain "AUT778"
                }

                // --- Imutabilidade (RN-03) --------------------------------------------------------------------
                withClue("o papel da aplicação não muda nem apaga lançamentos, pagamentos e movimentos de caixa") {
                    refusedByDatabase(INSUFFICIENT_PRIVILEGE) {
                        jdbc
                            .sql("UPDATE billing.posting SET amount = 0 WHERE id = :id")
                            .param("id", laundry.uuid("id"))
                            .update()
                    }
                    refusedByDatabase(INSUFFICIENT_PRIVILEGE) {
                        jdbc.sql("DELETE FROM billing.posting WHERE id = :id").param("id", laundry.uuid("id")).update()
                    }
                    refusedByDatabase(INSUFFICIENT_PRIVILEGE) {
                        jdbc
                            .sql("UPDATE billing.payment SET amount = 1 WHERE id = :id")
                            .param("id", cash.uuid("id"))
                            .update()
                    }
                    refusedByDatabase(INSUFFICIENT_PRIVILEGE) {
                        jdbc.sql("DELETE FROM billing.payment WHERE id = :id").param("id", cash.uuid("id")).update()
                    }
                    refusedByDatabase(INSUFFICIENT_PRIVILEGE) {
                        jdbc
                            .sql("UPDATE billing.cash_movement SET amount = 1 WHERE shift_id = :id")
                            .param("id", shiftId)
                            .update()
                    }
                    refusedByDatabase(INSUFFICIENT_PRIVILEGE) {
                        jdbc
                            .sql("DELETE FROM billing.cash_movement WHERE shift_id = :id")
                            .param("id", shiftId)
                            .update()
                    }
                }
                withClue("nem o dono dos schemas muda um lançamento: o trigger de imutabilidade recusa") {
                    val error =
                        shouldThrow<DataAccessException> {
                            owner
                                .sql("UPDATE billing.posting SET amount = 0 WHERE id = :id")
                                .param("id", laundry.uuid("id"))
                                .update()
                        }
                    val refusal =
                        generateSequence<Throwable>(error) { it.cause }
                            .filterIsInstance<SQLException>()
                            .first()
                    refusal.sqlState shouldBe RESTRICT_VIOLATION
                    refusal.message.orEmpty() shouldContain "billing.posting) são imutáveis"
                    postings(guestFolio).single { it["id"] == laundry["id"] }["amount"].asString() shouldBe "40.00"
                }

                // --- Nada do titular na trilha nem nas notificações -------------------------------------------
                withClue("nenhuma entrada da trilha nem notificação cita o nome, o CPF ou os dados sensíveis") {
                    for (value in listOf(guestName, cpf, formattedCpf(cpf), "INDIGENOUS", "VISUAL_LOW")) {
                        jdbc
                            .sql(
                                "SELECT count(*) FROM audit.audit_entry WHERE before_state::text LIKE :v " +
                                    "OR after_state::text LIKE :v",
                            ).param("v", "%$value%")
                            .query(Long::class.java)
                            .single() shouldBe 0L
                        jdbc
                            .sql("SELECT count(*) FROM notifications.notification WHERE title LIKE :v OR body LIKE :v")
                            .param("v", "%$value%")
                            .query(Long::class.java)
                            .single() shouldBe 0L
                    }
                    jdbc
                        .sql("SELECT count(*) FROM audit.audit_entry WHERE entity_id = :id")
                        .param("id", guestId.toString())
                        .query(Long::class.java)
                        .single() shouldNotBe 0L
                }
            }
        }

    // --- Apoio -----------------------------------------------------------------------------------------

    /**
     * Configura a taxa de serviço padrão do hotel (o que o administrador faz na implantação; o padrão é zero) durante
     * [block] e devolve a anterior no fim (o banco é compartilhado).
     */
    private fun withHotelServiceCharge(
        rate: String,
        block: () -> Unit,
    ) = with(billing) {
        val path = "/api/v1/hotel/tax-settings"
        val before = call(GET, path, admin).expect(200)

        fun put(value: String) {
            val body = jsonMapper.convertValue(before, Map::class.java) + ("serviceChargeRate" to value)
            call(PUT, path, admin, body, ifMatch = etagOf(path)).expect(200)
        }
        put(rate)
        try {
            block()
        } finally {
            put(before["serviceChargeRate"].asString())
        }
    }

    /** Quem opera numa sessão de dispositivo fixa (o token de aprovação fica vinculado a ela). */
    private class Operator(
        val user: TestUser,
        val sessionId: UUID = UuidV7.generate(),
    ) {
        val auth: RequestPostProcessor get() = asUser(user.id.value, sessionId)
    }

    /** Usuário novo com a role do modelo da SPEC §3.1 que o sistema cria no boot. */
    private fun userWithTemplate(template: String): TestUser {
        val role =
            jdbc
                .sql("SELECT id FROM iam.role WHERE template_key = :template")
                .param("template", template)
                .query(UUID::class.java)
                .single()
        return iam.createUser().also { iam.assignRole(it, RoleId(role)) }
    }

    private fun BillingTestSupport.approve(
        requester: Operator,
        required: JsonNode,
        approver: TestUser,
        pin: String,
    ): MockHttpServletResponse =
        call(
            POST,
            "/api/v1/auth/approvals",
            requester.auth,
            mapOf(
                "action" to required["action"].asString(),
                "permission" to required["permission"].asString(),
                "resourceRef" to required["resourceRef"].asString(),
                "limit" to
                    mapOf(
                        "key" to required["limit"]["key"].asString(),
                        "amount" to required["limit"]["amount"].asString(),
                    ),
                "approverLogin" to approver.login,
                "approverPin" to pin,
                "reason" to "Conferido com o hóspede",
            ),
            idempotencyKey = null,
        )

    private fun paymentCount(folio: UUID): Long =
        jdbc
            .sql("SELECT count(*) FROM billing.payment WHERE folio_id = :id")
            .param("id", folio)
            .query(Long::class.java)
            .single()

    private fun refusedByDatabase(
        sqlState: String,
        block: () -> Unit,
    ) {
        val error = shouldThrow<DataAccessException> { block() }
        (error.mostSpecificCause as SQLException).sqlState shouldBe sqlState
    }

    private companion object {
        const val HOTEL_SERVICE_CHARGE = "10.0000"
        const val REASON = "Lavanderia lançada no quarto errado"
        const val PIN_MIN = 1000
        const val PIN_MAX = 9999
        const val INSUFFICIENT_PRIVILEGE = "42501"
        const val RESTRICT_VIOLATION = "23001"
    }
}
