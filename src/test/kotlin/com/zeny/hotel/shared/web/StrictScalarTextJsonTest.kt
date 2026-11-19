package com.zeny.hotel.shared.web

import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistsTestSupport
import com.zeny.hotel.housekeeping.HousekeepingTestSupport
import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.CLEANING_TYPES
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.PUT
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.http.MockHttpInputMessage
import tools.jackson.databind.DatabindException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

/**
 * Texto que não é o valor no mapeador único (M6 E20, rodada de correção final; ARCHITECTURE §7, "Datas e valores"):
 * num [UUID] só vale a forma canônica de 36 caracteres (sem espaço nas pontas e sem a forma base64, que o Jackson
 * aceitaria), e em `Int`, `Long`, `Short` e `Boolean`, anuláveis ou não, o texto vazio ou em branco é recusado. Tudo
 * com `INVALID_FORMAT` no campo e o caminho completo, em vez de virar `null` em silêncio.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class StrictScalarTextJsonTest(
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
) {
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)
    private val checklists = ChecklistsTestSupport(iam)

    @Test
    fun `a UUID accepts only the canonical text, on the field, nested and in a list`() {
        val id = UUID.randomUUID()
        val bodies =
            listOf(" ", " $id ", "$id ", base64(id), id.toString().replace("-", "")).flatMap { value ->
                listOf(
                    """{"id":"$value"}""" to "id",
                    """{"slot":{"roomId":"$value"}}""" to "slot.roomId",
                    """{"ids":["$id","$value"]}""" to "ids[1]",
                )
            }

        bodies.associate { (json, _) -> json to outcome<Ids>(json) } shouldBe
            bodies.associate { (json, field) -> json to refused(field) }
        outcome<Ids>("""{"id":"$id","slot":{"roomId":"${id.toString().uppercase()}"},"ids":[null]}""") shouldBe
            "lido: ${Ids(id = id, slot = Slot(roomId = id), ids = listOf(null))}"
    }

    @Test
    fun `an empty or blank text in a Short or a Boolean, nullable or not, is refused on the field`() {
        val bodies =
            listOf("", " ").flatMap { value ->
                listOf(
                    """{"small":"$value"}""" to "small",
                    """{"flag":"$value"}""" to "flag",
                    """{"on":"$value"}""" to "on",
                    """{"count":"$value"}""" to "count",
                )
            }

        bodies.associate { (json, _) -> json to outcome<Flags>(json) } shouldBe
            bodies.associate { (json, field) -> json to refused(field) }
        outcome<Flags>("""{"small":3,"flag":true,"on":true,"count":"7"}""") shouldBe
            "lido: ${Flags(small = 3, flag = true, on = true, count = 7)}"
    }

    @Test
    fun `a blank checklistTemplateId on a cleaning type update is refused and the link stays`() {
        val template =
            checklists
                .create(checklists.templateBody(context = ChecklistContext.HOUSEKEEPING))
                .also { checklists.publish(it) }
        val id = housekeeping.createCleaningType(housekeeping.cleaningTypeBody(checklistTemplateId = template))
        val body = housekeeping.cleaningTypeBody(code = housekeeping.uniqueCode()) + ("checklistTemplateId" to " ")

        val problem =
            with(iam) {
                call(PUT, "$CLEANING_TYPES/$id", asAdmin(), body, ifMatch = housekeeping.etagOf("$CLEANING_TYPES/$id"))
                    .problem(400, "VALIDATION_FAILED")
            }

        problem["errors"].values().map { it["field"].asString() to it["code"].asString() } shouldBe
            listOf("checklistTemplateId" to "INVALID_FORMAT")
        with(iam) { call(GET, "$CLEANING_TYPES/$id", asAdmin()).ok()["checklistTemplateId"].asString() } shouldBe
            template.toString()
    }

    data class Ids(
        val id: UUID? = null,
        val slot: Slot? = null,
        val ids: List<UUID?>? = null,
    )

    data class Slot(
        val roomId: UUID? = null,
    )

    data class Flags(
        val small: Short? = null,
        val flag: Boolean? = null,
        val on: Boolean = false,
        val count: Int = 0,
    )

    private inline fun <reified T> outcome(json: String): String =
        try {
            "lido: ${jsonMapper.readValue<T>(json)}"
        } catch (error: DatabindException) {
            val violation =
                FieldViolations.of(
                    HttpMessageNotReadableException("corpo ilegível", error, MockHttpInputMessage(json.toByteArray())),
                )
            "recusado: $violation"
        }

    private fun refused(field: String) = "recusado: ${FieldViolations.invalidFormat(field)}"

    private fun base64(id: UUID): String =
        Base64.getEncoder().encodeToString(
            ByteBuffer
                .allocate(UUID_BYTES)
                .putLong(id.mostSignificantBits)
                .putLong(id.leastSignificantBits)
                .array(),
        )

    private companion object {
        const val UUID_BYTES = 16
    }
}
