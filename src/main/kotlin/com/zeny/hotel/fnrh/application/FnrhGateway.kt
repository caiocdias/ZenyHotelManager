package com.zeny.hotel.fnrh.application

import com.zeny.hotel.fnrh.domain.FnrhDocument
import com.zeny.hotel.fnrh.domain.FnrhDomain
import com.zeny.hotel.fnrh.domain.FnrhDomainValue
import com.zeny.hotel.fnrh.domain.FnrhGuest
import com.zeny.hotel.fnrh.domain.FnrhPersonData
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.fnrh.domain.FnrhReservationData
import com.zeny.hotel.shared.Cpf
import java.time.Instant

/** O resultado de `POST /reservas`: o id remoto e o link de pré-check-in (RF-FNRH-04), quando o Serpro o dá. */
data class FnrhReservationRegistered(
    val reservationId: FnrhRemoteId,
    val precheckinLink: String?,
)

/** Um hóspede da reserva no Serpro: os ids dele e da pessoa. Só ids; nenhum dado pessoal da resposta é guardado. */
data class FnrhRemoteGuestRef(
    val guestId: FnrhRemoteId,
    val personId: FnrhRemoteId,
)

/** Por que uma chamada ao Serpro falhou. [temporary] diz se vale tentar de novo. */
enum class FnrhFailure(
    val temporary: Boolean,
) {
    /** A integração está sem configuração (desligada): nenhuma chamada foi feita. */
    NOT_CONFIGURED(false),

    /** `4xx` que não os abaixo: o Serpro recusou os dados. */
    VALIDATION(false),
    UNAUTHORIZED(false),
    FORBIDDEN(false),
    NOT_FOUND(false),

    /** `429`, `3xx`, `5xx`, ou 2xx sem o esperado numa leitura. */
    SERVER_ERROR(true),

    /** Conexão recusada ou esgotada, ou leitura esgotada num `GET`: o Serpro não gravou nada. */
    UNAVAILABLE(true),

    /**
     * Numa escrita (`POST`/`PUT`/`PATCH`), a leitura esgotou ou o Serpro respondeu 2xx sem o id esperado: não se sabe
     * se ele gravou. Concilie antes de repetir.
     */
    UNKNOWN_OUTCOME(true),
}

/**
 * Falha de uma chamada ao Serpro. [sanitizedMessage] é a `mensagem` do Serpro sem os dados enviados (nome, documento,
 * e-mail, telefone), sem sequências longas de dígitos e sem e-mails. Nunca encadeia a exceção do cliente HTTP como
 * causa, porque ela pode trazer o corpo da resposta.
 */
class FnrhGatewayException(
    val failure: FnrhFailure,
    val httpStatus: Int?,
    val sanitizedMessage: String?,
) : RuntimeException(
        "FNRH: $failure" + httpStatus?.let { " (HTTP $it)" }.orEmpty() + sanitizedMessage?.let { ": $it" }.orEmpty(),
    )

/**
 * A porta da FNRH (API v2.4 do Serpro, `docs/fnrh/api-fnrh-v2.4.md`). Toda operação roda fora de transação do
 * banco e lança [FnrhGatewayException] em caso de falha. O `correlationRef` é uma referência opaca nossa (o id da
 * reserva, ou da submissão), nunca um dado pessoal. Só [registerPerson] leva o CPF solicitante (D-E07-9).
 */
interface FnrhGateway {
    /** `POST /reservas`. */
    fun registerReservation(
        reservation: FnrhReservationData,
        correlationRef: String,
    ): FnrhReservationRegistered

    /** `PUT /reservas/{id}`. */
    fun updateReservation(
        reservationId: FnrhRemoteId,
        reservation: FnrhReservationData,
        correlationRef: String,
    )

    /** `POST /reservas/{id}/cancelar`: só vale com a reserva em `CRIADA`. */
    fun cancelReservation(
        reservationId: FnrhRemoteId,
        correlationRef: String,
    )

    /** `GET /reservas?codigo_reserva=…`: para conciliar um [FnrhFailure.UNKNOWN_OUTCOME]; `null` se não existe. */
    fun findReservationByCode(
        reservationCode: String,
        correlationRef: String,
    ): FnrhRemoteId?

    /**
     * `GET /reservas/{id}/hospedes`, sem `cpf_solicitante`: para conciliar um [FnrhFailure.UNKNOWN_OUTCOME] do
     * `POST /reservas/{id}/hospedes`. Lê só `dados[].hospede.hospede_id` e `dados[].hospede.pessoa_id`; um `200` sem
     * `dados`, ou um item sem os dois ids, é [FnrhFailure.UNKNOWN_OUTCOME] (falha fechado).
     */
    fun listReservationGuests(
        reservationId: FnrhRemoteId,
        correlationRef: String,
    ): List<FnrhRemoteGuestRef>

    /**
     * `GET /pessoas/documento/{tipo}/{numero}`, com `cpf_solicitante`: antes de criar a pessoa. O id da pessoa
     * (`dados.id`) vem só quando ela já está cadastrada neste meio de hospedagem; sem `id`, ou com `404`, devolve
     * `null` (criar com [registerPerson]). Premissa: o nome `dados.id` vem da observação do PDF (o exemplo não o
     * mostra); a E09 (homologação) o confirma. Um `200` sem `dados` é [FnrhFailure.UNKNOWN_OUTCOME]. A resposta traz
     * dado pessoal e o número do documento vai na URL: nada disso é lido, guardado nem registrado.
     */
    fun findPersonByDocument(
        document: FnrhDocument,
        requesterCpf: Cpf,
        correlationRef: String,
    ): FnrhRemoteId?

    /** `POST /pessoas`, com `cpf_solicitante`. */
    fun registerPerson(
        person: FnrhPersonData,
        requesterCpf: Cpf,
        correlationRef: String,
    ): FnrhRemoteId

    /** `POST /reservas/{id}/hospedes`: devolve o id do hóspede. */
    fun addGuestToReservation(
        reservationId: FnrhRemoteId,
        personId: FnrhRemoteId,
        guest: FnrhGuest,
        correlationRef: String,
    ): FnrhRemoteId

    /** `PATCH /hospedes/{id}/checkout`, com a data e hora em `text/plain`. */
    fun checkOut(
        guestId: FnrhRemoteId,
        at: Instant,
        correlationRef: String,
    )

    /** `GET` do domínio oficial. */
    fun fetchDomain(domain: FnrhDomain): List<FnrhDomainValue>
}
