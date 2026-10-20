package com.zeny.hotel.rates.web

import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.rates.CancellationPenaltyType
import com.zeny.hotel.rates.CancellationPolicyId
import com.zeny.hotel.rates.GuaranteePolicyId
import com.zeny.hotel.rates.GuaranteeType
import com.zeny.hotel.rates.MealPlan
import com.zeny.hotel.rates.PackageId
import com.zeny.hotel.rates.PackagePostingRhythm
import com.zeny.hotel.rates.PackagePricing
import com.zeny.hotel.rates.RatePlanCategory
import com.zeny.hotel.rates.SalesChannel
import com.zeny.hotel.rates.application.CancellationPolicyCommand
import com.zeny.hotel.rates.application.CancellationPolicyView
import com.zeny.hotel.rates.application.GuaranteePolicyCommand
import com.zeny.hotel.rates.application.GuaranteePolicyView
import com.zeny.hotel.rates.application.NewRatePlanCommand
import com.zeny.hotel.rates.application.PackageCommand
import com.zeny.hotel.rates.application.PackageView
import com.zeny.hotel.rates.application.RatePlanCommand
import com.zeny.hotel.rates.application.RatePlanView
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

private const val CODE_DESCRIPTION = "Letras, dígitos, `_` e `-`, até 20; guardado em maiúsculas."
private const val MAX_ROOM_TYPES = 50
private const val MAX_PACKAGES = 20
private const val MAX_CHANNELS = 3
private const val MAX_DEADLINE_HOURS = 8760L
private const val MAX_DAYS = 365L

// --- Planos tarifários ---------------------------------------------------------------------------------------------

@Schema(description = "Os dados editáveis de um plano tarifário (a categoria é fixa desde a criação).")
data class RatePlanUpdateRequest(
    @field:Schema(description = CODE_DESCRIPTION, example = "BAR")
    @field:NotBlank
    @field:Size(max = 20)
    val code: String?,
    @field:Schema(example = "Tarifa balcão")
    @field:NotBlank
    @field:Size(max = 80)
    val name: String?,
    @field:Schema(nullable = true)
    @field:Size(max = 1000)
    val description: String? = null,
    @field:NotNull
    val mealPlan: MealPlan?,
    @field:Schema(description = "Canais em que o plano é vendido (o conjunto inteiro, ao menos um).")
    @field:NotEmpty
    @field:Size(max = MAX_CHANNELS)
    val channels: List<SalesChannel> = emptyList(),
    @field:Schema(description = "Tipos de UH a que o plano se aplica (o conjunto inteiro, ao menos um).")
    @field:NotEmpty
    @field:Size(max = MAX_ROOM_TYPES)
    val roomTypeIds: List<UUID> = emptyList(),
    @field:Schema(description = "Pacotes do plano (o conjunto inteiro).")
    @field:Size(max = MAX_PACKAGES)
    val packageIds: List<UUID> = emptyList(),
    @field:NotNull
    val cancellationPolicyId: UUID?,
    @field:NotNull
    val guaranteePolicyId: UUID?,
) {
    fun toCommand() =
        RatePlanCommand(
            code = checkNotNull(code),
            name = checkNotNull(name),
            description = description,
            mealPlan = checkNotNull(mealPlan),
            channels = channels.toSet(),
            roomTypeIds = roomTypeIds.map(::RoomTypeId).toSet(),
            packageIds = packageIds.map(::PackageId).toSet(),
            cancellationPolicyId = CancellationPolicyId(checkNotNull(cancellationPolicyId)),
            guaranteePolicyId = GuaranteePolicyId(checkNotNull(guaranteePolicyId)),
        )
}

@Schema(description = "Um plano tarifário novo; a categoria não muda depois.")
data class RatePlanCreateRequest(
    @field:Schema(description = CODE_DESCRIPTION, example = "BAR")
    @field:NotBlank
    @field:Size(max = 20)
    val code: String?,
    @field:Schema(example = "Tarifa balcão")
    @field:NotBlank
    @field:Size(max = 80)
    val name: String?,
    @field:Schema(nullable = true)
    @field:Size(max = 1000)
    val description: String? = null,
    @field:NotNull
    val mealPlan: MealPlan?,
    @field:Schema(
        description =
            "Categoria (Q-15), fixa: `COURTESY` conta na ocupação e não no ADR; `HOUSE_USE` não conta em " +
                "nenhum dos dois.",
    )
    @field:NotNull
    val category: RatePlanCategory?,
    @field:Schema(description = "Canais em que o plano é vendido (ao menos um).")
    @field:NotEmpty
    @field:Size(max = MAX_CHANNELS)
    val channels: List<SalesChannel> = emptyList(),
    @field:Schema(description = "Tipos de UH a que o plano se aplica (ao menos um).")
    @field:NotEmpty
    @field:Size(max = MAX_ROOM_TYPES)
    val roomTypeIds: List<UUID> = emptyList(),
    @field:Schema(description = "Pacotes do plano.")
    @field:Size(max = MAX_PACKAGES)
    val packageIds: List<UUID> = emptyList(),
    @field:NotNull
    val cancellationPolicyId: UUID?,
    @field:NotNull
    val guaranteePolicyId: UUID?,
) {
    fun toCommand() =
        NewRatePlanCommand(
            checkNotNull(category),
            RatePlanUpdateRequest(
                code,
                name,
                description,
                mealPlan,
                channels,
                roomTypeIds,
                packageIds,
                cancellationPolicyId,
                guaranteePolicyId,
            ).toCommand(),
        )
}

@Schema(description = "Um plano tarifário.")
data class RatePlanResponse(
    val id: UUID,
    val code: String,
    val name: String,
    @field:Schema(nullable = true)
    val description: String?,
    @field:ExtensibleEnum
    @field:Schema(description = "Regime de alimentação (trate um valor desconhecido sem falhar).")
    val mealPlan: MealPlan,
    val category: RatePlanCategory,
    @field:ExtensibleEnum
    @field:Schema(description = "Canais em que o plano é vendido (trate um valor desconhecido sem falhar).")
    val channels: List<SalesChannel>,
    val roomTypeIds: List<UUID>,
    val packageIds: List<UUID>,
    val cancellationPolicyId: UUID,
    val guaranteePolicyId: UUID,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: RatePlanView) =
            RatePlanResponse(
                view.id.value,
                view.code,
                view.name,
                view.description,
                view.mealPlan,
                view.category,
                view.channels.sorted(),
                view.roomTypeIds.map { it.value }.sorted(),
                view.packageIds.map { it.value }.sorted(),
                view.cancellationPolicyId.value,
                view.guaranteePolicyId.value,
                view.active,
                view.createdAt,
                view.updatedAt,
            )
    }
}

// --- Políticas -----------------------------------------------------------------------------------------------------

@Schema(description = "Os termos de uma política de cancelamento: o prazo e a multa (só o valor do tipo).")
data class CancellationPolicyRequest(
    @field:Schema(example = "Flexível 48 h")
    @field:NotBlank
    @field:Size(max = 80)
    val name: String?,
    @field:Schema(
        description =
            "Prazo em horas antes do horário de check-in da data de chegada (no fuso do hotel): o cancelamento " +
                "feito depois dele paga a multa.",
    )
    @field:NotNull
    @field:Min(0)
    @field:Max(MAX_DEADLINE_HOURS)
    val deadlineHours: Int?,
    @field:NotNull
    val penaltyType: CancellationPenaltyType?,
    @field:Schema(description = "Só em `PERCENT_OF_STAY`: percentual da estadia, de 0 a 100.", example = "50.0000")
    val penaltyPercent: Percent? = null,
    @field:Schema(description = "Só em `NIGHTS`: número de diárias, de 0 a 365.")
    @field:Min(0)
    @field:Max(MAX_DAYS)
    val penaltyNights: Int? = null,
    @field:Schema(description = "Só em `FIXED_AMOUNT`: valor da multa.", example = "150.00")
    val penaltyAmount: Money? = null,
) {
    fun toCommand() =
        CancellationPolicyCommand(
            checkNotNull(name),
            checkNotNull(deadlineHours),
            checkNotNull(penaltyType),
            penaltyPercent,
            penaltyNights,
            penaltyAmount,
        )
}

@Schema(description = "Uma política de cancelamento.")
data class CancellationPolicyResponse(
    val id: UUID,
    val name: String,
    val deadlineHours: Int,
    val penaltyType: CancellationPenaltyType,
    @field:Schema(nullable = true)
    val penaltyPercent: Percent?,
    @field:Schema(nullable = true)
    val penaltyNights: Int?,
    @field:Schema(nullable = true)
    val penaltyAmount: Money?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: CancellationPolicyView) =
            CancellationPolicyResponse(
                view.id.value,
                view.name,
                view.deadlineHours,
                view.penaltyType,
                view.penaltyPercent,
                view.penaltyNights,
                view.penaltyAmount,
                view.active,
                view.createdAt,
                view.updatedAt,
            )
    }
}

@Schema(description = "Os termos de uma política de garantia.")
data class GuaranteePolicyRequest(
    @field:Schema(example = "Depósito de 30%")
    @field:NotBlank
    @field:Size(max = 80)
    val name: String?,
    @field:NotNull
    val type: GuaranteeType?,
    @field:Schema(description = "Só em `DEPOSIT`: percentual da estadia, de mais de 0 a 100.", example = "30.0000")
    val depositPercent: Percent? = null,
    @field:Schema(description = "Só em `DEPOSIT`: até quantos dias antes da chegada, de 0 a 365.")
    @field:Min(0)
    @field:Max(MAX_DAYS)
    val dueDaysBefore: Int? = null,
) {
    fun toCommand() = GuaranteePolicyCommand(checkNotNull(name), checkNotNull(type), depositPercent, dueDaysBefore)
}

@Schema(description = "Uma política de garantia.")
data class GuaranteePolicyResponse(
    val id: UUID,
    val name: String,
    @field:ExtensibleEnum
    @field:Schema(description = "Tipo de garantia (trate um valor desconhecido sem falhar).")
    val type: GuaranteeType,
    @field:Schema(nullable = true)
    val depositPercent: Percent?,
    @field:Schema(nullable = true)
    val dueDaysBefore: Int?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: GuaranteePolicyView) =
            GuaranteePolicyResponse(
                view.id.value,
                view.name,
                view.type,
                view.depositPercent,
                view.dueDaysBefore,
                view.active,
                view.createdAt,
                view.updatedAt,
            )
    }
}

// --- Pacotes -------------------------------------------------------------------------------------------------------

@Schema(description = "Os dados de um pacote.")
data class PackageRequest(
    @field:Schema(description = CODE_DESCRIPTION, example = "CAFE")
    @field:NotBlank
    @field:Size(max = 20)
    val code: String?,
    @field:Schema(example = "Café da manhã")
    @field:NotBlank
    @field:Size(max = 80)
    val name: String?,
    @field:Schema(description = "Preço, de 0,01 a 1.000.000,00, na base de `pricing`.", example = "35.00")
    @field:NotNull
    val price: Money?,
    @field:NotNull
    val pricing: PackagePricing?,
    @field:Schema(description = "`DAILY` ou `ON_ARRIVAL`; o preço por estadia só na chegada.")
    @field:NotNull
    val postingRhythm: PackagePostingRhythm?,
    @field:Schema(description = "Se é incluso na diária (`true`) ou adicional (`false`).")
    val includedInRate: Boolean = false,
    @field:Schema(description = "Código de lançamento de consumo (`CHARGE`) do `billing`, ativo.")
    @field:NotNull
    val transactionCodeId: UUID?,
) {
    fun toCommand() =
        PackageCommand(
            checkNotNull(code),
            checkNotNull(name),
            checkNotNull(price),
            checkNotNull(pricing),
            checkNotNull(postingRhythm),
            includedInRate,
            TransactionCodeId(checkNotNull(transactionCodeId)),
        )
}

@Schema(description = "Um pacote.")
data class PackageResponse(
    val id: UUID,
    val code: String,
    val name: String,
    val price: Money,
    val pricing: PackagePricing,
    val postingRhythm: PackagePostingRhythm,
    val includedInRate: Boolean,
    val transactionCodeId: UUID,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: PackageView) =
            PackageResponse(
                view.id.value,
                view.code,
                view.name,
                view.price,
                view.pricing,
                view.postingRhythm,
                view.includedInRate,
                view.transactionCodeId.value,
                view.active,
                view.createdAt,
                view.updatedAt,
            )
    }
}
