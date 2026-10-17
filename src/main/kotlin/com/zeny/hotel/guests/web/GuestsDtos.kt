package com.zeny.hotel.guests.web

import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.guests.DocumentType
import com.zeny.hotel.guests.Gender
import com.zeny.hotel.guests.GuestPreferences
import com.zeny.hotel.guests.RaceColor
import com.zeny.hotel.guests.application.AddressCommand
import com.zeny.hotel.guests.application.CompanyCommand
import com.zeny.hotel.guests.application.CompanyView
import com.zeny.hotel.guests.application.DocumentCommand
import com.zeny.hotel.guests.application.GuestCommand
import com.zeny.hotel.guests.application.GuestView
import com.zeny.hotel.guests.application.RevealedDocument
import com.zeny.hotel.guests.application.SensitiveDataCommand
import com.zeny.hotel.shared.Address
import com.zeny.hotel.shared.BrazilianState
import com.zeny.hotel.shared.Cnpj
import com.zeny.hotel.shared.Money
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val MAX_DOCUMENTS = 6
private const val COUNTRY_DESCRIPTION = "País pela ISO 3166-1 alfa-2 (BR, AR, US…)."

@Schema(description = "Endereço no Brasil com o código IBGE do município.")
data class GuestAddressDto(
    @field:NotBlank @field:Size(max = 200)
    val street: String,
    @field:NotBlank @field:Size(max = 20)
    val number: String,
    @field:Size(max = 100)
    val complement: String?,
    @field:NotBlank @field:Size(max = 100)
    val district: String,
    @field:NotBlank @field:Size(max = 100)
    val city: String,
    val state: BrazilianState,
    @field:Schema(description = "CEP, com ou sem hífen; volta só com os 8 dígitos.", example = "01310-100")
    @field:Pattern(regexp = "\\d{5}-?\\d{3}")
    val postalCode: String,
    @field:Schema(description = "Código IBGE do município (7 dígitos, os 2 primeiros da UF).", example = "3550308")
    @field:Pattern(regexp = "\\d{7}")
    val ibgeCityCode: String,
) {
    fun toCommand() = AddressCommand(street, number, complement, district, city, state, postalCode, ibgeCityCode)

    override fun toString(): String = "GuestAddressDto(***)"

    companion object {
        fun of(address: Address) =
            GuestAddressDto(
                address.street,
                address.number,
                address.complement,
                address.district,
                address.city,
                address.state,
                address.postalCode,
                address.ibgeCityCode,
            )
    }
}

@Schema(description = "Preferências do hóspede (RF-HOS-02), em texto curto.")
data class GuestPreferencesDto(
    @field:Schema(description = "Andar preferido.", example = "Alto")
    @field:Size(max = 60)
    val floor: String? = null,
    @field:Schema(description = "Camas.", example = "Casal")
    @field:Size(max = 60)
    val bed: String? = null,
    @field:Schema(description = "Travesseiro.", example = "Baixo")
    @field:Size(max = 60)
    val pillow: String? = null,
) {
    fun toPreferences() = GuestPreferences(floor, bed, pillow)

    companion object {
        fun of(preferences: GuestPreferences) =
            GuestPreferencesDto(preferences.floor, preferences.bed, preferences.pillow)
    }
}

@Schema(description = "Um documento de identidade com o número em claro (só na entrada).")
data class GuestDocumentRequest(
    @field:NotNull
    val type: DocumentType?,
    @field:Schema(
        description =
            "País emissor (ISO 3166-1 alfa-2). Obrigatório para PASSPORT e FOREIGN_ID; nos documentos brasileiros " +
                "(CPF, RG, CNH, RNM) é sempre BR e pode ser omitido.",
        example = "AR",
    )
    @field:Size(max = 2)
    val issuingCountry: String? = null,
    @field:Schema(
        description = "Número, com ou sem pontuação. Fica cifrado e só volta mascarado.",
        example = "529.982.247-25",
    )
    @field:NotBlank
    @field:Size(max = 40)
    val number: String?,
) {
    fun toCommand() = DocumentCommand(checkNotNull(type), issuingCountry, checkNotNull(number))

    /** Nunca mostra o número. */
    override fun toString(): String = "GuestDocumentRequest(type=$type, issuingCountry=$issuingCountry)"
}

@Schema(
    description =
        "Dados sensíveis da FNRH (RN-19). Só se gravam: ficam cifrados, nunca voltam pela API (só " +
            "`hasSensitiveData`) e são lidos apenas pela integração FNRH. Os dois nulos apagam o que havia.",
)
data class GuestSensitiveDataRequest(
    @field:Schema(description = "Raça/cor pelas categorias do IBGE.")
    val raceColor: RaceColor? = null,
    @field:Schema(description = "Código da deficiência no domínio da FNRH (letras maiúsculas, dígitos e _).")
    @field:Size(max = 60)
    val disability: String? = null,
) {
    fun toCommand() = SensitiveDataCommand(raceColor, disability)

    /** Nunca mostra os dados. */
    override fun toString(): String = "GuestSensitiveDataRequest(***)"
}

@Schema(description = "Dados de um perfil de hóspede (RF-HOS-01 e 02).")
data class GuestProfileRequest(
    @field:Schema(description = "Nome completo.", example = "Maria da Silva")
    @field:NotBlank
    @field:Size(max = 200)
    val fullName: String?,
    @field:Size(max = 200)
    val socialName: String? = null,
    @field:Schema(description = "Nacionalidade. $COUNTRY_DESCRIPTION", example = "BR")
    @field:Size(max = 2)
    val nationality: String? = null,
    val birthDate: LocalDate? = null,
    val gender: Gender? = null,
    @field:Size(max = 254)
    val email: String? = null,
    @field:Size(max = 30)
    val phone: String? = null,
    @field:Schema(description = "País de residência. $COUNTRY_DESCRIPTION", example = "BR")
    @field:Size(max = 2)
    val residenceCountry: String? = null,
    @field:Schema(description = "Endereço completo: só para quem reside no Brasil (residenceCountry BR).")
    @field:Valid
    val address: GuestAddressDto? = null,
    @field:Valid
    val preferences: GuestPreferencesDto? = null,
    val vip: Boolean = false,
    @field:Size(max = 1000)
    val notes: String? = null,
    @field:Schema(description = "Empresa a que o hóspede está ligado (contato), se houver.")
    val companyId: UUID? = null,
    @field:Schema(
        description =
            "Restrições alimentares: dado sensível, só com o consentimento do hóspede (RF-HOS-02). Até o " +
                "consentimento existir, qualquer valor é recusado com 422 `DIETARY_RESTRICTIONS_REQUIRE_CONSENT`.",
    )
    @field:Size(max = 500)
    val dietaryRestrictions: String? = null,
) {
    fun toCommand() =
        GuestCommand(
            fullName = checkNotNull(fullName),
            socialName = socialName,
            nationality = nationality,
            birthDate = birthDate,
            gender = gender,
            email = email,
            phone = phone,
            residenceCountry = residenceCountry,
            address = address?.toCommand(),
            preferences = preferences?.toPreferences() ?: GuestPreferences(),
            vip = vip,
            notes = notes,
            companyId = companyId?.let(::CompanyId),
            dietaryRestrictions = dietaryRestrictions,
        )

    override fun toString(): String = "GuestProfileRequest(***)"
}

@Schema(description = "Um perfil novo: os dados, os documentos e, opcionalmente, os dados sensíveis da FNRH.")
data class CreateGuestRequest(
    @field:NotNull
    @field:Valid
    @field:Schema(description = "Os dados do perfil.")
    val profile: GuestProfileRequest?,
    @field:Schema(description = "Documentos de identidade (até 6; um de cada tipo e país).")
    @field:Size(max = MAX_DOCUMENTS)
    val documents: List<@Valid GuestDocumentRequest> = emptyList(),
    @field:Valid
    val sensitiveData: GuestSensitiveDataRequest? = null,
)

@Schema(description = "Um termo de busca por documento (o número vai no corpo, nunca na URL).")
data class GuestDocumentSearchRequest(
    @field:NotNull
    val type: DocumentType?,
    @field:Size(max = 2)
    val issuingCountry: String? = null,
    @field:NotBlank
    @field:Size(max = 40)
    val number: String?,
) {
    fun toCommand() = DocumentCommand(checkNotNull(type), issuingCountry, checkNotNull(number))

    override fun toString(): String = "GuestDocumentSearchRequest(type=$type)"
}

@Schema(description = "A inclusão na lista de restrição.")
data class RestrictGuestRequest(
    @field:Schema(description = "O motivo, mostrado no alerta da reserva e do check-in.")
    @field:NotBlank
    @field:Size(max = 500)
    val reason: String?,
) {
    override fun toString(): String = "RestrictGuestRequest(***)"
}

@Schema(description = "Um documento com o número mascarado (H9): tipo, país e só os últimos caracteres.")
data class GuestDocumentResponse(
    val id: UUID,
    val type: DocumentType,
    val issuingCountry: String,
    @field:Schema(description = "Número mascarado.", example = "***4725")
    val maskedNumber: String,
    val createdAt: Instant,
)

@Schema(description = "A inclusão na lista de restrição (RF-HOS-04).")
data class GuestRestrictionResponse(
    val reason: String,
    val since: Instant,
    @field:Schema(description = "Quem incluiu (nulo: o sistema).")
    val restrictedBy: UUID?,
)

@Schema(
    description =
        "Um perfil de hóspede. Os documentos vêm mascarados e os dados sensíveis da FNRH nunca vêm (só " +
            "`hasSensitiveData`).",
)
data class GuestResponse(
    val id: UUID,
    val fullName: String,
    val socialName: String?,
    val nationality: String?,
    val birthDate: LocalDate?,
    val gender: Gender?,
    val email: String?,
    val phone: String?,
    val residenceCountry: String?,
    val address: GuestAddressDto?,
    val preferences: GuestPreferencesDto,
    val vip: Boolean,
    val notes: String?,
    val companyId: UUID?,
    @field:Schema(description = "Preenchido só se o hóspede está na lista de restrição.")
    val restriction: GuestRestrictionResponse?,
    val documents: List<GuestDocumentResponse>,
    @field:Schema(description = "Se os dados sensíveis da FNRH já foram gravados (os valores nunca voltam).")
    val hasSensitiveData: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun toString(): String = "GuestResponse(id=$id)"

    companion object {
        fun of(view: GuestView) =
            GuestResponse(
                id = view.id.value,
                fullName = view.fullName,
                socialName = view.socialName,
                nationality = view.nationality,
                birthDate = view.birthDate,
                gender = view.gender,
                email = view.email,
                phone = view.phone,
                residenceCountry = view.residenceCountry,
                address = view.address?.let(GuestAddressDto::of),
                preferences = GuestPreferencesDto.of(view.preferences),
                vip = view.vip,
                notes = view.notes,
                companyId = view.companyId?.value,
                restriction =
                    view.restriction?.let { GuestRestrictionResponse(it.reason, it.since, it.restrictedBy?.value) },
                documents =
                    view.documents.map {
                        GuestDocumentResponse(it.id.value, it.type, it.issuingCountry, it.maskedNumber, it.createdAt)
                    },
                hasSensitiveData = view.hasSensitiveData,
                createdAt = view.createdAt,
                updatedAt = view.updatedAt,
            )
    }
}

@Schema(description = "Os perfis encontrados.")
data class GuestListResponse(
    val items: List<GuestResponse>,
)

@Schema(description = "O número inteiro de um documento (cada acesso fica na trilha de auditoria).")
data class RevealedDocumentResponse(
    val id: UUID,
    val guestId: UUID,
    val type: DocumentType,
    val issuingCountry: String,
    val number: String,
) {
    override fun toString(): String = "RevealedDocumentResponse(id=$id)"

    companion object {
        fun of(revealed: RevealedDocument) =
            RevealedDocumentResponse(
                revealed.id.value,
                revealed.guestId.value,
                revealed.type,
                revealed.issuingCountry,
                revealed.number,
            )
    }
}

@Schema(description = "Dados de uma empresa (RF-HOS-06).")
data class CompanyRequest(
    @field:NotBlank
    @field:Size(max = 200)
    val legalName: String?,
    @field:Size(max = 200)
    val tradeName: String? = null,
    @field:Schema(description = "CNPJ numérico ou alfanumérico, com ou sem pontuação.", example = "11.222.333/0001-81")
    @field:NotNull
    val cnpj: Cnpj?,
    @field:Schema(description = "Inscrição estadual.")
    @field:Size(max = 30)
    val stateRegistration: String? = null,
    @field:Schema(description = "Inscrição municipal.")
    @field:Size(max = 30)
    val municipalRegistration: String? = null,
    @field:Valid
    val address: GuestAddressDto? = null,
    @field:Size(max = 200)
    val contactName: String? = null,
    @field:Size(max = 30)
    val phone: String? = null,
    @field:Size(max = 254)
    val email: String? = null,
    @field:Schema(description = "E-mail de cobrança.")
    @field:Size(max = 254)
    val billingEmail: String? = null,
    @field:Schema(description = "Limite de crédito (não negativo); nulo: sem crédito.", example = "5000.00")
    val creditLimit: Money? = null,
) {
    fun toCommand() =
        CompanyCommand(
            legalName = checkNotNull(legalName),
            tradeName = tradeName,
            cnpj = checkNotNull(cnpj),
            stateRegistration = stateRegistration,
            municipalRegistration = municipalRegistration,
            address = address?.toCommand(),
            contactName = contactName,
            phone = phone,
            email = email,
            billingEmail = billingEmail,
            creditLimit = creditLimit,
        )
}

@Schema(description = "Uma empresa.")
data class CompanyResponse(
    val id: UUID,
    val legalName: String,
    val tradeName: String?,
    val cnpj: Cnpj,
    val stateRegistration: String?,
    val municipalRegistration: String?,
    val address: GuestAddressDto?,
    val contactName: String?,
    val phone: String?,
    val email: String?,
    val billingEmail: String?,
    val creditLimit: Money?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: CompanyView) =
            CompanyResponse(
                id = view.id.value,
                legalName = view.legalName,
                tradeName = view.tradeName,
                cnpj = view.cnpj,
                stateRegistration = view.stateRegistration,
                municipalRegistration = view.municipalRegistration,
                address = view.address?.let(GuestAddressDto::of),
                contactName = view.contactName,
                phone = view.phone,
                email = view.email,
                billingEmail = view.billingEmail,
                creditLimit = view.creditLimit,
                active = view.active,
                createdAt = view.createdAt,
                updatedAt = view.updatedAt,
            )
    }
}
