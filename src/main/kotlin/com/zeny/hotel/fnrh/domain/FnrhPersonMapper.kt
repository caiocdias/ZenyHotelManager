package com.zeny.hotel.fnrh.domain

import com.zeny.hotel.guests.DocumentType
import com.zeny.hotel.guests.FnrhGuestData
import com.zeny.hotel.guests.FnrhGuestDocument
import com.zeny.hotel.guests.Gender
import com.zeny.hotel.guests.RaceColor

/**
 * Converte os dados do hóspede (`GuestsApi.fnrhData`) nos da pessoa da FNRH. Não inventa dado: o que o perfil não
 * guarda fica nulo e a [FnrhLocalValidator] acusa (D-E07-10 e D-E07-11).
 */
object FnrhPersonMapper {
    private val DISABILITY_OPTIONS_WITHOUT_TYPE = setOf("NAO", "NAOINFORMAR")

    fun toPerson(guest: FnrhGuestData): FnrhPersonData {
        val address = guest.address
        val disability = guest.disability
        val withType = disability != null && disability !in DISABILITY_OPTIONS_WITHOUT_TYPE
        return FnrhPersonData(
            name = guest.fullName,
            socialName = guest.socialName,
            nationality = guest.nationality,
            gender = guest.gender?.let(::genderId),
            genderDescription = guest.genderDescription.takeIf { guest.gender == Gender.OTHER },
            birthDate = guest.birthDate,
            race = guest.raceColor?.let(::raceId),
            disabilityOption = if (withType) "SIM" else disability,
            disabilityType = disability.takeIf { withType },
            document = document(guest.documents),
            email = guest.email,
            phone = guest.phone,
            residenceCountry = guest.residenceCountry,
            postalCode = address?.postalCode,
            street = address?.street,
            number = address?.number,
            complement = address?.complement,
            neighborhood = address?.district,
            cityIbge = address?.ibgeCityCode?.toInt(),
            state = address?.state?.name,
        )
    }

    private fun document(documents: List<FnrhGuestDocument>): FnrhDocument? {
        val cpf = documents.firstOrNull { it.type == DocumentType.CPF }
        val passport = documents.firstOrNull { it.type == DocumentType.PASSPORT }
        return when {
            cpf != null -> FnrhDocument(FnrhDocumentType.CPF, cpf.number)
            passport != null -> FnrhDocument(FnrhDocumentType.PASSPORT, passport.number)
            else -> null
        }
    }

    private fun genderId(gender: Gender): String =
        when (gender) {
            Gender.FEMALE -> "MULHER"
            Gender.MALE -> "HOMEM"
            Gender.OTHER -> "OUTRO"
            Gender.NOT_INFORMED -> "NAOINFORMADO"
        }

    private fun raceId(race: RaceColor): String =
        when (race) {
            RaceColor.WHITE -> "BRANCA"
            RaceColor.BLACK -> "PRETA"
            RaceColor.BROWN -> "PARDA"
            RaceColor.YELLOW -> "AMARELA"
            RaceColor.INDIGENOUS -> "INDIGENA"
            RaceColor.NOT_DECLARED -> "NAOINFORMAR"
        }
}
