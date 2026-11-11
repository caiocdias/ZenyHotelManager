package com.zeny.hotel.fnrh.application

import com.zeny.hotel.fnrh.domain.FnrhFieldError
import com.zeny.hotel.fnrh.domain.FnrhGuest
import com.zeny.hotel.fnrh.domain.FnrhLocalValidator
import com.zeny.hotel.fnrh.domain.FnrhPersonData
import com.zeny.hotel.fnrh.domain.FnrhPersonMapper
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.fnrh.domain.FnrhSubmission
import com.zeny.hotel.fnrh.domain.FnrhSubmissionError
import com.zeny.hotel.fnrh.domain.RequesterCpfPolicy
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.StayGuestSummary
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.shared.FieldViolationCodes
import org.springframework.stereotype.Component

/**
 * O passo do `CHECK_IN` (D-E08-6, 7, 8, 10 e 15): valida todos os hóspedes antes de qualquer chamada, garante a
 * reserva no Serpro e põe cada hóspede nela (pessoa e hóspede), o responsável antes do menor. Toda escrita que cria
 * recurso marca a escrita em curso antes; com a marca, concilia antes de repetir. Os dados do hóspede só vivem em
 * memória durante o envio.
 */
@Component
class FnrhCheckInStep(
    private val sync: FnrhReservationSync,
    private val guests: GuestsApi,
    private val gateway: FnrhGateway,
    private val remoteIds: FnrhRemoteIds,
    private val validator: FnrhLocalValidator,
    private val requesters: FnrhRequesterDirectory,
) : FnrhOperationStep {
    /**
     * Pelo estado atual: com a reserva ainda com check-in, registra os hóspedes; sem ele (desfeito), não valida nem
     * cria nada, só concilia a escrita em curso e grava os ids do que já existe no Serpro (D-E08-8).
     */
    override fun send(
        submission: FnrhSubmission,
        connection: FnrhConnection,
    ): FnrhStepOutcome {
        val reservation = sync.reservationOf(submission)
        if (!hasCheckIn(reservation)) {
            reconcileOnly(submission, reservation, connection)
            return FnrhStepOutcome.Sent
        }
        return registerGuests(submission, reservation, connection)
    }

    /**
     * Valida todos os hóspedes da reserva (com erro, `Invalid` sem nenhuma chamada), garante a reserva no Serpro e
     * põe nela cada hóspede que ainda não tem id remoto: os sem autorização de menor primeiro (o principal à frente),
     * depois os menores, cada um com o id remoto do responsável. Os erros saem sem repetição: o mesmo sobrevivente
     * pode aparecer duas vezes na reserva depois de uma mesclagem, e a chave de `fnrh.submission_error` é o erro. Os
     * dos dados sensíveis (RN-19) viram um só erro neutro por hóspede ([neutral]).
     */
    fun registerGuests(
        submission: FnrhSubmission,
        reservation: ReservationSummary,
        connection: FnrhConnection,
    ): FnrhStepOutcome {
        val prepared = prepare(submission, reservation)
        val errors =
            prepared
                .flatMap { guest ->
                    validator
                        .validate(guest.person, guest.guest, reservation.arrival, guest.responsibleKnown)
                        .map { it.neutral() }
                        .map { FnrhSubmissionError(guest.guestId.value, it.field, it.code) }
                }.distinct()
        if (errors.isNotEmpty()) return FnrhStepOutcome.Invalid(errors)
        val inFlight = sync.writeInFlight(submission)
        val remoteReservation = sync.ensureReservation(submission, reservation, connection)
        val run = Registration(submission, reservation, connection, remoteReservation, inFlight)
        prepared.sortedWith(compareBy({ it.minor }, { !it.primary })).forEach(run::register)
        return FnrhStepOutcome.Sent
    }

    private fun hasCheckIn(reservation: ReservationSummary): Boolean =
        reservation.status in CHECKED_IN && reservation.guests.any { it.checkedInAt != null }

    /** A reserva sem check-in: com a escrita em curso, grava o id do hóspede que o Serpro já tem; nada mais. */
    private fun reconcileOnly(
        submission: FnrhSubmission,
        reservation: ReservationSummary,
        connection: FnrhConnection,
    ) {
        if (!sync.writeInFlight(submission)) return
        val remoteReservation = sync.existing(submission, reservation, connection) ?: return
        val listed = gateway.listReservationGuests(remoteReservation, submission.id.toString())
        remoteIds
            .guests(reservation.id.value, connection.environment)
            .filter { it.guestRemoteId == null }
            .forEach { row ->
                listed.guestOf(row.personId)?.let { remoteIds.saveGuest(row.copy(guestRemoteId = it), submission.id) }
            }
    }

    /** Os hóspedes da reserva com os dados da FNRH, pelo id do sobrevivente (D-E08-7). */
    private fun prepare(
        submission: FnrhSubmission,
        reservation: ReservationSummary,
    ): List<PreparedGuest> {
        val survivors = reservation.guests.associate { it.guestId to survivorOf(it.guestId) }
        return reservation.guests.map { stay ->
            val guestId = survivors.getValue(stay.guestId)
            val data =
                checkNotNull(guests.fnrhData(guestId)) {
                    "Hóspede $guestId da reserva ${reservation.id} não encontrado."
                }
            val responsible = stay.minorAuthorization?.responsibleGuestId?.let(::survivorOf)
            PreparedGuest(
                guestId = guestId,
                stay = stay,
                person = FnrhPersonMapper.toPerson(data),
                guest = fnrhGuest(stay, submission),
                responsibleId = responsible,
                responsibleKnown = responsible != null && responsible in survivors.values,
            )
        }
    }

    private fun survivorOf(id: GuestId): GuestId = guests.identification(id)?.id ?: id

    private fun fnrhGuest(
        stay: StayGuestSummary,
        submission: FnrhSubmission,
    ) = FnrhGuest(
        principal = stay.primary,
        checkedInAt = stay.checkedInAt ?: submission.occurredAt,
        travelReason = stay.travelReason,
        transportMode = stay.transportMode,
        responsibleGuestId = null,
    )

    /**
     * Um hóspede pronto para o envio. Tem dado pessoal e sensível: o [toString] mostra só o id, e nada dele sai
     * desta classe além das chamadas ao Serpro.
     */
    private class PreparedGuest(
        val guestId: GuestId,
        val stay: StayGuestSummary,
        val person: FnrhPersonData,
        val guest: FnrhGuest,
        val responsibleId: GuestId?,
        val responsibleKnown: Boolean,
    ) {
        val minor: Boolean get() = stay.minorAuthorization != null
        val primary: Boolean get() = stay.primary

        override fun toString(): String = "PreparedGuest(guestId=$guestId)"
    }

    /** Um envio dos hóspedes de uma reserva: o id remoto de cada hóspede posto, para o responsável dos menores. */
    private inner class Registration(
        private val submission: FnrhSubmission,
        private val reservation: ReservationSummary,
        private val connection: FnrhConnection,
        private val remoteReservation: FnrhRemoteId,
        private val inFlight: Boolean,
    ) {
        private val ref = submission.id.toString()
        private val added = mutableMapOf<GuestId, FnrhRemoteId>()
        private val requesterCpf: Cpf by lazy {
            RequesterCpfPolicy.choose(submission.performedBy, requesters::cpfOf, connection.defaultRequesterCpf)
                ?: connection.defaultRequesterCpf
        }

        // Com a escrita em curso, o que o Serpro já tem na reserva: lido uma vez, só se algum hóspede precisar.
        private val listed: List<FnrhRemoteGuestRef> by lazy {
            gateway.listReservationGuests(remoteReservation, ref)
        }

        /**
         * Pula o hóspede que já tem id remoto, ou cuja pessoa já está na reserva com id (uma mesclagem); senão
         * reaproveita ou cria a pessoa e põe o hóspede na reserva. A pessoa reaproveitada (conhecida ou achada pelo
         * documento) ganha a linha local antes do `POST` do hóspede: sem ela, um check-in desfeito com a escrita em
         * curso não acharia o que conciliar (D-E08-8).
         */
        fun register(guest: PreparedGuest) {
            val row = rowOf(guest.guestId)
            val personId = row?.personId ?: remoteIds.knownPerson(guest.guestId.value, env()) ?: findPerson(guest)
            val existing = row?.guestRemoteId ?: personId?.let(::guestOfPersonInReservation)
            if (existing != null) {
                added[guest.guestId] = existing
                return
            }
            if (row == null && personId != null) save(guest.guestId, personId, null)
            val person = personId ?: registerPerson(guest)
            added[guest.guestId] = reconciled(guest, person) ?: addGuest(guest, person)
        }

        /** Com a escrita em curso, o hóspede da pessoa que o Serpro já tem na reserva, gravado. */
        private fun reconciled(
            guest: PreparedGuest,
            personId: FnrhRemoteId,
        ): FnrhRemoteId? {
            if (!inFlight) return null
            return listed.guestOf(personId)?.also { save(guest.guestId, personId, it) }
        }

        private fun findPerson(guest: PreparedGuest): FnrhRemoteId? =
            gateway.findPersonByDocument(checkNotNull(guest.person.document), requesterCpf, ref)

        private fun registerPerson(guest: PreparedGuest): FnrhRemoteId {
            sync.beginWrite(submission)
            val personId = gateway.registerPerson(guest.person, requesterCpf, ref)
            save(guest.guestId, personId, null)
            return personId
        }

        private fun addGuest(
            guest: PreparedGuest,
            personId: FnrhRemoteId,
        ): FnrhRemoteId {
            val responsible =
                guest.responsibleId?.let { id ->
                    checkNotNull(added[id]) { "Responsável do hóspede ${guest.guestId} sem id remoto." }
                }
            sync.beginWrite(submission)
            val guestRemoteId =
                gateway.addGuestToReservation(
                    remoteReservation,
                    personId,
                    guest.guest.copy(responsibleGuestId = responsible),
                    ref,
                )
            save(guest.guestId, personId, guestRemoteId)
            return guestRemoteId
        }

        /** Grava os ids com o estado completo da linha, relida antes (não regride o check-out). */
        private fun save(
            guestId: GuestId,
            personId: FnrhRemoteId,
            guestRemoteId: FnrhRemoteId?,
        ) {
            val checkedOut = rowOf(guestId)?.checkedOut ?: false
            remoteIds.saveGuest(
                FnrhRemoteGuest(reservation.id.value, guestId.value, env(), personId, guestRemoteId, checkedOut),
                submission.id,
            )
        }

        private fun guestOfPersonInReservation(personId: FnrhRemoteId): FnrhRemoteId? =
            remoteIds
                .guests(reservation.id.value, env())
                .firstOrNull { it.personId == personId && it.guestRemoteId != null }
                ?.guestRemoteId

        private fun rowOf(guestId: GuestId): FnrhRemoteGuest? =
            remoteIds.guests(reservation.id.value, env()).firstOrNull { it.guestId == guestId.value }

        private fun env() = connection.environment
    }

    private companion object {
        val CHECKED_IN = setOf(ReservationStatus.IN_HOUSE, ReservationStatus.CHECKED_OUT)

        /** Os campos dos dados sensíveis (RN-19): raça/cor e deficiência. */
        val SENSITIVE_FIELDS = setOf("person.race", "person.disabilityOption", "person.disabilityType")
        val SENSITIVE_DATA_ERROR = FnrhFieldError("person.sensitiveData", FieldViolationCodes.INVALID_VALUE)

        /**
         * O erro de um dado sensível vira `person.sensitiveData` / `INVALID_VALUE`: o campo e o código diriam, por
         * exemplo, que o hóspede declarou deficiência (o tipo só existe com ela), e o erro é gravado em
         * `fnrh.submission_error` e exposto na API. A validação local segue com o detalhe; só o gravado é neutro.
         */
        fun FnrhFieldError.neutral(): FnrhFieldError = if (field in SENSITIVE_FIELDS) SENSITIVE_DATA_ERROR else this

        /**
         * O hóspede do Serpro da pessoa; `null` se não há. Mais de um casamento é resultado incerto: falha fechado
         * (D-E08-6).
         */
        fun List<FnrhRemoteGuestRef>.guestOf(personId: FnrhRemoteId): FnrhRemoteId? {
            val matches = filter { it.personId == personId }
            if (matches.size > 1) throw FnrhGatewayException(FnrhFailure.UNKNOWN_OUTCOME, null, null)
            return matches.singleOrNull()?.guestId
        }
    }
}
