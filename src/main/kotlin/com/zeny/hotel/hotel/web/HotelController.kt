package com.zeny.hotel.hotel.web

import com.zeny.hotel.hotel.ChildAgeBand
import com.zeny.hotel.hotel.EarlyLateTerms
import com.zeny.hotel.hotel.HotelProfile
import com.zeny.hotel.hotel.OperationalSettings
import com.zeny.hotel.hotel.PolicySettings
import com.zeny.hotel.hotel.TaxRegime
import com.zeny.hotel.hotel.TaxSettings
import com.zeny.hotel.hotel.application.AddressCommand
import com.zeny.hotel.hotel.application.HotelCommand
import com.zeny.hotel.hotel.application.HotelSettingsService
import com.zeny.hotel.hotel.application.HotelView
import com.zeny.hotel.hotel.application.Versioned
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.BrazilianState
import com.zeny.hotel.shared.Cnpj
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Configuração do hotel único (RF-CFG-01 a 05): dados, políticas, taxas e parâmetros, cada parte
 * com o seu `ETag`. Consultar exige `hotel.settings.read`; editar, `hotel.settings.manage` e o
 * `If-Match` da última leitura (ausente: 428; outra versão: 412). A data operacional é visível a
 * qualquer usuário autenticado e não avança por nenhum endpoint (só no fechamento do dia).
 */
@ApiV1Controller
@RequestMapping("/hotel")
@Tag(name = "hotel", description = "Configuração do hotel e data operacional.")
@Suppress("TooManyFunctions") // consulta e edição de cada parte da configuração
class HotelController(
    private val settings: HotelSettingsService,
) {
    @GetMapping("/business-date")
    @Operation(
        operationId = "getBusinessDate",
        summary = "Consultar a data operacional",
        description =
            "A data operacional corrente (RN-01) e o fuso do hotel, para todo usuário autenticado (sem permissão). " +
                "Só avança no fechamento do dia.",
    )
    fun businessDate(): BusinessDateResponse {
        val view = settings.businessDate()
        return BusinessDateResponse(view.businessDate, view.timezone.id)
    }

    @GetMapping
    @Operation(
        operationId = "getHotel",
        summary = "Consultar os dados do hotel",
        description =
            "Exige `hotel.settings.read`. Antes da configuração inicial, `configured` é falso e só o fuso vem " +
                "preenchido. O `ETag` vai no `If-Match` da edição.",
    )
    fun hotel(): ResponseEntity<HotelResponse> = ok(settings.hotel(), HotelResponse::of)

    @PutMapping
    @Operation(
        operationId = "updateHotel",
        summary = "Editar os dados do hotel",
        description =
            "Exige `hotel.settings.manage` e o `If-Match`. CNPJ com dígito verificador errado: 400 " +
                "`VALIDATION_FAILED` (campo `cnpj`, `INVALID_FORMAT`). Código IBGE de outra UF: 400 " +
                "`INVALID_ADDRESS`. Fuso desconhecido ou de deslocamento fixo: 400 `INVALID_TIMEZONE`. O logotipo é " +
                "o id de um anexo enviado antes em `POST /files` com a finalidade `hotel.logo`; inexistente ou de " +
                "outra finalidade: 422 `LOGO_FILE_INVALID`. Mudar o fuso não muda a data operacional.",
    )
    fun updateHotel(
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: HotelRequest,
    ): ResponseEntity<HotelResponse> =
        ok(settings.updateHotel(ETags.expectedVersion(ifMatch), request.toCommand()), HotelResponse::of)

    @GetMapping("/policies")
    @Operation(
        operationId = "getHotelPolicies",
        summary = "Consultar as políticas operacionais",
        description = "Exige `hotel.settings.read`. O `ETag` vai no `If-Match` da edição.",
    )
    fun policies(): ResponseEntity<PolicySettingsDto> = ok(settings.policy(), PolicySettingsDto::of)

    @PutMapping("/policies")
    @Operation(
        operationId = "updateHotelPolicies",
        summary = "Editar as políticas operacionais",
        description =
            "Troca a política inteira. Exige `hotel.settings.manage` e o `If-Match`. Pela Portaria MTur " +
                "28/2025 (RN-11): check-out antes do check-in, no mesmo dia (400 `CHECK_TIMES_INCOHERENT`); " +
                "no máximo 3 horas entre eles e de janela de arrumação (400 `CLEANING_WINDOW_TOO_LONG`); " +
                "janela que cabe entre eles (400 `CLEANING_WINDOW_INVALID`); early check-in antes do " +
                "check-in e late check-out depois do check-out, com preço informado (400 " +
                "`EARLY_CHECK_IN_INVALID`/`LATE_CHECK_OUT_INVALID`); faixas etárias contíguas de 0 à idade " +
                "limite (400 `CHILD_AGES_INVALID`); texto das confirmações obrigatório; horários em horas e " +
                "minutos, sem segundos (400 `TIME_NOT_WHOLE_MINUTE`).",
    )
    fun updatePolicies(
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: PolicySettingsDto,
    ): ResponseEntity<PolicySettingsDto> =
        ok(settings.updatePolicy(ETags.expectedVersion(ifMatch), request.toSettings()), PolicySettingsDto::of)

    @GetMapping("/tax-settings")
    @Operation(
        operationId = "getHotelTaxSettings",
        summary = "Consultar as taxas",
        description = "Exige `hotel.settings.read`. O `ETag` vai no `If-Match` da edição.",
    )
    fun taxSettings(): ResponseEntity<TaxSettingsDto> = ok(settings.taxSettings(), TaxSettingsDto::of)

    @PutMapping("/tax-settings")
    @Operation(
        operationId = "updateHotelTaxSettings",
        summary = "Editar as taxas",
        description =
            "Exige `hotel.settings.manage` e o `If-Match`. Taxa de serviço padrão de 0% a 100% (400 " +
                "`SERVICE_CHARGE_RATE_INVALID`); ISS de 0% a 5% (400 `ISS_RATE_INVALID`); taxa de turismo nula " +
                "(não cobrada) ou positiva (400 `TOURISM_FEE_INVALID`).",
    )
    fun updateTaxSettings(
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: TaxSettingsDto,
    ): ResponseEntity<TaxSettingsDto> =
        ok(settings.updateTaxSettings(ETags.expectedVersion(ifMatch), request.toSettings()), TaxSettingsDto::of)

    @GetMapping("/operational-settings")
    @Operation(
        operationId = "getHotelOperationalSettings",
        summary = "Consultar os parâmetros operacionais",
        description = "Exige `hotel.settings.read`. O `ETag` vai no `If-Match` da edição.",
    )
    fun operationalSettings(): ResponseEntity<OperationalSettingsDto> =
        ok(settings.operationalSettings(), OperationalSettingsDto::of)

    @PutMapping("/operational-settings")
    @Operation(
        operationId = "updateHotelOperationalSettings",
        summary = "Editar os parâmetros operacionais",
        description =
            "Exige `hotel.settings.manage` e o `If-Match`. Valor fora da faixa: 400 `OPERATIONAL_SETTING_INVALID` " +
                "com o campo em `errors[]`; horário com segundos: 400 `TIME_NOT_WHOLE_MINUTE`.",
    )
    fun updateOperationalSettings(
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: OperationalSettingsDto,
    ): ResponseEntity<OperationalSettingsDto> =
        ok(
            settings.updateOperationalSettings(ETags.expectedVersion(ifMatch), request.toSettings()),
            OperationalSettingsDto::of,
        )

    private fun <T, R : Any> ok(
        versioned: Versioned<T>,
        mapper: (Versioned<T>) -> R,
    ): ResponseEntity<R> = ResponseEntity.ok().eTag(ETags.of(versioned.version)).body(mapper(versioned))
}

@Schema(description = "A data operacional corrente do hotel.")
data class BusinessDateResponse(
    @field:Schema(description = "Data de negócio a que lançamentos e diárias se referem (YYYY-MM-DD).")
    val businessDate: LocalDate,
    @field:Schema(
        description = "Fuso do hotel (IANA), onde os instantes em UTC são exibidos.",
        example = "America/Sao_Paulo",
    )
    val timezone: String,
)

@Schema(description = "Endereço no Brasil com o código IBGE do município.")
data class AddressDto(
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
)

@Schema(description = "Dados cadastrais do hotel.")
data class HotelRequest(
    @field:NotBlank @field:Size(max = 200)
    val legalName: String,
    @field:NotBlank @field:Size(max = 200)
    val tradeName: String,
    @field:Schema(description = "CNPJ numérico ou alfanumérico, com ou sem pontuação.", example = "11.222.333/0001-81")
    val cnpj: Cnpj,
    @field:Size(max = 30)
    val municipalRegistration: String?,
    @field:Schema(description = "Número Cadastur.")
    @field:Size(max = 30)
    val cadastur: String?,
    @field:Valid
    val address: AddressDto,
    @field:Schema(description = "Fuso de região (IANA).", example = "America/Sao_Paulo")
    @field:NotBlank
    @field:Size(max = 64)
    val timezone: String,
    val taxRegime: TaxRegime,
    @field:Schema(description = "Id do anexo do logotipo (finalidade `hotel.logo`, enviado antes).", nullable = true)
    val logoFileId: UUID?,
) {
    fun toCommand() =
        HotelCommand(
            legalName = legalName,
            tradeName = tradeName,
            cnpj = cnpj,
            municipalRegistration = municipalRegistration,
            cadastur = cadastur,
            address =
                AddressCommand(
                    street = address.street,
                    number = address.number,
                    complement = address.complement,
                    district = address.district,
                    city = address.city,
                    state = address.state,
                    postalCode = address.postalCode,
                    ibgeCityCode = address.ibgeCityCode,
                ),
            timezone = timezone,
            taxRegime = taxRegime,
            logoFileId = logoFileId,
        )
}

@Schema(description = "Os dados do hotel. Antes da configuração inicial, só o fuso vem preenchido.")
data class HotelResponse(
    val id: UUID,
    @field:Schema(description = "Se os dados cadastrais já foram informados.")
    val configured: Boolean,
    val legalName: String?,
    val tradeName: String?,
    val cnpj: Cnpj?,
    val municipalRegistration: String?,
    val cadastur: String?,
    val address: AddressDto?,
    val timezone: String,
    val taxRegime: TaxRegime?,
    val logoFileId: UUID?,
    val updatedAt: Instant,
) {
    companion object {
        fun of(versioned: Versioned<HotelView>): HotelResponse {
            val view = versioned.value
            val profile: HotelProfile? = view.profile
            return HotelResponse(
                id = view.id,
                configured = profile != null,
                legalName = profile?.legalName,
                tradeName = profile?.tradeName,
                cnpj = profile?.cnpj,
                municipalRegistration = profile?.municipalRegistration,
                cadastur = profile?.cadastur,
                address =
                    profile?.address?.let {
                        AddressDto(
                            it.street,
                            it.number,
                            it.complement,
                            it.district,
                            it.city,
                            it.state,
                            it.postalCode,
                            it.ibgeCityCode,
                        )
                    },
                timezone = view.timezone.id,
                taxRegime = profile?.taxRegime,
                logoFileId = profile?.logoFileId,
                updatedAt = versioned.updatedAt,
            )
        }
    }
}

@Schema(description = "Early check-in ou late check-out oferecido, com o preço informado antes (RN-11).")
data class EarlyLateDto(
    @field:Schema(
        description = "Early: horário mais cedo de entrada; late: horário mais tarde de saída.",
        example = "10:00",
    )
    val limitTime: LocalTime,
    val fee: Money,
)

@Schema(description = "Faixa etária de criança, em anos completos, inclusive nas duas pontas.")
data class ChildAgeBandDto(
    val minAge: Int,
    val maxAge: Int,
    @field:NotBlank @field:Size(max = 50)
    val label: String,
)

@Schema(description = "Políticas operacionais do hotel (RF-CFG-02, Portaria MTur 28/2025).")
data class PolicySettingsDto(
    @field:Schema(example = "14:00")
    val checkInTime: LocalTime,
    @field:Schema(example = "12:00")
    val checkOutTime: LocalTime,
    @field:Schema(description = "Janela de arrumação entre o check-out e o check-in, em minutos (até 180).")
    val cleaningWindowMinutes: Int,
    @field:Schema(description = "Nulo se o hotel não oferece early check-in.", nullable = true)
    @field:Valid
    val earlyCheckIn: EarlyLateDto?,
    @field:Schema(description = "Nulo se o hotel não oferece late check-out.", nullable = true)
    @field:Valid
    val lateCheckOut: EarlyLateDto?,
    @field:Schema(description = "Idade (anos completos) até a qual o hóspede é criança (0 a 17).")
    val childMaxAge: Int,
    @field:Schema(description = "Faixas de 0 a childMaxAge, contíguas e em ordem; vazia se não diferencia.")
    @field:Size(max = 10)
    val childAgeBands: List<@Valid ChildAgeBandDto>,
    @field:Schema(description = "Texto obrigatório das confirmações: regras da diária, horários e arrumação.")
    @field:NotBlank
    @field:Size(max = 4000)
    val stayPolicyText: String,
    @field:Schema(description = "Texto complementar das confirmações (cancelamento, crianças, animais…).")
    @field:Size(max = 4000)
    val additionalPolicyText: String?,
) {
    fun toSettings() =
        PolicySettings(
            checkInTime = checkInTime,
            checkOutTime = checkOutTime,
            cleaningWindow = Duration.ofMinutes(cleaningWindowMinutes.toLong()),
            earlyCheckIn = earlyCheckIn?.let { EarlyLateTerms(it.limitTime, it.fee) },
            lateCheckOut = lateCheckOut?.let { EarlyLateTerms(it.limitTime, it.fee) },
            childMaxAge = childMaxAge,
            childAgeBands = childAgeBands.map { ChildAgeBand(it.minAge, it.maxAge, it.label) },
            stayPolicyText = stayPolicyText,
            additionalPolicyText = additionalPolicyText,
        )

    companion object {
        fun of(versioned: Versioned<PolicySettings>): PolicySettingsDto {
            val policy = versioned.value
            return PolicySettingsDto(
                checkInTime = policy.checkInTime,
                checkOutTime = policy.checkOutTime,
                cleaningWindowMinutes = policy.cleaningWindow.toMinutes().toInt(),
                earlyCheckIn = policy.earlyCheckIn?.let { EarlyLateDto(it.limitTime, it.fee) },
                lateCheckOut = policy.lateCheckOut?.let { EarlyLateDto(it.limitTime, it.fee) },
                childMaxAge = policy.childMaxAge,
                childAgeBands = policy.childAgeBands.map { ChildAgeBandDto(it.minAge, it.maxAge, it.label) },
                stayPolicyText = policy.stayPolicyText,
                additionalPolicyText = policy.additionalPolicyText,
            )
        }
    }
}

@Schema(description = "Taxas do hotel (RF-CFG-03).")
data class TaxSettingsDto(
    @field:Schema(description = "Taxa de serviço padrão, em pontos percentuais (0 a 100).", example = "10.0000")
    val serviceChargeRate: Percent,
    @field:Schema(description = "Alíquota de ISS, em pontos percentuais (0 a 5).", example = "5.0000")
    val issRate: Percent,
    @field:Schema(description = "Taxa municipal de turismo por pessoa/noite; nula se não é cobrada.", nullable = true)
    val tourismFeePerPersonNight: Money?,
) {
    fun toSettings() = TaxSettings(serviceChargeRate, issRate, tourismFeePerPersonNight)

    companion object {
        fun of(versioned: Versioned<TaxSettings>): TaxSettingsDto {
            val taxes = versioned.value
            return TaxSettingsDto(taxes.serviceChargeRate, taxes.issRate, taxes.tourismFeePerPersonNight)
        }
    }
}

@Schema(description = "Parâmetros operacionais do hotel (RF-CFG-04).")
data class OperationalSettingsDto(
    @field:Schema(description = "Horário do fechamento do dia, no fuso do hotel.", example = "03:00")
    val endOfDayTime: LocalTime,
    @field:Schema(description = "Validade da pré-reserva, em horas (1 a 720).")
    val preReservationExpiryHours: Int,
    @field:Schema(description = "Antecedência do alerta de pré-reserva perto do vencimento, em horas.")
    val preReservationAlertLeadHours: Int,
    @field:Schema(description = "Prazo de guarda de achados e perdidos, em dias (1 a 1825).")
    val lostAndFoundRetentionDays: Int,
) {
    fun toSettings() =
        OperationalSettings(
            endOfDayTime = endOfDayTime,
            preReservationExpiry = Duration.ofHours(preReservationExpiryHours.toLong()),
            preReservationAlertLead = Duration.ofHours(preReservationAlertLeadHours.toLong()),
            lostAndFoundRetentionDays = lostAndFoundRetentionDays,
        )

    companion object {
        fun of(versioned: Versioned<OperationalSettings>): OperationalSettingsDto {
            val operations = versioned.value
            return OperationalSettingsDto(
                endOfDayTime = operations.endOfDayTime,
                preReservationExpiryHours = operations.preReservationExpiry.toHours().toInt(),
                preReservationAlertLeadHours = operations.preReservationAlertLead.toHours().toInt(),
                lostAndFoundRetentionDays = operations.lostAndFoundRetentionDays,
            )
        }
    }
}
