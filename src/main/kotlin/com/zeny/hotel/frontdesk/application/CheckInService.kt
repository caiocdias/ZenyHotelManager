package com.zeny.hotel.frontdesk.application

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.billing.CardPreAuthorizationSummary
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.FolioStatus
import com.zeny.hotel.billing.NewCardPreAuthorization
import com.zeny.hotel.billing.NewPosting
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.PostingSummary
import com.zeny.hotel.billing.TransactionCodeKey
import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.FilesApi
import com.zeny.hotel.frontdesk.EarlyLateOffer
import com.zeny.hotel.frontdesk.FrontdeskErrorCodes
import com.zeny.hotel.frontdesk.FrontdeskPermissions
import com.zeny.hotel.frontdesk.domain.EarlyLateFeeRules
import com.zeny.hotel.frontdesk.domain.EarlyLateQuotes
import com.zeny.hotel.frontdesk.domain.GuestDataRules
import com.zeny.hotel.frontdesk.domain.MinorAttachmentRules
import com.zeny.hotel.frontdesk.domain.RoomNotReadyException
import com.zeny.hotel.frontdesk.domain.StayRules
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestIdentification
import com.zeny.hotel.guests.GuestStatus
import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.guests.RestrictionAlert
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.reservations.CheckIn
import com.zeny.hotel.reservations.CheckInGuest
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsPermissions
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatus
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.SensitiveAction
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Os códigos das ações e entidades da trilha do `frontdesk`. */
object FrontdeskAudit {
    const val CHECK_IN = "frontdesk.stay.check-in"
    const val UNDO_CHECK_IN = "frontdesk.stay.undo-check-in"
    const val WALK_IN = "frontdesk.stay.walk-in"
    const val CHECK_OUT = "frontdesk.stay.check-out"
    const val ROOM_MOVE = "frontdesk.stay.room-move"

    /** A hospedagem (id = a reserva): o que a recepção fez, sem dado pessoal. */
    const val STAY = "frontdesk.stay"
}

/**
 * O check-in pedido à recepção: a UH opcional (aloca ou realoca antes), a lista **completa** dos hóspedes da estadia
 * com os dados de check-in (como a `ReservationsApi` os recebe), o número de hóspedes na UH (nulo = o tamanho da
 * lista), a caução opcional e o valor do early check-in cotado (opcional, RN-11).
 */
data class CheckInCommand(
    val roomId: RoomId?,
    val guests: List<CheckInGuest>,
    val guestCount: Int?,
    val preAuthorization: NewCardPreAuthorization?,
    val earlyCheckInFee: Money? = null,
)

/**
 * O resultado do check-in: a reserva hospedada, a UH ocupada, a caução registrada, o early check-in lançado (nulo sem
 * `earlyCheckInFee`) e os alertas de restrição.
 */
data class CheckInOutcome(
    val reservation: ReservationSummary,
    val roomNumber: String,
    val room: RoomStatus,
    val preAuthorization: CardPreAuthorizationSummary?,
    val restrictionAlerts: List<RestrictionAlert>,
    val earlyCheckIn: PostingSummary? = null,
)

/** O resultado do desfazer: a reserva de novo confirmada e a UH vaga. */
data class UndoCheckInOutcome(
    val reservation: ReservationSummary,
    val roomNumber: String,
    val room: RoomStatus,
)

/** A cotação de early check-in e late check-out de uma reserva pela política vigente do hotel (RN-11). */
data class EarlyLateQuote(
    val reservation: ReservationSummary,
    val timezone: ZoneId,
    val checkInTime: LocalTime,
    val checkOutTime: LocalTime,
    val earlyCheckIn: EarlyLateOffer,
    val lateCheckOut: EarlyLateOffer,
)

/**
 * O check-in orquestrado da recepção (M5 E02; RF-REC-01, RF-REC-08, RN-05, RN-11, RN-24, ARCHITECTURE §6.1, H11 a H14)
 * e o desfazer check-in (sensível, ARCHITECTURE §6.3), **numa transação só**: qualquer recusa desfaz tudo (a caução, a
 * transição da reserva, a ocupação da UH, as entradas da trilha e os eventos do outbox).
 *
 * **Ordem do check-in** (e das travas): a permissão; a reserva lida sem trava (inexistente `404`; só confirmada e já
 * chegada, conferida antes dos outros passos); a conta da reserva aberta; a identificação dos hóspedes **sem número**
 * (`GuestsApi.identification`, H11: `422 GUEST_DATA_INCOMPLETE`); o anexo da comprovação do menor (`FilesApi.find`); os
 * alertas da lista de restrição (devolvidos, não bloqueiam); a UH pedida alocada, se mudou
 * (`ReservationsApi.assignRoom`,
 * com `reservations.assignments.manage`; trava a reserva e a UH); a UH alocada (`409 ROOM_NOT_ASSIGNED`) e pronta
 * (`RoomsApi.isReadyForCheckIn`, `409 ROOM_NOT_READY`); a transição (`ReservationsApi.checkIn`, que **trava a reserva**
 * e confere a RN-24); a caução (`BillingApi.registerCardPreAuthorization`, que **trava a conta**); a ocupação
 * (`RoomsApi.markOccupiedIfReady`, que **trava a UH** e confere a RN-05 de novo com ela travada: dois check-ins
 * simultâneos na mesma UH nunca passam os dois, B1 da revisão humana). A reserva
 * é travada antes da conta e da UH, como no cancelamento (reserva → conta): sem deadlock com ele. Nenhum
 * pacote é lançado aqui (H14). O early check-in, se o pedido traz o valor cotado, é conferido com a política vigente
 * antes de qualquer escrita ([CheckInSteps.acceptedEarlyFee]) e lançado em [CheckInSteps.complete]. Os passos ficam em
 * [CheckInSteps], reusados pelo walk-in (E03, [FrontdeskWalkInService]).
 */
@Service
class FrontdeskStayService(
    private val reservations: ReservationsApi,
    private val rooms: RoomsApi,
    private val hotel: HotelApi,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val steps: CheckInSteps,
) {
    @Audited(FrontdeskAudit.CHECK_IN)
    @Transactional
    fun checkIn(
        id: ReservationId,
        command: CheckInCommand,
    ): CheckInOutcome {
        authorization.require(FrontdeskPermissions.STAYS_CHECK_IN)
        val guestCount = StayRules.guestCount(command.guestCount, command.guests.size)
        val reservation = steps.reservation(id)
        val businessDate = hotel.currentBusinessDate()
        StayRules.requireCheckIn(reservation, businessDate)
        steps.requireOpenFolio(reservation.folioId)
        val prepared = steps.prepareGuests(command.guests, businessDate)
        val earlyFee = steps.acceptedEarlyFee(reservation, command.earlyCheckInFee)
        return steps.complete(reservation, command, guestCount, prepared, earlyFee)
    }

    /**
     * Desfaz o check-in (SPEC §8.4 `InHouse → Confirmed`; ação sensível `frontdesk.stay.undo-check-in`): exige
     * `frontdesk.stays.checkin` e, sem `frontdesk.stays.undocheckin`, a aprovação de superior
     * (`403 APPROVAL_REQUIRED`),
     * conferida **depois** da situação e da data (só uma hospedada, `409`; só na mesma data operacional do check-in,
     * `422 UNDO_CHECK_IN_EXPIRED`). A reserva volta a confirmada com a UH ainda alocada, e a UH volta a Vaga **sem
     * sujar** (origem `CHECK_IN`; decisão 4 da E01). A caução registrada e o early lançado ficam: são desfeitos à
     * parte,
     * pelo `billing` (liberação da caução, estorno do lançamento).
     */
    @Audited(FrontdeskAudit.UNDO_CHECK_IN)
    @Transactional
    fun undoCheckIn(
        id: ReservationId,
        approvalToken: String?,
    ): UndoCheckInOutcome {
        authorization.require(FrontdeskPermissions.STAYS_CHECK_IN)
        val reservation = steps.reservation(id)
        StayRules.requireUndo(reservation, hotel.currentBusinessDate())
        authorization.requireOrApproval(
            SensitiveAction(
                FrontdeskAudit.UNDO_CHECK_IN,
                FrontdeskPermissions.STAYS_UNDO_CHECK_IN,
                "reservations.reservation:${id.value}",
            ),
            approvalToken,
        )
        val undone = reservations.undoCheckIn(id)
        val roomId = checkNotNull(undone.roomAssignment) { "Check-in desfeito sem UH alocada" }.roomId
        val room = rooms.markVacant(roomId, RoomStatusSource.CHECK_IN)
        audit.record(
            FrontdeskAudit.STAY,
            id.value,
            mapOf("status" to reservation.status.name, "roomId" to roomId.value),
            mapOf("status" to undone.status.name, "roomId" to roomId.value, "roomVacated" to true),
        )
        return UndoCheckInOutcome(undone, steps.roomNumber(roomId), room)
    }

    /**
     * A cotação de early check-in e late check-out da reserva (RN-11, H13): pela política **vigente** do hotel
     * (`HotelApi.policy()`), no fuso dele, com o horário limite, o valor e a janela em instantes; não oferecido, sem
     * horário nem valor. Exige `reservations.reservations.read`. A cobrança (que confere de novo o valor da política) é
     * do check-in e do walk-in (early, E05) e do check-out (late, E04).
     */
    @Transactional(readOnly = true)
    fun earlyLateQuote(id: ReservationId): EarlyLateQuote {
        authorization.require(ReservationsPermissions.RESERVATIONS_READ)
        val reservation = steps.reservation(id)
        val policy = hotel.policy()
        val zone = hotel.timezone()
        return EarlyLateQuote(
            reservation = reservation,
            timezone = zone,
            checkInTime = policy.checkInTime,
            checkOutTime = policy.checkOutTime,
            earlyCheckIn = EarlyLateQuotes.early(policy, reservation.arrival, zone),
            lateCheckOut = EarlyLateQuotes.late(policy, reservation.departure, zone),
        )
    }
}

/** A identificação dos hóspedes do pedido (pelo índice da lista) e os alertas da lista de restrição. */
data class PreparedGuests(
    val identifications: Map<Int, GuestIdentification>,
    val restrictionAlerts: List<RestrictionAlert>,
)

/**
 * Os passos do check-in reusados pelo check-in de uma reserva (E02) e pelo walk-in (E03), **sempre na transação de
 * escrita de quem chama** (o caso de uso auditado; fora dela, `IllegalStateException`). As conferências sem escrita
 * ([reservation], [requireOpenFolio], [prepareGuests]) e o fim do check-in ([complete]: a UH alocada e pronta, a
 * transição que trava a reserva, a caução que trava a conta e a ocupação que trava a UH, nessa ordem).
 */
@Component
@Suppress("LongParameterList", "TooManyFunctions") // as APIs públicas que o check-in orquestra e um passo por função
class CheckInSteps(
    private val reservations: ReservationsApi,
    private val rooms: RoomsApi,
    private val guests: GuestsApi,
    private val billing: BillingApi,
    private val files: FilesApi,
    private val hotel: HotelApi,
    private val authorization: Authorization,
    private val audit: AuditContext,
) {
    /** A reserva lida sem trava; inexistente `404 RESERVATION_NOT_FOUND`. */
    fun reservation(id: ReservationId): ReservationSummary =
        reservations.reservation(id)
            ?: throw NotFoundException(FrontdeskErrorCodes.RESERVATION_NOT_FOUND, "Reserva não encontrada.")

    /**
     * A identificação de cada hóspede do pedido sem número de documento (H11: `400 UNKNOWN_GUEST` com o campo,
     * `422 GUEST_ANONYMIZED`, `422 GUEST_DATA_INCOMPLETE` com todos os incompletos), o anexo da comprovação de cada
     * menor que o tem (`FilesApi.find`) e os alertas da lista de restrição dos hóspedes e de [alsoAlert] (o titular do
     * walk-in), que não bloqueiam.
     */
    fun prepareGuests(
        requested: List<CheckInGuest>,
        businessDate: LocalDate,
        alsoAlert: Collection<GuestId> = emptyList(),
    ): PreparedGuests {
        val identifications = identify(requested, businessDate)
        requireMinorAttachments(requested, identifications)
        val alerts =
            (identifications.values.map { it.id } + alsoAlert)
                .distinct()
                .mapNotNull { guests.restrictionAlert(it) }
                .distinctBy { it.guestId }
        return PreparedGuests(identifications, alerts)
    }

    /**
     * O early check-in aceito (RN-11, H13): o [quotedFee] conferido com a política vigente para a chegada da
     * [reservation] no fuso do hotel (`EarlyLateFeeRules`: nulo sem valor; `422 EARLY_LATE_NOT_OFFERED`;
     * `409 EARLY_LATE_FEE_CHANGED`). Sem escrita: quem chama o confere antes de qualquer uma e repassa o resultado ao
     * `acceptedEarlyFee` do [complete] (o único valor que ele lança).
     */
    fun acceptedEarlyFee(
        reservation: ReservationSummary,
        quotedFee: Money?,
    ): Money? = acceptedEarlyFee(reservation.arrival, quotedFee)

    /** Como o anterior, para uma chegada em [arrival] (o walk-in confere antes de criar a reserva). */
    fun acceptedEarlyFee(
        arrival: LocalDate,
        quotedFee: Money?,
    ): Money? =
        EarlyLateFeeRules.acceptedFee(
            EarlyLateQuotes.early(hotel.policy(), arrival, hotel.timezone()),
            quotedFee,
            EarlyLateFeeRules.EARLY_LABEL,
        )

    /**
     * O fim do check-in da [reservation] (confirmada e já chegada, conferida por quem chama): a UH pedida alocada, se
     * mudou (`ReservationsApi.assignRoom`, com `reservations.assignments.manage`; trava a reserva e a UH); a UH alocada
     * (`409 ROOM_NOT_ASSIGNED`) e pronta (`RoomsApi.isReadyForCheckIn`, `409 ROOM_NOT_READY`); a transição
     * (`ReservationsApi.checkIn`, que **trava a reserva** e confere a RN-24); a caução
     * (`BillingApi.registerCardPreAuthorization`, que **trava a conta**); o early check-in já aceito em
     * [acceptedEarlyFee], o devolvido pela conferência (nunca o [CheckInCommand.earlyCheckInFee] sem conferir;
     * `BillingApi.post`, `ROOM_CHARGE`, origem `MANUAL`, também na conta, depois da caução); a ocupação
     * (`RoomsApi.markOccupiedIfReady`, que **trava a UH** e confere a RN-05 de novo com ela travada: dois check-ins
     * simultâneos na mesma UH nunca passam os dois, B1 da revisão humana da E02); e a hospedagem na trilha
     * (`frontdesk.stay`, com [trail] acrescentado ao retrato).
     */
    @Suppress("LongParameterList") // o early aceito vem à parte do pedido: só o conferido é lançado
    fun complete(
        reservation: ReservationSummary,
        command: CheckInCommand,
        guestCount: Int,
        prepared: PreparedGuests,
        acceptedEarlyFee: Money?,
        trail: Map<String, Any?> = emptyMap(),
    ): CheckInOutcome {
        check(
            TransactionSynchronizationManager.isActualTransactionActive() &&
                !TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
        ) { "O check-in só é feito dentro da transação de escrita de quem chama" }
        val id = reservation.id
        val roomId = readyRoom(reservation, command.roomId)
        val checkedIn = reservations.checkIn(id, CheckIn(command.guests))
        val preAuthorization = command.preAuthorization?.let { registerPreAuthorization(checkedIn.folioId, it) }
        val earlyCheckIn = acceptedEarlyFee?.let { postEarlyCheckIn(checkedIn.folioId, it) }
        val room = occupy(roomId, guestCount)
        audit.created(FrontdeskAudit.STAY, id.value) {
            mapOf(
                "reservationId" to id.value,
                "roomId" to roomId.value,
                "guestCount" to guestCount,
                "guestIds" to checkedIn.guests.map { it.guestId.value },
                "preAuthorizationId" to preAuthorization?.id?.value,
                "earlyCheckInPostingId" to earlyCheckIn?.id?.value,
                "restrictionAlerts" to prepared.restrictionAlerts.size,
                "businessDate" to checkedIn.checkIn?.businessDate,
            ) + trail
        }
        return CheckInOutcome(
            checkedIn,
            roomNumber(roomId),
            room,
            preAuthorization,
            prepared.restrictionAlerts,
            earlyCheckIn,
        )
    }

    /** O early check-in na conta da reserva: `ROOM_CHARGE`, `Early check-in`, origem `MANUAL` (RN-03, RN-11). */
    private fun postEarlyCheckIn(
        folioId: FolioId,
        fee: Money,
    ): PostingSummary =
        billing.post(
            folioId,
            NewPosting(
                billing.transactionCode(TransactionCodeKey.ROOM_CHARGE).id,
                fee,
                description = EARLY_CHECK_IN,
                source = PostingSource.MANUAL,
            ),
        )

    /**
     * A conta da reserva aberta. Toda reserva nasce com a conta (aberta na mesma transação da criação) e uma conta
     * nunca é apagada (trigger `billing.refuse_delete`), então a ausência é impossível na prática; ainda assim ela é
     * uma recusa de domínio (`404 FOLIO_NOT_FOUND`), nunca um `500`. Encerrada: `409 FOLIO_CLOSED` (o check-in não a
     * reabre).
     */
    fun requireOpenFolio(folioId: FolioId) {
        val folio =
            billing.folio(folioId)
                ?: throw NotFoundException(
                    FrontdeskErrorCodes.FOLIO_NOT_FOUND,
                    "A conta da reserva não foi encontrada.",
                )
        if (folio.status == FolioStatus.CLOSED) {
            throw ConflictException(
                FrontdeskErrorCodes.FOLIO_CLOSED,
                "A conta da reserva está encerrada: reabra-a antes do check-in.",
            )
        }
    }

    fun roomNumber(roomId: RoomId): String = checkNotNull(rooms.room(roomId)) { "UH inexistente" }.number

    /**
     * A UH do check-in: a pedida (alocada ou realocada antes, com `reservations.assignments.manage`, se difere da
     * alocada) ou a alocada (`409 ROOM_NOT_ASSIGNED` sem nenhuma), pronta pela RN-05 (`409 ROOM_NOT_READY`).
     */
    private fun readyRoom(
        reservation: ReservationSummary,
        requested: RoomId?,
    ): RoomId {
        val assigned =
            requested?.takeIf { it != reservation.roomAssignment?.roomId }?.let { roomId ->
                authorization.require(ReservationsPermissions.ASSIGNMENTS_MANAGE)
                reservations.assignRoom(reservation.id, roomId)
            } ?: reservation
        val roomId =
            assigned.roomAssignment?.roomId
                ?: throw ConflictException(FrontdeskErrorCodes.ROOM_NOT_ASSIGNED, "A reserva não tem UH alocada.")
        if (!rooms.isReadyForCheckIn(roomId)) throw RoomNotReadyException(checkNotNull(rooms.status(roomId)))
        return roomId
    }

    /**
     * Ocupa a UH com o número de hóspedes pela `RoomsApi.markOccupiedIfReady`, que confere a RN-05 **com a linha da UH
     * travada** (B1 da revisão humana): dois check-ins simultâneos na mesma UH (um day use e um pernoite alocados nela,
     * que não conflitam nos meios-dias, H5) se enfileiram na trava, e o segundo vê a UH já ocupada e é recusado com
     * `409 ROOM_NOT_READY` (a transição dele é desfeita junto). Qualquer outra mudança confirmada depois da conferência
     * sem trava (UH suja, OOO aberto) também recusa aqui.
     */
    private fun occupy(
        roomId: RoomId,
        guestCount: Int,
    ): RoomStatus {
        val occupation = rooms.markOccupiedIfReady(roomId, guestCount, RoomStatusSource.CHECK_IN)
        if (!occupation.ready) throw RoomNotReadyException(occupation.status)
        return occupation.status
    }

    /**
     * A identificação de cada hóspede do pedido (pelo índice da lista), sem número de documento: inexistente
     * `400 UNKNOWN_GUEST` com o campo; anonimizado `422 GUEST_ANONYMIZED`; sem os dados mínimos (H11) `422
     * GUEST_DATA_INCOMPLETE`, com todos os incompletos de uma vez.
     */
    private fun identify(
        requested: List<CheckInGuest>,
        businessDate: LocalDate,
    ): Map<Int, GuestIdentification> {
        val identifications =
            requested
                .mapIndexed { index, guest ->
                    val identification =
                        guests.identification(guest.guestId) ?: throw unknownGuest("guests[$index].guestId")
                    if (identification.status == GuestStatus.ANONYMIZED) {
                        throw BusinessRuleException(
                            FrontdeskErrorCodes.GUEST_ANONYMIZED,
                            "Um hóspede informado foi anonimizado e não pode ser hospedado.",
                        )
                    }
                    index to identification
                }.toMap()
        GuestDataRules.require(
            requested.mapIndexedNotNull { index, guest ->
                GuestDataRules.incomplete(guest.guestId, identifications.getValue(index), businessDate)
            },
        )
        return identifications
    }

    /**
     * O anexo de cada comprovação de menor que o tem: existente, de uma das finalidades de autorização do `guests` e
     * vinculado ao perfil do menor (o pedido ou, num mesclado, o corrente); senão `422
     * MINOR_AUTHORIZATION_ATTACHMENT_INVALID`. A exigência do anexo por tipo de comprovação é do `reservations`.
     */
    private fun requireMinorAttachments(
        requested: List<CheckInGuest>,
        identifications: Map<Int, GuestIdentification>,
    ) {
        requested.forEachIndexed { index, guest ->
            val attachment = guest.minorAuthorization?.attachmentId ?: return@forEachIndexed
            val owners: Set<GuestId> = setOf(guest.guestId, identifications.getValue(index).id)
            if (!MinorAttachmentRules.valid(files.find(FileId(attachment)), owners)) {
                throw MinorAttachmentRules.invalid(guest.guestId, "guests[$index].minorAuthorization.attachmentId")
            }
        }
    }

    /** A caução na conta da reserva; os campos recusados pelo `billing` voltam com o prefixo `preAuthorization.`. */
    private fun registerPreAuthorization(
        folioId: FolioId,
        preAuthorization: NewCardPreAuthorization,
    ): CardPreAuthorizationSummary =
        try {
            billing.registerCardPreAuthorization(folioId, preAuthorization)
        } catch (refused: InvalidInputException) {
            throw InvalidInputException(
                refused.code,
                refused.message,
                refused.violations.map { it.copy(field = "preAuthorization.${it.field}") },
                refused,
            )
        }

    private fun unknownGuest(field: String) =
        InvalidInputException(
            FrontdeskErrorCodes.UNKNOWN_GUEST,
            "O hóspede informado não existe.",
            listOf(FieldViolation(field, FrontdeskErrorCodes.UNKNOWN_GUEST.value, "O hóspede não existe.")),
        )

    private companion object {
        /** A descrição do lançamento do early check-in (sem dado do hóspede). */
        const val EARLY_CHECK_IN = "Early check-in"
    }
}

@Configuration(proxyBeanMethods = false)
class FrontdeskPermissionsConfiguration {
    @Bean
    fun frontdeskPermissions(): ModulePermissions = FrontdeskPermissions
}
