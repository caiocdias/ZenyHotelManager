package com.zeny.hotel.fnrh.domain

import com.zeny.hotel.fnrh.FnrhFieldCodes
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.shared.FieldViolationCodes
import java.time.LocalDate
import java.time.Period
import java.util.Locale

/**
 * A validação local dos dados da FNRH, antes de qualquer chamada ao Serpro (M5 E07): campos obrigatórios, valores
 * dos domínios oficiais ([catalog]), países ISO, município e UF do IBGE e o responsável do menor de 18 anos. Os erros
 * nunca carregam o valor recusado ([FnrhFieldError]).
 */
class FnrhLocalValidator(
    private val catalog: FnrhDomainCatalog,
    private val municipalities: IbgeMunicipalities,
    private val countries: Set<String> = Locale.getISOCountries().toSet(),
) {
    fun validate(
        person: FnrhPersonData,
        guest: FnrhGuest,
        arrival: LocalDate,
        responsibleKnown: Boolean,
    ): List<FnrhFieldError> {
        val errors = mutableListOf<FnrhFieldError>()
        val add: (String, String) -> Unit = { field, code -> errors.add(FnrhFieldError(field, code)) }
        validateIdentity(person, add)
        validateDomains(person, add)
        validateResidence(person, add)
        validateStay(guest, add)
        val birthDate = person.birthDate
        if (birthDate != null && Period.between(birthDate, arrival).years < ADULT_AGE && !responsibleKnown) {
            add("guest.responsibleGuestId", FieldViolationCodes.REQUIRED)
        }
        return errors
    }

    private fun validateIdentity(
        person: FnrhPersonData,
        add: (String, String) -> Unit,
    ) {
        if (person.name.isBlank()) add("person.name", FieldViolationCodes.REQUIRED)
        val document = person.document
        when {
            document == null || document.number.isBlank() -> {
                add("person.document", FieldViolationCodes.REQUIRED)
            }

            document.type == FnrhDocumentType.CPF && !Cpf.isValid(document.number) -> {
                add("person.document", FieldViolationCodes.INVALID_VALUE)
            }
        }
        if (person.nationality != null && person.nationality !in countries) {
            add("person.nationality", FnrhFieldCodes.NOT_IN_DOMAIN)
        }
        if (person.birthDate == null) add("person.birthDate", FieldViolationCodes.REQUIRED)
    }

    private fun validateDomains(
        person: FnrhPersonData,
        add: (String, String) -> Unit,
    ) {
        required(person.gender, FnrhDomain.GENDERS, "person.gender", add)
        if (person.gender == GENDER_OTHER && person.genderDescription.isNullOrBlank()) {
            add("person.genderDescription", FieldViolationCodes.REQUIRED)
        }
        required(person.race, FnrhDomain.RACES, "person.race", add)
        val option = person.disabilityOption
        if (option != null && !catalog.contains(FnrhDomain.DISABILITY_OPTIONS, option)) {
            add("person.disabilityOption", FnrhFieldCodes.NOT_IN_DOMAIN)
        }
        val type = person.disabilityType
        if (type != null && !catalog.contains(FnrhDomain.DISABILITY_TYPES, type)) {
            add("person.disabilityType", FnrhFieldCodes.NOT_IN_DOMAIN)
        } else if (type == null && option == DISABILITY_YES) {
            add("person.disabilityType", FieldViolationCodes.REQUIRED)
        }
    }

    private fun validateResidence(
        person: FnrhPersonData,
        add: (String, String) -> Unit,
    ) {
        val country = person.residenceCountry
        if (country != null && country !in countries) add("person.residenceCountry", FnrhFieldCodes.NOT_IN_DOMAIN)
        if (country != BRAZIL) return
        val uf = person.cityIbge?.let(municipalities::ufOf)
        when {
            person.cityIbge == null -> add("person.cityIbge", FieldViolationCodes.REQUIRED)
            uf == null -> add("person.cityIbge", FnrhFieldCodes.NOT_IN_DOMAIN)
        }
        when {
            person.state.isNullOrBlank() -> add("person.state", FieldViolationCodes.REQUIRED)
            uf != null && person.state != uf -> add("person.state", FnrhFieldCodes.INCONSISTENT)
        }
        val postalCode = person.postalCode
        if (!postalCode.isNullOrBlank()) {
            if (!POSTAL_CODE.matches(postalCode)) add("person.postalCode", FieldViolationCodes.INVALID_VALUE)
            if (person.street.isNullOrBlank()) add("person.street", FieldViolationCodes.REQUIRED)
            if (person.number.isNullOrBlank()) add("person.number", FieldViolationCodes.REQUIRED)
            if (person.neighborhood.isNullOrBlank()) add("person.neighborhood", FieldViolationCodes.REQUIRED)
        }
    }

    private fun validateStay(
        guest: FnrhGuest,
        add: (String, String) -> Unit,
    ) {
        required(guest.travelReason, FnrhDomain.TRAVEL_REASONS, "guest.travelReason", add)
        required(guest.transportMode, FnrhDomain.TRANSPORT_MODES, "guest.transportMode", add)
    }

    private fun required(
        value: String?,
        domain: FnrhDomain,
        field: String,
        add: (String, String) -> Unit,
    ) {
        when {
            value.isNullOrBlank() -> add(field, FieldViolationCodes.REQUIRED)
            !catalog.contains(domain, value) -> add(field, FnrhFieldCodes.NOT_IN_DOMAIN)
        }
    }

    private companion object {
        const val ADULT_AGE = 18
        const val BRAZIL = "BR"
        const val GENDER_OTHER = "OUTRO"
        const val DISABILITY_YES = "SIM"
        val POSTAL_CODE = Regex("""\d{8}""")
    }
}
