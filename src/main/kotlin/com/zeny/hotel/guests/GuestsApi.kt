package com.zeny.hotel.guests

import com.zeny.hotel.shared.Address
import com.zeny.hotel.shared.Cnpj
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UuidV7
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * API pública do módulo `guests` para os módulos de cima (reservas, recepção, FNRH, contas). Nenhum método
 * confere permissão: quem chama já se autorizou no próprio caso de uso. Os resumos levam o nome do hóspede,
 * mas nunca o número de documento nem os dados sensíveis da FNRH; só [fnrhData] os decifra.
 */
interface GuestsApi {
    /** O resumo de um perfil; `null` se não existe. */
    fun guest(id: GuestId): GuestSummary?

    /**
     * Os perfis com o documento informado, encontrados pelo índice cego (H8), sem decifrar nenhum documento.
     * O número é normalizado como no cadastro (CPF só com os dígitos; os demais em maiúsculas, sem espaços nem
     * pontuação). [issuingCountry]: obrigatório para passaporte e identidade estrangeira (que não aceita `BR`);
     * nos documentos brasileiros (CPF, RG, CNH, RNM) pode ser omitido (vale `BR`), e outro país é recusado.
     * Recusas: número inválido para o tipo, `400 DOCUMENT_NUMBER_INVALID`; país ausente, de outro país num
     * documento brasileiro ou `BR` numa identidade estrangeira, `400 DOCUMENT_COUNTRY_INVALID`; código de país
     * fora da ISO 3166-1, `400 INVALID_COUNTRY`.
     */
    fun findByDocument(
        type: DocumentType,
        issuingCountry: String?,
        number: String,
    ): List<GuestSummary>

    /**
     * O alerta da lista de restrição (RF-HOS-04) para a reserva e o check-in: o motivo e desde quando; `null`
     * se o hóspede não está na lista (ou não existe).
     */
    fun restrictionAlert(id: GuestId): RestrictionAlert?

    /** Se o hóspede é menor de idade na data (RN-24); `null` se não existe ou não tem data de nascimento. */
    fun isMinorOn(
        id: GuestId,
        date: LocalDate,
    ): Boolean?

    /** O resumo de uma empresa (pagadora de contas, tomadora de nota); `null` se não existe. */
    fun company(id: CompanyId): CompanySummary?

    /**
     * Os dados do hóspede exigidos pela FNRH, **inclusive o número dos documentos e os dados sensíveis
     * decifrados** (raça/cor e deficiência). **Exclusivo da integração FNRH** (módulo `fnrh`, M5; RN-19: só a
     * integração tem acesso): nenhum outro módulo chama este método, e o resultado nunca vai a resposta,
     * log, trilha nem evento. `null` se o perfil não existe.
     */
    fun fnrhData(id: GuestId): FnrhGuestData?
}

@JvmInline
value class GuestId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): GuestId = GuestId(UuidV7.generate())
    }
}

@JvmInline
value class GuestDocumentId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): GuestDocumentId = GuestDocumentId(UuidV7.generate())
    }
}

@JvmInline
value class CompanyId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): CompanyId = CompanyId(UuidV7.generate())
    }
}

/**
 * Tipos de documento aceitos, da lista da FNRH (decisão do usuário em 2026-10-16). Os brasileiros têm o país
 * emissor sempre `BR`; passaporte pode ser de qualquer país; a identidade estrangeira (como o DNI do
 * Mercosul), de qualquer país menos o Brasil.
 */
enum class DocumentType(
    /** Emitido no Brasil: o país emissor é sempre `BR`. */
    val brazilian: Boolean,
) {
    CPF(true),
    RG(true),
    CNH(true),
    PASSPORT(false),

    /** Registro Nacional Migratório (o antigo RNE). */
    RNM(true),

    /** Documento de identidade estrangeiro, como o DNI do Mercosul. */
    FOREIGN_ID(false),
}

/** Gênero informado pelo hóspede (FNRH). */
enum class Gender {
    FEMALE,
    MALE,
    OTHER,
    NOT_INFORMED,
}

/** Raça/cor pelas categorias do IBGE (dado sensível da FNRH, RN-19): só se grava; nunca volta pela API. */
enum class RaceColor {
    WHITE,
    BLACK,
    BROWN,
    YELLOW,
    INDIGENOUS,
    NOT_DECLARED,
}

/** Preferências do hóspede (RF-HOS-02): andar, camas e travesseiro, em texto curto. */
data class GuestPreferences(
    val floor: String? = null,
    val bed: String? = null,
    val pillow: String? = null,
)

/** O resumo de um perfil para os módulos de cima: sem documento nem dado sensível. */
data class GuestSummary(
    val id: GuestId,
    val fullName: String,
    val socialName: String?,
    val birthDate: LocalDate?,
    val nationality: String?,
    val vip: Boolean,
    val restricted: Boolean,
    val companyId: CompanyId?,
) {
    /** Sem dado pessoal (o resumo pode acabar num log). */
    override fun toString(): String = "GuestSummary(id=$id, vip=$vip, restricted=$restricted)"
}

/** O alerta da lista de restrição: o motivo registrado e desde quando. */
data class RestrictionAlert(
    val guestId: GuestId,
    val reason: String,
    val since: Instant,
) {
    override fun toString(): String = "RestrictionAlert(guestId=$guestId, since=$since)"
}

/** O resumo de uma empresa para os módulos de cima. [creditLimit] nulo: sem crédito. */
data class CompanySummary(
    val id: CompanyId,
    val legalName: String,
    val tradeName: String?,
    val cnpj: Cnpj,
    val billingEmail: String?,
    val creditLimit: Money?,
    val active: Boolean,
)

/** Um documento com o número em claro, só para a FNRH. */
data class FnrhGuestDocument(
    val type: DocumentType,
    val issuingCountry: String,
    val number: String,
) {
    /** Nunca mostra o número. */
    override fun toString(): String = "FnrhGuestDocument(type=$type, issuingCountry=$issuingCountry)"
}

/**
 * Os dados que a FNRH exige do hóspede (SPEC §5.9), inclusive os sensíveis (RN-19), decifrados. Só para a
 * integração FNRH ([GuestsApi.fnrhData]); [toString] não mostra nada além do id.
 */
data class FnrhGuestData(
    val id: GuestId,
    val fullName: String,
    val socialName: String?,
    val nationality: String?,
    val birthDate: LocalDate?,
    val gender: Gender?,
    val email: String?,
    val phone: String?,
    val residenceCountry: String?,
    val address: Address?,
    val documents: List<FnrhGuestDocument>,
    val raceColor: RaceColor?,
    val disability: String?,
) {
    /** Nunca mostra dado pessoal nem sensível. */
    override fun toString(): String = "FnrhGuestData(id=$id)"
}
