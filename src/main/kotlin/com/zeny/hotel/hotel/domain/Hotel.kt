package com.zeny.hotel.hotel.domain

import com.zeny.hotel.hotel.HotelErrorCodes
import com.zeny.hotel.hotel.HotelProfile
import com.zeny.hotel.hotel.TaxRegime
import com.zeny.hotel.shared.Address
import com.zeny.hotel.shared.Cnpj
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UuidV7
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Id do hotel (agregado [Hotel]); as outras partes da configuração usam o mesmo id. */
@JvmInline
value class HotelId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): HotelId = HotelId(UuidV7.generate())
    }
}

/**
 * O hotel, único (SPEC §8.2.2, RF-CFG-01): dados cadastrais, endereço com IBGE, fuso, regime
 * tributário e logotipo. Nasce no primeiro boot só com o fuso padrão (`America/Sao_Paulo`,
 * RNF-14), ainda não configurado ([configured] falso), e o administrador completa os dados. O
 * banco garante que existe no máximo um.
 */
@Entity
@Table(name = "hotel", schema = "hotel")
@Suppress("LongParameterList") // os campos cadastrais do hotel (RF-CFG-01)
class Hotel private constructor(
    @Id
    val id: HotelId,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
    timezone: ZoneId,
) {
    @Column(name = "legal_name")
    var legalName: String? = null
        protected set

    @Column(name = "trade_name")
    var tradeName: String? = null
        protected set

    var cnpj: Cnpj? = null
        protected set

    @Column(name = "municipal_registration")
    var municipalRegistration: String? = null
        protected set

    var cadastur: String? = null
        protected set

    @Embedded
    var address: Address? = null
        protected set

    @Column(name = "time_zone_id", nullable = false)
    private var timezoneId: String = timezone.id

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_regime")
    var taxRegime: TaxRegime? = null
        protected set

    /** Id do arquivo do logotipo no módulo `files` (H8). */
    @Column(name = "logo_file_id")
    var logoFileId: UUID? = null
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    @Version
    var version: Long? = null
        protected set

    val timezone: ZoneId get() = ZoneId.of(timezoneId)

    /** Se os dados cadastrais já foram informados. */
    val configured: Boolean get() = legalName != null

    /** Os dados cadastrais, se já configurados. */
    fun profile(): HotelProfile? {
        if (!configured) return null
        return HotelProfile(
            legalName = checkNotNull(legalName),
            tradeName = checkNotNull(tradeName),
            cnpj = checkNotNull(cnpj),
            municipalRegistration = municipalRegistration,
            cadastur = cadastur,
            address = checkNotNull(address),
            timezone = timezone,
            taxRegime = checkNotNull(taxRegime),
            logoFileId = logoFileId,
        )
    }

    /**
     * Grava os dados cadastrais (RF-CFG-01). Nomes obrigatórios; inscrição municipal e Cadastur em
     * branco viram ausentes. Devolve `false` se nada mudou. Mudar o fuso não muda a data operacional.
     */
    fun configure(
        profile: HotelProfile,
        now: Instant,
    ): Boolean {
        val normalized = normalize(profile)
        if (normalized == profile()) return false
        legalName = normalized.legalName
        tradeName = normalized.tradeName
        cnpj = normalized.cnpj
        municipalRegistration = normalized.municipalRegistration
        cadastur = normalized.cadastur
        address = normalized.address
        timezoneId = HotelTimezones.require(normalized.timezone).id
        taxRegime = normalized.taxRegime
        logoFileId = normalized.logoFileId
        updatedAt = now
        return true
    }

    companion object {
        /** Fuso padrão (RNF-14). */
        val DEFAULT_TIMEZONE: ZoneId = ZoneId.of("America/Sao_Paulo")

        const val NAME_MAX_LENGTH = 200
        const val REGISTRATION_MAX_LENGTH = 30

        fun create(
            timezone: ZoneId,
            now: Instant,
        ): Hotel = Hotel(HotelId.new(), now, HotelTimezones.require(timezone))

        private fun normalize(profile: HotelProfile): HotelProfile =
            profile.copy(
                legalName = name(profile.legalName, "legalName"),
                tradeName = name(profile.tradeName, "tradeName"),
                municipalRegistration = registration(profile.municipalRegistration, "municipalRegistration"),
                cadastur = registration(profile.cadastur, "cadastur"),
            )

        private fun name(
            value: String,
            field: String,
        ): String {
            val trimmed = value.trim()
            rule(trimmed.isNotEmpty() && trimmed.length <= NAME_MAX_LENGTH, HotelErrorCodes.HOTEL_DATA_INVALID, field) {
                "Informe o nome, com até $NAME_MAX_LENGTH caracteres."
            }
            return trimmed
        }

        private fun registration(
            value: String?,
            field: String,
        ): String? {
            val trimmed = value?.trim()?.ifEmpty { null } ?: return null
            rule(trimmed.length <= REGISTRATION_MAX_LENGTH, HotelErrorCodes.HOTEL_DATA_INVALID, field) {
                "Use até $REGISTRATION_MAX_LENGTH caracteres."
            }
            return trimmed
        }
    }
}

/** Fusos aceitos para o hotel (RNF-14): de uma região do banco de fusos, nunca um deslocamento fixo. */
object HotelTimezones {
    /** O fuso de [id]; desconhecido, deslocamento fixo ou sem regras de região: `400 INVALID_TIMEZONE`. */
    fun parse(id: String): ZoneId {
        val zone =
            try {
                ZoneId.of(id.trim())
            } catch (_: DateTimeException) {
                null
            }
        return require(zone)
    }

    /** Confere [zone] (nulo é recusado). */
    fun require(zone: ZoneId?): ZoneId {
        rule(
            zone != null && zone.id in ZoneId.getAvailableZoneIds() && !zone.rules.isFixedOffset,
            HotelErrorCodes.INVALID_TIMEZONE,
            "timezone",
        ) { "Fuso desconhecido. Use um fuso de região, como America/Sao_Paulo." }
        return checkNotNull(zone)
    }
}

/** Os horários da configuração são em horas e minutos: segundos ou frações dão `400 TIME_NOT_WHOLE_MINUTE`. */
internal fun requireWholeMinute(
    time: LocalTime?,
    field: String,
) {
    rule(time == null || (time.second == 0 && time.nano == 0), HotelErrorCodes.TIME_NOT_WHOLE_MINUTE, field) {
        "Informe o horário em horas e minutos, sem segundos."
    }
}

/**
 * Regra de validação da configuração: se [condition] falhar, `400` com [code] e o campo [field]
 * (nome do campo na API) em `errors[]`.
 */
internal inline fun rule(
    condition: Boolean,
    code: ErrorCode,
    field: String,
    message: () -> String,
) {
    if (!condition) {
        val text = message()
        throw InvalidInputException(code, text, listOf(FieldViolation(field, code.value, text)))
    }
}
