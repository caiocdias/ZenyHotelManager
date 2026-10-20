package com.zeny.hotel.rates

import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.rates.RatesTestSupport.Companion.definition
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.LimitUnit
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Cadastro do `rates` pela API (M4 E01, RF-TAR-01, 06 e 07): políticas de cancelamento e de garantia, pacotes e planos
 * tarifários com permissão, `If-Match`, cursor, auditoria e desativação; as referências a tipos de UH (pela
 * `RoomsApi`) e a códigos de lançamento (pela `BillingApi`) inexistentes (`400` com o campo) ou desativados (`422`);
 * nenhuma exclusão física; a permissão do desconto no catálogo (H7); e a API pública de leitura.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LargeClass") // cenários de ponta a ponta dos quatro cadastros e de cada recusa
class RatesApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val ratesApi: RatesApi,
    @Autowired @Qualifier("requestMappingHandlerMapping") private val handlerMapping: RequestMappingHandlerMapping,
) {
    private val rates = RatesTestSupport(iam)

    // --- Políticas de cancelamento ----------------------------------------------------------------------------------

    @Test
    fun `cancellation policies are created and read with permission, with the penalty of the type only`(): Unit =
        with(iam) {
            val reader = createUserWith(TestGrant(definition(RatesPermissions.PLANS_READ), ALL))
            val name = rates.uniqueName("Flexível")
            val body = percentBody(name, "50")
            call(POST, CANCELLATIONS, asTestUser(reader), body).problem(403, "PERMISSION_DENIED")

            val created = call(POST, CANCELLATIONS, asAdmin(), body)
            val createdBody = created.expectStatus(201).shouldNotBeNull()
            val id = UUID.fromString(createdBody["id"].asString())
            created.getHeader(HttpHeaders.LOCATION) shouldBe "$CANCELLATIONS/$id"
            created.getHeader(HttpHeaders.ETAG) shouldBe "\"0\""
            createdBody["penaltyPercent"].asString() shouldBe "50.0000"
            createdBody["penaltyNights"].isNull shouldBe true
            createdBody["active"].asBoolean() shouldBe true
            call(GET, "$CANCELLATIONS/$id", asTestUser(reader)).ok()["name"].asString() shouldBe name

            // Nome repetido em outra caixa: 409; multa incoerente com o tipo: 400 com o campo.
            admin(POST, CANCELLATIONS, rates.cancellationBody(name = name.uppercase()))
                .problem(409, "CANCELLATION_POLICY_NAME_ALREADY_IN_USE")
            val fixedWithNights = rates.cancellationBody(penaltyType = "FIXED_AMOUNT", penaltyNights = 1)
            fieldOf(admin(POST, CANCELLATIONS, fixedWithNights).problem(400, "CANCELLATION_POLICY_INVALID")) shouldBe
                "penaltyAmount"
            val nightsWithAmount = rates.cancellationBody(penaltyNights = 1, penaltyAmount = "10.00")
            fieldOf(admin(POST, CANCELLATIONS, nightsWithAmount).problem(400, "CANCELLATION_POLICY_INVALID")) shouldBe
                "penaltyAmount"
            fieldOf(
                admin(
                    POST,
                    CANCELLATIONS,
                    percentBody(rates.uniqueName("X"), "120"),
                ).problem(400, "CANCELLATION_POLICY_INVALID"),
            ) shouldBe "penaltyPercent"
            admin(POST, CANCELLATIONS, rates.cancellationBody(deadlineHours = -1)).problem(400, "VALIDATION_FAILED")
            call(GET, "$CANCELLATIONS/${UUID.randomUUID()}", asAdmin()).problem(404, "CANCELLATION_POLICY_NOT_FOUND")
            call(GET, CANCELLATIONS, asTestUser(createUser())).problem(403, "PERMISSION_DENIED")
        }

    @Test
    fun `cancellation policies are edited with If-Match, deactivated, reactivated and audited, never deleted`(): Unit =
        with(iam) {
            val reader = createUserWith(TestGrant(definition(RatesPermissions.PLANS_READ), ALL))
            val name = rates.uniqueName("Flexível")
            val id = rates.createCancellationPolicy(percentBody(name, "50"))
            val path = "$CANCELLATIONS/$id"
            val etag = rates.etagOf(path)
            val edited =
                rates.cancellationBody(
                    name,
                    penaltyType = "FIXED_AMOUNT",
                    penaltyNights = null,
                    penaltyAmount = "150.00",
                )
            call(PUT, path, asTestUser(reader), edited, ifMatch = etag).problem(403, "PERMISSION_DENIED")
            admin(PUT, path, edited).problem(428, "PRECONDITION_REQUIRED")
            call(PUT, path, asAdmin(), edited, ifMatch = "\"999\"").problem(412, "PRECONDITION_FAILED")
            val updated = call(PUT, path, asAdmin(), edited, ifMatch = etag).ok()
            updated["penaltyType"].asString() shouldBe "FIXED_AMOUNT"
            updated["penaltyAmount"].asString() shouldBe "150.00"
            updated["penaltyPercent"].isNull shouldBe true
            rates.etagOf(path) shouldBe "\"1\""

            call(POST, "$path/deactivate", asTestUser(reader)).problem(403, "PERMISSION_DENIED")
            admin(POST, "$path/deactivate").ok()["active"].asBoolean() shouldBe false
            rates.listAll("$CANCELLATIONS?active=true").map { it["id"].asString() } shouldNotContain id.toString()
            rates.listAll("$CANCELLATIONS?active=false").map { it["id"].asString() } shouldContain id.toString()
            call(POST, "$path/reactivate", asTestUser(reader)).problem(403, "PERMISSION_DENIED")
            admin(POST, "$path/reactivate").ok()["active"].asBoolean() shouldBe true

            auditActions("rates.cancellation-policy", id) shouldContainExactly
                lifecycle("rates.cancellation-policy")
            refusesDelete("rates.cancellation_policy", id)
            val summary = ratesApi.cancellationPolicy(CancellationPolicyId(id)).shouldNotBeNull()
            summary.penaltyType shouldBe CancellationPenaltyType.FIXED_AMOUNT
            summary.penaltyAmount shouldBe Money.of("150.00")
            summary.deadlineHours shouldBe 48
            ratesApi.cancellationPolicy(CancellationPolicyId(UUID.randomUUID())).shouldBeNull()
        }

    // --- Políticas de garantia --------------------------------------------------------------------------------------

    @Test
    fun `guarantee policies keep the deposit percent and due days only, and always, in DEPOSIT`(): Unit =
        with(iam) {
            val reader = createUserWith(TestGrant(definition(RatesPermissions.PLANS_READ), ALL))
            call(POST, GUARANTEES, asTestUser(reader), rates.guaranteeBody()).problem(403, "PERMISSION_DENIED")
            fieldOf(
                admin(POST, GUARANTEES, rates.guaranteeBody(depositPercent = null)).problem(400, GUARANTEE_INVALID),
            ) shouldBe
                "depositPercent"
            fieldOf(
                admin(POST, GUARANTEES, rates.guaranteeBody(dueDaysBefore = null)).problem(400, GUARANTEE_INVALID),
            ) shouldBe
                "dueDaysBefore"
            val cardWithPercent = rates.guaranteeBody(type = "CARD", dueDaysBefore = null)
            fieldOf(admin(POST, GUARANTEES, cardWithPercent).problem(400, GUARANTEE_INVALID)) shouldBe "depositPercent"
            fieldOf(
                admin(POST, GUARANTEES, rates.guaranteeBody(depositPercent = "0")).problem(400, GUARANTEE_INVALID),
            ) shouldBe
                "depositPercent"

            val id = rates.createGuaranteePolicy()
            val current = call(GET, "$GUARANTEES/$id", asTestUser(reader)).ok()
            current["type"].asString() shouldBe "DEPOSIT"
            current["depositPercent"].asString() shouldBe "30.0000"
            current["dueDaysBefore"].asInt() shouldBe 7
            admin(POST, GUARANTEES, rates.guaranteeBody(name = current["name"].asString()))
                .problem(409, "GUARANTEE_POLICY_NAME_ALREADY_IN_USE")
            call(GET, "$GUARANTEES/${UUID.randomUUID()}", asAdmin()).problem(404, "GUARANTEE_POLICY_NOT_FOUND")
        }

    @Test
    fun `guarantee policies are edited with If-Match, deactivated, reactivated and audited, never deleted`(): Unit =
        with(iam) {
            val reader = createUserWith(TestGrant(definition(RatesPermissions.PLANS_READ), ALL))
            val name = rates.uniqueName("Depósito")
            val id = rates.createGuaranteePolicy(rates.guaranteeBody(name = name))
            val path = "$GUARANTEES/$id"
            val card = rates.guaranteeBody(name = name, type = "CARD", depositPercent = null, dueDaysBefore = null)
            admin(PUT, path, card).problem(428, "PRECONDITION_REQUIRED")
            val updated = call(PUT, path, asAdmin(), card, ifMatch = rates.etagOf(path)).ok()
            updated["type"].asString() shouldBe "CARD"
            updated["depositPercent"].isNull shouldBe true
            updated["dueDaysBefore"].isNull shouldBe true

            call(POST, "$path/deactivate", asTestUser(reader)).problem(403, "PERMISSION_DENIED")
            admin(POST, "$path/deactivate").ok()["active"].asBoolean() shouldBe false
            admin(POST, "$path/reactivate").ok()["active"].asBoolean() shouldBe true
            auditActions("rates.guarantee-policy", id) shouldContainExactly lifecycle("rates.guarantee-policy")
            refusesDelete("rates.guarantee_policy", id)
            ratesApi.guaranteePolicy(GuaranteePolicyId(id)).shouldNotBeNull().type shouldBe GuaranteeType.CARD
        }

    // --- Pacotes ----------------------------------------------------------------------------------------------------

    @Test
    fun `a package needs an existing, active consumption transaction code checked by the BillingApi`(): Unit =
        with(iam) {
            val reader = createUserWith(TestGrant(definition(RatesPermissions.PLANS_READ), ALL))
            val code = rates.createTransactionCode()
            call(POST, PACKAGES, asTestUser(reader), rates.packageBody(code)).problem(403, "PERMISSION_DENIED")

            // Sem código de lançamento: 400 no DTO; inexistente: 400 com o campo; desativado ou de crédito: 422.
            fieldOf(admin(POST, PACKAGES, rates.packageBody(null)).problem(400, "VALIDATION_FAILED")) shouldBe
                "transactionCodeId"
            fieldOf(
                admin(POST, PACKAGES, rates.packageBody(UUID.randomUUID())).problem(400, "UNKNOWN_TRANSACTION_CODE"),
            ) shouldBe
                "transactionCodeId"
            val inactive = rates.createTransactionCode()
            admin(POST, "/api/v1/transaction-codes/$inactive/deactivate").ok()
            admin(POST, PACKAGES, rates.packageBody(inactive)).problem(422, "TRANSACTION_CODE_INACTIVE")
            val credit = rates.createTransactionCode(kind = "CREDIT")
            admin(POST, PACKAGES, rates.packageBody(credit)).problem(422, "TRANSACTION_CODE_KIND_NOT_ALLOWED")
            // Preço por estadia só na chegada; preço zero recusado.
            val perStayDaily = rates.packageBody(code, pricing = "PER_STAY", postingRhythm = "DAILY")
            fieldOf(admin(POST, PACKAGES, perStayDaily).problem(400, "PACKAGE_INVALID")) shouldBe "postingRhythm"
            fieldOf(
                admin(POST, PACKAGES, rates.packageBody(code, price = "0.00")).problem(400, "PACKAGE_INVALID"),
            ) shouldBe
                "price"

            val pkgCode = rates.uniqueCode("CAFE")
            val created = admin(POST, PACKAGES, rates.packageBody(code, code = pkgCode.lowercase()))
            val id = UUID.fromString(created.expectStatus(201)!!["id"].asString())
            created.getHeader(HttpHeaders.LOCATION) shouldBe "$PACKAGES/$id"
            val read = call(GET, "$PACKAGES/$id", asTestUser(reader)).ok()
            read["code"].asString() shouldBe pkgCode
            read["transactionCodeId"].asString() shouldBe code.toString()
            read["price"].asString() shouldBe "35.00"
            admin(POST, PACKAGES, rates.packageBody(code, code = pkgCode)).problem(409, "PACKAGE_CODE_ALREADY_IN_USE")
            call(GET, "$PACKAGES/${UUID.randomUUID()}", asAdmin()).problem(404, "PACKAGE_NOT_FOUND")
        }

    @Test
    fun `packages are edited with If-Match, keep a linked code deactivated later, and are never deleted`(): Unit =
        with(iam) {
            val reader = createUserWith(TestGrant(definition(RatesPermissions.PLANS_READ), ALL))
            val code = rates.createTransactionCode()
            val inactive = rates.createTransactionCode()
            admin(POST, "/api/v1/transaction-codes/$inactive/deactivate").ok()
            val pkgCode = rates.uniqueCode("CAFE")
            val id =
                UUID.fromString(
                    admin(POST, PACKAGES, rates.packageBody(code, code = pkgCode)).expectStatus(201)!!["id"].asString(),
                )
            val path = "$PACKAGES/$id"

            // O código já vinculado e desativado depois no billing não impede a edição; trocar por um desativado, sim.
            admin(POST, "/api/v1/transaction-codes/$code/deactivate").ok()
            val edited =
                rates.packageBody(
                    code,
                    pkgCode,
                    price = "40.00",
                    pricing = "PER_STAY",
                    postingRhythm = "ON_ARRIVAL",
                )
            admin(PUT, path, edited).problem(428, "PRECONDITION_REQUIRED")
            call(PUT, path, asAdmin(), edited, ifMatch = "\"9\"").problem(412, "PRECONDITION_FAILED")
            call(PUT, path, asAdmin(), edited, ifMatch = rates.etagOf(path)).ok()["price"].asString() shouldBe "40.00"
            call(PUT, path, asAdmin(), rates.packageBody(inactive, code = pkgCode), ifMatch = rates.etagOf(path))
                .problem(422, "TRANSACTION_CODE_INACTIVE")
            admin(POST, "/api/v1/transaction-codes/$code/reactivate").ok()

            call(POST, "$path/deactivate", asTestUser(reader)).problem(403, "PERMISSION_DENIED")
            admin(POST, "$path/deactivate").ok()["active"].asBoolean() shouldBe false
            admin(POST, "$path/reactivate").ok()["active"].asBoolean() shouldBe true
            auditActions("rates.package", id) shouldContainExactly lifecycle("rates.package")
            refusesDelete("rates.rate_package", id)

            val summary = ratesApi.ratePackage(PackageId(id)).shouldNotBeNull()
            summary.transactionCodeId shouldBe TransactionCodeId(code)
            summary.pricing shouldBe PackagePricing.PER_STAY
            summary.postingRhythm shouldBe PackagePostingRhythm.ON_ARRIVAL
            summary.price shouldBe Money.of("40.00")
        }

    @Test
    fun `the database refuses a package without a transaction code, even by direct SQL`() {
        shouldThrow<DataAccessException> {
            transactions.executeWithoutResult {
                jdbc
                    .sql(
                        "INSERT INTO rates.rate_package (id, code, name, price, pricing, posting_rhythm, " +
                            "included_in_rate, transaction_code_id, active, created_at, updated_at, version) " +
                            "VALUES (:id, :code, 'Sem código', 10, 'PER_NIGHT', 'DAILY', false, NULL, true, " +
                            "now(), now(), 0)",
                    ).param("id", UUID.randomUUID())
                    .param("code", rates.uniqueCode("NOTC"))
                    .update()
            }
        }
    }

    // --- Planos tarifários ------------------------------------------------------------------------------------------

    @Test
    fun `rate plans are created with permission, a fixed category and the sets of the body`(): Unit =
        with(iam) {
            val reader = createUserWith(TestGrant(definition(RatesPermissions.PLANS_READ), ALL))
            val roomType = rates.rooms.createRoomType()
            val cancellation = rates.createCancellationPolicy()
            val guarantee = rates.createGuaranteePolicy()
            val pkg = rates.createPackage()
            val code = rates.uniqueCode("RP")
            val body =
                rates.planBody(
                    listOf(roomType),
                    cancellation,
                    guarantee,
                    listOf(pkg),
                    code.lowercase(),
                    category = "COURTESY",
                )
            call(POST, PLANS, asTestUser(reader), body).problem(403, "PERMISSION_DENIED")

            val created = admin(POST, PLANS, body)
            val createdBody = created.expectStatus(201).shouldNotBeNull()
            val id = UUID.fromString(createdBody["id"].asString())
            created.getHeader(HttpHeaders.LOCATION) shouldBe "$PLANS/$id"
            created.getHeader(HttpHeaders.ETAG) shouldBe "\"0\""
            createdBody["code"].asString() shouldBe code
            createdBody["category"].asString() shouldBe "COURTESY"
            createdBody["mealPlan"].asString() shouldBe "BREAKFAST"
            createdBody["channels"].values().map { it.asString() } shouldContainExactly listOf("DIRECT", "WEBSITE")
            createdBody["roomTypeIds"].values().map { it.asString() } shouldContainExactly listOf(roomType.toString())
            createdBody["packageIds"].values().map { it.asString() } shouldContainExactly listOf(pkg.toString())
            call(GET, "$PLANS/$id", asTestUser(reader)).ok()["name"].asString() shouldBe body["name"]

            admin(POST, PLANS, rates.planBody(listOf(roomType), cancellation, guarantee, code = code))
                .problem(409, "RATE_PLAN_CODE_ALREADY_IN_USE")
            admin(POST, PLANS, rates.planBody(listOf(roomType), cancellation, guarantee, channels = emptyList()))
                .problem(400, "VALIDATION_FAILED")
            admin(POST, PLANS, rates.planBody(emptyList(), cancellation, guarantee)).problem(400, "VALIDATION_FAILED")
            admin(POST, PLANS, rates.planBody(listOf(roomType), cancellation, guarantee, category = null))
                .problem(400, "VALIDATION_FAILED")
            call(GET, "$PLANS/${UUID.randomUUID()}", asAdmin()).problem(404, "RATE_PLAN_NOT_FOUND")
            call(GET, PLANS, asTestUser(createUser())).problem(403, "PERMISSION_DENIED")
        }

    @Test
    fun `rate plans are edited with If-Match, all but the category, and filtered in the list`(): Unit =
        with(iam) {
            val reader = createUserWith(TestGrant(definition(RatesPermissions.PLANS_READ), ALL))
            val fixture = rates.createPlan(category = "COURTESY")
            val otherType = rates.rooms.createRoomType()
            val path = "$PLANS/${fixture.plan}"
            val code = admin(GET, path).ok()["code"].asString()
            val edited =
                rates.planBody(
                    listOf(fixture.roomType, otherType),
                    fixture.cancellationPolicy,
                    fixture.guaranteePolicy,
                    emptyList(),
                    code,
                    category = "HOUSE_USE",
                    channels = listOf("OTA"),
                    mealPlan = "HALF_BOARD",
                )
            call(PUT, path, asTestUser(reader), edited, ifMatch = rates.etagOf(path)).problem(403, "PERMISSION_DENIED")
            admin(PUT, path, edited).problem(428, "PRECONDITION_REQUIRED")
            call(PUT, path, asAdmin(), edited, ifMatch = "\"9\"").problem(412, "PRECONDITION_FAILED")
            val updated = call(PUT, path, asAdmin(), edited, ifMatch = rates.etagOf(path)).ok()
            updated["category"].asString() shouldBe "COURTESY" // fixa desde a criação
            updated["mealPlan"].asString() shouldBe "HALF_BOARD"
            updated["channels"].values().map { it.asString() } shouldContainExactly listOf("OTA")
            updated["roomTypeIds"].values().map { it.asString() } shouldContainExactlyInAnyOrder
                listOf(fixture.roomType.toString(), otherType.toString())
            updated["packageIds"].values().toList() shouldBe emptyList()

            rates.listAll("$PLANS?roomTypeId=$otherType").map { it["id"].asString() } shouldContainExactly
                listOf(fixture.plan.toString())
            rates.listAll("$PLANS?roomTypeId=$otherType&channel=DIRECT") shouldBe emptyList()
            rates.listAll("$PLANS?roomTypeId=$otherType&channel=OTA&category=COURTESY").size shouldBe 1
            rates.listAll("$PLANS?roomTypeId=$otherType&category=NORMAL") shouldBe emptyList()

            val summary = ratesApi.ratePlan(RatePlanId(fixture.plan)).shouldNotBeNull()
            summary.category shouldBe RatePlanCategory.COURTESY
            summary.channels shouldBe setOf(SalesChannel.OTA)
            summary.roomTypeIds shouldBe setOf(RoomTypeId(fixture.roomType), RoomTypeId(otherType))
            summary.cancellationPolicyId shouldBe CancellationPolicyId(fixture.cancellationPolicy)
            summary.active shouldBe true
        }

    @Test
    fun `rate plans are deactivated and reactivated, audited without free text, and never deleted`(): Unit =
        with(iam) {
            val reader = createUserWith(TestGrant(definition(RatesPermissions.PLANS_READ), ALL))
            val fixture = rates.createPlan()
            val path = "$PLANS/${fixture.plan}"
            val code = admin(GET, path).ok()["code"].asString()
            val renamed =
                rates.planBody(
                    listOf(fixture.roomType),
                    fixture.cancellationPolicy,
                    fixture.guaranteePolicy,
                    code = code,
                )
            call(PUT, path, asAdmin(), renamed, ifMatch = rates.etagOf(path)).ok()

            call(POST, "$path/deactivate", asTestUser(reader)).problem(403, "PERMISSION_DENIED")
            admin(POST, "$path/deactivate").ok()["active"].asBoolean() shouldBe false
            rates.listAll("$PLANS?roomTypeId=${fixture.roomType}&active=true") shouldBe emptyList()
            call(POST, "$path/reactivate", asTestUser(reader)).problem(403, "PERMISSION_DENIED")
            admin(POST, "$path/reactivate").ok()["active"].asBoolean() shouldBe true

            auditActions("rates.rate-plan", fixture.plan) shouldContainExactly lifecycle("rates.rate-plan")
            // O retrato não leva texto livre: a descrição entra só como "hasDescription".
            val states = auditStates("rates.rate-plan", fixture.plan)
            states.forEach { it shouldNotContain "Tarifa de balcão com café" }
            states.first() shouldNotContain "description\""
            refusesDelete("rates.rate_plan", fixture.plan)
        }

    @Test
    fun `a plan refuses unknown references with 400 and the field`(): Unit =
        with(iam) {
            val roomType = rates.rooms.createRoomType()
            val cancellation = rates.createCancellationPolicy()
            val guarantee = rates.createGuaranteePolicy()
            val unknown = UUID.randomUUID()

            val unknownType = rates.planBody(listOf(roomType, unknown), cancellation, guarantee)
            fieldOf(admin(POST, PLANS, unknownType).problem(400, "UNKNOWN_ROOM_TYPE")) shouldBe "roomTypeIds"
            val unknownCancellation = rates.planBody(listOf(roomType), unknown, guarantee)
            fieldOf(admin(POST, PLANS, unknownCancellation).problem(400, "UNKNOWN_CANCELLATION_POLICY")) shouldBe
                "cancellationPolicyId"
            val unknownGuarantee = rates.planBody(listOf(roomType), cancellation, unknown)
            fieldOf(admin(POST, PLANS, unknownGuarantee).problem(400, "UNKNOWN_GUARANTEE_POLICY")) shouldBe
                "guaranteePolicyId"
            val unknownPackage = rates.planBody(listOf(roomType), cancellation, guarantee, listOf(unknown))
            fieldOf(admin(POST, PLANS, unknownPackage).problem(400, "UNKNOWN_PACKAGE")) shouldBe "packageIds"
        }

    @Test
    fun `a plan refuses an inactive room type, policy or package with 422`(): Unit =
        with(iam) {
            val roomType = rates.rooms.createRoomType()
            val cancellation = rates.createCancellationPolicy()
            val guarantee = rates.createGuaranteePolicy()

            val inactiveType = rates.rooms.createRoomType()
            admin(POST, "/api/v1/room-types/$inactiveType/deactivate").ok()
            admin(
                POST,
                PLANS,
                rates.planBody(listOf(inactiveType), cancellation, guarantee),
            ).problem(422, "ROOM_TYPE_INACTIVE")
            val inactiveCancellation = rates.createCancellationPolicy()
            admin(POST, "$CANCELLATIONS/$inactiveCancellation/deactivate").ok()
            admin(POST, PLANS, rates.planBody(listOf(roomType), inactiveCancellation, guarantee))
                .problem(422, "CANCELLATION_POLICY_INACTIVE")
            val inactiveGuarantee = rates.createGuaranteePolicy()
            admin(POST, "$GUARANTEES/$inactiveGuarantee/deactivate").ok()
            admin(POST, PLANS, rates.planBody(listOf(roomType), cancellation, inactiveGuarantee))
                .problem(422, "GUARANTEE_POLICY_INACTIVE")
            val inactivePackage = rates.createPackage()
            admin(POST, "$PACKAGES/$inactivePackage/deactivate").ok()
            admin(POST, PLANS, rates.planBody(listOf(roomType), cancellation, guarantee, listOf(inactivePackage)))
                .problem(422, "PACKAGE_INACTIVE")
        }

    @Test
    fun `an edited plan checks only the new links`(): Unit =
        with(iam) {
            // Um tipo já vinculado e desativado depois no rooms não impede a edição; acrescentar um desativado, sim.
            val fixture = rates.createPlan()
            val inactiveType = rates.rooms.createRoomType()
            admin(POST, "/api/v1/room-types/$inactiveType/deactivate").ok()
            admin(POST, "/api/v1/room-types/${fixture.roomType}/deactivate").ok()
            val path = "$PLANS/${fixture.plan}"
            val code = admin(GET, path).ok()["code"].asString()

            fun body(
                roomTypes: List<UUID>,
                packages: List<UUID> = listOf(fixture.ratePackage),
                name: String = "Renomeado",
            ) = rates.planBody(roomTypes, fixture.cancellationPolicy, fixture.guaranteePolicy, packages, code, name)
            call(PUT, path, asAdmin(), body(listOf(fixture.roomType)), ifMatch = rates.etagOf(path))
                .ok()["name"]
                .asString() shouldBe "Renomeado"
            call(PUT, path, asAdmin(), body(listOf(fixture.roomType, inactiveType)), ifMatch = rates.etagOf(path))
                .problem(422, "ROOM_TYPE_INACTIVE")
            call(
                PUT,
                path,
                asAdmin(),
                body(listOf(fixture.roomType), listOf(UUID.randomUUID())),
                ifMatch = rates.etagOf(path),
            ).problem(400, "UNKNOWN_PACKAGE")
        }

    @Test
    fun `an active plan never keeps an inactive policy or package`(): Unit =
        with(iam) {
            val fixture = rates.createPlan()
            val planPath = "/api/v1/rate-plans/${fixture.plan}"

            // Em uso por um plano ativo, políticas e pacote não são desativados.
            call(POST, "/api/v1/cancellation-policies/${fixture.cancellationPolicy}/deactivate", asAdmin())
                .problem(422, "CANCELLATION_POLICY_IN_USE")
            call(POST, "/api/v1/guarantee-policies/${fixture.guaranteePolicy}/deactivate", asAdmin())
                .problem(422, "GUARANTEE_POLICY_IN_USE")
            call(POST, "/api/v1/packages/${fixture.ratePackage}/deactivate", asAdmin()).problem(422, "PACKAGE_IN_USE")

            // Com o plano desativado, sim; e o plano só volta com elas ativas.
            call(POST, "$planPath/deactivate", asAdmin()).ok()
            call(POST, "/api/v1/cancellation-policies/${fixture.cancellationPolicy}/deactivate", asAdmin()).ok()
            call(POST, "/api/v1/packages/${fixture.ratePackage}/deactivate", asAdmin()).ok()
            call(POST, "$planPath/reactivate", asAdmin()).problem(422, "CANCELLATION_POLICY_INACTIVE")
            call(POST, "/api/v1/cancellation-policies/${fixture.cancellationPolicy}/reactivate", asAdmin()).ok()
            call(POST, "$planPath/reactivate", asAdmin()).problem(422, "PACKAGE_INACTIVE")
            call(POST, "/api/v1/packages/${fixture.ratePackage}/reactivate", asAdmin()).ok()
            call(POST, "$planPath/reactivate", asAdmin()).ok()["active"].asBoolean() shouldBe true
            // Reativar de novo não muda nada (sem nova entrada na trilha).
            call(POST, "$planPath/reactivate", asAdmin()).ok()
            auditActions("rates.rate-plan", fixture.plan).count { it == "rates.rate-plan.reactivate" } shouldBe 1
        }

    @Test
    fun `deactivating a policy while a plan links it never leaves an active plan with an inactive policy`(): Unit =
        with(iam) {
            val roomType = rates.rooms.createRoomType()
            val guarantee = rates.createGuaranteePolicy()
            repeat(RACE_ROUNDS) {
                val cancellation = rates.createCancellationPolicy()
                val start = CountDownLatch(1)
                var planStatus = 0
                var deactivateStatus = 0
                val creator =
                    thread {
                        start.await()
                        planStatus =
                            call(
                                POST,
                                "/api/v1/rate-plans",
                                asAdmin(),
                                rates.planBody(listOf(roomType), cancellation, guarantee),
                            ).status
                    }
                val deactivator =
                    thread {
                        start.await()
                        deactivateStatus =
                            call(POST, "/api/v1/cancellation-policies/$cancellation/deactivate", asAdmin()).status
                    }
                start.countDown()
                creator.join()
                deactivator.join()
                // Uma das duas vence; a outra é recusada com 422 (nunca as duas, nunca 500).
                listOf(planStatus to deactivateStatus) shouldContain
                    (if (planStatus == 201) 201 to 422 else 422 to 200)
                jdbc
                    .sql(
                        "SELECT count(*) FROM rates.rate_plan p JOIN rates.cancellation_policy c " +
                            "ON c.id = p.cancellation_policy_id WHERE p.active AND NOT c.active AND c.id = :id",
                    ).param("id", cancellation)
                    .query(Long::class.java)
                    .single() shouldBe 0L
            }
        }

    // --- Permissões, mapeamentos e catálogo -------------------------------------------------------------------------

    @Test
    fun `the discount permission is sensitive with maxPercent, unlimited for managers, 10 for receptionists`(): Unit =
        with(iam) {
            val discount = definition(RatesPermissions.DISCOUNTS_APPLY)
            discount.sensitive shouldBe true
            discount.scopes shouldBe setOf(ALL)
            val limit = discount.limits.single()
            limit.key shouldBe "maxPercent"
            limit.unit shouldBe LimitUnit.PERCENT

            val catalog = rates.listAll("/api/v1/permissions")
            val listed = catalog.single { it["code"].asString() == "rates.discounts.apply" }
            listed["module"].asString() shouldBe "rates"
            listed["sensitive"].asBoolean() shouldBe true
            listed["limits"].values().map { it["key"].asString() to it["unit"].asString() } shouldContainExactly
                listOf("maxPercent" to "PERCENT")
            catalog.map { it["code"].asString() }.filter { it.startsWith("rates.") } shouldContainExactlyInAnyOrder
                listOf("rates.plans.read", "rates.plans.manage", "rates.discounts.apply")

            val templateLimits =
                jdbc
                    .sql(
                        """
                        SELECT r.template_key, l.unlimited, l.max_value FROM iam.role r
                        JOIN iam.role_grant g ON g.role_id = r.id
                        JOIN iam.role_grant_limit l ON l.grant_id = g.id AND l.limit_key = 'maxPercent'
                        WHERE r.template_key IN ('MANAGER', 'RECEPTIONIST', 'SALES_EVENTS', 'FINANCE')
                          AND g.permission_code = 'rates.discounts.apply'
                        """.trimIndent(),
                    ).query { rs, _ ->
                        Triple(
                            rs.getString(1),
                            rs.getBoolean(2),
                            rs.getBigDecimal(3)?.stripTrailingZeros()?.toPlainString(),
                        )
                    }.list()
            templateLimits shouldContainExactlyInAnyOrder
                listOf(Triple("MANAGER", true, null), Triple("RECEPTIONIST", false, "10"))
            discount.templates.keys shouldBe setOf(RoleTemplate.MANAGER, RoleTemplate.RECEPTIONIST)
            discount.templates.getValue(RoleTemplate.MANAGER).limits shouldBe
                mapOf("maxPercent" to LimitValue.Unlimited)
            (
                discount.templates
                    .getValue(RoleTemplate.RECEPTIONIST)
                    .limits
                    .getValue("maxPercent") as LimitValue.Max
            ).value
                .compareTo(BigDecimal.TEN) shouldBe 0
        }

    @Test
    fun `rates exposes no DELETE, only list, detail, create, update, deactivate and reactivate`() {
        val ratesMappings =
            handlerMapping.handlerMethods.filter { (_, method) ->
                method.beanType.packageName.startsWith("com.zeny.hotel.rates")
            }
        ratesMappings.keys
            .flatMap { it.methodsCondition.methods }
            .map { it.name }
            .toSet() shouldBe setOf("GET", "POST", "PUT")
        ratesMappings.keys.flatMap { it.patternValues }.toSet() shouldContainExactlyInAnyOrder
            listOf(
                "/api/v1/rate-plans",
                "/api/v1/cancellation-policies",
                "/api/v1/guarantee-policies",
                "/api/v1/packages",
            ).flatMap { listOf(it, "$it/{id}", "$it/{id}/deactivate", "$it/{id}/reactivate") }
    }

    // --- Apoio ------------------------------------------------------------------------------------------------------

    private fun fieldOf(problem: tools.jackson.databind.JsonNode): String = problem["errors"][0]["field"].asString()

    /** Uma chamada como o administrador. */
    private fun admin(
        method: HttpMethod,
        path: String,
        body: Any? = null,
    ): MockHttpServletResponse = iam.call(method, path, iam.asAdmin(), body)

    private fun percentBody(
        name: String,
        percent: String,
    ) = rates.cancellationBody(name, penaltyType = "PERCENT_OF_STAY", penaltyPercent = percent, penaltyNights = null)

    /** As quatro ações do ciclo de vida de [entity], na ordem. */
    private fun lifecycle(entity: String) =
        listOf("$entity.create", "$entity.update", "$entity.deactivate", "$entity.reactivate")

    private fun refusesDelete(
        table: String,
        id: UUID,
    ) {
        val error =
            shouldThrow<DataAccessException> {
                transactions.executeWithoutResult {
                    jdbc.sql("DELETE FROM $table WHERE id = :id").param("id", id).update()
                }
            }
        withClue(error.toString()) {
            generateSequence<Throwable>(
                error,
            ) { it.cause }.any { "nunca são apagados" in it.message.orEmpty() } shouldBe
                true
        }
        jdbc
            .sql("SELECT count(*) FROM $table WHERE id = :id")
            .param("id", id)
            .query(Int::class.java)
            .single() shouldBe 1
    }

    private fun auditActions(
        entityType: String,
        entityId: UUID,
    ): List<String> =
        jdbc
            .sql(
                "SELECT action FROM audit.audit_entry WHERE entity_type = :type AND entity_id = :id " +
                    "ORDER BY occurred_at, id",
            ).param("type", entityType)
            .param("id", entityId.toString())
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun auditStates(
        entityType: String,
        entityId: UUID,
    ): List<String> =
        jdbc
            .sql(
                "SELECT coalesce(before_state::text, '') || ' ' || coalesce(after_state::text, '') " +
                    "FROM audit.audit_entry WHERE entity_type = :type AND entity_id = :id ORDER BY occurred_at, id",
            ).param("type", entityType)
            .param("id", entityId.toString())
            .query(String::class.java)
            .list()
            .filterNotNull()

    private companion object {
        const val RACE_ROUNDS = 5
        const val PLANS = "/api/v1/rate-plans"
        const val CANCELLATIONS = "/api/v1/cancellation-policies"
        const val GUARANTEES = "/api/v1/guarantee-policies"
        const val PACKAGES = "/api/v1/packages"
        const val GUARANTEE_INVALID = "GUARANTEE_POLICY_INVALID"
    }
}
