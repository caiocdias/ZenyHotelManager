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
    /**
     * O resumo de um perfil; `null` se não existe. Um perfil mesclado (RF-HOS-05) continua respondendo, com
     * [GuestSummary.status] `MERGED` e o sobrevivente em [GuestSummary.mergedInto]: quem guarda o id reponta para
     * ele (e escuta [GuestsMerged]).
     */
    fun guest(id: GuestId): GuestSummary?

    /**
     * Os perfis com o documento informado, encontrados pelo índice cego (H8), sem decifrar nenhum documento.
     * O número é normalizado como no cadastro (CPF só com os dígitos; os demais em maiúsculas, sem espaços nem
     * pontuação). [issuingCountry]: obrigatório para passaporte e identidade estrangeira (que não aceita `BR`);
     * nos documentos brasileiros (CPF, RG, CNH, RNM) pode ser omitido (vale `BR`), e outro país é recusado.
     * Recusas: número inválido para o tipo, `400 DOCUMENT_NUMBER_INVALID`; país ausente, de outro país num
     * documento brasileiro ou `BR` numa identidade estrangeira, `400 DOCUMENT_COUNTRY_INVALID`; código de país
     * fora da ISO 3166-1, `400 INVALID_COUNTRY`. Só perfis ativos: um perfil mesclado não volta (o sobrevivente
     * tem os documentos dele).
     */
    fun findByDocument(
        type: DocumentType,
        issuingCountry: String?,
        number: String,
    ): List<GuestSummary>

    /**
     * O alerta da lista de restrição (RF-HOS-04) para a reserva e o check-in: o motivo e desde quando; `null`
     * se o hóspede não está na lista (ou não existe). De um perfil mesclado, vale o do sobrevivente (que herdou a
     * restrição: a mesclagem nunca apaga um alerta), com o id dele em [RestrictionAlert.guestId].
     */
    fun restrictionAlert(id: GuestId): RestrictionAlert?

    /**
     * Se o hóspede é menor de idade na data (RN-24); `null` se não existe ou não tem data de nascimento. De um perfil
     * mesclado, vale o sobrevivente.
     */
    fun isMinorOn(
        id: GuestId,
        date: LocalDate,
    ): Boolean?

    /**
     * As preferências do hóspede (RF-HOS-02: andar, camas e travesseiro, texto curto), para a sugestão de UH das
     * reservas (M4 E10, H13); `null` se o perfil não existe. De um perfil mesclado, valem as do sobrevivente
     * (seguindo a cadeia). Sem dado de contato nem documento.
     */
    fun preferences(id: GuestId): GuestPreferences?

    /** O resumo de uma empresa (pagadora de contas, tomadora de nota); `null` se não existe. */
    fun company(id: CompanyId): CompanySummary?

    /**
     * Os dados do hóspede exigidos pela FNRH, **inclusive o número dos documentos e os dados sensíveis
     * decifrados** (raça/cor e deficiência). **Exclusivo da integração FNRH** (módulo `fnrh`, M5; RN-19: só a
     * integração tem acesso): nenhum outro módulo chama este método, e o resultado nunca vai a resposta,
     * log, trilha nem evento. `null` se o perfil não existe. De um perfil mesclado, devolve os do sobrevivente (que
     * recebeu os documentos dele).
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

/**
 * Situação de um perfil: `ACTIVE`; `MERGED` depois de absorvido por outro numa mesclagem de duplicados (RF-HOS-05);
 * `ANONYMIZED` depois de anonimizado a pedido do titular (RF-HOS-07; o perfil absorvido por um anonimizado continua
 * `MERGED`, com a data da anonimização). Nenhum é apagado; o mesclado e o anonimizado não são editados e não voltam
 * nas buscas.
 */
enum class GuestStatus {
    ACTIVE,
    MERGED,
    ANONYMIZED,
}

/**
 * Os dados do perfil que o pedido de mesclagem pode trazer do absorvido (decisão do usuário em 2026-10-17). Por
 * padrão vale o que o sobrevivente tem, e os campos vazios dele são preenchidos com os do absorvido; um campo listado
 * em `takeFromMerged` vem do absorvido, se ele o tiver (a mesclagem nunca apaga um valor). [RESIDENCE] é o país de
 * residência com o endereço (o endereço completo é só de quem reside no Brasil) e [PREFERENCES], as três
 * preferências; [SENSITIVE_DATA], os dados sensíveis da FNRH. VIP, restrição, documentos e consentimentos têm regras
 * próprias (os consentimentos do absorvido passam todos para o sobrevivente; M3 E03).
 */
enum class GuestMergeField {
    FULL_NAME,
    SOCIAL_NAME,
    NATIONALITY,
    BIRTH_DATE,
    GENDER,
    EMAIL,
    PHONE,
    RESIDENCE,
    PREFERENCES,
    NOTES,
    COMPANY,
    SENSITIVE_DATA,

    /** As restrições alimentares: só ficam no sobrevivente se ele terminar com o consentimento delas ativo. */
    DIETARY_RESTRICTIONS,
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

/**
 * O resumo de um perfil para os módulos de cima: sem documento nem dado sensível. [mergedInto]: o sobrevivente, num
 * perfil mesclado ([status] `MERGED`).
 */
data class GuestSummary(
    val id: GuestId,
    val fullName: String,
    val socialName: String?,
    val birthDate: LocalDate?,
    val nationality: String?,
    val vip: Boolean,
    val restricted: Boolean,
    val companyId: CompanyId?,
    val status: GuestStatus,
    val mergedInto: GuestId?,
) {
    /** Sem dado pessoal (o resumo pode acabar num log). */
    override fun toString(): String =
        "GuestSummary(id=$id, status=$status, vip=$vip, restricted=$restricted, mergedInto=$mergedInto)"
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
