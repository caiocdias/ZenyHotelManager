package com.zeny.hotel.reservations.application

import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.hotel.EarlyLateTerms
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.printing.DocumentFormat
import com.zeny.hotel.printing.DocumentRequest
import com.zeny.hotel.printing.DocumentTemplate
import com.zeny.hotel.printing.ModuleDocumentTemplates
import com.zeny.hotel.printing.PrintingApi
import com.zeny.hotel.printing.RenderedDocument
import com.zeny.hotel.rates.RatesApi
import com.zeny.hotel.reservations.CancellationTerms
import com.zeny.hotel.reservations.GuaranteeTerms
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsErrorCodes
import com.zeny.hotel.reservations.ReservationsPermissions
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.NotFoundException
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A trilha do `print` da confirmação (D-E11-14): ação e entidade, com retrato só de ids. */
private const val CONFIRMATION_PRINT = "reservations.confirmation.print"
private const val CONFIRMATION_ENTITY = "reservations.confirmation"

/** Os templates de documento do `reservations` (D-E11-2): a confirmação de reserva. */
object ReservationsDocumentTemplates : ModuleDocumentTemplates {
    override val module = "reservations"

    const val CONFIRMATION = "reservations.confirmation"

    override val templates =
        listOf(
            DocumentTemplate(
                code = CONFIRMATION,
                title = "Confirmação de reserva",
                xhtmlTemplate = "templates/reservations/confirmation.xhtml",
                textTemplate = "templates/reservations/confirmation.txt",
            ),
        )
}

/** Registra os templates do `reservations` no catálogo do `printing`. */
@Configuration(proxyBeanMethods = false)
class ReservationsDocumentsConfiguration {
    @Bean
    fun reservationsDocumentTemplates(): ModuleDocumentTemplates = ReservationsDocumentTemplates
}

/** A confirmação em PDF com o código da reserva, para o nome do arquivo (`confirmacao-<código>.pdf`). */
class ReservationConfirmationPdf(
    val reservationCode: String,
    val document: RenderedDocument,
)

/**
 * A confirmação da reserva (RF-RES-12, M5 E11; D-E11-13): o código, a situação, o titular (o nome social, se houver),
 * o tipo de UH, o plano, as datas, as noites, as diárias e o total, os termos de cancelamento e de garantia **copiados
 * na reserva** (RN-15) e, da política do hotel, os horários, a janela de arrumação, o early e o late com o preço e os
 * dois textos (RN-11). Exige `reservations.reservations.read` antes de tudo (D-E11-10). O [pdf] é leitura; o [print] é
 * o caso de uso auditado, só com ids.
 */
@Service
@Suppress("LongParameterList") // as portas que o documento lê
class ReservationConfirmations(
    private val reservations: ReservationsApi,
    private val rooms: RoomsApi,
    private val rates: RatesApi,
    private val guests: GuestsApi,
    private val hotel: HotelApi,
    private val printing: PrintingApi,
    private val authorization: Authorization,
    private val audit: AuditContext,
) {
    @Transactional(readOnly = true)
    fun pdf(reservationId: ReservationId): ReservationConfirmationPdf {
        val reservation = readable(reservationId)
        return ReservationConfirmationPdf(
            reservation.code.toString(),
            printing.render(request(reservation), DocumentFormat.PDF),
        )
    }

    @Audited(CONFIRMATION_PRINT)
    @Transactional
    fun print(
        reservationId: ReservationId,
        printerId: UUID,
    ): UUID {
        val job = printing.print(request(readable(reservationId)), printerId)
        audit.created(CONFIRMATION_ENTITY, job) {
            mapOf(
                "reservationId" to reservationId.toString(),
                "printerId" to printerId.toString(),
                "printJobId" to job.toString(),
            )
        }
        return job
    }

    private fun readable(id: ReservationId): ReservationSummary {
        authorization.require(ReservationsPermissions.RESERVATIONS_READ)
        val reservation =
            reservations.reservation(id)
                ?: throw NotFoundException(ReservationsErrorCodes.RESERVATION_NOT_FOUND, "Reserva não encontrada.")
        if (reservation.status in WITHOUT_CONFIRMATION) {
            throw ConflictException(
                ReservationsErrorCodes.CONFIRMATION_UNAVAILABLE,
                "A reserva cancelada ou no-show não tem confirmação.",
            )
        }
        return reservation
    }

    private fun request(reservation: ReservationSummary): DocumentRequest =
        DocumentRequest(ReservationsDocumentTemplates.CONFIRMATION, model(reservation))

    private fun model(reservation: ReservationSummary): Map<String, Any?> {
        val policy = hotel.policy()
        return mapOf(
            "code" to reservation.code.toString(),
            "status" to STATUS_LABELS.getValue(reservation.status),
            "holder" to holderName(reservation),
            "roomType" to rooms.roomType(reservation.roomTypeId)?.name,
            "ratePlan" to rates.ratePlan(reservation.ratePlanId)?.name,
            "arrival" to reservation.arrival,
            "departure" to reservation.departure,
            "nights" to ChronoUnit.DAYS.between(reservation.arrival, reservation.departure),
            "adults" to reservation.adults,
            "childAges" to reservation.childAges.joinToString(", "),
            "nightlyRates" to reservation.nightlyRates.map { mapOf("date" to it.date, "amount" to it.amount) },
            "total" to reservation.total,
            "cancellation" to cancellationModel(reservation.cancellationTerms),
            "guarantee" to guaranteeModel(reservation.guaranteeTerms),
            "checkInTime" to policy.checkInTime,
            "checkOutTime" to policy.checkOutTime,
            "cleaningMinutes" to policy.cleaningWindow.toMinutes(),
            "earlyCheckIn" to policy.earlyCheckIn?.let(::earlyLateModel),
            "lateCheckOut" to policy.lateCheckOut?.let(::earlyLateModel),
            "stayPolicyText" to policy.stayPolicyText,
            "additionalPolicyText" to policy.additionalPolicyText,
        )
    }

    private fun holderName(reservation: ReservationSummary): String? {
        val holder = reservations.booking(reservation.bookingId)?.holderGuestId ?: return null
        // O perfil corrente: a identificação resolve o mesclado para o sobrevivente (o id dela).
        val current = guests.identification(holder)?.id ?: holder
        return guests.guest(current)?.let { it.socialName ?: it.fullName }
    }

    private fun cancellationModel(terms: CancellationTerms): Map<String, Any?> =
        mapOf(
            "name" to terms.name,
            "deadlineHours" to terms.deadlineHours,
            "penaltyType" to terms.penaltyType.name,
            "penaltyPercent" to terms.penaltyPercent,
            "penaltyNights" to terms.penaltyNights,
            "penaltyAmount" to terms.penaltyAmount,
        )

    private fun guaranteeModel(terms: GuaranteeTerms): Map<String, Any?> =
        mapOf(
            "name" to terms.name,
            "type" to terms.type.name,
            "depositPercent" to terms.depositPercent,
            "dueDaysBefore" to terms.dueDaysBefore,
        )

    private fun earlyLateModel(terms: EarlyLateTerms): Map<String, Any?> =
        mapOf("limitTime" to terms.limitTime, "fee" to terms.fee)

    private companion object {
        val WITHOUT_CONFIRMATION = setOf(ReservationStatus.CANCELLED, ReservationStatus.NO_SHOW)
        val STATUS_LABELS =
            mapOf(
                ReservationStatus.PROVISIONAL to "Pré-reserva",
                ReservationStatus.CONFIRMED to "Confirmada",
                ReservationStatus.IN_HOUSE to "Hospedada",
                ReservationStatus.CHECKED_OUT to "Encerrada",
                ReservationStatus.CANCELLED to "Cancelada",
                ReservationStatus.NO_SHOW to "No-show",
            )
    }
}
