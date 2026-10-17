package com.zeny.hotel.guests.application

import com.zeny.hotel.guests.GuestsErrorCodes
import com.zeny.hotel.guests.GuestsPermissions
import com.zeny.hotel.guests.domain.Company
import com.zeny.hotel.guests.domain.Guest
import com.zeny.hotel.guests.domain.GuestDocument
import com.zeny.hotel.shared.Address
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.ModulePermissions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Monta o [Address] do comando: o [Address] confere o CEP, o código IBGE e se ele é da UF; a recusa vira `400
 * INVALID_ADDRESS` com o campo, sem repetir o valor recebido (a mensagem do [Address] o cita).
 */
internal fun AddressCommand.toAddress(): Address =
    try {
        Address(
            street.trim(),
            number.trim(),
            complement?.trim()?.ifEmpty { null },
            district.trim(),
            city.trim(),
            state,
            postalCode,
            ibgeCityCode,
        )
    } catch (e: IllegalArgumentException) {
        val reason = e.message.orEmpty()
        val (field, message) =
            when {
                "IBGE" in reason && "UF" in reason -> {
                    "address.ibgeCityCode" to "Endereço inválido: o código IBGE do município não é da UF informada."
                }

                "IBGE" in reason -> {
                    "address.ibgeCityCode" to "Endereço inválido: o código IBGE tem 7 dígitos."
                }

                "CEP" in reason -> {
                    "address.postalCode" to "Endereço inválido: o CEP tem 8 dígitos."
                }

                else -> {
                    "address" to "Endereço inválido: logradouro, número, bairro e município são obrigatórios."
                }
            }
        throw InvalidInputException(
            GuestsErrorCodes.INVALID_ADDRESS,
            message,
            // Sem a causa: a mensagem do Address repete o CEP ou o código recebido.
            listOf(FieldViolation(field, GuestsErrorCodes.INVALID_ADDRESS.value, message)),
        )
    }

internal fun GuestDocument.view() = GuestDocumentView(id, type, issuingCountry, masked, createdAt)

internal fun Guest.view() =
    GuestView(
        id = id,
        fullName = fullName,
        socialName = socialName,
        nationality = nationality,
        birthDate = birthDate,
        gender = gender,
        email = email,
        phone = phone,
        residenceCountry = residenceCountry,
        address = address,
        preferences = preferences,
        vip = vip,
        notes = notes,
        companyId = companyId,
        restriction =
            if (restricted) {
                RestrictionView(checkNotNull(restrictionReason), checkNotNull(restrictedAt), restrictedBy)
            } else {
                null
            },
        documents = documents.map { it.view() },
        hasSensitiveData = hasSensitiveData,
        createdAt = createdAt,
        updatedAt = updatedAt,
        version = checkNotNull(version),
    )

internal fun Company.view() =
    CompanyView(
        id = id,
        legalName = legalName,
        tradeName = tradeName,
        cnpj = cnpj,
        stateRegistration = stateRegistration,
        municipalRegistration = municipalRegistration,
        address = address,
        contactName = contactName,
        phone = phone,
        email = email,
        billingEmail = billingEmail,
        creditLimit = creditLimit,
        active = active,
        createdAt = createdAt,
        updatedAt = updatedAt,
        version = checkNotNull(version),
    )

@Configuration(proxyBeanMethods = false)
class GuestsPermissionsConfiguration {
    @Bean
    fun guestsPermissions(): ModulePermissions = GuestsPermissions
}
