package com.zeny.hotel.reporting

import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.ReservationsTestSupport
import org.springframework.http.HttpMethod.POST
import java.time.LocalDate
import java.util.UUID

/**
 * Apoio dos testes dos relatórios sobre reservas: planos de cada categoria com preços e reservas criadas pela API, a
 * partir da data operacional [from] (o banco é compartilhado e a data operacional avança).
 */
class ReportingTestSupport(
    private val iam: IamTestSupport,
    val reservations: ReservationsTestSupport,
) {
    /** Um plano da [category] com o tipo, políticas novas sem garantia e preços de [from] a [PRICED_DAYS] dias. */
    fun plan(
        type: UUID,
        category: String,
        from: LocalDate,
    ): UUID {
        val rates = reservations.rates
        val cancellation = rates.createCancellationPolicy(rates.cancellationBody())
        val guarantee =
            rates.createGuaranteePolicy(rates.guaranteeBody(type = "NONE", depositPercent = null, dueDaysBefore = null))
        val body = rates.planBody(listOf(type), cancellation, guarantee, category = category)
        val id =
            with(iam) {
                UUID.fromString(
                    call(POST, "/api/v1/rate-plans", reservations.admin, body).expectStatus(201)!!["id"].asString(),
                )
            }
        reservations.setPrices(id, listOf(type), from, from.plusDays(PRICED_DAYS))
        return id
    }

    /** Cria uma reserva do [holder] e devolve o id dela. */
    fun book(
        holder: UUID,
        body: Map<String, Any?>,
    ): UUID =
        UUID.fromString(
            reservations.created(reservations.booking(holder, body))["reservations"][0]["id"].asString(),
        )

    private companion object {
        const val PRICED_DAYS = 14L
    }
}
