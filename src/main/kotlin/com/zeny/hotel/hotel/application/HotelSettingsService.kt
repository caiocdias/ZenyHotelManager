package com.zeny.hotel.hotel.application

import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.FilesApi
import com.zeny.hotel.files.ModuleFilePurposes
import com.zeny.hotel.hotel.HotelErrorCodes
import com.zeny.hotel.hotel.HotelFilePurposes
import com.zeny.hotel.hotel.HotelPermissions
import com.zeny.hotel.hotel.HotelProfile
import com.zeny.hotel.hotel.OperationalSettings
import com.zeny.hotel.hotel.PolicySettings
import com.zeny.hotel.hotel.TaxRegime
import com.zeny.hotel.hotel.TaxSettings
import com.zeny.hotel.hotel.domain.Hotel
import com.zeny.hotel.hotel.domain.HotelTimezones
import com.zeny.hotel.shared.Address
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BrazilianState
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.Cnpj
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.requireCurrentVersion
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Uma parte da configuração com a versão (o `ETag`) e a última alteração. */
data class Versioned<T>(
    val value: T,
    val version: Long,
    val updatedAt: Instant,
)

/** Os dados do hotel como a API os mostra: antes de configurados, só o fuso. */
data class HotelView(
    val id: UUID,
    val profile: HotelProfile?,
    val timezone: ZoneId,
)

/** A data operacional corrente e o fuso do hotel (RF-CFG-05, RNF-14). */
data class BusinessDateView(
    val businessDate: LocalDate,
    val timezone: ZoneId,
)

/** Endereço como chega da API, ainda sem a validação do [Address]. */
data class AddressCommand(
    val street: String,
    val number: String,
    val complement: String?,
    val district: String,
    val city: String,
    val state: BrazilianState,
    val postalCode: String,
    val ibgeCityCode: String,
)

/** Dados do hotel como chegam da API. */
data class HotelCommand(
    val legalName: String,
    val tradeName: String,
    val cnpj: Cnpj,
    val municipalRegistration: String?,
    val cadastur: String?,
    val address: AddressCommand,
    val timezone: String,
    val taxRegime: TaxRegime,
    val logoFileId: UUID?,
)

/**
 * Configuração do hotel pela API (RF-CFG-01 a 05): consultar exige `hotel.settings.read`; editar,
 * `hotel.settings.manage`, a versão da última leitura (`If-Match`) e passa pela trilha de auditoria.
 * A data operacional é visível a qualquer usuário autenticado e não muda por aqui: só pelo
 * [HotelApiService.advanceBusinessDate]. Um logotipo novo precisa ser um anexo da finalidade
 * [HotelFilePurposes.LOGO] (H8), conferido pela [FilesApi] e vinculado ao hotel.
 */
@Service
@Suppress("TooManyFunctions") // consulta e edição de cada parte da configuração
class HotelSettingsService(
    private val store: HotelStore,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val files: FilesApi,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun businessDate(): BusinessDateView {
        val day = store.businessDay() ?: notInitialized()
        val hotel = store.hotel() ?: notInitialized()
        return BusinessDateView(day.current, hotel.timezone)
    }

    @Transactional(readOnly = true)
    fun hotel(): Versioned<HotelView> {
        authorization.require(HotelPermissions.SETTINGS_READ)
        return view(store.hotel() ?: notInitialized())
    }

    @Audited(HotelAudit.UPDATE_HOTEL)
    @Transactional
    fun updateHotel(
        expectedVersion: Long,
        command: HotelCommand,
    ): Versioned<HotelView> {
        authorization.require(HotelPermissions.SETTINGS_MANAGE)
        val hotel = store.hotel() ?: notInitialized()
        requireCurrentVersion(expectedVersion, checkNotNull(hotel.version))
        val newLogo = command.logoFileId?.takeIf { it != hotel.logoFileId }?.let(::FileId)
        newLogo?.let(::requireLogoFile)
        audit.track(HotelAudit.HOTEL, hotel.id.value) { hotel.auditState() }
        if (hotel.configure(command.toProfile(), clock.instant())) {
            store.flush()
            log.info("Dados do hotel alterados")
        }
        newLogo?.let { files.attach(it, HotelFilePurposes.LOGO, hotel.id.value.toString()) }
        return view(hotel)
    }

    /**
     * Só um logotipo novo é conferido (manter o atual não falha): precisa existir e ser da finalidade
     * de logotipo; senão, `422 LOGO_FILE_INVALID`.
     */
    private fun requireLogoFile(id: FileId) {
        val file = files.find(id)
        if (file == null || file.purpose != HotelFilePurposes.LOGO) {
            throw BusinessRuleException(
                HotelErrorCodes.LOGO_FILE_INVALID,
                "O logotipo precisa ser uma imagem enviada com a finalidade ${HotelFilePurposes.LOGO}.",
            )
        }
    }

    @Transactional(readOnly = true)
    fun policy(): Versioned<PolicySettings> {
        authorization.require(HotelPermissions.SETTINGS_READ)
        val policy = store.policy() ?: notInitialized()
        return Versioned(policy.settings(), checkNotNull(policy.version), policy.updatedAt)
    }

    @Audited(HotelAudit.UPDATE_POLICY)
    @Transactional
    fun updatePolicy(
        expectedVersion: Long,
        settings: PolicySettings,
    ): Versioned<PolicySettings> {
        authorization.require(HotelPermissions.SETTINGS_MANAGE)
        val policy = store.policy() ?: notInitialized()
        requireCurrentVersion(expectedVersion, checkNotNull(policy.version))
        audit.track(HotelAudit.POLICY, policy.hotelId.value) { policy.settings().auditState() }
        if (policy.update(settings, clock.instant())) store.flush()
        return Versioned(policy.settings(), checkNotNull(policy.version), policy.updatedAt)
    }

    @Transactional(readOnly = true)
    fun taxSettings(): Versioned<TaxSettings> {
        authorization.require(HotelPermissions.SETTINGS_READ)
        val taxes = store.taxes() ?: notInitialized()
        return Versioned(taxes.settings(), checkNotNull(taxes.version), taxes.updatedAt)
    }

    @Audited(HotelAudit.UPDATE_TAX_SETTINGS)
    @Transactional
    fun updateTaxSettings(
        expectedVersion: Long,
        settings: TaxSettings,
    ): Versioned<TaxSettings> {
        authorization.require(HotelPermissions.SETTINGS_MANAGE)
        val taxes = store.taxes() ?: notInitialized()
        requireCurrentVersion(expectedVersion, checkNotNull(taxes.version))
        audit.track(HotelAudit.TAX_SETTINGS, taxes.hotelId.value) { taxes.settings().auditState() }
        if (taxes.update(settings, clock.instant())) store.flush()
        return Versioned(taxes.settings(), checkNotNull(taxes.version), taxes.updatedAt)
    }

    @Transactional(readOnly = true)
    fun operationalSettings(): Versioned<OperationalSettings> {
        authorization.require(HotelPermissions.SETTINGS_READ)
        val operations = store.operations() ?: notInitialized()
        return Versioned(operations.settings(), checkNotNull(operations.version), operations.updatedAt)
    }

    @Audited(HotelAudit.UPDATE_OPERATIONAL_SETTINGS)
    @Transactional
    fun updateOperationalSettings(
        expectedVersion: Long,
        settings: OperationalSettings,
    ): Versioned<OperationalSettings> {
        authorization.require(HotelPermissions.SETTINGS_MANAGE)
        val operations = store.operations() ?: notInitialized()
        requireCurrentVersion(expectedVersion, checkNotNull(operations.version))
        audit.track(HotelAudit.OPERATIONAL_SETTINGS, operations.hotelId.value) { operations.settings().auditState() }
        if (operations.update(settings, clock.instant())) store.flush()
        return Versioned(operations.settings(), checkNotNull(operations.version), operations.updatedAt)
    }

    private fun view(hotel: Hotel) =
        Versioned(
            HotelView(hotel.id.value, hotel.profile(), hotel.timezone),
            checkNotNull(hotel.version),
            hotel.updatedAt,
        )

    private fun HotelCommand.toProfile() =
        HotelProfile(
            legalName = legalName,
            tradeName = tradeName,
            cnpj = cnpj,
            municipalRegistration = municipalRegistration,
            cadastur = cadastur,
            address = address.toAddress(),
            timezone = HotelTimezones.parse(timezone),
            taxRegime = taxRegime,
            logoFileId = logoFileId,
        )

    /** O [Address] confere o CEP, o código IBGE e se ele é da UF; a recusa vira `400 INVALID_ADDRESS`. */
    private fun AddressCommand.toAddress(): Address =
        try {
            Address(
                street,
                number,
                complement?.trim()?.ifEmpty { null },
                district,
                city,
                state,
                postalCode,
                ibgeCityCode,
            )
        } catch (e: IllegalArgumentException) {
            val reason = e.message.orEmpty()
            val field =
                when {
                    "IBGE" in reason -> "address.ibgeCityCode"
                    "CEP" in reason -> "address.postalCode"
                    else -> "address"
                }
            val message = "Endereço inválido: $reason."
            throw InvalidInputException(
                HotelErrorCodes.INVALID_ADDRESS,
                message,
                listOf(FieldViolation(field, HotelErrorCodes.INVALID_ADDRESS.value, message)),
                e,
            )
        }
}

@Configuration(proxyBeanMethods = false)
class HotelPermissionsConfiguration {
    @Bean
    fun hotelPermissions(): ModulePermissions = HotelPermissions

    @Bean
    fun hotelFilePurposes(): ModuleFilePurposes = HotelFilePurposes
}
