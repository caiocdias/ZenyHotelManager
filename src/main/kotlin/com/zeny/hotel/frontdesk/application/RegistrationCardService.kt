package com.zeny.hotel.frontdesk.application

import com.zeny.hotel.frontdesk.FrontdeskErrorCodes
import com.zeny.hotel.frontdesk.FrontdeskPermissions
import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.guests.MaskedGuestDocument
import com.zeny.hotel.printing.DocumentFormat
import com.zeny.hotel.printing.DocumentRequest
import com.zeny.hotel.printing.DocumentTemplate
import com.zeny.hotel.printing.ModuleDocumentTemplates
import com.zeny.hotel.printing.PrintingApi
import com.zeny.hotel.printing.RenderedDocument
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.StayGuestSummary
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.Address
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.NotFoundException
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** A trilha do `print` da ficha (D-E11-14): ação e entidade, com retrato só de ids. */
private const val REGISTRATION_CARD_PRINT = "frontdesk.registration-card.print"
private const val REGISTRATION_CARD_ENTITY = "frontdesk.registration-card"

/** Os templates de documento do `frontdesk` (D-E11-2): a ficha de registro. */
object FrontdeskDocumentTemplates : ModuleDocumentTemplates {
    override val module = "frontdesk"

    const val REGISTRATION_CARD = "frontdesk.registration-card"

    override val templates =
        listOf(
            DocumentTemplate(
                code = REGISTRATION_CARD,
                title = "Ficha de registro",
                xhtmlTemplate = "templates/frontdesk/registration-card.xhtml",
                textTemplate = "templates/frontdesk/registration-card.txt",
            ),
        )
}

/** Registra os templates do `frontdesk` no catálogo do `printing`. */
@Configuration(proxyBeanMethods = false)
class FrontdeskDocumentsConfiguration {
    @Bean
    fun frontdeskDocumentTemplates(): ModuleDocumentTemplates = FrontdeskDocumentTemplates
}

/** A ficha em PDF com o código da reserva, para o nome do arquivo (`ficha-<código>.pdf`). */
class RegistrationCardPdf(
    val reservationCode: String,
    val document: RenderedDocument,
)

/**
 * A ficha de registro da hospedagem (RF-REC, M5 E11; D-E11-11): a estadia e, por hóspede, o nome (o social, se
 * houver), a nacionalidade, o nascimento, o endereço, os documentos **mascarados** e os códigos FNRH de motivo e meio
 * de transporte, com a linha de assinatura. Só lê a `GuestsApi.identification` (nunca `fnrhData`): o número inteiro do
 * documento e os dados de RN-19 (raça/cor, deficiência) não chegam ao modelo. Exige `frontdesk.stays.checkin` antes de
 * tudo (D-E11-10). O [pdf] é leitura; o [print] é o caso de uso auditado, só com ids.
 */
@Service
@Suppress("LongParameterList") // as portas que o documento lê
class RegistrationCardService(
    private val reservations: ReservationsApi,
    private val rooms: RoomsApi,
    private val guests: GuestsApi,
    private val printing: PrintingApi,
    private val authorization: Authorization,
    private val audit: AuditContext,
) {
    @Transactional(readOnly = true)
    fun pdf(reservationId: ReservationId): RegistrationCardPdf {
        val reservation = readable(reservationId)
        return RegistrationCardPdf(
            reservation.code.toString(),
            printing.render(request(reservation), DocumentFormat.PDF),
        )
    }

    @Audited(REGISTRATION_CARD_PRINT)
    @Transactional
    fun print(
        reservationId: ReservationId,
        printerId: UUID,
    ): UUID {
        val job = printing.print(request(readable(reservationId)), printerId)
        audit.created(REGISTRATION_CARD_ENTITY, job) {
            mapOf(
                "reservationId" to reservationId.toString(),
                "printerId" to printerId.toString(),
                "printJobId" to job.toString(),
            )
        }
        return job
    }

    private fun readable(id: ReservationId): ReservationSummary {
        authorization.require(FrontdeskPermissions.STAYS_CHECK_IN)
        val reservation =
            reservations.reservation(id)
                ?: throw NotFoundException(FrontdeskErrorCodes.RESERVATION_NOT_FOUND, "Reserva não encontrada.")
        if (reservation.status !in WITH_CARD) {
            throw ConflictException(
                FrontdeskErrorCodes.REGISTRATION_CARD_UNAVAILABLE,
                "A ficha de registro só existe para a reserva confirmada, hospedada ou encerrada.",
            )
        }
        return reservation
    }

    private fun request(reservation: ReservationSummary): DocumentRequest =
        DocumentRequest(FrontdeskDocumentTemplates.REGISTRATION_CARD, model(reservation))

    private fun model(reservation: ReservationSummary): Map<String, Any?> =
        mapOf(
            "code" to reservation.code.toString(),
            "room" to reservation.roomAssignment?.let { rooms.room(it.roomId)?.number },
            "arrival" to reservation.arrival,
            "departure" to reservation.departure,
            "checkedInAt" to reservation.guests.mapNotNull { it.checkedInAt }.minOrNull(),
            "adults" to reservation.adults,
            "children" to reservation.childAges.size,
            "guests" to reservation.guests.map(::guestModel),
        )

    private fun guestModel(stayGuest: StayGuestSummary): Map<String, Any?> {
        // Tudo do mesmo perfil corrente: a identificação resolve o mesclado para o sobrevivente (o id dela).
        val identification = guests.identification(stayGuest.guestId)
        val summary = guests.guest(identification?.id ?: stayGuest.guestId)
        return mapOf(
            "name" to (summary?.let { it.socialName ?: it.fullName } ?: "Hóspede não identificado"),
            "nationality" to identification?.nationality,
            "birthDate" to identification?.birthDate,
            "address" to identification?.address?.let(::addressText),
            "documents" to identification?.documents.orEmpty().map(::documentModel),
            "travelReason" to stayGuest.travelReason,
            "transportMode" to stayGuest.transportMode,
            "minorAuthorized" to (stayGuest.minorAuthorization != null),
        )
    }

    private fun documentModel(document: MaskedGuestDocument): Map<String, Any?> =
        mapOf(
            "type" to document.type.name,
            "country" to document.issuingCountry,
            "number" to document.maskedNumber,
        )

    private fun addressText(address: Address): String =
        listOfNotNull(
            "${address.street}, ${address.number}",
            address.complement,
            address.district,
            "${address.city}/${address.state.name}",
            "CEP ${address.formattedPostalCode()}",
        ).joinToString(" - ")

    private companion object {
        val WITH_CARD = setOf(ReservationStatus.CONFIRMED, ReservationStatus.IN_HOUSE, ReservationStatus.CHECKED_OUT)
    }
}
