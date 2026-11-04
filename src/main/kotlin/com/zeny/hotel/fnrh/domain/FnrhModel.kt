package com.zeny.hotel.fnrh.domain

import java.time.Instant
import java.time.LocalDate

/**
 * O identificador que o Serpro deu a um recurso (reserva, pessoa ou hóspede): um texto opaco, o `uuid` da API.
 * É uma referência, não um dado pessoal.
 */
@JvmInline
value class FnrhRemoteId(
    val value: String,
) {
    override fun toString(): String = value
}

/**
 * Os dados da reserva que vão ao Serpro (`POST /reservas` e `PUT /reservas/{id}`), sem nenhum dado pessoal:
 *
 * - [reservationCode] vira `numero_reserva` e é o `Reservation.code`, nunca o código do `Booking`;
 * - [otaCode] vira `numero_reserva_ota` e é o `Booking.externalId`; com ele, `origem_reserva_id` é `OTA`, e sem ele
 *   é `MEIOHOSPEDAGEM`;
 * - [minors] sai da data de nascimento (menor de 18 anos na data de entrada), e não de `childAges`.
 */
data class FnrhReservationData(
    val reservationCode: String,
    val otaCode: String?,
    val arrival: LocalDate,
    val departure: LocalDate,
    val adults: Int,
    val minors: Int,
)

/** O tipo de documento aceito pela FNRH: só o CPF e o passaporte (`tipos_documento`). */
enum class FnrhDocumentType { CPF, PASSPORT }

/** O documento da pessoa. O [toString] não mostra o número. */
data class FnrhDocument(
    val type: FnrhDocumentType,
    val number: String,
) {
    override fun toString(): String = "FnrhDocument(type=$type)"
}

/**
 * Os dados da pessoa para `POST /pessoas`. Os campos de domínio ([gender], [race], [disabilityOption],
 * [disabilityType]) levam o `id` do domínio oficial da FNRH (`HOMEM`, `PARDA`, `SIM`, `VISUAL`…); [nationality] e
 * [residenceCountry] são ISO 3166-1 alpha-2 e [cityIbge] é o código IBGE de 7 dígitos. O [toString] não mostra nada.
 */
data class FnrhPersonData(
    val name: String,
    val socialName: String? = null,
    val nationality: String? = null,
    val gender: String? = null,
    val genderDescription: String? = null,
    val birthDate: LocalDate? = null,
    val race: String? = null,
    val disabilityOption: String? = null,
    val disabilityType: String? = null,
    val document: FnrhDocument? = null,
    val email: String? = null,
    val phone: String? = null,
    val residenceCountry: String? = null,
    val postalCode: String? = null,
    val street: String? = null,
    val number: String? = null,
    val complement: String? = null,
    val neighborhood: String? = null,
    val cityIbge: Int? = null,
    val state: String? = null,
) {
    override fun toString(): String = "FnrhPersonData(***)"
}

/** A situação do hóspede na reserva (`/dominios/hospedes/situacoes`). */
enum class FnrhGuestStatus {
    CANCELADO,
    CHECKIN_REALIZADO,
    CHECKOUT_REALIZADO,
    NOSHOW,
    PRECHECKIN_NAOVINCULADO,
    PRECHECKIN_PENDENTE,
    PRECHECKIN_REALIZADO,
}

/**
 * O hóspede a pôr na reserva. Entra sempre com [FnrhGuestStatus.CHECKIN_REALIZADO] (D-E07-2): [checkedInAt] é o
 * `checkin_em`, [travelReason] e [transportMode] são ids dos domínios `motivos_viagem` e `meios_transporte`, e
 * [responsibleGuestId] é o hóspede (não a pessoa) responsável pelo menor de 18 anos. O [toString] não mostra nada.
 */
data class FnrhGuest(
    val principal: Boolean,
    val checkedInAt: Instant,
    val travelReason: String?,
    val transportMode: String?,
    val responsibleGuestId: FnrhRemoteId?,
) {
    override fun toString(): String = "FnrhGuest(***)"
}

/** Os dez domínios oficiais da FNRH e o caminho de cada um (`GET`, a partir da URL base). */
enum class FnrhDomain(
    val path: String,
) {
    TRANSPORT_MODES("/dominios/fnrh/meios_transporte"),
    TRAVEL_REASONS("/dominios/fnrh/motivos_viagem"),
    GUEST_STATUSES("/dominios/hospedes/situacoes"),
    GENDERS("/dominios/pessoas/generos"),
    DISABILITY_OPTIONS("/dominios/pessoas/opcao_deficiencia"),
    RACES("/dominios/pessoas/racas"),
    DISABILITY_TYPES("/dominios/pessoas/tipos_deficiencia"),
    DOCUMENT_TYPES("/dominios/pessoas/tipos_documento"),
    RESERVATION_STATUSES("/dominios/reservas/situacoes"),
    FORM_STATUSES("/dominios/fichas/situacoes"),
}

/** Um valor de domínio: o [code] (`id`) que a API aceita e o [label] que o usuário lê. */
data class FnrhDomainValue(
    val code: String,
    val label: String,
)
