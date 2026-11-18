package com.zeny.hotel.housekeeping

import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.CLEANING_TYPES
import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.definition
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * Os extremos aceitos das faixas do tipo de limpeza (M6 E01, Task 1; Review Focus 2, pelo lado de dentro): prioridade
 * 1 e 5, enxoval 1 e 60, `credits` 1 e 100 e exatamente 200 créditos são gravados, na criação e na edição. E a ordem
 * observada entre a forma do corpo e a autorização para um item nulo nos créditos, enviado por quem não mantém.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class CleaningTypesBoundaryTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
) {
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)
    private val rooms = housekeeping.rooms

    @Test
    fun `the lowest and highest priority, linen nights and credits are accepted on POST and on PUT`(): Unit =
        with(iam) {
            val low = rooms.createRoomType()
            val high = rooms.createRoomType()
            val created =
                call(
                    POST,
                    CLEANING_TYPES,
                    asAdmin(),
                    housekeeping.cleaningTypeBody(
                        defaultPriority = 1,
                        linenChangeEveryNights = 60,
                        credits = listOf(housekeeping.credit(low, 1), housekeeping.credit(high, 100)),
                    ),
                ).expectStatus(201)!!
            created["defaultPriority"].asInt() shouldBe 1
            created["linenChangeEveryNights"].asInt() shouldBe 60
            val id = UUID.fromString(created["id"].asString())
            housekeeping.storedCredits(id) shouldBe mapOf(low to 1, high to 100)

            val edited =
                call(
                    PUT,
                    "$CLEANING_TYPES/$id",
                    asAdmin(),
                    housekeeping.cleaningTypeBody(
                        code = created["code"].asString(),
                        defaultPriority = 5,
                        linenChangeEveryNights = 1,
                        credits = listOf(housekeeping.credit(low, 100), housekeeping.credit(high, 1)),
                    ),
                    ifMatch = "\"0\"",
                ).ok()
            edited["defaultPriority"].asInt() shouldBe 5
            edited["linenChangeEveryNights"].asInt() shouldBe 1
            housekeeping.storedCredits(id) shouldBe mapOf(low to 100, high to 1)
            jdbc
                .sql(
                    "SELECT default_priority, linen_change_every_nights FROM housekeeping.cleaning_type WHERE id = :id",
                ).param("id", id)
                .query { rs, _ -> rs.getInt(1) to rs.getInt(2) }
                .single() shouldBe (5 to 1)
        }

    @Test
    fun `exactly 200 credits are accepted and stored, on POST and on PUT`(): Unit =
        with(iam) {
            val roomTypes = (1..MAX_CREDITS).map { rooms.createRoomType() }
            try {
                val created =
                    call(
                        POST,
                        CLEANING_TYPES,
                        asAdmin(),
                        housekeeping.cleaningTypeBody(credits = roomTypes.map { housekeeping.credit(it, 2) }),
                    ).expectStatus(201)!!
                created["credits"].values().size shouldBe MAX_CREDITS
                val id = UUID.fromString(created["id"].asString())
                housekeeping.storedCredits(id) shouldBe roomTypes.associateWith { 2 }

                // A edição com os mesmos 200 tipos de UH e créditos novos também passa.
                call(
                    PUT,
                    "$CLEANING_TYPES/$id",
                    asAdmin(),
                    housekeeping.cleaningTypeBody(
                        code = created["code"].asString(),
                        credits = roomTypes.map { housekeeping.credit(it, 100) },
                    ),
                    ifMatch = "\"0\"",
                ).ok()["credits"].values().size shouldBe MAX_CREDITS
                housekeeping.storedCredits(id) shouldBe roomTypes.associateWith { 100 }
            } finally {
                // O banco é compartilhado: os 200 tipos de UH não ficam ativos para os outros testes.
                roomTypes.forEach { call(POST, "/api/v1/room-types/$it/deactivate", asAdmin()).ok() }
            }
        }

    @Test
    fun `200 credits with unknown room types pass the size limit and stop on the unknown reference`(): Unit =
        with(iam) {
            val code = housekeeping.uniqueCode("SZ")
            val body =
                housekeeping.cleaningTypeBody(
                    code = code,
                    credits = (1..MAX_CREDITS).map { housekeeping.credit(UUID.randomUUID(), 1) },
                )
            call(POST, CLEANING_TYPES, asAdmin(), body).problem(400, "CLEANING_TYPE_UNKNOWN_ROOM_TYPE")
            housekeeping.countByCode(code) shouldBe 0
        }

    /**
     * Comportamento observado (lacuna 2 da revisão): o item nulo é forma do corpo, recusada pela validação do
     * controller (`@Valid`), que roda antes do caso de uso e, portanto, antes do `authz.require`. Quem não mantém
     * recebe o mesmo `400 VALIDATION_FAILED` de quem mantém, e nada é gravado. A forma do corpo não revela estado
     * nenhum do servidor, então a ordem é aceitável; o teste a fixa para que uma mudança dela seja vista.
     */
    @Test
    fun `a null credit item from a user without manage is refused by the body shape, 400 before 403`(): Unit =
        with(iam) {
            val reader = asTestUser(createUserWith(TestGrant(definition(HousekeepingPermissions.CONFIG_READ), ALL)))
            val nobody = asTestUser(createUser())
            val existing = housekeeping.createCleaningType()
            val existingCode = call(GET, "$CLEANING_TYPES/$existing", asAdmin()).ok()["code"].asString()
            val code = housekeeping.uniqueCode("NM")

            listOf(reader, nobody).forEachIndexed { index, user ->
                withClue("usuário $index") {
                    val created =
                        call(
                            POST,
                            CLEANING_TYPES,
                            user,
                            housekeeping.cleaningTypeBody(code = code) + ("credits" to listOf(null)),
                        ).problem(400, "VALIDATION_FAILED")
                    created["errors"].values().map { it["field"].asString() } shouldContain "credits[0]"
                    call(
                        PUT,
                        "$CLEANING_TYPES/$existing",
                        user,
                        housekeeping.cleaningTypeBody(code = existingCode) + ("credits" to listOf(null)),
                        ifMatch = "\"0\"",
                    ).problem(400, "VALIDATION_FAILED")
                    // Com o corpo bem formado, a mesma pessoa recebe 403.
                    call(POST, CLEANING_TYPES, user, housekeeping.cleaningTypeBody(code = code))
                        .problem(403, "PERMISSION_DENIED")
                }
            }
            housekeeping.countByCode(code) shouldBe 0
            housekeeping.etagOf("$CLEANING_TYPES/$existing") shouldBe "\"0\""
        }

    private companion object {
        const val MAX_CREDITS = 200
    }
}
