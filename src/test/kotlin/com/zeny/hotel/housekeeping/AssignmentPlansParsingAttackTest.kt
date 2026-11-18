package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.AssignmentPlanTestSupport.Companion.PLANS
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.PATCH
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * Ataques do testador ao plano do dia (M6 E03, `Riscos:` de parsing e da janela): a publicação respeita a mesma janela
 * de datas da sugestão e do ajuste (D2-E03), a data malformada ou fora do intervalo gravável nunca dá `500`, e os
 * corpos com ids malformados, posição negativa ou tipos errados dão `400`, sem gravar nada no plano.
 *
 * Banco compartilhado: o plano usado é o de +7 dias, só com `If-Match` do plano existente e afirmações sobre o `ETag`
 * dele (nenhum desses pedidos pode mudá-lo).
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class AssignmentPlansParsingAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired staffApi: StaffApi,
) {
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)
    private val plans = AssignmentPlanTestSupport(iam, jdbc, staffApi)

    @Test
    fun `publishing a date before the operational one or after 7 days ahead is 422 DATE_INVALID`(): Unit =
        with(iam) {
            val today = hotel.currentBusinessDate()
            listOf(today.minusDays(1), today.plusDays(8), today.plusYears(1000)).forEach { date ->
                withClue(date) {
                    plans.publish(date, "\"0\"").problem(422, "ASSIGNMENT_PLAN_DATE_INVALID")
                }
            }
        }

    @Test
    fun `publishing a malformed date is 400, never 500`(): Unit =
        with(iam) {
            listOf("2026-13-01", "amanha", "2026-1-3", "99999999-01-01", "2026-02-30").forEach { date ->
                withClue(date) {
                    call(POST, "$PLANS/$date/publish", asAdmin(), null, "\"0\"").status shouldBe 400
                }
            }
        }

    @Test
    fun `a date outside the storable range is 404 on GET and 422 on writes, never 500`(): Unit =
        with(iam) {
            listOf("+6000000-01-01", "-5000-01-01").forEach { date ->
                withClue(date) {
                    call(GET, "$PLANS/$date", asAdmin()).problem(404, "ASSIGNMENT_PLAN_NOT_FOUND")
                    call(POST, "$PLANS/$date/suggest", asAdmin(), mapOf("employeeIds" to null))
                        .problem(422, "ASSIGNMENT_PLAN_DATE_INVALID")
                    call(PATCH, "$PLANS/$date", asAdmin(), mapOf("remove" to listOf(UUID.randomUUID())), "\"0\"")
                        .problem(422, "ASSIGNMENT_PLAN_DATE_INVALID")
                    call(POST, "$PLANS/$date/publish", asAdmin(), null, "\"0\"")
                        .problem(422, "ASSIGNMENT_PLAN_DATE_INVALID")
                }
            }
        }

    @Test
    fun `malformed ids, a negative position and wrong types in the bodies are 400, and the plan does not change`(): Unit =
        with(iam) {
            val date = hotel.currentBusinessDate().plusDays(7)
            val attendant = plans.attendant(date)
            val (_, etag) = plans.suggested(date, listOf(attendant))
            val task = plans.insertTask(date, area = housekeeping.rooms.createArea())
            listOf(
                mapOf("remove" to listOf("nao-e-uuid")),
                mapOf("items" to listOf(mapOf("taskId" to "nao-e-uuid", "employeeId" to attendant))),
                mapOf("items" to listOf(mapOf("taskId" to task, "employeeId" to "nao-e-uuid"))),
                mapOf("items" to listOf(mapOf("taskId" to task, "employeeId" to attendant, "position" to -1))),
                mapOf("items" to listOf(mapOf("taskId" to task, "employeeId" to attendant, "position" to "um"))),
                mapOf("items" to "nao-e-lista"),
                mapOf("remove" to task),
                listOf(task),
            ).forEach { body ->
                withClue(body) { call(PATCH, "$PLANS/$date", asAdmin(), body, etag).status shouldBe 400 }
            }
            listOf(
                mapOf("employeeIds" to listOf("nao-e-uuid")),
                mapOf("employeeIds" to attendant),
                listOf(attendant),
            ).forEach { body ->
                withClue(body) { call(POST, "$PLANS/$date/suggest", asAdmin(), body, etag).status shouldBe 400 }
            }
            plans.etagOrNull(date) shouldBe etag
        }
}
